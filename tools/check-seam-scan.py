#!/usr/bin/env python3
"""Independent ray/AABB oracle for explicit SeamScan diagnostics.

Raw holes/final source mismatches fail. Legacy depth rejection is diagnostic,
not a required zero: production provenance may correctly repair that rejection.
A negative geometry run is never required to reproduce a visual failure.
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path
import struct

import numpy as np
from PIL import Image


def image(path):
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB")).copy()


def erode(mask, times=3):
    for _ in range(times):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1, 1:-1] & p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:]
    return mask


def dilate(mask, times=3):
    for _ in range(times):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1, 1:-1] | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
    return mask


def expected(meta):
    w, h = int(meta["width"]), int(meta["height"])
    x, y = np.meshgrid(2 * (np.arange(w) + .5) / w - 1, 1 - 2 * (np.arange(h) + .5) / h)
    inv = np.asarray(meta["inverseDisplayViewProjection"], dtype=float).reshape(4, 4).T
    origin = np.asarray(meta["camera"], dtype=float)
    points = []
    for z in (-1., 0.):
        clip = np.stack((x, y, np.full_like(x, z), np.ones_like(x)), axis=-1)
        result = clip @ inv.T
        points.append(result[..., :3] / result[..., 3, None])
    direction = points[1] - points[0]
    result = np.zeros((h, w), dtype=bool)
    for bounds in meta["bounds"]:
        lo, hi = np.asarray(bounds, dtype=float)
        if lo.shape != (3,) or not np.isfinite([lo, hi]).all() or np.any(hi <= lo):
            raise ValueError("invalid world AABB")
        parallel = np.abs(direction) < 1e-15
        safe = np.where(parallel, 1., direction)
        a, b = (lo - origin) / safe, (hi - origin) / safe
        near = np.where(parallel, -np.inf, np.minimum(a, b)).max(axis=-1)
        far = np.where(parallel, np.inf, np.maximum(a, b)).min(axis=-1)
        outside = (parallel & ((origin < lo) | (origin > hi))).any(axis=-1)
        result |= ~outside & (far >= np.maximum(near, 0)) & (far > 0)
    return result


def phase_pixels(meta):
    matrix = np.asarray(meta["projection"]).reshape(4, 4).T @ np.asarray(meta["view"]).reshape(4, 4).T
    reference = np.asarray(meta["gridReference"])
    clips = [matrix @ np.r_[reference - np.asarray(meta[key]), 1.] for key in ("camera", "baseCamera")]
    return float(-(clips[0][1] / clips[0][3] - clips[1][1] / clips[1][3]) * meta["height"] / 2)


def jitter_key(vector):
    return ":".join(format(struct.unpack("<I", struct.pack("<f", float(v)))[0], "x") for v in vector)


def analyze(meta, mask, error, min_pixels=64):
    shape = (meta["height"], meta["width"], 3)
    if mask.shape != shape or error.shape != shape:
        raise ValueError("diagnostic images do not match display dimensions")
    geometric = expected(meta)
    roi = erode(geometric, 3)  # Outer silhouette only; internal block/fold edges stay in ROI.
    metrics = dict(sampledPixels=int(roi.sum()), rawHole=int((roi & (mask[..., 0] < 128)).sum()),
                   legacyDepthReject=int((roi & (mask[..., 0] >= 128) & (mask[..., 1] < 128)).sum()),
                   unexpectedHand=int((roi & (mask[..., 2] >= 128)).sum()),
                   finalMismatch=int((roi & (error.max(axis=-1) > 2)).sum()),
                   geometryLeak=int(((mask[..., 0] >= 128) & ~dilate(geometric, 3)).sum()))
    metrics["accepted"] = (metrics["sampledPixels"] >= min_pixels and metrics["rawHole"] == 0
                           and metrics["finalMismatch"] == 0 and metrics["unexpectedHand"] == 0
                           and metrics["geometryLeak"] == 0)
    return metrics


def dark_lines(source, roi):
    source = source.astype(int)
    above, below, middle = source[:-2], source[2:], source[1:-1]
    average = (above + below) // 2
    candidates = ((np.abs(above - below).max(axis=-1) <= 8)
                  & (np.abs(middle - average).max(axis=-1) >= 24)
                  & (middle[..., 2] < average[..., 2] - 20))
    return int((candidates & roi[1:-1]).sum())


def inspect_capture(path):
    meta = json.loads(path.read_text(encoding="utf-8"))
    prefix = meta["capturePrefix"]
    root = path.parent
    mask, source, final = [image(root / (prefix + suffix)) for suffix in ("-mask.png", "-source.png", "-final.png")]
    error = np.abs(source.astype(int) - final.astype(int)).astype(np.uint8)
    metrics = analyze(meta, mask, error)
    metrics["sourceDarkLine"] = dark_lines(source, erode(expected(meta), 3))
    metrics.update(stage=meta["stage"], capture=prefix)
    # Compare integer counts to pixels, not merely an enabled flag in metadata.
    metrics["recordedCountsAgree"] = all(metrics[k] == meta.get(k) for k in ("sampledPixels", "rawHole", "legacyDepthReject", "finalMismatch", "sourceDarkLine"))
    return metrics


def check(directory):
    root = directory if (directory / "seamscan-summary.json").exists() else directory / "seamscan"
    checks, stages, captures = [], [], []

    def add(name, passed, **metrics):
        checks.append(dict(name=name, passed=bool(passed), **metrics))

    try:
        summary = json.loads((root / "seamscan-summary.json").read_text(encoding="utf-8"))
        add("finished-real-scan", summary.get("finished") is True and summary.get("totalRenderFrames", 0) > 0)
        add("all-stage-phases-complete", summary.get("phaseComplete") is True
            and summary.get("completedStages") == summary.get("plannedStages"))
        add("no-terminal-failure", not summary.get("failure"))
        entries = summary.get("stages", [])
        add("planned-stages-really-recorded", len(entries) == summary.get("plannedStages") and len(entries) >= 64)
        required = set(itertools.product(("flat", "fold"), ((1280, 800), (1786, 1053)), range(16)))
        actual = set()
        stage_ids = set()
        for entry in entries:
            stage_id = entry["stage"]
            label = f"stage-{stage_id:03d}"
            add(label + ":unique-stage-id", stage_id not in stage_ids)
            stage_ids.add(stage_id)
            stored = json.loads((root / (entry["aggregatePrefix"] + ".json")).read_text(encoding="utf-8"))
            add(label + ":stage-file-matches-final-summary", stored == entry)
            if not entry.get("near", False):
                key = (entry["shape"], (entry["width"], entry["height"]), entry["phase"])
                add(label + ":unique-required-phase", key not in actual)
                actual.add(key)
            add(label + ":stage-complete", entry.get("complete") is True and 23 <= entry.get("renderFrames", 0) <= 180)
            add(label + ":isolated-no-foreground-diagnostic", entry.get("spectator") is True
                and entry.get("hideGui") is True and entry.get("worldName") == "Skybox render fixture")
            vectors = entry.get("jitterStates", [])
            keys = [jitter_key(j["vector"]) for j in vectors]
            add(label + ":23-actual-distinct-jitters", len(set(keys)) >= 23 and len(keys) == len(set(keys))
                and len(keys) == entry.get("jitterCount") and all(j["key"] == k for j, k in zip(vectors, keys)),
                distinct=len(set(keys)), recorded=entry.get("jitterCount"))
            add(label + ":fixed-pose-across-jitter-scan", bool(vectors)
                and len({j.get("projectionHash") for j in vectors}) == 1
                and len({j.get("viewHash") for j in vectors}) == 1
                and all(np.allclose(j.get("camera", [np.inf]*3), entry["camera"], atol=.00005, rtol=0) for j in vectors))
            add(label + ":no-gl-or-logical-state-failure", entry.get("stateFailures") == 0
                and entry.get("initialGlError") == entry.get("finalGlError") == 0
                and entry.get("irisMainBoundBefore") == entry.get("irisMainBoundAfter")
                and entry.get("irisOverridesBefore") == entry.get("irisOverridesAfter"))
            add(label + ":actual-window-size", entry.get("width") in (1280, 1786) and entry.get("height") in (800, 1053)
                and entry.get("subpixelBits", 0) > 0)
            actual_phase = phase_pixels(entry)
            add(label + ":actual-subpixel-phase", abs(actual_phase - entry["phase"] / 16) <= .002,
                requested=entry["phase"] / 16, actual=actual_phase)
            mask = image(root / (entry["aggregatePrefix"] + "-mask-min.png"))
            errors = image(root / (entry["aggregatePrefix"] + "-final-error-max.png"))
            metrics = analyze(entry, mask, errors)
            metrics.update(stage=stage_id, name=entry["name"], maxSourceDarkLine=entry.get("maxSourceDarkLine", 0))
            stages.append(metrics)
            add(label + ":nonempty-independent-union-roi", metrics["sampledPixels"] >= 64, pixels=metrics["sampledPixels"])
            for field in ("rawHole", "finalMismatch", "unexpectedHand", "geometryLeak"):
                add(label + ":" + field, metrics[field] == 0, pixels=metrics[field])
            # Legacy depth rejects and source-dark-line candidates remain visible observations.
        add("all-shapes-sizes-sixteen-phases", required <= actual, missing=[str(k) for k in sorted(required - actual)])
        for path in sorted(root.glob("s*-f*.json")):
            metrics = inspect_capture(path)
            captures.append(metrics)
            add(metrics["capture"] + ":recorded-counts-verified", metrics["recordedCountsAgree"])
        for entry in entries:
            observed = any(entry.get(k, 0) > 0 for k in ("maxRawHole", "maxLegacyDepthReject", "maxFinalMismatch", "maxSourceDarkLine"))
            add(f"stage-{entry['stage']:03d}:first-anomaly-evidence", not observed or any(c["stage"] == entry["stage"] for c in captures))
        negative = summary.get("negativeSeams", False)
        rendered = summary.get("totalRenderFrames", 0)
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        add("readable-complete-scan-evidence", False, error=str(error))
        negative, rendered = None, 0
    passed = bool(stages) and all(c["passed"] for c in checks)
    source_review = (any(s.get("maxSourceDarkLine", 0) > 0 for s in stages)
                     or any(c.get("sourceDarkLine", 0) > 0 for c in captures))
    return dict(schemaVersion=1, directory=str(root), passed=passed,
                status="PASS_WITH_SOURCE_REVIEW" if passed and source_review else "PASS" if passed else "FAIL",
                sourceReviewRequired=source_review, negativeSeams=negative, renderFrames=rendered,
                checks=checks, stages=stages, anomalyCaptures=captures,
                legacyDepthRejectPixels=sum(s["legacyDepthReject"] for s in stages),
                rawHolePixels=sum(s["rawHole"] for s in stages), finalMismatchPixels=sum(s["finalMismatch"] for s in stages),
                passedChecks=sum(c["passed"] for c in checks), totalChecks=len(checks),
                interpretation="Legacy rejects are observations, not required failures; source candidates need independent review; a clean negative run is reported honestly.")


def selftest():
    w, h = 96, 72
    projection = np.array([[1., 0, 0, 0], [0, 4 / 3, 0, 0], [0, 0, 1.02, -.202], [0, 0, 1, 0]])
    meta = dict(width=w, height=h, camera=[0., 0., 0.], baseCamera=[0., 0., 0.], gridReference=[0., 0., 4.],
                inverseDisplayViewProjection=np.linalg.inv(projection).T.ravel().tolist(),
                projection=projection.T.ravel().tolist(), view=np.eye(4).T.ravel().tolist(),
                bounds=[[[-3., -2., 4.], [3., 2., 4.2]]])
    geometric = expected(meta)
    mask = np.zeros((h, w, 3), dtype=np.uint8)
    mask[geometric, :2] = 255
    error = np.zeros_like(mask)
    cases = []

    def require(name, value):
        cases.append(bool(value)); print(("PASS " if value else "FAIL ") + name)

    require("normal-independent-geometry-pass", analyze(meta, mask, error)["accepted"])
    altered = mask.copy(); altered[30:40, 45, 0] = 0
    require("internal-grid-raw-hole-fails", not analyze(meta, altered, error)["accepted"])
    altered = mask.copy(); altered[30:40, 45, 1] = 0
    result = analyze(meta, altered, error)
    require("legacy-reject-observed-not-forced-failure", result["accepted"] and result["legacyDepthReject"] == 10)
    altered_error = error.copy(); altered_error[30:40, 45] = [80, 100, 150]
    require("one-pixel-final-dashes-fail", not analyze(meta, mask, altered_error)["accepted"])
    source = np.full_like(mask, [100, 160, 220])
    source[36, 35:55] = [80, 90, 85]
    require("source-equals-final-does-not-hide-source-line-observation", dark_lines(source, erode(geometric, 3)) == 20)
    altered = mask.copy(); altered[30:40, 45, 2] = 255
    require("unexpected-foreground-in-spectator-fails", not analyze(meta, altered, error)["accepted"])
    keys = [jitter_key([np.float32(i / 97), np.float32(-i / 131)]) for i in range(23)]
    require("jitter-keys-use-actual-float-bits", len(set(keys)) == 23 and jitter_key([0., 0.]) != jitter_key([-0., 0.]))
    phase = 7 / 16
    shifted = dict(meta, camera=[0., phase * 2 * 4 / ((4 / 3) * h), 0.])
    require("actual-camera-phase-projected-independently", abs(phase_pixels(shifted) - phase) < 1e-9)
    folded = dict(meta, bounds=meta["bounds"] + [[[-3., 2., -1.], [3., 2.5, 4.2]]])
    folded_expected = expected(folded)
    folded_mask = np.zeros_like(mask); folded_mask[folded_expected, :2] = 255
    require("fold-AABB-union-not-individual-face-erosion", analyze(folded, folded_mask, error)["accepted"])
    empty = dict(meta, bounds=[[[100., 100., 4.], [101., 101., 5.]]])
    require("empty-ROI-fails-closed", not analyze(empty, mask, error)["accepted"])
    print(f"{sum(cases)}/{len(cases)} seam scan oracle selftests passed")
    return all(cases)


def fresh(path):
    if not path.exists(): return path
    for i in itertools.count(2):
        candidate = path.with_name(f"{path.stem}-r{i}{path.suffix}")
        if not candidate.exists(): return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--negative", type=Path)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest: return 0 if selftest() else 1
    if args.directory is None: parser.error("directory is required unless --selftest")
    report = check(args.directory)
    if args.negative:
        negative = check(args.negative)
        report["negativeControl"] = negative
        # Raw/final failures in negative are evidence, not required for success.
        valid_negative = bool(negative["stages"]) and all(c["passed"] for c in negative["checks"]
            if not c["name"].endswith((":rawHole", ":finalMismatch")))
        valid_negative = valid_negative and negative.get("negativeSeams") is True
        report["negativeControlValid"] = valid_negative
        report["negativeVisualReproductionFound"] = negative["rawHolePixels"] + negative["finalMismatchPixels"] > 0
        report["checks"].append(dict(name="negative-control-evidence-valid", passed=bool(valid_negative)))
        report["totalChecks"] += 1
        report["passedChecks"] += int(valid_negative)
        report["passed"] &= valid_negative
        if not report["passed"]: report["status"] = "FAIL"
    if args.output:
        path = fresh(args.output); path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, indent=2, allow_nan=False); stream.write("\n")
        print("report:", path)
    print(report["status"], f"{report['passedChecks']}/{report['totalChecks']}",
          "rawHole=", report["rawHolePixels"], "legacyDepthReject=", report["legacyDepthRejectPixels"],
          "finalMismatch=", report["finalMismatchPixels"], "sourceReview=", report["sourceReviewRequired"])
    for c in report["checks"]:
        if not c["passed"]: print("FAIL", json.dumps(c))
    if args.negative: print("negativeVisualReproductionFound:", report["negativeVisualReproductionFound"])
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
