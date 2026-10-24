#!/usr/bin/env python3
"""Independent CPU spherical-ray oracle for R31 captured GPU material views.
Uses actual camera matrices, input panorama pixels, and measured depth masks.
Never accepts a material merely because a renderer flag says it was drawn.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT=Path(__file__).resolve().parents[1]
STYLES=['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']
VIEWS=['front','right','back','left','up','down','joint','wrap','foreground']
CACHE={}

def load(path): return json.loads(path.read_text(encoding='utf-8'))
def image(path): return np.array(Image.open(path).convert('RGB'))
def rays(data,w,h,display=True):
    key='inverseDisplayViewProjection' if display else 'inverseViewProjection'
    inv=np.array(data[key],np.float64).reshape((4,4),order='F')
    y,x=np.mgrid[:h,:w]
    clip=np.stack(((x+.5)/w*2-1,1-(y+.5)/h*2,np.ones_like(x),np.ones_like(x)),axis=-1)
    p=clip@inv.T
    r=p[...,:3]/p[...,3:4]
    r/=np.linalg.norm(r,axis=-1,keepdims=True)
    return r

def expected(style,r):
    if style in ('white','black'): return np.full(r.shape,255 if style=='white' else 0,np.float64)
    if style not in CACHE: CACHE[style]=image(ROOT/'src/main/resources/assets/skyboxblock/textures/sky'/f'{style}.png').astype(np.float64)
    a=CACHE[style]; h,w=a.shape[:2]
    u=np.arctan2(r[...,2],r[...,0])/(2*np.pi)+.5; v=np.arccos(np.clip(r[...,1],-1,1))/np.pi
    x=u*w-.5; y=np.clip(v*h-.5,0,h-1)
    x0=np.floor(x).astype(int); y0=np.floor(y).astype(int); wx=(x-x0)[...,None]; wy=(y-y0)[...,None]
    x1=(x0+1)%w; x0%=w; y1=np.minimum(y0+1,h-1)
    return (a[y0,x0]*(1-wx)+a[y0,x1]*wx)*(1-wy)+(a[y1,x0]*(1-wx)+a[y1,x1]*wx)*wy

def metrics(actual,reference,mask):
    count=int(mask.sum())
    if not count:return {'pixels':0,'mae':None,'p99':None,'badFraction':1,'passed':False}
    diff=np.abs(actual.astype(float)-reference)[mask]
    return {'pixels':count,'mae':float(diff.mean()),'p99':float(np.percentile(diff,99)),
            'max':float(diff.max()),'badFraction':float((diff.max(axis=1)>4).mean()),
            'passed':bool(diff.mean()<1.0 and (diff.max(axis=1)>4).mean()<.001)}

def interior(mask,radius=3):
    return np.array(Image.fromarray(np.uint8(mask)*255).filter(ImageFilter.MinFilter(radius*2+1)))>0

def check_scene(directory,path):
    data=load(path); name=data['scene']; actual=image(directory/f'{name}.png'); h,w=actual.shape[:2]
    output={'scene':name,'checks':[]}
    def check(label,ok,**info): output['checks'].append(dict(name=label,passed=bool(ok),**info))
    check('GL errors',data['initialGlError']==0 and data['finalGlError']==0,initial=data['initialGlError'],final=data['finalGlError'])
    check('raster containment',data['outsideRasterChangedPixels']==0 and data['outsideRasterDepthChangedPixels']==0)
    check('Iris state restoration',data['irisMainBoundBefore']==data['irisMainBoundAfter'] and data['irisOverridesBefore']==data['irisOverridesAfter'])
    check('resource reload complete',not data['resourceReloadPending'])
    if name.endswith('-removed'):
        check('removed geometry',data['blocks']==0 and data['depthChangedPixels']==0,blocks=data['blocks'],depth=data['depthChangedPixels'])
        return output
    check('real geometry and depth',data['blocks']>0 and data['depthChangedPixels']>10000,blocks=data['blocks'],depth=data['depthChangedPixels'])
    r=rays(data,w,h)
    if name.startswith('material-mixed'):
        # Independent ray/plane test against the fixture's nine adjacent 4x4 material panels.
        camera=np.array(data['camera']); distance=(8-camera[2])/r[...,2]
        p=camera+r*distance[...,None]
        col=np.floor((p[...,0]+6)/4).astype(int); row=np.floor((p[...,1]-142)/4).astype(int)
        region=(distance>0)&(col>=0)&(col<3)&(row>=0)&(row<3)
        for i,style in enumerate(STYLES):
            mask=interior(region&(row*3+col==i),5)
            check('mixed '+style+' visibility',int(mask.sum())>1000,pixels=int(mask.sum()))
            if style=='vanilla':continue
            result=metrics(actual,expected(style,r),mask)
            check('mixed '+style+' ray oracle',result['passed'],**{k:v for k,v in result.items() if k!='passed'})
    else:
        style=name.split('-')[1]
        foreground=name.endswith('-foreground')
        mask=np.ones((h,w),bool)
        # Both sky-depth coverage and post-opaque foreground observations come from actual GL depth.
        depth_path=directory/f'{name}-depth-change.png'
        if depth_path.exists():
            mask=np.array(Image.open(depth_path).convert('L').resize((w,h),Image.Resampling.NEAREST))>0
        if foreground:
            fg=directory/f'{name}-post-opaque-foreground.png'
            if fg.exists():mask &= np.array(Image.open(fg).convert('L').resize((w,h),Image.Resampling.NEAREST))==0
            # Exclude known placed opaque pillar, glass and hand/HUD by actual fixture rays, not RGB differences.
            camera=np.array(data['camera']); dist=(4-camera[2])/r[...,2]; hit=camera+r*dist[...,None]
            foreground_region=(dist>0)&(hit[...,1]>=145)&(hit[...,1]<=152)&(hit[...,0]>=-1.15)&(hit[...,0]<=4.15)
            mask &= ~foreground_region
            mask[int(h*.65):]=False
            mask[h//2-12:h//2+12,w//2-12:w//2+12]=False
        mask=interior(mask,4); mask[:8]=False;mask[-8:]=False;mask[:,:8]=False;mask[:,-8:]=False
        check('sample area',int(mask.sum())>w*h*(.03 if foreground else .85),pixels=int(mask.sum()))
        if style!='vanilla':
            reference=expected(style,r); result=metrics(actual,reference,mask)
            check('spherical display oracle',result['passed'],**{k:v for k,v in result.items() if k!='passed'})
            if not foreground:
                # Source discontinuities, per-block stretching and single-pixel gaps all violate this stricter global oracle.
                diff=np.max(np.abs(actual.astype(float)-reference),axis=-1)
                check('no seam outlier lines',float((diff[mask]>4).mean())<.001,badPixels=int((diff[mask]>4).sum()))
        if foreground:
            # Pillar area must differ from the background, regardless of the style's luminance.
            camera=np.array(data['camera']);dist=(4-camera[2])/r[...,2];hit=camera+r*dist[...,None]
            pillar=(dist>0)&(hit[...,0]>-.85)&(hit[...,0]<-.15)&(hit[...,1]>146)&(hit[...,1]<150)
            deviation=float(np.median(np.abs(actual.astype(float)-expected(style,r))[pillar].mean(axis=1))) if style!='vanilla' else None
            check('opaque foreground visible',int(pillar.sum())>1000 and float(np.std(actual[pillar]))>4 and (deviation is None or deviation>6),pixels=int(pillar.sum()),std=float(np.std(actual[pillar])),backgroundDeviation=deviation)
    return output

def selftest():
    # Detect wrong texture, color, UV orientation, one-pixel seams and missing coverage.
    h,w=120,240;y,x=np.mgrid[:h,:w]
    r=np.stack((np.cos(x/w*6.28)*np.sin((y+.5)/h*3.14),np.cos((y+.5)/h*3.14),np.sin(x/w*6.28)*np.sin((y+.5)/h*3.14)),axis=-1)
    ref=expected('lush',r); exact=np.uint8(np.clip(ref+.5,0,255)); mask=np.ones((h,w),bool)
    controls={}
    controls['positive']=metrics(exact,ref,mask)['passed']
    wrong=exact.copy();wrong[:,w//2]=[255,0,255];controls['seamDetected']=not metrics(wrong,ref,mask)['passed']
    controls['wrongStyleDetected']=not metrics(exact,expected('dusk',r),mask)['passed']
    controls['mirrorDetected']=not metrics(exact[:,::-1],ref,mask)['passed']
    controls['emptyDetected']=not metrics(exact,ref,np.zeros_like(mask))['passed']
    controls['solidWhitePositive']=metrics(np.full((h,w,3),255,dtype=np.uint8),expected('white',r),mask)['passed']
    controls['wrongSolidDetected']=not metrics(np.zeros((h,w,3),np.uint8),expected('white',r),mask)['passed']
    assert all(controls.values()),controls
    return controls

def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path,nargs='?');parser.add_argument('--output',type=Path);parser.add_argument('--partial',action='store_true');parser.add_argument('--selftest',action='store_true');args=parser.parse_args()
    controls=selftest()
    if args.directory is None:
        print(json.dumps(controls,indent=2));return 0
    paths=sorted(args.directory.glob('material-*.json'))
    planned={f'material-{s}-{v}' for s in STYLES for v in VIEWS}|{'material-mixed','material-mixed-reload','material-mixed-resized','material-mixed-removed'}
    actual={p.stem for p in paths}
    results=[check_scene(args.directory,p) for p in paths]
    failures=[{'scene':r['scene'],**c} for r in results for c in r['checks'] if not c['passed']]
    checks=sum(len(r['checks']) for r in results)
    result={'scenes':len(results),'plannedScenes':len(planned),'missing':sorted(planned-actual),'unexpected':sorted(actual-planned),
        'checks':checks,'passedChecks':checks-len(failures),'failedChecks':len(failures),'selftest':controls,'failures':failures,'results':results}
    result['passed']=not failures and (args.partial or actual==planned)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='results'},indent=2))
    return 0 if result['passed'] else 1
if __name__=='__main__': raise SystemExit(main())
