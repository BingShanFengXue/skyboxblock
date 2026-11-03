#!/usr/bin/env python3
"""Plain-Fabric integration: custom panorama, native moon oracle, colored-cloud reload, clock and rejoin."""
from pathlib import Path
import argparse
import importlib.util
import json
import zipfile
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('matrix', HERE / 'run-live-matrix.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
runtime = module.runtime


def color_cloud(game, rgb, alpha=255):
    target = game / 'resourcepacks/colored-clouds/assets/minecraft/textures/environment/clouds.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    Image.new('RGBA', (256, 256), (*rgb, alpha)).save(target)


def prepare(args):
    runtime.prepare(argparse.Namespace(name=args.name, mod=args.mod, dh=False, plain=True))
    case, game, control = runtime.paths(args.name)
    custom_spec = importlib.util.spec_from_file_location('custom_fixture', HERE / 'make-custom-sky-fixture.py')
    custom = importlib.util.module_from_spec(custom_spec)
    custom_spec.loader.exec_module(custom)
    custom.CONFIG_DIR = game / 'config/skyboxblock/skyboxes'
    custom.main()
    pack = game / 'resourcepacks/colored-clouds'
    pack.mkdir(parents=True, exist_ok=True)
    (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'pack_format': 88, 'min_format': [88, 0],
        'max_format': [88, 0], 'description': 'Owned colored/alpha cloud reload fixture'}}, indent=2), encoding='utf-8')
    color_cloud(game, (230, 45, 45))
    options = (game / 'options.txt').read_text(encoding='utf-8').splitlines()
    options = [line for line in options if not line.startswith('resourcePacks:')]
    options.append('resourcePacks:["file/colored-clouds"]')
    (game / 'options.txt').write_text('\n'.join(options)+'\n', encoding='utf-8')
    print('Advanced plain-Fabric fixture prepared:', game)


