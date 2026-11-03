#!/usr/bin/env python3
"""Measure fixed foreground brightness while changing only the independent dark-sky material."""
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
    parser.add_argument('--require-stable',action='store_true')
    parser.add_argument('--require-proxy',action='store_true')
    args=parser.parse_args()
    test=module.Matrix(argparse.Namespace(name=args.name,mode='dark-meter'))
    values={}
    try:
        test.shader('meter-itrp',module.runtime.PACKS[0])
        test.request('meter-setup',[
            'gamemode survival @a','fill -16 -61 -16 16 -61 7 minecraft:stone',
            'tp @a 0.500 -60.000 -8.000 0 -10',
            'fill 0 -60 2 0 -55 2 minecraft:sandstone',
            'item replace entity @a weapon.mainhand with minecraft:diamond_sword',
            'time set 6000','tick freeze'],frames=300,hud=True)
        for label,style in [('natural-before','sky_block'),('black','black_sky_block'),
                            ('galaxy','galaxy_sky_block'),('natural-after','sky_block')]:
            tag='meter-'+label
            test.request(tag,['fill -32 -63 8 32 0 8 skyboxblock:'+style],frames=1200,hud=True)
            image=test.image(tag)
            values[label]={'pillarRgb':image[240:360,466:493].mean(axis=(0,1)).tolist(),
                           'pillarMean':float(image[240:360,466:493].mean()),
                           'handMean':float(image[330:480,860:920].mean())}
        reference=(values['natural-before']['pillarMean']+values['natural-after']['pillarMean'])/2
        ratios={label:values[label]['pillarMean']/reference for label in ['black','galaxy']}
        if args.require_stable:
            for label,ratio in ratios.items():test.check(.85<ratio<1.15,label+' changed fixed foreground exposure: '+str(ratio))
        if args.require_proxy:
            log=(test.case/'client.log').read_text(encoding='utf-8',errors='replace').lower()
            test.check('itrp dark meter: composite74 sampler proxy active' in log,'real composite74 meter proxy activation was not observed')
            test.check('original atlas texture+sampler+activeunit restored after compute' in log,'meter sampler restoration was not observed')
    except Exception as error:
        test.failures.append(repr(error))
        raise
    finally:
        report={'passed':not test.failures,'failures':test.failures,'results':test.results,'brightness':values}
        if len(values)==4:report['darkToNaturalRatios']=ratios
        module.runtime.write_json(test.case/'dark-meter-report.json',report)
    print('DARK METER RATIOS',ratios,'PASS',not test.failures)
    if test.failures:raise SystemExit('\n'.join(test.failures))


if __name__=='__main__':main()
