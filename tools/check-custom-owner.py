#!/usr/bin/env python3
"""Compare identical front panels with and without fully occluded rear sky cubes."""
from pathlib import Path
import argparse,json
import numpy as np
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('case',type=Path);p.add_argument('--expect-failure',action='store_true');a=p.parse_args();case=a.case.resolve();d=case/('owner-results' if (case/'owner-results').exists() else 'r30-results')
summary=json.loads((d/'run-summary.json').read_text(encoding='utf-8'));phases=summary['phases'];bases={};rows=[]
for phase in phases:
 name=phase['name'];front,angle,behind=name.split('-');idx=phase['index'];im=np.array(Image.open(d/f'{idx:02d}-{name}.png').convert('RGB')).astype(float);key=(front,angle)
 if behind=='base':bases[key]=(im,phase);continue
 base,meta=bases[key];h,w=im.shape[:2];yy,xx=np.mgrid[0:h:2,0:w:2];x=xx.ravel();y=yy.ravel();uv=np.column_stack(((x+.5)/w,1-(y+.5)/h));port='worldVP' in meta;vp=np.array(meta['worldVP']).reshape((4,4),order='F') if port else np.array(meta['worldProjectionColumnMajor']).reshape((4,4),order='F')@np.array(meta['worldViewColumnMajor']).reshape((4,4),order='F');inv=np.linalg.inv(vp);points=[]
 for depth in (meta['rayDepth'] if port else [-1.,0.]):
  q=np.column_stack((uv*2-1,np.full(len(x),depth),np.ones(len(x))))@inv.T;points.append(q[:,:3]/q[:,3:4])
 ray=points[1]-points[0];camera=np.array(meta['camera']);origin=meta['serverGeometry'];t=(origin['originZ']-camera[2])/ray[:,2];hit=camera+ray*t[:,None];mask=(t>0)&(hit[:,0]>origin['originX']+.1)&(hit[:,0]<origin['originX']+48-.1)&(hit[:,1]>origin['originY']+.1)&(hit[:,1]<origin['originY']+40-.1)
 # Isolate the projection of the hidden cube, expanded by two pixels; only the FRONT footprint counts.
 corners=np.array([[cx,cy,cz] for cx in [-2,3] for cy in ([3,8] if port else [139,144]) for cz in [6,11]],float);q=np.column_stack((corners-camera,np.ones(8)))@vp.T;q=q[:,:3]/q[:,3:4];px=(q[:,0]*.5+.5)*w;py=(.5-q[:,1]*.5)*h;roi=mask&(x>=max(0,px.min()-3))&(x<=min(w-1,px.max()+3))&(y>=max(0,py.min()-3))&(y<=min(h-1,py.max()+3))
 err=np.abs(im[y[mask],x[mask]]-base[y[mask],x[mask]]);local=np.abs(im[y[roi],x[roi]]-base[y[roi],x[roi]])
 assert len(local)>50,(name,'cube not visible in front footprint')
 good=bool(local.max()<=2 and np.mean(local)<.1)
 rows.append({'name':name,'front':front,'rear':behind,'angle':angle,'frontPixels':int(mask.sum()),'hiddenCubePixels':int(roi.sum()),'cubeMAE':float(local.mean()),'cubeMax':float(local.max()),'cubeChangedShare':float(np.mean(np.max(local,axis=1)>2)),'frontMAE':float(err.mean()),'pass':good,'windowVisible':phase['windowVisible'],'windowFocused':phase['windowFocused'],'mouseGrabbed':phase['mouseGrabbed']})
report={'case':str(case),'phases':len(phases),'comparisons':rows,'passes':sum(v['pass'] for v in rows),'failures':sum(not v['pass'] for v in rows),'ok':all(v['pass'] and not any(v[k] for k in ['windowVisible','windowFocused','mouseGrabbed']) for v in rows)}
if a.expect_failure:report['negativeControlDetected']=all(any(not row['pass'] and row['front']==front for row in rows) for front in ['hdr','png'])
(case/'custom-owner-check.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2));raise SystemExit(0 if report.get('negativeControlDetected',report['ok']) else 1)
