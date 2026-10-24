#!/usr/bin/env python3
"""Bind scoped R26 checks to actual frames and runtime logs; do not waive historical gates."""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parent.parent
EXPECTED='c1ceb286dbf106ca86cec9ca5b44f4f2acd7138bf3467a9574b178c957bec0fb'
labels=['r26-test6-reference-final-r5',*[f'r26-test6-smoke-{x}-r5' for x in ['vanilla','iris','fsr']],
        'r26-test6-transparency-iris-r5','r26-test6-blue-iris-r5','r26-test6-blue-fsr-after-r5']
rows=[]
def read(p):return json.loads(p.read_text(encoding='utf-8'))
for label in labels:
    instance=ROOT/'build/render-regression'/label
    runtime=read(instance/'run-result.json');frames=[read(p) for p in sorted((instance/'results').glob('*.json'))]
    geometry=[m for m in frames if m.get('blocks',0)>0]
    iris='iris' in label or 'fsr' in label
    failures=[]
    for m in frames:
        if m.get('initialGlError')!=0 or m.get('finalGlError')!=0:failures.append(m['scene']+':GL')
        for key in ['irisMainBound','irisOverrides']:
            if m.get(key+'Before')!=m.get(key+'After'):failures.append(m['scene']+':'+key)
        if iris and m.get('blocks',0)>0:
            if not(m.get('finalResolved') and m.get('itrpMaterialWrites') and m.get('itrpBackgroundInjected')
                   and m.get('itrpExposureTexture',0)>0 and m.get('itrpBridgeFailure') is None):
                failures.append(m['scene']+':actual-exposure-bridge')
    installed=instance/'mods/skyboxblock-1.0.2-test.6.jar'
    bound=hashlib.sha256(installed.read_bytes()).hexdigest()==EXPECTED
    rows.append({'fixture':label,'scenes':len(frames),'geometryScenes':len(geometry),'runtime':runtime,
                 'candidateBytesBound':bound,'frameFailures':failures,'passed':bound and not failures
                 and runtime['finished'] and runtime['exitCode']==0 and not runtime['errors']
                 and not runtime['fatalErrors'] and not runtime['renderThreadViolations']})
checks={}
for kind in ['world','alignment']:
    data=[read(ROOT/f'build/reports/r26-test6-smoke-{e}-r5-{kind}.json')[0] for e in ['vanilla','iris','fsr']]
    checks[kind]={'passedChecks':sum(d['passedChecks'] for d in data),'totalChecks':sum(d['totalChecks'] for d in data),
                  'passed':all(d['passed'] for d in data)}
trans=read(ROOT/'build/reports/r26-test6-transparency-iris-r5.json')
checks['transparency']={'passedChecks':trans['passedChecks'],'totalChecks':trans['totalChecks'],'passed':trans['passed'],
                        'failed':[c for c in trans['checks'] if not c['passed']],
                        'settled15SecondProtocol':False}
ref=read(ROOT/'build/reports/r26-test6-reference-final-r5.json')
checks['vanillaBaseSky']={'passed':ref['passed'],'scenes':len(ref['scenes']),
                         'exactScenes':sum(s['differentPixels']==0 for s in ref['scenes']),
                         'maximumChannelError':max(s['maximumChannelError'] for s in ref['scenes'])}
result={'kind':'R26-scoped-runtime-bindings','candidateJarSha256':EXPECTED,'fixtures':rows,
        'candidateCaptures':sum(r['scenes'] for r in rows),'checks':checks,'runtimeBindingPassed':all(r['passed'] for r in rows),
        'releaseAccepted':False,'portalWorkStopped':True,'fullHistoricalReleaseGateRevalidated':False}
with (ROOT/'build/reports/r26-runtime-bindings.json').open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
print(json.dumps({'candidateCaptures':result['candidateCaptures'],'runtimeBindingPassed':result['runtimeBindingPassed'],
                  'checks':checks,'frameFailures':[{r['fixture']:r['frameFailures']} for r in rows if r['frameFailures']]},indent=2))
raise SystemExit(0 if result['runtimeBindingPassed'] else 1)
