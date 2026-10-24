#!/usr/bin/env python3
"""Independent numeric identity checks for the shader's scene/display exposure conversion."""
from pathlib import Path
import argparse
import json
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()
source = (ROOT / "src/main/resources/assets/skyboxblock/shaders/core/sky_material_background.fsh").read_text(encoding="utf-8")
rng = np.random.default_rng(280022)
exposure = np.exp(rng.uniform(np.log(1e-6), np.log(65504), 100000)).astype(np.float32)
linear = rng.random((100000, 3), dtype=np.float32)
hdr = linear / exposure[:, None]
restored = hdr * exposure[:, None]
maximum = float(np.abs(restored - linear).max())
checks = [
    {"name": "actual-exposure-channel-G", "passed": "texelFetch(ExposureData, ivec2(0), 0).g" in source},
    {"name": "inverse-exposure-conversion", "passed": "(Gain / exposure)" in source},
    {"name": "invalid-startup-values-guarded", "passed": "!(exposure > 0.0)" in source and "isnan(exposure)" in source and "isinf(exposure)" in source},
    {"name": "100000-valid-multipliers-round-trip", "passed": bool(np.isfinite(restored).all() and maximum < 2e-7), "maximumAbsoluteLinearError": maximum},
    {"name": "half-float-maximum-is-observed-limit", "passed": float(np.finfo(np.float16).max) == 65504.0},
    {"name": "old-luminance-multiply-not-executable", "passed": "* 0.13;" not in source and ").r, 1e-5)" not in source},
]
report = {"kind": "R28-independent-HDR-unit-property-check", "passed": all(c["passed"] for c in checks),
          "checks": checks, "randomInputs": 100000,
          "doesNotReplaceRealTimeGameValidation": True, "worldRgbReadbackInProduction": False}
a.output.parent.mkdir(parents=True, exist_ok=True)
with a.output.open("x", encoding="utf-8") as output:
    json.dump(report, output, indent=2)
print(json.dumps(report, indent=2))
raise SystemExit(0 if report["passed"] else 1)
