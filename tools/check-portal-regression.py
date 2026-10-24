#!/usr/bin/env python3
"""Independent three-way portal oracle. No renderer-generated coverage masks are used.

--baseline BASE --empty EMPTY --sky SKY --output REPORT
Directories may be fixture roots or results/. Fresh fixtureGeometry and skyViews
are mandatory; historical screenshots without the new evidence fail closed.
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
from pathlib import Path
import re
import zipfile

import numpy as np
from PIL import Image

SCENES = ("00-no-portal", "01-single-front", "02-single-oblique", "03-single-near", "04-through-before",
          "05-through-after", "06-return-before", "07-return-after", "08-clean")
FIXED = SCENES[1:5]
PORTAL_SCENES = set(FIXED) | {"06-return-before"}
GEOMETRY = dict(skyBounds=[[29, 140, 6], [36, 147, 7]], behindBounds=[[29, 140, 8], [36, 147, 9]],
                remotePillarBounds=[[32, 140, 4], [33, 144, 5]],
                sourcePortalBounds=[[-1.5, 140, 2], [2.5, 146, 2]], translation=[32, 0, 0])
LIMITS = dict(minPixels=64, margin=3, matrixAtol=2e-5, cameraAtol=1e-4,
              skyMaxRgbError=1, worldMae=1., worldP99=6., worldChangedFraction=.02,
              worldTileMae=4., pillarMae=1., pillarP99=5., stripeAccuracy=.85,
              pillarEdgeCoverage=.40, pillarTextureRms=.6)
FRAME_KEYS = set("viewId rootTransaction root portal shadow dimension camera projection view rasterScale rasterJitter "
                 "viewport targetViewport blocks vertices drawn irisFinalHook finalResolved finalResolveCount "
                 "referenceWorld referenceDisplay itrpMaterialWrites itrpBackgroundInjected itrpInjectionCount bridgeFailure "
                 "preViewImage postViewImage imageStage".split())
ITRP_SHA256 = "334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8"


def rgb(path):
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB")).copy()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


# Only defaults verified in this exact immutable pack (Settings.glsl:772,799).
VERIFIED_DEFAULTS = {ITRP_SHA256: {"FSR2_SCALE": "-1", "CUSTOM_RENDER_RESOLUTION": "false"}}


def properties(text):
    """Semantic options: ignore comment/date/newline churn, retain ALL unknown keys."""
    result = {}
    for raw in text.lstrip("\ufeff").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")): continue
        if line.endswith("\\"):
            raise ValueError("unverified continued shader option line")
        match = re.fullmatch(r"([^\s=:]+)(?:\s*[=:]\s*|\s+)?(.*)", line)
        if match is None or "\\" in match[1]:
            raise ValueError("unverified escaped shader option key")
        result[match[1]] = match[2].strip()  # Java properties' last assignment wins.
    return result


def canonical_settings(text, pack_hash):
    options = dict(VERIFIED_DEFAULTS.get(pack_hash, {}))
    options.update(properties(text))
    if pack_hash in VERIFIED_DEFAULTS:
        if not re.fullmatch(r"-1|[0-4]", options["FSR2_SCALE"]):
            raise ValueError("unverified FSR2_SCALE value")
        value = options["CUSTOM_RENDER_RESOLUTION"].lower()
        if value not in ("true", "false"): raise ValueError("unverified CUSTOM_RENDER_RESOLUTION value")
        options["CUSTOM_RENDER_RESOLUTION"] = value
    return dict(sorted(options.items()))


def semantic_hash(options):
    return hashlib.sha256(json.dumps(options, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def settings_evidence(manifest, actual_text, actual_pack_hash=None):
    """Pure comparison; caller supplies the verified actual archive hash."""
    pack_hash = manifest["shaderSha256"]
    actual_hash = pack_hash if actual_pack_hash is None else actual_pack_hash
    declared = canonical_settings(manifest["settings"], pack_hash)
    if not manifest["shader"]:
        actual = {}
        missing_allowed = not declared
    elif actual_text is None:
        if actual_hash not in VERIFIED_DEFAULTS:
            raise ValueError("missing options file for actual pack with no verified defaults")
        actual = canonical_settings("", actual_hash)
        missing_allowed = declared == actual
    else:
        actual = canonical_settings(actual_text, actual_hash)
        missing_allowed = True
    differences = {k: dict(manifest=declared.get(k), actual=actual.get(k))
                   for k in sorted(declared.keys() | actual.keys()) if declared.get(k) != actual.get(k)}
    return dict(manifestEffective=declared, actualEffective=actual, differences=differences,
                matched=declared == actual and missing_allowed,
                actualFilePresent=actual_text is not None, missingFileAllowed=missing_allowed,
                actualSource="file" if actual_text is not None else "verified-defaults" if manifest["shader"] else "shader-disabled",
                manifestSemanticSha256=semantic_hash(declared), actualSemanticSha256=semantic_hash(actual),
                manifestTextSha256=hashlib.sha256(manifest["settings"].encode()).hexdigest())


def live_fsr_settings(meta, profile):
    hints = harness_root(meta)["rasterHints"]
    option = profile["fsrOption"]
    w, h = meta["width"], meta["height"]
    if option == -1:
        scale, size = hints["fsrRenderScale"], hints["fsrScreenSize"]
        ok = ((scale is None or np.allclose(scale, [1, 1], atol=1e-5, rtol=0))
              and (size is None or np.allclose(size, [w, h], atol=.51, rtol=0)))
        expected_size = [w, h]
    else:
        factor = {0: 1., 1: .6667, 2: .5882, 3: .5, 4: .3333}[option]
        expected_size = np.floor(np.array([w, h]) * factor)
        scale, size = hints["fsrRenderScale"], hints["fsrScreenSize"]
        ok = (scale is not None and size is not None
              and np.allclose(scale, expected_size / [w, h], atol=2e-6, rtol=0)
              and np.allclose(size, expected_size, atol=.51, rtol=0))
        expected_size = expected_size.tolist()
    return bool(ok), dict(configuredFSR=option, liveScale=scale, liveSize=size, expectedSize=expected_size)


def erode(mask, n=3):
    for _ in range(n):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1, 1:-1] & p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:]
    return mask


def dilate(mask, n=3):
    for _ in range(n):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1, 1:-1] | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
    return mask


def matrix(values):
    result = np.asarray(values, dtype=float)
    if result.shape != (16,) or not np.isfinite(result).all():
        raise ValueError("missing/nonfinite 4x4 column-major matrix")
    result = result.reshape(4, 4).T
    if abs(np.linalg.det(result)) < 1e-12:
        raise ValueError("singular camera matrix")
    return result


def camera(view):
    value = np.asarray(view["camera"], dtype=float)
    if value.shape != (3,) or not np.isfinite(value).all():
        raise ValueError("missing/nonfinite world camera")
    return value


def rays(view, width, height):
    inv = np.linalg.inv(matrix(view["projection"]) @ matrix(view["view"]))
    x, y = np.meshgrid(2 * (np.arange(width) + .5) / width - 1, 1 - 2 * (np.arange(height) + .5) / height)
    points = []
    for z in (-1., 0.):
        clip = np.stack((x, y, np.full_like(x, z), np.ones_like(x)), axis=-1)
        result = clip @ inv.T
        points.append(result[..., :3] / result[..., 3, None])
    direction = points[1] - points[0]
    return direction / np.linalg.norm(direction, axis=-1, keepdims=True)


def box_distance(origin, direction, bounds):
    lo, hi = np.asarray(bounds, dtype=float)
    if lo.shape != (3,) or not np.isfinite([lo, hi]).all() or np.any(hi <= lo):
        raise ValueError("expected a finite nonempty world AABB")
    parallel = np.abs(direction) < 1e-12
    safe = np.where(parallel, 1., direction)
    a, b = (lo - origin) / safe, (hi - origin) / safe
    near = np.where(parallel, -np.inf, np.minimum(a, b)).max(axis=-1)
    far = np.where(parallel, np.inf, np.maximum(a, b)).min(axis=-1)
    outside = (parallel & ((origin < lo) | (origin > hi))).any(axis=-1)
    return np.where(~outside & (far >= np.maximum(near, 0)) & (far > 0), np.maximum(near, 0), np.inf)


def portal_intersection(origin, direction, bounds):
    lo, hi = np.asarray(bounds, dtype=float)
    if lo.shape != (3,) or hi[2] != lo[2] or np.any(hi[:2] <= lo[:2]):
        raise ValueError("only the declared zero-thickness Z portal rectangle is supported")
    with np.errstate(divide="ignore", invalid="ignore"):
        distance = (lo[2] - origin[2]) / direction[..., 2]
        hit = origin + direction * distance[..., None]
    valid = (np.isfinite(distance) & (distance > .001) & (hit[..., 0] >= lo[0]) & (hit[..., 0] <= hi[0])
             & (hit[..., 1] >= lo[1]) & (hit[..., 1] <= hi[1]))
    return valid, np.where(valid, distance, np.inf)


def harness_root(meta):
    raw = meta["rootView"]
    if "rasterHints" in raw:
        return raw
    matches = [v for v in meta["views"] if not v["portalRendering"] and not v["irisShadow"]
               and np.linalg.norm(camera(v) - camera(raw)) <= LIMITS["cameraAtol"]]
    if len(matches) != 1:
        raise ValueError("ambiguous raw root observation")
    return matches[0]


def actual_world(observation, profile=None):
    """Derive actual geometry P/V from INDEPENDENT Iris hints, never skyViews."""
    result = dict(observation)
    if not profile or not profile["iris"]:
        return result
    hints = observation["rasterHints"]
    required = {"capturedProjection", "capturedView", "gbufferProjection0", "fsrRenderScale", "taaJitter", "fsrScreenSize", "screenSize"}
    if not required <= hints.keys():
        raise ValueError("missing independent Iris raster hints: " + str(sorted(required - hints.keys())))
    p, v = matrix(hints["capturedProjection"]), matrix(hints["capturedView"])
    native = (profile["shaderHash"] == ITRP_SHA256 and profile.get("fsrOption") == -1
              and not profile.get("customResolution", False))
    for key in ("taaJitter", "fsrScreenSize", "screenSize"):
        if key == "fsrScreenSize" and hints[key] is None and native: continue
        vector = np.asarray(hints[key], dtype=float)
        if vector.shape != (2,) or not np.isfinite(vector).all() or (key != "taaJitter" and np.any(vector <= 0)):
            raise ValueError("invalid independent Iris vector: " + key)
    if hints["fsrRenderScale"] is None and native:
        scale = np.ones(2)
    else:
        scale = np.asarray(hints["fsrRenderScale"], dtype=float)
    if scale.shape != (2,) or not np.isfinite(scale).all() or np.any(scale <= 0):
        raise ValueError("invalid/missing per-axis Iris scale for an active scaled configuration")
    diagonal = np.asarray(hints["gbufferProjection0"], dtype=float)
    if diagonal.shape not in ((3,), (4,)) or not np.isfinite(diagonal).all() or np.any(diagonal[:2] <= 0):
        raise ValueError("invalid independent gbufferProjection0")
    if not np.allclose(scale, 1., rtol=0, atol=1e-5):
        if profile["shaderHash"] != ITRP_SHA256:
            raise ValueError("unknown scaled pack geometry mapping; raw P must not be silently substituted")
        p[0, 0], p[1, 1] = diagonal[:2]
    result.update(projection=p.T.ravel().tolist(), view=v.T.ravel().tolist())
    return result


def geometry(meta, profile=None):
    """IP stencil gate uses raw P; child/world material geometry uses independently derived actual P."""
    raw_root = harness_root(meta)
    root = actual_world(raw_root, profile)
    origin = camera(raw_root)
    raw_direction = rays(raw_root, meta["width"], meta["height"])
    direct_direction = rays(root, meta["width"], meta["height"])
    g = meta["fixtureGeometry"]
    for key, value in GEOMETRY.items():
        if key not in g or not np.allclose(g[key], value, atol=1e-6, rtol=0):
            raise ValueError("unknown/missing fixture geometry: " + key)
    shift = np.asarray(g["translation"], dtype=float)
    reverse = origin[0] > 24
    rect = np.asarray(g["sourcePortalBounds"], dtype=float) + (shift if reverse else 0)
    translation = -shift if reverse else shift
    portal, _ = portal_intersection(origin, raw_direction, rect)
    child_origin = origin + translation
    observed = [v for v in meta["views"] if v["portalRendering"] and not v["irisShadow"]
                and np.linalg.norm(camera(v) - child_origin) <= LIMITS["cameraAtol"]]
    if meta["scene"] in PORTAL_SCENES:
        if len(observed) != 1: raise ValueError("missing/ambiguous independent portal child observation")
        child = actual_world(observed[0], profile)
    else:
        portal[:] = False
        child = dict(root, camera=child_origin.tolist())
    child_direction = rays(child, meta["width"], meta["height"])
    # The actual child ray need not hit the same XY gate when FSR changes world FOV.
    # Clip against destination Z only; the IP gate remains the independent raw-root mask.
    with np.errstate(divide="ignore", invalid="ignore"):
        child_plane_t = (rect[0, 2] - child_origin[2]) / child_direction[..., 2]
    remote, direct = {}, {}
    for name, key in (("sky", "skyBounds"), ("behind", "behindBounds"), ("pillar", "remotePillarBounds")):
        remote[name] = box_distance(child_origin, child_direction, g[key])
        direct[name] = box_distance(origin, direct_direction, g[key])
    child_sky = portal & (remote["sky"] > child_plane_t) & np.isfinite(remote["sky"]) & (remote["sky"] < remote["pillar"])
    child_pillar = portal & (remote["pillar"] > child_plane_t) & np.isfinite(remote["pillar"]) & (remote["pillar"] < remote["sky"])
    behind = portal & (remote["behind"] > child_plane_t) & np.isfinite(remote["behind"]) & (remote["behind"] < remote["pillar"])
    direct_sky = ~portal & np.isfinite(direct["sky"]) & (direct["sky"] < direct["pillar"])
    direct_pillar = ~portal & np.isfinite(direct["pillar"]) & (direct["pillar"] < direct["sky"])
    potential = child_sky | direct_sky
    if meta["scene"] == "08-clean": potential[:] = False
    with np.errstate(invalid="ignore"):
        hit_x = child_origin[0] + child_direction[..., 0] * remote["behind"]
    stripe_red = np.isfinite(hit_x) & (np.floor(np.nan_to_num(hit_x, nan=0, posinf=0, neginf=0)).astype(np.int64) % 2 == 1)
    return dict(portal=portal, childSky=child_sky, directSky=direct_sky, sky=potential,
                childPillar=child_pillar, directPillar=direct_pillar, pillar=child_pillar | direct_pillar,
                behind=behind, stripeRed=stripe_red, translation=translation, childOrigin=child_origin,
                rootWorld=root, childWorld=child)


def compare_images(a, b, roi, mae=1., p99=6., changed=.02, tile_mae=4.):
    count = int(roi.sum())
    m = dict(pixels=count, mae=None, p99=None, maximum=None, changedFraction=None, worstTileMae=None)
    if a.shape != b.shape or count < LIMITS["minPixels"]:
        return False, m
    delta = np.abs(a.astype(float) - b.astype(float))
    values = delta[roi]
    worst = 0.
    # A localized portal spill must not disappear in full-frame MAE/percentiles.
    for y in range(0, len(roi), 16):
        for x in range(0, roi.shape[1], 16):
            mask = roi[y:y+16, x:x+16]
            if mask.sum() >= 64:
                worst = max(worst, float(delta[y:y+16, x:x+16][mask].mean()))
    m.update(mae=float(values.mean()), p99=float(np.percentile(values, 99)), maximum=float(values.max()),
             changedFraction=float((values.max(axis=-1) > 8).mean()), worstTileMae=worst)
    return bool(m["mae"] <= mae and m["p99"] <= p99 and m["changedFraction"] <= changed and worst <= tile_mae), m


def sky_match(actual, reference, roi):
    if actual.shape != reference.shape:
        return False, dict(error="reference must match output pixel grid; no silent resize/warp", pixels=int(roi.sum()))
    count = int(roi.sum())
    difference = np.abs(actual.astype(int) - reference.astype(int))[roi]
    maximum = int(difference.max()) if difference.size else None
    return bool(count >= LIMITS["minPixels"] and maximum <= LIMITS["skyMaxRgbError"]), dict(
        pixels=count, maximumRgbError=maximum, mae=float(difference.mean()) if difference.size else None)


def view_image_checks(pre, post, sky_roi, pillar_roi, reference=None):
    """Same-frame scope checks: no global exposure/TAA correction is necessary."""
    if pre.shape != post.shape or pre.shape[:2] != sky_roi.shape:
        raise ValueError("same-view pre/post dimensions do not match independent output grid")
    diff = np.max(np.abs(pre.astype(int) - post.astype(int)), axis=-1)
    outside = ~dilate(sky_roi, LIMITS["margin"])
    bad = outside & (diff > 1)
    checks = [dict(name="same-frame-no-world-leak", passed=bool(outside.sum() >= LIMITS["minPixels"] and not bad.any()),
                   pixels=int(outside.sum()), changedOutsidePixels=int(bad.sum()),
                   firstChangedPixels=np.argwhere(bad)[:12, ::-1].tolist())]
    pillar = erode(pillar_roi, LIMITS["margin"])
    if pillar.any():
        maximum = int(diff[pillar].max())
        checks.append(dict(name="same-frame-opaque-pillar-preserved", passed=bool(pillar.sum() >= LIMITS["minPixels"] and maximum <= 1),
                           pixels=int(pillar.sum()), maximumRgbError=maximum))
    if reference is not None:
        ok, metrics = sky_match(post, reference, erode(sky_roi, LIMITS["margin"]))
        checks.append(dict(name="same-frame-private-reference", passed=ok, **metrics))
    return checks


def stripe_evidence(pixels, g):
    red_roi = erode(g["behind"] & g["stripeRed"], LIMITS["margin"])
    white_roi = erode(g["behind"] & ~g["stripeRed"], LIMITS["margin"])
    values = pixels.astype(float)
    r, green, b = values[..., 0], values[..., 1], values[..., 2]
    red = (r > green * 1.35 + 8) & (r > b * 1.25 + 8) & (r > 30)
    neutral = values.max(axis=-1) - values.min(axis=-1) < np.maximum(16, values.mean(axis=-1) * .16)
    white = neutral & (values.mean(axis=-1) > 25)
    counts = [int(red_roi.sum()), int(white_roi.sum())]
    fractions = [float(red[red_roi].mean()) if counts[0] else 0., float(white[white_roi].mean()) if counts[1] else 0.]
    passed = min(counts) >= LIMITS["minPixels"] and min(fractions) >= LIMITS["stripeAccuracy"]
    return bool(passed), dict(redPixels=counts[0], whitePixels=counts[1], redAccuracy=fractions[0], whiteAccuracy=fractions[1])


def pillar_evidence(pixels, pillar_roi):
    roi = erode(pillar_roi, LIMITS["margin"])
    values = pixels.astype(float)
    luma = values.mean(axis=-1)
    pad = np.pad(luma, 1, mode="edge")
    blur = (pad[1:-1, 1:-1] + pad[:-2, 1:-1] + pad[2:, 1:-1] + pad[1:-1, :-2] + pad[1:-1, 2:]) / 5
    gradient = np.maximum(np.abs(pad[2:, 1:-1] - pad[:-2, 1:-1]), np.abs(pad[1:-1, 2:] - pad[1:-1, :-2]))
    boundary = pillar_roi & ~erode(pillar_roi, 1)
    edge_count = int(boundary.sum())
    coverage = float(dilate(gradient > 5, 2)[boundary].mean()) if edge_count else 0.
    count = int(roi.sum())
    neutral = values.max(axis=-1) - values.min(axis=-1) <= np.maximum(16, luma * .18)
    fraction = float(neutral[roi].mean()) if count else 0.
    rms = float(np.sqrt(np.mean((luma - blur)[roi] ** 2))) if count else 0.
    passed = (count >= LIMITS["minPixels"] and edge_count >= 16 and fraction >= .70
              and coverage >= LIMITS["pillarEdgeCoverage"] and rms >= LIMITS["pillarTextureRms"])
    return bool(passed), dict(pixels=count, boundaryPixels=edge_count, neutralFraction=fraction,
                              projectedEdgeCoverage=coverage, textureRms=rms)


def movement_checks(evidence):
    checks = []
    def add(name, ok, **m): checks.append(dict(name=name, passed=bool(ok), **m))
    by_direction = {e.get("direction"): e for e in evidence}
    add("two-actual-walks", len(evidence) == 2 and set(by_direction) == {"outward", "return"})
    for direction, sign in (("outward", 1), ("return", -1)):
        e = by_direction.get(direction, {})
        try:
            flags = ("completed", "clientReachedOppositeSide", "serverConfirmedOppositeSide", "startSideCorrect",
                     "portalTranslationObserved", "noResetTeleportDuringWalk")
            add(direction + ":confirmed-flags", all(e[k] is True for k in flags))
            add(direction + ":no-reset-teleport", e["resetTeleportCountBefore"] == e["resetTeleportCountAfter"])
            add(direction + ":key-input-mechanism", "keyUp" in e["movementMechanism"] and "no position lock" in e["movementMechanism"])
            samples = e["samples"]
            positions = np.asarray([s["position"] for s in samples], dtype=float)
            ticks = np.asarray([s["walkTick"] for s in samples], dtype=int)
            if positions.ndim != 2 or positions.shape[1:] != (3,) or len(positions) < 4 or not np.isfinite(positions).all():
                raise ValueError("missing actual walking samples")
            delta = np.diff(positions, axis=0)
            jumps = np.flatnonzero(np.abs(delta[:, 0]) > 24)
            jump_good = (len(jumps) == 1 and abs(delta[jumps[0], 0] - sign * 32) < .5
                         and max(abs(positions[jumps[0], 2] - 2), abs(positions[jumps[0]+1, 2] - 2)) < .9)
            advance = float(sign * (positions[-1, 2] - positions[0, 2]))
            add(direction + ":measured-physical-trajectory", jump_good and advance > (3.2 if sign == 1 else 1.5)
                and np.all(np.diff(ticks) > 0) and np.max(np.abs(delta[:, 2])) < .65
                and float((sign * delta[:, 2] >= -.05).mean()) >= .95,
                samples=len(samples), measuredTranslationJumps=len(jumps), forwardZDistance=advance)
            before, after, server = (np.asarray(e[key]["position"], dtype=float) for key in ("before", "after", "serverAfter"))
            good_side = (29 < server[0] < 36 and server[2] > 2.8) if sign == 1 else (-3 < server[0] < 4 and server[2] < 1.2)
            good_start = (-3 < before[0] < 4 and before[2] < 2) if sign == 1 else (29 < before[0] < 36 and before[2] > 2)
            add(direction + ":numeric-server-arrival", good_side and good_start and np.linalg.norm(server - after) < 1.
                and e["serverAfter"]["dimension"] == "minecraft:overworld"
                and all(s["dimension"] == "minecraft:overworld" for s in samples), serverPosition=server.tolist())
        except (KeyError, ValueError, TypeError, IndexError) as error:
            add(direction + ":complete-movement-evidence", False, error=str(error))
    try:
        out, back = by_direction["outward"], by_direction["return"]
        add("no-reset-between-two-walks", out["resetTeleportCountAfter"] == back["resetTeleportCountBefore"]
            and np.linalg.norm(np.asarray(out["after"]["position"]) - back["before"]["position"]) < 1.)
    except (KeyError, TypeError, ValueError): add("no-reset-between-two-walks", False)
    return checks


def root_child_match(root, child, translation):
    p_error = float(np.max(np.abs(matrix(root["projection"]) - matrix(child["projection"]))))
    v_error = float(np.max(np.abs(matrix(root["view"]) - matrix(child["view"]))))
    c_error = float(np.linalg.norm(camera(root) + translation - camera(child)))
    return bool(p_error <= LIMITS["matrixAtol"] and v_error <= LIMITS["matrixAtol"]
                and c_error <= LIMITS["cameraAtol"]), dict(projectionMaximumError=p_error, viewMaximumError=v_error, cameraTranslationError=c_error)


def results_path(path):
    return path if (path / "summary.json").exists() else path / "results"


def safe_child(root, filename):
    if not isinstance(filename, str) or not filename:
        raise ValueError("missing reference filename")
    result = (root / filename).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError("reference escapes the declared results directory")
    return result


def load(path):
    root = results_path(path)
    fixture = root.parent
    manifest = json.loads((fixture / "fixture.json").read_text(encoding="utf-8"))
    summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
    run = json.loads((fixture / "run-result.json").read_text(encoding="utf-8"))
    metadata = {name: json.loads((root / (name + ".json")).read_text(encoding="utf-8")) for name in SCENES}
    pictures = {name: rgb(root / (name + ".png")) for name in SCENES}
    return dict(root=root, fixture=fixture, manifest=manifest, summary=summary, run=run, metadata=metadata, pictures=pictures)


def check(baseline, empty, sky):
    checks, observations = [], {}
    def add(group, name, passed, **metrics):
        checks.append(dict(group=group, name=name, passed=bool(passed), **metrics))
    groups = {}
    for name, path in (("base", baseline), ("empty", empty), ("sky", sky)):
        try:
            data = groups[name] = load(path)
            manifest, summary, run = data["manifest"], data["summary"], data["run"]
            variant = "baseline" if name == "base" else name
            add(name, "manifest-variant", manifest["variant"] == variant and summary["variant"] == variant)
            add(name, "nine-real-captures", summary["expectedScenes"] == summary["capturedScenes"] == 9
                and tuple(summary["scenes"]) == SCENES and all(m["screenshotWritten"] for m in data["metadata"].values()))
            add(name, "actual-run-success", run["exitCode"] == 0 and run["finished"] is True and run["scenes"] == 9
                and not run["fatalErrors"] and summary["success"] is True and not summary["errors"])
            add(name, "real-through-and-return", summary["actualThroughCompleted"] is True and summary["actualReturnCompleted"] is True)
            for result in movement_checks(summary["movementEvidence"]): add(name, result.pop("name"), result.pop("passed"), **result)
            actual_mods = {}
            for jar in sorted((data["fixture"] / "mods").glob("*.jar")):
                with zipfile.ZipFile(jar) as archive:
                    if "fabric.mod.json" not in archive.namelist(): continue
                    info = json.loads(archive.read("fabric.mod.json"))
                if info["id"] in actual_mods: raise ValueError("duplicate installed mod ID: " + info["id"])
                actual_mods[info["id"]] = dict(file=jar.name, sha256=sha(jar))
            data["actualMods"] = actual_mods
            add(name, "genuine-primary-mod-presence", ("skyboxblock" in actual_mods) == (name != "base")
                and summary["skyboxblockLoaded"] == (name != "base") and manifest["skyboxblockPresent"] == (name != "base"))
            add(name, "actual-harness-hash", actual_mods["skybox_ip_fixture"]["sha256"] == manifest["harnessSha256"])
            for mod in manifest["mods"]:
                add(name, "actual-mod-hash:" + mod["id"], mod["id"] in actual_mods and actual_mods[mod["id"]]["sha256"] == mod["sha256"])
            if name != "base": add(name, "actual-primary-jar-hash", actual_mods["skyboxblock"]["sha256"] == manifest["jarSha256"])
            actual_settings, settings_file_hash, actual_pack_hash = None, None, None
            if manifest["shader"]:
                pack = safe_child(data["fixture"] / "shaderpacks", manifest["shader"])
                actual_pack_hash = sha(pack)
                add(name, "actual-pack-hash", actual_pack_hash == manifest["shaderSha256"])
                option_file = pack.with_name(pack.name + ".txt")
                if option_file.exists():
                    actual_settings = option_file.read_text(encoding="utf-8")
                    settings_file_hash = sha(option_file)
            else: add(name, "explicit-no-shader", manifest["shaderSha256"] is None)
            trace = settings_evidence(manifest, actual_settings, actual_pack_hash=actual_pack_hash)
            trace["actualFileSha256"] = settings_file_hash  # Audit only, NEVER semantic identity.
            data["settingsEvidence"] = trace
            data["settingsHash"] = trace["actualSemanticSha256"]
            add(name, "manifest-actual-effective-settings", trace["matched"], **trace)
            iris_properties = properties((data["fixture"] / "config" / "iris.properties").read_text(encoding="utf-8"))
            add(name, "actual-shader-selection", iris_properties.get("enableShaders", "").lower() == str(bool(manifest["shader"])).lower()
                and (not manifest["shader"] or iris_properties.get("shaderPack") == manifest["shader"]))
            data["irisProperties"] = iris_properties
            data["iris"] = bool(manifest["shader"])
            options = trace["actualEffective"]
            data["profile"] = dict(iris=data["iris"], shaderHash=actual_pack_hash,
                fsrOption=int(options.get("FSR2_SCALE", -1)) if actual_pack_hash == ITRP_SHA256 else None,
                customResolution=options.get("CUSTOM_RENDER_RESOLUTION", "false").lower() == "true")
            for scene in SCENES:
                m = data["metadata"][scene]
                pixels = data["pictures"][scene]
                label = scene + ":"
                add(name, label + "capture-identity", m["scene"] == scene and m["variant"] == variant
                    and pixels.shape == (m["height"], m["width"], 3) and not m["errors"]
                    and m["skyboxblockLoaded"] == (name != "base"))
                add(name, label + "root-camera-observed", m["rootViewObserved"] is True
                    and m["rootView"]["portalRendering"] is False and m["rootView"]["irisShadow"] is False)
                matrix(m["rootView"]["projection"]); matrix(m["rootView"]["view"]); camera(m["rootView"])
                if data["iris"] and actual_pack_hash == ITRP_SHA256 and not data["profile"]["customResolution"]:
                    consistent, live_metrics = live_fsr_settings(m, data["profile"])
                    add(name, label + "live-effective-FSR-config", consistent, **live_metrics)
                add(name, label + "root-world-returned", m["rootReturnRenderState"]["portalRendering"] is False
                    and m["rootReturnRenderState"]["portalLayer"] == 0
                    and m["rootReturnRenderState"]["worldRenderInfoActive"] is False)
                add(name, label + "screenshot-state-restored", m["glBeforeScreenshot"] == m["glAfterScreenshot"])
                observed = [v for v in m["views"] if v["portalRendering"] and not v["irisShadow"]]
                add(name, label + "actual-portal-view-count", m["portalViewObserved"] == bool(observed)
                    and m["portalViewCount"] >= len(observed) and (bool(observed) if scene in PORTAL_SCENES else not observed))
                scene_geometry = geometry(m, data["profile"])  # Required fresh independent geometry.
                if scene in PORTAL_SCENES:
                    translation = np.asarray(m["fixtureGeometry"]["translation"]) * (-1 if camera(m["rootView"])[0] > 24 else 1)
                    candidates = [v for v in observed if np.linalg.norm(camera(v) - camera(m["rootView"]) - translation) < LIMITS["cameraAtol"]]
                    add(name, label + "unique-real-child-view", len(candidates) == 1)
                    if len(candidates) == 1:
                        ok, metrics = root_child_match(m["rootView"], candidates[0], translation)
                        add(name, label + "pure-translation-child-matrices", ok, **metrics)
                if scene in FIXED:
                    g = scene_geometry
                    ok, metrics = stripe_evidence(pixels, g)
                    if name != "sky": add(name, label + "visible-independent-behind-stripes", ok, **metrics)
                    if name != "sky":
                        ok, metrics = pillar_evidence(pixels, g["childPillar"])
                        add(name, label + "real-pillar-at-projected-location", ok, **metrics)
                if scene == "05-through-after":
                    ok, metrics = pillar_evidence(pixels, scene_geometry["directPillar"])
                    if name != "sky": add(name, label + "arrival-direct-pillar", ok, **metrics)
                if scene in ("05-through-after", "07-return-after"):
                    pos = m["serverPlayer"]["position"]
                    correct = (29 < pos[0] < 36 and pos[2] > 2.8) if scene.startswith("05") else (-3 < pos[0] < 4 and pos[2] < 1.2)
                    add(name, label + "numeric-server-side", correct and m["serverPlayer"]["dimension"] == "minecraft:overworld")
                if scene == "08-clean":
                    add(name, label + "portals-really-removed", not m["portalCreation"]["forwardCreated"]
                        and not m["portalCreation"]["reverseCreated"] and not observed)
                if "skyViews" not in m or not isinstance(m["skyViews"], list):
                    raise ValueError("missing new skyViews array: " + scene)
                frames = m["skyViews"]
                if name == "base":
                    add(name, label + "baseline-no-main-renderer", not frames)
                    continue
                add(name, label + "root-transaction-recorded", bool(frames))
                ids = [f["viewId"] for f in frames]
                transactions = {f["rootTransaction"] for f in frames}
                add(name, label + "unique-views-same-root-transaction", len(ids) == len(set(ids)) and len(transactions) == 1
                    and all(v is not None and not isinstance(v, bool) and bool(str(v)) for v in ids + list(transactions)),
                    viewIds=ids, rootTransactions=list(transactions))
                add(name, label + "one-root-frame", sum(bool(f["root"]) and not f["shadow"] for f in frames) == 1)
                for f in frames:
                    if not FRAME_KEYS <= f.keys(): raise ValueError("missing Frame keys: " + str(sorted(FRAME_KEYS - f.keys())))
                    matrix(f["projection"]); matrix(f["view"]); camera(f)
                    for key in ("rasterScale", "rasterJitter"):
                        vector = np.asarray(f[key], dtype=float)
                        if vector.shape != (2,) or not np.isfinite(vector).all() or (key == "rasterScale" and np.any(vector <= 0)):
                            raise ValueError("invalid Frame vector: " + key)
                    if any(type(f[k]) is not int or f[k] < 0 for k in ("blocks", "vertices", "finalResolveCount")):
                        raise ValueError("unknown/negative Frame counts")
                    if any(type(f[k]) is not bool for k in ("root", "portal", "shadow", "drawn", "irisFinalHook", "finalResolved")):
                        raise ValueError("unknown Frame boolean flags")
                    add(name, label + "view-dimension:" + str(f["viewId"]), f["dimension"] == m["rootView"]["dimension"] and not f["bridgeFailure"],
                        blocks=f["blocks"], vertices=f["vertices"], itrpMaterialWrites=f["itrpMaterialWrites"],
                        itrpBackgroundInjected=f["itrpBackgroundInjected"], itrpInjectionCount=f["itrpInjectionCount"], bridgeFailure=f["bridgeFailure"])
                    has_geometry = f["vertices"] > 0 and f["drawn"] and not f["shadow"]
                    if data["iris"] and has_geometry:
                        add(name, label + "one-iris-resolve:" + str(f["viewId"]), f["finalResolved"] is True
                            and f["irisFinalHook"] is True and f["finalResolveCount"] == 1,
                            finalResolved=f["finalResolved"], finalResolveCount=f["finalResolveCount"], irisFinalHook=f["irisFinalHook"])
                    if name == "empty" or scene == "08-clean":
                        add(name, label + "no-sky-geometry:" + str(f["viewId"]), f["blocks"] == f["vertices"] == 0)
                    if f["shadow"]: continue
                    g = scene_geometry
                    if f["root"] and not f["portal"]:
                        expected_view, region, pillar_region = g["rootWorld"], g["directSky"], g["directPillar"]
                    elif f["portal"] and np.linalg.norm(camera(f) - g["childOrigin"]) <= LIMITS["cameraAtol"]:
                        expected_view, region, pillar_region = g["childWorld"], g["childSky"], g["childPillar"]
                    else: raise ValueError("unknown view origin/type; cannot invent an ROI")
                    aligned, metrics = root_child_match(expected_view, f, np.zeros(3))
                    add(name, label + "independent-actual-world-grid:" + str(f["viewId"]), aligned, **metrics)
                    if not aligned: continue
                    required_images = bool(f["root"] or has_geometry)
                    available = bool(f["preViewImage"] and f["postViewImage"])
                    if required_images:
                        add(name, label + "same-frame-images-present:" + str(f["viewId"]), available)
                    if available:
                        add(name, label + "same-frame-image-stage:" + str(f["viewId"]), f["imageStage"] == ("iris-final" if data["iris"] else "opaque"))
                        pre = rgb(safe_child(data["root"], f["preViewImage"]))
                        post = rgb(safe_child(data["root"], f["postViewImage"]))
                        actual_region = region if name == "sky" and scene != "08-clean" and has_geometry else np.zeros_like(region)
                        reference = None
                        if name == "sky" and has_geometry:
                            reference_key = "referenceDisplay" if data["iris"] else "referenceWorld"
                            canvas = rgb(safe_child(data["root"], f[reference_key]))
                            add(name, label + "own-canvas-full-output-size:" + str(f["viewId"]), canvas.shape == pixels.shape,
                                referenceField=reference_key, referenceFile=f[reference_key])
                            if int(erode(actual_region).sum()) >= LIMITS["minPixels"]: reference = canvas
                        for result in view_image_checks(pre, post, actual_region, pillar_region, reference):
                            add(name, label + result.pop("name") + ":" + str(f["viewId"]), result.pop("passed"), **result)
        except (OSError, ValueError, KeyError, TypeError, IndexError, zipfile.BadZipFile) as error:
            add(name, "complete-readable-variant-evidence", False, error=str(error))
    if len(groups) == 3:
        try:
            base, blank, full = (groups[k] for k in ("base", "empty", "sky"))
            signatures = []
            for data in (base, blank, full):
                manifest = data["manifest"]
                signatures.append(dict(harness=manifest["harnessSha256"], shader=manifest["shader"],
                    shaderHash=manifest["shaderSha256"], settings=data["settingsHash"],
                    manifestSettings=data["settingsEvidence"]["manifestSemanticSha256"],
                    irisProperties=data["irisProperties"],
                    mods={k: v["sha256"] for k, v in data["actualMods"].items() if k != "skyboxblock"},
                    portalConfig=manifest["portalConfig"]))
            add("identity", "same-harness-pack-settings-and-supporting-mods", signatures[0] == signatures[1] == signatures[2], signatures=signatures)
            add("identity", "same-main-mod-jar-in-empty-and-sky", bool(blank["manifest"]["jarSha256"])
                and blank["manifest"]["jarSha256"] == full["manifest"]["jarSha256"])
            outside_scenes = 0
            for scene in SCENES:
                bm, em, sm = (data["metadata"][scene] for data in (base, blank, full))
                label = scene + ":"
                if scene in FIXED or scene in ("00-no-portal", "08-clean"):
                    pose_ok = all(np.allclose(camera(bm["rootView"]), camera(m["rootView"]), atol=LIMITS["cameraAtol"], rtol=0)
                        and np.allclose(matrix(bm["rootView"]["projection"]), matrix(m["rootView"]["projection"]), atol=LIMITS["matrixAtol"], rtol=0)
                        and np.allclose(matrix(bm["rootView"]["view"]), matrix(m["rootView"]["view"]), atol=LIMITS["matrixAtol"], rtol=0) for m in (em, sm))
                    add("pairs", label + "fixed-real-pose", pose_ok)
                    if not pose_ok: continue
                    roi = np.ones(base["pictures"][scene].shape[:2], dtype=bool)
                    ok, metrics = compare_images(base["pictures"][scene], blank["pictures"][scene], roi)
                    add("empty", label + "ordinary-world-matches-no-mod", ok, **metrics)
                g = geometry(sm, full["profile"])
                observations[scene] = {k + "Pixels": int(g[k].sum()) for k in ("portal", "childSky", "directSky", "pillar")}
                if scene in FIXED or scene in ("00-no-portal", "08-clean"):
                    outside = ~dilate(g["sky"], LIMITS["margin"])
                    _, metrics = compare_images(full["pictures"][scene], blank["pictures"][scene], outside)
                    observations[scene]["crossRunSkyEmptyColorObservation"] = metrics
                    observations[scene]["crossRunColorEnforced"] = False
                    roots = [f for f in sm["skyViews"] if f["root"] and not f["shadow"]]
                    add("sky", label + "final-root-post-reference-present", len(roots) == 1 and bool(roots[0]["postViewImage"]))
                    outer = ~dilate(g["portal"], LIMITS["margin"])
                    if int(outer.sum()) >= LIMITS["minPixels"] and len(roots) == 1 and roots[0]["postViewImage"]:
                        outside_scenes += 1
                        root_post = rgb(safe_child(full["root"], roots[0]["postViewImage"]))
                        if root_post.shape != full["pictures"][scene].shape: raise ValueError("root post/final output size mismatch")
                        delta = np.abs(full["pictures"][scene].astype(int) - root_post.astype(int))[outer]
                        maximum = int(delta.max())
                        add("sky", label + "same-frame-final-outside-portal", maximum <= 1,
                            pixels=int(outer.sum()), maximumRgbError=maximum)
                    else: observations[scene]["outsidePortalCheck"] = "geometrically offscreen or missing required root post image"
                    if scene in FIXED:
                        ok, metrics = pillar_evidence(full["pictures"][scene], g["childPillar"])
                        add("sky", label + "final-independent-pillar-geometry", ok, **metrics)
                elif scene == "05-through-after":
                    # Separate walks may stop at slightly different Z. Do not mechanically compare these images.
                    ok, metrics = pillar_evidence(full["pictures"][scene], g["directPillar"])
                    add("sky", label + "independent-arrival-pillar", ok, **metrics)
                if scene in FIXED or scene == "05-through-after":
                    child = scene in FIXED
                    origin = g["childOrigin"] if child else camera(sm["rootView"])
                    frames = [f for f in sm["skyViews"] if not f["shadow"] and f["portal"] == child
                              and f["root"] == (not child) and f["vertices"] > 0
                              and np.linalg.norm(camera(f) - origin) <= LIMITS["cameraAtol"]]
                    add("sky", label + "corresponding-own-sky-frame", len(frames) == 1)
                    if len(frames) != 1: continue
                    frame = frames[0]
                    expected_view = g["childWorld"] if child else g["rootWorld"]
                    ok, metrics = root_child_match(expected_view, frame, np.zeros(3))
                    add("sky", label + "reference-same-NDC-grid", ok, **metrics)
                    if not ok: continue
                    key = "referenceDisplay" if full["iris"] else "referenceWorld"
                    reference = rgb(safe_child(full["root"], frame[key]))
                    roi = erode(g["childSky"] if child else g["directSky"], LIMITS["margin"])
                    ok, metrics = sky_match(full["pictures"][scene], reference, roi)
                    add("sky", label + "private-sky-reference-matches-actual-portal", ok, referenceField=key, referenceFile=frame[key], **metrics)
            add("sky", "outside-portal-leak-tested-in-several-views", outside_scenes >= 2, measuredScenes=outside_scenes)
        except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
            add("pairs", "complete-comparable-three-way-evidence", False, error=str(error))
    else: add("identity", "all-three-variants-present", False)
    base_checks = [c for c in checks if c["group"] == "base"]
    expected_visual = {scene + suffix for scene in FIXED for suffix in
        (":visible-independent-behind-stripes", ":real-pillar-at-projected-location")} | {"05-through-after:arrival-direct-pillar"}
    base_visual = [c for c in base_checks if c["name"] in expected_visual]
    evidence_complete = bool(base_checks) and not any(c["name"] == "complete-readable-variant-evidence" and not c["passed"] for c in base_checks)
    config_names = {"actual-pack-hash", "actual-shader-selection", "manifest-actual-effective-settings",
                    "actual-harness-hash", "genuine-primary-mod-presence", "actual-run-success", "nine-real-captures", "explicit-no-shader"}
    config_checks = [c for c in base_checks if c["name"] in config_names or c["name"].endswith(":live-effective-FSR-config")]
    config_ready = evidence_complete and bool(config_checks) and all(c["passed"] for c in config_checks)
    visual_ready = config_ready and {c["name"] for c in base_visual} == expected_visual
    visual_passed = bool(all(c["passed"] for c in base_visual)) if visual_ready else None
    passed = bool(checks) and all(c["passed"] for c in checks)
    return dict(schemaVersion=1, passed=passed, baseCombinationPassed=bool(base_checks) and all(c["passed"] for c in base_checks),
                baseEvidenceComplete=evidence_complete, baseConfigEvidencePassed=config_ready,
                baseVisualEvidenceComplete=visual_ready, baseVisualPassed=visual_passed,
                configurationEvidence={name: data.get("settingsEvidence") for name, data in groups.items()},
                thresholds=LIMITS, scenes=observations, checks=checks, passedChecks=sum(c["passed"] for c in checks), totalChecks=len(checks),
                scope="Same-dimension, pure-translation portal and actual outward/return walks; no rotation/scaling/nesting claim.",
                interpretation="Config/protocol failure is not a visual verdict: baseVisualPassed is null until trusted image evidence is complete. Actual baseline image failure remains a base-combination failure. Moving views use own geometry/reference, never forced cross-run pixel equality.")


def fresh(path):
    if not path.exists(): return path
    for i in itertools.count(2):
        candidate = path.with_name(f"{path.stem}-r{i}{path.suffix}")
        if not candidate.exists(): return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--empty", type=Path, required=True)
    parser.add_argument("--sky", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = check(args.baseline, args.empty, args.sky)
    if args.output:
        path = fresh(args.output); path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, indent=2, allow_nan=False); stream.write("\n")
        print("report:", path)
    print("PASS" if result["passed"] else "FAIL", f"{result['passedChecks']}/{result['totalChecks']}",
          "baseCombinationPassed=", result["baseCombinationPassed"])
    for item in result["checks"]:
        if not item["passed"]: print("FAIL", json.dumps(item))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
