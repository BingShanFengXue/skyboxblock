#!/usr/bin/env python3
"""Retain the R26 blue-halo criterion with version-independent installed-JAR identity."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("world", ROOT / "tools/check-world-regression.py")
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("before", type=Path)
p.add_argument("after", type=Path)
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()
read = lambda f: json.loads(f.read_text(encoding="utf-8"))
left, right = read(a.before / "fixture.json"), read(a.after / "fixture.json")
mods = lambda d: {f.name: sha(f) for f in (d / "mods").glob("*.jar") if not f.name.startswith("skyboxblock-")}
packs = lambda d: {f.name: sha(f) for f in (d / "shaderpacks").glob("*.zip")}
checks = {"sameShaderOptions": left["shaderOptions"] == right["shaderOptions"],
          "sameSupportingMods": mods(a.before) == mods(a.after), "sameShaderArchive": packs(a.before) == packs(a.after),
          "afterInstalledJarMatchesDeclaration": sha(next((a.after / "mods").glob("skyboxblock-*.jar"))) == right["jarSha256"]}
rows = []
for scene in ("blue-night-wall", "blue-night-settled", "blue-return-night"):
    dirs = [d / "results" for d in (a.before, a.after)]
    metas = [read(d / (scene + ".json")) for d in dirs]
    arrays = [np.asarray(Image.open(d / (scene + ".png")).convert("RGB"), dtype=np.float32) for d in dirs]
    m = metas[0]
    direction = w.rays(dict(m, inverseViewProjection=m["inverseDisplayViewProjection"]), m["width"], m["height"])
    origin = np.asarray(m["camera"])
    with np.errstate(divide="ignore", invalid="ignore"):
        t = (180 - origin[1]) / direction[..., 1]
        point = origin + direction * t[..., None]
    floor = (direction[..., 1] < 0) & (t > 0) & (point[..., 0] > -7) & (point[..., 0] < 8) & (point[..., 2] > 1) & (point[..., 2] < 5)
    stats = []
    for image in arrays:
        rgb = image[floor]
        excess = np.maximum(rgb[:, 2] - np.maximum(rgb[:, 0], rgb[:, 1]), 0)
        stats.append({"pixels": len(rgb), "meanBlueExcess": float(excess.mean()),
                      "p99BlueExcess": float(np.percentile(excess, 99)), "blueExcessOver3": int((excess > 3).sum())})
    geometry = all(metas[0][k] == metas[1][k] for k in ("width", "height", "camera", "blocks", "vertices", "cloudWorldHeight"))
    reduction = 1 - stats[1]["meanBlueExcess"] / max(stats[0]["meanBlueExcess"], 1e-9)
    rows.append({"scene": scene, "sameGeometryAndPose": geometry, "before": stats[0], "after": stats[1],
                 "blueExcessReduction": reduction, "passed": geometry and reduction > .8})
report = {"kind": "R28-retained-R26-night-blue-halo-criterion", "reference": str(a.before), "candidate": str(a.after),
          "identityChecks": checks, "measurements": rows, "passed": all(checks.values()) and all(r["passed"] for r in rows),
          "bloomDisabled": False, "historicalReferenceNotFreshCausalPair": True, "portalTestsPerformed": False}
a.output.parent.mkdir(parents=True, exist_ok=True)
with a.output.open("x", encoding="utf-8") as f:
    json.dump(report, f, indent=2)
print(json.dumps(report, indent=2))
raise SystemExit(0 if report["passed"] else 1)
