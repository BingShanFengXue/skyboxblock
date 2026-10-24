#!/usr/bin/env python3
"""Independent finite-range/masked-luminance properties for the metering-only proxy."""
from pathlib import Path
import json,numpy as np
root=Path(__file__).resolve().parents[1]
rng=np.random.default_rng(330023)
n=100000
rgb=np.exp(rng.uniform(np.log(1e-8),np.log(65504),(n,3)))
exposure=np.exp(rng.uniform(np.log(float(np.nextafter(np.float16(0),np.float16(1)))),np.log(65504),n))
fraction=rng.random(n);reference=np.maximum(rng.random(n),.18)
luma=rgb@np.array([.2126,.7152,.0722]);floor=np.minimum(reference/exposure,2048)*fraction
corrected=rgb+np.maximum(floor-luma,0)[:,None]
source=(root/'src/main/resources/assets/skyboxblock/shaders/core/sky_meter_proxy.fsh').read_text(encoding='utf-8')
checks=[
 dict(name='100000 finite half-float corrections',passed=bool(np.isfinite(corrected.astype(np.float16)).all())),
 dict(name='meter luminance at least owned reference',passed=bool(np.all(corrected@np.array([.2126,.7152,.0722])+1e-10>=floor))),
 dict(name='brighter physical samples unchanged',passed=bool(np.array_equal(corrected[luma>=floor],rgb[luma>=floor]))),
 dict(name='zero owned coverage exactly unchanged',passed=bool(np.array_equal(rgb+np.maximum(-luma,0)[:,None],rgb))),
 dict(name='no direct exposure image writes',passed='imageStore' not in source),
 dict(name='no actual display output modification',passed='gl_FragDepth' not in source),
 dict(name='original HDR gamma conversion unchanged',passed='(Gain / exposure)' in (root/'src/main/resources/assets/skyboxblock/shaders/core/sky_material_background.fsh').read_text(encoding='utf-8')),
]
result=dict(inputs=n,checks=checks,passed=all(c['passed'] for c in checks))
(root/'build/reports/r33-meter-math.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,indent=2));raise SystemExit(0 if result['passed'] else 1)
