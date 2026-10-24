from pathlib import Path
import importlib.util,json
ROOT=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('compare',ROOT/'tools/compare-r34-edges.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
rows={}
for tag,prefix in [('control','r34-control-r2'),('fixed','r34-fixed-r1')]:
 rows[tag]=m.temporal(ROOT/'build/render-regression'/f'{prefix}-fsr2/results','mixed')
rows['reduction']=1-rows['fixed']['meanTemporalStd']/rows['control']['meanTemporalStd']
(ROOT/'build/reports/r34-fsr2-temporal-early.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8');print(json.dumps(rows,indent=2))
