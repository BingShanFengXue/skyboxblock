"""Seal final R34.2 same-binary identities, scoped checks, errors and single-backdrop evidence."""
from pathlib import Path
import json,hashlib,importlib.util,numpy as np
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 reports=ROOT/'build/reports';jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.2.jar';identity=sha(jar);edge=load(reports/'r34-2-edge-matrix.json');reg=load(reports/'r34-2-regression-matrix.json');package=load(reports/'r34-2-package-audit.json');lod=load(reports/'r34-2-lod-background-oracle.json')
 assert edge['complete'] and edge['passed'] and reg['complete'] and reg['passed'] and package['passed'] and lod['passed']
 assert edge['jarSha256']==reg['jarSha256']==package['jarSha256']==lod['productionJarSha256']==identity
 rows=[];positive=control=checks=0
 for r in edge['runs']+reg['runs']:
  d=ROOT/'build/render-regression'/r['label'];f=load(d/'fixture.json');run=load(d/'run-result.json');q=load(reports/f'{r["label"]}-oracle.json')
  assert r['client']==r['oracle']==run['exitCode']==0 and run['finished'] and not run['fatalErrors'] and not run['renderThreadViolations']
  assert f['jarSha256']==identity and sha(next((d/'mods').glob('skyboxblock-*.jar')))==identity
  network=[x for x in run['errors'] if 'Yggdrasil Key Fetcher/ERROR' in x and 'Failed to request yggdrasil public key' in x]
  assert network==run['errors'],(r['label'],run['errors'])
  negative=f.get('negativeSurfaceLayer',False)
  if negative:control+=run['scenes'];assert all(x['name']=='bounded geometric edge error' for x in q['failures'])
  else:positive+=run['scenes'];checks+=q['checks'];assert q['passed']
  rows.append(dict(label=r['label'],scenes=run['scenes'],checks=q['checks'],negativeSurfaceLayer=negative,fatalErrors=run['fatalErrors'],renderThreadViolations=run['renderThreadViolations'],runtimeLogErrors=run['errors'],externalNetworkErrors=network,passed=True))
 comparisons=[]
 for mode,layout in [('dh','silhouette'),('iris-off-dh','silhouette'),('vanilla','silhouette'),('native','mixed'),('fsr2','mixed')]:
  fixed=load(reports/f'r34-2-edge-fixed-{mode}-oracle.json');old=load(reports/f'r34-2-edge-control-{mode}-oracle.json')
  for phase in ['static','walk']:
   metric=lambda q:float(np.mean([x['edgeMAE'] for x in q['results'] if x['layout']==layout and f'-{phase}-' in x['scene']]))
   a,b=metric(old),metric(fixed);assert b<a*.75
   comparisons.append(dict(mode=mode,layout=layout,phase=phase,controlMAE=a,fixedMAE=b,reduction=1-b/a))
 spec=importlib.util.spec_from_file_location('compare',ROOT/'tools/compare-r34-edges.py');comp=importlib.util.module_from_spec(spec);spec.loader.exec_module(comp);temporal=[]
 for mode in ['native','fsr2']:
  a=comp.temporal(ROOT/f'build/render-regression/r34-2-edge-control-{mode}/results','mixed');b=comp.temporal(ROOT/f'build/render-regression/r34-2-edge-fixed-{mode}/results','mixed');assert a['sameCamera'] and b['sameCamera'] and b['meanTemporalStd']==0
  temporal.append(dict(mode=mode,control=a,fixed=b))
 hands=[load(reports/f'r34-2-regression-handexposure-{mode}-oracle.json') for mode in ['native','fsr2']]
 assert 'PASS: 389385 assertions' in (ROOT/'build/r34-2-native-background-build.log').read_text(encoding='utf-8')
 result=dict(version='1.0.3-test.4-r34.2',jarSha256=identity,bytes=jar.stat().st_size,passed=True,positiveScenes=positive,positiveChecks=checks,controlScenes=control,totalScenes=positive+control,realLodPhases=5,realLodOrderingChecks=lod['ordering']['counts'],nativeBackgroundEvidence=lod['stages'],mathAssertions=389385,packageChecks=package['checks'],maximumHandWhiteFraction248=max(x['whiteFraction248'] for q in hands for x in q['measurements']),comparisons=comparisons,temporal=temporal,runs=rows,
  scope='MC1.21.1/Fabric; Iris1.8.1/Sodium0.6.7/ITRP Alpha0.8.31 native and FSR2; native DH3.3.3 and disabled-pack Iris+Sodium+DH; root first-person view',
  limits=['Four-sample generated-sky geometry, not full-scene MSAA/TAA.','Native backdrop reconstruction is pinned to verified native DH3.3.3 apply-source/depth, matching frame/size/format; unsupported inputs use original center ownership.','Iris partial exterior silhouette is outside mixed-material all-sky MAE.','MOTION_BLUR=false in fixed shader tests; original config untouched.','Historical strict transparency restoration four red items, portal work and performance/resource long runs remain outside this acceptance.'])
 (reports/'r34-2-final-acceptance.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in result.items() if k!='runs'},indent=2));return 0
if __name__=='__main__':raise SystemExit(main())
