#!/usr/bin/env python3
"""Independent same-frame private-sky / real-LOD front-versus-back ordering oracle.

ROI comes from actual collected block positions and recorded production matrices.
A physical NoSky gold wall and its holdout must be observable before SkyOn is
interpreted. This controlled fixture is separate from normal dynamic coverage.
"""
from __future__ import annotations
import argparse, json, math
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFilter

LIMITS=dict(roiErodePixels=6,minGoldPositivePixels=256,minForegroundPixels=256,minSkyPixels=256,
            goldDepthCalibrationMedianBlocks=4.,depthSeparationBlocks=2.,skyMae=1.,skyP99=4.,foregroundNonSkyFraction=.95,foregroundGoldFraction=.80)

def rgb(path):return np.asarray(Image.open(path).convert('RGB'),dtype=np.float64)
def decode(p):
    p=int(p); x=p>>38; z=(p>>12)&0x3ffffff; y=p&0xfff
    if z>=0x2000000:z-=0x4000000
    if y>=0x800:y-=0x1000
    return x,y,z

def matrices(meta):
    p=np.array(meta['worldProjectionColumnMajor']).reshape(4,4,order='F')
    v=np.array(meta['worldViewColumnMajor']).reshape(4,4,order='F')
    if not np.isfinite(p).all() or not np.isfinite(v).all():raise ValueError('Nonfinite recorded production matrix')
    return p,v

def project(points,meta,w,h):
    p,v=matrices(meta); camera=np.array(meta['camera'])
    xyz=np.array(points,dtype=float)-camera
    clip=(p@v@np.column_stack([xyz,np.ones(len(xyz))]).T).T
    if np.any(clip[:,3]<=0):raise ValueError('Controlled face is behind the camera')
    ndc=clip[:,:2]/clip[:,3,None]
    return np.column_stack([(ndc[:,0]*.5+.5)*w,(.5-ndc[:,1]*.5)*h])

def mask(meta,w,h,gold=False,gold_z=160):
    out=Image.new('L',(w,h)); draw=ImageDraw.Draw(out)
    if gold:
        points=[(-80,100,gold_z),(81,100,gold_z),(81,141,gold_z),(-80,141,gold_z)]
        draw.polygon([tuple(x) for x in project(points,meta,w,h)],fill=255)
    else:
        positions=[decode(p) for p in meta['actualCollectedSkyBlockPosLong']]
        for x,y,z in positions:
            points=[(x,y,z),(x+1,y,z),(x+1,y+1,z),(x,y+1,z)]
            draw.polygon([tuple(q) for q in project(points,meta,w,h)],fill=255)
    return np.array(out.filter(ImageFilter.MinFilter(2*LIMITS['roiErodePixels']+1)))!=0

def gold_signal(image):
    r,g,b=image[...,0],image[...,1],image[...,2]
    return (r>50)&(g>40)&(r>=g*.90)&(r>b*1.5)&(g>b*1.5)

def projection_for_depth(meta):
    dh=meta['dh']
    if 'irisDhProjectionColumnMajor' in dh:return np.array(dh['irisDhProjectionColumnMajor']).reshape(4,4,order='F')
    raw=np.array(dh['lastRenderParameter']['dhProjectionValues'])
    possibilities=[raw.reshape(4,4,order=order) for order in ('F','C')]
    valid=[p for p in possibilities if abs(p[3,2]+1)<1e-4 and abs(p[3,3])<1e-6]
    if len(valid)!=1:raise ValueError('Native DH projection layout is not uniquely proved by perspective -Z contract')
    return valid[0]

def depth_eye(meta,root,w,h):
    dh=meta['dh']; source=dh['depth'][0]
    if source.get('status')!='SAMPLED':raise ValueError('Actual DH depth unavailable')
    data=np.fromfile(root/source['rawFile'],dtype='<f4').reshape(source['height'],source['width'])
    y,x=np.indices((h,w),dtype=float)
    u=(x+.5)/w*meta['rasterScaleX']+meta.get('rasterJitterX',0)*.5
    vv=(1-(y+.5)/h)*meta['rasterScaleY']+meta.get('rasterJitterY',0)*.5
    rx=np.floor(u*source['width']).astype(int); ry=np.floor(vv*source['height']).astype(int)
    inside=(rx>=0)&(rx<source['width'])&(ry>=0)&(ry<source['height'])
    values=data[np.clip(ry,0,source['height']-1),np.clip(rx,0,source['width']-1)]
    valid=inside&np.isfinite(values)&(values>0)&(values<1)
    p=projection_for_depth(meta)
    ndc=values*2-1 if dh.get('depthRange')=='NEG_ONE_TO_POS_ONE' else values
    with np.errstate(divide='ignore',invalid='ignore'):
        eye=-(p[2,3]-ndc*p[3,3])/(ndc*p[3,2]-p[2,2])
    eye=np.where(valid&np.isfinite(eye)&(eye>0),eye,np.inf)
    return eye,valid

