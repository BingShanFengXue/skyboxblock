#!/usr/bin/env python3
"""Capture actual live GPU inputs for a same-material temporal-edge negative control."""
from pathlib import Path
import argparse
import importlib.util
import json
import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location('matrix', Path(__file__).with_name('run-live-matrix.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--name', required=True)
    args = parser.parse_args()
    test = module.Matrix(argparse.Namespace(name=args.name, mode='debug'))
    test.shader('itrp-debug', module.runtime.PACKS[0])
    base = test.request('debug-background', ['fill -32 -63 0 32 0 3 minecraft:air',
        'fill -32 -63 4 32 0 7 minecraft:air', 'tp @a 0.500 -60.000 -8.000 0 -25',
        'fill -32 -63 8 32 0 8 skyboxblock:galaxy_sky_block'], frames=120, debug='gpu-background')
    front = test.request('debug-foreground', ['fill -5 -61 2 -2 -44 2 skyboxblock:galaxy_sky_block',
        'fill 1 -60 4 4 -43 4 skyboxblock:galaxy_sky_block',
        'fill -1 -56 0 0 -52 0 skyboxblock:galaxy_sky_block'], frames=120, debug='gpu-foreground')
    actual = test.image('debug-foreground')
    error = np.abs(test.image('debug-background') - actual).max(axis=2)
    h, w = error.shape
    own = test.backdrop_mask(base, h, w)
    bad = own & (error > 8)
    directory = test.control / 'gpu-foreground'
    meta = json.loads((directory / 'gpu-inputs.json').read_text(encoding='utf-8'))
    th, tw = meta['height'], meta['width']
    yy, xx = np.indices((h, w))
    tx = np.clip(np.floor(((xx+.5)/w*meta['scaleX']+meta['jitterX']*.5)*tw).astype(int), 0, tw-1)
    ty = np.clip(np.floor(((h-yy-.5)/h*meta['scaleY']+meta['jitterY']*.5)*th).astype(int), 0, th-1)
    def read(label, components):
        return np.fromfile(directory / (label+'.f32'), dtype='<f4').reshape(th, tw, components)[ty, tx]
    owner, depth = read('coverage', 2), read('depth', 1)[..., 0]
    before, after = read('before', 4), read('after', 4)
    delta = np.abs(after-before).max(axis=2)
    limit = np.maximum(.001, np.abs(before[..., :3]).max(axis=2)*.0001)
    protected = delta > limit
    material = meta['materials'][0]['material']+1
    owner_missing = np.abs(owner[..., 1]-material) > .1
    depth_difference = np.abs(depth-owner[..., 0])
    report = {'meta': meta, 'ownedPixels': int(own.sum()), 'badPixels': int(bad.sum()),
              'maxDifference': float(error[own].max()),
              'badAndOwnerMissing': int((bad & owner_missing).sum()),
              'badAndTransparencyProtected': int((bad & protected).sum()),
              'badAndDepthOver1e7': int((bad & (depth_difference > 1e-7)).sum()),
              'badAndDepthOver1e5': int((bad & (depth_difference > 1e-5)).sum()),
              'ownerValuesAtBad': np.unique(owner[..., 1][bad]).tolist(),
              'depthDifferenceAtBad': np.quantile(depth_difference[bad], [0, .5, .9, 1]).tolist() if bad.any() else [],
              'colorDeltaAtBad': np.quantile(delta[bad], [0, .5, .9, 1]).tolist() if bad.any() else []}
    module.runtime.write_json(test.case / 'gpu-diagnosis.json', report)
    print(json.dumps(report, indent=2))
    for name, mask in [('bad', bad), ('transparency-guard', protected), ('owner-missing', owner_missing),
                       ('depth-mismatch', depth_difference > 1e-7)]:
        Image.fromarray((mask.astype(np.uint8)*255)).save(test.control / ('debug-'+name+'.png'))


if __name__ == '__main__':
    main()
