#!/usr/bin/env python3
"""Same-binary dark/mixed room exposure timelines; original sky and color/foreground controls."""
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]

def read(p):return json.loads(p.read_text(encoding='utf-8'))
def main():
    p=argparse.ArgumentParser();p.add_argument('--prefix',default='r33-final-r1');a=p.parse_args()
    jar=ROOT/'build/libs/skyboxblock-1.0.3-test.3-r33.jar';identity=hashlib.sha256(jar.read_bytes()).hexdigest();runs=[];output=ROOT/'build/reports'/f'{a.prefix}-matrix.json'
    def save(complete=False):output.write_text(json.dumps(dict(jar=str(jar),jarSha256=identity,runs=runs,complete=complete,passed=complete and all(r['client']==0 and r['oracle']==0 for r in runs)),indent=2)+'\n',encoding='utf-8')
    def execute(label,fsr,material='black',negative=False,static=False):
        path=ROOT/'build/render-regression'/label;log=ROOT/'build'/f'{label}.log'
        cmd=[sys.executable,str(ROOT/'tools/render-regression.py'),'--profile','iris-dh','--jar',str(jar),'--label',label,'--shader','iterationRP Alpha 0.8.31.zip','--shader-settings','instance','--shader-option',f'FSR2_SCALE={fsr}','--shader-option','MOTION_BLUR=false','--timeout','700']
        if static:
            scenes=[f'material-{s}-{v}' for s in ['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse'] for v in ['front','foreground']]+['material-mixed','material-mixed-reload','material-mixed-resized','material-mixed-removed']
            cmd+=['--suite','materials','--scenes',','.join(scenes)]
            check=[sys.executable,str(ROOT/'tools/check-r31-materials.py'),str(path/'results'),'--partial','--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
        else:
            cmd+=['--suite','handexposure','--hand-material',material]
            if negative:cmd+=['--negative-dark-meter']
            check=[sys.executable,str(ROOT/'tools/check-r33-hand.py'),str(path),'--expect','negative' if negative else ('control' if material=='sky' else 'fixed'),'--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
        print('START',label,flush=True)
        with log.open('wb') as f:client=subprocess.call(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
        with log.open('ab') as f:oracle=subprocess.call(check,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT) if client==0 else -1
        runs.append(dict(label=label,client=client,oracle=oracle,fsr=fsr,material=material,negative=negative,static=static,command=cmd));save()
        print('DONE',label,client,oracle,flush=True)
        if client or oracle:raise RuntimeError(label)
    existing=ROOT/'build/render-regression/r33-fixed-black-fsr2-r1';old=read(existing/'fixture.json');score=read(ROOT/'build/reports/r33-fixed-black-fsr2-r1-oracle.json')
    assert old['jarSha256']==identity and score['passed'] and score['scenes']==15
    runs.append(dict(label='r33-fixed-black-fsr2-r1',client=0,oracle=0,fsr='2',material='black',negative=False,static=False,reusedSameBinary=True));save()
    for fsr,env in [('2','fsr2'),('-1','native')]:
        for material in ['black','galaxy','apocalypse','mixed','sky']:
            if fsr=='2' and material=='black':continue
            execute(f'{a.prefix}-{material}-{env}',fsr,material)
        execute(f'{a.prefix}-foreground-{env}',fsr,static=True)
    execute(f'{a.prefix}-negative-black-fsr2','2',negative=True)
    save(True);print(output.read_text(encoding='utf-8'),flush=True)
if __name__=='__main__':main()