def run(args):
    test = module.Matrix(argparse.Namespace(name=args.name, mode='advanced'))
    test.active = 'off'
    try:
        first = test.request('plain-initial-authority', test.reset() + [
            'skyboxblock player @a reset all', 'skyboxblock environment reset all', 'skyboxblock environment time reset'],
            frames=180)
        test.names(first)
        test.check(first.get('serverEnforced') and first.get('serverRevision', 0) >= 1,
                   'initial JOIN snapshot was not accepted')
        generated = test.game / 'config/skyboxblock/generated/assets/skyboxblock/textures/sky/gradient.png'
        custom = test.request('custom-gradient', ['fill -32 -63 8 32 0 8 skyboxblock:gradient'], frames=120)
        names = {row['id']: row for row in custom['names']}
        test.check('skyboxblock:gradient' in names, 'custom block did not register')
        if 'skyboxblock:gradient' in names:
            test.check(names['skyboxblock:gradient']['item'] in ('夹具·渐变全景', 'Fixture Gradient'),
                       'custom item name did not use configured display name')
        test.panorama_oracle('custom-gradient', custom, 'gradient', generated)
        test.request('moon-time-ready', [
            'fill -32 -63 8 32 0 8 minecraft:air', 'skyboxblock environment set cloud_color #00000000',
            'time set 18000', 'tick unfreeze', 'tp @a 0.500 -60.000 -8.000 0 -90'], frames=120, clouds=False)
        test.request('moon-window', ['tick freeze', 'fill -32 -40 -32 32 -40 32 skyboxblock:sky_block'], frames=120)
        test.request('moon-native-oracle', ['fill -32 -40 -32 32 -40 32 minecraft:air'], frames=120)
        window = test.image('moon-window')[200:400, 380:580]
        native = test.image('moon-native-oracle')[200:400, 380:580]
        difference = np.abs(window-native)
        metrics = {'mean': float(difference.mean()), 'p99': float(np.percentile(difference, 99)),
                   'max': float(difference.max())}
        test.results[-1]['moonOracle'] = metrics
        test.check((window.max(axis=2)>100).sum()>500 and (native.max(axis=2)>100).sum()>500,
                   'moon oracle did not actually contain a visible moon')
        test.check(metrics['mean'] < 3 and metrics['p99'] < 12, 'native moon orientation/position mismatch: '+str(metrics))
        test.request('red-clouds', [
            'tp @a 0.500 -60.000 -8.000 0 -60', 'time set 6000',
            'fill -32 -63 8 32 0 8 skyboxblock:sky_block',
            'skyboxblock environment set cloud_height 64', 'skyboxblock environment set cloud_color #FFFFFFFF'],
            frames=150, clouds=True)
        red = test.image('red-clouds')[240:420, 320:640]
        red_fraction = float(((red[...,0]>red[...,1]+40)&(red[...,0]>red[...,2]+40)).mean())
        test.check(red_fraction>.03, 'colored cloud PNG RGB was not consumed: red fraction='+str(red_fraction))
        color_cloud(test.game, (40, 70, 240), 179)
        test.request('blue-alpha-clouds-after-reload', frames=150, reload=True)
        blue = test.image('blue-alpha-clouds-after-reload')[240:420, 320:640]
        blue_fraction = float(((blue[...,2]>blue[...,0]+40)&(blue[...,2]>blue[...,1]+40)).mean())
        test.results[-1]['cloudReload'] = {'redFractionBefore': red_fraction, 'blueFractionAfter': blue_fraction,
                                         'meanImageChange': float(np.abs(red-blue).mean()), 'textureAlpha': 179}
        test.check(blue_fraction>.03 and np.abs(red-blue).mean()>20,
                   'same-size cloud RGB/alpha resource reload did not update the rendered window')
        custom_reload = test.request('custom-gradient-after-resource-reload', [
            'tp @a 0.500 -60.000 -8.000 0 -25', 'fill -32 -63 8 32 0 8 skyboxblock:gradient'], frames=120)
        test.panorama_oracle('custom-gradient-after-resource-reload', custom_reload, 'gradient', generated)
        test.request('clock-night-source', [
            'fill -32 -63 8 32 0 8 minecraft:air', 'fill -4 -62 8 4 -44 8 skyboxblock:sky_block',
            'skyboxblock environment reset all', 'skyboxblock environment set cloud_color #00000000',
            'skyboxblock environment time reset', 'time set 18000', 'tick unfreeze'], frames=120)
        test.request('clock-lock-current', ['skyboxblock environment time lock'], frames=90)
        test.request('clock-locked-night-world-day', ['time set 6000'], frames=120)
        locked = test.image('clock-locked-night-world-day')[280:380, 430:530].mean()
        test.check(locked < 35, 'lock-current captured noon instead of the currently displayed night')
        test.request('clock-running-day', ['skyboxblock environment time set 6000'], frames=120)
        running = test.image('clock-running-day')[280:380, 430:530].mean()
        test.check(running > locked+40, 'RUNNING clock did not restore independent daylight')
        test.request('foreground-hand-glass', [
            'skyboxblock environment time reset', 'skyboxblock environment reset all', 'gamemode survival @a',
            'fill -16 -61 -16 16 -61 7 minecraft:stone', 'tp @a 0.500 -60.000 -8.000 0 -10',
            'item replace entity @a weapon.mainhand with minecraft:diamond_sword',
            'fill -16 -63 8 16 0 8 skyboxblock:white_sky_block',
            'fill 0 -60 2 0 -55 2 minecraft:sandstone',
            'fill -2 -60 1 -2 -55 1 minecraft:red_stained_glass'], frames=120, hud=True)
        foreground = test.image('foreground-hand-glass')
        test.check(foreground[200:580,350:610].std()>15, 'foreground occluder/hand image collapsed to solid sky')
        final = test.request('player-green-before-rejoin', [
            'gamemode spectator @a', 'fill -32 -63 0 32 0 5 minecraft:air',
            'fill -32 -63 6 32 0 12 minecraft:air', 'tp @a 0.500 -60.000 -8.000 0 -25',
            'fill -4 -62 8 4 -44 8 skyboxblock:sky_block',
            'skyboxblock player @a visual/sky_color #00FF00',
            'skyboxblock player @a visual/fog_color #00FF00',
            'skyboxblock player @a visual/cloud_color #00000000'], frames=120, hud=False)
        green = test.image('player-green-before-rejoin')[280:380,430:530].mean(axis=(0,1))
        test.check(green[1]>green[0]+80 and green[1]>green[2]+80, 'per-player environment did not reach renderer')
        test.check(final.get('serverEnforced') and final.get('serverRevision',0)>1, 'authoritative player revision missing')
    except Exception as error:
        test.failures.append(repr(error))
        raise
    finally:
        runtime.write_json(test.case / 'advanced-report.json', {'results':test.results,'failures':test.failures,
                                                              'passed':not test.failures})
    if test.failures:
        raise SystemExit('\n'.join(test.failures))
    print('ADVANCED MATRIX PASS', len(test.results), 'scenes; leave green state persisted for rejoin test')


def rejoin(args):
    test = module.Matrix(argparse.Namespace(name=args.name,mode='rejoin'))
    result = test.request('player-green-first-snapshot-after-rejoin', frames=150)
    green = test.image('player-green-first-snapshot-after-rejoin')[280:380,430:530].mean(axis=(0,1))
    test.check(result.get('serverEnforced') and result.get('serverRevision',0)>=1, 'rejoin initial authoritative snapshot missing')
    test.check(green[1]>green[0]+80 and green[1]>green[2]+80, 'persisted player settings lost on first snapshot after rejoin')
    runtime.write_json(test.case / 'rejoin-report.json', {'results':test.results,'failures':test.failures,'passed':not test.failures})
    if test.failures: raise SystemExit('\n'.join(test.failures))
    print('REJOIN FIRST-SNAPSHOT PASS')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('action', choices=['prepare','run','rejoin'])
    parser.add_argument('--name', required=True)
    parser.add_argument('--mod')
    args = parser.parse_args()
    globals()[args.action](args)


if __name__ == '__main__':
    main()
