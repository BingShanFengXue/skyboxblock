#!/usr/bin/env python3
"""Pure synthetic portal oracle controls; no Minecraft/Gradle or fixture writes."""
from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import sys

import numpy as np

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("portal_oracle", Path(__file__).with_name("check-portal-regression.py"))
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)


def meta():
    p = np.array([[1., 0, 0, 0], [0, 4 / 3, 0, 0], [0, 0, 1.02, -.202], [0, 0, 1, 0]])
    root = dict(camera=[.5, 141.62, -3.], projection=p.T.ravel().tolist(), view=np.eye(4).ravel().tolist(),
                portalRendering=False, irisShadow=False, dimension="minecraft:overworld")
    child = dict(root, camera=[32.5, 141.62, -3.], portalRendering=True)
    return dict(scene="01-single-front", width=192, height=144, rootView=root,
                views=[root, child], fixtureGeometry=copy.deepcopy(o.GEOMETRY))


def pictures(m):
    g = o.geometry(m)
    h, w = m["height"], m["width"]
    y, x = np.indices((h, w))
    ordinary = np.full((h, w, 3), [110, 170, 230], dtype=np.uint8)
    ordinary[g["behind"] & g["stripeRed"]] = [150, 30, 25]
    ordinary[g["behind"] & ~g["stripeRed"]] = [170, 170, 170]
    texture = np.where(((x // 2 + y // 3) % 2)[..., None], 80, 110)
    textured = np.repeat(texture, 3, axis=-1).astype(np.uint8)
    ordinary[g["pillar"]] = textured[g["pillar"]]
    reference = np.stack((20 + x * .8, 80 + y * .6, 170 + x * .2), axis=-1).astype(np.uint8)
    final = ordinary.copy()
    final[g["childSky"]] = reference[g["childSky"]]
    return g, ordinary, final, reference


def movement():
    result = []
    for direction, start, end, count in (("outward", -.75, 3., 21), ("return", 3., 1., 15)):
        returning = direction == "return"
        positions = []
        for i, z in enumerate(np.linspace(start, end, count)):
            remote = z > 2 if not returning else z >= 2
            positions.append(dict(position=[32.5 if remote else .5, 140., float(z)],
                                  dimension="minecraft:overworld", walkTick=i + 1))
        before, after = copy.deepcopy(positions[0]), copy.deepcopy(positions[-1])
        result.append(dict(direction=direction,
            movementMechanism="client keyUp; camera rotation locked; no position lock or teleport during walk",
            completed=True, clientReachedOppositeSide=True, serverConfirmedOppositeSide=True,
            startSideCorrect=True, portalTranslationObserved=True, noResetTeleportDuringWalk=True,
            resetTeleportCountBefore=5, resetTeleportCountAfter=5,
            before=before, after=after, serverAfter=copy.deepcopy(after), samples=positions))
    return result


def run():
    cases = []
    def require(name, condition):
        cases.append(bool(condition)); print(("PASS " if condition else "FAIL ") + name)

    m = meta()
    g, ordinary, final, reference = pictures(m)
    checks = o.view_image_checks(ordinary, final, g["childSky"], g["childPillar"], reference)
    require("correct-portal-sky-and-pillar-pass", all(c["passed"] for c in checks))
    # Independent analytic gate at z=2: at x=0.5+/-2, z distance=5, xNDC=+/-0.4.
    occupied_columns = np.flatnonzero(g["portal"].any(axis=0))
    require("raw-gate-projection-has-independent-analytic-boundary", occupied_columns[0] == 58 and occupied_columns[-1] == 133)
    require("baseline-red-white-marker-visible", o.stripe_evidence(ordinary, g)[0])
    require("baseline-stone-pillar-at-independent-projection", o.pillar_evidence(ordinary, g["childPillar"])[0])
    roi = o.erode(g["childSky"])
    require("final-own-reference-passes", o.sky_match(final, reference, roi)[0])
    shifted = final.copy()
    shifted[g["childSky"]] = np.roll(reference, 19, axis=1)[g["childSky"]]
    require("shifted-sky-fails-with-no-metadata-change", not o.sky_match(shifted, reference, roi)[0])
    spill = final.copy(); spill[30:45, 15:35] = [5, 10, 20]
    checks = o.view_image_checks(ordinary, spill, g["childSky"], g["childPillar"])
    require("sky-bleeding-outside-portal-fails", any(not c["passed"] and c["name"] == "same-frame-no-world-leak" for c in checks))
    covered = final.copy(); covered[g["childPillar"]] = reference[g["childPillar"]]
    checks = o.view_image_checks(ordinary, covered, g["childSky"], g["childPillar"])
    require("covered-opaque-pillar-fails", any(not c["passed"] and "pillar" in c["name"] for c in checks))
    require("covered-pillar-fails-independent-final-geometry", not o.pillar_evidence(covered, g["childPillar"])[0])
    require("normal-TAA-sized-one-RGB-residual-allowed", o.compare_images(ordinary, np.clip(ordinary.astype(int) + 1, 0, 255).astype(np.uint8), np.ones(roi.shape, bool))[0])
    bright_pre = np.clip(ordinary.astype(float) * .8, 0, 255).astype(np.uint8)
    bright_post = bright_pre.copy(); bright_post[g["childSky"]] = reference[g["childSky"]]
    require("cross-run-global-gain-not-mislabelled-by-same-frame-scope", all(c["passed"] for c in o.view_image_checks(bright_pre, bright_post, g["childSky"], g["childPillar"], reference)))
    blank = np.full_like(ordinary, 50); thin = blank.copy(); thin[40, 5:30] = 150
    okay, metrics = o.compare_images(blank, thin, np.ones(roi.shape, bool))
    require("localized-thin-spill-not-hidden-in-global-MAE", not okay and metrics["mae"] < 1 and metrics["worstTileMae"] > 4)
    zero_roi = np.zeros(roi.shape, bool)
    require("zero-geometry-root-still-proves-full-frame-unchanged", all(c["passed"] for c in o.view_image_checks(ordinary, ordinary, zero_roi, zero_roi)))
    require("shifted-ordinary-world-layout-fails", not o.compare_images(ordinary, np.roll(ordinary, 16, axis=1), np.ones(roi.shape, bool))[0])
    missing = False
    try: o.safe_child(Path.cwd(), None)
    except ValueError: missing = True
    require("missing-child-reference-fails-closed", missing)
    escaping = False
    try: o.safe_child(Path.cwd(), "../outside.png")
    except ValueError: escaping = True
    require("reference-directory-traversal-fails-closed", escaping)
    require("reference-size-mismatch-not-silently-resized", not o.sky_match(final, reference[::2, ::2], roi)[0])
    actual = movement()
    require("real-sampled-outward-return-and-server-arrival-pass", all(c["passed"] for c in o.movement_checks(actual)))
    fake = copy.deepcopy(actual)
    for e in fake:
        for s in e["samples"]: s["position"][2] = e["samples"][0]["position"][2]
    require("fake-movement-true-flags-no-walking-fails", any(not c["passed"] for c in o.movement_checks(fake)))
    wrong_server = copy.deepcopy(actual); wrong_server[0]["serverAfter"]["position"] = [.5, 140., 0.]
    require("fake-server-confirmation-boolean-fails-numeric-check", any(not c["passed"] for c in o.movement_checks(wrong_server)))
    wrong_reset = copy.deepcopy(actual); wrong_reset[0]["resetTeleportCountAfter"] = 6
    require("reset-teleport-during-walk-fails", any(not c["passed"] for c in o.movement_checks(wrong_reset)))
    # Independent world P changes while IP raw gate P deliberately does not.
    scaled = copy.deepcopy(m)
    for v in scaled["views"]:
        p = o.matrix(v["projection"])
        v["rasterHints"] = dict(capturedProjection=v["projection"], capturedView=v["view"],
            gbufferProjection0=[float(p[0, 0] * .88), float(p[1, 1] * .88), -1., 0.],
            fsrRenderScale=[.5875, .5868], taaJitter=[.0003, -.0005], fsrScreenSize=[112, 84], screenSize=[192, 144])
    profile = dict(iris=True, shaderHash=o.ITRP_SHA256)
    double_projection = o.geometry(scaled, profile)
    require("FSR-world-projection-does-not-alter-IP-raw-gate", np.array_equal(double_projection["portal"], g["portal"]))
    require("FSR-remote-world-geometry-uses-hints-not-raw-or-mod-P", not np.array_equal(double_projection["childPillar"], g["childPillar"]))
    require("per-axis-scale-not-hardcoded-is-retained", scaled["views"][1]["rasterHints"]["fsrRenderScale"][0] != scaled["views"][1]["rasterHints"]["fsrRenderScale"][1])
    unknown = False
    try: o.geometry(scaled, dict(iris=True, shaderHash="unknown"))
    except ValueError: unknown = True
    require("unknown-scaled-pack-mapping-fails-closed", unknown)
    native_view = copy.deepcopy(scaled["views"][0])
    native_view["rasterHints"]["fsrRenderScale"] = None
    native_view["rasterHints"]["fsrScreenSize"] = None
    native = o.actual_world(native_view, dict(iris=True, shaderHash=o.ITRP_SHA256, fsrOption=-1))
    require("known-native-null-FSR-uniforms-are-not-a-false-baseline-failure", np.array_equal(o.matrix(native["projection"]), o.matrix(native_view["projection"])))
    missing_active = False
    try: o.actual_world(native_view, dict(iris=True, shaderHash=o.ITRP_SHA256, fsrOption=2))
    except ValueError: missing_active = True
    require("active-FSR-missing-raster-uniforms-fail-closed", missing_active)
    missing_matrix = copy.deepcopy(native_view); missing_matrix["rasterHints"].pop("capturedProjection")
    rejected_matrix = False
    try: o.actual_world(missing_matrix, dict(iris=True, shaderHash=o.ITRP_SHA256, fsrOption=-1))
    except ValueError: rejected_matrix = True
    require("native-still-requires-independent-captured-matrices", rejected_matrix)
    bad_child = copy.deepcopy(m["views"][1]); bad_child["projection"][0] *= .8
    require("unexplained-child-matrix-divergence-not-warped-away", not o.root_child_match(m["rootView"], bad_child, np.array([32., 0., 0.]))[0])
    require("finite-report-values-serialize", bool(json.dumps(checks, allow_nan=False)))
    known = o.ITRP_SHA256
    manifest = dict(shader="iterationRP Alpha 0.8.31.zip", shaderSha256=known, settings="#original\nFSR2_SCALE=-1\n")
    defaults = o.settings_evidence(manifest, None, actual_pack_hash=known)
    require("Iris-deleted-verified-native-default-options-file-is-valid", defaults["matched"] and defaults["missingFileAllowed"]
        and defaults["actualSource"] == "verified-defaults")
    a = o.canonical_settings("#time one\r\nFSR2_SCALE = 2\r\n", known)
    b = o.canonical_settings("!time two\nFSR2_SCALE:2\n", known)
    require("timestamp-comments-newlines-do-not-change-effective-FSR", a == b and o.semantic_hash(a) == o.semantic_hash(b))
    require("explicit-proven-default-and-omitted-default-equivalent", o.canonical_settings("FSR2_SCALE=-1\n", known) == o.canonical_settings("", known))
    active_manifest = dict(manifest, settings="FSR2_SCALE=2\n")
    require("missing-nondefault-options-file-fails", not o.settings_evidence(active_manifest, None, actual_pack_hash=known)["matched"])
    require("manifest-actual-FSR-mismatch-fails-even-if-all-runs-agree", not o.settings_evidence(active_manifest, "FSR2_SCALE=-1\n", actual_pack_hash=known)["matched"])
    unknown_flag = dict(manifest, settings="FSR2_SCALE=-1\nUNVERIFIED_FLAG=7\n")
    mismatch = o.settings_evidence(unknown_flag, "FSR2_SCALE=-1\nUNVERIFIED_FLAG=8\n", actual_pack_hash=known)
    require("unknown-nondefault-parameter-preserved-and-compared", not mismatch["matched"] and "UNVERIFIED_FLAG" in mismatch["differences"])
    require("missing-file-does-not-discard-unverified-option", not o.settings_evidence(unknown_flag, None, actual_pack_hash=known)["matched"])
    require("unknown-inline-comment-like-value-not-dropped", o.properties("CUSTOM_TOKEN=abc#def\n")["CUSTOM_TOKEN"] == "abc#def")
    rejected_unknown = False
    try: o.settings_evidence(manifest, None, actual_pack_hash="different-archive")
    except ValueError: rejected_unknown = True
    require("manifest-cannot-fake-defaults-for-unknown-actual-archive", rejected_unknown)
    rejected_pack = False
    try: o.settings_evidence(dict(manifest, shaderSha256="unknown"), None, actual_pack_hash="unknown")
    except ValueError: rejected_pack = True
    require("unknown-pack-missing-file-not-inferred-native", rejected_pack)
    require("properties-last-assignment-is-effective", o.properties("X=1\nX=2\n") == {"X": "2"})
    probe = meta(); probe.update(width=1280, height=800)
    probe["rootView"]["rasterHints"] = dict(fsrRenderScale=[.5875, .5875], fsrScreenSize=[752.,470.])
    require("canonical-FSR-crosschecks-independent-live-hints", o.live_fsr_settings(probe, dict(fsrOption=2))[0])
    require("native-declaration-with-active-live-scale-fails", not o.live_fsr_settings(probe, dict(fsrOption=-1))[0])
    first_props = o.properties("#one\nshaderPack=iterationRP Alpha 0.8.31.zip\ncolorSpace=SRGB\nenableShaders=true\n")
    second_props = o.properties("#two\r\nenableShaders=true\r\ncolorSpace=SRGB\r\nshaderPack=iterationRP Alpha 0.8.31.zip\r\n")
    require("Iris-global-properties-ignore-comment-and-order-only", first_props == second_props)
    altered_props = dict(second_props, colorSpace="DISPLAY_P3")
    require("Iris-global-nondefault-parameters-not-ignored", first_props != altered_props)
    print(f"{sum(cases)}/{len(cases)} portal oracle controls passed")
    return 0 if all(cases) else 1


if __name__ == "__main__":
    raise SystemExit(run())
