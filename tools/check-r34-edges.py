"""Independent box-ray edge oracle: no renderer coverage IDs or private layer pixels used as truth."""
from pathlib import Path
import argparse,importlib.util,json
import numpy as np
from PIL import Image,ImageFilter,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('base',ROOT/'tools/check-r31-materials.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
STYLES=['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']
def image(p):return np.array(Image.open(p).convert('RGB'),dtype=np.float32)
def dilate(mask,n=3):return np.array(Image.fromarray(np.uint8(mask)*255).filter(ImageFilter.MaxFilter(n)))>0
def ray(data,x,y,w,h):
 inv=np.array(data['inverseDisplayViewProjection'],float).reshape((4,4),order='F')
 clip=np.stack((x/w*2-1,1-y/h*2,np.zeros_like(x),np.ones_like(x)),axis=-1);a=clip@inv.T;clip[...,2]=-.8;b=clip@inv.T
 direction=a[...,:3]/a[...,3:4]-b[...,:3]/b[...,3:4];direction/=np.linalg.norm(direction,axis=-1,keepdims=True)
 origin=inv[:,2];origin=origin[:3]/origin[3]+np.array(data['camera'])
 return origin,direction

def boxes(layout):
 if layout=='silhouette':return [(1,[-4,140,5],[5,150,6])]
 return [(8,[-12,140,8],[13,156,9]),(1,[-7,140,5],[0,153,6]),(2,[0,140,5],[7,153,6]),(6,[-3,144,4],[3,149,5])]
def intersect(origin,direction,lo,hi):
 # Identical slab equations, evaluated axis-wise to avoid large Nx3 temporary reductions.
 near=np.full(direction.shape[:-1],-np.inf);far=np.full(direction.shape[:-1],np.inf)
 with np.errstate(divide='ignore',invalid='ignore'):
  for axis in range(3):
   a=(lo[axis]-origin[axis])/direction[...,axis];b=(hi[axis]-origin[axis])/direction[...,axis]
   near=np.maximum(near,np.minimum(a,b));far=np.minimum(far,np.maximum(a,b))
 return np.where((far>=np.maximum(near,0))&(far>0),np.maximum(near,0),np.inf)
def ownership(data,x,y,w,h,layout):
 origin,direction=ray(data,x,y,w,h);best=np.full(x.shape,np.inf);owner=np.full(x.shape,-1,np.int16)
 for style,lo,hi in boxes(layout):
  t=intersect(origin,direction,lo,hi);take=t<best;best[take]=t[take];owner[take]=style
 # The real sandstone walking platform is a foreground occluder in EVERY layout.
 # Its contact edge is protected by the existing Iris foreground-history guard, not a sky-material edge.
 foreground=[([-28,139,-10],[29,140,13])]
 if layout=='foreground':foreground += [([-2,140,2],[-1,150,3]),([2,140,2],[3,150,3])]
 for lo,hi in foreground:
  t=intersect(origin,direction,lo,hi);owner[t<best]=-2
 return owner,direction

def expected_colors(owners,rays):
 out=np.zeros(owners.shape+(3,),np.float32)
 for s in np.unique(owners):
  if s<0:continue
  mask=owners==s
  out[mask]=base.expected(STYLES[s],rays[mask])
 return out

def main():
 p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--baseline',action='store_true');p.add_argument('--partial',action='store_true');a=p.parse_args()
 checks=[];rows=[];cache={};worst=None
 def need(name,ok,scene=None,**info):checks.append(dict(name=name,passed=bool(ok),scene=scene,**info))
 paths=sorted(a.directory.glob('edge-*.json'))
 for frame_index,path in enumerate(paths):
  if frame_index%30==0:print('CHECK',frame_index,'/',len(paths),path.stem,flush=True)
  d=json.loads(path.read_text(encoding='utf-8'));name=d['scene'];layout=name.split('-')[1]
  need('GL and raster state',d['initialGlError']==0 and d['finalGlError']==0 and d['outsideRasterChangedPixels']==0 and d['outsideRasterDepthChangedPixels']==0,name)
  need('Iris state restored',d['irisMainBoundBefore']==d['irisMainBoundAfter'] and d['irisOverridesBefore']==d['irisOverridesAfter'],name)
  if name.endswith('removed'):
   need('removed sky absent',d['blocks']==0 and d['depthChangedPixels']==0,name);continue
  need('fixture stays on walking platform',abs(d['camera'][0])<4 and 140<d['camera'][1]<143 and abs(d['camera'][2])<8,name,camera=d['camera'])
  if not a.baseline:need('real 4x coverage path',d.get('surfaceLayerUsed') and d.get('surfaceLayerSamples')==4,name)
  actual=image(a.directory/f'{name}.png');h,w=actual.shape[:2]
  key=(layout,w,h,tuple(d['camera']),tuple(d['inverseDisplayViewProjection']))
  if key not in cache:
   y,x=np.mgrid[:h,:w];owner,rays=ownership(d,x+.5,y+.5,w,h,layout)
   # Every adjacent owner transition is derived from geometry, not actual pixel colors.
   edge=np.zeros((h,w),bool);edge[1:]|=owner[1:]!=owner[:-1];edge[:,1:]|=owner[:,1:]!=owner[:,:-1]
   edge=dilate(edge,5);valid=owner>=0;inside=base.interior(valid,7)
   inner=edge&base.interior(valid,3);inner&=~dilate(owner==-2,9)
   reference=expected_colors(owner,rays)
   # Independent 16-point area quadrature only around edges. Counts the actual cube silhouettes.
   ey,ex=np.where(edge&~dilate(owner==-2,9));acc=np.zeros((len(ex),3),np.float32);coverage=np.zeros(len(ex),np.float32)
   for sy in [.125,.375,.625,.875]:
    for sx in [.125,.375,.625,.875]:
     sub,rr=ownership(d,ex+sx,ey+sy,w,h,layout);acc+=expected_colors(sub,rr)/16;coverage+=(sub>=0)/16
   if len(cache)>4:cache.clear()
   cache[key]=(owner,inside,inner,reference,ey,ex,acc,coverage)
  owner,inside,inner,reference,ey,ex,acc,coverage=cache[key]
  hand=np.array(Image.open(a.directory/f'{name}-post-hand-mask.png').convert('L').resize((w,h),Image.Resampling.NEAREST))>0
  inside=inside&~dilate(hand,9);inner=inner&~dilate(hand,9)
  # Exclude foreground, hand and screen margin; interior material colors must remain exact.
  inside[:5]=False;inside[-5:]=False;inside[:,:5]=False;inside[:,-5:]=False
  error=np.abs(actual-reference).mean(axis=-1)
  interior=float(error[inside].mean()) if inside.any() else None
  need('interior original colors',interior is not None and interior<2.0,name,mae=interior)
  border_actual=actual[ey,ex];ideal=acc.copy();allsky=coverage>.999
  if not d.get('itrpMaterialWrites',False):
   background=image(a.directory/f'{name}-before.png');ideal+=(1-coverage[:,None])*background[ey,ex]
   valid=(coverage>0)&~hand[ey,ex]
  else:valid=allsky&~hand[ey,ex]
  # Area oracle and 4-sample hardware differ by <=1/4 at subpixel edges; aggregate metric is useful, not pixel-perfect.
  err=np.abs(border_actual-ideal).mean(axis=-1)
  edge_mae=float(err[valid].mean()) if valid.any() else None
  wrong=float(np.mean(err[valid]>35)) if valid.any() else None
  if layout!='silhouette' or not d.get('itrpMaterialWrites',False):
   need('bounded geometric edge error',edge_mae is not None and edge_mae<16 and wrong<.16,name,mae=edge_mae,largeErrorFraction=wrong)
  rows.append(dict(scene=name,layout=layout,edgeMAE=edge_mae,largeErrorFraction=wrong,interiorMAE=interior,innerEdgePixels=int(inner.sum()),camera=d['camera'],view=d['worldView'],jitter=d['rasterJitter'],moving=d['panoramaMotion']))
  if edge_mae is not None and (worst is None or edge_mae>worst[0]):worst=(edge_mae,name,actual.copy(),ey.copy(),ex.copy(),err.copy())
 groups={}
 for r in rows:
  if '-static-' in r['scene']:groups.setdefault(r['layout'],[]).append(r)
 for layout,group in groups.items():
  need('static phase count',len(group)==32 or a.partial,layout,count=len(group),distinctJitters=len({tuple(x['jitter']) for x in group}))
 walks=[r for r in rows if '-walk-' in r['scene']]
 for layout in {r['layout'] for r in walks}:
  q=[r for r in walks if r['layout']==layout];movement=float(np.linalg.norm(np.ptp(np.array([r['camera'] for r in q]),axis=0)))
  need('real walking',all(r['moving'] for r in q) and movement>.2,layout,metres=movement)
 need('complete edge scenes',len(paths)==180 or a.partial,None,scenes=len(paths))
 if worst:
  score,name,im,ey,ex,err=worst;heat=np.zeros_like(im,dtype=np.uint8);heat[ey,ex,0]=np.uint8(np.clip(err*4,0,255))
  sheet=Image.new('RGB',(im.shape[1]*2,im.shape[0]+28),'#202631');sheet.paste(Image.fromarray(np.uint8(im)),(0,0));sheet.paste(Image.fromarray(heat),(im.shape[1],0));ImageDraw.Draw(sheet).text((8,im.shape[0]+6),f'{name} | actual / independent edge error x4',fill='white');sheet.save(a.output.with_suffix('.png'))
 failures=[c for c in checks if not c['passed']]
 report=dict(scenes=len(paths),checks=len(checks),passedChecks=len(checks)-len(failures),failures=failures,results=rows,validation=checks,baseline=a.baseline,passed=not failures)
 a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
 print(json.dumps(dict(scenes=len(paths),checks=len(checks),failures=failures[:12],failedChecks=len(failures),meanEdgeMAE=float(np.mean([r['edgeMAE'] for r in rows if r['edgeMAE'] is not None]))),indent=2))
 return 0 if report['passed'] or a.baseline else 1
if __name__=='__main__':raise SystemExit(main())
