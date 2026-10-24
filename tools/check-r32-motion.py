#!/usr/bin/env python3
"""R32 independent screen-ray oracle under ACTUAL walking, turning and Minecraft view bob.
No reuse of the renderer's new PanoramaRayInverse; use two homogeneous unprojections instead.
"""
from pathlib import Path
import argparse
import importlib.util
import json
import numpy as np
from PIL import Image,ImageDraw,ImageFilter

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('r31_oracle',ROOT/'tools/check-r31-materials.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
STYLES=base.STYLES

def rays(data,w,h):
    inv=np.array(data['inverseDisplayViewProjection'],np.float64).reshape((4,4),order='F')
    y,x=np.mgrid[:h,:w]
    clip=np.stack(((x+.5)/w*2-1,1-(y+.5)/h*2,np.zeros_like(x),np.ones_like(x)),axis=-1)
    near=clip@inv.T;clip[...,2]=-.8;other=clip@inv.T
    direction=near[...,:3]/near[...,3:4]-other[...,:3]/other[...,3:4]
    return direction/np.linalg.norm(direction,axis=-1,keepdims=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--partial',action='store_true');p.add_argument('--annotate',action='store_true');args=p.parse_args()
    planned={f'motion-{s}-setup' for s in STYLES}|{f'motion-{s}-{i:02d}' for s in STYLES for i in range(8)}|{'motion-clean'}
    paths=sorted(args.directory.glob('motion-*.json'));results=[];checks=[];groups={};worst=None
    def need(name,ok,scene=None,**info):checks.append(dict(name=name,passed=bool(ok),scene=scene,**info))
    for path in paths:
        data=json.loads(path.read_text(encoding='utf-8'));name=data['scene']
        need('GL errors',data['initialGlError']==0 and data['finalGlError']==0,name)
        need('raster containment',data['outsideRasterChangedPixels']==0 and data['outsideRasterDepthChangedPixels']==0,name)
        need('Iris restoration',data['irisMainBoundBefore']==data['irisMainBoundAfter'] and data['irisOverridesBefore']==data['irisOverridesAfter'],name)
        if name=='motion-clean':
            need('sky removed',data['blocks']==0 and data['depthChangedPixels']==0,name);continue
        style=name.split('-')[1]
        actual=base.image(args.directory/f'{name}.png');h,w=actual.shape[:2]
        mask=np.array(Image.open(args.directory/f'{name}-depth-change.png').convert('L').resize((w,h),Image.Resampling.NEAREST))>0
        mask=base.interior(mask,7);mask[:10]=False;mask[-10:]=False;mask[:,:10]=False;mask[:,-10:]=False
        need('real sky sample',int(mask.sum())>w*h*.35 and data['blocks']>800,name,pixels=int(mask.sum()),blocks=data['blocks'])
        if style=='vanilla':
            display=args.directory/f'{name}-scene-display-clouds.png';reference=base.image(display if display.exists() else args.directory/f'{name}-scene-clouds.png').astype(float)
            label='unchanged vanilla canvas'
        else:
            reference=base.expected(style,rays(data,w,h));label='depth independent panorama' if style not in ['white','black'] else 'solid control'
        metric=base.metrics(actual,reference,mask)
        need(label,metric['passed'],name,**{k:v for k,v in metric.items() if k!='passed'})
        inverse=np.array(data['inverseDisplayViewProjection'],np.float64).reshape((4,4),order='F')
        # Perspective camera center is inverseVP * (0,0,1,0); bob may be in projection OR view.
        center=inverse[:,2];camera_offset=center[:3]/center[3]
        row={'scene':name,'style':style,**metric,'bobEnabled':data['bobViewEnabled'],'moving':data['panoramaMotion'],'motionTicks':data['panoramaMotionTicks'],
             'camera':data['camera'],'viewTranslation':data['worldView'][12:15],'effectiveCameraOffset':camera_offset.tolist(),'viewLinear':data['worldView'][:12]}
        results.append(row)
        if not name.endswith('-setup'):groups.setdefault(style,[]).append(row)
        if style not in ['vanilla','white','black'] and not name.endswith('-setup') and (worst is None or metric['mae']>worst[0]):
            worst=(metric['mae'],name,actual.copy(),reference.copy(),mask.copy())
    for style,rows in groups.items():
        positions=np.array([r['camera'] for r in rows]);translations=np.array([r['effectiveCameraOffset'] for r in rows]);linear=np.array([r['viewLinear'] for r in rows])
        need('walk actually moved',float(np.linalg.norm(np.ptp(positions,axis=0)))>.2,style,metres=float(np.linalg.norm(np.ptp(positions,axis=0))))
        need('bob actually offset camera transform',float(np.linalg.norm(translations,axis=1).max())>.001,style,maxTranslation=float(np.linalg.norm(translations,axis=1).max()))
        need('turn actually changed view',float(np.max(np.ptp(linear,axis=0)))>.001,style,maxRotationChange=float(np.max(np.ptp(linear,axis=0))))
        need('actual bob and movement enabled',all(r['bobEnabled'] and r['moving'] and r['motionTicks']>0 for r in rows),style)
    actual_names={p.stem for p in paths}
    need('capture coverage',bool(actual_names) and (args.partial or actual_names==planned),missing=sorted(planned-actual_names),unexpected=sorted(actual_names-planned))
    if args.annotate and worst:
        score,name,actual,reference,mask=worst
        diff=np.abs(actual.astype(float)-reference).max(axis=-1);heat=np.zeros_like(actual)
        heat[...,0]=np.uint8(np.clip(diff*12,0,255));heat[...,1]=np.uint8(mask)*30
        width=actual.shape[1];height=actual.shape[0]
        sheet=Image.new('RGB',(width*3,height+28),'#202631');draw=ImageDraw.Draw(sheet)
        for i,(a,label) in enumerate([(actual,'GPU actual'),(np.uint8(np.clip(reference+.5,0,255)),'independent infinite-direction oracle'),(heat,'absolute RGB error x12')]):
            sheet.paste(Image.fromarray(a),(i*width,0));draw.text((i*width+8,height+6),label+' / '+name,fill='white')
        destination=args.output.with_suffix('.png');destination.parent.mkdir(parents=True,exist_ok=True);sheet.save(destination)
    failures=[c for c in checks if not c['passed']]
    report=dict(scenes=len(paths),plannedScenes=len(planned),partial=args.partial,checks=len(checks),passedChecks=len(checks)-len(failures),failedChecks=len(failures),
        maximumMeanRGBError=max((r['mae'] for r in results),default=None),maximumPanoramaMeanRGBError=max((r['mae'] for r in results if r['style'] not in ['vanilla','white','black']),default=None),
        failures=failures,results=results,validation=checks,passed=not failures)
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['results','validation']},indent=2))
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