def plane_eye(meta,z,w,h):
    p,v=matrices(meta); y,x=np.indices((h,w),dtype=float)
    clip=np.stack([(x+.5)/w*2-1,1-(y+.5)/h*2,np.full((h,w),-1.),np.ones((h,w))],axis=-1)
    ray=clip@np.linalg.inv(p).T; ray=ray[...,:3]/ray[...,3,None]
    directions=np.concatenate([ray,np.zeros((h,w,1))],axis=-1)@np.linalg.inv(v).T
    camera=np.array(meta['camera']); t=(z-camera[2])/directions[...,2]
    relative=directions[...,:3]*t[...,None]
    view=np.concatenate([relative,np.ones((h,w,1))],axis=-1)@v.T
    return -view[...,2]

def evaluate(directory):
    directory=directory.resolve(strict=True); root=directory/'r30-results'
    marker=json.loads((directory/'r30-owned.json').read_text(encoding='utf-8'))
    summary=json.loads((root/'run-summary.json').read_text(encoding='utf-8'))
    phases={p['name']:p for p in summary['phases']}; checks=[]
    def need(name,condition,**extra):checks.append(dict(name=name,passed=bool(condition),**extra));return bool(condition)
    need('explicit-controlled-fixture',marker.get('controlledLodOcclusion',{}).get('enabled') is True)
    need('live-DH-not-disabled',marker['dhConfiguration']['actual']['common']['worldGenerator']['generatorPlan']!='DISABLED' and marker['dhConfiguration']['actual']['client']['advanced']['debugging']['rendererMode']=='DEFAULT')
    gold_z=marker['controlledLodOcclusion']['goldWall']['z']
    image=lambda p:rgb(root/f"{p['index']:02d}-{p['name']}.png")
    control=phases['lod-only-gold-positive']; holdout=phases['lod-only-no-sky-holdout']
    a=image(control); b=image(holdout); h,w=a.shape[:2]
    need('same-physical-camera-and-size',a.shape==b.shape and np.max(np.abs(np.array(control['camera'])-holdout['camera']))<1e-4)
    region=mask(control,w,h,gold=True,gold_z=gold_z)&mask(holdout,w,h,gold=True,gold_z=gold_z)
    positive=region&gold_signal(a)&gold_signal(b)
    Image.fromarray((positive*255).astype('uint8')).save(root/'oracle-gold-positive-and-holdout-mask.png')
    need('physical-gold-and-holdout-observable',int(positive.sum())>=LIMITS['minGoldPositivePixels'],pixels=int(positive.sum()))
    for p in (control,holdout):
        need(p['name']+':actual-NoSky-and-suppression',p['maximumCollectedBlocks']==0 and p['controlledVanillaTerrainSuppression'] is True)
        need(p['name']+':actual-server-gold-probes',len(p['serverGeometry'].get('blockProbes',[]))>=2 and all(x['block']=='minecraft:gold_block' for x in p['serverGeometry']['blockProbes']))
        distance,valid=depth_eye(p,root,w,h); expected=plane_eye(p,gold_z,w,h)
        eligible=positive&valid
        delta=np.abs(distance[eligible]-expected[eligible]); med=float(np.median(delta)) if len(delta) else None
        need(p['name']+':projection-calibrates-real-gold-distance',med is not None and med<=LIMITS['goldDepthCalibrationMedianBlocks'],medianDistanceError=med,pixels=int(eligible.sum()))
    cases=[]
    for name in ('lod-only-sky-behind','lod-only-sky-front','ordinary-and-lod-sky-behind'):
        meta=phases[name]; actual=image(meta)
        refs=meta['privateSkyReferences']; ref=refs.get('displayTarget',refs.get('target'))
        if ref is None:raise ValueError('Missing same-frame production private canvas')
        reference=rgb(root/ref['file']); need(name+':unscaled-real-private-reference',reference.shape==actual.shape)
        geometry=mask(meta,w,h); eligible=geometry&positive
        positions=[decode(x) for x in meta['actualCollectedSkyBlockPosLong']]
        zs={p[2] for p in positions}; need(name+':one-recorded-physical-sky-plane',len(zs)==1)
        if len(zs)!=1:continue
        sky=plane_eye(meta,next(iter(zs)),w,h); distant,valid=depth_eye(meta,root,w,h)
        if name=='ordinary-and-lod-sky-behind':
            # Vanilla can already depth-cull equal DH fragments: use the independently
            # verified physical gold plane for this ordinary-rendering control only.
            foreground=eligible&(plane_eye(meta,gold_z,w,h)+LIMITS['depthSeparationBlocks']<sky)
        else:
            foreground=eligible&valid&(distant+LIMITS['depthSeparationBlocks']<sky)
        if name=='lod-only-sky-front':
            gold_distance=plane_eye(meta,gold_z,w,h)
            sky_expected=eligible&(sky+LIMITS['depthSeparationBlocks']<gold_distance)
            need(name+':no-unexpected-closer-depth-in-combined-target',
                 not np.any(sky_expected&valid&(distant+LIMITS['depthSeparationBlocks']<sky)))
        else:
            sky_expected=eligible&(~valid|(sky+LIMITS['depthSeparationBlocks']<distant))
        difference=np.abs(actual-reference)
        mean_error=difference.mean(axis=2)
        result=dict(name=name,eligiblePixels=int(eligible.sum()),foregroundPixels=int(foreground.sum()),skyPixels=int(sky_expected.sum()))
        Image.fromarray((geometry*255).astype('uint8')).save(root/(name+'-oracle-collected-sky-mask.png'))
        Image.fromarray((foreground*255).astype('uint8')).save(root/(name+'-oracle-LOD-foreground-mask.png'))
        Image.fromarray((sky_expected*255).astype('uint8')).save(root/(name+'-oracle-sky-front-mask.png'))
        need(name+':actual-collected-coverage',meta['maximumCollectedBlocks']>0 and int(eligible.sum())>=LIMITS['minSkyPixels'])
        if name!='lod-only-sky-front':
            need(name+':real-closer-LOD-foreground',int(foreground.sum())>=LIMITS['minForegroundPixels'])
            non_sky=float((mean_error[foreground]>10).mean()) if foreground.any() else 0.
            gold=float(gold_signal(actual)[foreground].mean()) if foreground.any() else 0.
            result.update(foregroundNonPrivateSkyFraction=non_sky,foregroundPhysicalGoldFraction=gold)
            need(name+':closer-LOD-not-overpainted-with-private-sky',non_sky>=LIMITS['foregroundNonSkyFraction'] and gold>=LIMITS['foregroundGoldFraction'],nonSkyFraction=non_sky,goldFraction=gold)
        else:
            need(name+':sky-really-in-front-of-LOD',int(sky_expected.sum())>=LIMITS['minSkyPixels'])
            mae=float(difference[sky_expected].mean()) if sky_expected.any() else None
            p99=float(np.percentile(difference[sky_expected],99)) if sky_expected.any() else None
            result.update(privateReferenceMae=mae,privateReferenceP99=p99)
            need(name+':near-sky-retained-over-far-LOD',mae is not None and mae<=LIMITS['skyMae'] and p99<=LIMITS['skyP99'],mae=mae,p99=p99)
        cases.append(result)
    passed=all(c['passed'] for c in checks)
    return dict(status='CONTROLLED_LOD_ORDERING_VERIFIED' if passed else 'CONTROLLED_LOD_ORDERING_OPEN',passed=passed,counts=dict(passed=sum(c['passed'] for c in checks),total=len(checks)),checks=checks,cases=cases,limits=LIMITS,normalDynamicMatrixIsSeparate=True,releaseAccepted=False)

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('directory',type=Path); p.add_argument('--output',type=Path,required=True); a=p.parse_args()
    result=evaluate(a.directory)
    with a.output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    print(json.dumps({k:v for k,v in result.items() if k!='checks'},indent=2))
    for c in result['checks']:
        if not c['passed']:print('FAIL',json.dumps(c))
    return 0 if result['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
