#!/usr/bin/env python3
"""Owned R36 cloud seam/overdraw experiment; all changes stay inside a fresh fixture world."""
from __future__ import annotations
import argparse,importlib.util,os
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r36_cloud_probe',ROOT/'tools/run-r30-probe.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--label',required=True)
    p.add_argument('--profile',choices=['vanilla','iris','dh','iris-dh'],default='vanilla');p.add_argument('--shader');p.add_argument('--fsr',type=int,default=-1)
    p.add_argument('--baseline',action='store_true',help='Frozen R35 uses the old client tree; R36 uses server setting commands')
    p.add_argument('--quick',action='store_true',help='Diagnostic only, not full cloud-cell motion acceptance')
    p.add_argument('--poses',default='under,above');p.add_argument('--opacity',default='#FFFFFFFF')
    a=p.parse_args();chosen=a.poses.split(',')
    poses={'under':(180,-12),'above':(202,12),'inside':(192,0),
        'lower-transition-before':(194.70,0),'lower-transition-after':(194.72,0),
        'upper-transition-before':(182.70,-8),'upper-transition-after':(182.72,-8)}
    if any(name not in poses for name in chosen):p.error('Unknown pose')
    settings=['skyboxblock env reset all','skyboxblock env set cloud_color '+a.opacity,
        'skyboxblock env set fog_start_distance 383','skyboxblock env set cloud_fog_end_distance 384',
        'skyboxblock env set sky_color #80A0FF','skyboxblock env set fog_color #80A0FF',
        'skyboxblock env set sky_fog_end_distance 32768','skyboxblock env set star_brightness 0',
        'skyboxblock env set sunrise_sunset_color #00000000']
    def phases(mode,warmup,sample,cycles,hold):
        out=[]
        for name in chosen:
            y,pitch=poses[name]
            row=dict(name='cloud-'+name,panelWidth=64,panelHeight=64,panelDistance=6,orientPanel=True,
                x=.5,y=y,z=-8.5,pitch=pitch,warmupSeconds=8 if not out else 3,
                sampleSeconds=12 if a.quick else 65,captureFrames=10 if a.quick else 64,captureEveryGameTicks=20,
                commands=['gamerule doDaylightCycle false','gamerule doWeatherCycle false','time set 6000','weather clear'],
                clientCommands=settings if a.baseline else [],settingCommands=[] if a.baseline else settings)
            out.append(row)
        return out
    runner.phases=phases
    old=os.environ.get('JAVA_TOOL_OPTIONS');os.environ['JAVA_TOOL_OPTIONS']=((old+' ') if old else '')+'-Dskyboxblock.r36.cloudDiagnostics=true'
    args=['--mode','smoke','--profile',a.profile,'--terrain','flat','--render-distance','8','--max-fps','120',
        '--probe',str(ROOT/'build/libs/skybox-validation-probe-r36.jar'),'--jar',str(a.jar),'--label',a.label,'--timeout','1200']
    if a.shader:args+=['--shader',a.shader,'--fsr',str(a.fsr)]
    try:return runner.main(args)
    finally:
        if old is None:os.environ.pop('JAVA_TOOL_OPTIONS',None)
        else:os.environ['JAVA_TOOL_OPTIONS']=old
if __name__=='__main__':raise SystemExit(main())