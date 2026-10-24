#!/usr/bin/env python3
"""Real NeverSky sensitivity at unchanged thresholds; NOT a SkyOn/no-leak acceptance."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,sys
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parent.parent

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file)
    out=importlib.util.module_from_spec(spec);sys.modules[name]=out;spec.loader.exec_module(out);return out
O=module('physical_marker_original','check-transparency-regression.py')
E=module('physical_marker_fixture','check-marker-reference-repeat.py')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('reference',type=Path)
    p.add_argument('--expected-jar-sha256',required=True);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();checks=[];inputs={};cases={}
    def add(name,passed,**data): checks.append(dict(name=name,passed=bool(passed),**data))
    try:
        f=E.Fixture(args.reference,args.expected_jar_sha256.lower(),True,ROOT,inputs,E.Sources())
        run=f.read_json(f.root/'run-result.json')
        add('runtime-no-render-thread-violations',run.get('renderThreadViolations')==[])
        for front in O.FRONTS:
            for protection in O.PROTECTIONS:
                for slot in ('open','sky'):
                    pair=[f.scene(f'trans-{slot}-{front}-{protection}-{state}',True,slot=='sky') for state in ('marker','empty')]
                    E.equivalent(pair[0],pair[1:]);key=front+':'+protection+':'+slot
                    for scene in pair:
                        t=scene.t;add(scene.name+':new-physical-protocol',t.get('fixtureGeometryVersion',0)>=2)
                        add(scene.name+':zero-gl-errors',scene.meta.get('initialGlError')==scene.meta.get('finalGlError')==0)
                        if t.get('fixtureGeometryVersion',0)>=3:
                            add(scene.name+':whole-NeverSky-session-observed',scene.meta.get('neverSkyObservedRootFrames',0)>0)
                            add(scene.name+':water-only-freeze',t.get('fluidFreezeMode')=='owned-scheduled-water-only')
                        expected=O.expected_fixture_geometry(t)
                        for side in ('Server','Client'):
                            add(scene.name+':actual-'+side.lower()+'-geometry',t.get('actual'+side+'Geometry')==expected,
                                expected=expected,actual=t.get('actual'+side+'Geometry'))
                    masks=[E.roi_for(scene) for scene in pair];roi=masks[0][0]&masks[1][0];anchor=masks[0][1]&masks[1][1]
                    marker,empty=[scene.images['final'] for scene in pair]
                    passed,m=O.marker_metrics(marker,empty,marker,empty,roi,O.DEFAULTS,masks[0][2],geometry=anchor)
                    cases[key]=m
                    add(key+':reference-admissible',m['evidenceValid'],referenceFeatureRms=m['referenceFeatureRms'],
                        legacyEvidenceValid=m['legacyEvidenceValid'],multiScaleEvidenceValid=m['multiScaleEvidenceValid'])
                    add(key+':real-reference-self-leak-positive',not passed and m['evidenceValid'] and m['leakDetected'],
                        legacyLeakDetected=m['legacyLeakDetected'],multiscaleLeakDetected=m['multiscaleLeakDetected'])
        for front in O.FRONTS:
            name='trans-removed-'+front;t=f.read_json(f.results/(name+'.json'))['transparency'];expected=O.expected_fixture_geometry(t)
            for side in ('Server','Client'): add(name+':actual-'+side.lower()+'-geometry',t.get('actual'+side+'Geometry')==expected)
        error=None
    except (OSError,KeyError,ValueError,TypeError,IndexError) as exc:
        error=str(exc);add('complete-reference-contract',False,error=error)
    report=dict(kind='physical-marker-reference-sensitivity',passed=bool(checks) and all(x['passed'] for x in checks),
        passedChecks=sum(x['passed'] for x in checks),totalChecks=len(checks),checks=checks,cases=cases,
        reference=str(args.reference.resolve()),expectedJarSha256=args.expected_jar_sha256,inputs=inputs,oracleThresholds=O.DEFAULTS,error=error,
        toolSha256={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in (Path(__file__),ROOT/'tools/check-transparency-regression.py',ROOT/'tools/check-marker-reference-repeat.py')},
        note='Real NeverSky image pairs are their own intentional leak-positive at unchanged thresholds. Not SkyOn/no-leak or unique direct-transmission proof.')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('x',encoding='utf-8') as stream:json.dump(report,stream,indent=2,allow_nan=False)
    print(f"{report['passedChecks']}/{report['totalChecks']} {'PASS' if report['passed'] else 'FAIL'}",args.output)
    for x in checks:
        if not x['passed']:print('FAIL',json.dumps(x))
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
