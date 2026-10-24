#!/usr/bin/env python3
"""Measure blue cast on independently ray-selected real bedrock, not the generated sky itself."""
import argparse,hashlib,importlib.util,json,zipfile
from pathlib import Path
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('world_oracle',ROOT/'tools/check-world-regression.py')
w=importlib.util.module_from_spec(spec);spec.loader.exec_module(w)
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('before',type=Path);p.add_argument('after',type=Path);p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def jar_entries(p):
    with zipfile.ZipFile(p) as z:return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
old=a.before/'mods/skyboxblock-1.0.2-test.6.jar';new=a.after/'mods/skyboxblock-1.0.2-test.6.jar'
x,y=jar_entries(old),jar_entries(new)
delta=[n for n in sorted(set(x)|set(y)) if x.get(n)!=y.get(n)]
mods=lambda d:{p.name:sha(p) for p in (d/'mods').glob('*.jar') if not p.name.startswith('skyboxblock-')}
packs=lambda d:{p.name:sha(p) for p in (d/'shaderpacks').glob('*.zip')}
def options(d):
    result={}
    for p in sorted((d/'shaderpacks').glob('*.zip.txt')):
        result[p.name]={k.strip():v.strip() for line in p.read_text(encoding='utf-8').splitlines()
                        if line.strip() and not line.lstrip().startswith('#') and '=' in line
                        for k,v in [line.split('=',1)]}
    return result
checks={'shaderOptionsIdentical':options(a.before)==options(a.after) and bool(options(a.before)),
        'onlyIntendedExposureDelta':bool(delta) and all(n in {
            'assets/skyboxblock/shaders/core/sky_material_background.fsh',
            'assets/skyboxblock/shaders/core/sky_material_background.json',
            'dev/yg/skyboxblock/client/SkyWorldRenderer$Frame.class'}
            or n.startswith('dev/yg/skyboxblock/client/ItrpSkyBridge')
            or n.startswith('dev/yg/skyboxblock/client/RenderRegression') for n in delta),
        'opaqueSkyAlbedoUnchanged':x.get('assets/skyboxblock/shaders/core/world_sky.fsh')==y.get('assets/skyboxblock/shaders/core/world_sky.fsh'),
        'supportingModsIdentical':mods(a.before)==mods(a.after),'shaderArchiveIdentical':packs(a.before)==packs(a.after)}
rows=[]
for name in ['blue-night-wall','blue-night-settled','blue-return-night']:
    paths=[d/'results' for d in [a.before,a.after]]
    metas=[json.loads((d/(name+'.json')).read_text(encoding='utf-8')) for d in paths]
    images=[np.asarray(Image.open(d/(name+'.png')).convert('RGB'),dtype=np.float32) for d in paths]
    m=metas[0];raymeta=dict(m);raymeta['inverseViewProjection']=m['inverseDisplayViewProjection']
    directions=w.rays(raymeta,images[0].shape[1],images[0].shape[0]);o=np.asarray(m['camera'])
    with np.errstate(divide='ignore',invalid='ignore'):
        t=(180-o[1])/directions[...,1];points=o+directions*t[...,None]
    # Actual floor top of the fixed room. No color-derived exclusions or hue masks.
    floor=(directions[...,1]<0)&(t>0)&(points[...,0]>-7)&(points[...,0]<8)&(points[...,2]>1)&(points[...,2]<5)
    stats=[]
    for image in images:
        rgb=image[floor];excess=np.maximum(rgb[:,2]-np.maximum(rgb[:,0],rgb[:,1]),0)
        stats.append({'pixels':len(rgb),'meanRgb':rgb.mean(axis=0).tolist(),'meanBlueExcess':float(excess.mean()),
                      'p99BlueExcess':float(np.percentile(excess,99)),'blueExcessOver3':int((excess>3).sum())})
    reduction=1-stats[1]['meanBlueExcess']/max(stats[0]['meanBlueExcess'],1e-9)
    paired=all(metas[0][k]==metas[1][k] for k in ['width','height','camera','blocks','vertices','cloudWorldHeight'])
    rows.append({'scene':name,'geometryAndPoseMatch':paired,'before':stats[0],'after':stats[1],
                 'blueExcessReduction':reduction,'diagnosticImproved':paired and reduction>.8})
report={'kind':'bounded-blue-light-comparison','beforeJarSha256':sha(old),'afterJarSha256':sha(new),
        'changedJarEntries':delta,'identityChecks':checks,'measurements':rows,
        'blueCastImproved':all(checks.values()) and all(r['diagnosticImproved'] for r in rows),
        'universalShaderCompatibilityClaim':False}
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open('x',encoding='utf-8') as f:json.dump(report,f,indent=2)
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['blueCastImproved'] else 1)
