#!/usr/bin/env python3
"""R33: actual hand-depth color, physical exposure, sampler restoration and unchanged sky RGB."""
from pathlib import Path
import argparse,importlib.util,json
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'tools'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
hand=module('r28_hand','check-hand-exposure-r28.py')
pano=module('r32_pano','check-r32-motion.py')

def main():
    p=argparse.ArgumentParser();p.add_argument('instance',type=Path);p.add_argument('--expect',choices=['fixed','negative','control'],default='fixed');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    instance=a.instance;directory=instance/'results';fixture=hand.read(instance/'fixture.json');run=hand.read(instance/'run-result.json');material=fixture['handMaterial'];dark=material in ['black','galaxy','apocalypse','mixed'];checks=[];rows=[]
    def need(name,ok,scene=None,**info):checks.append(dict(name=name,passed=bool(ok),scene=scene,**info))
    need('full fifteen-scene timeline',run['exitCode']==0 and run['finished'] and run['scenes']==15)
    external=[e for e in run['errors'] if '[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in e]
    need('no rendering runtime errors',not run['fatalErrors'] and not run['renderThreadViolations'] and len(external)==len(run['errors']),externalNetworkErrors=external)
    need('live exposure not frozen',not fixture['freezeShaderTime'])
    need('actual scene identities',{p.stem for p in directory.glob('hand-*.json')}==set(hand.SCENES))
    need('actual installed JAR',hand.sha(next((instance/'mods').glob('skyboxblock-*.jar')))==fixture['jarSha256'])
    for scene in hand.SCENES:
        data=hand.read(directory/(scene+'.json'));e=data['handExposure'];exposure=e['exposure'];mask=hand.hand_mask(directory,scene,data);image=hand.rgb(directory/(scene+'.png'));pixels=image[mask]
        white=float(np.all(pixels>=248,axis=1).mean()) if len(pixels) else 1.;span=float(np.mean(pixels.max(axis=1)-pixels.min(axis=1))) if len(pixels) else 0.
        mean=pixels.mean(axis=0).tolist() if len(pixels) else []
        pre=hand.rgb(directory/(scene+'-pre-resolve.png'));delta=int(np.abs(pre-image)[mask].max()) if len(pixels) else 255
        need('real hand depth population',len(pixels)>5000,scene,pixels=len(pixels))
        need('hand never repainted by final resolve',delta==0,scene,maximumDifference=delta)
        need('GL zero',data['initialGlError']==data['finalGlError']==0,scene)
        need('Iris state restored',data['irisMainBoundBefore']==data['irisMainBoundAfter'] and data['irisOverridesBefore']==data['irisOverridesAfter'],scene)
        need('true daylight and hand',e['hudAndHandEnabled'] and e['timeOfDay']%24000==6000 and e['skyLight']==15 and e['blockLight']==0,scene)
        need('owned physical exposure read',exposure is not None and exposure.get('ownerVerified') and 'error' not in exposure,scene)
        values=np.array([exposure.get('averageLuminance',np.nan),exposure.get('exposureMultiplier',np.nan)]) if exposure else np.array([np.nan,np.nan])
        need('finite unsaturated physical meter',np.isfinite(values).all() and (values>0).all() and (values<60000).all(),scene,values=values.tolist())
        actual=e.get('actualTotalSkyBlocks',e['actualSkyBlocks']);expected=e['expectedShellBlocks'] if material not in ['air','glass','stone'] else 0
        if scene=='hand-removed-00':need('removal acknowledged or in flight',actual in (0,expected),scene,actual=actual)
        else:need('actual shell geometry',actual==(0 if scene=='hand-removed-10' else expected),scene,actual=actual,expected=expected)
        if a.expect!='negative':
            need('hand not white clipped',white<.02,scene,whiteFraction248=white)
            need('skin chroma retained',span>8,scene,meanChannelSpan=span)
            if dark and data['blocks']>0 and scene not in ['hand-removed-00']:
                need('meter-only proxy active and restored',e['meterProxyActive'] and e['meterSamplerRestored'],scene)
        if not dark:need('unrelated original/control does not activate proxy',not e.get('meterProxyActive',False),scene)
        skyMetric=None
        camera=np.array(data['camera'])
        inside=(-5<camera[0]<6 and 180<camera[1]<186 and 1<camera[2]<12)
        if inside and scene not in ['hand-removed-00','hand-removed-10'] and material in ['black','galaxy','apocalypse','mixed']:
            h,w=image.shape[:2];r=pano.rays(data,w,h)
            coverage=np.asarray(Image.open(directory/(scene+'-depth-change.png')).convert('L').resize((w,h),Image.Resampling.NEAREST))>0
            skyMask=pano.base.interior(coverage & ~mask,8);skyMask[:12]=False;skyMask[-48:]=False;skyMask[:,:12]=False;skyMask[:,-12:]=False
            if material=='mixed':
                boundsLow=np.array([-5.,180.,1.]);boundsHigh=np.array([6.,186.,12.])
                with np.errstate(divide='ignore',invalid='ignore'):t=np.where(r>0,(boundsHigh-camera)/r,(boundsLow-camera)/r)
                axis=np.argmin(t,axis=-1);styleIndex=np.zeros((h,w),np.int8)
                styleIndex[(axis==2)&(r[...,2]>0)]=1;styleIndex[(axis==0)&(r[...,0]<0)]=2
                reference=np.zeros((h,w,3),float)
                for number,style in [(1,'apocalypse'),(2,'galaxy')]:
                    colors=pano.base.expected(style,r);reference[styleIndex==number]=colors[styleIndex==number]
                # Depth discontinuities along the intersection of different materials are not a sky-color oracle.
                for number in [0,1,2]:
                    region=styleIndex==number;skyMask[region & ~pano.base.interior(region,8)]=False
            else:reference=pano.base.expected(material,r)
            skyMetric=pano.base.metrics(image,reference,skyMask)
            need('sky display color preserved',skyMetric['passed'],scene,**{k:v for k,v in skyMetric.items() if k!='passed'})
        rows.append(dict(scene=scene,whiteFraction248=white,meanHandRgb=mean,meanHandChannelSpan=span,handPixels=len(pixels),handResolveDifference=delta,
            averageLuminance=float(values[0]),exposureMultiplier=float(values[1]),milliseconds=e['elapsedSinceTransitionMilliseconds'],actualTotalSkyBlocks=actual,
            proxy=e.get('meterProxyActive',False),restored=e.get('meterSamplerRestored',False),skyColor=skyMetric))
    by={r['scene']:r for r in rows}
    for prefix in ['hand-inside','hand-reenter']:
        late=by[prefix+'-30'];early=by[prefix+'-10']
        need('real thirty-second residence',late['milliseconds']>=30000,prefix,milliseconds=late['milliseconds'])
        if a.expect!='negative':
            need('late hand color stable',max(abs(x-y) for x,y in zip(late['meanHandRgb'],early['meanHandRgb']))<8,prefix)
            need('late physical meter stable',max(late['averageLuminance'],early['averageLuminance'])/min(late['averageLuminance'],early['averageLuminance'])<1.2,prefix)
    if a.expect=='negative':
        for scene in ['hand-inside-10','hand-inside-30','hand-reenter-30']:
            need('negative reproduces white hand',by[scene]['whiteFraction248']>.9 and by[scene]['exposureMultiplier']>100,scene)
        need('negative proxy disabled',all(not r['proxy'] for r in rows))
    failures=[c for c in checks if not c['passed']]
    report=dict(kind='R33-hand-exposure-and-meter-isolation',expect=a.expect,material=material,jarSha256=fixture['jarSha256'],scenes=len(rows),checks=len(checks),passedChecks=len(checks)-len(failures),failedChecks=len(failures),passed=not failures,
        failures=failures,measurements=rows,validation=checks,externalNetworkErrors=external)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['measurements','validation']},indent=2))
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
