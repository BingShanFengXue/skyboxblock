"""Freeze one JAR; run fixed/control pairs serially to avoid competing GPU clients."""
from pathlib import Path
import subprocess,sys,os,json,hashlib,time
ROOT=Path(__file__).resolve().parents[1]
JAR=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar'
def main():
 identity=hashlib.sha256(JAR.read_bytes()).hexdigest();rows=[];env=os.environ.copy();env['OPENBLAS_NUM_THREADS']='1'
 for mode in ['fsr2','native','vanilla']:
  for negative in [False,True]:
   prefix='r34-control-r2' if negative else 'r34-fixed-r1';label=prefix+'-'+mode
   assert hashlib.sha256(JAR.read_bytes()).hexdigest()==identity,'Frozen package changed'
   cmd=[sys.executable,str(ROOT/'tools/run-r34-edges.py'),'--jar',str(JAR),'--prefix',prefix,'--modes',mode]
   if negative:cmd+=['--negative-surface-layer']
   directory=ROOT/'build/render-regression'/label
   prior=directory/'run-result.json';fixture=directory/'fixture.json'
   reusable=False
   if prior.exists() and fixture.exists():
    run=json.loads(prior.read_text(encoding='utf-8'));f=json.loads(fixture.read_text(encoding='utf-8'))
    reusable=run['exitCode']==0 and run['finished'] and run['scenes']==180 and f['jarSha256']==identity and f.get('negativeSurfaceLayer',False)==negative
   print('RECHECK' if reusable else 'RUN',label,flush=True);code=0 if reusable else subprocess.call(cmd,cwd=ROOT,env=env)
   checker=[sys.executable,str(ROOT/'tools/check-r34-edges.py'),str(ROOT/'build/render-regression'/label/'results'),'--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
   if negative:checker+=['--baseline']
   with (ROOT/'build'/f'{label}-checker.log').open('wb') as log:oracle=subprocess.call(checker,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT) if code==0 else -1
   if negative and oracle==0:
    score=json.loads((ROOT/'build/reports'/f'{label}-oracle.json').read_text(encoding='utf-8'))
    unexpected=[f for f in score['failures'] if f['name']!='bounded geometric edge error']
    if unexpected:print('INVALID CONTROL',unexpected[:5],flush=True);oracle=2
   rows.append(dict(label=label,client=code,oracle=oracle,negativeSurfaceLayer=negative));print('RESULT',rows[-1],flush=True)
   report=dict(jarSha256=identity,runs=rows,complete=len(rows)==6,passed=len(rows)==6 and all(r['client']==0 and r['oracle']==0 for r in rows))
   (ROOT/'build/reports/r34-edge-matrix.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
   if code or oracle:return 1
 return subprocess.call([sys.executable,str(ROOT/'tools/compare-r34-edges.py'),'--baseline','r34-control-r2','--fixed','r34-fixed-r1','--output',str(ROOT/'build/reports/r34-edge-comparison.json')],cwd=ROOT,env=env)
if __name__=='__main__':raise SystemExit(main())
