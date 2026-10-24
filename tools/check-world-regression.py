#!/usr/bin/env python3
"""Validate the world-stage renderer using independent ray/AABB visibility.

Uses the active raster size (not the upscaled output size), and checks both
coverage and leakage. A successful game exit alone is not a visual pass.
Legacy captures are accepted as negative controls, without world-stage claims.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


PANEL = ([-3, 140, 3], [4, 145, 4])
SKY_BLOCK = ([0, 141, 3], [1, 142, 4])
PILLAR = ([0, 140, 0], [1, 146, 1])
SKY_WALL = ([-5, 181, 2], [6, 191, 3])


def image(path: Path) -> np.ndarray:
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB"))


def rays(meta: dict, width: int, height: int) -> np.ndarray:
    x, y = np.meshgrid(2 * (np.arange(width) + .5) / width - 1,
                       1 - 2 * (np.arange(height) + .5) / height)
    inverse = np.asarray(meta["inverseViewProjection"], dtype=float).reshape(4, 4).T
    points = []
    for z in (-1., 0.):
        clip = np.stack([x, y, np.full_like(x, z), np.ones_like(x)], axis=-1)
        world = clip @ inverse.T
        points.append(world[..., :3] / world[..., 3, None])
    direction = points[1] - points[0]
    return direction / np.linalg.norm(direction, axis=-1, keepdims=True)


def box_distance(origin, direction: np.ndarray, box) -> np.ndarray:
    low, high = np.asarray(box[0]), np.asarray(box[1])
    with np.errstate(divide="ignore", invalid="ignore"):
        a, b = (low - origin) / direction, (high - origin) / direction
    enter = np.max(np.minimum(a, b), axis=-1)
    leave = np.min(np.maximum(a, b), axis=-1)
    return np.where(leave >= np.maximum(enter, .05), np.maximum(enter, 0), np.inf)


def erode(mask: np.ndarray, times=2) -> np.ndarray:
    for _ in range(times):
        padded = np.pad(mask, 1, constant_values=False)
        mask = (padded[1:-1, 1:-1] & padded[:-2, 1:-1] & padded[2:, 1:-1]
                & padded[1:-1, :-2] & padded[1:-1, 2:])
    return mask


def dilate(mask: np.ndarray, times=2) -> np.ndarray:
    for _ in range(times):
        padded = np.pad(mask, 1, constant_values=False)
        mask = (padded[1:-1, 1:-1] | padded[:-2, 1:-1] | padded[2:, 1:-1]
                | padded[1:-1, :-2] | padded[1:-1, 2:])
    return mask


def expected_mask(name: str, meta: dict, direction: np.ndarray) -> np.ndarray | None:
    origin = np.asarray(meta["camera"])
    if name.startswith("bug-panel-"):
        return np.isfinite(box_distance(origin, direction, PANEL))
    if name.startswith("bug-pillar-"):
        sky = box_distance(origin, direction, SKY_BLOCK)
        pillar = box_distance(origin, direction, PILLAR)
        return np.isfinite(sky) & (sky < pillar)
    if name == "seam-clean":
        return np.zeros(direction.shape[:2], dtype=bool)
    if name.startswith("seam-"):
        return np.isfinite(box_distance(origin, direction, ([-10, 140, 6], [11, 151, 7])))
    if name in {"bug-clean", "sky-clean", "sky-occluded", "sky-inside"}:
        return np.zeros(direction.shape[:2], dtype=bool)
    if name.startswith(("sky-", "dim-")):
        return np.isfinite(box_distance(origin, direction, SKY_WALL))
    return None


def check(directory: Path, suite: str = "all") -> dict:
    result = {"directory": str(directory), "suite": suite, "checks": [], "scenes": {}}

    def add(name, passed, **metrics):
        result["checks"].append({"name": name, "passed": bool(passed), **metrics})

    metadata = {p.stem: json.loads(p.read_text(encoding="utf-8"))
                for p in sorted(directory.glob("*.json")) if p.stem.startswith(("bug-", "sky-", "seam-", "dim-"))}
    if suite != "all":
        prefix = "bug-" if suite == "bugs" else "sky-"
        metadata = {name: meta for name, meta in metadata.items() if name.startswith(prefix)}
    add("captures-present", bool(metadata), count=len(metadata))
    add("all-capture-gl-errors-zero", bool(metadata) and all(
        m["initialGlError"] == 0 and m["finalGlError"] == 0 for m in metadata.values()))
    add("real-first-person-not-spectator", bool(metadata) and all(
        m.get("cameraType") == "FIRST_PERSON" and not m.get("hideGui", True)
        for m in metadata.values()))
    add("all-resource-reloads-complete-at-capture", bool(metadata) and all(
        not m.get("resourceReloadPending", False) for m in metadata.values()))
    world_stage = all("opaqueWidth" in m for m in metadata.values())
    result["worldStageEvidence"] = world_stage
    for name, meta in metadata.items():
        width, height = meta.get("opaqueWidth", meta["width"]), meta.get("opaqueHeight", meta["height"])
        before = image(directory / (name + "-before.png"))
        opaque_path = directory / (name + "-opaque.png")
        opaque = image(opaque_path if opaque_path.exists() else directory / (name + ".png"))
        add(name + ":raster-evidence-size", before.shape == opaque.shape == (height, width, 3),
            activeRaster=[width, height], finalOutput=[meta["width"], meta["height"]])
        if before.shape != opaque.shape or before.shape != (height, width, 3):
            continue
        color_changes = np.max(np.abs(opaque.astype(int) - before.astype(int)), axis=-1) > 2
        depth_path = directory / (name + "-depth-change.png")
        actual = image(depth_path)[..., 0] > 0 if depth_path.exists() else color_changes
        if depth_path.exists():
            add(name + ":depth-evidence-consistent", int(actual.sum()) == meta["depthChangedPixels"],
                depthChangedPixels=int(actual.sum()))
            add(name + ":draw-before-first-person-hands", meta.get("drawStage") == "opaque-before-iris-hand")
        if "outsideRasterChangedPixels" in meta:
            add(name + ":inactive-raster-color-untouched", meta["outsideRasterChangedPixels"] == 0,
                changedPixels=meta["outsideRasterChangedPixels"])
            add(name + ":inactive-raster-depth-untouched", meta["outsideRasterDepthChangedPixels"] == 0,
                changedPixels=meta["outsideRasterDepthChangedPixels"])
            target = meta["targetViewport"]
            scale = meta["rasterScale"]
            add(name + ":raster-extent-matches-pack", abs(target[2] * scale[0] - width) < .01
                and abs(target[3] * scale[1] - height) < .01,
                targetViewport=target, activeViewport=meta["activeViewport"], rasterScale=scale)
        expected = expected_mask(name, meta, rays(meta, width, height))
        if expected is None:
            add(name + ":known-fixture", False)
            continue
        interior = erode(expected)
        interior_pixels = int(interior.sum())
        missing = int((interior & ~actual).sum())
        leakage = int((actual & ~dilate(expected)).sum())
        coverage = 1 - missing / max(1, interior_pixels)
        union = int((actual | expected).sum())
        iou = float((actual & expected).sum() / union) if union else 1.
        metrics = {"expectedPixels": int(expected.sum()), "actualPixels": int(actual.sum()),
                   "interiorPixels": interior_pixels, "missingInteriorPixels": missing,
                   "leakedPixelsBeyond2px": leakage, "interiorCoverage": round(coverage, 7),
                   "coverageIoU": round(iou, 7)}
        metrics["coverageEvidence"] = "depth" if depth_path.exists() else "rgb-negative-control"
        result["scenes"][name] = metrics
        should_be_visible = (name.startswith("bug-panel-") or (name.startswith(("seam-", "dim-")) and name!="seam-clean") or name in {
            "bug-pillar-side-left", "bug-pillar-side-right", "bug-pillar-turned",
            "bug-pillar-resized-side", "bug-pillar-restored-side",
            "sky-day", "sky-night", "sky-reload", "sky-resized", "sky-rain", "sky-clear"})
        if should_be_visible:
            add(name + ":fixture-actually-looks-at-sky", int(expected.sum()) > 32,
                expectedPixels=int(expected.sum()))
        color_leakage = int((color_changes & ~dilate(expected)).sum())
        add(name + ":no-color-leak", color_leakage == 0, leakedPixels=color_leakage)
        add(name + ":no-occlusion-leak", leakage == 0, leakedPixels=leakage)
        if expected.any():
            add(name + ":sky-interior-complete", interior_pixels > 0 and missing == 0, **metrics)
            add(name + ":visible-silhouette", iou >= .95, coverageIoU=round(iou, 7))
        else:
            add(name + ":fully-occluded-or-clean", not actual.any(), changedPixels=int(actual.sum()))
        scene_path = directory / (name + "-scene-clouds.png")
        if scene_path.exists() and interior_pixels:
            source = image(scene_path)
            if source.shape == opaque.shape:
                errors = np.abs(source.astype(int) - opaque.astype(int))[interior]
                maximum = int(errors.max()) if errors.size else 255
                add(name + ":independent-scene-to-face", maximum <= 1, maximumRgbError=maximum)
        # End-to-end display validation. Gbuffer coverage alone misses shaderpack whitening.
        display_meta = dict(meta)
        display_meta["inverseViewProjection"] = meta.get("inverseDisplayViewProjection", meta["inverseViewProjection"])
        final = image(directory / (name + ".png"))
        out_h, out_w = final.shape[:2]
        expected_final = expected_mask(name, display_meta, rays(display_meta, out_w, out_h))
        if expected_final is None:
            continue
        hand_mask = np.zeros((out_h, out_w), dtype=bool)
        hand_path = directory / (name + "-post-hand-mask.png")
        if hand_path.exists():
            low_hand = image(hand_path)[..., 0] > 0
            jx, jy = meta["rasterJitter"]
            target_w, target_h = meta["targetViewport"][2:]
            ix = np.floor((np.arange(out_w) + .5) / out_w * width + jx * .5 * target_w).astype(int)
            iy_gl = np.floor((1 - (np.arange(out_h) + .5) / out_h) * height + jy * .5 * target_h).astype(int)
            iy = height - 1 - iy_gl
            hand_mask = low_hand[np.clip(iy, 0, height-1)[:, None], np.clip(ix, 0, width-1)[None, :]]
        foreground_mask = hand_mask
        foreground_path = directory / (name + "-post-opaque-foreground.png")
        if foreground_path.exists() and hand_path.exists():
            low_foreground = image(foreground_path)[..., 0] > 0
            foreground_mask = low_foreground[np.clip(iy, 0, height-1)[:, None], np.clip(ix, 0, width-1)[None, :]]
        pre_path = directory / (name + "-pre-resolve.png")
        if pre_path.exists():
            pre = image(pre_path)
            final_changes = np.max(np.abs(final.astype(int) - pre.astype(int)), axis=-1) > 1
            leaked = int((final_changes & ~dilate(expected_final, 2)).sum())
            add(name + ":final-color-no-world-leak", leaked == 0, leakedPixels=leaked)
            inner_hand = erode(hand_mask)
            hand_pixels = int(inner_hand.sum())
            if hand_pixels:
                maximum = int(np.abs(final.astype(int) - pre.astype(int))[inner_hand].max())
                add(name + ":final-resolve-preserves-hands", maximum == 0,
                    sampledHandPixels=hand_pixels, maximumRgbError=maximum)
        source_path = directory / (name + "-scene-display-clouds.png")
        if not source_path.exists():
            source_path = directory / (name + "-scene-clouds.png")
        if source_path.exists() and expected_final.any():
            with Image.open(source_path) as source_image:
                reference = np.asarray(source_image.convert("RGB").resize((out_w, out_h), Image.Resampling.NEAREST))
            sample = erode(expected_final, 4) & ~dilate(foreground_mask, 3)
            overlay_path = directory / (name + "-world-overlay-mask.png")
            if overlay_path.exists():
                overlay = image(overlay_path)[..., 0] > 0
                add(name + ":world-overlay-mask-size", overlay.shape == sample.shape)
                if overlay.shape == sample.shape:
                    sample &= ~overlay
                    if name == "sky-rain":
                        add(name + ":actual-precipitation-recorded", int(overlay.sum()) > 32,
                            foregroundPixels=int(overlay.sum()))
            # Also keep fixture arm/item silhouettes out of pre-resolve historical negative controls.
            xx, yy = np.meshgrid(np.arange(out_w), np.arange(out_h))
            sample &= ~((xx > out_w*.65) & (yy > out_h*.62))
            if meta.get("offhandItem", "minecraft:air") != "minecraft:air":
                sample &= ~((xx < out_w*.4) & (yy > out_h*.62))
            delta = np.abs(final.astype(int) - reference.astype(int))[sample]
            maximum = int(delta.max()) if delta.size else 255
            mae = float(delta.mean()) if delta.size else 255.
            required_samples = max(32, int(erode(expected_final, 4).sum() * .4))
            add(name + ":final-display-matches-independent-sky", int(sample.sum()) >= required_samples and maximum <= 1,
                sampledPixels=int(sample.sum()), requiredSamples=required_samples,
                maximumRgbError=maximum, meanAbsoluteRgbError=round(mae, 7),
                finalMeanRgb=final[sample].mean(axis=0).round(3).tolist() if sample.any() else [],
                sourceMeanRgb=reference[sample].mean(axis=0).round(3).tolist() if sample.any() else [])
    # All held-item variants must draw the same complete world geometry *before* hands.
    for name, item in (("bug-panel-empty", "minecraft:air"),
                       ("bug-panel-sky-hand", "skyboxblock:sky_block"),
                       ("bug-panel-solid-hand", "minecraft:sandstone"),
                       ("bug-panel-torch-hand", "minecraft:torch")):
        if name in metadata:
            add(name + ":held-item-confirmed", metadata[name].get("heldItem") == item,
                heldItem=metadata[name].get("heldItem"))
    for name, expected_main, expected_offhand in (
            ("bug-panel-return-empty", "minecraft:air", "minecraft:air"),
            ("bug-panel-offhand", "minecraft:air", "skyboxblock:sky_block"),
            ("bug-panel-dual-hand", "minecraft:sandstone", "minecraft:torch")):
        if name in metadata:
            meta = metadata[name]
            add(name + ":both-hands-confirmed", meta.get("heldItem") == expected_main
                and meta.get("offhandItem") == expected_offhand,
                heldItem=meta.get("heldItem"), offhandItem=meta.get("offhandItem"))
    # The final screenshot must still contain first-person item/arm pixels.
    # Only compare the bottom-right region; shaderpack logos and moving clouds elsewhere are irrelevant.
    if "bug-panel-empty" in metadata:
        base = image(directory / "bug-panel-empty.png")
        height, width = base.shape[:2]
        crop = (slice(int(height * .73), height), slice(int(width * .68), width))
        for name in ("bug-panel-sky-hand", "bug-panel-solid-hand", "bug-panel-torch-hand"):
            if name not in metadata:
                continue
            final = image(directory / (name + ".png"))
            changed = np.max(np.abs(final.astype(int) - base.astype(int)), axis=-1) > 32
            count = int(changed[crop].sum())
            add(name + ":hand-visible-in-final-frame", count > 500, differingHandRegionPixels=count)
        for name in ("bug-panel-offhand", "bug-panel-dual-hand"):
            if name not in metadata:
                continue
            final = image(directory / (name + ".png"))
            changed = np.max(np.abs(final.astype(int) - base.astype(int)), axis=-1) > 32
            left = (slice(int(height * .65), height), slice(0, int(width * .35)))
            count = int(changed[left].sum())
            add(name + ":offhand-visible-in-final-frame", count > 500, differingOffhandRegionPixels=count)
    brightness = {}
    for name in ("sky-day", "sky-night", "sky-resized", "sky-reload", "sky-rain", "sky-clear"):
        base_path = directory / (name + "-scene-base.png")
        cloud_path = directory / (name + "-scene-clouds.png")
        if name not in metadata or not base_path.exists() or not cloud_path.exists():
            continue
        base, clouds = image(base_path), image(cloud_path)
        changed = np.max(np.abs(base.astype(int) - clouds.astype(int)), axis=-1) > 2
        count = int(changed.sum())
        enabled = metadata[name].get("cloudsEnabled", True)
        add(name + ":cloud-layer" if enabled else name + ":clouds-disabled",
            count > 32 if enabled else count == 0, cloudPixels=count)
        brightness[name] = float(clouds.mean())
    if "sky-day" in brightness and "sky-night" in brightness:
        add("independent-sky-follows-day-night", brightness["sky-day"] > brightness["sky-night"] + 10,
            dayMeanRgb=round(brightness["sky-day"], 3), nightMeanRgb=round(brightness["sky-night"], 3))
    result["passedChecks"] = sum(item["passed"] for item in result["checks"])
    result["totalChecks"] = len(result["checks"])
    result["passed"] = bool(metadata) and all(item["passed"] for item in result["checks"])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--suite", choices=["all", "bugs", "sky"], default="all")
    args = parser.parse_args()
    results = [check(directory, args.suite) for directory in args.directories]
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    for result in results:
        print(f"{result['directory']}: {result['passedChecks']}/{result['totalChecks']} "
              f"{'PASS' if result['passed'] else 'FAIL'}")
        for item in result["checks"]:
            if not item["passed"]:
                print("  FAIL", json.dumps(item))
    return 0 if all(result["passed"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
