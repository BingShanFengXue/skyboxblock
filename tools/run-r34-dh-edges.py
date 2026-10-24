"""Supplemental native-DH outer silhouettes, including actual end-of-level color recovery."""
from pathlib import Path
import json,subprocess,sys,os,hashlib,importlib.util
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
def main():
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';identity=hashlib.sha256(jar.read_bytes()).hexdigest();env=os.environ.copy();env['OPENBLAS_NUM_THREADS']='1';rows=[]
 scenes=['edge-silhouette-setup']+[f'edge-silhouette-static-{i:02d}' for i in range(32)]+[f'edge-silhouette-walk-{i:02d}' for i in range(24)]+['edge-silhouette-reload','edge-silhouette-resize','edge-silhouette-removed']
 for negative in [False,True]:
  label='r34-dh-control-r1-dh' if negative else 'r34-dh-fixed-r1-dh';prefix=label[:-3]
  cmd=[sys.executable,str(ROOT/'tools/run-r34-edges.py'),'--jar',str(jar),'--prefix',prefix,'--modes','dh','--scenes',','.join(scenes)]
  if negative:cmd+=['--negative-surface-layer']
  print('START DH',label,flush=True)
  code=subprocess.call(cmd,cwd=ROOT,env=env)
  directory=ROOT/'build/render-regression'/label
  check=[sys.executable,str(ROOT/'tools/check-r34-edges.py'),str(directory/'results'),'--partial','--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
  if negative:check+=['--baseline']
  with (ROOT/'build'/f'{label}-checker.log').open('wb') as log:oracle=subprocess.call(check,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT) if code==0 else -1
  if code or oracle:raise RuntimeError((label,code,oracle))
  score=json.loads((ROOT/'build/reports'/f'{label}-oracle.json').read_text(encoding='utf-8'));fixture=json.loads((directory/'fixture.json').read_text(encoding='utf-8'))
  assert fixture['jarSha256']==identity and score['scenes']==60
  unexpected=[f for f in score['failures'] if f['name']!='bounded geometric edge error'];assert not unexpected,unexpected
  recovered=sum(json.loads(p.read_text(encoding='utf-8'))['finalResolved'] for p in (directory/'results').glob('edge-*.json'));assert recovered>=32,recovered
  rows.append(dict(label=label,client=code,oracle=oracle,negativeSurfaceLayer=negative,lateRecoveredFrames=recovered,scenes=60));print('DONE DH',rows[-1],flush=True)
 # Establish that the pre-sky diagnostic is a valid independent WORLD-SKY background at these upper/side silhouettes.
 spec=importlib.util.spec_from_file_location('edges',ROOT/'tools/check-r34-edges.py');edges=importlib.util.module_from_spec(spec);spec.loader.exec_module(edges);background=[]
 for row in rows:
  directory=ROOT/'build/render-regression'/row['label']/'results'
  for scene in ['edge-silhouette-static-00','edge-silhouette-walk-05','edge-silhouette-resize']:
   d=json.loads((directory/(scene+'.json')).read_text(encoding='utf-8'));actual=edges.image(directory/(scene+'.png'));before=edges.image(directory/(scene+'-before.png'));h,w=actual.shape[:2];y,x=np.mgrid[:h,:w];owner,_=edges.ownership(d,x+.5,y+.5,w,h,'silhouette');mask=edges.base.interior(owner==-1,21)&(y<h*.6)
   assert mask.sum()>1000
   mae=float(np.abs(actual-before)[mask].mean());assert mae<.5,(row['label'],scene,mae)
   background.append(dict(label=row['label'],scene=scene,pixels=int(mask.sum()),backgroundMAE=mae))
 scores=[json.loads((ROOT/'build/reports'/f'{r["label"]}-oracle.json').read_text(encoding='utf-8')) for r in rows]
 means=[float(np.mean([x['edgeMAE'] for x in q['results'] if '-walk-' in x['scene']])) for q in scores];gain=1-means[0]/means[1];assert gain>.25,(means,gain)
 result=dict(jarSha256=identity,complete=True,passed=True,runs=rows,fixedWalkEdgeMAE=means[0],controlWalkEdgeMAE=means[1],reduction=gain,independentBackgroundChecks=background)
 (ROOT/'build/reports/r34-dh-edge-matrix.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2))
 return 0
if __name__=='__main__':raise SystemExit(main())
