#!/usr/bin/env python3
"""Same-position vanilla/Sodium AO oracle; geometry masks are fixed before reading RGB."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("world_oracle", ROOT / "tools/check-world-regression.py")
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)
MATERIALS = ("air", "glass", "stone", "sky", "removed")
LIMITS = {"meanAbsolute": 0.75, "p99Absolute": 2.0, "maxAbsolute": 3}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image(path):
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB"), dtype=np.float32)


def difference(reference, sample, mask):
    a, b = reference[mask], sample[mask]
    if not len(a):
        raise ValueError("empty independently selected surface")
    absolute = np.abs(b - a)
    signed = (b - a).mean(axis=1)
    return {"pixels": len(a), "meanAbsolute": float(absolute.mean()),
            "p99Absolute": float(np.percentile(absolute, 99)), "maxAbsolute": int(absolute.max()),
            "meanSignedRgb": (b - a).mean(axis=0).tolist(),
            "meanDarkening": float((-signed).mean()), "p99Darkening": float(np.percentile(-signed, 99)),
            "darkenedOver3": int((signed < -3).sum()), "referenceMeanRgb": a.mean(axis=0).tolist(),
            "sampleMeanRgb": b.mean(axis=0).tolist()}


def equal(measurement):
    return all(measurement[k] <= v for k, v in LIMITS.items())


def masks(meta):
    # Fixture solid bounds are specified here, not inferred from changed/color pixels.
    h, width = meta["height"], meta["width"]
    raymeta = dict(meta, inverseViewProjection=meta["inverseDisplayViewProjection"])
    directions = w.rays(raymeta, width, h)
    origin = np.asarray(meta["camera"])
    plane = w.box_distance(origin, directions, ([-3, 180, 3], [4, 185, 4]))
    boxes = {"floor": ([-7, 179, -8], [8, 180, 8])}
    if meta["scene"].startswith("ao-corner-"):
        boxes |= {"leftWall": ([-4, 180, 1], [-3, 186, 6]),
                  "rightWall": ([4, 180, 1], [5, 186, 6]),
                  "ceiling": ([-3, 185, 1], [4, 186, 6])}
    distances = {key: w.box_distance(origin, directions, box) for key, box in boxes.items()}
    output = {}
    for key, distance in distances.items():
        others = np.minimum.reduce([plane] + [d for k, d in distances.items() if k != key])
        visible = np.isfinite(distance) & (distance < others - 1e-5)
        visible = w.erode(visible, 3)
        output[key] = visible
        with np.errstate(invalid="ignore"):
            point_z = origin[2] + directions[..., 2] * distance
        output[key + "ContactBand"] = visible & (point_z >= 2.05) & (point_z <= 2.95)
    return output


def jar_entries(path):
    with zipfile.ZipFile(path) as z:
        return {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith("/")}


def inspect(instance, expected, old_instance=None):
    directory = instance / "results"
    fixture = json.loads((instance / "fixture.json").read_text(encoding="utf-8"))
    run = json.loads((instance / "run-result.json").read_text(encoding="utf-8"))
    jars = list((instance / "mods").glob("skyboxblock-*.jar"))
    if len(jars) != 1:
        raise ValueError("expected one actual skyboxblock fixture jar")
    checks, measurements = [], []

    def check(name, passed, details=None):
        checks.append({"name": name, "passed": bool(passed), "details": details})

    check("jar-identity", sha(jars[0]) == fixture["jarSha256"])
    check("no-shader-pack", fixture["shader"] is None)
    check("owned-run-complete", run["exitCode"] == 0 and run["finished"] and run["scenes"] == 40)
    check("runtime-errors-zero", not run["errors"] and not run["renderThreadViolations"] and not run["fatalErrors"])
    names = [f"ao-{layout}-{time}-{ao}-{material}" for layout in ("floor", "corner")
             for time in ("day", "night") for ao in ("on", "off") for material in MATERIALS]
    check("exact-40-scenes", {p.stem for p in directory.glob("*.json")} == set(names))
    for layout in ("floor", "corner"):
        for time in ("day", "night"):
            for ao in ("on", "off"):
                base = f"ao-{layout}-{time}-{ao}"
                metas = {m: json.loads((directory / f"{base}-{m}.json").read_text(encoding="utf-8")) for m in MATERIALS}
                images = {m: image(directory / f"{base}-{m}.png") for m in MATERIALS}
                reference = metas["air"]
                surfaces = masks(reference)
                for material, meta in metas.items():
                    info = meta["ambientOcclusion"]
                    check(f"{base}-{material}:pose-and-raster", all(meta[k] == reference[k] for k in (
                        "camera", "inverseDisplayViewProjection", "width", "height")))
                    check(f"{base}-{material}:gl-zero", meta["initialGlError"] == meta["finalGlError"] == 0)
                    check(f"{base}-{material}:fixture-mode", info["smoothLighting"] == (ao == "on") and not info["irisActive"])
                    check(f"{base}-{material}:plane-count", info["planeBlockCount"] == 35)
                    if material in ("air", "removed", "glass", "sky"):
                        check(f"{base}-{material}:physical-light", info["physicalLightSamples"] == reference["ambientOcclusion"]["physicalLightSamples"])
                        check(f"{base}-{material}:no-emission-or-light-block", info["emission"] == info["lightBlock"] == 0 and info["propagatesSkylight"])
                    check(f"{base}-{material}:skylight-only-protocol", all(sample[4] == 0 for sample in info["physicalLightSamples"]))
                    if material == "sky":
                        check(f"{base}-{material}:collision-preserved", info["fullCollision"] and not info["solidRender"])
                        check(f"{base}-{material}:ao-scalar", abs(info["shadeBrightness"] - (0.2 if expected == "legacy" else 1.0)) < 1e-6, info["shadeBrightness"])
                for surface, mask in surfaces.items():
                    check(f"{base}:{surface}:sample-population", int(mask.sum()) >= 100, int(mask.sum()))
                    for material in ("glass", "stone", "sky", "removed"):
                        measure = difference(images["air"], images[material], mask)
                        measurements.append({"sceneGroup": base, "surface": surface, "material": material, **measure})
                        if material in ("glass", "removed") or (material == "sky" and (expected == "fixed" or ao == "off")):
                            check(f"{base}:{surface}:{material}:air-equivalence", equal(measure), measure)
                    if ao == "on" and surface.endswith("ContactBand"):
                        sky = difference(images["air"], images["sky"], mask)
                        stone = difference(images["air"], images["stone"], mask)
                        check(f"{base}:{surface}:opaque-negative-control", stone["meanDarkening"] > 2 and stone["darkenedOver3"] > 100, stone)
                        if expected == "legacy":
                            check(f"{base}:{surface}:legacy-shadow-reproduced", sky["meanDarkening"] > 2 and sky["darkenedOver3"] > 100, sky)
    pair = None
    if old_instance is not None:
        old_jars = list((old_instance / "mods").glob("skyboxblock-*.jar"))
        old_entries, new_entries = jar_entries(old_jars[0]), jar_entries(jars[0])
        delta = [n for n in sorted(set(old_entries) | set(new_entries)) if old_entries.get(n) != new_entries.get(n)]
        check("pair:only-sky-block-class-changed", delta == ["dev/yg/skyboxblock/SkyBlock.class"], delta)
        mods = lambda p: {f.name: sha(f) for f in (p / "mods").glob("*.jar") if not f.name.startswith("skyboxblock-")}
        check("pair:support-mods-identical", mods(instance) == mods(old_instance))
        pair = {"baselineJarSha256": sha(old_jars[0]), "changedEntries": delta}
    failed = [c for c in checks if not c["passed"]]
    return {"kind": "R27-independent-geometry-neighbor-AO", "expected": expected, "fixture": str(instance),
            "jarSha256": sha(jars[0]), "limits": LIMITS,
            "maskPolicy": "fixed ray/AABB surface visibility, exclude analytic sky-plane footprint; 3px silhouette erosion; contact z=2.05..2.95; no RGB exclusions",
            "checks": len(checks), "passed": len(checks) - len(failed), "failed": failed,
            "allChecksPassed": not failed, "measurements": measurements, "pair": pair,
            "fullPriorReleaseMatrixRevalidated": False, "portalTestsPerformed": False}


def selftest():
    mask = np.ones((20, 20), dtype=bool)
    a = np.full((20, 20, 3), 130.0)
    assert equal(difference(a, a.copy(), mask))
    assert not equal(difference(a, a - 1, mask))
    assert not equal(difference(a, a - 4, mask))
    assert not equal(difference(a, a - np.indices((20, 20))[0][..., None] * 2, mask))
    shadow = difference(a, a - 20, mask)
    assert shadow["meanDarkening"] == 20 and shadow["darkenedOver3"] == 400
    assert difference(a, a + 20, mask)["meanDarkening"] == -20
    print("PASS: oracle selftest, equality/brightness/sign/spatial negative controls")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("instance", type=Path, nargs="?")
    p.add_argument("--expect", choices=("legacy", "fixed"), default="fixed")
    p.add_argument("--baseline", type=Path)
    p.add_argument("--output", type=Path)
    p.add_argument("--selftest", action="store_true")
    a = p.parse_args()
    if a.selftest:
        selftest()
        return 0
    if not a.instance or not a.output:
        p.error("instance and --output required")
    report = inspect(a.instance, a.expect, a.baseline)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with a.output.open("x", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(json.dumps({k: v for k, v in report.items() if k != "measurements"}, indent=2))
    return 0 if report["allChecksPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
