#!/usr/bin/env python3
"""Verify actual owned R30 capture inputs, measurements and resource-release evidence.

A valid individual probe is not the complete dynamic compatibility/performance
matrix and does not waive R29 strict failures or imply formal release acceptance.
"""
from __future__ import annotations
import argparse, hashlib, json, math, struct, zipfile
from pathlib import Path
import numpy as np


def verify(directory: Path):
    root=directory.resolve(strict=True); checks=[]
    def need(name,condition,**evidence):
        checks.append(dict(name=name,passed=bool(condition),**evidence)); return bool(condition)
    def read(name): return json.loads((root/name).read_text(encoding='utf-8'))
    def owned(name):
        p=(root/name).resolve(strict=True)
        if not p.is_relative_to(root):raise ValueError('Evidence path escapes owned fixture: '+name)
        return p
    marker=read('r30-owned.json'); runtime=read('run-result.json'); summary=read('r30-results/run-summary.json')
    need('owned-marker-and-directory',marker.get('owned') is True and marker['gameDir']==str(root) and root.parent.name=='render-regression')
    need('complete-process-execution',runtime.get('exitCode')==0 and runtime.get('complete') is True and not runtime.get('timedOut'))
    need('runtime-no-ERROR-or-fatal',runtime.get('errors')==[] and runtime.get('fatalErrors')==[],errors=runtime.get('errors'),fatal=runtime.get('fatalErrors'))
    need('exact-phase-count',runtime['phases']==runtime['expectedPhases']==len(marker['phases'])==len(summary['phases']))
    mods=[]
    for path in sorted((root/'mods').glob('*.jar')):
        with zipfile.ZipFile(path) as archive:
            if 'fabric.mod.json' in archive.namelist():
                m=json.loads(archive.read('fabric.mod.json')); mods.append(dict(id=m['id'],version=m['version'],sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    ids=[m['id'] for m in mods]
    need('one-real-main-and-probe',ids.count('skyboxblock')==1 and ids.count('skybox_r30_probe')==1)
    need('no-portal-mod-installed','immersive_portals' not in ids)
    need('actual-main-JAR-identity',any(m['id']=='skyboxblock' and m['sha256']==marker['productionJarSha256'] for m in mods))
    need('actual-probe-JAR-identity',any(m['id']=='skybox_r30_probe' and m['sha256']==marker['probeJarSha256'] for m in mods))
    for name,expected in marker['inputs'].items():need('input-bytes:'+name,hashlib.sha256(owned(name).read_bytes()).hexdigest()==expected)
    need('no-legacy-capture-harness',summary.get('legacyRegressionEnabled') is False)
    need('live-clock-and-animation-input',all(marker.get(k) is False for k in ('freezeShaderTime','freezeClouds','freezeWater')) and marker.get('liveWorldTicks') is True)
    actual_ids=[m['id'] for m in summary['actualMods']]
    need('actual-loader-confirms-no-portals','immersive_portals' not in actual_ids)
    has_dh='distanthorizons' in ids
    if has_dh:
        config=marker['dhConfiguration']['actual']
        need('actual-DH-installed-and-renderer-enabled','distanthorizons' in actual_ids and config['client']['advanced']['debugging']['rendererMode']=='DEFAULT')
        need('distant-generation-remains-enabled',config['common']['worldGenerator']['generatorPlan']!='DISABLED')
    phase_summaries=[]; minimum_lod=None; depth_samples=0; last_events=-1
    for index,(requested,phase) in enumerate(zip(marker['phases'],summary['phases'])):
        name=phase['name']; prefix=f'{index:02d}:{name}'
        need(prefix+':request-identity',phase['request']==requested and phase['index']==index and requested['name']==name)
        geometry=phase['serverGeometry']
        need(prefix+':actual-server-panel-count',geometry['actualServerPanelBlocks']==geometry['requestedPanelBlocks'])
        if geometry['requestedPanelBlocks']==0 or geometry['material']=='air':need(prefix+':actual-NoSky',phase['minimumCollectedBlocks']==phase['maximumCollectedBlocks']==0)
        elif name not in {'move-east','move-far'}:
            need(prefix+':actual-sky-collection-positive',phase['maximumCollectedBlocks']>0)
        raw=owned('r30-results/'+phase['rawFrameTimes']); times=np.fromfile(raw,dtype='>i8')
        need(prefix+':raw-interval-count',len(times)==phase['measurementFrames'] and len(times)>=10)
        need(prefix+':raw-intervals-positive',bool(len(times) and np.all(times>0)))
        if len(times):
            sorted_times=np.sort(times); n=len(times); calc=dict(mean=float(times.mean()/1e6),p50=float(sorted_times[(n-1)//2]/1e6),p95=float(sorted_times[int((n-1)*.95)]/1e6),p99=float(sorted_times[int((n-1)*.99)]/1e6),max=float(sorted_times[-1]/1e6),effectiveFps=float(n/(times.sum()/1e9)))
            need(prefix+':raw-recomputes-all-distributions',all(math.isclose(value,phase['frameTimeMilliseconds'][key],rel_tol=1e-10,abs_tol=1e-7) for key,value in calc.items()))
            need(prefix+':measured-time-coverage',times.sum()/1e9>=max(.1,requested['sampleSeconds']-1),observedSeconds=float(times.sum()/1e9),requestedSeconds=requested['sampleSeconds'])
        need(prefix+':no-phase-end-GL-errors',phase.get('phaseEndGlErrors')==[])
        audit=phase.get('privateStateAudit',{})
        if marker['mode']!='performance':
            need(prefix+':sparse-state-audit-executed',audit.get('enabled') is True and audit.get('sampledTransactions',0)>0)
            need(prefix+':private-state-restored',audit.get('failedTransactions')==0,failures=audit.get('failures'))
        else:need(prefix+':state-audit-disabled-for-performance',audit.get('enabled') is False)
        need(prefix+':actual-camera-and-matrices',len(phase.get('camera',[]))==3 and len(phase.get('worldProjectionColumnMajor',[]))==16 and len(phase.get('worldViewColumnMajor',[]))==16)
        need(prefix+':main-resolve-not-duplicated',phase.get('finalResolveCount',0)<=1)
        if 'iris' in ids and marker.get('profile')=='iris-dh' and 'shaderpacks' in {p.name for p in root.iterdir()} and phase['maximumCollectedBlocks']>0:
            need(prefix+':Iris-final-resolve-executed',phase.get('irisFinalHook') is True and phase.get('finalResolveCount')==1)
        if has_dh:
            dh=phase.get('dh',{}); events=dh.get('beforeRenderEvents',0)
            need(prefix+':actual-DH-render-events',events>last_events and events>0 and dh.get('afterRenderEvents',0)>0)
            last_events=events
            render=dh.get('renderList',{}); count=render.get('count',0)
            need(prefix+':actual-last-built-LOD-list',render.get('status')=='SAMPLED' and count>0 and len(render.get('buffers',[]))>0 and render.get('sameSizeAfterObservation') is True,containerClass=render.get('containerClass'),count=count,error=render.get('error'))
            vertices=sum(entry.get('vboOpaqueWrappersVertexCount',0)+entry.get('vboTransparentWrappersVertexCount',0) for entry in render.get('buffers',[]))
            need(prefix+':actual-uploaded-LOD-vertices',vertices>0 and any(entry.get('buffersUploaded') for entry in render.get('buffers',[])),vertices=vertices)
            minimum_lod=count if minimum_lod is None else min(count,minimum_lod)
            depths=dh.get('depth',[]); need(prefix+':depth-suppliers-present',len(depths)>0)
            for number,depth in enumerate(depths):
                dp=prefix+f':depth-{number}'
                if not need(dp+':actual-live-depth',depth.get('status')=='SAMPLED',evidence=depth):continue
                path=owned('r30-results/'+depth['rawFile']); values=np.fromfile(path,dtype='<f4'); depth_samples+=1
                need(dp+':raw-hash-size-and-grid',hashlib.sha256(path.read_bytes()).hexdigest()==depth['rawSha256'] and path.stat().st_size==depth['rawBytes']==depth['width']*depth['height']*4)
                need(dp+':finite-unit-depth',bool(np.all(np.isfinite(values)) and np.all((values>=0)&(values<=1))))
                need(dp+':real-distant-depth-observable',int(np.count_nonzero((values>0)&(values<1)))==depth['interiorDepthCount'] and depth['interiorDepthCount']>0)
        phase_summaries.append(dict(name=name,frames=phase['measurementFrames'],frameTime=phase['frameTimeMilliseconds'],collectedMin=phase['minimumCollectedBlocks'],collectedMax=phase['maximumCollectedBlocks'],resources=phase['resources']['liveUniqueObjects']))
    releases=sorted((root/'r30-results').glob('post-release-*.json'))
    need('actual-production-release-observed',bool(releases))
    for path in releases:
        data=json.loads(path.read_text(encoding='utf-8'))
        need('zero-owned-objects-after:'+path.name,data.get('stage')=='SkyWorldRenderer.release RETURN' and data.get('liveUniqueObjects')==0,evidence=data)
    passed=all(c['passed'] for c in checks)
    return dict(status='PROBE_EVIDENCE_VALID' if passed else 'PROBE_EVIDENCE_FAILED',passed=passed,counts=dict(passed=sum(c['passed'] for c in checks),total=len(checks)),checks=checks,actualMods=mods,phaseSummaries=phase_summaries,depthSamples=depth_samples,minimumLastBuiltLodBuffers=minimum_lod,
                completeDynamicCompatibilityAccepted=False,performanceAccepted=False,releaseAccepted=False,
                limitation='Individual probe validity only; final matrix, explicit occlusion oracles, temporal stability analysis and original failure records remain separate.')

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('directory',type=Path); p.add_argument('--output',type=Path,required=True); a=p.parse_args(argv)
    result=verify(a.directory)
    with a.output.open('x',encoding='utf-8') as stream:json.dump(result,stream,indent=2); stream.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k not in {'checks','actualMods','phaseSummaries'}},indent=2))
    for c in result['checks']:
        if not c['passed']:print('FAIL',c['name'],json.dumps({k:v for k,v in c.items() if k not in {'name','passed'}},ensure_ascii=False)[:1500])
    return 0 if result['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
