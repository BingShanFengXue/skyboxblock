"""Frozen R34.2 edge pairs, including disabled-pack Iris/Sodium+DH, then eleven regressions."""
from pathlib import Path
import os,sys,json,hashlib,subprocess
ROOT=Path(__file__).resolve().parents[1]
def main():
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.2.jar';identity=hashlib.sha256(jar.read_bytes()).hexdigest();rows=[];env=os.environ.copy();env['OPENBLAS_NUM_THREADS']='1'
 def scenes(layout):return [f'edge-{layout}-setup']+[f'edge-{layout}-static-{i:02d}' for i in range(32)]+[f'edge-{layout}-walk-{i:02d}' for i in range(24)]+[f'edge-{layout}-reload',f'edge-{layout}-resize',f'edge-{layout}-removed']
 cases=[('dh',['silhouette','foreground']),('iris-off-dh',['silhouette']),('vanilla',['silhouette']),('native',['mixed']),('fsr2',['mixed'])]
 for mode,layouts in cases:
  selection=sum([scenes(layout) for layout in layouts],[])
  for negative in [False,True]:
   prefix='r34-2-edge-control' if negative else 'r34-2-edge-fixed';label=prefix+'-'+mode;directory=ROOT/'build/render-regression'/label
   assert hashlib.sha256(jar.read_bytes()).hexdigest()==identity
   command=[sys.executable,str(ROOT/'tools/run-r34-edges.py'),'--jar',str(jar),'--prefix',prefix,'--modes',mode,'--scenes',','.join(selection)]
   if negative:command+=['--negative-surface-layer']
   print('START',label,flush=True);code=subprocess.call(command,cwd=ROOT,env=env)
   checker=[sys.executable,str(ROOT/'tools/check-r34-edges.py'),str(directory/'results'),'--partial','--output',str(ROOT/'build/reports'/f'{label}-oracle.json')]
   if negative:checker+=['--baseline']
   with (ROOT/'build'/f'{label}-checker.log').open('wb') as log:oracle=subprocess.call(checker,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT) if code==0 else -1
   if code or oracle:raise RuntimeError((label,code,oracle))
   score=json.loads((ROOT/'build/reports'/f'{label}-oracle.json').read_text(encoding='utf-8'));run=json.loads((directory/'run-result.json').read_text(encoding='utf-8'))
   assert score['scenes']==run['scenes']==len(selection)
   assert {p.stem for p in (directory/'results').glob('edge-*.json')}==set(selection)
   if negative:assert all(f['name']=='bounded geometric edge error' for f in score['failures'])
   if not negative and mode in ['dh','iris-off-dh']:
    metadata=[json.loads(p.read_text(encoding='utf-8')) for p in (directory/'results').glob('edge-*.json') if not p.stem.endswith('removed')]
    assert all(m['nativeBackgroundReady'] and m['nativeBackgroundColorTexture']>0 for m in metadata),'Supported native DH silently fell back'
   rows.append(dict(label=label,client=code,oracle=oracle,negativeSurfaceLayer=negative,scenes=len(selection),mode=mode));print('DONE',rows[-1],flush=True)
   complete=len(rows)==len(cases)*2
   (ROOT/'build/reports/r34-2-edge-matrix.json').write_text(json.dumps(dict(jarSha256=identity,complete=complete,passed=complete,runs=rows),indent=2)+'\n',encoding='utf-8')
 print('START ELEVEN REGRESSION GROUPS',flush=True)
 return subprocess.call([sys.executable,str(ROOT/'tools/run-r34-regressions.py'),'--jar',str(jar),'--prefix','r34-2-regression'],cwd=ROOT,env=env)
if __name__=='__main__':raise SystemExit(main())
