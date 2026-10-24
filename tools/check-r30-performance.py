#!/usr/bin/env python3
"""Finite-window frame distributions, owned GPU lifetime and GC-aware memory trends.

No universal FPS threshold and no claim that a finite soak proves absence of all
leaks. Matched NeverSky measurements are separate from within-run block scaling.
"""
from __future__ import annotations
import argparse,importlib.util,json
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_perf_base',ROOT/'tools/check-r30-probe.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def read(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def rows(path):return [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()]
def memory_trend(samples):
    selected=[s for s in samples if s.get('phase')=='long-hold-4096' and s.get('measurement')]
    if len(selected)<10:return dict(status='INSUFFICIENT_LONG_HOLD_SAMPLES',samples=len(selected))
    origin=min(s['elapsedSeconds'] for s in selected);buckets={}
    for sample in selected:buckets.setdefault(int((sample['elapsedSeconds']-origin)//60),[]).append(sample['heapUsedBytes'])
    floor=[dict(minute=k,heapP10Bytes=float(np.percentile(v,10)),heapMinBytes=min(v),samples=len(v)) for k,v in sorted(buckets.items()) if len(v)>=10]
    slope=float(np.polyfit([x['minute'] for x in floor],[x['heapP10Bytes'] for x in floor],1)[0]) if len(floor)>=4 else None
    return dict(status='FINITE_WINDOW_MEASURED',samples=len(selected),durationSeconds=max(s['elapsedSeconds'] for s in selected)-origin,
                heapP10SlopeBytesPerMinute=slope,perMinuteFloors=floor,firstGcCount=selected[0]['gcCount'],lastGcCount=selected[-1]['gcCount'],
                heapUsedFirst=selected[0]['heapUsedBytes'],heapUsedLast=selected[-1]['heapUsedBytes'],
                meaning='GC-aware empirical floor trend; growth may include live DH generation/cache and is not by itself a leak attribution')
def evaluate(directory):
    directory=directory.resolve(strict=True);marker=read(directory/'r30-owned.json');summary=read(directory/'r30-results/run-summary.json')
    checked=base.verify(directory);checks=[]
    def need(name,value,**extra):checks.append(dict(name=name,passed=bool(value),**extra))
    need('actual-inputs-and-interval-distributions',checked['passed'],counts=checked['counts'])
    need('performance-mode',marker['mode']=='performance')
    need('not-allocation-profiled',not marker.get('allocationProfiling',False))
    need('no-diagnostic-temporal-readbacks',all(p.get('temporalCapture',{}).get('capturedFrames',0)==0 for p in summary['phases']))
    stage_rows=[]
    for phase in summary['phases']:
        name=phase['name'];life=phase.get('gpuLifetime',{});resources=phase['resources']
        need(name+':owned-lifetime-balanced',life.get('balancedCount') is True and life.get('live')==resources['liveUniqueObjects'])
        need(name+':no-untracked-or-duplicate-lifetimes',life.get('unknownDeletes')==life.get('duplicateCreates')==life.get('droppedEvents')==0)
        need(name+':deleted-names-not-resurrected',life.get('retiredNameStillLiveWithoutNewAllocation')==[] and life.get('trackedNameNoLongerLive')==[])
        stage_rows.append(dict(name=name,requestedBlocks=phase['request'].get('panelWidth',0)*phase['request'].get('panelHeight',0),
            actualMinBlocks=phase['minimumCollectedBlocks'],actualMaxBlocks=phase['maximumCollectedBlocks'],frames=phase['measurementFrames'],
            frameTimeMs=phase['frameTimeMilliseconds'],trackedOwnedObjects=resources['liveUniqueObjects'],bufferStorageBytes=resources['observedGpuBufferBytes'],
            created=life.get('created'),deleted=life.get('deleted'),live=life.get('live')))
    releases=sorted((directory/'r30-results').glob('post-release-*.json'));need('real-release-return-observed',bool(releases))
    for path in releases:
        r=read(path);g=r.get('gpuLifetime',{})
        need(path.name+':all-owned-objects-retired',g.get('live')==r['liveUniqueObjects']==0 and g.get('created')==g.get('deleted') and g.get('unknownDeletes')==0)
        need(path.name+':retired-names-physically-gone',g.get('retiredNameStillLiveWithoutNewAllocation')==[] and g.get('trackedNameNoLongerLive')==[])
    samples=rows(directory/'r30-results/samples.jsonl')
    if marker.get('neverSkyControl'):need('entire-sampled-run-never-sky',all(s['skyBlocks']==0 and s['resources']['liveUniqueObjects']==0 for s in samples))
    hold=[s for s in samples if s.get('phase')=='long-hold-4096' and s.get('measurement')]
    if hold:
        live=[s['resources']['liveUniqueObjects'] for s in hold]
        need('long-hold-owned-object-count-constant',min(live)==max(live),minimum=min(live),maximum=max(live),samples=len(live))
    process=rows(directory/'process-memory.jsonl');observed=[s for s in process if s.get('status')=='SAMPLED']
    passed=all(x['passed'] for x in checks)
    return dict(status='FINITE_PERFORMANCE_RESOURCE_EVIDENCE_VALID' if passed else 'PERFORMANCE_RESOURCE_OPEN',passed=passed,
        counts=dict(passed=sum(x['passed'] for x in checks),total=len(checks)),checks=checks,stages=stage_rows,memoryTrend=memory_trend(samples),
        processMemory=dict(samples=len(observed),peakWorkingSetBytes=max((s['workingSetBytes'] for s in observed),default=None),peakPrivateBytes=max((s['privateBytes'] for s in observed),default=None)),
        identity=dict(jar=marker['productionJarSha256'],probe=marker['probeJarSha256'],gc=marker['gc'],profile=marker['profile'],neverSky=marker.get('neverSkyControl',False)),
        gpuResourceLifetimeVerified=passed,universalNoLeakClaim=False,universalFpsClaim=False,releaseAccepted=False)
def compare(candidate,control):
    a=evaluate(candidate);b=evaluate(control);ma=read(candidate/'r30-owned.json');mb=read(control/'r30-owned.json')
    keys=['productionJarSha256','probeJarSha256','gc','profile','terrain','heap','renderDistance','maxFps','dhConfiguration']
    same=all(ma[k]==mb[k] for k in keys)
    ka=a['memoryTrend'].get('heapP10SlopeBytesPerMinute');kb=b['memoryTrend'].get('heapP10SlopeBytesPerMinute')
    return dict(candidate=a,neverSky=b,matchedInputs=same,controlPhysicallyNeverSky=mb.get('neverSkyControl') is True,
                observedHeapFloorSlopeDifferenceBytesPerMinute=ka-kb if ka is not None and kb is not None else None,
                interpretation='Measured trend difference only; inspect floor curves, GC and live LOD growth before attributing a leak',releaseAccepted=False)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('--control',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=compare(a.directory,a.control) if a.control else evaluate(a.directory)
    with a.output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    if a.control:print(json.dumps({k:v for k,v in result.items() if k not in {'candidate','neverSky'}},indent=2));return 0 if result['candidate']['passed'] and result['neverSky']['passed'] and result['matchedInputs'] and result['controlPhysicallyNeverSky'] else 1
    print(json.dumps({k:v for k,v in result.items() if k not in {'checks','stages'}},indent=2))
    for x in result['checks']:
        if not x['passed']:print('FAIL',json.dumps(x))
    return 0 if result['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
