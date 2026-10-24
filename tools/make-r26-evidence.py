#!/usr/bin/env python3
"""Create labelled display comparisons and adversarial checks without changing any captured source."""
from pathlib import Path
import json,subprocess,sys
from PIL import Image,ImageDraw,ImageFont
import numpy as np
ROOT=Path(__file__).resolve().parent.parent
REPORT=ROOT/'build/reports'
REF=ROOT/'build/render-regression/r26-test6-reference-final-r5/results'
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',20)

def board(path,rows,titles):
    image=Image.new('RGB',(1280,len(rows)*430+42),(27,29,34));d=ImageDraw.Draw(image)
    d.text((12,10),titles,fill='white',font=font)
    for i,(label,left,right,crop) in enumerate(rows):
        y=42+i*430;d.text((12,y+4),label,fill='white',font=font)
        for x,p in [(0,left),(640,right)]:
            with Image.open(p) as original:
                tile=original.convert('RGB')
                if crop:tile=tile.crop((320,200,960,600))
                tile=tile.resize((640,400),Image.Resampling.LANCZOS)
                image.paste(tile,(x,y+30))
    assert not path.exists();image.save(path)

rows=[]
for label,name,crop in [('Dawn: 23000 ticks (full view)','ref-horizon-23000',False),
                        ('Sunset: 12000 ticks (full view)','ref-horizon-12000',False),
                        ('Moon phase 1 (same center crop, no rotation)','ref-moon-1',True)]:
    rows.append((label,REF/(name+'-native-sky.png'),REF/(name+'-scene-base.png'),crop))
board(REPORT/'r26-vanilla-reference-comparison.png',rows,'ACTUAL VANILLA (left) | PRIVATE GENERATED SKY (right) - display comparison only')
board(REPORT/'r26-blue-halo-before-after.png',[
    ('ITRP, bloom ON, no portal mod: before / exposure-relative HDR fix',
     ROOT/'build/render-regression/r26-test6-blue-iris-r3/results/blue-night-settled.png',
     ROOT/'build/render-regression/r26-test6-blue-iris-r5/results/blue-night-settled.png',False)],
    'BEFORE (left) | AFTER (right) - two controlled runs, not the same rendered frame')

base=REPORT/'r26-oracle-controls';base.mkdir(exist_ok=False)
checks=[]
for kind,source in [('positive','ref-moon-1'),('moon-180','ref-moon-1'),('blue-tint','ref-night-0'),('dawn-color','ref-horizon-12000')]:
    folder=base/kind;dest=folder/'results';dest.mkdir(parents=True)
    native=Image.open(REF/(source+'-native-sky.png')).convert('RGB')
    private=Image.open(REF/(source+'-scene-base.png')).convert('RGB')
    a=np.asarray(private).copy()
    if kind=='moon-180':a[360:440,600:680]=np.rot90(a[360:440,600:680],2)
    elif kind=='blue-tint':a[...,2]=np.minimum(a[...,2].astype(np.int16)+8,255)
    elif kind=='dawn-color':a[...,0]=np.minimum(a[...,0].astype(np.int16)+8,255)
    native.save(dest/'probe-native-sky.png');Image.fromarray(a).save(dest/'probe-scene-base.png')
    result=folder/'report.json'
    with (folder/'checker.log').open('xb') as log:
        rc=subprocess.run([sys.executable,str(ROOT/'tools/check-vanilla-reference-r26.py'),str(folder),'--expect','1','--output',str(result)],stdout=log,stderr=subprocess.STDOUT).returncode
    data=json.loads(result.read_text(encoding='utf-8'))
    checks.append({'control':kind,'exitCode':rc,'oraclePassed':data['passed'],
                   'passed':rc==(0 if kind=='positive' else 1) and data['passed']==(kind=='positive')})
summary={'checks':checks,'passed':all(c['passed'] for c in checks),'sourceCapturesModified':False}
with (REPORT/'r26-oracle-controls.json').open('x',encoding='utf-8') as f:json.dump(summary,f,indent=2)
print(json.dumps(summary,indent=2))
raise SystemExit(0 if summary['passed'] else 1)
