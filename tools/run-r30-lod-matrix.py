#!/usr/bin/env python3
"""Queue the serial causal LOD matrix after the normal matrix; never compete for the GPU."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, subprocess, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file); value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
checker=module('r30_lod_input_checker','check-r30-probe.py')
oracle=module('r30_lod_order_checker','check-r30-lod-occlusion.py')
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def save(path,value):
    with Path(path).open('x',encoding='utf-8') as f:json.dump(value,f,indent=2)

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--label-prefix',required=True); p.add_argument('--after-matrix',type=Path)
    a=p.parse_args(); jar=a.jar.resolve(strict=True); probe=ROOT/'build/libs/skybox-validation-probe-r30.3.jar'
    pin=sha(jar); pp=sha(probe); report=ROOT/'build/reports'/(a.label_prefix+'-matrix'); report.mkdir(exist_ok=False)
    save(report/'controller.json',dict(pid=os.getpid(),candidateSha256=pin,probeSha256=pp,dependency=str(a.after_matrix) if a.after_matrix else None))
    if a.after_matrix:
        print('QUEUED: wait for normal matrix',a.after_matrix,flush=True); start=time.monotonic()
        while not a.after_matrix.exists():
            if time.monotonic()-start>4000:raise TimeoutError('Normal matrix did not produce completion evidence')
            time.sleep(10)
        prerequisite=json.loads(a.after_matrix.read_text(encoding='utf-8'))
        save(report/'prerequisite.json',dict(path=str(a.after_matrix),sha256=sha(a.after_matrix),actual=prerequisite))
        if not prerequisite.get('complete') or prerequisite.get('candidateSha256')!=pin:raise RuntimeError('Normal matrix did not validate the pinned candidate')
    stages=[]
    for env,profile,shader,fsr in [('dh','dh',None,-1),('native','iris-dh','iterationRP Alpha 0.8.31.zip',-1),('fsr','iris-dh','iterationRP Alpha 0.8.31.zip',2)]:
        assert sha(jar)==pin and sha(probe)==pp,'Pinned JAR changed'
        label=a.label_prefix+'-'+env; directory=ROOT/'build/render-regression'/label
        if directory.exists():raise RuntimeError('Do not overwrite or duplicate a fixture: '+label)
        args=[sys.executable,str(ROOT/'tools/run-r30-lod-occlusion.py'),'--label',label,'--profile',profile,'--jar',str(jar)]
        if shader:args+=['--shader',shader,'--fsr',str(fsr)]
        print('START',env,label,flush=True); entry=dict(environment=env,label=label,started=time.time(),command=args)
        with (report/(env+'-execution.log')).open('xb') as log:entry['exitCode']=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT).returncode
        try:
            evidence=checker.verify(directory); save(report/(env+'-inputs.json'),evidence); entry['inputsPassed']=evidence['passed']
            ordering=oracle.evaluate(directory); save(report/(env+'-ordering.json'),ordering); entry['orderingPassed']=ordering['passed']; entry['checks']=ordering['counts']
        except Exception as error:entry.update(inputsPassed=False,orderingPassed=False,error=repr(error))
        entry['finished']=time.time(); stages.append(entry)
        with (report/'timeline.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(entry)+'\n')
        print('FINISH',json.dumps(entry),flush=True)
        if entry['exitCode']!=0 or not entry.get('inputsPassed') or not entry.get('orderingPassed'):break
    passed=len(stages)==3 and all(s['exitCode']==0 and s.get('inputsPassed') and s.get('orderingPassed') for s in stages)
    result=dict(status='CONTROLLED_LOD_MATRIX_VERIFIED' if passed else 'CONTROLLED_LOD_MATRIX_OPEN',passed=passed,stages=stages,candidateSha256=pin,probeSha256=pp,releaseAccepted=False)
    save(report/'summary.json',result); print(json.dumps(result,indent=2),flush=True); return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
