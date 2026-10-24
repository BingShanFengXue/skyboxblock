#!/usr/bin/env python3
"""Compare a private generated sky against the SAME FRAME's actual vanilla renderSky output."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('fixture',type=Path)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--expect',type=int,required=True)
a=p.parse_args()
r=a.fixture/'results'
rows=[]
for f in sorted(r.glob('*-native-sky.png')):
    name=f.name.removesuffix('-native-sky.png')
    other=r/(name+'-scene-base.png')
    if not other.exists():
        rows.append({'scene':name,'passed':False,'reason':'private base image missing'});continue
    native=np.asarray(Image.open(f).convert('RGB'),dtype=np.int16)
    own=np.asarray(Image.open(other).convert('RGB'),dtype=np.int16)
    if native.shape!=own.shape:
        rows.append({'scene':name,'passed':False,'reason':'shape mismatch'});continue
    d=np.abs(native-own);peak=d.max(axis=2)
    row={'scene':name,'size':[native.shape[1],native.shape[0]],'meanAbsoluteRgb':float(d.mean()),
         'maximumChannelError':int(d.max()),'pixelsOver1':int((peak>1).sum()),'differentPixels':int((peak>0).sum()),
         'nativeSha256':hashlib.sha256(f.read_bytes()).hexdigest(),
         'privateSha256':hashlib.sha256(other.read_bytes()).hexdigest(),
         'passed':bool((peak<=1).all())}
    meta=r/(name+'.json')
    if meta.exists():
        m=json.loads(meta.read_text(encoding='utf-8'))
        row['cloudWorldHeight']=m.get('cloudWorldHeight');row['cloudRelativeY']=m.get('cloudRelativeY')
    if name.startswith('ref-cloud-'):
        diag=r/'cloud-diagnostics'/(name+'-clouds.json')
        evidence=json.loads(diag.read_text(encoding='utf-8')) if diag.exists() else {}
        expected={'ref-cloud-96':96,'ref-cloud-240':240,'ref-cloud-reload':240,'ref-cloud-reset':192}[name]
        row['nativeDimensionCloudHeight']=evidence.get('nativeDimensionCloudHeight')
        row['cloudHeightCommandPassed']=(row.get('cloudWorldHeight')==expected
            and evidence.get('configuredVanillaCloudHeight')==expected
            and evidence.get('nativeDimensionCloudHeight')==192)
        row['passed']=row['passed'] and row['cloudHeightCommandPassed']
    rows.append(row)
report={'kind':'same-frame-vanilla-sky-oracle','threshold':'every RGB channel differs by at most 1/255; no masks/exclusions',
        'fixture':str(a.fixture.resolve()),'expectedScenes':a.expect,'actualScenes':len(rows),'scenes':rows,
        'passed':len(rows)==a.expect and all(row['passed'] for row in rows)}
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
print(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(0 if report['passed'] else 1)
