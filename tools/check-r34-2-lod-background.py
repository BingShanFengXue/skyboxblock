"""Verify native backdrop recovery against same-frame unpolluted DH source and physical holdouts."""
from pathlib import Path
import argparse,json,importlib.util,numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def rgb(p):return np.asarray(Image.open(p).convert('RGB'),dtype=np.float64)
def main():
 p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();d=a.directory.resolve();r=d/'r30-results';run=read(d/'run-result.json');owner=read(d/'r30-owned.json');summary=read(r/'run-summary.json')
 assert run['exitCode']==0 and run['complete'] and not run['errors'] and not run['fatalErrors'] and run['inputBytesUnchanged']
 spec=importlib.util.spec_from_file_location('lod',ROOT/'tools/check-r30-lod-occlusion.py');lod=importlib.util.module_from_spec(spec);spec.loader.exec_module(lod)
 ordered=lod.evaluate(d);assert ordered['passed']
 gold=rgb(r/'00-lod-only-gold-positive.png');hold=rgb(r/'03-lod-only-no-sky-holdout.png');h,w=gold.shape[:2];goldroi=lod.mask(summary['phases'][0],w,h,gold=True,gold_z=owner['controlledLodOcclusion']['goldWall']['z']);stable=goldroi&lod.gold_signal(gold)&lod.gold_signal(hold)&(np.max(np.abs(gold-hold),axis=-1)<2);rows=[]
 for phase in summary['phases']:
  stages=phase['r34EdgeStages'];assert set(stages)=={'opaque','pre','post'} and all(s['transaction']==phase['rootTransaction'] and s['initialGlError']==s['finalGlError']==0 for s in stages.values())
  if 'layer' not in stages['post']:continue
  s=stages['post'];assert s['nativeBackgroundReady'] and 'nativeBackground' in s
  layer=np.asarray(Image.open(r/s['layer']).convert('RGBA'),dtype=np.float64);background=rgb(r/s['nativeBackground']);final=rgb(r/s['color']);pre=rgb(r/stages['pre']['color']);opaque=rgb(r/stages['opaque']['color']);alpha=layer[...,3]/255
  _,drawn=lod.depth_eye(phase,r,w,h);partial=(alpha>0)&(alpha<1);repair=partial&drawn;retain=partial&~drawn;empty=alpha==0;full=alpha==1
  expected=np.clip(layer[...,:3]+(1-alpha[...,None])*background,0,255);error=np.abs(final-expected);changed=np.any(final!=pre,axis=-1)
  row=dict(name=phase['name'],rootTransaction=phase['rootTransaction'],nativeSourceTexture=s['nativeBackgroundColorTexture'],partialPixels=int(partial.sum()),drawnBackgroundPartialPixels=int(repair.sum()),preservedPartialPixels=int(retain.sum()),repairedPixelChanges=int(np.count_nonzero(changed&repair)))
  assert not np.any(changed&retain),('double blend on no-DH fringe',row)
  assert not np.any(changed&empty),('foreground repainted',row)
  if repair.any():
   row.update(idealOneCompositeMAE=float(error[repair].mean()),idealOneCompositeMaximum=float(error[repair].max()));assert row['idealOneCompositeMAE']<=1 and row['idealOneCompositeMaximum']<=2,row
  if full.any():assert float(np.max(np.abs(final-layer[...,:3])[full]))<=1
  if phase['name']=='lod-only-sky-front':
   eligible=repair&stable;assert eligible.sum()>20
   row.update(physicalGoldBackgroundPixels=int(eligible.sum()),nativeSourceVersusPhysicalGoldMAE=float(np.abs(background-gold)[eligible].mean()),physicalGoldOneCompositeMAE=float(np.abs(final-(layer[...,:3]+(1-alpha[...,None])*gold))[eligible].mean()))
   assert row['nativeSourceVersusPhysicalGoldMAE']<1 and row['physicalGoldOneCompositeMAE']<1,row
   assert row['repairedPixelChanges']>100,row
  rows.append(row)
 result=dict(passed=True,productionJarSha256=owner['productionJarSha256'],ordering=ordered,stages=rows,scope='same-frame native 3.3.3 apply-source texture; actual gold positive plus holdout; no already composed MC color')
 a.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2));return 0
if __name__=='__main__':raise SystemExit(main())
