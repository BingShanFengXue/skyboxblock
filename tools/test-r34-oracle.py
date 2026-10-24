"""Counterfactual checker test: retain GPU metadata, corrupt only captured final colors."""
from pathlib import Path
import shutil,subprocess,sys,json,argparse
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--label',default='initial');args=p.parse_args()
 source=ROOT/'build/render-regression/r34-smoke-r1-fsr2/results';destination=ROOT/'build'/f'r34-oracle-counterfactual-{args.label}';destination.mkdir(exist_ok=False)
 scene='edge-mixed-static-00'
 for suffix in ['.json','.png','-before.png','-post-hand-mask.png']:shutil.copy2(source/(scene+suffix),destination/(scene+suffix))
 image=np.array(Image.open(destination/(scene+'.png')).convert('RGB'));image=np.roll(image,40,axis=1);Image.fromarray(image).save(destination/(scene+'.png'))
 out=ROOT/'build/reports'/f'r34-oracle-counterfactual-{args.label}.json';code=subprocess.call([sys.executable,str(ROOT/'tools/check-r34-edges.py'),str(destination),'--partial','--output',str(out)],cwd=ROOT)
 report=json.loads(out.read_text(encoding='utf-8'));assert code==1 and any(f['name'] in ['interior original colors','bounded geometric edge error'] for f in report['failures'])
 print('PASS: shifted final RGB detected with unchanged metadata and nominal 4x flags')
if __name__=='__main__':main()
