#!/usr/bin/env python3
"""Summarize serial skybox A/B runs. Stage durations overlap and must not be added."""
from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from statistics import median

from PIL import Image, ImageChops

SCENES = ('empty', 'small', 'large', 'panorama', 'global-off', 'player-on', 'player-reset', 'restore')
DISABLED = {'global-off', 'player-reset'}


def percentiles(values):
    if not values:
        return None
    values = sorted(values)
    return {key: round(values[max(0, math.ceil(q * len(values)) - 1)] / 1e6, 6)
            for key, q in [('p50', .5), ('p95', .95), ('p99', .99)]}


def compare(before, after):
    with Image.open(before) as a, Image.open(after) as b:
        if a.size != b.size:
            raise ValueError(f'mismatched capture extents: {before} and {after}')
        diff = ImageChops.difference(a.convert('RGB'), b.convert('RGB'))
        mask = ImageChops.lighter(ImageChops.lighter(*diff.split()[:2]), diff.split()[2])
        errors = mask.histogram()
        return {'size': list(a.size), 'changedPixels': sum(errors[1:]),
                'maxChannelDifference': max(i for i, n in enumerate(errors) if n)}


def case(root, label, version, scenes):
    folder = root / label / ('results' if version == '1211' else 'control')
    out = {}
    for scene in scenes:
        stem = 'perf-' + scene
        with (folder / (stem + '.csv')).open(encoding='utf-8', newline='') as stream:
            rows = list(csv.DictReader(stream))
        if len(rows) < 60 or any(int(row['frame']) != i for i, row in enumerate(rows)):
            raise ValueError(f'incomplete timing capture: {label}/{scene}')
        enabled = scene not in DISABLED
        faces = sorted({int(row['faces']) for row in rows})
        if any(int(row['render_enabled']) != enabled for row in rows) or (
                faces != [0] if not enabled or scene == 'empty' else not faces or faces[0] < 1):
            raise ValueError(f'invalid render state in {label}/{scene}: {faces}')
        timing = {}
        for k in rows[0]:
            if not k.endswith('_ns'):
                continue
            durations = [int(row[k]) for row in rows if row[k] and int(row[k]) > 0]
            timing[k.removesuffix('_ns')] = {'samples': len(durations), 'ms': percentiles(durations)}
        out[scene] = {'sampleFrames': len(rows), 'faces': faces, 'renderSkybox': enabled,
                      'copiedPixelsMedian': median(int(row['copy_pixels']) for row in rows),
                      'timings': timing}
        if version == '1211':
            data = json.loads((folder / (stem + '.json')).read_text(encoding='utf-8'))
            out[scene]['capture'] = {k: data[k] for k in ('renderSkybox', 'changedPixels', 'depthChangedPixels',
                                      'outsideRasterChangedPixels', 'outsideRasterDepthChangedPixels',
                                      'initialGlError', 'finalGlError', 'camera', 'targetViewport')}
            if data['renderSkybox'] != enabled or data['initialGlError'] or data['finalGlError']:
                raise ValueError(f'invalid capture or GL error: {label}/{scene}')
            if not enabled and (data['changedPixels'] or data['depthChangedPixels']):
                raise ValueError(f'hidden sky draws colour/depth in {label}/{scene}')
    return folder, out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', choices=['1211', '262'])
    parser.add_argument('prefix')
    parser.add_argument('--root', type=Path)
    parser.add_argument('--pairs', nargs='+', default=['base:opt', 'base2:opt2'])
    parser.add_argument('--scenes', nargs='+', choices=SCENES, default=SCENES)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    root = args.root or (project if args.version == '1211' else project / 'mc26.2') / 'build/render-regression'
    result = {'version': args.version, 'prefix': args.prefix, 'pairs': []}
    for pair in args.pairs:
        before, after = (f'{args.prefix}-{suffix}' for suffix in pair.split(':', 1))
        fa, a = case(root, before, args.version, args.scenes)
        fb, b = case(root, after, args.version, args.scenes)
        scenes = {}
        for scene in args.scenes:
            name = 'perf-' + scene + '.png'
            if a[scene]['faces'] != b[scene]['faces']:
                raise ValueError(f'geometry differs between {before}/{after}/{scene}')
            scenes[scene] = {'baseline': a[scene], 'optimized': b[scene],
                             'pixels': compare(fa / name, fb / name)}
        for folder, data in ((fa, a), (fb, b)):
            if {'empty', *DISABLED} <= data.keys():
                for scene in DISABLED:
                    data[scene]['emptyVsDisabled'] = compare(folder / 'perf-empty.png', folder / ('perf-' + scene + '.png'))
        result['pairs'].append({'before': before, 'after': after, 'scenes': scenes})
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    for pair in result['pairs']:
        print(pair['before'], pair['after'])
        for label, scene in pair['scenes'].items():
            a, b = scene['baseline'], scene['optimized']
            row = [label, f"RGBdiff={scene['pixels']['changedPixels']}",
                   f"copy={a['copiedPixelsMedian']}>{b['copiedPixelsMedian']}"]
            for stage in ('frame_interval', 'OPAQUE_CPU', 'OPAQUE_GPU', 'WORLD_CANVAS_GPU', 'DISPLAY_CANVAS_GPU'):
                x, y = [v['timings'][stage]['ms'] for v in (a, b)]
                row.append(f"{stage}={x and x['p95']}>{y and y['p95']}")
            print(' '.join(row))


if __name__ == '__main__':
    main()
