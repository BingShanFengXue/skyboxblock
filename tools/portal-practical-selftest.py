#!/usr/bin/env python3
"""Bounded sensitivity tests for the practical IP evidence helpers."""
from __future__ import annotations
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import numpy as np

SPEC=importlib.util.spec_from_file_location('practical_evidence',Path(__file__).with_name('portal-practical-evidence.py'))
E=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(E)
O=E.ORACLE

def run():
    checks=[]
    def add(name,condition):checks.append(dict(name=name,passed=bool(condition)))
    h,w=160,256;y,x=np.indices((h,w));behind=(x>=20)&(x<236)&(y>=15)&(y<145)
    red=(x//24)%2==1;g=dict(behind=behind,stripeRed=red)
    base=np.full((h,w,3),(95,135,190),dtype=np.uint8)
    base[behind & red]=(160,40,30);base[behind & ~red]=(170,170,170)
    add('neutral-physical-stripes-positive',E.stripe_layout_evidence(base,g)[0])
    blue=base.copy();blue[behind & ~red]=(140,155,178)
    add('blue-lit-white-retains-structural-positive',E.stripe_layout_evidence(blue,g)[0])
    add('original-strict-neutral-test-still-red-for-blue-lit-white',not O.stripe_evidence(blue,g)[0])
    add('original-neutral-strict-positive-retained',O.stripe_evidence(base,g)[0])
    # Reviewer-proven hole: color/edge consistency cannot identify pale material.
    hole=base.copy();hole[behind & ~red]=(95,135,190)
    consistent,metrics=E.stripe_layout_evidence(hole,g)
    add('bright-sky-pale-hole-is-explicitly-not-complete-geometry',consistent
        and metrics['geometryEvidenceComplete'] is False and metrics['completeGeometryPassed'] is None)
    add('strict-pale-hole-negative-retained',not O.stripe_evidence(hole,g)[0])
    mutations={}
    a=base.copy();a[behind]=(95,135,190);mutations['missing-wall']=a
    a=base.copy();a[behind]=(160,40,30);mutations['all-red-no-stripes']=a
    a=base.copy();a[behind]=(170,170,170);mutations['all-white-no-stripes']=a
    a=base.copy();a[behind & red]=(170,170,170);a[behind & ~red]=(160,40,30);mutations['one-band-translated-wall']=a
    a=base.copy();a[behind & (y<100)]=(95,135,190);mutations['partially-missing-wall']=a
    a=base.copy();a[behind & ~red]=(0,0,0);mutations['black-pale-interiors']=a
    a=base.copy();a[behind]=np.random.default_rng(16512).integers(0,256,(int(behind.sum()),3),dtype=np.uint8);mutations['random-noise-wall']=a
    for name,a in mutations.items():add('structure-detects:'+name,not E.stripe_layout_evidence(a,g)[0])
    emptyg=dict(behind=np.zeros((h,w),bool),stripeRed=red)
    add('empty-projection-not-evidence',not E.stripe_layout_evidence(base,emptyg)[0])
    root=Path(__file__).resolve().parents[1]
    p=root/'build/portal-fixture/ip-r23-basic-iris-native-baseline/results/01-single-front.json'
    source=json.loads(p.read_text(encoding='utf-8'));summary={'mode':'portal'}
    add('actual-frozen-probe-positive',E.portal_protocol(source,summary)[0])
    for name,change in [
        ('missing-actual-probe',lambda m:m['serverPlayer'].pop('actualWorldGeometry')),
        ('wrong-actual-block',lambda m:m['serverPlayer']['actualWorldGeometry']['blocks'][0].update(actual='minecraft:diamond_block')),
        ('missing-mode',lambda m:m.pop('mode')),
        ('mislabelled-remote-direct',lambda m:m.update(mode='remote-direct')),
        ('wrong-portal-inventory',lambda m:m['serverPlayer']['actualWorldGeometry'].update(portalEntityCount=0,portalEntityUuids=[])),
        ('boolean-probe-count',lambda m:m['serverPlayer']['actualWorldGeometry'].update(sampleCount=True)),
        ('float-probe-count',lambda m:m['serverPlayer']['actualWorldGeometry'].update(sampleCount=1627.0)),
        ('wrong-digest',lambda m:m['serverPlayer']['actualWorldGeometry'].update(blockEvidenceSha256='0'*64)),
        ('duplicate-probe-position',lambda m:m['serverPlayer']['actualWorldGeometry']['blocks'].__setitem__(1,copy.deepcopy(m['serverPlayer']['actualWorldGeometry']['blocks'][0]))),
        ('wrong-global-inventory',lambda m:m['serverPlayer']['actualWorldGeometry'].update(globalPortalCount=1)),
    ]:
        meta=copy.deepcopy(source);change(meta)
        try:ok=E.portal_protocol(meta,summary)[0]
        except (KeyError,TypeError,ValueError,IndexError):ok=False
        add('fail-closed:'+name,not ok)
    add('wrong-summary-mode-blocked',not E.portal_protocol(source,{'mode':'remote-direct'})[0])
    add('missing-summary-mode-blocked',not E.portal_protocol(source,{})[0])
    # Real oblique child iris-final image is sampled before IP composites its gate.
    meta=json.loads((root/'build/portal-fixture/ip-r23-iris-native-sky/results/02-single-oblique.json').read_text(encoding='utf-8'))
    manifest=json.loads((root/'build/portal-fixture/ip-r23-iris-native-sky/fixture.json').read_text(encoding='utf-8'))
    profile=dict(iris=True,shaderHash=manifest['shaderSha256'],fsrOption=-1,customResolution=False)
    geometry=O.geometry(meta,profile)
    frame=next(f for f in meta['skyViews'] if f['portal'] and f['vertices']>0)
    sky,pillar=E.intermediate_regions(meta,geometry,frame)
    add('child-intermediate-projection-includes-later-root-gate',np.all(~geometry['childSky']|sky))
    add('child-intermediate-projection-is-not-root-gate-crop',int(sky.sum())>int(geometry['childSky'].sum()))
    folder=root/'build/portal-fixture/ip-r23-iris-native-sky/results'
    pre=O.rgb(folder/frame['preViewImage']);post=O.rgb(folder/frame['postViewImage'])
    revised=O.view_image_checks(pre,post,sky,pillar)
    original=O.view_image_checks(pre,post,geometry['childSky'],geometry['childPillar'])
    add('historical-child-stage-false-world-leak-reproduced',not original[0]['passed'])
    add('stage-correct-no-world-leak-measured',revised[0]['passed'])
    altered=post.copy();outside=np.argwhere(~O.dilate(sky,O.LIMITS['margin']))[0];altered[tuple(outside)]=(255,0,255)
    add('real-outside-child-spill-still-detected',not O.view_image_checks(pre,altered,sky,pillar)[0]['passed'])
    return dict(kind='practical-helper-sensitivity-selftest-not-product-release',passed=all(c['passed'] for c in checks),
        passedChecks=sum(c['passed'] for c in checks),totalChecks=len(checks),checks=checks)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    result=run()
    with args.output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps(result,indent=2));raise SystemExit(0 if result['passed'] else 1)
