from pathlib import Path
import json
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/screenshots/r34-edge-comparison-20261024.png'
def main():
 rows=[]
 for env,layout,box,label in [('fsr2','mixed',(610,300,910,460),'ITRP FSR2 / mixed boundary'),('vanilla','silhouette',(0,0,1280,720),'Vanilla / sky silhouette')]:
  pair=[]
  for prefix in ['r34-control-r2','r34-fixed-r1']:
   path=ROOT/'build/render-regression'/f'{prefix}-{env}'/'results'/f'edge-{layout}-static-00.png'
   im=Image.open(path).convert('RGB')
   if layout=='silhouette':
    # Keep identical geometry-selected crop centered on the upper-left projected white panel corner.
    spec=__import__('importlib.util',fromlist=['util']);s=spec.spec_from_file_location('edge_oracle',ROOT/'tools/check-r34-edges.py');m=spec.module_from_spec(s);s.loader.exec_module(m)
    import numpy as np
    data=json.loads(path.with_suffix('.json').read_text(encoding='utf-8'));h,w=im.height,im.width;y,x=np.mgrid[:h,:w];owner,_=m.ownership(data,x+.5,y+.5,w,h,layout)
    yy,xx=np.where(owner>=0);box=(max(0,int(xx.min())-14),max(0,int(yy.min())-14),min(w,int(xx.min())+286),min(h,int(yy.min())+146))
   pair.append(im.crop(box).resize((750,400),Image.Resampling.NEAREST))
  rows.append((label,pair))
 sheet=Image.new('RGB',(1500,900),'#202631');draw=ImageDraw.Draw(sheet)
 for i,(label,pair) in enumerate(rows):
  y=i*450
  for k,im in enumerate(pair):sheet.paste(im,(750*k,y+30));draw.text((750*k+10,y+8),label+(' / old path' if k==0 else ' / R34 coverage'),fill='white')
 sheet.save(OUT);print(OUT)
 # Real consecutive selected jitter phases; no synthetic interpolation.
 frames=[]
 for n in range(16):
  pair=Image.new('RGB',(1200,340),'#202631');draw=ImageDraw.Draw(pair)
  for col,prefix in enumerate(['r34-control-r2','r34-fixed-r1']):
   im=Image.open(ROOT/'build/render-regression'/f'{prefix}-fsr2/results'/f'edge-mixed-static-{n:02d}.png').convert('RGB').crop((610,300,910,460)).resize((600,320),Image.Resampling.NEAREST)
   pair.paste(im,(col*600,20));draw.text((col*600+8,3),('Old path' if col==0 else 'R34')+f' / real phase {n:02d}',fill='white')
  frames.append(pair)
 frames[0].save(ROOT/'docs/screenshots/r34-itrp-jitter-20261024.gif',save_all=True,append_images=frames[1:],duration=130,loop=0)
if __name__=='__main__':main()
