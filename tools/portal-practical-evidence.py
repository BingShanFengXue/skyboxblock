#!/usr/bin/env python3
"""Small, stage-aware practical IP evidence helpers; not a release-gate waiver.

Keep the old strict RGB/neutral-material verdict separate from structural checks.
Projected color layouts use the original >=.85 red/non-red accuracy,
>=64 pixels and margin=3, plus existing interface edge thresholds. This is NOT
complete geometry evidence: a missing pale stripe exposing bright blue sky can
retain that layout. Therefore it is diagnostic only, never an acceptance-gate
replacement. Full pale-material visibility needs independent depth or manual
original-image review; old strict material verdicts remain intact.
"""
from __future__ import annotations
import hashlib
import importlib.util
import json
from pathlib import Path
import numpy as np

SPEC=importlib.util.spec_from_file_location('portal_strict_oracle',Path(__file__).with_name('check-portal-regression.py'))
ORACLE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(ORACLE)


def actual_blocks(evidence,variant,portal_count):
    expected={}
    for x in range(29,36):
        for y in range(140,147):
            expected[(x,y,8)]='minecraft:red_concrete' if x%2 else 'minecraft:white_concrete'
            expected[(x,y,6)]='skyboxblock:sky_block' if variant=='sky' else 'minecraft:air'
    for y in range(140,144):expected[(32,y,4)]='minecraft:stone'
    for x in range(-12,49):
        for z in range(-12,13):expected[(x,139,z)]='minecraft:bedrock'
    blocks=evidence['blocks'];seen=set();matched=True
    for item in blocks:
        p=item['position']
        if not isinstance(p,list) or len(p)!=3 or any(type(v) is not int for v in p):raise ValueError('invalid block position')
        position=tuple(p)
        matched &= position in expected and position not in seen and item['actual']==expected.get(position)
        seen.add(position)
    digest=hashlib.sha256(json.dumps(blocks,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    ids=evidence['portalEntityUuids']
    good=(matched and seen==expected.keys() and type(evidence['sampleCount']) is int
        and evidence['sampleCount']==len(expected) and evidence['blockEvidenceSha256']==digest
        and type(evidence['portalEntityCount']) is int and evidence['portalEntityCount']==portal_count
        and len(ids)==len(set(ids))==portal_count and type(evidence['globalPortalCount']) is int
        and evidence['globalPortalCount']==0)
    return bool(good),dict(observed=len(seen),required=len(expected),portalCount=evidence['portalEntityCount'],digest=digest)


def portal_protocol(meta,summary=None):
    if meta.get('mode')!='portal':return False,dict(error='metadata mode must be portal')
    if summary is not None and summary.get('mode')!='portal':return False,dict(error='summary mode must be portal')
    scene=meta['scene']
    expected=0 if scene in ('00-no-portal','08-clean') else 1 if scene in ('01-single-front','02-single-oblique','03-single-near') else 2
    if scene not in ORACLE.FIXED:return True,dict(actualBlockCaptureRequired=False)
    evidence=meta['serverPlayer']['actualWorldGeometry']
    ok,metrics=actual_blocks(evidence,meta['variant'],expected)
    return ok,dict(actualBlockCaptureRequired=True,**metrics)


def stripe_layout_evidence(pixels,g):
    red_roi=ORACLE.erode(g['behind'] & g['stripeRed'],ORACLE.LIMITS['margin'])
    pale_roi=ORACLE.erode(g['behind'] & ~g['stripeRed'],ORACLE.LIMITS['margin'])
    v=pixels.astype(float);r,green,b=v[...,0],v[...,1],v[...,2]
    red=(r>green*1.35+8)&(r>b*1.25+8)&(r>30)
    pale=(~red)&(v.mean(axis=-1)>25)
    counts=[int(red_roi.sum()),int(pale_roi.sum())]
    fractions=[float(red[red_roi].mean()) if counts[0] else 0.,float(pale[pale_roi].mean()) if counts[1] else 0.]
    # Independently projected red/pale interfaces, excluding the wall's outer edge.
    boundary=ORACLE.erode(g['behind'],ORACLE.LIMITS['margin']) & ORACLE.dilate(g['stripeRed'],1) & ORACLE.dilate(~g['stripeRed'],1)
    chroma=r-(green+b)*.5
    p=np.pad(chroma,1,mode='edge')
    gradient=np.maximum(np.abs(p[2:,1:-1]-p[:-2,1:-1]),np.abs(p[1:-1,2:]-p[1:-1,:-2]))
    edges=int(boundary.sum())
    coverage=float(ORACLE.dilate(gradient>5,2)[boundary].mean()) if edges else 0.
    passed=(min(counts)>=ORACLE.LIMITS['minPixels'] and min(fractions)>=ORACLE.LIMITS['stripeAccuracy']
        and edges>=16 and coverage>=ORACLE.LIMITS['pillarEdgeCoverage'])
    return bool(passed),dict(redPixels=counts[0],palePixels=counts[1],redAccuracy=fractions[0],paleAccuracy=fractions[1],
        projectedInterfacePixels=edges,projectedInterfaceCoverage=coverage,
        geometryEvidenceComplete=False,completeGeometryPassed=None,
        scope='color layout diagnostic only; pale-stripe holes can expose equally non-red sky; not a geometry acceptance gate')


def intermediate_regions(meta,g,frame):
    if not frame['portal'] or frame['imageStage']!='iris-final':
        return (g['directSky'],g['directPillar']) if frame['root'] else (g['childSky'],g['childPillar'])
    # Iris child final-pass capture precedes IP's later stencil/framebuffer composite.
    view=g['childWorld'];origin=ORACLE.camera(view)
    direction=ORACLE.rays(view,meta['width'],meta['height'])
    sky=ORACLE.box_distance(origin,direction,meta['fixtureGeometry']['skyBounds'])
    pillar=ORACLE.box_distance(origin,direction,meta['fixtureGeometry']['remotePillarBounds'])
    return np.isfinite(sky)&(sky<pillar),np.isfinite(pillar)&(pillar<sky)


def baseline(path):
    d=ORACLE.load(Path(path));manifest=d['manifest']
    settings=ORACLE.canonical_settings(manifest['settings'],manifest['shaderSha256'])
    profile=dict(iris=bool(manifest['shader']),shaderHash=manifest['shaderSha256'],fsrOption=int(settings.get('FSR2_SCALE',-1)),
        customResolution=settings.get('CUSTOM_RENDER_RESOLUTION','false')=='true')
    checks=[]
    for scene in ORACLE.SCENES:
        meta=d['metadata'][scene]
        ok,metrics=portal_protocol(meta,d['summary'])
        checks.append(dict(name=scene+':actual-physical-protocol',passed=ok,**metrics))
        if scene in ORACLE.FIXED:
            g=ORACLE.geometry(meta,profile);pixels=d['pictures'][scene]
            for label,fn in [('strict-neutral-stripes',ORACLE.stripe_evidence),('red-pale-layout-diagnostic',stripe_layout_evidence)]:
                ok,metrics=fn(pixels,g);checks.append(dict(name=scene+':'+label,passed=ok,**metrics))
            ok,metrics=ORACLE.pillar_evidence(pixels,g['childPillar'])
            checks.append(dict(name=scene+':projected-pillar',passed=ok,**metrics))
    return dict(fixture=str(path),releaseAccepted=False,checks=checks)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('fixture',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result=baseline(args.fixture)
    with args.output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2);f.write('\n')
    for c in result['checks']:print(json.dumps(c))
