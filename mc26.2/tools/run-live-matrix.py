#!/usr/bin/env python3
"""Real final-display regression matrix for the opt-in 26.2 fixture.

This does not use old world-frame.png / screen-coordinate columns as an oracle.
It records active shader, actual player pose, names and background-window state for every PNG.
"""
from __future__ import annotations
import argparse
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import time
import traceback

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('runtime_regression', HERE / 'runtime-regression.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

STYLES = ['sky_block', 'white_sky_block', 'black_sky_block', 'kloppenheim_sky_block',
          'syferfontein_sky_block', 'dusk_sky_block', 'galaxy_sky_block', 'lush_sky_block', 'apocalypse_sky_block']
SHADERS = [('vanilla', 'off'), ('itrp', runtime.PACKS[0]), ('complementary', runtime.PACKS[1]),
           ('photon', runtime.PACKS[2]), ('bsl', runtime.PACKS[3])]


class Matrix:
    def __init__(self, args):
        self.args = args
        self.case, self.game, self.control = runtime.paths(args.name)
        self.results = []
        self.failures = []
        self.active = None

    def check(self, condition, message):
        if not condition:
            self.failures.append(message)
            print('FAIL', message, flush=True)

    def request(self, label, commands=(), frames=90, **extra):
        payload = {'capture': label + '.png', 'commands': list(commands), 'frames': frames, **extra}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = runtime.call(argparse.Namespace(name=self.args.name, file=None, json=json.dumps(payload), timeout=300))
        (self.control / (label + '-response.txt')).write_text(output.getvalue(), encoding='utf-8')
        result['label'] = label
        self.results.append(result)
        for outcome in result.get('commands', []):
            self.check('success' in outcome, label+': command did not parse: '+outcome.get('command',''))
            if outcome.get('command','').startswith('skyboxblock '):
                self.check(outcome.get('success') is True, label+': skybox command failed: '+str(outcome))
        self.check(not result.get('mouseGrabbed'), label + ': mouse was grabbed')
        self.check(not result.get('windowFocused'), label + ': window took focus')
        self.check(not result.get('windowVisible'), label + ': fixture window was not hidden')
        self.check(result.get('irisError') in (None, 'Optional.empty'), label + ': stored shader error')
        if self.active is not None:
            self.check(result.get('shadersActive') == (self.active != 'off'), label + ': shader activation mismatch')
            if self.active != 'off':
                self.check(result.get('worldVPSource') == 'iris-gbuffer-uniforms', label + ': real Iris world-camera oracle was not captured')
        runtime.write_json(self.case / 'matrix-report.json', {'results': self.results, 'failures': self.failures})
        print(label, 'shader=' + str(result.get('shader')), 'frame=' + str(result.get('frame')), flush=True)
        return result

    def image(self, label):
        return np.asarray(Image.open(self.control / (label + '.png')).convert('RGB'), dtype=np.float64)

    @staticmethod
    def reset():
        return ['tick unfreeze', 'difficulty peaceful', 'gamemode spectator @a',
                'execute in minecraft:overworld run tp @a 0.500 -60.000 -8.000 0 -25',
                'fill -32 -63 0 32 0 5 minecraft:air', 'fill -32 -63 6 32 0 12 minecraft:air', 'weather clear', 'time set 6000']

    def shader(self, tag, pack):
        self.active = pack
        # Let native rain/thunder interpolation reach clear BEFORE freezing the world.
        # Freezing on the same tick as 'weather clear' preserves the preceding rainLevel forever.
        self.request(tag + '-shader-warmup', self.reset(), frames=1200 if pack != 'off' else 360,
                     shader=pack, clouds=True)
        self.request(tag + '-shader-ready', ['time set 6000', 'tick freeze'], frames=60)

    def reference(self):
        for tag, pack in SHADERS:
            self.shader(tag, pack)
            self.request(tag + '-reference', frames=60)
            sky = self.image(tag + '-reference')[20:360, 30:-30]
            mean, spread = sky.mean(), sky.std()
            self.check(mean > 10, tag + ': reference daytime sky is black')
            self.results[-1]['skyMean'] = float(mean)
            self.results[-1]['skyStd'] = float(spread)

    def names(self, result):
        lang = json.loads((runtime.PROJECT / 'src/main/resources/assets/skyboxblock/lang/zh_cn.json').read_text(encoding='utf-8'))
        names = {row['id']: row for row in result['names']}
        for style in STYLES:
            key = 'skyboxblock:' + style
            self.check(key in names, 'missing registered style ' + key)
            if key in names:
                expected = lang['block.skyboxblock.' + style]
                self.check(names[key]['block'] == expected and names[key]['item'] == expected,
                           key + ': block/item localization mismatch')

    def styles(self, tag):
        for style in STYLES:
            label = tag + '-' + style
            result = self.request(label, ['fill -32 -63 0 32 0 5 minecraft:air', 'fill -32 -63 6 32 0 12 minecraft:air',
                f'fill -32 -63 8 32 0 8 skyboxblock:{style}'], frames=90)
            self.names(result)
            image = self.image(label)[20:-20, 20:-20]
            if style == 'white_sky_block':
                self.check(np.percentile(image.min(axis=2), 1) >= 250, label + ': solid white is shaded/fogged')
            elif style == 'black_sky_block':
                self.check(np.percentile(image.max(axis=2), 99) <= 5, label + ': solid black is shaded/fogged')
            elif style != 'sky_block':
                self.panorama_oracle(label, result, style)

    def panorama_oracle(self, label, result, style, texture_path=None):
        """Independent CPU ray/PNG bilinear oracle using 1.21.1's equirectangular convention."""
        image = self.image(label)
        h, w = image.shape[:2]
        vp = np.array(result.get('worldVP', result['cameraVP'])).reshape(4, 4, order='F')
        inverse = np.linalg.inv(vp)
        xs = np.linspace(w * .15, w * .85, 19).astype(int)
        ys = np.linspace(h * .15, h * .85, 13).astype(int)
        xx, yy = np.meshgrid(xs, ys)
        ndcx = (xx.ravel() + .5) / w * 2 - 1
        ndcy = 1 - (yy.ravel() + .5) / h * 2
        near = np.stack([ndcx, ndcy, np.ones_like(ndcx), np.ones_like(ndcx)])
        depth = result.get('rayDepth', [1, .5])
        near[2] = depth[0]
        far = near.copy(); far[2] = depth[1]
        near = inverse @ near; far = inverse @ far
        ray = far[:3] / far[3] - near[:3] / near[3]
        ray /= np.linalg.norm(ray, axis=0)
        u = np.arctan2(ray[2], ray[0]) / (2 * np.pi) + .5
        v = np.arccos(np.clip(ray[1], -1, 1)) / np.pi
        texture_path = texture_path or (runtime.PROJECT / 'src/main/resources/assets/skyboxblock/textures/sky' /
                                       (style.removesuffix('_sky_block') + '.png'))
        texture = np.asarray(Image.open(texture_path).convert('RGB'), dtype=np.float64)
        th, tw = texture.shape[:2]
        tx, ty = u * tw - .5, np.clip(v * th - .5, 0, th - 1)
        x0, y0 = np.floor(tx).astype(int), np.floor(ty).astype(int)
        fx, fy = (tx - x0)[:, None], (ty - y0)[:, None]
        y1 = np.minimum(y0 + 1, th - 1)
        expected = ((1-fy) * ((1-fx)*texture[y0, x0 % tw] + fx*texture[y0, (x0+1) % tw]) +
                    fy * ((1-fx)*texture[y1, x0 % tw] + fx*texture[y1, (x0+1) % tw]))
        actual = image[yy.ravel(), xx.ravel()]
        error = np.abs(actual - expected)
        metrics = {'mean': float(error.mean()), 'p99': float(np.percentile(error, 99)), 'max': float(error.max())}
        self.results[-1]['panoramaOracle'] = metrics
        self.check(metrics['mean'] < 3 and metrics['p99'] < 12, label + ': panorama CPU oracle ' + str(metrics))

    def depth_continuity(self, tag):
        foreground = ['fill -5 -61 2 -2 -44 2 skyboxblock:galaxy_sky_block',
                      'fill 1 -60 4 4 -43 4 skyboxblock:galaxy_sky_block',
                      'fill -1 -56 0 0 -52 0 skyboxblock:galaxy_sky_block']
        poses = [(0.5, -60, -8, 0, -25), (1.4, -59.8, -7.3, 7, -22), (-1.1, -60, -6.8, -9, -28)]
        for index, pose in enumerate(poses):
            camera = 'tp @a ' + ' '.join(f'{v:.3f}' for v in pose)
            # Remove the near grass lip too. It is a REAL foreground silhouette, not an owned sky
            # pixel; its subpixel TAA coverage legitimately changes between jitter phases.
            commands = ['fill -32 -63 -16 32 0 -11 minecraft:air', 'fill -32 -63 -10 32 0 -5 minecraft:air',
                        'fill -32 -63 -4 32 0 1 minecraft:air', 'fill -32 -63 2 32 0 7 minecraft:air', camera,
                        'fill -32 -63 8 32 0 8 skyboxblock:galaxy_sky_block']
            base = f'{tag}-depth-{index}-background'
            front = f'{tag}-depth-{index}-foreground'
            reference = self.request(base, commands, frames=90)
            self.request(front, foreground, frames=90)
            image = self.image(base)
            mask = self.backdrop_mask(reference, image.shape[0], image.shape[1])
            self.check(int(mask.sum()) > image.shape[0] * image.shape[1] * .45, front + ': insufficient owned-window oracle coverage')
            # Natural shader clouds/grass OUTSIDE the finite backdrop keep animating; they are not
            # an infinite-window oracle. Analytically intersect the actual camera rays with its cube face.
            error = np.abs(image - self.image(front))[mask]
            metrics = {'mean': float(error.mean()), 'p99': float(np.percentile(error, 99)), 'max': float(error.max()),
                       'pixels': int(mask.sum()), 'pixelsOver8': int((error.max(axis=1) > 8).sum())}
            self.results[-1]['sameMaterialDepthDifference'] = metrics
            self.check(metrics['max'] <= 2, front + ': depth-dependent panorama seam ' + str(metrics))

    @staticmethod
    def backdrop_mask(meta, h, w):
        yy, xx = np.indices((h, w))
        ndcx = (xx.ravel() + .5) / w * 2 - 1
        ndcy = 1 - (yy.ravel() + .5) / h * 2
        clip = np.stack([ndcx, ndcy, np.ones_like(ndcx), np.ones_like(ndcx)])
        inverse = np.linalg.inv(np.array(meta['cameraVP']).reshape(4, 4, order='F'))
        near = inverse @ clip
        clip[2] = .5
        far = inverse @ clip
        rays = far[:3] / far[3] - near[:3] / near[3]
        eye = np.array([meta['cameraX'], meta['cameraY'], meta['cameraZ']])
        with np.errstate(divide='ignore', invalid='ignore'):
            distance = (8 - eye[2]) / rays[2]
            hit = eye[:, None] + rays * distance
        # Margin excludes only the silhouette/AA of the finite cube; all internal front/back edges remain.
        return ((distance > 0) & (hit[0] > -31.5) & (hit[0] < 32.5) &
                (hit[1] > -62.5) & (hit[1] < .5)).reshape(h, w)

    def windows(self):
        selected = set(getattr(self.args, 'tags', 'vanilla,itrp,complementary').split(','))
        for tag, pack in SHADERS:
            if tag not in selected:
                continue
            self.shader(tag, pack)
            self.styles(tag)
            self.depth_continuity(tag)
            self.movement(tag)
            self.request(tag + '-real-sky-preserved', self.reset() +
                         ['fill -3 -60 8 3 -49 8 skyboxblock:sky_block', 'tick freeze'], frames=150)
            sky = self.image(tag + '-real-sky-preserved')[20:120, 20:200]
            self.check(sky.mean() > 10, tag + ': mod blackened the real sky')
            for label, moment in [('night', 18000), ('sunset', 12500)]:
                self.request(tag + '-window-' + label, ['time set ' + str(moment)], frames=150)
            self.request(tag + '-window-rain', ['weather rain', 'tick unfreeze'], frames=300)

    def movement(self, tag):
        self.request(tag+'-movement-start', ['gamemode survival @a',
             'fill -16 -61 -16 16 -61 7 minecraft:stone',
             'tp @a 0.500 -60.000 -8.000 0 -25', 'fill -32 -63 8 32 0 8 skyboxblock:galaxy_sky_block', 'tick unfreeze'], frames=60)
        moving = []
        for index in range(6):
            label=tag+'-movement-'+str(index)
            result = self.request(label, frames=12, moving=True, yawStep=.3)
            moving.append(result)
            self.panorama_oracle(label, result, 'galaxy_sky_block')
        self.request(tag+'-movement-stop', frames=2, moving=False)
        distance = sum((moving[-1][key] - moving[0][key]) ** 2 for key in ('x', 'y', 'z')) ** .5
        self.check(distance > .1 and abs(moving[-1]['yaw'] - moving[0]['yaw']) > .1, tag+': movement test did not actually move/rotate')
        self.check(any(row.get('bobOriginOffset', 0) > .0001 for row in moving), tag+': movement test did not observe an actual post-bob origin offset')

    def run(self):
        started = time.time()
        try:
            self.reference() if self.args.mode == 'reference' else self.windows()
        except Exception:
            self.failures.append(traceback.format_exc())
            raise
        finally:
            report = {'mode': self.args.mode, 'name': self.args.name, 'seconds': time.time() - started,
                      'results': self.results, 'failures': self.failures, 'passed': not self.failures}
            runtime.write_json(self.case / 'matrix-report.json', report)
        if self.failures:
            raise SystemExit(f'{len(self.failures)} live regression assertions failed; see matrix-report.json')
        print('LIVE MATRIX PASS', len(self.results), 'scenes')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--mode', choices=['reference', 'windows'], required=True)
    parser.add_argument('--tags', default='vanilla,itrp,complementary')
    Matrix(parser.parse_args()).run()


if __name__ == '__main__':
    main()
