#!/usr/bin/env python3
"""Check transmission response, not merely that a glass/hand screenshot has variance."""
from pathlib import Path
import argparse
import importlib.util
import numpy as np

spec=importlib.util.spec_from_file_location('matrix',Path(__file__).with_name('run-live-matrix.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--name',required=True)
    parser.add_argument('--pack',choices=['itrp','complementary','photon','bsl'],default='itrp')
    parser.add_argument('--require-bridge',action='store_true')
    args=parser.parse_args()
    pack=dict(module.SHADERS)[args.pack]
    test=module.Matrix(argparse.Namespace(name=args.name,mode='glass-transmission'))
    values={}
    try:
        test.shader('glass-'+args.pack,pack)
        test.request('glass-'+args.pack+'-setup',[
            'gamemode spectator @a','tp @a 0.500 -60.000 -8.000 0 -10',
            'fill -16 -61 -16 16 -61 7 minecraft:stone',
            'fill 0 -60 2 0 -55 2 minecraft:sandstone',
            'fill -2 -60 1 -2 -55 1 minecraft:red_stained_glass',
            'time set 6000','tick freeze'],frames=120)
        for label,block in [('sky-white','skyboxblock:white_sky_block'),
                            ('sky-black','skyboxblock:black_sky_block'),
                            ('concrete-white','minecraft:white_concrete'),
                            ('concrete-black','minecraft:black_concrete')]:
            tag='glass-'+args.pack+'-'+label
            test.request(tag,['fill -16 -63 8 16 0 8 '+block],frames=600)
            if args.require_bridge and label=='sky-white':
                log=(test.case/'client.log').read_text(encoding='utf-8',errors='replace')
                markers=['material MRT and composite25/40/50 plan validated',
                         'composite40 actual colortex6 RGBA16F input',
                         'ITRP scene input injected before composite40']
                for marker in markers:
                    test.check(marker in log,'audited bridge did not activate: '+marker)
                if test.failures:raise RuntimeError('Bridge activation validation failed; inspect client.log')
            image=test.image(tag)
            glass=image[240:360,552:582].mean(axis=(0,1))
            control=image[240:360,466:493].mean(axis=(0,1))
            values[label]={'glassRgb':glass.tolist(),'controlRgb':control.tolist(),
                           'normalizedRgb':(glass/np.maximum(control,1)).tolist()}
        def response(prefix):
            return float(np.abs(np.array(values[prefix+'-white']['normalizedRgb'])-
                                np.array(values[prefix+'-black']['normalizedRgb'])).mean())
        sky,concrete=response('sky'),response('concrete')
        test.check(concrete>.02,'ordinary opaque backdrop did not produce a measurable glass response')
        test.check(sky>.02 and sky>concrete*.2,'sky-window backdrop is missing from glass transmission')
    except Exception as error:
        test.failures.append(repr(error))
        raise
    finally:
        report={'pack':pack,'passed':not test.failures,'failures':test.failures,
                'results':test.results,'transmission':values}
        if len(values)==4:
            report['skyResponse']=response('sky');report['concreteResponse']=response('concrete')
        module.runtime.write_json(test.case/('glass-'+args.pack+'-report.json'),report)
    print('GLASS RESPONSE',sky,concrete,'PASS',not test.failures)
    if test.failures:raise SystemExit('\n'.join(test.failures))


if __name__=='__main__':main()
