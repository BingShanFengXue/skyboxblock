#!/usr/bin/env python3
"""Synthetic/TEMP protocol controls only, never real GPU or release evidence."""
from __future__ import annotations
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time
import numpy as np
from PIL import Image

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result

T = module('settled_existing_fixture_tests', 'check-marker-reference-repeat-selftest.py')
C = module('settled_cli_under_test', 'check-settled-transparency.py')
RESTORE = module('settled_restoration_under_test', 'check-restoration-repeat.py')
S = T.R.O.S


def upgrade(root, native):
    manifest = root / 'fixture.json'
    m = json.loads(manifest.read_text(encoding='utf-8'))
    m.update(suite='transparency', requestedScenes=[], settledTransparency=True,
             fixedHistoryWarmupScenes=list(S.WARMUPS),
             neverSkyWarmupScenes=list(S.WARMUPS) if native else [],
             neverSkyMeasurementSceneCount=51 if native else None,
             settlementPolicy=dict(S.POLICY), freezeWaterTextures=True,
             freezeFluidTicks=True, waterSpritePhase='first-declared-resource-frame',
             fixtureInputIsolation='owned-client-START_CLIENT_TICK-releaseAll-and-resetToggleKeys')
    T.put(manifest, m)
    T.put(root / 'run-result.json', dict(exitCode=0, finished=True, fatalErrors=[], errors=[],
          renderThreadViolations=[], scenes=53, seconds=820.))
    results = root / 'results'
    template = json.loads((results / 'trans-open-none-none-marker.json').read_text(encoding='utf-8'))
    for name in S.WARMUPS:
        source = 'trans-open-none-none-marker'
        data = copy.deepcopy(template)
        data.update(scene=name, transparency=None, fixtureSettlement=None,
                    camera=[.5 if name == S.WARMUPS[0] else 1.15, 141.62, -3.])
        T.put(results / (name + '.json'), data)
        for suffix in ('', '-before', '-opaque', '-depth-change', '-post-hand-mask'):
            shutil.copyfile(results / (source + suffix + '.png'), results / (name + suffix + '.png'))
    for index, name in enumerate(S.TIMELINE):
        path = results / (name + '.json')
        data = json.loads(path.read_text(encoding='utf-8'))
        warmup = name in S.WARMUPS
        present = not native and name.startswith('trans-sky-')
        data.update(sceneRenderFrames=450, sceneWaitTicks=300 if warmup else 90 if name.startswith('trans-removed-') else 70,
                    fixtureInputGuardTicks=10000 + index * 400,
                    observedFixtureWaterSprites=2, frozenFixtureWaterSprites=2,
                    staticFixtureWaterSprites=0, suppressedFixtureWaterSpriteTicks=1000 + index * 100,
                    neverSkyObservedRootFrames=(index + 1) * 1000 if native else 0,
                    finalResolved=present, initialGlError=0, finalGlError=0, resourceReloadPending=False,
                    irisFrameCounter=100+index, irisMainBoundBefore=True, irisMainBoundAfter=True,
                    irisOverridesBefore=True, irisOverridesAfter=True,
                    outsideRasterChangedPixels=0, outsideRasterDepthChangedPixels=0)
        if not warmup:
            t = data['transparency']
            t.update(fixtureGeometryVersion=3, markerPattern='solid',
                     fluidFreezeMode='owned-scheduled-water-only',
                     actualServerGameTime=10000 + index * 500,
                     actualClientGameTime=10001 + index * 500)
            t['actualServerGeometry'] = T.R.O.expected_fixture_geometry(t)
            t['actualClientGeometry'] = dict(t['actualServerGeometry'])
            data['suppressedFixtureWaterTicks'] = 40 if t['front'] == 'water' else 0
            data['fixtureSettlement'] = dict(protocol='fixed-real-time-and-root-render-frames',
                requiredMilliseconds=15000, requiredFrames=300,
                observedMilliseconds=15010, observedFrames=400, stablePoseRequired=True)
        T.put(path, data)
        shutil.copyfile(results / (name + '.png'), results / (name + '-pre-resolve.png'))
    (root / 'client.log').write_text('fixture-only Iris frameTimeCounter=0 active\n'
        + 'owned water sprite first-frame freeze minecraft:block/water_still\n'
        + 'owned water sprite first-frame freeze minecraft:block/water_flow\n'
        + ''.join('[skybox-regression] CAPTURED ' + name + '\n' for name in S.TIMELINE)
        + '[skybox-regression] FINISHED: 53 scenes\n', encoding='utf-8')


