#!/usr/bin/env python3
"""Serial 26.2 render-work A/B on fresh isolated worlds; does not start or modify the user's instance."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('skybox_runtime', HERE / 'runtime-regression.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

BACKDROP = [
    'gamemode spectator @a', 'tp @a 0.500 -60.000 -8.000 0 0',
    'weather clear', 'time set 6000',
    'fill -24 -60 7 24 -38 7 minecraft:sandstone',
    'fill -3 -60 6 3 -52 6 minecraft:blue_concrete',
]
SCENES = [
    ('empty', [], True),
    ('small', ['fill -1 -59 4 1 -58 4 skyboxblock:sky_block'], True),
    ('large', ['fill -1 -59 4 1 -58 4 minecraft:air', 'fill -24 -60 5 24 -38 5 skyboxblock:sky_block',
               'fill 3 -60 3 4 -52 3 minecraft:glass'], True),
    ('panorama', ['fill -24 -60 5 24 -38 5 skyboxblock:kloppenheim_sky_block',
                  'fill 3 -60 3 4 -52 3 minecraft:air'], True),
    ('global-off', ['skyboxblock env render_skybox false',
                    'execute if block 0 -59 5 skyboxblock:kloppenheim_sky_block'], False),
    ('player-on', ['skyboxblock player @a render_skybox true'], True),
    ('player-reset', ['skyboxblock player @a reset render_skybox'], False),
    ('restore', ['skyboxblock env reset render_skybox'], True),
]


def run(opts, variant):
    name = f'{opts.label}-{variant}'
    case, game, control = runtime.paths(name)
    runtime.prepare(argparse.Namespace(name=name, mod=str(opts.jar), plain=not opts.shader, dh=False))
    option_file = game / 'options.txt'
    lines = option_file.read_text(encoding='utf-8').splitlines()
    values = {'maxFps': '260', 'enableVsync': 'false', 'bobView': 'false', 'fov': '0.0', 'renderDistance': '8'}
    lines = [line for line in lines if line.partition(':')[0] not in values]
    option_file.write_text('\n'.join(lines + [f'{k}:{v}' for k, v in values.items()]) + '\n', encoding='utf-8')
    proc = runtime.launch(argparse.Namespace(name=name, size=opts.size, jvm=['-Dskyboxblock.perf.capture=true',
                        f'-Dskyboxblock.perf.baseline={str(variant.startswith("base")).lower()}']))

    def call(scene, **params):
        with contextlib.redirect_stdout(io.StringIO()):
            return runtime.call(argparse.Namespace(name=name, file=None, timeout=180,
                                json=json.dumps({'id': scene, **params})))

    results = {}
    try:
        setup = call('setup', commands=BACKDROP, frames=300, clouds=False, **({'shader': opts.shader} if opts.shader else {}))
        if setup.get('shadersActive') != bool(opts.shader):
            raise RuntimeError(f'Wrong shader state: {name}: {setup.get("shader")}')
        call('freeze', commands=['time set 6000', 'tick freeze'], frames=120)
        for scene, commands, expected in SCENES:
            r = call('perf-' + scene, commands=commands, frames=180, perf=opts.frames, capture='perf-' + scene + '.png')
            if r.get('renderSkybox') != expected or not r.get('serverEnforced'):
                raise RuntimeError(f'{name}/{scene}: client did not receive expected render state: {r}')
            for operation in r.get('commands', []):
                if operation.get('success') is not True:
                    raise RuntimeError(f'{name}/{scene}: command failed: {operation}')
            if not (control / ('perf-' + scene + '.csv')).is_file():
                raise RuntimeError(f'{name}/{scene}: timing CSV absent')
            results[scene] = r
            print(name, scene, 'enabled=' + str(expected), 'revision=' + str(r['serverRevision']), flush=True)
        runtime.write_json(case / 'perf-suite.json', {
            'variant': variant, 'shader': opts.shader, 'resolution': opts.size, 'frames': opts.frames,
            'jarSha256': hashlib.sha256(opts.jar.read_bytes()).hexdigest(), 'results': results,
        })
    finally:
        if proc.poll() is None:
            try:
                call('stop', stop=True)
                proc.wait(30)
            except Exception:
                proc.terminate()
                proc.wait(20)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--label', required=True)
    p.add_argument('--variants', nargs='+', default=['base', 'opt'])
    p.add_argument('--shader', choices=runtime.PACKS)
    p.add_argument('--frames', type=int, default=600)
    p.add_argument('--size', type=int, nargs=2, metavar=('W', 'H'), default=(1280, 800))
    opts = p.parse_args()
    if not 60 <= opts.frames <= 3000 or not all(320 <= n <= 4096 for n in opts.size):
        p.error('invalid sample count or window dimensions')
    opts.jar = opts.jar.resolve(strict=True)
    for variant in opts.variants:
        if not variant.startswith(('base', 'opt')):
            p.error('variant names must begin with base or opt')
        run(opts, variant)


if __name__ == '__main__':
    main()
