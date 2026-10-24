#!/usr/bin/env python3
"""Early candidate-only water/restoration diagnostic, never two-reference acceptance.
Uses the original oracle's exact image mathematics and independent AABB masks.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import numpy as np

ROOT=Path(__file__).resolve().parent.parent
PIN='07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c'


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file)
    result=importlib.util.module_from_spec(spec);sys.modules[name]=result;spec.loader.exec_module(result)
    return result


O=module('r29_early_candidate_oracle','check-transparency-regression.py')
M=module('r29_early_candidate_restoration','measure-native-repeat.py')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def diagnose(fixture,output):
    fixture=Path(fixture).resolve(strict=True)
    if fixture.name=='results':fixture=fixture.parent
    directory=fixture/'results'
    manifest=json.loads((fixture/'fixture.json').read_text(encoding='utf-8'))
    assert manifest['jarSha256']==PIN and manifest['nativeTransparencyControl'] is False
    protocol=O.S.inspect_fixture(fixture)
    assert protocol['settled'] is True
    O.S.verify_warmup_images(fixture,protocol,all_no_sky=True)
    report=dict(kind='R29-candidate-only-early-water-restoration-not-acceptance',
                directory=str(directory),jarSha256=PIN,profile=manifest['profile'],shader=manifest['shader'],
                protocol=protocol,originalThresholds=O.DEFAULTS,checks=[],inputs={},
                independentReferencesEvaluated=0,requiredIndependentReferences=2,
                fullEnvironmentAccepted=False,releaseAccepted=False)
    def observe(path):
        path=Path(path).resolve(strict=True)
        assert path.is_relative_to(fixture)
        report['inputs'][str(path)]=dict(sha256=digest(path),bytes=path.stat().st_size)
    observe(fixture/'fixture.json');observe(fixture/'run-result.json')
    for protection in O.PROTECTIONS:
        scenes=[];rois=[];anchors=[]
        for slot,state in (('sky','marker'),('sky','empty'),('open','marker'),('open','empty')):
            name=f'trans-{slot}-water-{protection}-{state}'
            metadata_path=directory/(name+'.json');observe(metadata_path)
            metadata=json.loads(metadata_path.read_text(encoding='utf-8'))
            scene=O.Scene(name,metadata)
            final=directory/(name+'.png');observe(final);scene.images['final']=O.rgb(final)
            hand=directory/(name+'-post-hand-mask.png');observe(hand)
            shape=(metadata['height'],metadata['width']);g=O.geometry(scene,shape)
            scale=float(min(metadata['rasterScale']));margin=max(1,int(np.ceil(O.DEFAULTS['margin_active']/scale)))
            mask=O.map_active_mask(O.rgb(hand)[...,0]>0,metadata,shape)
            assert mask.mean()<=O.DEFAULTS['max_hand_fraction']
            safe=O.erode(g['sky'],margin)&~O.dilate(mask,max(1,margin//2))
            rois.append(safe&O.erode(g['transparent'],margin)&O.dilate(g['marker'],margin+2))
            anchors.append(O.marker_geometry(scene,shape));scenes.append(scene)
        assert all(O.matching(scenes[0],scene) for scene in scenes[1:])
        roi=np.logical_and.reduce(rois);anchor=np.logical_and.reduce(anchors)
        passed,metrics=O.marker_metrics(*(scene.images['final'] for scene in scenes),roi,O.DEFAULTS,scale,geometry=anchor)
        report['checks'].append(dict(protection=protection,passed=bool(passed),metrics=metrics))
        print(json.dumps(dict(protection=protection,passed=bool(passed),evidenceValid=metrics['evidenceValid'],leakDetected=metrics['leakDetected'],error=metrics.get('evidenceError'))),flush=True)
    report['rawRemovalDiagnostic']=M.measure(directory,False)
    for front,pair in report['rawRemovalDiagnostic']['pairs'].items():
        for name in ('trans-open-'+front+'-none-marker','trans-removed-'+front):
            for suffix in ('.json','.png','-post-hand-mask.png'):
                observe(directory/(name+suffix))
        print('RAW_REMOVAL',front,json.dumps(pair['raw']),flush=True)
    report['toolSha256']={path.name:digest(path) for path in (Path(__file__),Path(O.__file__),Path(M.__file__),Path(O.S.__file__))}
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x',encoding='utf-8') as stream:
        json.dump(report,stream,ensure_ascii=False,indent=2,allow_nan=False);stream.write('\n')
    print('DIAGNOSTIC_ONLY',output,flush=True)
    return 0


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    raise SystemExit(diagnose(args.fixture,args.output))
