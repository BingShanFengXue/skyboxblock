from pathlib import Path
import importlib.util,json,numpy as np
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
def main():
 s=importlib.util.spec_from_file_location('edge',ROOT/'tools/check-r34-edges.py');edge=importlib.util.module_from_spec(s);s.loader.exec_module(edge)
 rows=[]
 for mode,layout,label in [('fsr2','mixed','ITRP FSR2 / material edge'),('vanilla','silhouette','Vanilla / geometric silhouette')]:
  pair=[]
  for prefix in ['r34-2-edge-control','r34-2-edge-fixed']:
   p=ROOT/'build/render-regression'/f'{prefix}-{mode}/results/edge-{layout}-static-00.png';im=Image.open(p).convert('RGB');box=(610,300,910,460)
   if layout=='silhouette':
    data=json.loads(p.with_suffix('.json').read_text(encoding='utf-8'));h,w=im.height,im.width;y,x=np.mgrid[:h,:w];owner,_=edge.ownership(data,x+.5,y+.5,w,h,layout);yy,xx=np.where(owner>=0);left=max(0,int(xx.min())-14);top=max(0,int(yy.min())-14);box=(left,top,left+300,top+160)
   pair.append(im.crop(box).resize((750,400),Image.Resampling.NEAREST))
  rows.append((label,pair,['Same JAR / original path','Same JAR / R34.2']))
 pair=[]
 for revision in ['1','2']:
  p=ROOT/f'build/render-regression/r34-{revision}-lod-edge-stages/r30-results/02-lod-only-sky-front.png';pair.append(Image.open(p).convert('RGB').crop((802,425,877,465)).resize((750,400),Image.Resampling.NEAREST))
 rows.append(('Native DH real LOD backdrop / separate runs, same pose',pair,['R34.1 superseded partial-background error','R34.2 one backdrop composition']))
 sheet=Image.new('RGB',(1500,len(rows)*450),'#202631');draw=ImageDraw.Draw(sheet)
 for i,(title,pair,labels) in enumerate(rows):
  for col,im in enumerate(pair):sheet.paste(im,(col*750,i*450+30));draw.text((col*750+8,i*450+8),title+' / '+labels[col],fill='white')
 out=ROOT/'docs/screenshots/r34-2-final-comparison-20261024.png';sheet.save(out)
 frames=[]
 for n in range(16):
  im=Image.new('RGB',(1200,340),'#202631');d=ImageDraw.Draw(im)
  for col,prefix in enumerate(['r34-2-edge-control','r34-2-edge-fixed']):
   raw=Image.open(ROOT/f'build/render-regression/{prefix}-fsr2/results/edge-mixed-static-{n:02d}.png').convert('RGB').crop((610,300,910,460)).resize((600,320),Image.Resampling.NEAREST);im.paste(raw,(col*600,20));d.text((col*600+8,3),('Original path' if col==0 else 'R34.2')+f' / captured sample {n:02d}',fill='white')
  frames.append(im)
 frames[0].save(ROOT/'docs/screenshots/r34-2-final-jitter-20261024.gif',save_all=True,append_images=frames[1:],duration=130,loop=0);print(out)
if __name__=='__main__':main()
