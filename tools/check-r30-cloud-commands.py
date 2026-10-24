#!/usr/bin/env python3
"""Verify actual client-command results, persistent values, GPU uniforms, range growth and release balance."""
from __future__ import annotations
import argparse,importlib.util,json,math
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_base_check',ROOT/'tools/check-r30-probe.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
def evaluate(directory):
    directory=directory.resolve(strict=True); data=json.loads((directory/'r30-results/run-summary.json').read_text(encoding='utf-8'))
    original=base.verify(directory); checks=[]; rows=[]
    def need(name,value,**extra):checks.append(dict(name=name,passed=bool(value),**extra))
    need('base-input-and-runtime-evidence',original['passed'],counts=original['counts'])
    for phase in data['phases']:
        name=phase['name']; request=phase['request']; cloud=phase['cloudState']
        expected=[request['expectedCloudFadeStart'],request['expectedCloudFadeEnd']]
        actual=[cloud['vanillaCloudFadeStart'],cloud['vanillaCloudFadeEnd']]
        gpu=[cloud['CloudFogStart'],cloud['CloudFogEnd']]
        saved=[float(cloud['persistedProperties'][key]) for key in ('vanillaCloudFadeStart','vanillaCloudFadeEnd')]
        need(name+':active-config',all(abs(a-b)<1e-4 for a,b in zip(expected,actual)))
        need(name+':actual-GPU-uniforms',all(abs(a-b)<1e-4 for a,b in zip(expected,gpu)))
        need(name+':saved-properties',all(abs(a-b)<1e-4 for a,b in zip(expected,saved)))
        need(name+':real-client-command-result',all(c['result']==request.get('expectedCommandResult',1) for c in phase['clientCommandResults']))
        need(name+':clouds-actually-drawn',cloud['renderedCloudDraws']>0)
        p=cloud['actualGpuCloudProjectionColumnMajor']; far=float(p[14]/(p[10]+1)) if abs(p[10]+1)>1e-12 else math.inf
        need(name+':cloud-far-clip-covers-fade-end',far>=expected[1],observedFar=far)
        life=phase['gpuLifetime']; observed=phase['resources']['liveUniqueObjects']
        need(name+':GPU-lifetime-reconciles-inventory',life['balancedCount'] and life['live']==observed and life['unknownDeletes']==0 and life['duplicateCreates']==0 and life['droppedEvents']==0)
        need(name+':retired-GPU-names-really-gone',life.get('retiredNameStillLiveWithoutNewAllocation')==[] and life.get('trackedNameNoLongerLive')==[])
        storage=sum(o.get('bufferBytes',0) for o in phase['resources']['objects'] if o.get('label')=='cloud.mesh.vbo')
        rows.append(dict(name=name,config=actual,gpu=gpu,persisted=saved,farClip=far,cloudVertexBufferBytes=storage,liveOwnedObjects=observed))
    lookup={p['name']:p for p in rows}
    need('geometry-really-expands-for-768',lookup['cloud-far-768']['cloudVertexBufferBytes']>lookup['cloud-default']['cloudVertexBufferBytes'])
    need('geometry-really-expands-for-1536',lookup['cloud-far-1536']['cloudVertexBufferBytes']>lookup['cloud-far-768']['cloudVertexBufferBytes'])
    need('reset-restores-original-mesh-storage',lookup['cloud-reset']['cloudVertexBufferBytes']==lookup['cloud-default']['cloudVertexBufferBytes'])
    for path in sorted((directory/'r30-results').glob('post-release-*.json')):
        record=json.loads(path.read_text(encoding='utf-8'));life=record['gpuLifetime']
        need(path.name+':all-observed-owned-allocations-released',life['created']==life['deleted'] and life['live']==record['liveUniqueObjects']==0 and life['unknownDeletes']==life['duplicateCreates']==0)
    passed=all(x['passed'] for x in checks)
    return dict(status='CLOUD_COMMANDS_VERIFIED' if passed else 'CLOUD_COMMANDS_OPEN',passed=passed,counts=dict(passed=sum(x['passed'] for x in checks),total=len(checks)),checks=checks,rows=rows,sourceBuildIdentity=original['actualMods'],releaseAccepted=False)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=evaluate(a.directory)
    with a.output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    print(json.dumps({k:v for k,v in result.items() if k not in {'checks','sourceBuildIdentity'}},indent=2))
    for c in result['checks']:
        if not c['passed']:print('FAIL',json.dumps(c))
    return 0 if result['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
