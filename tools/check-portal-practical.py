#!/usr/bin/env python3
"""Practical portal check after the independent R23 audit.

Runs and retains the complete original strict report unchanged. Separately
records limited stripe-layout diagnostics (NOT full geometry), correct
pre-IP-composite child scope, and mandatory actual fixture/mode/config evidence. No threshold waiver,
resampling, fitted ROI, release-gate override or historical report rewriting.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import numpy as np

SPEC=importlib.util.spec_from_file_location('portal_practical_evidence',Path(__file__).with_name('portal-practical-evidence.py'))
E=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(E)
O=E.ORACLE


def check(baseline,empty,sky):
    strict=O.check(Path(baseline),Path(empty),Path(sky))
    replacements={};additions=[];observations={};errors=[]
    def add(group,name,passed,**metrics):additions.append(dict(group=group,name=name,passed=bool(passed),**metrics))
    for group,path in [('base',baseline),('empty',empty),('sky',sky)]:
        try:
            d=O.load(Path(path));m=d['manifest'];s=d['summary'];run=d['run']
            config=json.loads((d['fixture']/'config/immersive_portals.json').read_text(encoding='utf-8'))
            aliases={'crossPortalView':'enableCrossPortalView','clipping':'enableClippingMechanism','depthClamp':'useDepthClampForPortalRendering'}
            expected={aliases.get(k,k):v for k,v in m['portalConfig'].items()}
            add(group,'actual-IP-config',all(config.get(k)==v for k,v in expected.items()),actual={k:config.get(k) for k in expected})
            settings=O.canonical_settings(m['settings'],m['shaderSha256'])
            profile=dict(iris=bool(m['shader']),shaderHash=m['shaderSha256'],fsrOption=int(settings.get('FSR2_SCALE',-1)),
                customResolution=settings.get('CUSTOM_RENDER_RESOLUTION','false')=='true')
            observations[group]=dict(runtimeErrors=run['errors'],fatalErrors=run['fatalErrors'],
                renderThreadViolations=run.get('renderThreadViolations',[]),cleanRuntime=not run['errors'],settings=settings)
            expected_renderer=('qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer'
                if config['compatibilityRenderMode'] else 'qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer')
            for scene in O.SCENES:
                meta=d['metadata'][scene];pixels=d['pictures'][scene]
                ok,metrics=E.portal_protocol(meta,s)
                add(group,scene+':actual-mode-and-physical-evidence',ok,**metrics)
                if profile['iris']:
                    observations_for_mode=[O.harness_root(meta),*meta['views']]
                    add(group,scene+':actual-shader-portal-renderer',all(v['rendererClass']==expected_renderer
                        and v['pipelineClass']=='net.irisshaders.iris.pipeline.IrisRenderingPipeline'
                        and v['rasterHints']['irisActive'] is True for v in observations_for_mode))
                g=O.geometry(meta,profile)
                if group!='sky' and scene in O.FIXED:
                    ok,metrics=E.stripe_layout_evidence(pixels,g)
                    observations[group].setdefault('stripeLayoutDiagnostics',{})[scene]=dict(layoutConsistent=ok,**metrics)
                    # Never replace the strict gate with red/non-red layout: missing
                    # pale material can expose a non-red sky and pass that diagnostic.
                if not profile['iris']:continue
                for frame in meta['skyViews']:
                    if frame['shadow'] or not frame['portal'] or frame['imageStage']!='iris-final':continue
                    if not frame['preViewImage'] or not frame['postViewImage']:continue
                    region,pillar=E.intermediate_regions(meta,g,frame)
                    actual=region if group=='sky' and scene!='08-clean' and frame['drawn'] and frame['vertices']>0 else np.zeros_like(region)
                    pre=O.rgb(O.safe_child(d['root'],frame['preViewImage']));post=O.rgb(O.safe_child(d['root'],frame['postViewImage']))
                    reference=O.rgb(O.safe_child(d['root'],frame['referenceDisplay'])) if actual.any() else None
                    for result in O.view_image_checks(pre,post,actual,pillar,reference):
                        name=scene+':'+result.pop('name')+':'+str(frame['viewId'])
                        replacements[(group,name)]=dict(group=group,name=name,stageScope='full-independent-child-before-IP-stencil-composite',**result)
        except (ValueError,KeyError,TypeError,IndexError,OSError) as exc:
            errors.append(dict(group=group,error=str(exc)))
            add(group,'complete-practical-evidence',False,error=str(exc))
    effective=[];used=[]
    for c in strict['checks']:
        key=(c['group'],c['name'])
        if key in replacements:
            effective.append(replacements[key]);used.append(dict(group=c['group'],originalName=c['name'],
                originalPassed=c['passed'],newName=replacements[key]['name'],newPassed=replacements[key]['passed']))
        else:effective.append(c)
    effective.extend(additions)
    return dict(kind='practical-portal-compatibility-not-full-release-acceptance',schemaVersion=1,
        practicalPassed=None,checkedClaimsPassed=bool(effective) and all(c['passed'] for c in effective),releaseAccepted=False,
        automaticAcceptanceReady=False,
        manualReviewRequired='Pale-material geometry is not proved by color layout; actual original images require bounded manual review. No automatic practical/full release PASS is signed.',
        scope='same-overworld pure translation; real outward/return; stage-aware private sky and ordinary structure; no rotation/scaling/nesting claim',
        strictOracleSha256=O.sha(Path(__file__).with_name('check-portal-regression.py')),
        evidenceHelperSha256=O.sha(Path(__file__).with_name('portal-practical-evidence.py')),
        originalStrictPassed=strict['passed'],originalStrictPassedChecks=strict['passedChecks'],originalStrictTotalChecks=strict['totalChecks'],
        originalStrictFailures=[c for c in strict['checks'] if not c['passed']],originalStrictReport=strict,
        semanticReplacements=used,observations=observations,errors=errors,checks=effective,
        passedChecks=sum(c['passed'] for c in effective),totalChecks=len(effective))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('baseline','empty','sky','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();r=check(args.baseline,args.empty,args.sky)
    with args.output.open('x',encoding='utf-8') as f:json.dump(r,f,indent=2,allow_nan=False);f.write('\n')
    print(json.dumps({k:r[k] for k in ('practicalPassed','releaseAccepted','passedChecks','totalChecks','originalStrictPassedChecks','originalStrictTotalChecks')}))
    for c in r['checks']:
        if not c['passed']:print(json.dumps(c))
    return 0 if r['practicalPassed'] else 1

if __name__=='__main__':raise SystemExit(main())