def run(output=None):
    start = time.monotonic()
    checks = []
    def require(name, passed, detail=None):
        checks.append(dict(name=name, passed=bool(passed), detail=detail))
        print(('PASS ' if passed else 'FAIL ') + name, flush=True)
    with tempfile.TemporaryDirectory(prefix='skybox-settled53-selftest-') as temporary:
        project = Path(temporary)
        roots, pin, roi, anchor, scale = T.make_fixtures(project)
        a, b, c = roots
        def proof():
            return T.R.proof(a, b, c, pin, protections=('none',), project=project)
        legacy = proof()
        require('original-51-slot-parser-and-image-proof-still-passes', legacy['passed'], legacy['errors'])
        require('legacy-never-acquires-a-settled-pass', not S.inspect_fixture(a)['settled'])
        for index, root in enumerate(roots):
            upgrade(root, index < 2)
        for root in roots:
            protocol = S.inspect_fixture(root)
            S.verify_warmup_images(root, protocol, all_no_sky=True)
            require('complete-53-protocol-and-no-write-images-' + root.name,
                    protocol['settled'] and protocol['metadataCount'] == 53
                    and protocol['minimumObservedMilliseconds'] == 15010
                    and protocol['minimumObservedFrames'] == 400)
        supported = proof()
        require('same-53-protocol-real-parser-image-repeat-pass', supported['passed'], supported['errors'])
        require('all-three-protocols-bound-in-repeat-report', len(supported.get('settledProtocolEvidence', {})) == 3)
        require('standalone-package-and-protocol-gate-pass', C.check(a, pin)['passed'])
        require('standalone-explicit-wrong-pin-fails', not C.check(a, 'f' * 64)['passed'])
        require('image-thresholds-unchanged', all(T.R.O.DEFAULTS[k] == v for k, v in T.R.FROZEN.items()))
        strict_path=project/'synthetic-strict.json'
        T.put(strict_path,dict(directory=str((c/'results').resolve()),passedChecks=1,totalChecks=1,
                              checks=[dict(name='synthetic-only-control',passed=True)]))
        calibrated=RESTORE.check(c/'results',[a/'results',b/'results'],strict_path)
        require('settled-restoration-real-reader-two-reference-positive',calibrated['passed'],
                [x for x in calibrated['checks'] if not x['passed']])
        require('settled-restoration-one-reference-still-fails',
                not RESTORE.check(c/'results',[a/'results'],strict_path)['passed'])
        with T.changed_json(c/'results/trans-removed-water.json',lambda d:d.pop('fixtureSettlement')):
            invalid_restore=RESTORE.check(c/'results',[a/'results',b/'results'],strict_path)
        require('restoration-rejects-missing-removed-settlement',not invalid_restore['passed'])
        def rejected(name, path, mutate, images=False):
            with T.changed_json(path, mutate):
                try:
                    protocol = S.inspect_fixture(b)
                    if images:
                        S.verify_warmup_images(b, protocol, all_no_sky=True)
                    failed, detail = False, None
                except (OSError, ValueError, KeyError, TypeError) as error:
                    failed, detail = True, str(error)
            require(name, failed, detail)
        fixture, runtime = b / 'fixture.json', b / 'run-result.json'
        measurement = b / 'results/trans-sky-water-none-marker.json'
        warmup = b / 'results/warmup-empty-front.json'
        for key, value in [('settledTransparency', False), ('settledTransparency', 1),
                           ('freezeWaterTextures', False), ('freezeShaderTime', False),
                           ('freezeFluidTicks', False), ('waterSpritePhase', 'live'),
                           ('requestedScenes', ['trans-open-none-none-marker']),
                           ('fixedHistoryWarmupScenes', list(reversed(S.WARMUPS))),
                           ('neverSkyWarmupScenes', []), ('neverSkyMeasurementSceneCount', 53),
                           ('fixtureInputIsolation', 'uncontrolled')]:
            rejected('declaration-' + key + '-fails', fixture, lambda d, k=key, v=value: d.update({k:v}))
        for key, value in [('scenes', 51), ('scenes', True), ('seconds', 764.999),
                           ('exitCode', 1), ('finished', False), ('fatalErrors', ['fault']),
                           ('errors', ['runtime error']), ('renderThreadViolations', ['wrong thread'])]:
            rejected('runtime-' + key + '-' + str(value) + '-fails', runtime, lambda d, k=key, v=value: d.update({k:v}))
        rejected('lowered-declared-settlement-requirement-fails', fixture,
                 lambda d: d['settlementPolicy'].update(minimumRealMilliseconds=14999))
        for key, value in [('observedMilliseconds',14999), ('observedMilliseconds',True),
                           ('observedFrames',299), ('observedFrames',451), ('observedFrames',True),
                           ('requiredMilliseconds',14999), ('requiredFrames',299),
                           ('stablePoseRequired',False), ('protocol','ticks-only')]:
            rejected('per-capture-' + key + '-' + str(value) + '-fails', measurement,
                     lambda d, k=key, v=value: d['fixtureSettlement'].update({k:v}))
        rejected('missing-settlement-evidence-fails', measurement, lambda d: d.pop('fixtureSettlement'))
        for key, value in [('sceneRenderFrames',299), ('fixtureInputGuardTicks',0),
                           ('neverSkyObservedRootFrames',0), ('frozenFixtureWaterSprites',0),
                           ('observedFixtureWaterSprites',1), ('suppressedFixtureWaterSpriteTicks',0),
                           ('suppressedFixtureWaterTicks',0), ('initialGlError',1),
                           ('resourceReloadPending',True), ('itrpBackgroundInjected',True),
                           ('blocks',88), ('finalResolved',True), ('irisFrameCounter',None),
                           ('irisMainBoundAfter',False), ('irisOverridesAfter',False),
                           ('outsideRasterChangedPixels',1), ('outsideRasterDepthChangedPixels',1)]:
            rejected('actual-counter-or-state-' + key + '-fails', measurement,
                     lambda d, k=key, v=value: d.update({k:v}))
        rejected('actual-client-sky-in-neverSky-fails', measurement,
                 lambda d: d['transparency']['actualClientGeometry'].update(sky=88))
        rejected('global-fluid-freeze-not-settled-evidence', measurement,
                 lambda d: d['transparency'].update(fluidFreezeMode='legacy-global-server'))
        rejected('wrong-fixed-warmup-camera-fails', warmup, lambda d: d.update(camera=[9.,141.62,-3.]))
        rejected('warmup-disguised-as-settled-measurement-fails', warmup,
                 lambda d: d.update(fixtureSettlement=dict(observedMilliseconds=15000)))
        rejected('wrong-warmup-name-fails', warmup, lambda d: d.update(scene='warmup-other'))
        log_path = b / 'client.log'
        original_log = log_path.read_bytes()
        with T.changed_bytes(log_path, original_log.replace(b'water_flow', b'wrong_resource')):
            try: S.inspect_fixture(b); failed=False
            except ValueError: failed=True
        require('missing-actual-water-upload-log-fails', failed)
        with T.changed_bytes(log_path, original_log.replace(b'CAPTURED warmup-empty-front', b'CAPTURED wrong-warmup')):
            try: S.inspect_fixture(b); failed=False
            except ValueError: failed=True
        require('wrong-live-capture-order-fails', failed)
        depth = b / 'results/warmup-empty-front-depth-change.png'
        with T.changed_bytes(depth, depth.read_bytes()):
            array = T.R.O.rgb(depth); array[0,0] = 255; T.png(depth, array)
            try: S.verify_warmup_images(b, S.inspect_fixture(b), all_no_sky=True); failed=False
            except ValueError: failed=True
        require('actual-warmup-depth-write-fails', failed)
        pre = b / 'results/trans-sky-water-none-marker-pre-resolve.png'
        with T.changed_bytes(pre, pre.read_bytes()):
            array = T.R.O.rgb(pre); array[0,0] ^= 255; T.png(pre, array)
            try: S.verify_warmup_images(b, S.inspect_fixture(b), all_no_sky=True); failed=False
            except ValueError: failed=True
        require('actual-neverSky-late-color-write-fails', failed)
        for key in ('skyEntities', 'highSky', 'highSkyEntities'):
            rejected('actual-server-' + key + '-lifecycle-fails', measurement,
                     lambda d, k=key: d['transparency']['actualServerGeometry'].update({k:88}))
        rejected('actual-sky-count-bool-is-not-integer', measurement,
                 lambda d: d['transparency']['actualClientGeometry'].update(sky=False))
        for key in ('width', 'height', 'opaqueWidth', 'opaqueHeight'):
            rejected('metadata-image-grid-' + key + '-fails', warmup,
                     lambda d, k=key: d.update({k:1}), images=True)
        for key in ('requiredMilliseconds', 'requiredFrames'):
            rejected('boolean-' + key + '-not-accepted', measurement,
                     lambda d, k=key: d['fixtureSettlement'].update({k:True}))
        missing = b / 'results/warmup-empty-oblique.json'
        saved = missing.read_bytes()
        assert missing.resolve().parent == (b/'results').resolve() and missing.name == 'warmup-empty-oblique.json'
        missing.unlink()
        try:
            try: S.inspect_fixture(b); failed=False
            except ValueError: failed=True
            require('missing-real-warmup-metadata-fails', failed)
        finally: missing.write_bytes(saved)
        extra = b / 'results/extra-scene.json'
        T.put(extra, dict(scene='extra-scene'))
        try:
            try: S.inspect_fixture(b); failed=False
            except ValueError: failed=True
            require('extra-scene-cannot-be-hidden-from-53-count', failed)
        finally:
            assert extra.resolve().parent == (b/'results').resolve() and extra.name == 'extra-scene.json'
            extra.unlink()
        raw = measurement.read_bytes()
        duplicate = raw.replace(b'{', b'{"scene":"impostor",', 1)
        with T.changed_bytes(measurement, duplicate):
            try: S.inspect_fixture(b); failed=False
            except ValueError: failed=True
            require('duplicate-json-key-fails-direct-gate', failed)
            duplicate_proof = proof()
            require('duplicate-json-key-also-fails-through-repeat-reader',
                    not duplicate_proof['passed'] and 'duplicate JSON key' in json.dumps(duplicate_proof))
        # CLI timeout errors occur before instance creation or game launch.
        import contextlib, io
        runner = module('settled_runner_parser_under_test', 'render-regression.py')
        old_argv = sys.argv
        try:
            sys.argv = ['render-regression.py','--suite','transparency','--settled-transparency','--timeout','360']
            try:
                with contextlib.redirect_stderr(io.StringIO()): runner.main()
                rejected_timeout=False
            except SystemExit as error: rejected_timeout=error.code==2
            require('short-settled-timeout-rejected-before-launch', rejected_timeout)
        finally: sys.argv = old_argv
        # Explicit vanilla branch: disabled pack and absent Iris, not fabricated shader-time activation.
        originals = {}
        try:
            for root in roots:
                for path in (root/'fixture.json', root/'config/iris.properties', root/'client.log'):
                    originals[path] = path.read_bytes()
                data = json.loads((root/'fixture.json').read_text(encoding='utf-8'))
                data.update(profile='vanilla', shader=None, shaderOptions={})
                T.put(root/'fixture.json', data)
                (root/'config/iris.properties').write_text('enableShaders=false\n', encoding='utf-8')
                text = (root/'client.log').read_text(encoding='utf-8').replace('fixture-only Iris frameTimeCounter=0 active\n','')
                (root/'client.log').write_text(text, encoding='utf-8')
                for path in (root/'results').glob('*.json'):
                    originals[path] = path.read_bytes()
                    metadata=json.loads(originals[path])
                    metadata.update(irisFrameCounter=None,irisMainBoundBefore=None,irisMainBoundAfter=None,
                                    irisOverridesBefore=None,irisOverridesAfter=None)
                    T.put(path,metadata)
            vanilla = proof()
            require('actual-vanilla-no-Iris-no-pack-repeat-pass', vanilla['passed'], vanilla['errors'])
            vanilla_restore=RESTORE.check(c/'results',[a/'results',b/'results'],strict_path)
            require('actual-vanilla-settled-restoration-reader-pass',vanilla_restore['passed'],
                    [x for x in vanilla_restore['checks'] if not x['passed']])
            with T.changed_json(b/'results/trans-sky-water-none-marker.json',lambda d:d.update(irisFrameCounter=1)):
                contradictory_vanilla=proof()
            require('vanilla-with-actual-Iris-frame-observation-fails',not contradictory_vanilla['passed'])
            with T.changed_bytes(b/'config/iris.properties', b'enableShaders=true\n'):
                wrong = proof()
            require('vanilla-with-active-pack-declaration-fails', not wrong['passed'] and bool(wrong['errors']))
        finally:
            for path, data in originals.items(): path.write_bytes(data)
        # Legacy declaration with settled files is rejected; never silently drops the new evidence.
        with T.changed_json(c/'fixture.json', lambda d:d.update(settledTransparency=False)):
            mixed = proof()
        require('mixed-or-undeclared-settled-candidate-fails', not mixed['passed'] and bool(mixed['errors']))
    report = dict(kind='synthetic TEMP settled53 tests only; not GPU evidence',
                  passed=all(c['passed'] for c in checks), passedChecks=sum(c['passed'] for c in checks),
                  totalChecks=len(checks), checks=checks, seconds=round(time.monotonic()-start,3))
    if output:
        output.parent.mkdir(parents=True,exist_ok=True)
        with output.open('x',encoding='utf-8') as stream: json.dump(report,stream,ensure_ascii=False,indent=2,allow_nan=False)
    print('SELFTEST',report['passedChecks'],'/',report['totalChecks'],'seconds',report['seconds'],flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    raise SystemExit(run(parser.parse_args().output))
