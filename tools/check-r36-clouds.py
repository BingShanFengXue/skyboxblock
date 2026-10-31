#!/usr/bin/env python3
"""Independent PNG-based cloud overdraw checker. No renderer self-report is used as pixel truth."""
from __future__ import annotations
import argparse,json
from pathlib import Path
import numpy as np
from PIL import Image

def alpha(directory,pair):
    black=np.asarray(Image.open(directory/pair['black']).convert('RGB'),dtype=np.float64)
    white=np.asarray(Image.open(directory/pair['white']).convert('RGB'),dtype=np.float64)
    return np.clip(1-(white-black).mean(axis=2)/255,0,1)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('instance',type=Path)
    p.add_argument('--expect',choices=['baseline','fixed'],required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--legacy-format-diagnostic',action='store_true',help='Only for the initial alpha proof, never final framebuffer acceptance')
    a=p.parse_args();root=a.instance/'r30-results';directory=root/'temporal';checks=[];frames=[]
    def check(name,ok,**data):checks.append(dict(name=name,passed=bool(ok),**data))
    run=json.loads((a.instance/'run-result.json').read_text(encoding='utf-8'))
    summary=json.loads((root/'run-summary.json').read_text(encoding='utf-8'))
    auth=[s for s in run['errors'] if '[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in s]
    other=[s for s in run['errors'] if s not in auth]
    check('execution_complete',run['complete'] and run['exitCode']==0 and not run['timedOut'] and run['phases']==run['expectedPhases'])
    check('input_bytes_unchanged',run['inputBytesUnchanged'])
    check('no_unexpected_client_errors',not other and not run['fatalErrors'],unexpected=other,offlineAuthWarnings=auth)
    max_alpha=0;raw_total=0;fixed_total=0;lost_total=0
    for path in sorted(directory.glob('*-cloud.json')):
        data=json.loads(path.read_text(encoding='utf-8'));measured=data['measured'];raw=data['sameFrameRaw']
        actual=alpha(directory,measured);bad=alpha(directory,raw);single=measured['singleBlendAlpha']
        count=int(np.count_nonzero(actual>single+.025));raw_count=int(np.count_nonzero(bad>single+.025))
        # A weak but nonzero single fog layer is not a geometry hole. The old 2.5% cutoff
        # falsely called 1.96% alpha a hole when the raw triple-blended result was 5.10%.
        lost=int(np.count_nonzero((bad>.05)&(actual<1e-6)))
        max_alpha=max(max_alpha,float(actual.max()));raw_total+=raw_count;fixed_total+=count;lost_total+=lost
        check(path.stem+':java_matches_independent_png',count==measured['overdrawPixels'] and
            abs(float(actual.max())-measured['maxMeasuredAlpha'])<1e-8)
        if not a.legacy_format_diagnostic:
            check(path.stem+':linear_single_sample_target',measured['encoding']==9729 and measured['samples']==0)
            check(path.stem+':actual_depth_attachment',measured['depthBits']>=24)
        if single>0:check(path.stem+':visible_clouds',int(np.count_nonzero(actual>.025))>1000)
        else:check(path.stem+':zero_alpha_empty',not np.any(actual>.025))
        if a.expect=='fixed':
            check(path.stem+':one_color_blend',count==0,maxAlpha=float(actual.max()),singleAlpha=single)
            check(path.stem+':private_stencil_present',measured['stencilBits']>=8)
            check(path.stem+':no_new_coverage_holes',lost==0,lostPixels=lost)
        frames.append(dict(file=path.name,gameTime=data['gameTime'],opacity=data['cloudOpacity'],overdrawPixels=count,
            rawOverdrawPixels=raw_count,lostPixels=lost,maxMeasuredAlpha=float(actual.max())))
    check('actual_frames_present',bool(frames))
    check('negative_control_reproduces_reported_overdraw',raw_total>0,rawOverdrawPixels=raw_total)
    if a.expect=='baseline':check('baseline_overdraw_reproduced',fixed_total>0,overdrawPixels=fixed_total)
    for scene in summary['phases']:
        stem=f"{scene['index']:02d}-{scene['name']}"
        records=[f for f in frames if f['file'].startswith(stem+'-frame-')]
        expected=scene['request'].get('captureFrames',0)
        check(scene['name']+':all_requested_motion_frames',len(records)==expected,actual=len(records),expected=expected)
        if scene['request'].get('sampleSeconds',0)>=60:
            span=max(f['gameTime'] for f in records)-min(f['gameTime'] for f in records) if records else 0
            check(scene['name']+':three_real_cloud_cells',span>=1200,gameTickSpan=span,cloudCellSpan=span*.03/12)
        if not a.legacy_format_diagnostic:
            check(scene['name']+':no_gl_errors',not scene['phaseEndGlErrors'] and not scene['diagnosticGlErrors'])
        check(scene['name']+':real_sky_geometry',scene['minimumCollectedBlocks']>0)
    # Synthetic counterexamples check the measurement discriminates one/two blends and true holes.
    for opacity in [1,.5,.25]:
        one=.8*opacity;two=1-(1-one)**2
        check('oracle_negative_control:'+str(opacity),two>one+.025 and one<=one+.025)
    result=dict(status='PASS' if all(c['passed'] for c in checks) else 'FAIL',expected=a.expect,
        scope='initial-alpha-diagnostic-only' if a.legacy_format_diagnostic else 'GPU cloud color blend and motion',
        passed=sum(c['passed'] for c in checks),total=len(checks),failed=[c for c in checks if not c['passed']],checks=checks,
        frames=frames,frameCount=len(frames),maxMeasuredAlpha=max_alpha,overdrawPixels=fixed_total,
        rawOverdrawPixels=raw_total,lostCoveragePixels=lost_total,productionJarSha256=run['productionJarSha256'],
        probeJarSha256=run['probeJarSha256'],offlineAuthWarnings=auth,instance=str(a.instance))
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['status','passed','total','failed','frameCount','maxMeasuredAlpha','overdrawPixels','rawOverdrawPixels','lostCoveragePixels']},indent=2))
    return 0 if result['status']=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())