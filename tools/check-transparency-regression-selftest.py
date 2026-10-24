#!/usr/bin/env python3
"""Synthetic transparency controls plus OPTIONAL retained real-image negatives.

python -B tools/check-transparency-regression-selftest.py
python -B tools/check-transparency-regression-selftest.py --real-results RESULTS
No game, fixture/source modifications, or report writes. Legacy R1 geometry is
explicitly supplied from its verified producer; failure must come from pixels.
"""
from __future__ import annotations

import argparse
import copy
import importlib.util
import itertools
import json
from pathlib import Path
import sys

import numpy as np

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("transparency_oracle", Path(__file__).with_name("check-transparency-regression.py"))
oracle = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = oracle
spec.loader.exec_module(oracle)


def fixture():
    width, height = 192, 144
    projection = np.array([[1., 0, 0, 0], [0, 4 / 3, 0, 0], [0, 0, 1.02, -.202], [0, 0, 1, 0]])
    inverse = np.linalg.inv(projection).T.ravel().tolist()
    meta = dict(width=width, height=height, opaqueWidth=width, opaqueHeight=height,
                targetViewport=[0, 0, width, height], activeViewport=[0, 0, width, height],
                camera=[0., 0., 0.], inverseDisplayViewProjection=inverse,
                inverseViewProjection=inverse, rasterScale=[1., 1.], rasterJitter=[0., 0.],
                initialGlError=0, finalGlError=0, outsideRasterChangedPixels=0,
                outsideRasterDepthChangedPixels=0, resourceReloadPending=False,
                irisMainBoundBefore=True, irisMainBoundAfter=True, irisOverridesBefore=True, irisOverridesAfter=True)
    y, x = np.indices((height, width))
    sky = np.stack((75 + x / 8, 135 + y / 8, 225 + x / 15), axis=-1).astype(np.uint8)
    background = np.broadcast_to(np.array([70, 90, 110], dtype=np.uint8), sky.shape).copy()
    checker = ((x // 7 + y // 6) % 2).astype(bool)
    pattern = np.where(checker[..., None], np.array([240, 230, 210]), np.array([18, 28, 35])).astype(np.uint8)
    result = []
    for sky_present, front, protection, behind in itertools.product(
            (True, False), oracle.FRONTS, oracle.PROTECTIONS, (False, True)):
        t = dict(front=front, protection=protection, behind=behind, skyPresent=sky_present,
                 skyBounds=[[-3., -2., 4.], [3., 2., 4.2]],
                 frontBounds=None if front == "none" else [[-2., -1.6, 2.], [2., 1.6, 2.1]],
                 behindBounds=[[-2., -1.6, 7.], [2., 1.6, 7.2]],
                 pillarBounds=[[-.7, -1.7, 1.1], [-.15, 1.7, 1.3]] if protection in ("pillar", "both") else None)
        t["markerBoxes"] = [copy.deepcopy(t["behindBounds"])]
        t["markerMaterial"] = "synthetic-checker"
        name = f"synthetic-{int(sky_present)}-{front}-{protection}-{int(behind)}"
        m = copy.deepcopy(meta); m["transparency"] = t
        scene = oracle.Scene(name, m)
        g = oracle.geometry(scene, (height, width))
        hand = np.zeros((height, width), dtype=bool)
        if protection in ("hand", "both"):
            hand[int(height * .65):int(height * .92), int(width * .72):int(width * .92)] = True
        before = background.copy()
        if behind: before[g["marker"]] = pattern[g["marker"]]
        before[g["pillar"]] = [160, 143, 107]
        opaque = before.copy()
        if sky_present: opaque[g["sky"]] = sky[g["sky"]]
        final = opaque.copy()
        if front != "none":
            gain, emission = ([.65, .45, .55], [15, 5, 20]) if front == "glass" else ([.45, .65, .80], [0, 8, 12])
            full_front = np.isfinite(oracle.distance(m["camera"], oracle.rays(m, width, height), t["frontBounds"]))
            full_front &= ~g["pillar"]
            final[full_front] = np.clip(final[full_front] * gain + emission, 0, 255).astype(np.uint8)
        final[hand] = [190, 115, 80]
        depth = g["sky"] if sky_present else np.zeros_like(hand)
        m.update(blocks=1 if sky_present else 0, depthChangedPixels=int(depth.sum()), finalResolved=sky_present)
        mask_rgb = lambda mask: np.repeat((mask.astype(np.uint8) * 255)[..., None], 3, axis=-1)
        scene.images = dict(final=final, pre=final.copy(), before=before, opaque=opaque,
                            depth=mask_rgb(depth), hand=mask_rgb(hand), foreground=mask_rgb(hand))
        if sky_present: scene.images["reference"] = sky.copy()
        result.append(scene)
    for front in oracle.FRONTS:
        removed = copy.deepcopy(next(s for s in result if s.key == (front, "none", True, False)))
        removed.name += "-removed"; removed.t["role"] = "removed"; result.append(removed)
    return result


def failures(report, fragment=None):
    return [c["name"] for c in report["checks"] if not c["passed"] and (fragment is None or fragment in c["name"])]


def real_negative_controls(directory, require):
    root = directory if (directory / "trans-sky-glass-none-marker.json").exists() else directory / "results"
    verified_legacy_boxes = [[[x, 140, 8], [x+1, 148, 9]] for x in (-3, -1, 1, 3)]
    for front in ("glass", "water"):
        try:
            scenes, rois, anchors, sources = [], [], [], []
            for has_sky, behind in ((True, True), (True, False), (False, True), (False, False)):
                name = f"trans-{'sky' if has_sky else 'open'}-{front}-none-{'marker' if behind else 'empty'}"
                meta = json.loads((root / (name + ".json")).read_text(encoding="utf-8"))
                t = meta["transparency"]
                if (t["front"], t["protection"], t["skyPresent"], t["behind"]) != (front, "none", has_sky, behind):
                    raise ValueError("real capture identity mismatch")
                if "markerBoxes" not in t:
                    # ONLY this named historical fixture recipe, verified from the frozen producer.
                    if t["behindBounds"] != [[-3., 140., 8.], [4., 148., 9.]]:
                        raise ValueError("unknown legacy producer geometry; no fabricated override")
                    t["markerBoxes"] = copy.deepcopy(verified_legacy_boxes)
                    t["markerMaterial"] = "minecraft:white_concrete"
                    t["markerGeometryEvidence"] = "legacy-known-producer-geometry"
                else:
                    t["markerGeometryEvidence"] = "declared-markerBoxes"
                scene = oracle.Scene(name, meta, {"final": oracle.rgb(root / (name + ".png"))})
                shape = scene.images["final"].shape[:2]
                scale = min(meta["rasterScale"])
                margin = int(np.ceil(oracle.DEFAULTS["margin_active"] / scale))
                g = oracle.geometry(scene, shape)
                hand = oracle.rgb(root / (name + "-post-hand-mask.png"))[..., 0] > 0
                hand = oracle.map_active_mask(hand, meta, shape)
                safe = oracle.erode(g["sky"], margin) & ~oracle.dilate(hand, max(1, margin // 2))
                roi = safe & oracle.erode(g["transparent"], margin) & oracle.dilate(g["marker"], margin + 2)
                scenes.append(scene); rois.append(roi); anchors.append(oracle.marker_geometry(scene, shape))
                sources.append(t["markerGeometryEvidence"])
            if not all(oracle.matching(scenes[0], s) for s in scenes[1:]):
                raise ValueError("real pairs differ in camera/geometry/material")
            passed, metrics = oracle.marker_metrics(*(s.images["final"] for s in scenes),
                np.logical_and.reduce(rois), oracle.DEFAULTS, min(scenes[0].meta["rasterScale"]),
                geometry=np.logical_and.reduce(anchors), geometry_source=sources[0])
            # A metadata/SNR failure must NEVER masquerade as real PNG xray detection.
            require("real-" + front + "-PNG-shape-detected", not passed and metrics["evidenceValid"]
                    and metrics["multiscaleLeakDetected"] and not metrics.get("evidenceError"))
            if sources[0] == "legacy-known-producer-geometry":
                require("real-" + front + "-retained-old-HF-false-negative-confirmed", not metrics["legacyLeakDetected"])
            summary = {k: metrics[k] for k in ("geometrySource", "pixels", "referenceFeatureRms", "evidenceValid",
                "legacyEvidenceValid", "multiScaleEvidenceValid", "legacyLeakDetected", "multiscaleLeakDetected", "referenceShapeBands")}
            summary["oraclePassed"] = passed
            summary["bands"] = metrics["bands"]
            summary["coherentMatchCount"] = len(metrics["coherentMatches"])
            print("REAL-NEGATIVE", front, json.dumps(summary, allow_nan=False))
        except (OSError, ValueError, KeyError, TypeError) as error:
            require("real-" + front + "-PNG-shape-detected", False, str(error))


def run(real_results=None):
    cases = []
    def require(name, condition, detail=""):
        cases.append((name, bool(condition)))
        print(("PASS " if condition else "FAIL ") + name + (": " + detail if detail else ""), flush=True)

    import tempfile
    with tempfile.TemporaryDirectory(prefix="skybox-mixed-oracle-") as temporary:
        directory=Path(temporary)
        (directory/"bug-ordinary.json").write_text(json.dumps({"scene":"bug-ordinary","transparency":None}),encoding="utf-8")
        require("mixed-all-ordinary-null-transparency-is-not-a-trans-scene",oracle.load(directory)==[])
        (directory/"trans-missing.json").write_text(json.dumps({"scene":"trans-missing"}),encoding="utf-8")
        loaded=oracle.load(directory)
        require("named-trans-missing-evidence-is-retained",len(loaded)==1 and loaded[0].name=="trans-missing")
        require("named-trans-missing-evidence-explicitly-fails",not oracle.evaluate(loaded)["passed"]
                and bool(failures(oracle.evaluate(loaded),"valid-evidence")))
        (directory/"trans-null.json").write_text(json.dumps({"scene":"trans-null","transparency":None}),encoding="utf-8")
        require("named-trans-null-evidence-is-not-silently-skipped",len(oracle.load(directory))==2)
    normal = fixture(); report = oracle.evaluate(normal)
    require("normal-full-51-scene-fixture-passes", report["passed"], str(failures(report)[:6]))
    require("actual-image-checks-executed", report["totalChecks"] > 400)
    require("CLI-report-is-finite-JSON", json.loads(json.dumps(report, allow_nan=False))["passed"])
    geometry_v2=copy.deepcopy(normal)
    for scene in geometry_v2:
        scene.t.update(markerPattern="striped",fixtureGeometryVersion=2)
        expected=oracle.expected_fixture_geometry(scene.t)
        scene.t["actualServerGeometry"]=copy.deepcopy(expected)
        scene.t["actualClientGeometry"]=copy.deepcopy(expected)
    require("fixed-geometry-v2-both-real-sides-pass",oracle.evaluate(geometry_v2)["passed"])
    for key in oracle.expected_fixture_geometry(geometry_v2[0].t):
        bad=copy.deepcopy(geometry_v2); bad[0].t["actualClientGeometry"][key]-=1
        require("physical-geometry-count-"+key+"-fails",not oracle.evaluate(bad)["passed"])
    missing=copy.deepcopy(geometry_v2); del missing[0].t["actualServerGeometry"]
    require("physical-server-evidence-missing-fails",not oracle.evaluate(missing)["passed"])
    unknown=copy.deepcopy(geometry_v2); unknown[0].t["markerPattern"]="unreviewed"
    require("unknown-physical-marker-recipe-fails",not oracle.evaluate(unknown)["passed"])
    require("fixed-striped-and-solid-count-contracts-differ",oracle.expected_fixture_geometry(dict(geometry_v2[0].t,markerPattern="solid"))["markerWhite"]==0)
    geometry_v3=copy.deepcopy(geometry_v2)
    for i,scene in enumerate(geometry_v3):
        scene.t.update(fixtureGeometryVersion=3,fluidFreezeMode="owned-scheduled-water-only",actualServerGameTime=1000+i,actualClientGameTime=1000+i)
        scene.meta["suppressedFixtureWaterTicks"]=1
        expected=oracle.expected_fixture_geometry(scene.t)
        scene.t["actualServerGeometry"]=copy.deepcopy(expected);scene.t["actualClientGeometry"]=copy.deepcopy(expected)
    require("fluid-only-v3-keeps-normal-oracle-pass",oracle.evaluate(geometry_v3)["passed"])
    bad=copy.deepcopy(geometry_v3);bad[0].t["actualClientGeometry"]["waterOutside"]=1
    require("actual-water-outside-fixture-fails",not oracle.evaluate(bad)["passed"])
    bad=copy.deepcopy(geometry_v3)
    for scene in bad: scene.t["actualServerGameTime"]=1000
    require("global-server-clock-freeze-fails",not oracle.evaluate(bad)["passed"])
    bad=copy.deepcopy(geometry_v3);bad[0].t["fluidFreezeMode"]="legacy-global-server"
    require("legacy-global-freeze-not-normal-fixture",not oracle.evaluate(bad)["passed"])
    bad=copy.deepcopy(geometry_v3);next(scene for scene in bad if scene.t["front"]=="water").meta["suppressedFixtureWaterTicks"]=0
    require("water-without-actual-scheduled-control-fails",not oracle.evaluate(bad)["passed"])
    bad=copy.deepcopy(geometry_v3);del bad[0].t["actualServerGameTime"]
    require("missing-real-world-clock-fails",not oracle.evaluate(bad)["passed"])
    leaked = copy.deepcopy(normal)
    index = {s.key: s for s in leaked if s.t.get("role") != "removed"}
    for s in leaked:
        if not s.t["skyPresent"] or not s.t["behind"]: continue
        visible_on = index[(*s.key[:2], True, False)].images["final"].astype(float)
        visible_off = index[(*s.key[:2], False, False)].images["final"].astype(float)
        g = oracle.geometry(s, s.images["final"].shape[:2])
        roi = g["sky"] & ~g["pillar"] & ~(s.images["hand"][..., 0] > 0)
        if s.t["front"] != "none": roi &= g["transparent"]
        value = np.clip(s.images["final"].astype(float) + .70 * (visible_on - visible_off), 0, 255).astype(np.uint8)
        s.images["final"][roi] = value[roi]; s.images["pre"] = s.images["final"].copy()
    result = oracle.evaluate(leaked)
    require("transmitted-behind-pattern-fails-marker-oracle", not result["passed"] and len(failures(result, "no-transmitted-marker")) >= 8)
    erased = copy.deepcopy(normal)
    index = {s.key: s for s in erased if s.t.get("role") != "removed"}
    for s in erased:
        if not s.t["skyPresent"] or s.t["front"] == "none": continue
        g = oracle.geometry(s, s.images["final"].shape[:2]); region = g["transparent"] & ~(s.images["hand"][..., 0] > 0)
        bare = index[("none", s.t["protection"], s.t["behind"], True)].images["final"]
        s.images["final"][region] = bare[region]
    result = oracle.evaluate(erased)
    require("glass-water-erased-fails-material-oracle", not result["passed"] and len(failures(result, "material-contributes")) == 16)
    require("glass-water-erased-fails-same-frame-preservation", len(failures(result, "transparent-layer-not-erased")) == 16)
    covered = copy.deepcopy(normal)
    for s in covered:
        if not s.t["skyPresent"]: continue
        g = oracle.geometry(s, s.images["final"].shape[:2]); region = g["pillar"] | (s.images["hand"][..., 0] > 0)
        s.images["final"][region] = s.images["reference"][region]
    result = oracle.evaluate(covered)
    require("opaque-pillar-covered-fails", len(failures(result, "preserve-pillar")) == 12)
    require("real-depth-hand-covered-fails", len(failures(result, "preserve-hand")) == 12)
    missing = [s for s in normal if s.key != ("water", "none", False, False)]
    require("missing-reference-pair-fails-closed", not oracle.evaluate(missing)["passed"])
    no_removed = [s for s in normal if s.t.get("role") != "removed"]
    require("missing-removal-evidence-fails-closed", not oracle.evaluate(no_removed)["passed"])
    require("empty-fixture-fails-closed", not oracle.evaluate([])["passed"])
    s = next(s for s in normal if s.key == ("glass", "none", True, True))
    other = next(v for v in normal if v.key == ("glass", "none", False, True))
    va = next(v for v in normal if v.key == ("glass", "none", True, False))
    vb = next(v for v in normal if v.key == ("glass", "none", False, False))
    g = oracle.geometry(s, s.images["final"].shape[:2])
    roi = oracle.erode(g["transparent"], 3) & oracle.dilate(g["marker"], 5)
    anchor = oracle.marker_geometry(s, s.images["final"].shape[:2])
    a = other.images["final"].astype(float) + 8
    ok, metrics = oracle.marker_metrics(a, other.images["final"], va.images["final"], vb.images["final"], roi, oracle.DEFAULTS, geometry=anchor)
    require("broad-lighting-change-is-not-labelled-direct-xray", ok and metrics["mae"] >= 7)
    ok, _ = oracle.material_metrics(s.images["final"], other.images["final"], np.zeros_like(roi), oracle.DEFAULTS)
    require("empty-material-roi-fails", not ok)
    ok, _ = oracle.marker_metrics(a, other.images["final"], va.images["final"], vb.images["final"], np.zeros_like(roi), oracle.DEFAULTS, geometry=anchor)
    require("empty-marker-roi-fails", not ok)
    low = np.zeros((6, 8), dtype=bool); low[2:5, 3:6] = True
    meta = dict(targetViewport=[0, 0, 16, 12], rasterScale=[.5, .5], rasterJitter=[.0625, -.0833333333333])
    got = oracle.map_active_mask(low, meta, (12, 16)); expected = np.zeros_like(got)
    for y in range(12):
        for x in range(16):
            ix = int(np.floor((x + .5) / 2 + .5)); iy = 5 - int(np.floor((12 - y - .5) / 2 - .5))
            expected[y, x] = low[min(5, max(0, iy)), min(7, max(0, ix))]
    require("FSR-jitter-active-depth-mapping-independent-check", np.array_equal(got, expected))
    marker_delta = va.images["final"].astype(float) - vb.images["final"].astype(float)
    shifted = other.images["final"].astype(float) + .5 * np.roll(marker_delta, (2, -3), axis=(0, 1))
    ok, metrics = oracle.marker_metrics(shifted, other.images["final"], va.images["final"], vb.images["final"], roi, oracle.DEFAULTS, geometry=anchor)
    require("refracted-shifted-marker-fails", not ok and metrics.get("leakDetected", False))
    ok, metrics = oracle.marker_metrics(a, other.images["final"], vb.images["final"], vb.images["final"], roi, oracle.DEFAULTS, geometry=anchor)
    require("invisible-marker-control-fails-instead-of-empty-pass", not ok and metrics["referenceFeatureRms"] == 0)
    excessive = copy.deepcopy(normal)
    target = next(v for v in excessive if v.key == ("glass", "none", True, True))
    original_geometry = oracle.geometry(target, target.images["final"].shape[:2])["sky"].copy()
    target.images["hand"][:] = 255
    require("geometry-independent-of-observed-exclusion-mask", np.array_equal(original_geometry, oracle.geometry(target, target.images["final"].shape[:2])["sky"]))
    require("broad-hand-exclusion-cannot-hide-xray", bool(failures(oracle.evaluate(excessive), "bounded-real-hand-exclusion")))
    broken_removed = copy.deepcopy(normal)
    for target in broken_removed:
        if target.t.get("role") == "removed": target.images["final"][:] = [60, 150, 240]
    require("removed-world-contamination-fails", len(failures(oracle.evaluate(broken_removed), "removed-world-restored")) == 3)
    corrupt_depth = copy.deepcopy(normal)
    target = next(v for v in corrupt_depth if v.key == ("glass", "none", True, True)); target.images["depth"][:] = 255
    require("depth-write-outside-geometric-sky-fails", bool(failures(oracle.evaluate(corrupt_depth), "early-depth-no-occlusion-leak")))
    # Additional safeguards for the new structure branch.
    yy, xx = np.indices(roi.shape)
    linear = other.images["final"].astype(float) + 5 + 8*xx[..., None]/roi.shape[1] + 4*yy[..., None]/roi.shape[0]
    ok, _ = oracle.marker_metrics(linear, other.images["final"], va.images["final"], vb.images["final"], roi, oracle.DEFAULTS, geometry=anchor)
    require("smooth-global-affine-illumination-not-labelled-xray", ok)
    ok, metrics = oracle.marker_metrics(a, other.images["final"], va.images["final"], vb.images["final"], roi, oracle.DEFAULTS)
    require("missing-independent-marker-geometry-fails-closed", not ok and metrics.get("evidenceError") == "missing-independent-marker-geometry")
    blurred = oracle.box_average(anchor.astype(float), 5)
    soft_leak = other.images["final"].astype(float) + blurred[..., None]*np.array([18., 24., 65.])
    ok, metrics = oracle.marker_metrics(soft_leak, other.images["final"], va.images["final"], vb.images["final"], roi, oracle.DEFAULTS, geometry=anchor)
    require("soft-recoloured-large-scale-occluder-fails", not ok and metrics["multiscaleLeakDetected"])
    # Different zero-mean textures have unrelated signed RGB but the same object-energy envelope.
    h, w = 240, 320
    ey, ex = np.indices((h, w))
    independent = np.zeros((h, w), bool)
    for x in (35, 100, 165, 230): independent[40:200, x:x+30] = True
    eligibility = np.zeros_like(independent); eligibility[20:220, 20:300] = True
    native_off = np.full((h, w, 3), 120.)
    texture = np.where((ex//5 + ey//5) % 2, 1., -1.)
    native_on = native_off + (independent * texture)[..., None] * 50
    background = np.broadcast_to(np.array([90., 130., 180.]), (h, w, 3)).copy()
    signs = np.where(np.random.default_rng(801).integers(0, 2, size=(h, w, 3)), 1., -1.)
    energy_leak = background + independent[..., None] * signs * 20
    ok, metrics = oracle.marker_metrics(energy_leak, background, native_on, native_off, eligibility,
        oracle.DEFAULTS, geometry=independent, geometry_source="synthetic-independent-four-posts")
    require("different-texture-same-energy-envelope-fails", not ok and metrics["evidenceValid"]
        and any(c["family"] == "energy-envelope" for c in metrics["coherentMatches"]))
    illumination = background + (25*np.sin(2*np.pi*ey/h))[..., None]*np.array([1., .8, .6])
    ok, metrics = oracle.marker_metrics(illumination, background, native_on, native_off, eligibility,
        oracle.DEFAULTS, geometry=independent, geometry_source="synthetic-independent-four-posts")
    require("non-marker-smooth-global-illumination-not-labelled-xray", ok and not metrics["multiscaleLeakDetected"])
    # Weak HF does not erase independently strong low-frequency evidence.
    smooth_shape = oracle.box_average(independent.astype(float), 5)
    soft_native = native_off + smooth_shape[..., None] * 40
    ok, metrics = oracle.marker_metrics(background, background, soft_native, native_off, eligibility,
        oracle.DEFAULTS, geometry=independent, geometry_source="synthetic-independent-four-posts")
    require("weak-HF-strong-geometry-LP-positive-still-observable", ok and not metrics["legacyEvidenceValid"]
        and metrics["multiScaleEvidenceValid"])
    ok, metrics = oracle.marker_metrics(background + smooth_shape[..., None]*20, background,
        soft_native, native_off, eligibility, oracle.DEFAULTS, geometry=independent,
        geometry_source="synthetic-independent-four-posts")
    require("weak-HF-strong-LP-leak-still-detected", not ok and not metrics["legacyEvidenceValid"]
        and metrics["multiScaleEvidenceValid"] and metrics["multiscaleLeakDetected"])
    ok, metrics = oracle.marker_metrics(background, background, native_off + smooth_shape[..., None]*.3,
        native_off, eligibility, oracle.DEFAULTS, geometry=independent,
        geometry_source="synthetic-independent-four-posts")
    require("all-reference-bands-weak-fail-closed", not ok and not metrics["legacyEvidenceValid"]
        and not metrics["multiScaleEvidenceValid"] and not metrics["evidenceValid"])
    if real_results is not None: real_negative_controls(real_results, require)
    else: print("Real PNG negatives not requested; use --real-results to verify retained captures.")
    print(f"{sum(p for _, p in cases)}/{len(cases)} oracle controls passed")
    return 0 if all(p for _, p in cases) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--real-results", type=Path)
    raise SystemExit(run(parser.parse_args().real_results))
