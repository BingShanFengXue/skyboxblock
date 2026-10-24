#!/usr/bin/env python3
"""Validate hand color/exposure over a real-time entry/stay/exit timeline, with measured depth masks."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import zipfile
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("world", ROOT / "tools/check-world-regression.py")
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)
SCENES = ["hand-outside-start", "hand-enter-00", "hand-inside-01", "hand-inside-03", "hand-inside-10",
          "hand-inside-20", "hand-inside-30", "hand-exit-00", "hand-exit-03", "hand-exit-10",
          "hand-reenter-00", "hand-reenter-10", "hand-reenter-30", "hand-removed-00", "hand-removed-10"]


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def rgb(path):
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB"), dtype=np.int16)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def hand_mask(directory, scene, meta):
    low = rgb(directory / (scene + "-post-hand-mask.png"))[..., 0] > 0
    height, width = low.shape
    out_h, out_w = meta["height"], meta["width"]
    jx, jy = meta["rasterJitter"]
    target_w, target_h = meta["targetViewport"][2:]
    ix = np.floor((np.arange(out_w) + .5) / out_w * width + jx * .5 * target_w).astype(int)
    iy = height - 1 - np.floor((1 - (np.arange(out_h) + .5) / out_h) * height + jy * .5 * target_h).astype(int)
    mask = low[np.clip(iy, 0, height - 1)[:, None], np.clip(ix, 0, width - 1)[None, :]]
    # Fixed geometric/depth erosion; no skin-color or changed-pixel selection.
    mask = w.erode(mask, 6)
    mask[-40:, :] = False
    return mask


def inspect(instance, expected, baseline=None):
    fixture, runtime = read(instance / "fixture.json"), read(instance / "run-result.json")
    directory = instance / "results"
    checks, rows = [], []

    def check(name, passed, details=None):
        checks.append({"name": name, "passed": bool(passed), "details": details})

    check("complete-15-scene-timeline", runtime["finished"] and runtime["exitCode"] == 0 and runtime["scenes"] == 15)
    check("runtime-errors-zero", not runtime["errors"] and not runtime["fatalErrors"] and not runtime["renderThreadViolations"])
    check("actual-exposure-not-frozen", not fixture["freezeShaderTime"])
    check("expected-scene-identities", {p.stem for p in directory.glob("*.json")} == set(SCENES))
    jars = list((instance / "mods").glob("skyboxblock-*.jar"))
    check("installed-jar-identity", len(jars) == 1 and sha(jars[0]) == fixture["jarSha256"])
    for scene in SCENES:
        m = read(directory / (scene + ".json"))
        e = m["handExposure"]
        exposure = e["exposure"]
        mask = hand_mask(directory, scene, m)
        image = rgb(directory / (scene + ".png"))
        pixels = image[mask]
        check(scene + ":hand-depth-population", len(pixels) > 5000, len(pixels))
        white = float(np.all(pixels >= 248, axis=1).mean()) if len(pixels) else 1.0
        mean = pixels.mean(axis=0).tolist() if len(pixels) else []
        span = float(np.mean(pixels.max(axis=1) - pixels.min(axis=1))) if len(pixels) else 0.0
        pre = rgb(directory / (scene + "-pre-resolve.png"))
        protection = int(np.abs(pre - image)[mask].max()) if len(pixels) else 255
        check(scene + ":final-resolve-protects-hand", protection == 0, protection)
        check(scene + ":gl-zero", m["initialGlError"] == m["finalGlError"] == 0)
        check(scene + ":restored-iris-state", m["irisMainBoundBefore"] == m["irisMainBoundAfter"] and m["irisOverridesBefore"] == m["irisOverridesAfter"])
        check(scene + ":real-hand-and-daylight", e["hudAndHandEnabled"] and e["timeOfDay"] % 24000 == 6000 and e["skyLight"] == 15 and e["blockLight"] == 0)
        check(scene + ":exposure-owner-read", exposure is not None and exposure.get("ownerVerified") and "error" not in exposure)
        values = np.asarray([exposure.get("averageLuminance", np.nan), exposure.get("exposureMultiplier", np.nan)], dtype=float) if exposure else np.array([np.nan, np.nan])
        check(scene + ":finite-positive-exposure", np.isfinite(values).all() and (values > 0).all(), values.tolist())
        removed = scene.startswith("hand-removed-")
        expected_blocks = 0 if removed or fixture["handMaterial"] != "sky" else e["expectedShellBlocks"]
        if scene == "hand-removed-00" and fixture["handMaterial"] == "sky":
            # This is the first frame after requesting a SERVER command, not a claimed client acknowledgement.
            # Record either actual pre-update or acknowledged geometry; the late removed-10 frame must be zero.
            check(scene + ":removal-acknowledged-or-still-in-flight", e["actualSkyBlocks"] in (0, e["expectedShellBlocks"]),
                  {"actualSkyBlocks": e["actualSkyBlocks"], "removalAcknowledged": e["actualSkyBlocks"] == 0})
        else:
            check(scene + ":actual-sky-geometry", e["actualSkyBlocks"] == expected_blocks, e["actualSkyBlocks"])
        if expected in ("fixed", "control"):
            check(scene + ":no-white-clipping", white < .02, white)
            check(scene + ":skin-chroma-retained", span > 8.0, span)
            check(scene + ":meter-not-saturated", values[0] < 60000 and values[1] < 60000, values.tolist())
        rows.append({"scene": scene, "millisecondsSinceTransition": e["elapsedSinceTransitionMilliseconds"],
                     "handPixels": len(pixels), "whiteFraction248": white, "meanHandRgb": mean,
                     "meanHandChannelSpan": span, "finalResolveHandMaxDifference": protection,
                     "averageLuminance": float(values[0]), "exposureMultiplier": float(values[1]),
                     "actualSkyBlocks": e["actualSkyBlocks"], "skyLight": e["skyLight"], "blockLight": e["blockLight"]})
    by_name = {r["scene"]: r for r in rows}
    if expected == "legacy":
        check("legacy:actual-full-white-hand", by_name["hand-inside-30"]["whiteFraction248"] > .9)
        check("legacy:half-float-meter-saturation", by_name["hand-inside-30"]["averageLuminance"] >= 65500)
        check("legacy:persists-on-reentry", by_name["hand-reenter-30"]["whiteFraction248"] > .9)
    else:
        for prefix in ("hand-inside", "hand-reenter"):
            late = [r for r in rows if r["scene"] in (prefix + "-10", prefix + "-30")]
            if len(late) == 2:
                a, b = late
                check(prefix + ":settled-hand-color", max(abs(x - y) for x, y in zip(a["meanHandRgb"], b["meanHandRgb"])) < 8.0,
                      {"earlyRgb": a["meanHandRgb"], "lateRgb": b["meanHandRgb"]})
                check(prefix + ":settled-meter", max(a["averageLuminance"], b["averageLuminance"]) / min(a["averageLuminance"], b["averageLuminance"]) < 1.2)
        check("inside:real-time-observed", by_name["hand-inside-30"]["millisecondsSinceTransition"] >= 30000)
        check("reentry:real-time-observed", by_name["hand-reenter-30"]["millisecondsSinceTransition"] >= 30000)
    delta = None
    if baseline:
        old_fixture = read(baseline / "fixture.json")
        # Differences in installed JAR path/hash are the explicitly varied implementation, not hidden settings.
        # Iris rewrites a generated timestamp comment. Require identical parsed options and all other
        # declared settings, and independently pin actual shader ZIP/support-mod bytes; retain raw hashes.
        excluded = ("jar", "jarSha256", "shaderSettingsSha256")
        check("pair:all-other-fixture-settings-identical", {k: v for k, v in fixture.items() if k not in excluded} ==
              {k: v for k, v in old_fixture.items() if k not in excluded})
        archives = lambda root, folder, pattern: {p.name: sha(p) for p in (root / folder).glob(pattern)
                                                  if not p.name.startswith("skyboxblock-")}
        check("pair:actual-shader-ZIP-identical", archives(instance, "shaderpacks", "*.zip") == archives(baseline, "shaderpacks", "*.zip"))
        check("pair:actual-support-mods-identical", archives(instance, "mods", "*.jar") == archives(baseline, "mods", "*.jar"))
        def entries(path):
            with zipfile.ZipFile(path) as z:
                return {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith("/")}
        old = entries(next((baseline / "mods").glob("skyboxblock-*.jar")))
        new = entries(jars[0])
        delta = [n for n in sorted(set(old) | set(new)) if old.get(n) != new.get(n)]
        check("pair:only-intended-HDR-shader-changed", delta == ["assets/skyboxblock/shaders/core/sky_material_background.fsh"], delta)
    failed = [c for c in checks if not c["passed"]]
    return {"kind": "R28-real-time-hand-exposure-oracle", "protocolRevision": 3,
            "rawSettingsSha256": fixture["shaderSettingsSha256"],
            "settingsComparisonPolicy": "parsed options plus full remaining declaration plus actual shader/support-mod bytes; generated timestamp comments are not shader settings",
            "removalFirstFrameSemantics": "request-not-acknowledgement; actual client count retained; removed-10 must be zero",
            "expected": expected, "fixture": str(instance),
            "jarSha256": fixture["jarSha256"], "checks": len(checks), "passed": len(checks) - len(failed),
            "failed": failed, "allChecksPassed": not failed, "measurements": rows, "changedJarEntries": delta,
            "handMaskPolicy": "actual first-person depth writes; same FSR/jitter transform as established world oracle; fixed 6px erosion and bottom 40px exclusion; no color-selected pixels",
            "bloomOrAutoExposureDisabled": False, "portalTestsPerformed": False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("instance", type=Path)
    p.add_argument("--expect", choices=("legacy", "fixed", "control"), required=True)
    p.add_argument("--baseline", type=Path)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    report = inspect(a.instance, a.expect, a.baseline)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with a.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
    print(json.dumps({k: v for k, v in report.items() if k != "measurements"}, indent=2))
    return 0 if report["allChecksPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
