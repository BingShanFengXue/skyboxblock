#!/usr/bin/env python3
"""DH 3.3.3 current-view smoke on a deliberately non-symmetric camera rotation."""
from pathlib import Path
import argparse
import importlib.util
import json

spec = importlib.util.spec_from_file_location('matrix', Path(__file__).with_name('run-live-matrix.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--pack', choices=['off','itrp'], default='off')
    args = parser.parse_args()
    test = module.Matrix(argparse.Namespace(name=args.name, mode='dh-smoke'))
    pack = 'off' if args.pack == 'off' else module.runtime.PACKS[0]
    try:
        test.shader('dh-'+args.pack, pack)
        result = test.request('dh-'+args.pack+'-sky-window', [
            'tp @a 0.500 -60.000 -8.000 37 -25',
            'fill -32 -63 8 32 0 8 skyboxblock:sky_block'], frames=600)
        test.request('dh-'+args.pack+'-foreground', [
            'fill -7 -60 0 -7 -54 0 minecraft:sandstone'], frames=180)
        log = (test.case / 'client.log').read_text(encoding='utf-8', errors='replace')
        test.check('[skyboxblock] observed DH 3.3.3 view projection' in log, 'real DH render parameters were not observed')
        test.check('[skyboxblock] current-view DH depth configured' in log, 'real current-view DH depth was never configured')
        test.check('[skyboxblock] DH 3.3.3 depth adapter:' not in log, 'DH API adapter reported a reflection/runtime error')
        test.check(abs(result['yaw']-37)<.1 and abs(result['pitch']+25)<.1,
                   'DH view validation did not use the requested non-symmetric rotation')
        test.check(test.image('dh-'+args.pack+'-sky-window').std()>5, 'DH smoke display collapsed to a uniform frame')
    except Exception as error:
        test.failures.append(repr(error))
        raise
    finally:
        module.runtime.write_json(test.case / 'dh-smoke-report.json', {
            'pack':pack,'results':test.results,'failures':test.failures,'passed':not test.failures})
    if test.failures:
        raise SystemExit('\n'.join(test.failures))
    print('DH CURRENT-VIEW SMOKE PASS',args.pack)


if __name__ == '__main__':
    main()
