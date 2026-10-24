#!/usr/bin/env python3
"""Read-only DH depth/LOD drift diagnostic in an actual entire NeverSky session.

No acceptance waiver or threshold fitting. Demonstrates only what the real
NoSky run and independently read depth/container observations support.
"""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,sys,zipfile
from pathlib import Path
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parent.parent

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('fixture',type=Path)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args();pins={};checks=[];pairs={}
    def pin(path):
        path=Path(path).resolve(strict=True);path.relative_to(ROOT);pins[str(path)]=sha(path);return path
    def read(path):return json.loads(pin(path).read_text(encoding='utf-8'))
    def add(name,passed,**data):checks.append(dict(name=name,passed=bool(passed),**data))
    root=a.fixture.resolve(strict=True);root.relative_to(ROOT);results=root/'results'
    try:
        f=read(root/'fixture.json');r=read(root/'run-result.json');pin(root/'client.log')
        add('true-never-sky-diagnostic-role',f.get('nativeTransparencyControl') is True and f.get('suite')=='transparency'
            and f.get('distantTerrainDiagnostics') is True and f.get('requestedScenes')==[])
        add('finished-without-fatal-or-render-thread-error',r.get('finished') is True and r.get('exitCode')==0
            and r.get('fatalErrors')==[] and r.get('renderThreadViolations')==[])
        jar=pin(f['jar']);add('frozen-main-hash',pins[str(jar)]==f['jarSha256'])
        installed={}
        for path in sorted((root/'mods').glob('*.jar')):
            path=pin(path)
            with zipfile.ZipFile(path) as z:
                m=json.loads(z.read('fabric.mod.json')) if 'fabric.mod.json' in z.namelist() else {'id':path.name}
            installed[m['id']]=pins[str(path)]
        add('actual-installed-main-hash',installed.get('skyboxblock')==f['jarSha256'])
        metadata={path.stem:read(path) for path in sorted(results.glob('trans-*.json'))}
        add('all-51-captures-present',len(metadata)==51)
        counts=[]
        for name,m in metadata.items():
            t=m['transparency'];counts.append(m.get('neverSkyObservedRootFrames',0))
            add(name+':whole-session-zero-private-rendering-counter',m.get('neverSkyObservedRootFrames',0)>0
                and m.get('blocks')==m.get('vertices')==0 and m.get('depthChangedPixels')==0
                and m.get('itrpMaterialWrites') is False and m.get('itrpBackgroundInjected') is False
                and m.get('itrpInjectionCount')==0 and m.get('finalResolved') is False
                and m.get('initialGlError')==m.get('finalGlError')==0
                and t.get('nativeRepeatControl') is True and t.get('skyPresent') is False
                and t.get('actualServerGeometry',{}).get('sky')==t.get('actualClientGeometry',{}).get('sky')==0
                and t.get('actualServerGeometry',{}).get('highSky')==t.get('actualClientGeometry',{}).get('highSky')==0)
        for front in ('none','glass','water'):
            first='trans-open-'+front+'-none-marker';last='trans-removed-'+front
            x,y=metadata[first],metadata[last];dx,dy=x.get('distantTerrain'),y.get('distantTerrain')
            rec=dict(before=first,after=last,actualFrames=[x.get('irisFrameCounter'),y.get('irisFrameCounter')],depth={},
                caveat='Same pose/world phase/jitter does not freeze temporal history. A changed depth plus changed LOD list supports a DH geometry candidate, not a unique shader attribution.')
            pairs[front]=rec
            add(front+':same-camera-projection-jitter-phase',all(x[k]==y[k] for k in ('camera','inverseDisplayViewProjection','rasterScale','rasterJitter'))
                and all(x['displaySky'][k]==y['displaySky'][k] for k in ('timeSource','phase','moonPhase','rain','thunder')))
            if not isinstance(dx,dict) or not isinstance(dy,dict):raise ValueError('Missing actual DH snapshot')
            add(front+':same-in-process-pipeline-owner',dx.get('clientLevelIdentity')==dy.get('clientLevelIdentity')
                and dx.get('pipelineIdentity')==dy.get('pipelineIdentity') and dx.get('samePipelineAfterSampling') is True
                and dy.get('samePipelineAfterSampling') is True)
            before=np.asarray(Image.open(pin(results/(first+'.png'))).convert('RGB')).astype(int)
            after=np.asarray(Image.open(pin(results/(last+'.png'))).convert('RGB')).astype(int)
            delta=np.abs(before-after);changed=np.max(delta,axis=-1)>2
            rec['rawImage']=dict(mae=float(delta.mean()),p99=float(np.percentile(delta,99)),maximum=int(delta.max()),changedPixels=int(changed.sum()))
            coverage_flips=0
            for role in ('all','opaque'):
                q0=dx.get('depthTextures',{}).get(role,{});q1=dy.get('depthTextures',{}).get(role,{})
                if q0.get('status')!='sampled' or q1.get('status')!='sampled':
                    rec['depth'][role]=dict(status='UNAVAILABLE',before=q0,after=q1);continue
                p0=pin(results/q0['rawFile']);p1=pin(results/q1['rawFile'])
                if sha(p0)!=q0['rawSha256'] or sha(p1)!=q1['rawSha256']:raise ValueError('Depth bytes changed after snapshot')
                if (q0['width'],q0['height'])!=(q1['width'],q1['height']):raise ValueError('Actual DH depth grids differ')
                d0=np.fromfile(p0,dtype='<f4').reshape(q0['height'],q0['width'])
                d1=np.fromfile(p1,dtype='<f4').reshape(q1['height'],q1['width'])
                valid=np.isfinite(d0)&np.isfinite(d1);flip=valid&((d0<1)!=(d1<1));coverage_flips+=int(flip.sum())
                item=dict(status='SAMPLED',grid=[q0['width'],q0['height']],coverageFlipPixels=int(flip.sum()),
                    depthBitChangedPixels=int((d0.view('<u4')!=d1.view('<u4')).sum()),
                    beforeCoverage=int((np.isfinite(d0)&(d0<1)).sum()),afterCoverage=int((np.isfinite(d1)&(d1<1)).sum()),
                    physicalMaskBefore=q0['physicalMask'],physicalMaskAfter=q1['physicalMask'])
                if q0.get('activeCropMappingVerified') and q1.get('activeCropMappingVerified'):
                    x0,y0,w,h=q0['activeCropInTexture'];active=np.flipud(flip[y0:y0+h,x0:x0+w])
                    item['activeCoverageFlipPixels']=int(active.sum())
                    item['activeImageYRowsWithCoverageFlips']=np.flatnonzero(active.any(axis=1)).tolist()
                rec['depth'][role]=item
            sx,sy=dx.get('dhState',{}),dy.get('dhState',{})
            fingerprints=[sx.get('lastBuiltFingerprintSha256'),sy.get('lastBuiltFingerprintSha256')]
            enabled_fingerprints=[sx.get('enabledFingerprintSha256'),sy.get('enabledFingerprintSha256')]
            known=all(isinstance(v,str) for v in fingerprints)
            rec['dhList']=dict(beforeStatus=sx.get('status'),afterStatus=sy.get('status'),fingerprints=fingerprints,
                lastBuiltCounts=[sx.get('lastBuiltRenderBufferCount'),sy.get('lastBuiltRenderBufferCount')],
                changed=known and fingerprints[0]!=fingerprints[1],enabledFingerprints=enabled_fingerprints,
                enabledChanged=all(isinstance(v,str) for v in enabled_fingerprints) and enabled_fingerprints[0]!=enabled_fingerprints[1],
                provenance='last built list, not per-draw proof; enabled list recorded separately')
            rec['supportsChangingDhGeometryCandidate']=coverage_flips>0 and rec['dhList']['changed']
            rec['variationWithoutAnyModPrivateRendering']=bool(changed.any())
        error=None
    except (OSError,KeyError,ValueError,TypeError,IndexError,zipfile.BadZipFile) as e:
        error=str(e);add('complete-real-diagnostic-inputs',False,error=error)
    report=dict(kind='never-sky-distant-terrain-drift-diagnostic',passed=bool(checks) and all(c['passed'] for c in checks),
        passedChecks=sum(c['passed'] for c in checks),totalChecks=len(checks),checks=checks,pairs=pairs,inputs=pins,error=error,
        scope='Input/NoSky diagnostic validity only, NOT restored-world acceptance or a waiver. Original raw/lowpass caps remain unchanged.',
        toolSha256=sha(Path(__file__)))
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with a.output.open('x',encoding='utf-8') as out:json.dump(report,out,indent=2,allow_nan=False)
    print(f"{report['passedChecks']}/{report['totalChecks']} {'VALID_DIAGNOSTIC' if report['passed'] else 'NOT_VERIFIED'}",a.output)
    for key,value in pairs.items():print(key,json.dumps({k:v for k,v in value.items() if k!='depth'}))
    for c in checks:
        if not c['passed']:print('FAIL',json.dumps(c))
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
