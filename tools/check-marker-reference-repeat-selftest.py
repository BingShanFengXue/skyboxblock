#!/usr/bin/env python3
"""CPU-only synthetic/TEMP controls; NOT real GPU evidence or independent audit.

python -B tools/check-marker-reference-repeat-selftest.py [--output NEW_REPORT.json]
Uses only newly owned temporary fixtures. No Gradle, game, user instance or old
report writes. Tests the actual parser/images/hash path as well as array matcher.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager, redirect_stdout
import copy
import importlib.util
import io
import itertools
import json
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import time
import zipfile
import numpy as np
from PIL import Image

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("marker_repeat_under_test", Path(__file__).with_name("check-marker-reference-repeat.py"))
R = importlib.util.module_from_spec(spec); sys.modules[spec.name] = R; spec.loader.exec_module(R)


def put(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, allow_nan=False), encoding="utf-8")


def png(path, array): Image.fromarray(np.asarray(array, dtype=np.uint8)).save(path)


@contextmanager
def changed_json(path, mutate):
    before = path.read_bytes(); data = json.loads(before); mutate(data); put(path, data)
    try: yield
    finally: path.write_bytes(before)


@contextmanager
def changed_bytes(path, after):
    before = path.read_bytes(); path.write_bytes(after)
    try: yield
    finally: path.write_bytes(before)


def recipe():
    width, height = 224, 168
    projection = np.array([[1., 0, 0, 0], [0, 4/3, 0, 0], [0, 0, 1.02, -.202], [0, 0, 1, 0]])
    inverse = np.linalg.inv(projection).T.ravel().tolist()
    meta = dict(width=width, height=height, opaqueWidth=width, opaqueHeight=height,
                targetViewport=[0, 0, width, height], activeViewport=[0, 0, width, height],
                camera=[0., 0., 0.], inverseDisplayViewProjection=inverse, inverseViewProjection=inverse,
                rasterScale=[1., 1.], rasterJitter=[0., 0.], dimension="minecraft:overworld",
                cameraType="FIRST_PERSON", heldItem="minecraft:air", offhandItem="minecraft:air",
                initialGlError=0, finalGlError=0, resourceReloadPending=False, itrpBridgeFailure=None,
                displaySky=dict(timeSource="local-sky", dayTime=1000, gameTime=1000, phase=.82667, nativePhase=.82667,
                                moonPhase=0, nativeMoonPhase=0, rain=0., thunder=0., nativeFixedTime=False,
                                hasSkylight=True, nativeSkyType="NORMAL"))
    boxes = [[[x, -1.6, 7.], [x+.5, 1.6, 7.2]] for x in (-2., -1., 0., 1.)]
    t = dict(skyBounds=[[-3., -2., 4.], [3., 2., 4.2]], behindBounds=[[-2., -1.6, 7.], [2., 1.6, 7.2]],
             frontBounds=[[-2., -1.6, 2.], [2., 1.6, 2.1]], pillarBounds=None, markerBoxes=boxes,
             markerMaterial="minecraft:black_concrete", front="water", protection="none", behind=True,
             requestedSky=True, skyPresent=False, nativeRepeatControl=True, role="baseline", serverSkyBlockCount=0)
    scene = R.O.Scene("synthetic-geometry", dict(meta, transparency=t))
    roi, anchor, scale = R.roi_for(R.O.Scene(scene.name, scene.meta, dict(hand=np.zeros((height, width, 3), dtype=np.uint8))))
    return meta, t, roi, anchor, scale


def make_fixtures(project):
    meta, template, roi, anchor, scale = recipe()
    frozen = project / "frozen.jar"
    with zipfile.ZipFile(frozen, "w") as z:
        z.writestr("fabric.mod.json", json.dumps(dict(id="skyboxblock", version="synthetic-test")))
    pin = R.sha(frozen); roots = [project / name for name in ("reference-a", "reference-b", "candidate")]
    for index, root in enumerate(roots):
        native = index < 2
        for directory in ("results", "mods", "shaderpacks", "config"): (root / directory).mkdir(parents=True)
        shutil.copyfile(frozen, root / "mods/main.jar")
        with zipfile.ZipFile(root / "mods/helper.jar", "w") as z:
            z.writestr(zipfile.ZipInfo("fabric.mod.json", (2020, 1, 1, 0, 0, 0)), json.dumps(dict(id="helper", version="1")))
        with zipfile.ZipFile(root / "shaderpacks/synthetic.zip", "w") as z:
            z.writestr(zipfile.ZipInfo("readme.txt", (2020, 1, 1, 0, 0, 0)), "synthetic only")
        (root / "shaderpacks/synthetic.zip.txt").write_text("# date ignored\nFSR2_SCALE=-1\nCUSTOM_RENDER_RESOLUTION=false\n", encoding="utf-8")
        (root / "config/iris.properties").write_text("enableShaders=true\nshaderPack=synthetic.zip\n", encoding="utf-8")
        (root / "client.log").write_text("fixture-only Iris frameTimeCounter=0 active\n", encoding="utf-8")
        put(root / "fixture.json", dict(jar=str(frozen), jarSha256=pin, nativeTransparencyControl=native,
            freezeShaderTime=True, freezeClouds=True, captureJitterBits="00000000:00000000", profile="synthetic",
            shader="synthetic.zip", shaderOptions=dict(FSR2_SCALE="-1", CUSTOM_RENDER_RESOLUTION="false"),
            mods=[dict(id="helper", version="1", file="helper.jar")]))
        put(root / "run-result.json", dict(exitCode=0, finished=True, fatalErrors=[], scenes=51 if native else 113))
        slots = list(itertools.product(("open", "sky"), R.O.FRONTS, R.O.PROTECTIONS, ("empty", "marker")))
        slots += [("removed", front, "none", "marker") for front in R.O.FRONTS]
        for number, (slot, front, protection, state) in enumerate(slots):
            name = f"trans-{slot}-{front}-{protection}-{state}" if slot != "removed" else "trans-removed-" + front
            m, t = copy.deepcopy(meta), copy.deepcopy(template)
            present = slot == "sky" and not native
            t.update(front=front, protection=protection, behind=state == "marker", requestedSky=slot == "sky",
                     skyPresent=present, nativeRepeatControl=native, role="removed" if slot == "removed" else "baseline",
                     serverSkyBlockCount=88 if present else 0)
            if front == "none": t["frontBounds"] = None
            # Independent per-capture sensor noise prevents the fixture generator
            # itself from sneaking identical captures through distinct roots.
            rng = np.random.default_rng(10000 * index + number)
            final = 180 + rng.integers(-1, 2, size=(meta["height"], meta["width"], 3))
            if state == "marker" and not present: final = final - 120 * anchor[..., None]
            final = final.astype(np.uint8)
            depth = np.zeros_like(final)
            if present: depth[roi] = 255
            m.update(scene=name, transparency=t, blocks=88 if present else 0, vertices=3168 if present else 0,
                     depthChangedPixels=int(roi.sum()) if present else 0, itrpMaterialWrites=present,
                     itrpBackgroundInjected=present, itrpInjectionCount=1 if present else 0)
            m["displaySky"]["gameTime"] += index * 24000 + number * 17
            put(root / "results" / (name + ".json"), m)
            for suffix, array in (("", final), ("-before", final), ("-opaque", final),
                                  ("-depth-change", depth), ("-post-hand-mask", np.zeros_like(final))):
                png(root / "results" / (name + suffix + ".png"), array)
    return roots, pin, roi, anchor, scale


@contextmanager
def physical_version(roots, version):
    """Upgrade only our TEMP metadata; image stimuli and thresholds stay fixed."""
    originals = {}
    try:
        for index, root in enumerate(roots):
            for number, path in enumerate(sorted((root / "results").glob("trans-*.json"))):
                originals[path] = path.read_bytes(); data = json.loads(originals[path]); t = data["transparency"]
                t.update(fixtureGeometryVersion=version, markerPattern="solid")
                t["actualServerGeometry"] = copy.deepcopy(R.O.expected_fixture_geometry(t))
                t["actualClientGeometry"] = copy.deepcopy(R.O.expected_fixture_geometry(t))
                if version >= 3:
                    t.update(fluidFreezeMode="owned-scheduled-water-only", actualServerGameTime=10000+index*1000+number*17,
                             actualClientGameTime=10001+index*1000+number*17)
                    data["suppressedFixtureWaterTicks"] = 40 if t["front"] == "water" else 0
                    if index < 2: data["neverSkyObservedRootFrames"] = 100+number*25
                put(path, data)
        yield
    finally:
        for path, before in originals.items(): path.write_bytes(before)


@contextmanager
def distant_environment(roots):
    """Actual installed synthetic DH jar + owned TOML; never a user config."""
    originals = {}; created = []
    try:
        for root in roots:
            manifest = root / "fixture.json"; originals[manifest] = manifest.read_bytes()
            data = json.loads(originals[manifest])
            data["mods"].append(dict(id="distanthorizons", version="synthetic-1", file="synthetic-dh.jar"))
            data["distantGenerationPolicy"] = dict(requested="disabled", actualGeneratorPlan="DISABLED", rendererModeUnchanged="DEFAULT",
                limitation="Synthetic controlled near-world only; dynamic distant-generation/LOD is not validated")
            put(manifest, data)
            jar = root / "mods/synthetic-dh.jar"; config = root / "config/DistantHorizons.toml"
            assert not jar.exists() and not config.exists()
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr(zipfile.ZipInfo("fabric.mod.json", (2020, 1, 1, 0, 0, 0)), json.dumps(dict(id="distanthorizons", version="synthetic-1")))
            created.append(jar)
            config.write_text('[common.worldGenerator]\ngeneratorPlan="DISABLED"\n[client.advanced.debugging]\nrendererMode="DEFAULT"\n', encoding="utf-8")
            created.append(config)
        yield
    finally:
        for path, before in originals.items(): path.write_bytes(before)
        for path in created:
            absolute = path.resolve(strict=True)
            assert any(absolute.is_relative_to(root.resolve()) for root in roots) and path.name in ("synthetic-dh.jar", "DistantHorizons.toml")
            path.unlink()


def run(output=None):
    start = time.monotonic(); checks = []
    def require(name, condition, detail=None):
        record = dict(name=name, passed=bool(condition))
        if detail is not None: record["detail"] = detail
        checks.append(record); print(("PASS " if condition else "FAIL ") + name, flush=True)
    with tempfile.TemporaryDirectory(prefix="skybox-empirical-repeat-selftest-") as temporary:
        project = Path(temporary); roots, pin, roi, anchor, scale = make_fixtures(project)
        a, b, c = roots
        def proof(**kw):
            args = dict(ref_a=a, ref_b=b, candidate=c, expected_jar=pin, front="water", protections=("none",), project=project)
            args.update(kw); return R.proof(**args)
        def rejected(name, mutate_path, mutate, fragment=None):
            with changed_json(mutate_path, mutate): report = proof()
            text = json.dumps(report)
            require(name, report["status"] == "NOT_VERIFIED" and not report["passed"] and (fragment is None or fragment in text), report["errors"])
            return report
        normal = proof(); case = normal.get("cases", {}).get("none", {})
        require("TEMP-real-parser-and-images-noLeak-pass", normal["passed"], normal["errors"])
        require("explicit-new-empirical-evidence-type", case.get("evidenceType") == R.EVIDENCE_TYPE)
        require("all-four-bare-slot-physical-controls-executed", len(case.get("bareGeometryPositiveControls", [])) == 4 and all(x["multiscaleLeakDetected"] for x in case.get("bareGeometryPositiveControls", [])))
        require("holdout-self-leak-positive-detected", case.get("holdoutLeakPositiveControl", {}).get("matched"))
        require("same-state-M-and-E-negative-controls-executed", len(case.get("sameStateNegativeControls", [])) == 4 and len(case.get("holdoutSameStateNegativeControls", [])) == 8)
        require("all-51-native-states-hash-bound", all(sum(Path(p).parent == (root / "results").resolve() and Path(p).suffix == ".json" for p in normal["inputs"]) == 51 for root in (a, b)))
        require("actual-frozen-installed-pack-settings-bound", normal.get("actualEnvironment", {}).get("jar") == pin and bool(normal.get("semanticSettings")) and str((a / "mods/main.jar").resolve()) in normal["inputs"])
        require("report-is-finite-json", json.loads(json.dumps(normal, allow_nan=False))["passed"])
        ref_only = proof(references_only=True, candidate=None)
        require("references-only-never-claims-final-pass", not ref_only["passed"] and ref_only.get("referenceRepeatAdmissible") is True and ref_only["status"] == "NOT_VERIFIED")
        sky_marker = c / "results/trans-sky-water-none-marker.png"
        sky_empty = R.O.rgb(c / "results/trans-sky-water-none-empty.png")
        leaked = np.clip(sky_empty.astype(int) - 100 * anchor[..., None], 0, 255).astype(np.uint8)
        with changed_bytes(sky_marker, sky_marker.read_bytes()):
            png(sky_marker, leaked); leak = proof()
        require("TEMP-trueLeak-fails-with-valid-evidence", not leak["passed"] and leak["status"] == "MARKER_INFLUENCE_DETECTED" and leak["cases"]["none"]["empiricalRepeatEvidenceValid"])
        # Unit controls exercise exact feature thresholds, not filesystem shortcuts.
        base = np.full(anchor.shape + (3,), 180., dtype=float); signal = -120 * anchor[..., None]
        rng = np.random.default_rng(771)
        noises = [rng.normal(0, .4, base.shape) for _ in range(8)]
        states = [base + noises[i] + (signal if i in (0, 2, 4) else 0) for i in range(8)]
        bare = [(base + signal, base), (base + signal, base)]
        def arrays(s, controls=bare): return R.evaluate_arrays(s, roi, anchor, scale, controls)
        unit = arrays(states)
        require("array-real-marker-noLeak-pass", unit["status"] == "NO_MATCH_ABOVE_FIXED_THRESHOLDS", unit.get("referenceRepeatAdmissible"))
        s = list(states); s[6] = s[6] + signal
        require("array-real-marker-trueLeak-detected", arrays(s)["status"] == "MARKER_INFLUENCE_DETECTED")
        s = [base + noises[i] for i in range(8)]
        require("independent-noise-references-fail-closed", not arrays(s)["referenceRepeatAdmissible"])
        s = [base + noises[i] + (.001*signal if i in (0, 2, 4) else 0) for i in range(8)]
        require("weak-allband-fails-closed", not arrays(s)["referenceRepeatAdmissible"])
        s = list(states); s[2] = s[2] + signal; s[3] = s[3] + signal
        correlated = arrays(s)
        require("same-state-marker-correlated-confound-rejected", not correlated["referenceRepeatAdmissible"] and any(x["matched"] for x in correlated["sameStateNegativeControls"]))
        s = list(states); s[1] = s[1] + signal; s[3] = s[3] + 2*signal
        require("same-state-empty-correlated-confound-rejected", not arrays(s)["referenceRepeatAdmissible"])
        # Repeated slot signal alone passes correlation, but no actual bare marker
        # silhouette => reject, including a weak high-frequency noise impostor.
        require("slot-only-repeat-no-physical-marker-rejected", not arrays(states, [(base+noises[0], base), (base+noises[2], base)])["referenceRepeatAdmissible"])
        wrong = -120 * np.roll(anchor, 22, axis=1)[..., None]
        require("wrong-physical-marker-geometry-rejected", not arrays(states, [(base+wrong, base), (base+wrong, base)])["referenceRepeatAdmissible"])
        s = list(states); s[4], s[5] = base+noises[4], base+noises[5]
        require("nonmatching-NoSky-holdout-rejected", not arrays(s)["empiricalRepeatEvidenceValid"])
        s = list(states); s[4], s[5] = base+2*signal+noises[4], base+signal+noises[5]
        require("holdout-same-state-correlated-confound-rejected", not arrays(s)["empiricalRepeatEvidenceValid"])
        # Fixed two-band rule, gain, correlation and both RMS floors individually.
        fm, mask = R.lp_features(signal + np.zeros_like(base), roi, scale)
        only_one = {key: (value if key[1] == R.BANDS[0] else np.zeros_like(value)) for key, value in fm.items()}
        require("single-band-never-enough", not R.matches(only_one, only_one, mask, scale)["matched"])
        weak = {key: value*.001 for key, value in fm.items()}
        require("reference-RMS-floor-preserved", not R.matches(weak, weak, mask, scale)["matched"])
        low_gain = {key: value*.10 for key, value in fm.items()}
        require("gain-floor-preserved", not R.matches(fm, low_gain, mask, scale)["matched"])
        yy, xx = np.indices(mask.shape)
        # Bounded +/-10 signal: gain=.19 and rho=1, but residual RMS <=1.9.
        bounded = {key: np.broadcast_to((10 * np.where((xx//4 + yy//4) % 2, 1., -1.))[..., None], value.shape) for key, value in fm.items()}
        below_rms = {key: value*.19 for key, value in bounded.items()}
        require("residual-RMS-floor-preserved", not R.matches(bounded, below_rms, mask, scale)["matched"])
        noisy = {key: rng.normal(0, 40, value.shape) for key, value in fm.items()}
        require("correlation-floor-preserved", not R.matches(fm, noisy, mask, scale)["matched"])
        require("upstream-all-LP-HF-thresholds-unchanged", all(R.O.DEFAULTS[k] == v for k, v in R.FROZEN.items()) and R.LIMITS == R.O.DEFAULTS)
        old = R.O.marker_metrics(*(R.O.rgb(c / "results" / (name + ".png")) for name in (
            "trans-sky-water-none-marker", "trans-sky-water-none-empty", "trans-open-water-none-marker", "trans-open-water-none-empty")), roi, R.LIMITS, scale, geometry=anchor)[1]
        require("original-geometry-flags-exactly-preserved", case.get("legacyWaterFlags") == {k: old[k] for k in case.get("legacyWaterFlags", {})})
        # Preserve the identifiability limit explicitly: a slot nuisance plus a
        # real independent bare control is observationally identical to a marker.
        require("perfect-slot-nuisance-boundary-is-explicit", unit["referenceRepeatAdmissible"] and any("perfectly repeated marker-slot" in x for x in normal["limitations"]))
        # A controlled low-frequency response need not be a direct silhouette.
        # Separate empirical evidence can be valid while OLD geometry/HF evidence
        # stays weak; this is not permission to waive a detected/material failure.
        yy, xx = np.indices(anchor.shape)
        diffuse = np.broadcast_to((55*np.sin(2*np.pi*yy/48))[..., None], base.shape)
        diffuse_states = [base+noises[i]+(diffuse if i in (0, 2, 4) else 0) for i in range(8)]
        diffuse_result = arrays(diffuse_states)
        diffuse_old = R.O.marker_metrics(diffuse_states[6], diffuse_states[7], diffuse_states[4], diffuse_states[5], roi, R.LIMITS, scale, geometry=anchor)[1]
        require("explicit-LP-empirical-evidence-not-legacy-geometry-override", diffuse_result["empiricalRepeatEvidenceValid"] and not diffuse_old["multiScaleEvidenceValid"] and not diffuse_old["legacyEvidenceValid"], {k: diffuse_old[k] for k in ("multiScaleEvidenceValid", "legacyEvidenceValid", "referenceFeatureRms")})
        mpath = b / "results/trans-sky-water-none-marker.json"
        hpath = c / "results/trans-open-water-none-marker.json"
        fixture = b / "fixture.json"
        rejected("missing-phase-metadata-rejected", mpath, lambda d: d["displaySky"].pop("phase"), "Missing metadata")
        rejected("missing-jitter-metadata-rejected", mpath, lambda d: d.pop("rasterJitter"), "Missing metadata")
        rejected("missing-marker-boxes-rejected", mpath, lambda d: d["transparency"].pop("markerBoxes"), "Missing metadata")
        rejected("missing-front-bounds-rejected", mpath, lambda d: d["transparency"].pop("frontBounds"), "Missing metadata")
        rejected("missing-native-repeat-role-rejected", hpath, lambda d: d["transparency"].pop("nativeRepeatControl"), "Missing metadata")
        rejected("missing-capture-jitter-bits-rejected", fixture, lambda d: d.pop("captureJitterBits"), "Missing metadata")
        rejected("different-declared-jar-pin-rejected", fixture, lambda d: d.update(jarSha256="0"*64), "jar hash")
        rejected("different-actual-pose-rejected", mpath, lambda d: d["camera"].__setitem__(0, .3), "Pose/AABB")
        rejected("different-display-projection-rejected", mpath, lambda d: d["inverseDisplayViewProjection"].__setitem__(0, 1.2), "Pose/AABB")
        rejected("different-phase-rejected", mpath, lambda d: d["displaySky"].update(phase=.1), "phase")
        rejected("different-native-phase-rejected", mpath, lambda d: d["displaySky"].update(nativePhase=.1), "phase")
        rejected("different-daytime-modulo-rejected", mpath, lambda d: d["displaySky"].update(dayTime=12000), "phase")
        with changed_json(mpath, lambda d: d["displaySky"].update(dayTime=25000)): phase_equivalent = proof()
        require("same-day-phase-modulo-not-absolute-gameTime-accepted", phase_equivalent["passed"])
        rejected("SkyOn-without-actual-geometry-rejected", c / "results/trans-sky-water-none-marker.json", lambda d: d.update(blocks=0, vertices=0, depthChangedPixels=0), "positive actual geometry")
        rejected("different-dimension-rejected", mpath, lambda d: d.update(dimension="minecraft:the_nether"), "dimension")
        rejected("different-actual-jitter-rejected", mpath, lambda d: d["rasterJitter"].__setitem__(0, .001), "jitter")
        rejected("wrong-per-scene-native-role-rejected", mpath, lambda d: d["transparency"].update(nativeRepeatControl=False), "scene role")
        rejected("wrong-per-scene-requestedSky-rejected", mpath, lambda d: d["transparency"].update(requestedSky=False), "scene role")
        rejected("wrong-per-scene-skyPresent-rejected", mpath, lambda d: d["transparency"].update(skyPresent=True), "scene role")
        rejected("wrong-per-scene-behind-role-rejected", mpath, lambda d: d["transparency"].update(behind=False), "marker role")
        rejected("NoSky-server-count-rejected", hpath, lambda d: d["transparency"].update(serverSkyBlockCount=1), "sky count")
        for field, value in (("blocks", 110), ("vertices", 3960), ("itrpMaterialWrites", True), ("itrpBackgroundInjected", True), ("itrpInjectionCount", 1), ("depthChangedPixels", 1)):
            rejected("NoSky-" + field + "-rejected-even-if-other-counters-clean", hpath, lambda d, k=field, v=value: d.update({k: v}), "NoSky contaminated")
        rejected("R16-stale-wall-combination-strictly-rejected", hpath, lambda d: d.update(blocks=110, vertices=3960, itrpMaterialWrites=True, itrpBackgroundInjected=True, itrpInjectionCount=1), "NoSky contaminated")
        rejected("missing-NoSky-bridge-flag-rejected", hpath, lambda d: d.pop("itrpMaterialWrites"), "Missing metadata")
        rejected("wrong-nativeTransparencyControl-rejected", fixture, lambda d: d.update(nativeTransparencyControl=False), "nativeTransparencyControl")
        rejected("missing-freeze-policy-rejected", fixture, lambda d: d.update(freezeClouds=False), "animation")
        rejected("run-fatal-errors-rejected", b / "run-result.json", lambda d: d.update(fatalErrors=["synthetic failure"]), "incomplete/fatal")
        rejected("reference-51-count-required", b / "run-result.json", lambda d: d.update(scenes=50), "51-slot")
        for name, path in (("changed-installed-main-hash", b / "mods/main.jar"), ("changed-frozen-main-hash", project / "frozen.jar")):
            with changed_bytes(path, path.read_bytes()+b"changed"): report = proof()
            require(name+"-rejected", not report["passed"] and report["status"] == "NOT_VERIFIED")
        with changed_bytes(b / "mods/helper.jar", (b / "mods/helper.jar").read_bytes()+b"other"):
            report = proof()
        require("different-actual-mod-hash-rejected", report["status"] == "NOT_VERIFIED" and "artifact/pack/settings" in json.dumps(report))
        with changed_bytes(b / "shaderpacks/synthetic.zip", (b / "shaderpacks/synthetic.zip").read_bytes()+b"other"):
            report = proof()
        require("different-actual-pack-hash-rejected", report["status"] == "NOT_VERIFIED" and "artifact/pack/settings" in json.dumps(report))
        settings_file = b / "shaderpacks/synthetic.zip.txt"
        with changed_bytes(settings_file, b"FSR2_SCALE=2\nCUSTOM_RENDER_RESOLUTION=false\n"):
            report = proof()
        require("actual-vs-declared-settings-mismatch-rejected", report["status"] == "NOT_VERIFIED" and "settings mismatch" in json.dumps(report))
        with changed_bytes(settings_file, b"FSR2_SCALE=2\nCUSTOM_RENDER_RESOLUTION=false\n"):
            with changed_json(fixture, lambda d: d["shaderOptions"].update(FSR2_SCALE="2")): report = proof()
        require("internally-consistent-but-different-settings-rejected", report["status"] == "NOT_VERIFIED" and "artifact/pack/settings" in json.dumps(report))
        with changed_bytes(settings_file, b"! other date\n CUSTOM_RENDER_RESOLUTION = false\nFSR2_SCALE = -1\n"):
            report = proof()
        require("semantic-settings-comment-order-churn-accepted", report["passed"])
        with changed_bytes(b / "config/iris.properties", b"enableShaders=false\nshaderPack=synthetic.zip\n"): report = proof()
        require("actual-disabled-pack-rejected", report["status"] == "NOT_VERIFIED" and "pack selection" in json.dumps(report))
        defaults = R.P.settings_evidence(dict(shader="known-pack.zip", shaderSha256=R.P.ITRP_SHA256, settings="FSR2_SCALE=-1"), None, R.P.ITRP_SHA256)
        require("exact-known-pack-missing-default-settings-accepted", defaults["matched"] and defaults["actualSource"] == "verified-defaults")
        bad_defaults = R.P.settings_evidence(dict(shader="known-pack.zip", shaderSha256=R.P.ITRP_SHA256, settings="FSR2_SCALE=2"), None, R.P.ITRP_SHA256)
        require("known-pack-missing-nondefault-settings-rejected", not bad_defaults["matched"])
        try:
            R.P.settings_evidence(dict(shader="unknown.zip", shaderSha256="0"*64, settings=""), None, "0"*64)
            missing_unknown_rejected = False
        except ValueError: missing_unknown_rejected = True
        require("unknown-pack-missing-settings-rejected", missing_unknown_rejected)
        require("same-reference-directory-rejected", proof(ref_b=a)["status"] == "NOT_VERIFIED")
        require("results-alias-reuse-rejected", proof(ref_b=a / "results")["status"] == "NOT_VERIFIED")
        require("candidate-reference-reuse-rejected", proof(candidate=a)["status"] == "NOT_VERIFIED")
        # Equal contents do NOT establish reuse. Independent deterministic captures
        # remain admissible; observe the duplicate for provenance review instead.
        a_img = a / "results/trans-sky-water-none-marker.png"
        b_img = b / "results/trans-sky-water-none-marker.png"
        with changed_bytes(b_img, a_img.read_bytes()): report = proof()
        require("equal-byte-independent-files-admissible-with-source-review", report["passed"] and bool(report["sourceObservations"]) and report["sourceObservations"][0]["type"] == "duplicate-content-observation/source-review")
        with changed_bytes(b_img, b_img.read_bytes()):
            Image.fromarray(R.O.rgb(a_img)).save(b_img, compress_level=0); report = proof()
        require("equal-pixel-reencoded-files-admissible-with-source-review", report["passed"] and bool(report["sourceObservations"]))
        # Verify absolute deletion targets before replacing ONLY this TEMP file.
        hardlink_path = (b / "results/trans-open-glass-both-empty.json").resolve(strict=True)
        assert hardlink_path.is_relative_to(project.resolve()) and hardlink_path.name == "trans-open-glass-both-empty.json"
        saved = hardlink_path.read_bytes(); hardlink_path.unlink()
        try:
            os.link(a / "results/trans-open-glass-both-empty.json", hardlink_path)
            report = proof()
        finally:
            assert hardlink_path.absolute().is_relative_to(project.resolve()); hardlink_path.unlink(); hardlink_path.write_bytes(saved)
        require("cross-root-hardlink-capture-metadata-rejected", report["status"] == "NOT_VERIFIED" and "same-source file reuse" in json.dumps(report))
        # Actual pixel early writes with forged zero counters must still fail.
        for suffix in ("-depth-change", "-opaque"):
            path = c / "results" / ("trans-open-water-none-marker" + suffix + ".png")
            with changed_bytes(path, path.read_bytes()):
                image = R.O.rgb(path); image[0, 0] = [255, 0, 0]; png(path, image); report = proof()
            require("NoSky-actual-" + suffix + "-pixel-contamination-rejected", report["status"] == "NOT_VERIFIED" and "NoSky contaminated" in json.dumps(report))
        # First bare marker absence is rejected even if selected trans-sky repeats.
        first_bare = a / "results/trans-open-none-none-marker.png"
        with changed_bytes(first_bare, first_bare.read_bytes()):
            png(first_bare, R.O.rgb(a / "results/trans-open-none-none-empty.png")); report = proof()
        require("missing-first-bare-marker-rejects-valid-later-repeat", not report["passed"] and not report["cases"]["none"]["referenceRepeatAdmissible"])
        # SkyOn image content never changes the reference-derived ROI.
        require("SkyOn-does-not-fit-ROI", case.get("roiSha256") == leak["cases"]["none"].get("roiSha256") and "ONLY" in normal["roiSource"])
        sky_hand = c / "results/trans-sky-water-none-marker-post-hand-mask.png"
        with changed_bytes(sky_hand, sky_hand.read_bytes()):
            hand = R.O.rgb(sky_hand); y, x = np.argwhere(roi)[len(np.argwhere(roi))//2]; hand[y, x] = 255; png(sky_hand, hand); report = proof()
        require("SkyOn-mask-conflict-rejected-not-ROI-shrunk", report["status"] == "NOT_VERIFIED" and "no target-fitted shrinking" in json.dumps(report) and report["cases"]["none"].get("roiSha256") == case.get("roiSha256"))
        # Versioned physical metadata is checked by the actual Fixture/scene path.
        # All prior 87 v1 controls remain in this run: no new fields are invented
        # for historical data and no image/HF/LP threshold changes are involved.
        require("historical-v1-no-new-physical-fields-required", normal["passed"] and all(record["fixtureGeometryVersion"] == 1 for records in normal["versionedPhysicalEvidence"].values() for record in records.values()))
        early = a / "results/trans-open-glass-both-marker.json"
        removed = b / "results/trans-removed-water.json"
        for version in (2, 3):
            with physical_version(roots, version):
                upgraded = proof()
                require(f"TEMP-v{version}-exact-both-side-physical-evidence-passes", upgraded["passed"], upgraded["errors"])
                require(f"TEMP-v{version}-all-51-reference-physical-records-bound", all(len(upgraded["versionedPhysicalEvidence"][str(root.resolve())]) == 51 for root in (a, b)))
                sample = json.loads(early.read_text(encoding="utf-8"))
                expected = R.O.expected_fixture_geometry(sample["transparency"])
                require(f"v{version}-expected-count-contract-exactly-shared-with-old-oracle", upgraded["versionedPhysicalEvidence"][str(a.resolve())][early.stem]["expectedGeometry"] == expected)
                for side in ("Server", "Client"):
                    field = "actual" + side + "Geometry"
                    rejected(f"v{version}-missing-{side}-physical-side-fails", early, lambda d, k=field: d["transparency"].pop(k), field)
                    for key in expected:
                        rejected(f"v{version}-{side}-wrong-{key}-count-fails", early,
                                 lambda d, k=field, q=key: d["transparency"][k].__setitem__(q, d["transparency"][k][q]+1), field)
                    rejected(f"v{version}-{side}-missing-floor-count-fails", early, lambda d, k=field: d["transparency"][k].pop("floor"), field)
                    rejected(f"v{version}-{side}-extra-unknown-count-fails", early, lambda d, k=field: d["transparency"][k].update(unreviewedCount=0), field)
                rejected(f"v{version}-unknown-marker-recipe-fails", early, lambda d: d["transparency"].update(markerPattern="unreviewed"), "Unknown fixed physical marker pattern")
                rejected(f"v{version}-one-reference-scene-downgraded-to-legacy-fails", early, lambda d: d["transparency"].pop("fixtureGeometryVersion"), "Mixed fixture geometry versions")
                rejected(f"v{version}-bool-count-not-exact-integer-fails", early, lambda d: d["transparency"]["actualClientGeometry"].update(sky=False), "actualClientGeometry")
                rejected(f"v{version}-float-count-not-exact-integer-fails", early, lambda d: d["transparency"]["actualServerGeometry"].update(floor=357.0), "actualServerGeometry")
                rejected(f"v{version}-declared-striped-but-solid-actual-marker-fails", early, lambda d: d["transparency"].update(markerPattern="striped"), "physical")
                rejected(f"v{version}-candidate-holdout-side-evidence-required", hpath, lambda d: d["transparency"].pop("actualClientGeometry"), "actualClientGeometry")
                rejected(f"v{version}-candidate-SkyOn-skyentity-count-required", c / "results/trans-sky-water-none-marker.json", lambda d: d["transparency"]["actualServerGeometry"].update(skyEntities=0), "actualServerGeometry")
                if version == 2:
                    require("v2-no-v3-clock-fluid-or-whole-root-fields-invented", all("actualServerGameTime" not in record and "fluidFreezeMode" not in record and "neverSkyObservedRootFrames" not in record for records in upgraded["versionedPhysicalEvidence"].values() for record in records.values()))
                    continue
                evidence = upgraded["versionedPhysicalEvidence"]
                require("v3-whole-NoSky-root-counter-present-on-every-reference-scene", all(type(record.get("neverSkyObservedRootFrames")) is int and record["neverSkyObservedRootFrames"] > 0 for root in (a, b) for record in evidence[str(root.resolve())].values()))
                require("v3-actual-clocks-separate-from-fixed-display-phase", all(record["actualServerGameTime"] != record["actualClientGameTime"] for records in evidence.values() for record in records.values()))
                for field in ("actualServerGameTime", "actualClientGameTime"):
                    rejected("v3-missing-"+field+"-fails", early, lambda d, k=field: d["transparency"].pop(k), field)
                    rejected("v3-noninteger-"+field+"-fails", early, lambda d, k=field: d["transparency"].__setitem__(k, 1000.5), field)
                    rejected("v3-bool-"+field+"-fails", early, lambda d, k=field: d["transparency"].__setitem__(k, True), field)
                    rejected("v3-negative-"+field+"-fails", early, lambda d, k=field: d["transparency"].__setitem__(k, -1), field)
                    # Keep all other evidence valid while freezing a whole real
                    # TEMP reference timeline; a correct mode label is insufficient.
                    snapshots = {}
                    try:
                        for path in (a / "results").glob("trans-*.json"):
                            snapshots[path] = path.read_bytes(); data = json.loads(snapshots[path]); data["transparency"][field] = 777; put(path, data)
                        frozen_clock = proof()
                    finally:
                        for path, before in snapshots.items(): path.write_bytes(before)
                    require("v3-whole-reference-"+field+"-frozen-fails", frozen_clock["status"] == "NOT_VERIFIED" and field in json.dumps(frozen_clock) and "does not advance" in json.dumps(frozen_clock))
                    snapshots = {}
                    try:
                        for slot, state in itertools.product(("open", "sky"), ("marker", "empty")):
                            path = c / "results" / f"trans-{slot}-water-none-{state}.json"
                            snapshots[path] = path.read_bytes(); data = json.loads(snapshots[path]); data["transparency"][field] = 777; put(path, data)
                        frozen_clock = proof()
                    finally:
                        for path, before in snapshots.items(): path.write_bytes(before)
                    require("v3-candidate-"+field+"-frozen-fails", frozen_clock["status"] == "NOT_VERIFIED" and "does not advance" in json.dumps(frozen_clock))
                rejected("v3-wrong-global-fluid-freeze-mode-fails", early, lambda d: d["transparency"].update(fluidFreezeMode="legacy-global-server"), "not global freeze")
                rejected("v3-missing-water-only-mode-fails", early, lambda d: d["transparency"].pop("fluidFreezeMode"), "not global freeze")
                for value in (0, -1, False, 1.5):
                    rejected("v3-invalid-actual-water-control-"+repr(value)+"-fails", hpath, lambda d, v=value: d.update(suppressedFixtureWaterTicks=v), "suppressedFixtureWaterTicks")
                rejected("v3-missing-actual-water-control-fails", hpath, lambda d: d.pop("suppressedFixtureWaterTicks"), "suppressedFixtureWaterTicks")
                rejected("v3-missing-water-counter-on-nonwater-also-fails", early, lambda d: d.pop("suppressedFixtureWaterTicks"), "suppressedFixtureWaterTicks")
                for value in (0, -1, False, 1.5):
                    rejected("v3-invalid-whole-NoSky-root-counter-"+repr(value)+"-fails", early, lambda d, v=value: d.update(neverSkyObservedRootFrames=v), "whole-NoSky root counter")
                rejected("v3-missing-whole-NoSky-root-counter-fails", early, lambda d: d.pop("neverSkyObservedRootFrames"), "whole-NoSky root counter")
                rejected("v3-removed-51st-reference-whole-root-evidence-required", removed, lambda d: d.pop("neverSkyObservedRootFrames"), "whole-NoSky root counter")
                snapshots = {}
                try:
                    for path in (a / "results").glob("trans-*.json"):
                        snapshots[path] = path.read_bytes(); data = json.loads(snapshots[path]); data["neverSkyObservedRootFrames"] = 100; put(path, data)
                    frozen_roots = proof()
                finally:
                    for path, before in snapshots.items(): path.write_bytes(before)
                require("v3-positive-but-nonadvancing-whole-NoSky-root-counter-fails", frozen_roots["status"] == "NOT_VERIFIED" and "root counter does not advance" in json.dumps(frozen_roots))
                rejected("v3-removed-51st-reference-waterOutside-must-be-zero", removed, lambda d: d["transparency"]["actualClientGeometry"].update(waterOutside=1), "actualClientGeometry")
                with changed_bytes(sky_marker, sky_marker.read_bytes()):
                    png(sky_marker, leaked); upgraded_leak = proof()
                require("TEMP-v3-trueLeak-still-detected-not-waived-by-good-physical-evidence", upgraded_leak["status"] == "MARKER_INFLUENCE_DETECTED" and not upgraded_leak["passed"])
        require("versioned-TEMP-edits-restored-v1-originals", proof()["passed"] and "fixtureGeometryVersion" not in json.loads(early.read_text(encoding="utf-8"))["transparency"])
        # Actual DH TOML semantics, not only identical pack/mods/phase, determine
        # whether independent runs share the same distant-generation background.
        with distant_environment(roots):
            distant = proof()
            require("TEMP-installed-DH-disabled-default-actual-TOML-passes", distant["passed"], distant["errors"])
            require("actual-DH-generator-and-renderer-signature-bound", distant["actualEnvironment"]["distantGeneration"] == dict(installed=True, configPresent=True, generatorPlan="DISABLED", rendererMode="DEFAULT"))
            require("DH-controlled-near-world-never-dynamic-LOD-acceptance", all(x["dynamicLodAcceptance"] is False and "not established" in x["warning"] for x in distant["distantGenerationEvidence"].values()))
            require("actual-DH-TOML-file-hash-bound", all(str((root / "config/DistantHorizons.toml").resolve()) in distant["inputs"] for root in roots))
            for root in roots:
                path = root / "config/DistantHorizons.toml"
                with changed_bytes(path, b'[common.worldGenerator]\ngeneratorPlan="SURFACE_THEN_CHUNKS"\n[client.advanced.debugging]\nrendererMode="DEFAULT"\n'): bad_dh = proof()
                require(root.name+"-requested-disabled-but-actual-generator-active-fails", bad_dh["status"] == "NOT_VERIFIED" and "DH requested=disabled" in json.dumps(bad_dh))
                with changed_bytes(path, b'[common.worldGenerator]\ngeneratorPlan="DISABLED"\n[client.advanced.debugging]\nrendererMode="DISABLED"\n'): bad_dh = proof()
                require(root.name+"-requested-disabled-but-actual-renderer-changed-fails", bad_dh["status"] == "NOT_VERIFIED" and "DH requested=disabled" in json.dumps(bad_dh))
                # Instance policy removes the disabled-policy claim, so this
                # failure must come from A/B/candidate actual semantic mismatch.
                with changed_json(root / "fixture.json", lambda d: d["distantGenerationPolicy"].update(requested="instance")):
                    with changed_bytes(path, b'[common.worldGenerator]\ngeneratorPlan="SURFACE_THEN_CHUNKS"\n[client.advanced.debugging]\nrendererMode="DEFAULT"\n'): bad_dh = proof()
                    require(root.name+"-actual-DH-generator-difference-fails-environment-comparison", bad_dh["status"] == "NOT_VERIFIED" and "artifact/pack/settings" in json.dumps(bad_dh))
                    with changed_bytes(path, b'[common.worldGenerator]\ngeneratorPlan="DISABLED"\n[client.advanced.debugging]\nrendererMode="DEBUG"\n'): bad_dh = proof()
                    require(root.name+"-actual-DH-renderer-difference-fails-environment-comparison", bad_dh["status"] == "NOT_VERIFIED" and "artifact/pack/settings" in json.dumps(bad_dh))
            for field, value in (("actualGeneratorPlan", "SURFACE_THEN_CHUNKS"), ("rendererModeUnchanged", "DISABLED"), ("limitation", "")):
                rejected("DH-declared-disabled-wrong-"+field+"-fails", b / "fixture.json", lambda d, k=field, v=value: d["distantGenerationPolicy"].__setitem__(k, v), "DH requested=disabled")
            dh_config = b / "config/DistantHorizons.toml"
            with changed_bytes(dh_config, b'# harmless date\n[client.advanced.debugging]\nrendererMode = "DEFAULT"\n[common.worldGenerator]\ngeneratorPlan = "DISABLED"\n'): dh_churn = proof()
            require("DH-TOML-comment-order-whitespace-churn-accepted-semantically", dh_churn["passed"])
            with changed_bytes(dh_config, b'[common.worldGenerator]\ngeneratorPlan="DISABLED"\n'): bad_dh = proof()
            require("DH-actual-TOML-missing-renderer-semantic-fails", bad_dh["status"] == "NOT_VERIFIED" and "generatorPlan/rendererMode" in json.dumps(bad_dh))
            with changed_bytes(dh_config, b'[client.advanced.debugging]\nrendererMode="DEFAULT"\n'): bad_dh = proof()
            require("DH-actual-TOML-missing-generator-semantic-fails", bad_dh["status"] == "NOT_VERIFIED" and "generatorPlan/rendererMode" in json.dumps(bad_dh))
            with changed_bytes(dh_config, b'[common.worldGenerator\ngeneratorPlan="DISABLED"'): bad_dh = proof()
            require("DH-malformed-actual-TOML-fails", bad_dh["status"] == "NOT_VERIFIED" and "generatorPlan/rendererMode" in json.dumps(bad_dh))
            absolute = dh_config.resolve(strict=True); assert absolute.is_relative_to(project.resolve()) and absolute.name == "DistantHorizons.toml"
            saved = dh_config.read_bytes(); dh_config.unlink()
            try: bad_dh = proof()
            finally: dh_config.write_bytes(saved)
            require("DH-requested-disabled-missing-actual-config-fails", bad_dh["status"] == "NOT_VERIFIED" and "DH requested=disabled" in json.dumps(bad_dh))
            snapshots = {}; removed_configs = {}
            try:
                for root in roots:
                    path = root / "fixture.json"; snapshots[path] = path.read_bytes(); data = json.loads(snapshots[path]); data.pop("distantGenerationPolicy"); put(path, data)
                    path = root / "config/DistantHorizons.toml"; removed_configs[path] = path.read_bytes()
                    absolute = path.resolve(strict=True); assert absolute.is_relative_to(project.resolve()) and absolute.name == "DistantHorizons.toml"; path.unlink()
                dh_legacy = proof()
            finally:
                for path, before in snapshots.items(): path.write_bytes(before)
                for path, before in removed_configs.items(): path.write_bytes(before)
            require("legacy-no-new-DH-policy-or-actual-config-keeps-original-fields", dh_legacy["passed"] and dh_legacy["actualEnvironment"]["distantGeneration"] == dict(installed=True, configPresent=False))
        rejected("requested-disabled-without-installed-DH-fails", b / "fixture.json", lambda d: d.update(distantGenerationPolicy=dict(requested="disabled", actualGeneratorPlan="DISABLED", rendererModeUnchanged="DEFAULT", limitation="TEMP near-world only")), "DH requested=disabled")
        require("TEMP-DH-install-config-edits-restored-original-noDH-v1", proof()["passed"] and not (b / "mods/synthetic-dh.jar").exists() and not (b / "config/DistantHorizons.toml").exists())
        # Exercise actual CLI and existing-report protection without subprocess/GPU.
        cli_output = project / "cli-diagnostic.json"
        old_root = R.ROOT
        try:
            # main() deliberately uses the actual project; TEMP must be declared
            # via the proof wrapper while all actual fixture/parser code executes.
            actual_proof = R.proof
            def temp_proof(*args, **kw): return actual_proof(*args, **dict(kw, project=project))
            R.proof = temp_proof
            argv = ["--ref-a", str(a), "--ref-b", str(b), "--candidate", str(c), "--expected-jar-sha256", pin, "--protection", "none", "--output", str(cli_output)]
            with redirect_stdout(io.StringIO()): code = R.main(argv)
            original = cli_output.read_bytes()
            with redirect_stdout(io.StringIO()): code2 = R.main(argv)
            require("CLI-noLeak-exit-zero-and-JSON", code == code2 == 0 and json.loads(original)["passed"])
            require("CLI-new-report-preserves-existing-history", cli_output.read_bytes() == original and (project / "cli-diagnostic-r2.json").exists())
            with changed_bytes(sky_marker, sky_marker.read_bytes()):
                png(sky_marker, leaked)
                with redirect_stdout(io.StringIO()): leak_code = R.main(argv)
            require("CLI-trueLeak-exit-one", leak_code == 1 and json.loads((project / "cli-diagnostic-r3.json").read_text())["status"] == "MARKER_INFLUENCE_DETECTED")
        finally: R.proof = actual_proof; R.ROOT = old_root
    report = dict(kind="synthetic/TEMP selftest ONLY; not real GPU or independent audit", passed=all(x["passed"] for x in checks),
                  total=len(checks), passedChecks=sum(x["passed"] for x in checks), failedChecks=sum(not x["passed"] for x in checks),
                  seconds=round(time.monotonic()-start, 3), checks=checks,
                  toolSha256={str(path): R.sha(path) for path in (Path(__file__), Path(R.__file__), Path(R.O.__file__), Path(R.P.__file__))})
    if output:
        output = R.O.new_report_path(output); output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as stream: json.dump(report, stream, indent=2, allow_nan=False); stream.write("\n")
        print("REPORT", output)
    print("SELFTEST", report["passedChecks"], "/", report["total"], "failed", report["failedChecks"], "seconds", report["seconds"])
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--output", type=Path)
    raise SystemExit(run(parser.parse_args().output))
