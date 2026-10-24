#!/usr/bin/env python3
"""Serial final-DH and bounded performance evidence, conditional on the actual native Iris ordering oracle."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,os,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def load(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'tools'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
runner=load('r30_final_runner','run-r30-probe.py');check=load('r30_final_check','check-r30-probe.py');order=load('r30_final_order','check-r30-lod-occlusion.py')
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(p,d):
    with Path(p).open('x',encoding='utf-8') as f:json.dump(d,f,indent=2)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--prefix',required=True)
    p.add_argument('--after-check',type=Path,required=True)
    p.add_argument('--probe',type=Path,default=ROOT/'build/libs/skybox-validation-probe-r30.6.jar');a=p.parse_args();jar=a.jar.resolve(strict=True)
    probe=a.probe.resolve(strict=True);pin=sha(jar);pp=sha(probe)
    report=ROOT/'build/reports'/(a.prefix+'-validation');report.mkdir(exist_ok=False)
    save(report/'controller.json',dict(pid=os.getpid(),candidateSha256=pin,probeSha256=pp,dependency=str(a.after_check)))
    print('QUEUED oracle:',a.after_check,flush=True);waiting=time.monotonic()
    while not a.after_check.exists():
        if time.monotonic()-waiting>1800:raise TimeoutError('Required ordering report not produced')
        time.sleep(10)
    prerequisite=json.loads(a.after_check.read_text(encoding='utf-8'));save(report/'prerequisite.json',prerequisite)
    if not prerequisite.get('passed'):raise RuntimeError('Actual ordering gate is open; do not start downstream benchmark')
    stages=[]
    def stage(name,args,ordering=False):
        assert sha(jar)==pin and sha(probe)==pp,'Frozen candidate/probe changed'
        label=a.prefix+'-'+name;path=ROOT/'build/render-regression'/label
        if path.exists():raise RuntimeError('Do not duplicate or overwrite '+label)
        command=[sys.executable,*args,'--jar',str(jar),'--label',label]
        print('START',name,flush=True);entry=dict(name=name,label=label,started=time.time(),command=command)
        with (report/(name+'-execution.log')).open('xb') as log:entry['exitCode']=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT).returncode
        try:
            evidence=check.verify(path);save(report/(name+'-evidence.json'),evidence);entry['evidencePassed']=evidence['passed'];entry['evidenceCounts']=evidence['counts']
            if ordering:
                proof=order.evaluate(path);save(report/(name+'-ordering.json'),proof);entry['orderingPassed']=proof['passed'];entry['orderingCounts']=proof['counts']
        except Exception as error:entry.update(evidencePassed=False,error=repr(error))
        entry['finished']=time.time();stages.append(entry)
        with (report/'timeline.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(entry)+'\n')
        print('FINISH',json.dumps(entry),flush=True)
        if entry['exitCode']!=0 or not entry.get('evidencePassed') or (ordering and not entry.get('orderingPassed')):
            save(report/'open-stage.json',entry);raise RuntimeError('Retained failed stage '+name)
    lod=[str(ROOT/'tools/run-r30-lod-occlusion.py'),'--profile','iris-dh','--shader','iterationRP Alpha 0.8.31.zip','--fsr','2','--probe',str(probe),
         '--gold-z','208','--behind-distance','240','--front-distance','192']
    stage('lod-fsr2',lod,True)
    # Identical 13 normal scene identities; targeted final-JAR retest, not the
    # earlier 45+30s collection masquerading as same-duration re-execution.
    for env,profile,fsr in [('dh','dh',None),('native','iris-dh',-1),('fsr2','iris-dh',2)]:
        args=[str(ROOT/'tools/run-r30-probe.py'),'--mode','dynamic','--profile',profile,'--terrain','normal','--warmup','15','--sample','15','--probe',str(probe),'--timeout','1100']
        if fsr is not None:args+=['--shader','iterationRP Alpha 0.8.31.zip','--fsr',str(fsr)]
        stage('dynamic-'+env,args)
    for name,profile,fsr,never,cycles,hold in [
        ('performance-native','iris-dh',-1,False,3,1200),
        ('performance-native-never-sky','iris-dh',-1,True,3,1200),
        ('performance-vanilla','vanilla',None,False,1,180),
        ('performance-fsr2','iris-dh',2,False,1,180)]:
        args=[str(ROOT/'tools/run-r30-probe.py'),'--mode','performance','--profile',profile,'--terrain','normal','--warmup','15','--sample','20','--cycles',str(cycles),
              '--hold-seconds',str(hold),'--probe',str(probe),'--gc','zgc','--timeout',str(hold+1800)]
        if fsr is not None:args+=['--shader','iterationRP Alpha 0.8.31.zip','--fsr',str(fsr)]
        if never:args+=['--never-sky']
        stage(name,args)
    result=dict(status='FINAL_MEASUREMENTS_COMPLETE',complete=True,stages=stages,candidateSha256=pin,probeSha256=pp,
                performanceAnalysisRequired=True,independentAuditCompleted=False,releaseAccepted=False)
    save(report/'summary.json',result);print(json.dumps(result,indent=2),flush=True);return 0
if __name__=='__main__':raise SystemExit(main())
