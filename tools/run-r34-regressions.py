"""Targeted same-binary foreground/reload, prior ray motion, and R33 exposure regressions."""
from pathlib import Path
import hashlib,json,subprocess,sys,os,time,argparse
ROOT=Path(__file__).resolve().parents[1]
STYLES=['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']
def main():
 p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,default=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar');p.add_argument('--prefix',default='r34-regression');a=p.parse_args()
 jar=a.jar.resolve();identity=hashlib.sha256(jar.read_bytes()).hexdigest();runs=[];env=os.environ.copy();env['OPENBLAS_NUM_THREADS']='1'
 def execute(mode,profile,fsr,suite,scenes=None,material=None):
  assert hashlib.sha256(jar.read_bytes()).hexdigest()==identity
  label=f'{a.prefix}-{suite}-{mode}';directory=ROOT/'build/render-regression'/label
  cmd=[sys.executable,str(ROOT/'tools/render-regression.py'),'--jar',str(jar),'--profile',profile,'--suite',suite,'--label',label,'--timeout','1200']
  if fsr is not None:cmd+=['--shader','iterationRP Alpha 0.8.31.zip','--shader-settings','instance','--shader-option',f'FSR2_SCALE={fsr}','--shader-option','MOTION_BLUR=false']
  if scenes:cmd+=['--scenes',','.join(scenes)]
  if material:cmd+=['--hand-material',material]
  print('START',label,flush=True)
  with (ROOT/'build'/f'{label}.log').open('wb') as f:code=subprocess.call(cmd,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT)
  checker='check-r31-materials.py' if suite=='materials' else ('check-r32-motion.py' if suite=='panomotion' else 'check-r33-hand.py')
  check=[sys.executable,str(ROOT/'tools'/checker),str(directory if suite=='handexposure' else directory/'results'),'--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
  if suite!='handexposure':check+=['--partial']
  with (ROOT/'build'/f'{label}-checker.log').open('wb') as f:oracle=subprocess.call(check,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT) if code==0 else -1
  runs.append(dict(label=label,client=code,oracle=oracle,command=cmd));print('DONE',runs[-1],flush=True)
  (ROOT/'build/reports'/f'{a.prefix}-matrix.json').write_text(json.dumps(dict(jarSha256=identity,runs=runs,complete=len(runs)==11,passed=len(runs)==11 and all(r['client']==0 and r['oracle']==0 for r in runs)),indent=2)+'\n',encoding='utf-8')
  if code or oracle:raise RuntimeError(label)
 selection=[f'material-{s}-{v}' for s in STYLES for v in ['front','foreground']]+['material-mixed','material-mixed-reload','material-mixed-resized','material-mixed-removed']
 motion=[n for s in ['vanilla','kloppenheim','galaxy'] for n in [f'motion-{s}-setup']+[f'motion-{s}-{i:02d}' for i in range(8)]]+['motion-clean']
 for mode,profile,fsr in [('vanilla','vanilla',None),('iris-off','iris',None),('dh','dh',None),('native','iris-dh','-1'),('fsr2','iris-dh','2')]:
  execute(mode,profile,fsr,'materials',selection)
  if mode!='dh':execute(mode,profile,fsr,'panomotion',motion)
  if fsr is not None:execute(mode,profile,fsr,'handexposure',material='mixed')
 return 0
if __name__=='__main__':raise SystemExit(main())
