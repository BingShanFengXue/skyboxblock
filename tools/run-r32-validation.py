#!/usr/bin/env python3
"""R32 moving-camera suite plus explicitly scoped R31 foreground/reload regressions."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import time
ROOT=Path(__file__).resolve().parents[1]
STYLES=['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']

def load(path):return json.loads(path.read_text(encoding='utf-8'))
def main():
    p=argparse.ArgumentParser();p.add_argument('--prefix',default='r32-final-r1');p.add_argument('--reuse-fsr2',default='');a=p.parse_args()
    jar=ROOT/'build/libs/skyboxblock-1.0.3-test.2-r32.jar';identity=hashlib.sha256(jar.read_bytes()).hexdigest();runs=[]
    report=ROOT/'build/reports'/f'{a.prefix}-matrix.json'
    def save(complete=False):
        report.write_text(json.dumps(dict(jar=str(jar),jarSha256=identity,runs=runs,complete=complete,passed=complete and all(r['client']==0 and r['oracle']==0 for r in runs)),indent=2)+'\n',encoding='utf-8')
    def execute(label,profile,fsr,suite,scenes=None):
        directory=ROOT/'build/render-regression'/label;log=ROOT/'build'/f'{label}.log'
        args=[sys.executable,str(ROOT/'tools/render-regression.py'),'--profile',profile,'--suite',suite,'--jar',str(jar),'--label',label,'--timeout','1200']
        if fsr is not None:args+=['--shader','iterationRP Alpha 0.8.31.zip','--shader-settings','instance','--shader-option',f'FSR2_SCALE={fsr}','--shader-option','MOTION_BLUR=false']
        if scenes:args+=['--scenes',','.join(scenes)]
        print('START',label,flush=True);started=time.monotonic()
        with log.open('wb') as stream:code=subprocess.call(args,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        checker=[sys.executable,str(ROOT/'tools/check-r32-motion.py' if suite=='panomotion' else ROOT/'tools/check-r31-materials.py'),str(directory/'results'),'--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
        checker+=['--annotate'] if suite=='panomotion' else ['--partial']
        with log.open('ab') as stream:oracle=subprocess.call(checker,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT) if code==0 else -1
        runs.append(dict(label=label,client=code,oracle=oracle,suite=suite,command=args,seconds=round(time.monotonic()-started,2)))
        save();print('DONE',label,code,oracle,flush=True)
        if code or oracle:raise RuntimeError('Validation failed: '+label)
    modes=[('vanilla','vanilla',None),('dh','dh',None),('native','iris-dh','-1'),('fsr2','iris-dh','2')]
    for env,profile,fsr in modes:
        if env=='fsr2' and a.reuse_fsr2:
            d=ROOT/'build/render-regression'/a.reuse_fsr2;fixture=load(d/'fixture.json');run=load(d/'run-result.json');oracle=load(ROOT/'build/reports'/f'{a.reuse_fsr2}-oracle.json')
            assert fixture['jarSha256']==identity and run['exitCode']==0 and run['finished'] and oracle['passed'] and oracle['scenes']==82
            runs.append(dict(label=a.reuse_fsr2,client=0,oracle=0,suite='panomotion',reusedSameBinary=True));save()
        else:execute(a.prefix+'-motion-'+env,profile,fsr,'panomotion')
        selection=[f'material-{s}-{view}' for s in STYLES for view in ['front','foreground']]+['material-mixed','material-mixed-reload','material-mixed-resized','material-mixed-removed']
        execute(a.prefix+'-static-'+env,profile,fsr,'materials',selection)
    negative=load(ROOT/'build/reports/r32-motion-legacy-fsr2-r2-oracle.json');nrun=load(ROOT/'build/render-regression/r32-motion-legacy-fsr2-r2/run-result.json');nfixture=load(ROOT/'build/render-regression/r32-motion-legacy-fsr2-r2/fixture.json')
    assert nfixture['jarSha256']==identity and nfixture['negativePanoramaRays'] and nrun['finished'] and nrun['exitCode']==0
    assert negative['failedChecks']==8 and all(x['name']=='depth independent panorama' for x in negative['failures'])
    save(True);print(report.read_text(encoding='utf-8'),flush=True)
    return 0
if __name__=='__main__':raise SystemExit(main())
