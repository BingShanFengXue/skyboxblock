"""Deterministic sample-depth / premultiplied-resolution properties. Not a substitute for real GPU scenes."""
from pathlib import Path
import json,numpy as np
ROOT=Path(__file__).resolve().parents[1]
def main():
 rng=np.random.default_rng(340024);count=100000
 colors=rng.random((count,4,3));covered=rng.random((count,4))>.35;background=rng.random((count,3))
 color=np.mean(colors*covered[...,None],axis=1);alpha=covered.mean(axis=1)
 composed=color+background*(1-alpha[:,None]);independent=np.mean(np.where(covered[...,None],colors,background[:,None,:]),axis=1)
 assert np.allclose(composed,independent,atol=1e-14)
 assert np.all((composed>=0)&(composed<=1))
 old=color*alpha[:,None]+background*(1-alpha[:,None]);negative=int(np.sum(np.max(np.abs(old-independent),axis=1)>1e-8));assert negative>50000
 # Nearest owner per independently covered sample is invariant to batch order.
 depths=rng.random((count,4,6));visible=rng.random((count,4,6))>.25;depths=np.where(visible,depths,np.inf)
 oracle=np.argmin(depths,axis=-1);none=np.all(~visible,axis=-1);oracle[none]=-1
 for order in [np.arange(6),np.arange(5,-1,-1),rng.permutation(6),rng.permutation(6)]:
  z=np.full((count,4),np.inf);owner=np.full((count,4),-1)
  for batch in order:
   take=depths[...,batch]<z;z[take]=depths[...,batch][take];owner[take]=batch
  assert np.array_equal(owner,oracle)
 cfg=json.loads((ROOT/'src/main/resources/assets/skyboxblock/shaders/core/sky_surface_composite.json').read_text(encoding='utf-8'))['blend']
 assert cfg==dict(func='add',srcrgb='one',dstrgb='1-srcalpha',srcalpha='zero',dstalpha='one')
 report=dict(passed=True,samples=count,nearestOwnerOrders=4,nearestOwnerSamples=count*4*4,premultipliedProperties=count*3,oldAlphaSquaredNegativeDetections=negative,actualShaderBlend=cfg)
 (ROOT/'build/reports/r34-coverage-properties.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
