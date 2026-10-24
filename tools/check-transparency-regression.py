#!/usr/bin/env python3
"""Independent transparency oracle (NumPy/Pillow only).

CLI: python -B tools/check-transparency-regression.py RESULTS --output REPORT.json
Each environment requires the 24 sky-present combinations and matching no-sky
behind=false/true references, plus one no-sky role='removed' repeat per front.
ROIs come from camera rays and declared world AABBs, NEVER sky/change masks.
Thresholds are explicit provisional values; retain first failures for calibration.
transparency.markerBoxes declares individual potential objects in BOTH B0/B1.
Legacy images need an explicit verified geometry override (see real-image selftest).
Signed/energy multiscale detection requires two geometry-aligned bands at one shift;
overall MAE and global affine RGB/lighting changes alone never label leakage.
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from dataclasses import dataclass, field
import itertools
import json
from pathlib import Path

import numpy as np
from PIL import Image

FRONTS = ("none", "glass", "water")
PROTECTIONS = ("none", "pillar", "hand", "both")
_protocol_spec = importlib.util.spec_from_file_location("skybox_settled_protocol", Path(__file__).with_name("settled-transparency-protocol.py"))
S = importlib.util.module_from_spec(_protocol_spec)
sys.modules[_protocol_spec.name] = S
_protocol_spec.loader.exec_module(S)
DEFAULTS = dict(min_pixels=64, margin_active=3, max_hand_fraction=.20,
                marker_reference_rms=3., marker_correlation=.35,
                marker_gain=.08, marker_residual_rms=1.5, marker_shift_active=6,
                material_mae=2.5, material_changed_fraction=.20,
                preserve_mae=1., preserve_p99=3., sky_max_error=2.,
                restore_mae=1., restore_p99=5.,
                shape_correlation=.75, shape_gain=.15, shape_residual_rms=2.,
                shape_reference_rms=3., shape_geometry_correlation=.60, shape_min_bands=2)


@dataclass
class Scene:
    name: str
    meta: dict
    images: dict = field(default_factory=dict)
    errors: list = field(default_factory=list)

    @property
    def t(self):
        return self.meta["transparency"]

    @property
    def key(self):
        return self.t["front"], self.t["protection"], self.t["behind"], self.t["skyPresent"]


def expected_fixture_geometry(t):
    pattern=t.get("markerPattern")
    if pattern not in ("solid","striped"): raise ValueError("Unknown fixed physical marker pattern")
    behind=t["behind"]
    expected=dict(existingChunks=4,markerBlack=(16 if pattern=="striped" else 32) if behind else 0,
                markerWhite=16 if behind and pattern=="striped" else 0,
                sky=88 if t["skyPresent"] else 0,skyEntities=88 if t["skyPresent"] else 0,
                highSky=0,highSkyEntities=0,glass=40 if t["front"]=="glass" else 0,
                water=40 if t["front"]=="water" else 0,pillar=7 if t["protection"] in ("pillar","both") else 0,floor=357)
    if t.get("fixtureGeometryVersion",1)>=3: expected["waterOutside"]=0
    return expected


def rgb(path):
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB")).copy()


def resize(array, shape):
    h, w = shape
    if array.shape[:2] == shape:
        return array
    return np.asarray(Image.fromarray(array).resize((w, h), Image.Resampling.NEAREST))


def erode(mask, count=1):
    for _ in range(count):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1, 1:-1] & p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:]
    return mask


def dilate(mask, count=1):
    for _ in range(count):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1, 1:-1] | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
    return mask


def rays(meta, width, height, display=True):
    x, y = np.meshgrid(2 * (np.arange(width) + .5) / width - 1,
                       1 - 2 * (np.arange(height) + .5) / height)
    key = "inverseDisplayViewProjection" if display else "inverseViewProjection"
    inv = np.asarray(meta[key], dtype=float).reshape(4, 4).T
    points = []
    for z in (-1., 0.):
        clip = np.stack((x, y, np.full_like(x, z), np.ones_like(x)), axis=-1)
        world = clip @ inv.T
        points.append(world[..., :3] / world[..., 3, None])
    direction = points[1] - points[0]
    return direction / np.linalg.norm(direction, axis=-1, keepdims=True)


def distance(origin, direction, bounds):
    if bounds is None:
        return np.full(direction.shape[:2], np.inf)
    lo, hi = np.asarray(bounds, dtype=float)
    if lo.shape != (3,) or not np.all(np.isfinite([lo, hi])) or np.any(hi <= lo):
        raise ValueError("AABB requires finite, strictly ordered world coordinates")
    origin = np.asarray(origin, dtype=float)
    parallel = np.abs(direction) < 1e-12
    safe = np.where(parallel, 1., direction)
    a, b = (lo - origin) / safe, (hi - origin) / safe
    entry, leave = np.minimum(a, b), np.maximum(a, b)
    outside = parallel & ((origin < lo) | (origin > hi))
    entry = np.where(parallel, -np.inf, entry)
    leave = np.where(parallel, np.inf, leave)
    near, far = entry.max(axis=-1), leave.min(axis=-1)
    hit = ~outside.any(axis=-1) & (far >= np.maximum(near, .01))
    return np.where(hit, np.maximum(near, 0.), np.inf)


def geometry(scene, shape, display=True):
    h, w = shape
    d = rays(scene.meta, w, h, display)
    t, origin = scene.t, scene.meta["camera"]
    sky = distance(origin, d, t["skyBounds"])
    front = distance(origin, d, t.get("frontBounds"))
    behind = distance(origin, d, t["behindBounds"])
    pillar = distance(origin, d, t.get("pillarBounds"))
    visible = np.isfinite(sky) & (sky < pillar)
    return dict(sky=visible, transparent=visible & (front < sky),
                marker=np.isfinite(behind) & (behind > sky),
                pillar=np.isfinite(pillar) & (pillar < sky))


def marker_geometry(scene, shape):
    """Declared per-object boxes, not the whole enclosing behindBounds rectangle."""
    boxes = scene.t.get("markerBoxes")
    if not isinstance(boxes, list) or not boxes:
        raise ValueError("missing independent markerBoxes; legacy images require an explicit verified geometry override")
    low, high = np.asarray(scene.t["behindBounds"], dtype=float)
    d = rays(scene.meta, shape[1], shape[0])
    mask = np.zeros(shape, dtype=bool)
    for bounds in boxes:
        lo, hi = np.asarray(bounds, dtype=float)
        if lo.shape != (3,) or np.any(lo < low - 1e-6) or np.any(hi > high + 1e-6):
            raise ValueError("markerBoxes escape the declared behindBounds")
        mask |= np.isfinite(distance(scene.meta["camera"], d, bounds))
    return mask


def map_active_mask(mask, meta, shape):
    """The production WorldDepth transform, applied to a top-down ACTIVE crop."""
    h, w = shape
    ah, aw = mask.shape
    tw, th = meta["targetViewport"][2:]
    sx, sy = meta["rasterScale"]
    jx, jy = meta["rasterJitter"]
    ix = np.floor((np.arange(w) + .5) / w * sx * tw + jx * .5 * tw).astype(int)
    iy_gl = np.floor((1 - (np.arange(h) + .5) / h) * sy * th + jy * .5 * th).astype(int)
    iy = ah - 1 - iy_gl
    return mask[np.clip(iy, 0, ah - 1)[:, None], np.clip(ix, 0, aw - 1)[None, :]]


def pixel_metrics(a, b, mask):
    count = int(mask.sum())
    if not count:
        return dict(pixels=0, mae=None, p99=None, maximum=None, changedFraction=None)
    delta = np.abs(a.astype(float) - b.astype(float))[mask]
    return dict(pixels=count, mae=float(delta.mean()), p99=float(np.percentile(delta, 99)),
                maximum=float(delta.max()), changedFraction=float((delta.max(axis=-1) > 3).mean()))


def material_metrics(material, bare, roi, limits):
    m = pixel_metrics(material, bare, roi)
    passed = (m["pixels"] >= limits["min_pixels"] and m["mae"] >= limits["material_mae"]
              and m["changedFraction"] >= limits["material_changed_fraction"])
    return bool(passed), m


def preserve_metrics(final, pre, roi, limits):
    m = pixel_metrics(final, pre, roi)
    passed = (m["pixels"] >= limits["min_pixels"] and m["mae"] <= limits["preserve_mae"]
              and m["p99"] <= limits["preserve_p99"])
    return bool(passed), m


def highpass(array):
    a = array.astype(float)
    p = np.pad(a, ((2, 2), (2, 2), (0, 0)), mode="edge")
    average = sum(p[y:y + a.shape[0], x:x + a.shape[1]] for y in range(5) for x in range(5)) / 25
    return a - average


def box_average(array, radius):
    """Exact separable box average via integral image; no optional SciPy/OpenCV."""
    scalar = array.ndim == 2
    a = array[..., None] if scalar else array
    p = np.pad(a.astype(float), ((radius, radius), (radius, radius), (0, 0)), mode="edge")
    integral = np.pad(p, ((1, 0), (1, 0), (0, 0))).cumsum(0).cumsum(1)
    n = 2 * radius + 1
    result = (integral[n:, n:] - integral[:-n, n:] - integral[n:, :-n] + integral[:-n, :-n]) / (n * n)
    return result[..., 0] if scalar else result


def remove_affine(array, mask):
    # Remove only global additive RGB and linear screen-space trends, never a marker-fit term.
    y, x = np.indices(mask.shape, dtype=float)
    coordinates = np.stack((np.ones_like(x), x / max(1, mask.shape[1]-1) - .5,
                            y / max(1, mask.shape[0]-1) - .5), axis=-1)
    coefficients = np.linalg.lstsq(coordinates[mask], array[mask], rcond=None)[0]
    return array - coordinates @ coefficients


def _search_band(template, residual, anchor, mask, shifts, limits, family, stride=1):
    legacy = family == "legacy-highpass"
    if template.ndim == 2: template, residual = template[..., None], residual[..., None]
    result = dict(family=family, sampleStride=stride, maxAbsCorrelation=0., matchedAbsCorrelation=0.,
                  matchedGain=0., referenceRms=0., residualRms=0., geometryReferenceCorrelation=0.,
                  geometryResidualCorrelation=0., matchedShift=[0, 0], matchedChannel=0,
                  sampledPixels=0, leakDetected=False, referenceGeometryObservable=False)
    thresholds = ((limits["marker_correlation"], limits["marker_gain"], limits["marker_residual_rms"],
                   limits["marker_reference_rms"]) if legacy else
                  (limits["shape_correlation"], limits["shape_gain"], limits["shape_residual_rms"], limits["shape_reference_rms"]))
    corr_min, gain_min, rms_min, ref_min = thresholds
    h, w = mask.shape
    hits, best = set(), -1.
    for dy, dx in itertools.product(shifts, repeat=2):
        # Slices rather than roll: reference and geometry move together; no wrapped pixels enter ROI.
        ry, rx = slice(max(0, dy), min(h, h+dy), stride), slice(max(0, dx), min(w, w+dx), stride)
        ty, tx = slice(max(0, -dy), min(h, h-dy), stride), slice(max(0, -dx), min(w, w-dx), stride)
        valid = mask[ry, rx]
        count = int(valid.sum())
        if count < limits["min_pixels"]: continue
        a, b, g = template[ty, tx][valid], residual[ry, rx][valid], anchor[ty, tx][valid]
        if not legacy: a, b = a - a.mean(0), b - b.mean(0)
        centered_a, centered_b, g = a - a.mean(0), b - b.mean(0), g - g.mean()
        aa, bb, cross = np.mean(a*a, 0), np.mean(b*b, 0), np.mean(a*b, 0)
        rho = np.abs(cross) / np.maximum(np.sqrt(aa*bb), 1e-12)
        gain, reference_rms, residual_rms = np.abs(cross) / np.maximum(aa, 1e-12), np.sqrt(aa), np.sqrt(bb)
        gg = float(np.mean(g*g))
        geo_t = np.abs(np.mean(centered_a*g[:, None], 0)) / np.maximum(np.sqrt(np.mean(centered_a**2, 0)*gg), 1e-12)
        geo_r = np.abs(np.mean(centered_b*g[:, None], 0)) / np.maximum(np.sqrt(np.mean(centered_b**2, 0)*gg), 1e-12)
        for channel in range(len(rho)):
            if reference_rms[channel] < ref_min: continue
            result["maxAbsCorrelation"] = max(result["maxAbsCorrelation"], float(rho[channel]))
            observable = legacy or geo_t[channel] >= limits["shape_geometry_correlation"]
            result["referenceGeometryObservable"] |= bool(observable)
            hit = (rho[channel] >= corr_min and gain[channel] >= gain_min and residual_rms[channel] >= rms_min
                   and observable and (legacy or geo_r[channel] >= limits["shape_geometry_correlation"]))
            if hit: hits.add((dx, dy))
            score = min(float(rho[channel]/corr_min), float(gain[channel]/gain_min), float(residual_rms[channel]/rms_min))
            if not legacy: score = min(score, float(geo_t[channel]/limits["shape_geometry_correlation"]),
                                      float(geo_r[channel]/limits["shape_geometry_correlation"]))
            prefer_zero = abs(score-best) < 1e-12 and abs(dx)+abs(dy) < sum(abs(v) for v in result["matchedShift"])
            if score > best + 1e-12 or prefer_zero:
                best = score
                result.update(matchedAbsCorrelation=float(rho[channel]), matchedGain=float(gain[channel]),
                              referenceRms=float(reference_rms[channel]), residualRms=float(residual_rms[channel]),
                              geometryReferenceCorrelation=float(geo_t[channel]), geometryResidualCorrelation=float(geo_r[channel]),
                              matchedShift=[dx, dy], matchedChannel=channel, sampledPixels=count)
    result["leakDetected"] = bool(hits)
    return result, hits


def marker_metrics(on, off, visible_on, visible_off, roi, limits, scale=1., geometry=None,
                   geometry_source="declared-markerBoxes"):
    """Legacy HF plus affine-nuisance-removed multi-scale signed/energy shape matches.

    A shape hit needs two bands of the SAME family at the SAME searched displacement,
    each aligned with independently projected marker boxes AND the actual no-sky AB
    template. Overall MAE never labels leakage. Missing/weak references fail closed.
    """
    metrics = pixel_metrics(on, off, roi)
    metrics.update(maxAbsCorrelation=0., matchedAbsCorrelation=0., matchedGain=0., matchedResidualRms=0.,
                   referenceFeatureRms=0., matchedShift=[0, 0], matchedChannel=0, leakDetected=False,
                   legacyLeakDetected=False, multiscaleLeakDetected=False, evidenceValid=False,
                   legacyEvidenceValid=False, multiScaleEvidenceValid=False,
                   geometrySource=geometry_source, bands=[], coherentMatches=[])
    if metrics["pixels"] < limits["min_pixels"]:
        metrics["evidenceError"] = "too-few-independent-ROI-samples"; return False, metrics
    if geometry is None or geometry.shape != roi.shape:
        metrics["evidenceError"] = "missing-independent-marker-geometry"; return False, metrics
    inside, outside = int((roi & geometry).sum()), int((roi & ~geometry).sum())
    metrics.update(geometryInsideSamples=inside, geometryOutsideSamples=outside)
    if min(inside, outside) < limits["min_pixels"]:
        metrics["evidenceError"] = "too-few-marker-or-gap-samples"; return False, metrics
    radius = int(np.ceil(limits["marker_shift_active"] / max(scale, .01)))
    step = max(1, radius // 3)
    shifts = sorted(set(range(-radius, radius + 1, step)) | {0, radius})
    active_bands = ((4, 16), (8, 32), (16, 64))
    band_radii = [tuple(max(1, int(np.ceil(r / max(scale, .01)))) for r in band) for band in active_bands]
    border = radius + max(r[1] for r in band_radii) + 3
    ys, xs = np.nonzero(roi)
    crop = np.s_[max(0, ys.min()-border):min(len(roi), ys.max()+border+1),
                 max(0, xs.min()-border):min(roi.shape[1], xs.max()+border+1)]
    t = visible_on[crop].astype(float) - visible_off[crop].astype(float)
    r = on[crop].astype(float) - off[crop].astype(float)
    mask, anchor = roi[crop], geometry[crop].astype(float)
    ht, hr = highpass(t), highpass(r)
    metrics["referenceFeatureRms"] = float(np.sqrt(np.mean(ht[mask]**2)))
    metrics["legacyEvidenceValid"] = metrics["referenceFeatureRms"] >= limits["marker_reference_rms"]
    if metrics["legacyEvidenceValid"]:
        old, old_hits = _search_band(ht, hr, highpass(np.repeat(anchor[..., None], 3, -1))[..., 0], mask, shifts, limits, "legacy-highpass")
    else:
        old_hits = set()
        old = dict(family="legacy-highpass", sampleStride=1, maxAbsCorrelation=0., matchedAbsCorrelation=0.,
                   matchedGain=0., referenceRms=metrics["referenceFeatureRms"], residualRms=float(np.sqrt(np.mean(hr[mask]**2))),
                   geometryReferenceCorrelation=0., geometryResidualCorrelation=0., matchedShift=[0,0], matchedChannel=0,
                   sampledPixels=int(mask.sum()), leakDetected=False, referenceGeometryObservable=False,
                   skipped="reference RMS below legacy threshold; lower-frequency bands still evaluated")
    old.update(activeRadii=None, displayRadii=[0, 2], evidenceValid=metrics["legacyEvidenceValid"])
    metrics["bands"].append(old)
    metrics.update(maxAbsCorrelation=old["maxAbsCorrelation"], matchedAbsCorrelation=old["matchedAbsCorrelation"],
                   matchedGain=old["matchedGain"], matchedResidualRms=old["residualRms"],
                   matchedShift=old["matchedShift"], matchedChannel=old["matchedChannel"], legacyLeakDetected=bool(old_hits))
    t, r = remove_affine(t, mask), remove_affine(r, mask)
    energy_t, energy_r = np.sqrt(np.mean(t*t, -1)), np.sqrt(np.mean(r*r, -1))
    stride = max(1, int(round(2 / max(scale, .01))))
    support, references = {}, {"signed-shape": 0, "energy-envelope": 0}
    for active, (small, large) in zip(active_bands, band_radii):
        gb = box_average(anchor, small) - box_average(anchor, large)
        for family, a, b in (("signed-shape", t, r), ("energy-envelope", energy_t, energy_r)):
            tb, rb = box_average(a, small)-box_average(a, large), box_average(b, small)-box_average(b, large)
            band, hits = _search_band(tb, rb, gb, mask, shifts, limits, family, stride)
            band.update(activeRadii=list(active), displayRadii=[small, large])
            metrics["bands"].append(band)
            references[family] += int(band["referenceGeometryObservable"])
            for position in hits: support.setdefault((family, position), []).append(list(active))
    for (family, position), supported in sorted(support.items()):
        if len(supported) >= limits["shape_min_bands"]:
            metrics["coherentMatches"].append(dict(family=family, shift=list(position), activeBands=supported))
    metrics["shiftRadiusDisplayPixels"] = radius
    metrics["referenceShapeBands"] = references
    metrics["multiscaleLeakDetected"] = bool(metrics["coherentMatches"])
    metrics["leakDetected"] = bool(old_hits) or metrics["multiscaleLeakDetected"]
    metrics["multiScaleEvidenceValid"] = max(references.values()) >= limits["shape_min_bands"]
    metrics["evidenceValid"] = metrics["legacyEvidenceValid"] or metrics["multiScaleEvidenceValid"]
    if not metrics["evidenceValid"]: metrics["evidenceError"] = "native-reference-weak-in-all-tested-frequency-families"
    return bool(metrics["evidenceValid"] and not metrics["leakDetected"]), metrics


def matching(a, b):
    # Jitter may differ; geometry/projection and world-space fixture must not.
    keys = ("camera", "inverseDisplayViewProjection", "width", "height")
    if not all(np.allclose(a.meta[k], b.meta[k], rtol=0, atol=2e-5) for k in keys):
        return False
    return all(a.t.get(k) == b.t.get(k) for k in ("skyBounds", "behindBounds", "pillarBounds", "frontBounds", "markerBoxes", "markerMaterial"))


def load(directory):
    scenes = []
    for path in sorted(directory.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        named_transparency=path.stem.startswith("trans-")
        if not isinstance(data,dict):
            if not named_transparency: continue
            data={"transparency":None}
        if not named_transparency and not isinstance(data.get("transparency"),dict):
            continue
        if "transparency" not in data:
            data=dict(data,transparency=None)
        scene = Scene(path.stem, data)
        suffixes = dict(final="", before="-before", opaque="-opaque", depth="-depth-change",
                        pre="-pre-resolve", hand="-post-hand-mask", foreground="-post-opaque-foreground")
        for key, suffix in suffixes.items():
            p = directory / (path.stem + suffix + ".png")
            if p.exists():
                scene.images[key] = rgb(p)
        for suffix in ("-scene-display-clouds", "-scene-clouds"):
            p = directory / (path.stem + suffix + ".png")
            if p.exists():
                scene.images["reference"] = rgb(p)
                break
        scenes.append(scene)
    return scenes


def evaluate(scenes, limits=None):
    limits = dict(DEFAULTS, **(limits or {}))
    report = dict(schemaVersion=1, markerMetricVersion=2, thresholds=limits, checks=[], scenes={},
                  shapeRule=dict(activeBands=[[4,16],[8,32],[16,64]], families=["signed-shape","energy-envelope"],
                                 coherentDisplacement=True, affineRgbNuisanceRemoved=True,
                                 independentGeometry="transparency.markerBoxes", legacyHighpassRmsThresholdPreserved=True,
                                 referenceValidity="legacyEvidenceValid OR multiScaleEvidenceValid"),
                  caveats=["Provisional image thresholds require isolated live-fixture calibration.",
                           "Shape matches identify geometry-correlated marker influence, not arbitrary MAE; exact aligned reflections/GI require controlled producer diagnosis.",
                           "State evidence: GL errors, inactive writes, Iris logical equality; full GL snapshots checked only if supplied."])

    def add(name, passed, **metrics):
        report["checks"].append(dict(name=name, passed=bool(passed), **metrics))

    add("transparency-captures-present", bool(scenes), count=len(scenes))
    observed_world_times=[s.t.get("actualServerGameTime") for s in scenes if isinstance(s.t,dict) and s.t.get("fixtureGeometryVersion",1)>=3]
    if observed_world_times:
        add("owned-fluid-fixture-world-clock-advances",all(type(v) is int for v in observed_world_times)
            and max(observed_world_times)>min(observed_world_times))
    baseline, removed, masks = {}, [], {}
    for scene in scenes:
        name, m, t, images = scene.name, scene.meta, scene.t, scene.images
        try:
            valid = (t["front"] in FRONTS and t["protection"] in PROTECTIONS
                     and type(t["behind"]) is bool and type(t["skyPresent"]) is bool
                     and t.get("role", "baseline") in ("baseline", "removed")
                     and t.get("skyBounds") is not None and t.get("behindBounds") is not None
                     and (t["front"] == "none") == (t.get("frontBounds") is None)
                     and (t["protection"] in ("pillar", "both")) == (t.get("pillarBounds") is not None))
            add(name + ":metadata-valid", valid)
            if not valid: continue
            if t.get("fixtureGeometryVersion",1) >= 3:
                add(name+":only-water-scheduling-is-frozen",t.get("fluidFreezeMode")=="owned-scheduled-water-only")
                add(name+":live-world-clock-observed",type(t.get("actualServerGameTime")) is int and type(t.get("actualClientGameTime")) is int)
                add(name+":water-control-observed",type(m.get("suppressedFixtureWaterTicks")) is int
                    and (t["front"]!="water" or m["suppressedFixtureWaterTicks"]>0))
            if t.get("fixtureGeometryVersion",1) >= 2:
                expected=expected_fixture_geometry(t)
                for side in ("Server","Client"):
                    observed=t.get("actual"+side+"Geometry")
                    add(name + ":actual-"+side.lower()+"-fixture-geometry",isinstance(observed,dict) and observed==expected,
                        expected=expected,actual=observed)
            if t.get("role", "baseline") == "removed":
                removed.append(scene)
            else:
                add(name + ":unique-pair-key", scene.key not in baseline, key=list(scene.key))
                baseline[scene.key] = scene
            required = {"final", "before", "opaque", "depth"}
            if t["skyPresent"]: required.add("reference")
            iris = m.get("irisMainBoundBefore") is not None
            if iris and t["skyPresent"]: required |= {"pre", "hand", "foreground"}
            missing = sorted(required - images.keys())
            add(name + ":evidence-complete", not missing, missing=missing)
            if missing: continue
            shape = (m["height"], m["width"])
            active = (m["opaqueHeight"], m["opaqueWidth"])
            size_ok = (images["final"].shape[:2] == shape and all(
                images[k].shape[:2] == active for k in ("before", "opaque", "depth")))
            add(name + ":image-sizes", size_ok, display=list(shape[::-1]), active=list(active[::-1]))
            if not size_ok: continue
            if "foreground" in images:
                add(name + ":foreground-mask-active-size", images["foreground"].shape[:2] == active)
            add(name + ":zero-gl-errors", m.get("initialGlError") == m.get("finalGlError") == 0)
            add(name + ":inactive-raster-untouched", m.get("outsideRasterChangedPixels") == 0
                and m.get("outsideRasterDepthChangedPixels") == 0)
            state_keys = ("irisMainBoundBefore", "irisMainBoundAfter", "irisOverridesBefore", "irisOverridesAfter")
            add(name + ":iris-state-restored", all(k in m for k in state_keys)
                and m[state_keys[0]] == m[state_keys[1]] and m[state_keys[2]] == m[state_keys[3]])
            if "glStateBefore" in m or "glStateAfter" in m:
                add(name + ":full-gl-snapshot-restored", "glStateBefore" in m and "glStateAfter" in m
                    and m["glStateBefore"] == m["glStateAfter"])
            add(name + ":resource-reload-complete", m.get("resourceReloadPending") is False)
            expected_extent = np.asarray(m["targetViewport"][2:]) * m["rasterScale"]
            add(name + ":active-raster-scale", np.allclose(expected_extent, active[::-1], atol=.51, rtol=0))
            scale = float(min(m["rasterScale"]))
            margin = max(1, int(np.ceil(limits["margin_active"] / max(scale, .01))))
            g = geometry(scene, shape)
            independent_marker = marker_geometry(scene, shape)
            hand = np.zeros(shape, dtype=bool)
            if "hand" in images:
                if images["hand"].shape[:2] != active:
                    raise ValueError("post-hand mask must be an active-raster crop")
                hand = map_active_mask(images["hand"][..., 0] > 0, m, shape)
            elif "handDisplayBounds" in t:
                x0, y0, x1, y1 = map(int, t["handDisplayBounds"])
                if not (0 <= x0 < x1 <= shape[1] and 0 <= y0 < y1 <= shape[0]):
                    raise ValueError("handDisplayBounds out of display bounds")
                hand[y0:y1, x0:x1] = True
            needs_hand = t["protection"] in ("hand", "both")
            add(name + ":bounded-real-hand-exclusion", hand.mean() <= limits["max_hand_fraction"]
                and (not needs_hand or int(erode(hand).sum()) >= limits["min_pixels"]),
                handPixels=int(hand.sum()), handFraction=float(hand.mean()),
                source=m.get("handMaskEvidence", "legacy-depth-mask") if "hand" in images else "declared-bounds" if "handDisplayBounds" in t else "none")
            safe = erode(g["sky"], margin) & ~dilate(hand, max(1, margin // 2))
            transmission = safe & erode(g["transparent"], margin)
            bare = safe & ~dilate(g["transparent"], margin)
            marker = (transmission if t["front"] != "none" else safe) & dilate(g["marker"], margin + 2)
            masks[name] = dict(safe=safe, transmission=transmission, bare=bare,
                               marker=marker, markerGeometry=independent_marker, hand=hand,
                               pillar=g["pillar"], margin=margin, scale=scale)
            report["scenes"][name] = dict(skyPixels=int(g["sky"].sum()), safePixels=int(safe.sum()),
                transmissionPixels=int(transmission.sum()), markerPixels=int(marker.sum()), handPixels=int(hand.sum()))
            if t["skyPresent"]:
                add(name + ":independent-sky-roi-nonempty", int(safe.sum()) >= limits["min_pixels"], samples=int(safe.sum()))
                if t["front"] != "none":
                    add(name + ":independent-transmission-roi-nonempty", int(transmission.sum()) >= limits["min_pixels"], samples=int(transmission.sum()))
                if t["front"] == "none" or int(bare.sum()) >= limits["min_pixels"]:
                    reference = resize(images["reference"], shape)
                    metrics = pixel_metrics(images["final"], reference, bare)
                    add(name + ":unmasked-independent-sky", metrics["pixels"] >= limits["min_pixels"]
                        and metrics["maximum"] <= limits["sky_max_error"], **metrics)
                low_expected = erode(geometry(scene, active, False)["sky"], 2)
                actual_depth = images["depth"][..., 0] > 0
                count = int(low_expected.sum())
                coverage = float(actual_depth[low_expected].mean()) if count else 0.
                add(name + ":early-independent-depth-coverage", count >= limits["min_pixels"] and coverage >= .98,
                    samples=count, coverage=coverage)
                low_outer = dilate(geometry(scene, active, False)["sky"], 2)
                leaked = int((actual_depth & ~low_outer).sum())
                add(name + ":early-depth-no-occlusion-leak", leaked == 0, leakedPixels=leaked)
            else:
                add(name + ":no-sky-no-private-world-write", int((images["depth"][..., 0] > 0).sum()) == 0
                    and m.get("blocks") == 0 and m.get("depthChangedPixels") == 0
                    and np.array_equal(images["before"], images["opaque"]))
            actual_count = int((images["depth"][..., 0] > 0).sum())
            add(name + ":depth-evidence-count", actual_count == m.get("depthChangedPixels"),
                actual=actual_count, recorded=m.get("depthChangedPixels"))
            if "pre" in images:
                if images["pre"].shape[:2] != shape:
                    raise ValueError("pre-resolve must have final display dimensions")
                changed = np.max(np.abs(images["final"].astype(int) - images["pre"].astype(int)), axis=-1) > 2
                leak = int((changed & ~dilate(g["sky"], margin)).sum())
                add(name + ":resolve-no-world-leak", leak == 0, leakedPixels=leak)
                if not t["skyPresent"]:
                    add(name + ":empty-resolve-unchanged", not changed.any(), changedPixels=int(changed.sum()))
        except (KeyError, ValueError, TypeError, IndexError) as error:
            add(name + ":valid-evidence", False, error=str(error))

    for front, protection, behind in itertools.product(FRONTS, PROTECTIONS, (False, True)):
        key = (front, protection, behind, True)
        add("core-combination:" + str(key), key in baseline, key=list(key))
    for front, protection in itertools.product(FRONTS, PROTECTIONS):
        keys = [(front, protection, b, s) for s, b in itertools.product((True, False), (True, False))]
        pair = [baseline.get(k) for k in keys]
        label = f"pair:{front}:{protection}"
        add(label + ":all-marker-pairs", all(pair), requiredKeys=[list(k) for k in keys])
        if not all(pair) or any(s.name not in masks for s in pair): continue
        on, off, visible_on, visible_off = pair
        compatible = all(matching(on, s) for s in pair[1:])
        add(label + ":fixed-camera-and-bounds", compatible)
        if not compatible: continue
        roi = np.logical_and.reduce([masks[s.name]["marker"] for s in pair])
        anchor = np.logical_and.reduce([masks[s.name]["markerGeometry"] for s in pair])
        passed, metrics = marker_metrics(*(s.images["final"] for s in pair), roi, limits, masks[on.name]["scale"],
            geometry=anchor, geometry_source=on.t.get("markerGeometryEvidence", "declared-markerBoxes"))
        add(label + ":no-transmitted-marker", passed, **metrics)
        for scene in (on, off):
            mask = masks[scene.name]
            ref = baseline.get((front, protection, scene.t["behind"], False))
            pre = scene.images.get("pre", ref.images["final"] if ref else None)
            evidence = "same-frame-pre-resolve" if "pre" in scene.images else "no-sky-reference-weaker"
            for kind in ("pillar", "hand"):
                needed = protection == kind or protection == "both"
                if not needed: continue
                region = erode(mask[kind], mask["margin"] if kind == "pillar" else 1)
                if pre is None:
                    add(scene.name + ":preserve-" + kind, False, error="missing preservation reference")
                else:
                    ok, metrics = preserve_metrics(scene.images["final"], pre, region, limits)
                    add(scene.name + ":preserve-" + kind, ok, evidence=evidence, **metrics)
            if front != "none":
                bare_scene = baseline.get(("none", protection, scene.t["behind"], True))
                add(scene.name + ":material-control-present", bare_scene is not None)
                if bare_scene and bare_scene.name in masks:
                    ok, metrics = material_metrics(scene.images["final"], bare_scene.images["final"], mask["transmission"], limits)
                    add(scene.name + ":material-contributes-in-final", ok, **metrics)
                if "pre" in scene.images:
                    ok, metrics = preserve_metrics(scene.images["final"], scene.images["pre"], mask["transmission"], limits)
                    add(scene.name + ":transparent-layer-not-erased-by-resolve", ok, **metrics)

    add("removed-capture-present", bool(removed), count=len(removed))
    for front in FRONTS:
        add("removed-combination:" + front, any(s.key == (front, "none", True, False) for s in removed))
    for scene in removed:
        reference = baseline.get(scene.key)
        valid = (not scene.t["skyPresent"] and reference is not None
                 and reference.name in masks and scene.name in masks)
        add(scene.name + ":removed-reference-present", valid)
        if valid:
            add(scene.name + ":removed-fixed-camera", matching(scene, reference))
            roi = ~dilate(masks[scene.name]["hand"] | masks[reference.name]["hand"], 2)
            metrics = pixel_metrics(scene.images["final"], reference.images["final"], roi)
            add(scene.name + ":removed-world-restored", metrics["pixels"] >= limits["min_pixels"]
                and metrics["mae"] <= limits["restore_mae"] and metrics["p99"] <= limits["restore_p99"], **metrics)
    report["passedChecks"] = sum(c["passed"] for c in report["checks"])
    report["totalChecks"] = len(report["checks"])
    report["passed"] = bool(scenes) and all(c["passed"] for c in report["checks"])
    return report


def new_report_path(path):
    """Preserve the first failed/live report instead of silently overwriting it."""
    if not path.exists(): return path
    for index in itertools.count(2):
        candidate = path.with_name(f"{path.stem}-r{index}{path.suffix}")
        if not candidate.exists(): return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    for name, value in DEFAULTS.items():
        parser.add_argument("--" + name.replace("_", "-"), type=type(value), default=value)
    args = parser.parse_args()
    limits = {key: getattr(args, key) for key in DEFAULTS}
    if any(not np.isfinite(v) or v <= 0 for v in limits.values()):
        parser.error("all thresholds must be finite and positive")
    try:
        result = evaluate(load(args.directory), limits)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = dict(passed=False, passedChecks=0, totalChecks=1,
                      checks=[dict(name="readable-valid-fixture", passed=False, error=str(error))])
    result["directory"] = str(args.directory)
    fixture_root = args.directory.resolve().parent
    if (fixture_root / "fixture.json").is_file():
        try:
            protocol = S.inspect_fixture(fixture_root)
            S.verify_warmup_images(fixture_root, protocol, all_no_sky=True)
            result["settledProtocol"] = protocol
            if protocol["settled"]:
                result["checks"].append(dict(name="complete-settled53-producer-protocol", passed=True))
        except (OSError, ValueError, KeyError, TypeError) as error:
            result["checks"].append(dict(name="complete-settled53-producer-protocol", passed=False, error=str(error)))
        result["passedChecks"] = sum(c["passed"] for c in result["checks"])
        result["totalChecks"] = len(result["checks"])
        result["passed"] = bool(result["checks"]) and all(c["passed"] for c in result["checks"])
    if args.output:
        output = new_report_path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, indent=2, allow_nan=False)
            stream.write("\n")
        print("report:", output)
    print(f"{args.directory}: {result['passedChecks']}/{result['totalChecks']} "
          f"{'PASS' if result['passed'] else 'FAIL'}")
    for check in result["checks"]:
        if not check["passed"]: print("FAIL", json.dumps(check, allow_nan=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
