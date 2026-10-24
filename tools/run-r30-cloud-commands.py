#!/usr/bin/env python3
"""Execute real client cloud-distance commands and preserve uniforms, mesh sizes and saved properties."""
from __future__ import annotations
import argparse,importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_cloud_runner',ROOT/'tools/run-r30-probe.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--label',required=True)
    p.add_argument('--profile',choices=['vanilla','iris','dh','iris-dh'],default='vanilla');p.add_argument('--shader');p.add_argument('--fsr',type=int,default=-1)
    a=p.parse_args()
    def phases(mode,warmup,sample,cycles,hold):
        def phase(name,commands,start,end,**extra):
            return dict(name=name,panelWidth=32,panelHeight=32,y=180,pitch=-10,warmupSeconds=10,sampleSeconds=8,
                        clientCommands=commands,expectedCloudFadeStart=start,expectedCloudFadeEnd=end,**extra)
        return [phase('cloud-default',['skyboxblock clouddistance reset'],128,384),
                phase('cloud-near-128',['skyboxblock clouddistance 128'],128/3,128),
                phase('cloud-far-768',['skyboxblock clouddistance 768'],256,768),
                phase('cloud-far-1536',['skyboxblock clouddistance 1536'],512,1536),
                phase('cloud-explicit-64-256',['skyboxblock cloudfade 64 256'],64,256),
                phase('cloud-reset',['skyboxblock cloudfade reset'],128,384),
                phase('cloud-invalid-inversion',['skyboxblock cloudfade 512 128'],128,384,allowFailedClientCommand=True,expectedCommandResult=0),
                phase('cloud-persisted-reload',['skyboxblock reload'],128,384)]
    runner.phases=phases
    args=['--mode','smoke','--profile',a.profile,'--terrain','flat','--render-distance','8','--probe',str(ROOT/'build/libs/skybox-validation-probe-r30.4.jar'),
          '--jar',str(a.jar),'--label',a.label,'--timeout','600']
    if a.shader:args+=['--shader',a.shader,'--fsr',str(a.fsr)]
    return runner.main(args)
if __name__=='__main__':raise SystemExit(main())
