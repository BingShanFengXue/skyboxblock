from pathlib import Path
import importlib.util,numpy as np,json,time
ROOT=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('edge',ROOT/'tools/check-r34-edges.py');e=importlib.util.module_from_spec(s);s.loader.exec_module(e)
rng=np.random.default_rng(343434);n=100000;directions=rng.normal(size=(n,3));directions[:1000,0]=0;directions[1000:2000,1]=0;directions[2000:3000,2]=0
checks=[]
for origin in [np.array([-.625,141.62,-3.375]),np.array([0.,140.,5.]),np.zeros(3)]:
 for lo,hi in [([-7,140,5],[0,153,6]),([-28,139,-10],[29,140,13]),([-2,140,2],[-1,150,3])]:
  with np.errstate(divide='ignore',invalid='ignore'):
   a=(np.array(lo)-origin)/directions;b=(np.array(hi)-origin)/directions;near=np.max(np.minimum(a,b),axis=-1);far=np.min(np.maximum(a,b),axis=-1)
  old=np.where((far>=np.maximum(near,0))&(far>0),np.maximum(near,0),np.inf);new=e.intersect(origin,directions,lo,hi)
  same=np.array_equal(old.view(np.uint64),new.view(np.uint64));assert same;checks.append(dict(origin=origin.tolist(),low=lo,high=hi,bitExact=True,rays=n))
report=dict(passed=True,rayCases=n*len(checks),cases=checks)
(ROOT/'build/reports/r34-oracle-slab-equivalence.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print('PASS:',report['rayCases'],'axis-wise slab results exactly equal to original vector reductions, including zero directions')
