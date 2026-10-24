"""Compare independent edge oracle errors plus temporal residuals, not just nominal switches."""
from pathlib import Path
import argparse,json,importlib.util
import numpy as np
from PIL import Image,ImageFilter
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text(encoding='utf-8'))
def temporal(directory,layout):
 paths=sorted(directory.glob(f'edge-{layout}-static-*.json'));images=[];camera=[]
 for p in paths:
  d=load(p);camera.append(d['camera']+d['inverseDisplayViewProjection'])
  im=np.array(Image.open(p.with_suffix('.png')).convert('RGB'),np.float32)
  images.append(im)
 if not images:return None
 # The same geometry-selected mask is used in both runs. No actual colors select pixels.
 spec=importlib.util.spec_from_file_location('edges',ROOT/'tools/check-r34-edges.py');edges=importlib.util.module_from_spec(spec);spec.loader.exec_module(edges)
 h,w=images[0].shape[:2];y,x=np.mgrid[:h,:w];first=load(paths[0]);owner,_=edges.ownership(first,x+.5,y+.5,w,h,layout)
 edge=np.zeros((h,w),bool);edge[1:]|=owner[1:]!=owner[:-1];edge[:,1:]|=owner[:,1:]!=owner[:,:-1]
 mask=edges.dilate(edge,5)&edges.base.interior(owner>=0,7)&~edges.dilate(owner==-2,9)
 mask[:8]=False;mask[-8:]=False;mask[:,:8]=False;mask[:,-8:]=False
 stack=np.stack(images)
 return dict(frames=len(paths),sameCamera=bool(np.max(np.ptp(np.array(camera),axis=0))<1e-6),meanTemporalStd=float(np.std(stack,axis=0)[mask].mean()),pixels=int(mask.sum()))
def main():
 p=argparse.ArgumentParser();p.add_argument('--baseline',required=True);p.add_argument('--fixed',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();rows=[];checks=[]
 for env in ['fsr2','native','vanilla']:
  b=load(ROOT/'build/reports'/f'{a.baseline}-{env}-oracle.json');f=load(ROOT/'build/reports'/f'{a.fixed}-{env}-oracle.json')
  checks.append(dict(name='fixed oracle passes '+env,passed=f['passed']))
  for layout in ['mixed','silhouette','foreground']:
   for mode in ['static','walk']:
    select=lambda q:[r['edgeMAE'] for r in q['results'] if r['layout']==layout and f'-{mode}-' in r['scene'] and r['edgeMAE'] is not None]
    old=float(np.mean(select(b)));new=float(np.mean(select(f)));improvement=(old-new)/old if old else 0
    row=dict(environment=env,layout=layout,mode=mode,baselineMAE=old,fixedMAE=new,reduction=improvement);rows.append(row)
    if (env=='fsr2' and layout=='mixed' and mode=='static') or (env=='vanilla' and layout=='silhouette' and mode=='walk'):
     checks.append(dict(name='target edge improvement '+env,passed=improvement>.25,**row))
  if env!='vanilla':
   for tag,q in [('control',b),('fixed',f)]:
    phases={tuple(r['jitter']) for r in q['results'] if r['layout']=='mixed' and '-static-' in r['scene']}
    checks.append(dict(name='actual distinct jitter phases '+env+' '+tag,passed=len(phases)>=4,phases=len(phases)))
   old=temporal(ROOT/'build/render-regression'/f'{a.baseline}-{env}'/'results','mixed');new=temporal(ROOT/'build/render-regression'/f'{a.fixed}-{env}'/'results','mixed')
   rows.append(dict(environment=env,temporalBaseline=old,temporalFixed=new))
   checks.append(dict(name='fixed camera and actual jitter phases '+env,passed=old['sameCamera'] and new['sameCamera']))
 report=dict(passed=all(c['passed'] for c in checks),checks=checks,results=rows)
 a.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2));return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
