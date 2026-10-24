#!/usr/bin/env python3
"""Serial owned-GPU settled matrix; pinned test.8, immutable stage logs/reports.

Existing completed fixtures may be reused only after declaration/hash checks.
Never starts a second game when a selected fixture directory is still unfinished.
Image failures remain failures and do not stop unrelated environment collection.
"""
from __future__ import annotations
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import time
import zipfile
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
PIN = '07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c'
TOOLS = ('render-regression.py', 'launch-client.py', 'settled-transparency-protocol.py',
         'check-settled-transparency.py', 'check-transparency-regression.py',
         'check-marker-reference-repeat.py', 'check-restoration-repeat.py', 'measure-native-repeat.py',
         'check-portal-regression.py')
RECIPES = {'vanilla': ('vanilla', 'r28-full-vanilla-r4'),
           'native': ('iris', 'r28-full-native-r3'), 'fsr': ('iris', 'r28-full-fsr-r3')}


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--environments', default='vanilla,native,fsr')
    parser.add_argument('--fixture-revision', default='r1')
    parser.add_argument('--report-revision', default='r1')
    parser.add_argument('--collect-only', action='store_true')
    args = parser.parse_args()
    environments = args.environments.split(',')
    if not environments or any(x not in RECIPES for x in environments) or len(set(environments)) != len(environments):
        parser.error('select unique vanilla,native,fsr environments')
    if not all(value.startswith('r') and value[1:].isdigit() for value in (args.fixture_revision, args.report_revision)):
        parser.error('revisions must be r1, r2, ...')
    reports, logs = ROOT / 'build/reports', ROOT / 'build/logs'
    reports.mkdir(parents=True, exist_ok=True)
    logs.mkdir(parents=True, exist_ok=True)
    batch = 'r29-settled-matrix-' + '-'.join(environments) + '-' + args.report_revision
    journal = reports / (batch + '.jsonl')
    final_path = reports / (batch + '.json')
    if journal.exists() or final_path.exists():
        raise FileExistsError('Batch already exists; retain it and choose a new report revision.')
    jar = ROOT / 'build/libs/skyboxblock-1.0.2-test.8.jar'
    assert sha(jar) == PIN, 'Frozen main jar changed'
    hashes = {name: sha(ROOT / 'tools' / name) for name in TOOLS}
    stages, summaries = [], {}
    def record(event):
        event['observedAt'] = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
        with journal.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(event, ensure_ascii=False, allow_nan=False) + '\n')
        print(json.dumps(event, ensure_ascii=False), flush=True)
    def stable():
        assert sha(jar) == PIN, 'Frozen main jar changed during matrix'
        assert all(sha(ROOT/'tools'/name) == value for name,value in hashes.items()), 'Validation tools changed during matrix; keep evidence and start a new report revision'
    def execute(name, script, arguments, required=False):
        stable()
        path = logs / (name + '-' + args.report_revision + '.log')
        command = [sys.executable, '-B', str(ROOT/'tools'/script), *map(str, arguments)]
        start = time.monotonic()
        record(dict(event='stage-start', stage=name, command=command, log=str(path.relative_to(ROOT))))
        with path.open('xb') as stream:
            completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        stage = dict(event='stage-finished', stage=name, exitCode=completed.returncode,
                     seconds=round(time.monotonic()-start,3), log=str(path.relative_to(ROOT)))
        stages.append(stage)
        record(stage)
        stable()
        if required and completed.returncode != 0:
            raise RuntimeError('Required stage failed: ' + name + '; inspect retained log before continuing')
        return completed.returncode
    record(dict(event='matrix-start', mainJarSha256=PIN, toolSha256=hashes, environments=environments,
                portalWorkStopped=True, productionSourcesModified=False, releaseAccepted=False))
    error = None
    try:
        for env in environments:
            profile, baseline = RECIPES[env]
            old_root = ROOT / 'build/render-regression' / baseline
            old_manifest = read(old_root / 'fixture.json')
            old_meta = read(old_root / 'results/trans-open-none-none-empty.json')
            bits = ':'.join(struct.pack('>f', value).hex() for value in old_meta['rasterJitter'])
            common = ['--profile', profile, '--suite', 'transparency', '--settled-transparency',
                      '--capture-jitter-bits', bits, '--jar', jar, '--timeout', '1800', '--heap', old_manifest['heap']]
            if old_manifest['shader']:
                common += ['--shader', old_manifest['shader'], '--shader-settings', 'instance']
                for key, value in sorted(old_manifest['shaderOptions'].items()):
                    common += ['--shader-option', key+'='+value]
            fixtures = {}
            for role in ('candidate', 'never-a', 'never-b'):
                label = 'r29-settled-' + env + '-' + role + '-' + args.fixture_revision
                fixture = ROOT / 'build/render-regression' / label
                options = list(common) + ['--label', label]
                if role != 'candidate':
                    options += ['--native-transparency-control']
                if fixture.exists():
                    stable()
                    assert (fixture/'run-result.json').is_file(), 'Selected fixture is unfinished; do not duplicate its running job: ' + label
                    m, r = read(fixture/'fixture.json'), read(fixture/'run-result.json')
                    assert m['jarSha256'] == PIN and m['profile'] == profile and m['suite'] == 'transparency'
                    assert m['settledTransparency'] is True and m['captureJitterBits'] == bits
                    assert m['shader'] == old_manifest['shader'] and m['shaderOptions'] == old_manifest['shaderOptions']
                    assert m['nativeTransparencyControl'] is (role != 'candidate')
                    assert r['exitCode'] == 0 and r['finished'] is True and r['scenes'] == 53
                    record(dict(event='reuse-completed-fixture-not-new-run', fixture=label,
                                runResultSha256=sha(fixture/'run-result.json')))
                else:
                    execute(label, 'render-regression.py', options, required=True)
                protocol = reports / (label+'-protocol-'+args.report_revision+'.json')
                execute(label+'-protocol', 'check-settled-transparency.py',
                        [fixture, '--expected-jar-sha256', PIN, '--output', protocol], required=True)
                fixtures[role] = fixture
            if args.collect_only:
                summaries[env] = dict(collectionCompleted=True, analysisCompleted=False,
                                      fixtures={k:str(v.relative_to(ROOT)) for k,v in fixtures.items()})
                continue
            candidate, a, b = (fixtures[k] for k in ('candidate','never-a','never-b'))
            prefix = 'r29-settled-' + env + '-' + args.report_revision
            strict = reports / (prefix+'-transparency.json')
            execute(prefix+'-strict', 'check-transparency-regression.py',
                    [candidate/'results', '--output', strict])
            restoration = reports / (prefix+'-restoration.json')
            execute(prefix+'-restoration', 'check-restoration-repeat.py',
                    ['--candidate',candidate/'results','--control',a/'results','--control',b/'results',
                     '--strict-report',strict,'--output',restoration])
            marker = reports / (prefix+'-water-repeat.json')
            execute(prefix+'-water-repeat', 'check-marker-reference-repeat.py',
                    ['--ref-a',a,'--ref-b',b,'--candidate',candidate,'--expected-jar-sha256',PIN,
                     '--front','water','--output',marker])
            s, restore, water = read(strict), read(restoration), read(marker)
            summaries[env] = dict(collectionCompleted=True, analysisCompleted=True,
                fixtures={k:str(v.relative_to(ROOT)) for k,v in fixtures.items()},
                strictPassed=s['passed'], strictChecks=[s['passedChecks'],s['totalChecks']],
                strictFailures=[c for c in s['checks'] if not c['passed']],
                restorationPassed=restore['passed'], restorationChecks=[restore['passedChecks'],restore['totalChecks']],
                waterRepeatPassed=water['passed'], waterRepeatStatus=water['status'], waterErrors=water['errors'],
                reports=dict(strict=str(strict.relative_to(ROOT)),restoration=str(restoration.relative_to(ROOT)),
                             water=str(marker.relative_to(ROOT))))
            record(dict(event='environment-summary', environment=env, summary=summaries[env]))
    except (OSError, ValueError, KeyError, AssertionError, RuntimeError) as failure:
        error = str(failure)
        record(dict(event='matrix-stopped-with-evidence', error=error))
    result = dict(kind='R29-pinned-settled53-matrix',mainJarSha256=PIN,toolSha256=hashes,
                  environments=summaries,stages=stages,error=error,
                  collectionAndAnalysisComplete=error is None and len(summaries)==len(environments),
                  releaseAccepted=False,oldReportsPreserved=True,portalWorkStopped=True)
    with final_path.open('x',encoding='utf-8') as stream:
        json.dump(result,stream,ensure_ascii=False,indent=2,allow_nan=False)
        stream.write('\n')
    print('MATRIX_REPORT',final_path,flush=True)
    return 0 if result['collectionAndAnalysisComplete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
