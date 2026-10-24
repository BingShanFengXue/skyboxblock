#!/usr/bin/env python3
"""Serial real-DH matrix: no competing GPU fixtures, no portals, no acceptance by exit code alone."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, subprocess, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_checker',ROOT/'tools/check-r30-probe.py')
checker=importlib.util.module_from_spec(spec); spec.loader.exec_module(checker)
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,required=True); p.add_argument('--label-prefix',required=True)
    p.add_argument('--probe',type=Path,default=ROOT/'build/libs/skybox-validation-probe-r30.2.jar')
    a=p.parse_args(); jar=a.jar.resolve(strict=True); probe=a.probe.resolve(strict=True)
    pin=sha(jar); pp=sha(probe); report=ROOT/'build/reports'/(a.label_prefix+'-matrix')
    report.mkdir(exist_ok=False)
    timeline=report/'timeline.jsonl'; stages=[]
    for env,profile,shader,fsr in [('dh','dh',None,-1),('native','iris-dh','iterationRP Alpha 0.8.31.zip',-1),('fsr','iris-dh','iterationRP Alpha 0.8.31.zip',2)]:
        label=a.label_prefix+'-'+env; directory=ROOT/'build/render-regression'/label
        args=[sys.executable,str(ROOT/'tools/run-r30-probe.py'),'--mode','dynamic','--profile',profile,'--terrain','normal',
              '--warmup','45','--sample','30','--jar',str(jar),'--probe',str(probe),'--label',label,'--timeout','1500','--gc','g1']
        if shader:args+=['--shader',shader,'--fsr',str(fsr)]
        assert sha(jar)==pin and sha(probe)==pp,'Pinned candidate/probe changed'
        if directory.exists():raise RuntimeError('Existing fixture is not silently re-run: '+str(directory))
        entry=dict(environment=env,label=label,started=time.time(),command=args,candidateSha256=pin,probeSha256=pp)
        with timeline.open('a',encoding='utf-8') as f:f.write(json.dumps(dict(event='start',**entry))+'\n')
        print('START',env,label,flush=True)
        with (report/(env+'-execution.log')).open('xb') as log:code=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT).returncode
        entry.update(exitCode=code,finished=time.time())
        try:
            checked=checker.verify(directory)
            with (report/(env+'-verification.json')).open('x',encoding='utf-8') as f:json.dump(checked,f,indent=2)
            entry.update(evidencePassed=checked['passed'],checks=checked['counts'])
        except Exception as error:entry.update(evidencePassed=False,verificationError=repr(error))
        stages.append(entry)
        with timeline.open('a',encoding='utf-8') as f:f.write(json.dumps(dict(event='finish',**entry))+'\n')
        print('FINISH',env,'exit',code,'evidence',entry.get('evidencePassed'),'checks',entry.get('checks'),flush=True)
        if code!=0 or not entry.get('evidencePassed'):break
    complete=len(stages)==3 and all(x['exitCode']==0 and x.get('evidencePassed') for x in stages)
    summary=dict(status='MATRIX_MEASUREMENTS_VALID' if complete else 'MATRIX_OPEN_ITEMS',complete=complete,stages=stages,
                 candidateSha256=pin,probeSha256=pp,dynamicDhAccepted=False,performanceAccepted=False,releaseAccepted=False,
                 meaning='Real dynamic inputs, depth, GPU geometry, state and release evidence; explicit image occlusion acceptance remains separate')
    with (report/'summary.json').open('x',encoding='utf-8') as f:json.dump(summary,f,indent=2)
    print(json.dumps(summary,indent=2),flush=True); return 0 if complete else 1
if __name__=='__main__':raise SystemExit(main())
