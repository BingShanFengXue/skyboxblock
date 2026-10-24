from pathlib import Path
import importlib.util,json,numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('edge',ROOT/'tools/check-r34-edges.py');e=importlib.util.module_from_spec(s);s.loader.exec_module(e)
p=ROOT/'build/render-regression/r34-fixed-r1-fsr2/results/edge-foreground-resize.json';d=json.loads(p.read_text());a=e.image(p.with_suffix('.png'));h,w=a.shape[:2];y,x=np.mgrid[:h,:w];o,r=e.ownership(d,x+.5,y+.5,w,h,'foreground')
border=np.zeros((h,w),bool);border[1:]|=o[1:]!=o[:-1];border[:,1:]|=o[:,1:]!=o[:,:-1];border=e.dilate(border,5)
ey,ex=np.where(border&~e.dilate(o==-2,9));acc=np.zeros((len(ex),3),np.float32);coverage=np.zeros(len(ex))
for yy in [.125,.375,.625,.875]:
 for xx in [.125,.375,.625,.875]:
  oo,rr=e.ownership(d,ex+xx,ey+yy,w,h,'foreground');acc+=e.expected_colors(oo,rr)/16;coverage+=(oo>=0)/16
valid=coverage>.999;err=np.abs(a[ey,ex]-acc).mean(-1);bad=valid&(err>35)
print('valid',valid.sum(),'bad',bad.sum(),'MAE',err[valid].mean())
print('bad owners',[(int(k),int(np.sum(bad&(o[ey,ex]==k)))) for k in np.unique(o[ey,ex][bad])])
for i in np.where(bad)[0][::max(1,int(bad.sum()/20))]:print(int(ex[i]),int(ey[i]),'owner',int(o[ey[i],ex[i]]),'actual',a[ey[i],ex[i]].tolist(),'ideal',acc[i].tolist(),'error',float(err[i]))
heat=np.uint8(a*.25);heat[ey[bad],ex[bad]]=[255,0,0];Image.fromarray(heat).save(ROOT/'build/reports/r34-foreground-resize-error.png')
