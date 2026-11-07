#!/usr/bin/env python3
"""Pixel oracle, A/B parity, occlusion and background-window assertions."""
from pathlib import Path
import argparse, hashlib, json
import numpy as np
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('case',type=Path);p.add_argument('--version',choices=['1211','262'],required=True);a=p.parse_args();case=a.case.resolve()
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def image(p):return np.array(Image.open(p).convert('RGB'))
if a.version=='1211':
 d=case/'r30-results';m=load(d/'00-hdr.json');n=load(d/'01-png-control.json');o=load(d/'02-hdr-occluded.json');img=image(d/'00-hdr.png');png=image(d/'01-png-control.png');occluded=image(d/'02-hdr-occluded.png');vp=np.array(m['worldProjectionColumnMajor']).reshape((4,4),order='F')@np.array(m['worldViewColumnMajor']).reshape((4,4),order='F');depths=(-1.,0.);game=case
else:
 d=case/'control';m=load(d/'hdr.json');n=load(d/'png-control.json');o=load(d/'hdr-occluded.json');img=image(d/'hdr.png');png=image(d/'png-control.png');occluded=image(d/'hdr-occluded.png');vp=np.array(m['worldVP']).reshape((4,4),order='F');depths=m['rayDepth'];game=case/'game'
texture=image(game/'config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_live.png')
h,w=img.shape[:2];th,tw=texture.shape[:2]
xs=np.linspace(int(.3*w),int(.7*w)-1,65).astype(int);ys=np.linspace(int(.15*h),int(.7*h)-1,49).astype(int);xx,yy=np.meshgrid(xs,ys);x=xx.ravel();y=yy.ravel();uv=np.stack(((x+.5)/w,1-(y+.5)/h),axis=1)
inv=np.linalg.inv(vp)
points=[]
for depth in depths:
 q=np.column_stack((uv*2-1,np.full(len(x),depth),np.ones(len(x))))@inv.T;points.append(q[:,:3]/q[:,3:4])
ray=points[1]-points[0];ray/=np.linalg.norm(ray,axis=1)[:,None]
u=np.arctan2(ray[:,2],ray[:,0])/(2*np.pi)+.5;v=np.arccos(np.clip(ray[:,1],-1,1))/np.pi
def sample(u,v):
 px=u*tw-.5;py=np.clip(v*th-.5,0,th-1);x0=np.floor(px).astype(int);y0=np.floor(py).astype(int);fx=(px-x0)[:,None];fy=(py-y0)[:,None];x1=(x0+1)%tw;x0%=tw;y1=np.minimum(y0+1,th-1);return (texture[y0,x0]*(1-fx)+texture[y0,x1]*fx)*(1-fy)+(texture[y1,x0]*(1-fx)+texture[y1,x1]*fx)*fy
expected=sample(u,v);actual=img[y,x].astype(float);error=np.abs(actual-expected);negative=np.abs(actual-sample((u+.25)%1,v));ab=np.abs(actual-png[y,x].astype(float))
center=np.s_[int(.3*h):int(.65*h),int(.44*w):int(.56*w)]
occ=np.abs(img[center].astype(float)-occluded[center].astype(float));folder=load(case/'folder-open.json')
report={'version':a.version,'case':str(case),'oracle':{'samples':len(x),'rgbMAE':float(error.mean()),'rgbP99':float(np.quantile(error,.99)),'rgbMax':float(error.max()),'longitudeShiftNegativeMAE':float(negative.mean())},'pngAB':{'rgbMAE':float(ab.mean()),'rgbMax':float(ab.max())},'occlusion':{'centerRGBMAE':float(occ.mean()),'changedPixelShare':float(np.mean(np.max(occ,axis=-1)>20))},'folderMatched':folder['ok'],'background':{key:[m[key],n[key],o[key]] for key in ['windowVisible','windowFocused','mouseGrabbed']}}
if a.version=='1211':report['frameTimes']={'hdr':m['frameTimeMilliseconds'],'png':n['frameTimeMilliseconds'],'scope':'5s each at 60 FPS cap; CPU wall intervals, not uncapped benchmark'};report['gpuBuffers']={'hdr':m['resources']['observedGpuBufferBytes'],'png':n['resources']['observedGpuBufferBytes']}
checks={'sourceImageNotFlat':float(actual.std())>10,'displayOracle':error.mean()<2.0 and np.quantile(error,.99)<4.0,'negativeControlDetected':negative.mean()>8,'pngAB':ab.max()<=2,'foregroundPreserved':occ.mean()>10 and np.mean(np.max(occ,axis=-1)>20)>.5,'folder':folder['ok'],'background':not any(any(value) for value in report['background'].values())}
checks={key:bool(value) for key,value in checks.items()};report['checks']=checks;report['ok']=all(checks.values());(case/'hdr-pixel-validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2));raise SystemExit(0 if report['ok'] else 1)
