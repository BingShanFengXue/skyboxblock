#!/usr/bin/env python3
"""Bind the bounded R25 practical trials, without claiming full release acceptance."""
from __future__ import annotations
import hashlib
import importlib.util
import json
from pathlib import Path
from datetime import datetime
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('practical_evidence',ROOT/'tools/portal-practical-evidence.py')
E=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(E)
O=E.ORACLE
MAIN='9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129'
HARNESS='612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7'


def load(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def pin(p):return dict(path=p.relative_to(ROOT).as_posix(),sha256=sha(p),bytes=p.stat().st_size)


def main(output=None):
    checks=[];portal=[];smoke=[];probes=0
    def add(name,ok):checks.append(dict(name=name,passed=bool(ok)))
    for env,fsr in [('native',-1),('fsr',2)]:
        for variant in ('baseline','empty','sky'):
            folder=ROOT/f'build/portal-fixture/ip-r25-{env}-no-motion-{variant}'
            manifest=load(folder/'fixture.json');summary=load(folder/'results/summary.json');run=load(folder/'run-result.json')
            actual={}
            for p in (folder/'mods').glob('*.jar'):
                with zipfile.ZipFile(p) as z:info=json.loads(z.read('fabric.mod.json'))
                if info['id'] in actual:raise ValueError('duplicate installed ID')
                actual[info['id']]=sha(p)
            add(folder.name+':installed-mod-set-and-hashes',actual=={m['id']:m['sha256'] for m in manifest['mods']})
            add(folder.name+':primary-presence-and-exact-bytes',('skyboxblock' in actual)==(variant!='baseline')
                and (variant=='baseline' or actual['skyboxblock']==MAIN) and actual['skybox_ip_fixture']==HARNESS)
            pack=folder/'shaderpacks'/manifest['shader'];settings_file=pack.with_name(pack.name+'.txt')
            settings=O.settings_evidence(manifest,settings_file.read_text(encoding='utf-8'),sha(pack))
            options=settings['actualEffective']
            add(folder.name+':same-pack-and-required-options',sha(pack)==O.ITRP_SHA256 and settings['matched']
                and options['MOTION_BLUR']=='false' and options['FSR2_SCALE']==str(fsr))
            config=load(folder/'config/immersive_portals.json')
            add(folder.name+':real-normal-config',config['compatibilityRenderMode'] is False
                and all(config[k] is True for k in ('enableCrossPortalView','enableClippingMechanism','useDepthClampForPortalRendering')))
            add(folder.name+':nine-runs-and-real-walks',run['exitCode']==0 and run['finished'] is True and run['scenes']==9
                and run['fatalErrors']==[] and run['renderThreadViolations']==[] and summary['success'] is True
                and summary['actualThroughCompleted'] is True and summary['actualReturnCompleted'] is True)
            movement=O.movement_checks(summary['movementEvidence'])
            add(folder.name+':numeric-movement-evidence',all(c['passed'] for c in movement))
            profile=dict(iris=True,shaderHash=O.ITRP_SHA256,fsrOption=fsr,customResolution=False)
            for scene in O.SCENES:
                meta=load(folder/'results'/f'{scene}.json')
                ok,metrics=E.portal_protocol(meta,summary)
                add(folder.name+':'+scene+':actual-mode-probes',ok)
                if metrics.get('actualBlockCaptureRequired'):probes+=metrics['observed']
                views=[O.harness_root(meta),*meta['views']]
                add(folder.name+':'+scene+':real-Iris-IP',all(v['rendererClass']=='qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer'
                    and v['pipelineClass']=='net.irisshaders.iris.pipeline.IrisRenderingPipeline'
                    and v['rasterHints']['irisActive'] is True for v in views))
                add(folder.name+':'+scene+':live-FSR',O.live_fsr_settings(meta,profile)[0])
            portal.append(dict(environment=env,variant=variant,scenes=run['scenes'],seconds=run['seconds'],runtimeErrors=len(run['errors']),
                fatalErrors=len(run['fatalErrors']),renderThreadViolations=len(run['renderThreadViolations']),
                actualMainSha256=actual.get('skyboxblock'),requiredOption='MOTION_BLUR=false',manifest=pin(folder/'fixture.json'),run=pin(folder/'run-result.json')))
    for env in ('vanilla','native','full-fsr'):
        folder=ROOT/f'build/render-regression/test5-r25-practical-smoke-{env}'
        run=load(folder/'run-result.json');manifest=load(folder/'fixture.json')
        world_path=ROOT/f'build/reports/test5-r25-practical-smoke-{env}-world.json'
        alignment_path=ROOT/f'build/reports/test5-r25-practical-smoke-{env}-alignment.json'
        world_items=load(world_path);alignment_items=load(alignment_path)
        if not isinstance(world_items,list) or len(world_items)!=1 or not isinstance(alignment_items,list) or len(alignment_items)!=1:
            raise ValueError('smoke checker reports must be one-directory lists')
        world=world_items[0];align=alignment_items[0]
        add(env+':28-real-smoke-scenes',run['scenes']==28 and run['finished'] is True and run['exitCode']==0
            and run['fatalErrors']==[] and run['renderThreadViolations']==[])
        add(env+':world-passed',world['passed'] is True)
        add(env+':alignment-passed',align['passed'] is True)
        # This launcher lists supporting mods only; its primary JAR is a separate field.
        primary=folder/'mods'/Path(manifest['jar']).name
        with zipfile.ZipFile(primary) as z:primary_info=json.loads(z.read('fabric.mod.json'))
        add(env+':same-frozen-candidate',primary_info['id']=='skyboxblock'
            and sha(primary)==manifest['jarSha256']==MAIN)
        smoke.append(dict(environment=env,scenes=run['scenes'],seconds=run['seconds'],runtimeErrors=len(run['errors']),
            fatalErrors=len(run['fatalErrors']),renderThreadViolations=len(run['renderThreadViolations']),
            worldPassedChecks=world['passedChecks'],worldTotalChecks=world['totalChecks'],
            alignmentPassedChecks=align['passedChecks'],alignmentTotalChecks=align['totalChecks'],
            manifest=pin(folder/'fixture.json'),run=pin(folder/'run-result.json'),world=pin(world_path),alignment=pin(alignment_path)))
    selftest_path=ROOT/'build/reports/ip-r25-practical-helper-selftest-r2.json';selftest=load(selftest_path)
    add('bounded-helper-selftests',selftest['passed'] is True and selftest['passedChecks']==selftest['totalChecks']==32)
    diagnostics=[]
    for filename in ('ip-r25-native-no-motion-practical-r2.json','ip-r25-fsr-no-motion-practical-r1.json'):
        p=ROOT/'build/reports'/filename;r=load(p)
        add(filename+':does-not-claim-auto-acceptance',r['practicalPassed'] is None and r['releaseAccepted'] is False
            and r['automaticAcceptanceReady'] is False and 'originalStrictReport' in r)
        diagnostics.append(dict(report=pin(p),originalStrictPassedChecks=r['originalStrictPassedChecks'],originalStrictTotalChecks=r['originalStrictTotalChecks'],
            checkedClaimsPassed=r['checkedClaimsPassed'],passedChecks=r['passedChecks'],totalChecks=r['totalChecks'],automaticAcceptanceReady=False))
    report=dict(kind='practical-test-candidate-evidence-summary-not-release-acceptance',createdAt=datetime.now().astimezone().isoformat(),projectAuthor='Bing6512',
        evidenceBindingChecksPassed=all(c['passed'] for c in checks),releaseAccepted=False,automatedPortalAcceptance=None,
        primaryRendererJarChanged=False,primaryJarSha256=MAIN,harnessSha256=HARNESS,
        requiredCompatibilityOption='ITRP MOTION_BLUR=false; shaders remain enabled; keep chosen FSR scale',
        scope='Bounded manual-visual practical candidate; same-overworld pure translation IP; no full release-gate claim',
        portalRuns=portal,smokeRuns=smoke,totalRealCaptures=sum(r['scenes'] for r in portal+smoke),actualPortalBlockSamples=probes,
        totalSmokeWorldChecks=sum(r['worldTotalChecks'] for r in smoke),totalSmokeAlignmentChecks=sum(r['alignmentTotalChecks'] for r in smoke),
        diagnostics=diagnostics,selftest=pin(selftest_path),checks=checks,
        deferred=['R23 first high-wall missing render not reproduced by R24 prefix; explicitly deferred by latest user scope, not fixed',
            'Prior full settled three restoration strict failures retained; no new full settled or dynamic-DH acceptance',
            'Pale-stripe geometry not automatically proved by red/non-red layout; layout is diagnostic only; original strict gate retained',
            'Material-neutrality/texture and unsynchronized cross-run RGB strict failures retained; no exact whole-frame equivalence claim',
            'Rotated/scaled/nested/mirrored/cross-dimension portals and arbitrary shader versions not verified'])
    out=Path(output).resolve() if output else ROOT/'build/reports/practical-r25-runtime-summary-20261020.json'
    with out.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps({k:report[k] for k in ('evidenceBindingChecksPassed','releaseAccepted','primaryRendererJarChanged','totalRealCaptures','actualPortalBlockSamples','totalSmokeWorldChecks','totalSmokeAlignmentChecks')},ensure_ascii=False))
    for r in smoke:print(json.dumps({k:v for k,v in r.items() if k not in ('manifest','run','world','alignment')},ensure_ascii=False))
    for c in checks:
        if not c['passed']:print('FAILED',c)
    return 0 if report['evidenceBindingChecksPassed'] else 1

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path)
    args=parser.parse_args();raise SystemExit(main(args.output))
