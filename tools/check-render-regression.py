#!/usr/bin/env python3
"""Independent geometry, ray sampling, cloud-composition and occlusion checks.

A shaderpack's outside sky may differ from the accepted vanilla sky inside the block.
Background differences are diagnostics unless --require-matching-background is explicit.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent


def image(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"))


def rays(meta: dict) -> np.ndarray:
    width, height = meta["width"], meta["height"]
    xx, yy = np.meshgrid(2 * (np.arange(width) + .5) / width - 1,
                         1 - 2 * (np.arange(height) + .5) / height)
    inverse = np.asarray(meta["inverseViewProjection"]).reshape(4, 4).T
    points = []
    for z in (-1., 0.):
        clip = np.stack([xx, yy, np.full_like(xx, z), np.ones_like(xx)], axis=-1)
        world = clip @ inverse.T
        points.append(world[..., :3] / world[..., 3, None])
    direction = points[1] - points[0]
    return direction / np.linalg.norm(direction, axis=-1, keepdims=True)


def box_mask(meta: dict, direction: np.ndarray, low, high) -> np.ndarray:
    origin = np.asarray(meta["camera"])
    with np.errstate(divide="ignore", invalid="ignore"):
        t1 = (np.asarray(low) - origin) / direction
        t2 = (np.asarray(high) - origin) / direction
    enter = np.max(np.minimum(t1, t2), axis=-1)
    leave = np.min(np.maximum(t1, t2), axis=-1)
    return leave >= np.maximum(enter, .05)


def erode(mask: np.ndarray, times=2) -> np.ndarray:
    for _ in range(times):
        inner = np.zeros_like(mask)
        inner[1:-1, 1:-1] = (mask[1:-1, 1:-1] & mask[:-2, 1:-1] & mask[2:, 1:-1]
                              & mask[1:-1, :-2] & mask[1:-1, 2:])
        mask = inner
    return mask


def panorama_reference(direction: np.ndarray, name="aurora") -> np.ndarray:
    texture = image(ROOT / "src" / "main" / "resources" / "assets" / "skyboxblock" / "textures" / "sky" / (name + ".png"))
    u = np.mod(.5 + np.arctan2(direction[..., 0], direction[..., 2]) / (2 * np.pi), 1)
    v = .5 - np.arcsin(np.clip(direction[..., 1], -1, 1)) / np.pi
    x = np.floor(u * texture.shape[1]).astype(int) % texture.shape[1]
    y = np.minimum(np.floor(v * texture.shape[0]).astype(int), texture.shape[0] - 1)
    return texture[y, x]


def check(directory: Path, require_matching_background: bool = False) -> dict:
    result = {"directory": str(directory), "checks": [], "metrics": {}}

    def add(name, passed, **metrics):
        result["checks"].append({"name": name, "passed": bool(passed), **metrics})

    all_meta = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(directory.glob("*.json"))}
    add("captures-present", bool(all_meta), count=len(all_meta))
    add("all-capture-gl-errors-zero", all(m["initialGlError"] == 0 and m["finalGlError"] == 0 for m in all_meta.values()))
    for name in ("inside", "occluded", "clouds-occluded", "clean"):
        if name in all_meta:
            count = all_meta[name]["changedPixels"]
            add(name + "-no-overlay-leak", count == 0, changedPixels=count)
    for name, low, high in (("longitude-seam", [-2, 140, -4], [3, 144, -3]),
                            ("near-corner", [0, 141, 0], [1, 142, 1])):
        if name not in all_meta:
            continue
        meta = all_meta[name]
        direction = rays(meta)
        mask = box_mask(meta, direction, low, high)
        actual = image(directory / (name + ".png"))
        previous = image(directory / (name + "-before.png"))
        difference = np.max(np.abs(actual.astype(int) - previous.astype(int)), axis=-1) > 2
        union = np.count_nonzero(mask | difference)
        iou = np.count_nonzero(mask & difference) / max(1, union)
        sample_mask = erode(mask)
        delta = np.abs(actual.astype(float) - panorama_reference(direction).astype(float))[sample_mask]
        mae = float(np.mean(delta)) if delta.size else 999.
        accurate = float(np.mean(np.max(delta, axis=-1) <= 3)) if delta.size else 0.
        add(name + "-direction-sampling", mae < .75 and accurate > .98,
            meanAbsoluteRgbError=round(mae, 5), accuratePixelFraction=round(accurate, 6),
            sampledPixels=int(np.count_nonzero(sample_mask)), lateOverlayCoverageIoU=round(iou, 6))
    if "void" in all_meta:
        meta = all_meta["void"]
        mask = erode(box_mask(meta, rays(meta), [0, 141, 1], [1, 142, 2]), 4)
        pixels = image(directory / "void.png")[mask]
        maximum = int(np.max(pixels)) if pixels.size else 255
        add("void-pure-black", maximum == 0, maximumRgb=maximum)
    if all(name in all_meta for name in ("live-day", "live-night")):
        means = []
        for name in ("live-day", "live-night"):
            meta = all_meta[name]
            mask = erode(box_mask(meta, rays(meta), [0, 141, 1], [1, 142, 2]), 5)
            current = image(directory / (name + ".png"))
            sky = image(directory / (name + "-before.png"))
            means.append(np.mean(current[mask], axis=0))
            mae = float(np.mean(np.abs(current.astype(float) - sky.astype(float))[mask]))
            result["metrics"][name + "-outside-background-difference"] = round(mae, 5)
            if require_matching_background:
                add(name + "-background-alignment", mae < 1.0, meanAbsoluteRgbError=round(mae, 5))
        change = float(np.mean(np.abs(means[0] - means[1])))
        add("live-day-night-updates", change > 20, meanRgbChange=round(change, 5),
            dayRgb=means[0].round(2).tolist(), nightRgb=means[1].round(2).tolist())
    cloud_brightness = {}
    for name, meta in all_meta.items():
        if not name.startswith("clouds-"):
            continue
        base_path = directory / (name + "-probe-base.png")
        cloud_path = directory / (name + "-probe-clouds.png")
        expected = meta.get("cloudsEnabled", True)
        available = base_path.exists() and cloud_path.exists()
        add(name + "-probe-evidence-present", available)
        if not available:
            continue
        base, cloud = image(base_path), image(cloud_path)
        cloud_mask = np.max(np.abs(base.astype(int) - cloud.astype(int)), axis=-1) > 2
        changed = int(np.count_nonzero(cloud_mask))
        add(name + "-vanilla-cloud-layer" if expected else name + "-clouds-disabled",
            changed > 32 if expected else changed == 0, changedProbePixels=changed,
            renderFrames=meta.get("cloudFrames"))
        if changed:
            cloud_brightness[name] = float(np.mean(cloud[cloud_mask]))
        if name == "clouds-occluded":
            continue
        mask = erode(box_mask(meta, rays(meta), [-5, 181, 2], [6, 191, 3]), 4)
        final = image(directory / (name + ".png"))
        delta = np.abs(final.astype(float) - cloud.astype(float))[mask]
        mae = float(np.mean(delta)) if delta.size else 999.
        add(name + "-probe-to-block-alignment", mae < .5,
            meanAbsoluteRgbError=round(mae, 6), sampledPixels=int(np.count_nonzero(mask)))
        if expected:
            visible_cloud_pixels = int(np.count_nonzero(mask & cloud_mask))
            add(name + "-clouds-visible-inside-block", visible_cloud_pixels > 32,
                visibleCloudPixels=visible_cloud_pixels)
    if "clouds-day" in cloud_brightness and "clouds-night" in cloud_brightness:
        day, night = cloud_brightness["clouds-day"], cloud_brightness["clouds-night"]
        add("clouds-follow-day-night-color", day > night + 10,
            dayMeanRgb=round(day, 3), nightMeanRgb=round(night, 3))
    if "glass-control" in all_meta and "glass-foreground" in all_meta:
        a = image(directory / "glass-control.png").astype(float)
        b = image(directory / "glass-foreground.png").astype(float)
        meta = all_meta["glass-foreground"]
        mask = erode(box_mask(meta, rays(meta), [0, 141, 3], [1, 142, 4]), 5)
        difference = float(np.mean(np.abs(a - b)[mask]))
        add("sky-visible-through-glass", difference > 3, meanRgbChange=round(difference, 5))
    result["passed"] = all(item["passed"] for item in result["checks"])
    result["passedChecks"] = sum(item["passed"] for item in result["checks"])
    result["totalChecks"] = len(result["checks"])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-matching-background", action="store_true",
                        help="Historical stricter criterion, not the default acceptance target")
    args = parser.parse_args()
    results = [check(p, args.require_matching_background) for p in args.directories]
    text = json.dumps(results, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if all(item["passed"] for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
