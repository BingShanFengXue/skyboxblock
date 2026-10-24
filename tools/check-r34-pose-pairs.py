from pathlib import Path
import json,hashlib,numpy as np
ROOT=Path(__file__).resolve().parents[1]
rows=[];configuration_pairs=[]
for env in ['fsr2','native','vanilla']:
 roots=[ROOT/'build/render-regression'/f'{prefix}-{env}' for prefix in ['r34-control-r2','r34-fixed-r1']]
 fixtures=[json.loads((p/'fixture.json').read_text(encoding='utf-8')) for p in roots]
 for key in ['profile','shader','shaderSettingsMode','shaderOptions','shaderSettingsSha256','freezeClouds','freezeShaderTime','mods','jarSha256']:
  assert fixtures[0][key]==fixtures[1][key],(env,key)
 pack_hash=None
 if fixtures[0]['shader']:
  hashes=[hashlib.sha256((p/'shaderpacks'/fixtures[0]['shader']).read_bytes()).hexdigest() for p in roots];assert hashes[0]==hashes[1];pack_hash=hashes[0]
 configuration_pairs.append(dict(environment=env,sameConfiguration=True,shaderZipSha256=pack_hash,shaderSettingsSha256=fixtures[0]['shaderSettingsSha256']))
 for layout in ['mixed','silhouette','foreground']:
  values=[]
  for prefix in ['r34-control-r2','r34-fixed-r1']:
   d=json.loads((ROOT/'build/render-regression'/f'{prefix}-{env}/results/edge-{layout}-static-00.json').read_text(encoding='utf-8'))
   values.append(np.array(d['camera']+d['inverseDisplayViewProjection']))
  delta=float(np.max(np.abs(values[0]-values[1])));assert delta<1e-6,(env,layout,delta)
  rows.append(dict(environment=env,layout=layout,maxPoseDifference=delta))
report=dict(passed=True,comparisonCount=len(rows),configurationPairs=configuration_pairs,results=rows)
(ROOT/'build/reports/r34-static-pose-pairs.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2))
