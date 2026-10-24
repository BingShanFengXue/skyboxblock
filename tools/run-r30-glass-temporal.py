#!/usr/bin/env python3
"""Owned continuous-frame glass controls at unchanged camera, pack and FSR parameters."""
from __future__ import annotations
import argparse,importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_glass_runner',ROOT/'tools/run-r30-probe.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--label',required=True)
    p.add_argument('--fsr',type=int,default=2);p.add_argument('--frames',type=int,default=90)
    p.add_argument('--profile',choices=['iris','iris-dh'],default='iris-dh')
    p.add_argument('--probe',type=Path,default=ROOT/'build/libs/skybox-validation-probe-r30.4.jar');a=p.parse_args()
    if not 32<=a.frames<=240:p.error('Use 32..240 independently recorded frames per control')
    def phases(mode,warmup,sample,cycles,hold):
        def phase(name,sky,glass):
            return dict(name=name,panelWidth=7 if sky else 0,panelHeight=7 if sky else 0,warmupSeconds=30,sampleSeconds=15,
                        captureFrames=a.frames,captureEveryFrames=3,
                        commands=['fill -4 136 -1 4 148 -1 minecraft:'+('glass' if glass else 'air')],
                        blockProbes=[[0,140,-1]],expectedGlass=glass,expectedSky=sky)
        return [phase('open-glass',False,True),phase('sky-glass',True,True),phase('sky-no-glass',True,False),phase('open-no-glass',False,False)]
    runner.phases=phases
    return runner.main(['--mode','smoke','--profile',a.profile,'--terrain','normal','--shader','iterationRP Alpha 0.8.31.zip','--fsr',str(a.fsr),
                        '--probe',str(a.probe),'--jar',str(a.jar),'--label',a.label,'--timeout','650'])
if __name__=='__main__':raise SystemExit(main())
