#!/usr/bin/env python3
"""Serial GPU validation of the frozen test.6 candidate, with no portal mod work."""
from pathlib import Path
import json,subprocess,sys,time
ROOT=Path(__file__).resolve().parent.parent
PY=sys.executable
JAR=ROOT/'build/libs/skyboxblock-1.0.2-test.6.jar'
SHADER='iterationRP Alpha 0.8.31.zip'
records=[]
def run(label,tool,args):
    log=ROOT/'build/logs'/(label+'.log');log.parent.mkdir(parents=True,exist_ok=True)
    cmd=[PY,str(ROOT/'tools'/tool),*map(str,args)]
    print('START',label,flush=True);start=time.monotonic()
    with log.open('xb') as f:rc=subprocess.run(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT).returncode
    row={'label':label,'command':cmd,'exitCode':rc,'seconds':round(time.monotonic()-start,2),'log':str(log.relative_to(ROOT))}
    records.append(row);print('END',label,'exit',rc,'seconds',row['seconds'],flush=True)
    if rc:print('\n'.join(log.read_text(encoding='utf-8',errors='replace').splitlines()[-12:]),flush=True)
    return rc

def fixture(label,profile,suite,extra=(),jar=JAR):
    args=['--profile',profile,'--label',label,'--jar',jar,'--suite',suite,'--timeout','420']
    if profile=='iris':args+=['--shader',SHADER,'--shader-settings','instance','--shader-option','MOTION_BLUR=false']
    return run(label,'render-regression.py',args+list(extra))

reference='r26-test6-reference-final-r5'
if fixture(reference,'vanilla','reference')==0:
    run(reference+'-oracle','check-vanilla-reference-r26.py',[
        'build/render-regression/'+reference,'--expect','23','--output','build/reports/'+reference+'.json'])

bugs=['bug-panel-empty','bug-panel-sky-hand','bug-panel-solid-hand','bug-panel-torch-hand',
      'bug-panel-return-empty','bug-panel-offhand','bug-panel-dual-hand','bug-pillar-near-empty',
      'bug-pillar-far-empty','bug-pillar-far-sky-hand','bug-pillar-far-solid-hand','bug-pillar-farther-empty',
      'bug-pillar-side-left','bug-pillar-side-right','bug-pillar-turned','bug-pillar-resized-hidden',
      'bug-pillar-resized-side','bug-pillar-restored-side','bug-clean']
align=['align-solid-no-sky','align-solid-sky','align-sky-selected','align-other-solid','align-oblique',
       'align-resized','align-reload','align-sky-removed','align-clean']
for env,profile,extra in [('vanilla','vanilla',[]),('iris','iris',['--shader-option','FSR2_SCALE=-1']),
                         ('fsr','iris',['--shader-option','FSR2_SCALE=2'])]:
    label='r26-test6-smoke-'+env+'-r5'
    if fixture(label,profile,'all',['--scenes',','.join(bugs+align),*extra])==0:
        results='build/render-regression/'+label+'/results'
        run(label+'-world','check-world-regression.py',[results,'--suite','bugs','--output','build/reports/'+label+'-world.json'])
        run(label+'-alignment','check-alignment-regression.py',[results,'--output','build/reports/'+label+'-alignment.json'])

trans='r26-test6-transparency-iris-r5'
if fixture(trans,'iris','transparency',['--shader-option','FSR2_SCALE=-1'])==0:
    run(trans+'-oracle','check-transparency-regression.py',['build/render-regression/'+trans+'/results',
        '--output','build/reports/'+trans+'.json'])

for variant,jar in [('before',ROOT/'build/render-regression/r26-test6-blue-iris-r3/mods/skyboxblock-1.0.2-test.6.jar'),('after',JAR)]:
    fixture('r26-test6-blue-fsr-'+variant+'-r5','iris','blue',['--shader-option','FSR2_SCALE=2'],jar)
run('r26-test6-blue-fsr-check-r5','check-blue-light-r26.py',[
    'build/render-regression/r26-test6-blue-fsr-before-r5','build/render-regression/r26-test6-blue-fsr-after-r5',
    '--output','build/reports/r26-blue-fsr-r5.json'])
summary={'kind':'R26-scoped-validation-not-formal-release-gate','stages':records,
         'allStageExitsZero':all(r['exitCode']==0 for r in records),'portalTestsPerformed':False,
         'fullSettledTransparencyProtocol':False,'fullPriorTest5GateRevalidated':False}
path=ROOT/'build/reports/r26-validation-summary-r5.json'
with path.open('x',encoding='utf-8') as f:json.dump(summary,f,indent=2)
print('SUMMARY',path,'allStageExitsZero',summary['allStageExitsZero'],flush=True)
raise SystemExit(0 if summary['allStageExitsZero'] else 1)
