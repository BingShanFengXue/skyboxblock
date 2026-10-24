#!/usr/bin/env python3
"""Controlled causal LOD-only occlusion test, separate from the normal dynamic matrix.

Reuse the normal owned launcher. Only this fixture omits vanilla chunk draws and
sets owned DH overdraw prevention to zero, keeping real DH generation/rendering
live. A real gold wall must be positively observable before interpreting SkyOn.
"""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, re, tomllib
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_lod_runner',ROOT/'tools/run-r30-probe.py')
runner=importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--label',required=True)
    p.add_argument('--profile',choices=['dh','iris-dh'],required=True); p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--edge-stages',action='store_true')
    p.add_argument('--shader'); p.add_argument('--fsr',type=int,default=-1)
    p.add_argument('--probe',type=Path,default=ROOT/'build/libs/skybox-validation-probe-r30.3.jar')
    p.add_argument('--control-warmup',type=float,default=90); p.add_argument('--stage-warmup',type=float,default=45)
    p.add_argument('--gold-z',type=int,default=160,help='Independent physical NoSky gold wall, beyond the actual pack near-LOD exclusion')
    p.add_argument('--behind-distance',type=int,default=200); p.add_argument('--front-distance',type=int,default=96)
    a=p.parse_args()
    def controlled_phases(mode,warmup,sample,cycles,hold):
        def phase(name,w=0,h=0,distance=12,**extra):
            return dict(name=name,panelWidth=w,panelHeight=h,panelDistance=distance,warmupSeconds=a.stage_warmup,sampleSeconds=15,
                        lodOnlyWorld=True,blockProbes=[[0,140,a.gold_z],[0,120,a.gold_z]],**extra)
        control=phase('lod-only-gold-positive',commands=[f'forceload add -80 {a.gold_z} 80 {a.gold_z}',f'fill -80 100 {a.gold_z} 80 140 {a.gold_z} minecraft:gold_block'])
        control['warmupSeconds']=a.control_warmup
        normal=phase('ordinary-and-lod-sky-behind',64,64,a.behind_distance); normal['lodOnlyWorld']=False
        return [control,phase('lod-only-sky-behind',64,64,a.behind_distance),phase('lod-only-sky-front',64,64,a.front_distance),phase('lod-only-no-sky-holdout'),normal]
    original_write=runner.write_new
    def write_with_explicit_owned_override(path,value):
        if Path(path).name=='r30-owned.json':
            owner=Path(value['gameDir']).resolve(strict=True)
            assert value['owned'] is True and owner.parent.name=='render-regression'
            config=owner/'config/DistantHorizons.toml'; before=config.read_text(encoding='utf-8')
            after,count=re.subn(r'(?m)^(\s*overdrawPrevention\s*=\s*).+$',lambda m:m[1]+'"0.0"',before)
            assert count==1,'Known pinned DH overdraw setting required'
            actual=tomllib.loads(after)
            assert actual['common']['worldGenerator']['generatorPlan']!='DISABLED'
            assert actual['client']['advanced']['debugging']['rendererMode']=='DEFAULT'
            config.write_text(after,encoding='utf-8')
            value['dhConfiguration']['actual']=actual
            value['dhConfiguration']['preparedSha256']=hashlib.sha256(after.encode('utf-8')).hexdigest()
            value['controlledLodOcclusion']=dict(enabled=True,vanillaChunkDrawing='disabled only in explicitly marked phases; sky BER and DH remain active',
                overdrawPrevention='0.0',sourceConfigUnchanged=True,goldWall=dict(x=[-80,80],y=[100,140],z=a.gold_z),
                expectedOrder=f'sky-behind at z{a.behind_distance-9} must retain closer LOD gold; sky-front at z{a.front_distance-9} must hide farther LOD gold')
        return original_write(path,value)
    runner.phases=controlled_phases; runner.write_new=write_with_explicit_owned_override
    args=['--mode','dynamic','--profile',a.profile,'--terrain','normal','--render-distance','16','--lod-radius','64',
          '--probe',str(a.probe),'--jar',str(a.jar),'--label',a.label,'--timeout','1100']
    if a.edge_stages:args+=['--edge-stages']
    if a.shader:args+=['--shader',a.shader,'--fsr',str(a.fsr)]
    return runner.main(args)
if __name__=='__main__':raise SystemExit(main())
