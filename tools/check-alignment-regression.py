#!/usr/bin/env python3
"""Independent position oracle: real line-write mask vs projected block edges, emeralds vs world positions.

Never accepts an outline merely because its pixels survived the final sky resolve.
The line mask comes from the actual nonempty RenderType.lines draw, not a reference redraw.
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

NAMES = {"align-solid-no-sky", "align-solid-sky", "align-sky-selected", "align-other-solid",
         "align-oblique", "align-resized", "align-reload", "align-sky-removed", "align-clean"}


def rgb(path: Path) -> np.ndarray:
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB"))


def project(meta: dict, points, raster: bool = False) -> np.ndarray:
    matrix = np.linalg.inv(np.array(meta["inverseDisplayViewProjection"]).reshape(4, 4).T)
    points = np.atleast_2d(np.asarray(points, dtype=float)) - np.asarray(meta["camera"])
    clips = np.c_[points, np.ones(len(points))] @ matrix.T
    ndc = clips[:, :2] / clips[:, 3, None]
    if raster:
        sx, sy = meta["rasterScale"]
        jx, jy = meta["rasterJitter"]
        ndc = ndc * [sx, sy] + [sx - 1 + jx, sy - 1 + jy]
        width, height = meta["targetViewport"][2:]
    else:
        width, height = meta["width"], meta["height"]
    return np.c_[(ndc[:, 0] + 1) * width / 2, (1 - ndc[:, 1]) * height / 2]


def box_edges(meta: dict, raster: bool = False):
    # Known fixture cube, independent of the renderer's collected vertices or geometry helper.
    low, high = np.array([0., 141., 2.]) - .002, np.array([1., 142., 3.]) + .002
    vertices = list(itertools.product((0, 1), repeat=3))
    world = np.array([np.where(v, high, low) for v in vertices])
    points = project(meta, world, raster)
    all_edges, visible_edges = [], []
    camera = np.asarray(meta["camera"])
    for i, a in enumerate(vertices):
        for j in range(i + 1, len(vertices)):
            b = vertices[j]
            if sum(x != y for x, y in zip(a, b)) != 1:
                continue
            edge = np.stack([points[i], points[j]])
            all_edges.append(edge)
            visible = any(a[k] == b[k] and ((a[k] == 0 and camera[k] < low[k])
                         or (a[k] == 1 and camera[k] > high[k])) for k in range(3))
            if visible:
                visible_edges.append(edge)
    return all_edges, visible_edges


def primary_face_edges(meta: dict, raster: bool = False):
    # Require every edge of the largest projected camera-facing face. Thin secondary faces
    # can lose their far silhouette to the ordinary depth test at a subpixel boundary;
    # their actually drawn pixels still must pass the all-twelve-edge precision test.
    low, high = np.array([0., 141., 2.]) - .002, np.array([1., 142., 3.]) + .002
    camera = np.asarray(meta["camera"])
    faces = []
    for axis in range(3):
        if low[axis] <= camera[axis] <= high[axis]:
            continue
        side = low[axis] if camera[axis] < low[axis] else high[axis]
        other = [a for a in range(3) if a != axis]
        corners = []
        for u, v in ((0, 0), (1, 0), (1, 1), (0, 1)):
            p = low.copy()
            p[axis] = side
            p[other[0]] = high[other[0]] if u else low[other[0]]
            p[other[1]] = high[other[1]] if v else low[other[1]]
            corners.append(p)
        face = project(meta, corners, raster)
        area = abs(np.dot(face[:, 0], np.roll(face[:, 1], 1)) - np.dot(face[:, 1], np.roll(face[:, 0], 1))) / 2
        faces.append((area, face))
    face = max(faces, key=lambda item: item[0])[1]
    return [np.stack([face[i], face[(i + 1) % 4]]) for i in range(4)]


def distances(points: np.ndarray, edges) -> np.ndarray:
    best = np.full(len(points), np.inf)
    for a, b in edges:
        ab = b - a
        t = np.clip((points - a) @ ab / max(float(ab @ ab), 1e-12), 0, 1)
        best = np.minimum(best, np.linalg.norm(points - (a + t[:, None] * ab), axis=1))
    return best


def components(mask: np.ndarray) -> list[dict]:
    # No optional SciPy dependency: connected green pixels are small and sparse in this fixture.
    ys, xs = np.nonzero(mask)
    remaining = set(zip(xs.tolist(), ys.tolist()))
    result = []
    while remaining:
        seed = remaining.pop()
        queue, pixels = [seed], []
        while queue:
            x, y = queue.pop()
            pixels.append((x, y))
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                p = (x + dx, y + dy)
                if p in remaining:
                    remaining.remove(p)
                    queue.append(p)
        if len(pixels) >= 8:
            points = np.asarray(pixels)
            result.append({"pixels": len(pixels), "center": (points.mean(axis=0) + .5).tolist(),
                           "bounds": [points.min(axis=0).tolist(), points.max(axis=0).tolist()]})
    return sorted(result, key=lambda c: c["pixels"], reverse=True)


SKY_SCENES = NAMES - {"align-solid-no-sky", "align-sky-removed", "align-clean"}


def _erode(mask, count=2):
    for _ in range(count):
        p = np.pad(mask, 1, constant_values=False)
        mask = p[1:-1,1:-1] & p[:-2,1:-1] & p[2:,1:-1] & p[1:-1,:-2] & p[1:-1,2:]
    return mask


def alignment_sky_region(meta, name, shape):
    """Known fixture world AABBs, never item centers or renderer-generated masks."""
    h, w = shape
    xx, yy = np.meshgrid(2*(np.arange(w)+.5)/w-1, 1-2*(np.arange(h)+.5)/h)
    inverse = np.asarray(meta["inverseDisplayViewProjection"], dtype=float).reshape(4,4).T
    points = []
    for z in (-1., 0.):
        clip = np.stack((xx, yy, np.full_like(xx,z), np.ones_like(xx)), axis=-1)
        p = clip @ inverse.T
        points.append(p[...,:3]/p[...,3,None])
    direction = points[1]-points[0]
    origin = np.asarray(meta["camera"], dtype=float)

    def hit(bounds):
        low, high = np.asarray(bounds, dtype=float)
        parallel = np.abs(direction)<1e-12
        safe = np.where(parallel,1.,direction)
        a,b = (low-origin)/safe, (high-origin)/safe
        enter = np.where(parallel,-np.inf,np.minimum(a,b)).max(axis=-1)
        leave = np.where(parallel,np.inf,np.maximum(a,b)).min(axis=-1)
        outside = (parallel & ((origin<low)|(origin>high))).any(axis=-1)
        return np.where(~outside & (leave>=np.maximum(enter,0)) & (leave>0),np.maximum(enter,0),np.inf)

    if name not in SKY_SCENES: return np.zeros((h,w),dtype=bool)
    wall = hit([[-3,140,4],[4,145,5]])
    cube = hit([[0,141,2],[1,142,3]])
    visible = ((np.isfinite(wall)|np.isfinite(cube)) if name=="align-sky-selected"
               else (np.isfinite(wall)&(wall<cube)))
    return _erode(visible,2)  # Outer/solid-cube silhouette only; no item-centered exclusion.


def _plausible_item(component, shape):
    h,w = shape
    low,high = np.asarray(component["bounds"])
    width,height = high-low+1
    fill = component["pixels"] / float(width*height)
    return (2<=width<=w*.15 and h*.025<=height<=h*.16 and fill>=.18), fill


def item_candidates(meta, name, final, reference=None):
    """Whole-image green seeds + complete color-independent sky foreground bodies.

    This function deliberately does NOT inspect alignment.items/expected centers.
    Only the later global assignment knows where an item ought to be.
    """
    colors = final.astype(float)
    green = ((colors[...,1]>colors[...,0]*1.3)&(colors[...,1]>colors[...,2]*1.03)
             &(colors[...,1]-colors[...,0]>8)&(colors[...,1]>16))
    green[:20] = False; green[-48:] = False
    raw_green = components(green)
    result, raw_foreground = [], []
    region = alignment_sky_region(meta,name,final.shape[:2])
    details = dict(rawGreenComponents=raw_green[:16], skySearchPixels=int(region.sum()),
                   contrastThresholdRgb=8, referenceUsed=reference is not None,
                   searchUsesExpectedItemCenters=False,
                   shapeRules=dict(minHeightFraction=.025,maxHeightFraction=.16,maxWidthFraction=.15,minFill=.18))
    if reference is not None:
        if reference.shape!=final.shape: raise ValueError("private sky reference output size mismatch; no resize")
        foreground = region & (np.max(np.abs(final.astype(int)-reference.astype(int)),axis=-1)>8)
        raw_foreground = components(foreground)
        for component in raw_foreground:
            plausible, fill = _plausible_item(component,final.shape[:2])
            if not plausible: continue
            low,high = np.asarray(component["bounds"])
            body = dict(component, pixelCenter=component["center"],
                        center=((low+high+1)/2).tolist(), fillFraction=float(fill),
                        sources=["whole-sky-foreground-silhouette"], greenSeedPixels=0)
            result.append(body)
    for component in raw_green:
        low,high = np.asarray(component["bounds"])
        # Tiny green islands of the SAME sprite must not masquerade as another entity.
        owner = next((body for body in result if np.all(low>=np.asarray(body["bounds"][0])-2)
                      and np.all(high<=np.asarray(body["bounds"][1])+2)),None)
        if owner is not None:
            owner["greenSeedPixels"] += component["pixels"]
            if "green" not in owner["sources"]: owner["sources"].append("green")
            continue
        plausible, fill = _plausible_item(component,final.shape[:2])
        if not plausible: continue
        # Keep original hue/pixel-center evidence when no independent sky canvas exists.
        result.append(dict(component,fillFraction=float(fill),sources=["green"],greenSeedPixels=component["pixels"]))
    details["rawForegroundComponents"] = raw_foreground[:12]
    details["retainedCandidates"] = len(result)
    return sorted(result,key=lambda c:c["pixels"],reverse=True), details


def check(directory: Path, annotate: bool = False) -> dict:
    metadata = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in directory.glob("*.json")}
    alignment = {name: meta for name, meta in metadata.items() if name.startswith("align-")}
    checks, scenes = [], {}

    def add(name, passed, **metrics):
        checks.append({"name": name, "passed": bool(passed), **metrics})

    add("all-nine-alignment-scenes-captured", set(alignment) == NAMES, actual=sorted(alignment))
    add("all-cloud-world-heights-locked-to-192", bool(metadata) and all(
        m.get("cloudWorldHeight") == 192. and abs(m["cloudRelativeY"] + m["camera"][1] - .33 - 192) < .0001
        for m in metadata.values()))
    for name, meta in sorted(alignment.items()):
        add(name + ":zero-gl-errors", meta["initialGlError"] == meta["finalGlError"] == 0)
        add(name + ":iris-logical-target-restored", meta["irisMainBoundBefore"] == meta["irisMainBoundAfter"],
            before=meta["irisMainBoundBefore"], after=meta["irisMainBoundAfter"])
        add(name + ":iris-world-override-restored", meta["irisOverridesBefore"] == meta["irisOverridesAfter"],
            before=meta["irisOverridesBefore"], after=meta["irisOverridesAfter"])
        evidence = meta["alignment"]
        if name == "align-clean":
            add(name + ":no-items-or-sky-remain", not evidence["items"] and meta["blocks"] == 0)
            continue
        add(name + ":fixture-selected-known-cube", evidence.get("hitBlock") == [0, 141, 2]
            and evidence.get("outlineBounds") == [[0., 141., 2.], [1., 142., 3.]], hitBlock=evidence.get("hitBlock"))
        shader = evidence.get("lineShader", "") or ""
        is_iris = meta["irisOverridesBefore"] is not None
        add(name + ":correct-line-shader", ("ExtendedShader" in shader) if is_iris else (
            shader.endswith("ShaderInstance") or shader == "net.minecraft.class_5944"),
            shader=shader)
        line_path = directory / (name + "-line-mask-full.png")
        add(name + ":actual-line-write-mask-present", line_path.exists())
        final = rgb(directory / (name + ".png"))
        annotated = Image.fromarray(final)
        draw = ImageDraw.Draw(annotated)
        if line_path.exists():
            mask = rgb(line_path)[..., 0] > 0
            y, x = np.nonzero(mask)
            actual = np.c_[x + .5, y + .5]
            edges, _ = box_edges(meta, True)
            visible = primary_face_edges(meta, True)
            precision = float((distances(actual, edges) <= 3).mean()) if len(actual) else 0.
            coverage = []
            for edge in visible:
                samples = np.linspace(edge[0], edge[1], max(2, int(np.linalg.norm(edge[1] - edge[0]))))
                if len(actual):
                    # Small sets; chunk the all-pairs distance to keep memory bounded.
                    nearest = np.concatenate([np.linalg.norm(p[:, None] - actual[None, :], axis=2).min(axis=1)
                                              for p in np.array_split(samples, max(1, len(samples) // 128))])
                    coverage.append(float((nearest <= 3).mean()))
                else:
                    coverage.append(0.)
            add(name + ":line-pixels-at-correct-projected-location", len(actual) >= 64 and precision >= .99,
                actualLinePixels=len(actual), within3pxFraction=round(precision, 6))
            add(name + ":all-primary-face-outline-edges-covered", bool(coverage) and min(coverage) >= .75,
                visibleEdgeCoverage=[round(v, 6) for v in coverage], toleranceRasterPixels=3)
            scenes[name] = {"linePixelPrecision": precision, "minimumEdgeCoverage": min(coverage, default=0.)}
        for edge in box_edges(meta)[1]:
            draw.line([tuple(edge[0]), tuple(edge[1])], fill=(255, 60, 210), width=1)
        items = evidence["items"]
        add(name + ":two-known-emerald-entities", len(items) == 2 and all(i["item"] == "minecraft:emerald" for i in items))
        reference = None
        if name in SKY_SCENES:
            reference_path = directory / (name + ("-scene-display-clouds.png" if is_iris else "-scene-clouds.png"))
            add(name + ":independent-sky-foreground-reference-present", reference_path.exists())
            if reference_path.exists():
                reference = rgb(reference_path)
                add(name + ":independent-sky-reference-output-size", reference.shape==final.shape,
                    referenceSize=list(reference.shape[:2][::-1]), finalSize=list(final.shape[:2][::-1]))
                if reference.shape!=final.shape: reference=None
        candidates, detection = item_candidates(meta,name,final,reference)
        add(name + ":visible-emerald-components", len(candidates) >= 2, components=candidates[:8], detection=detection)
        expected_centers = []
        for item in sorted(items, key=lambda i: i["position"][0]):
            center = np.array(item["position"], dtype=float)
            phase = (item.get("age", item["tickCount"]) + item["partialTick"]) / 10 + item["bobOffset"]
            # Vanilla renderer adds .25*GROUND.scale.y (.125); generated-item GROUND
            # transform additionally translates by 2/16 world units (.125).
            center[1] += math.sin(phase) * .1 + .1 + .125 + .125
            expected_centers.append(project(meta, center)[0])
        # Global one-to-one assignment: a missing/edge-on sprite must not steal the other entity's match.
        choices = list(candidates) + [None] * len(items)
        assignment = min(itertools.permutations(range(len(choices)), len(items)), key=lambda indices: sum(
            float(np.linalg.norm(np.asarray(choices[j]["center"]) - expected)) if choices[j] else 1e8
            for expected, j in zip(expected_centers, indices)))
        errors = []
        for index, (expected, choice) in enumerate(zip(expected_centers, assignment)):
            chosen = choices[choice]
            actual = np.asarray(chosen["center"]) if chosen else None
            error = float(np.linalg.norm(actual - expected)) if actual is not None else None
            tolerance = max(10., meta["width"] * .018)
            add(name + f":item-{index}-at-correct-projected-location", error is not None and error <= tolerance,
                expectedCenter=expected.round(3).tolist(), actualCenter=actual.round(3).tolist() if actual is not None else None,
                centerErrorPixels=round(error, 3) if error is not None else None,
                actualBounds=chosen["bounds"] if chosen else None,
                actualEvidenceSources=chosen.get("sources",[]) if chosen else [],
                missingVisibleEmeraldComponent=chosen is None, tolerancePixels=round(tolerance, 3))
            errors.append(error)
            ex, ey = expected
            draw.ellipse((ex - 7, ey - 7, ex + 7, ey + 7), outline=(255, 210, 0), width=2)
            if chosen:
                ax, ay = actual
                draw.rectangle((ax - 5, ay - 5, ax + 5, ay + 5), outline=(0, 255, 255), width=2)
        scenes.setdefault(name, {})["itemCenterErrorsPixels"] = errors
        if annotate:
            annotated.save(directory / (name + "-position-oracle.png"))
    return {"directory": str(directory), "checks": checks, "scenes": scenes,
            "passedChecks": sum(c["passed"] for c in checks), "totalChecks": len(checks),
            "passed": bool(alignment) and all(c["passed"] for c in checks)}


def fresh_report(path: Path) -> Path:
    if not path.exists(): return path
    for revision in itertools.count(2):
        candidate=path.with_name(f"{path.stem}-r{revision}{path.suffix}")
        if not candidate.exists(): return candidate


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--annotate", action="store_true")
    args = parser.parse_args()
    results = [check(d, args.annotate) for d in args.directories]
    if args.output:
        destination=fresh_report(args.output)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("x",encoding="utf-8") as stream:
            json.dump(results,stream,indent=2,allow_nan=False);stream.write("\n")
        print("report:",destination)
    for result in results:
        print(f"{result['directory']}: {result['passedChecks']}/{result['totalChecks']} "
              f"{'PASS' if result['passed'] else 'FAIL'}")
        for item in result["checks"]:
            if not item["passed"]:
                print("  FAIL", json.dumps(item))
    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
