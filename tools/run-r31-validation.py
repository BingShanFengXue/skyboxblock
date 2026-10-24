#!/usr/bin/env python3
"""Sequential isolated-client R31 matrix; saves every command/result and identical JAR identity."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--prefix',default='r31-final');parser.add_argument('--jar',type=Path,default=ROOT/'build/libs/skyboxblock-1.0.3-test.1-r31.jar');parser.add_argument('--negative-only',action='store_true');args=parser.parse_args()
    jar=args.jar.resolve(); identity=hashlib.sha256(jar.read_bytes()).hexdigest()
    modes=[('native','iris-dh','-1'),('fsr2','iris-dh','2'),('vanilla','vanilla',None),('dh','dh',None)]
    runs=[]
    if args.negative_only:modes=[]
    for env,profile,fsr in modes:
        label=args.prefix+'-'+env;log=ROOT/'build'/f'{label}.log'
        cmd=[sys.executable,str(ROOT/'tools/render-regression.py'),'--profile',profile,'--suite','materials','--jar',str(jar),'--label',label,'--timeout','1200','--heap','6G']
        if fsr is not None:cmd+=['--shader','iterationRP Alpha 0.8.31.zip','--shader-settings','instance','--shader-option','FSR2_SCALE='+fsr,'--shader-option','MOTION_BLUR=false']
        print('START',label,flush=True);start=time.time()
        with log.open('wb') as stream:code=subprocess.call(cmd,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        directory=ROOT/'build/render-regression'/label
        checker=[sys.executable,str(ROOT/'tools/check-r31-materials.py'),str(directory/'results'),'--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
        with log.open('ab') as stream:checkcode=subprocess.call(checker,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT) if code==0 else -1
        runs.append(dict(label=label,command=cmd,returnCode=code,oracleReturnCode=checkcode,seconds=round(time.time()-start,2)))
        report=dict(jar=str(jar),jarSha256=identity,runs=runs,complete=False)
        (ROOT/'build/reports'/f'{args.prefix}-matrix.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('DONE',label,'client',code,'oracle',checkcode,flush=True)
        if code or checkcode:return 1
    # Runtime negative: same exact binary, only material ownership deliberately disabled.
    label=args.prefix+'-negative-ownership';log=ROOT/'build'/f'{label}.log'
    cmd=[sys.executable,str(ROOT/'tools/render-regression.py'),'--profile','iris','--suite','materials','--jar',str(jar),'--label',label,'--timeout','420','--shader','iterationRP Alpha 0.8.31.zip','--shader-settings','instance','--shader-option','FSR2_SCALE=2','--shader-option','MOTION_BLUR=false','--negative-material-ownership','--scenes','material-mixed,material-mixed-reload,material-mixed-resized,material-mixed-removed']
    print('START',label,flush=True)
    with log.open('wb') as stream:code=subprocess.call(cmd,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
    checker=[sys.executable,str(ROOT/'tools/check-r31-materials.py'),str(ROOT/'build/render-regression'/label/'results'),'--partial','--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
    with log.open('ab') as stream:checkcode=subprocess.call(checker,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT) if code==0 else -1
    negative=code==0 and checkcode==1
    runs.append(dict(label=label,command=cmd,returnCode=code,oracleReturnCode=checkcode,expectedFailureObserved=negative))
    report=dict(jar=str(jar),jarSha256=identity,runs=runs,complete=True,passed=all(r['returnCode']==0 and (r.get('expectedFailureObserved') or r['oracleReturnCode']==0) for r in runs))
    (ROOT/'build/reports'/f'{args.prefix}-matrix.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2),flush=True)
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
