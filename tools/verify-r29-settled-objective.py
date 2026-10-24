#!/usr/bin/env python3
"""Final evidence audit for the scoped R29 goal, never a release acceptance gate.

Does not launch Minecraft, modify old reports, waive image failures, or infer a
pass from the matrix runner's execution-only exit code.
"""
from __future__ import annotations
import argparse
import ast
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PIN = '07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c'
ENVIRONMENTS = ('vanilla', 'native', 'fsr')
ROLES = ('candidate', 'never-a', 'never-b')


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            h.update(block)
    return h.hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def read_log(path):
    raw=Path(path).read_bytes()
    if raw.startswith((b'\xff\xfe',b'\xfe\xff')):
        return raw.decode('utf-16')
    return raw.decode('utf-8-sig')


def defaults(source):
    tree=ast.parse(source)
    for node in tree.body:
        if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='DEFAULTS' for t in node.targets):
            return {item.arg:ast.literal_eval(item.value) for item in node.value.keywords}
    raise ValueError('DEFAULTS declaration absent')


def functions(source,names):
    return {n.name:ast.dump(n,include_attributes=False) for n in ast.parse(source).body
            if isinstance(n,ast.FunctionDef) and n.name in names}


def audit(matrix_path,output):
    checks=[]
    report=dict(kind='R29-whole-scoped-objective-evidence-audit-NOT-release',
                matrix=str(matrix_path),checks=checks,environments={},releaseAccepted=False,
                productionJarSha256=PIN,oldFailuresPreserved=True)
    def add(name,passed,**detail):
        checks.append(dict(name=name,passed=bool(passed),**detail))
    def ledger(entries,context):
        if not isinstance(entries,dict) or not entries:
            add(context+':all-recorded-inputs-still-match',False,error='Missing/nonempty input ledger required')
            return
        errors=[]
        for text,identity in entries.items():
            path=Path(text).resolve(strict=True)
            if not path.is_relative_to(ROOT):
                errors.append(dict(path=text,error='input escapes project'))
            elif digest(path)!=identity['sha256']:
                errors.append(dict(path=text,error='recorded input bytes changed'))
        add(context+':all-recorded-inputs-still-match',not errors,inputs=len(entries),errors=errors)
    try:
        matrix_path=matrix_path.resolve(strict=True)
        matrix=load(matrix_path)
        add('matrix-is-complete-execution-not-an-assumed-pass',matrix.get('collectionAndAnalysisComplete') is True
            and matrix.get('error') is None and set(matrix.get('environments',{}))==set(ENVIRONMENTS))
        add('matrix-main-package-pin',matrix.get('mainJarSha256')==PIN
            and digest(ROOT/'build/libs/skyboxblock-1.0.2-test.8.jar')==PIN)
        add('validation-tools-still-match-frozen-matrix',all(digest(ROOT/'tools'/name)==pin
            for name,pin in matrix['toolSha256'].items()))
        original_snapshot=ROOT.parent/'artifacts/snapshots/skybox-r29-protocol-before-20261022T1529.zip'
        with zipfile.ZipFile(original_snapshot) as frozen:
            old_source=frozen.read('tools/check-transparency-regression.py').decode('utf-8')
            old_defaults=defaults(old_source)
            old_function_names={n.name for n in ast.parse(old_source).body if isinstance(n,ast.FunctionDef)}-{'main'}
            old_functions=functions(old_source,old_function_names)
            old_budget=functions(frozen.read('tools/check-restoration-repeat.py').decode('utf-8'),('budget','acceptable'))
        add('all-original-image-thresholds-identical',defaults((ROOT/'tools/check-transparency-regression.py').read_text(encoding='utf-8'))==old_defaults)
        add('all-original-image-math-ROI-load-and-check-functions-identical',
            functions((ROOT/'tools/check-transparency-regression.py').read_text(encoding='utf-8'),old_function_names)==old_functions,
            functions=len(old_function_names))
        add('native-budget-hard-caps-and-acceptability-functions-identical',
            functions((ROOT/'tools/check-restoration-repeat.py').read_text(encoding='utf-8'),('budget','acceptable'))==old_budget)
        old=load(ROOT/'build/reports/r28-final-validation.json')
        add('R28-nine-original-failures-remain-red',old['candidateTestBuildAccepted'] is False
            and old['releaseAccepted'] is False and len(old['criticalFailures'])==4
            and len(old['retainedRecoveryDiagnostics'])==5
            and old['metrics']['transparency']==dict(passed=3675,total=3684,allPassed=False))
        new_tests=load(ROOT/'build/reports/r29-settled-selftest-r3.json')
        old_tests=load(ROOT/'build/reports/r29-protocol-legacy-marker-r2.json')
        add('new-protocol-89-controls-passed',new_tests['passed'] is True
            and new_tests['passedChecks']==new_tests['totalChecks']==89)
        add('legacy-repeat-226-controls-passed',old_tests['passed'] is True
            and old_tests['passedChecks']==old_tests['total']==226)
        for name,count,fragment in (('transparency',54,'54/54 oracle controls passed'),
                                    ('restoration',5,'5/5 native-calibration control invariants PASS')):
            log=ROOT/'build/logs'/('r29-protocol-legacy-'+name+'-r2.log')
            add('legacy-'+name+'-controls-passed',fragment in read_log(log),assertions=count)
        roots=[];image_passed=image_total=protocol_passed=protocol_total=0
        full_strict=full_restoration=full_water=True
        for env in ENVIRONMENTS:
            info=matrix['environments'][env]
            add(env+':collection-and-analysis-complete',info.get('collectionCompleted') is True
                and info.get('analysisCompleted') is True and set(info.get('fixtures',{}))==set(ROLES))
            for role in ROLES:
                fixture=(ROOT/info['fixtures'][role]).resolve(strict=True)
                roots.append(fixture)
                manifest=load(fixture/'fixture.json');runtime=load(fixture/'run-result.json')
                add(env+':'+role+':actual-complete-53-run',runtime['exitCode']==0
                    and runtime['finished'] is True and runtime['scenes']==53
                    and runtime['errors']==runtime['fatalErrors']==runtime['renderThreadViolations']==[])
                add(env+':'+role+':actual-role-and-pinned-package',manifest['jarSha256']==PIN
                    and manifest['nativeTransparencyControl'] is (role!='candidate'))
                installed=[]
                for path in (fixture/'mods').glob('*.jar'):
                    with zipfile.ZipFile(path) as archive:
                        if 'fabric.mod.json' in archive.namelist() and json.loads(archive.read('fabric.mod.json')).get('id')=='skyboxblock':
                            installed.append(path)
                add(env+':'+role+':single-actual-installed-jar-pin',len(installed)==1 and digest(installed[0])==PIN)
                # The matrix creates this exact canonical protocol report for each role.
                suffix=matrix_path.stem.rsplit('-',1)[-1]
                protocol_path=ROOT/'build/reports'/(fixture.name+'-protocol-'+suffix+'.json')
                protocol=load(protocol_path)
                add(env+':'+role+':settled53-gate-passed',protocol['passed'] is True
                    and protocol['protocol']['metadataCount']==53
                    and protocol['protocol']['measurementCount']==51
                    and protocol['protocol']['neverSky'] is (role!='candidate'))
                ledger(protocol['inputs'],env+':'+role+':protocol')
            strict=load(ROOT/info['reports']['strict'])
            restore=load(ROOT/info['reports']['restoration'])
            water=load(ROOT/info['reports']['water'])
            add(env+':strict-thresholds-match-original',strict['thresholds']==old_defaults)
            gate=[c for c in strict['checks'] if c['name']=='complete-settled53-producer-protocol']
            image=[c for c in strict['checks'] if c['name']!='complete-settled53-producer-protocol']
            add(env+':one-separate-new-protocol-check',len(gate)==1 and gate[0]['passed'] is True)
            add(env+':all-original-1228-image-checks-retained',len(image)==1228)
            image_passed+=sum(c['passed'] for c in image);image_total+=len(image)
            protocol_passed+=sum(c['passed'] for c in gate);protocol_total+=len(gate)
            full_strict &= strict['passed'] is True
            full_restoration &= restore['passed'] is True
            full_water &= water['passed'] is True
            ledger(water.get('inputs'),env+':water-repeat')
            add(env+':water-repeat-bound-to-same-actual-three-roots',
                {Path(p).resolve(strict=True) for p in water.get('fixtureRoots',[])}==
                {(ROOT/info['fixtures'][role]).resolve(strict=True) for role in ROLES})
            failures=[c for c in strict['checks'] if not c['passed']]
            report['environments'][env]=dict(strictPassed=strict['passed'],
                originalImageChecks=[sum(c['passed'] for c in image),len(image)],
                newProtocolChecks=[sum(c['passed'] for c in gate),len(gate)],
                strictFailures=failures,restorationPassed=restore['passed'],
                restorationFailedChecks=[c for c in restore['checks'] if not c['passed']],
                waterRepeatPassed=water['passed'],waterStatus=water['status'],
                waterErrors=water['errors'],
                waterCases={key:{field:case.get(field) for field in
                    ('status','referenceRepeatAdmissible','empiricalRepeatEvidenceValid','leakDetected','legacyWaterFlags')}
                    for key,case in water['cases'].items()},reports=info['reports'])
        add('nine-distinct-real-fixture-roots',len(roots)==len(set(roots))==9)
        report['originalImageChecks']=[image_passed,image_total]
        report['separateProtocolChecks']=[protocol_passed,protocol_total]
        report['strictTransparentChecksAllPassed']=bool(full_strict)
        report['boundedRestorationAllPassed']=bool(full_restoration)
        report['independentWaterReferencesAllSupported']=bool(full_water)
    except (OSError,ValueError,KeyError,TypeError,zipfile.BadZipFile) as error:
        add('complete-readable-whole-objective-evidence',False,error=str(error))
    report['integrityChecks']=[sum(c['passed'] for c in checks),len(checks)]
    report['wholeEvidenceComplete']=bool(checks) and all(c['passed'] for c in checks)
    report['scopedTransparencyVerified']=report['wholeEvidenceComplete'] and all(report.get(k) is True for k in
        ('strictTransparentChecksAllPassed','boundedRestorationAllPassed','independentWaterReferencesAllSupported'))
    report['status']='VERIFIED_SCOPED_NOT_RELEASE' if report['scopedTransparencyVerified'] else (
        'EVIDENCE_COMPLETE_WITH_OPEN_ITEMS' if report['wholeEvidenceComplete'] else 'INCOMPLETE_OR_INCONSISTENT')
    report['limitations']=['Scoped settled transparency only; no new release, deployment, high-wall, dynamic DH, performance or independent final audit pass.',
        'Distinct acquired fixture roots/file identities and pinned observations do not cryptographically authenticate acquisition.',
        'Historical failures remain preserved; no companion or empirical proof changes the original image thresholds or flags.']
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x',encoding='utf-8') as stream:
        json.dump(report,stream,ensure_ascii=False,indent=2,allow_nan=False)
        stream.write('\n')
    print(report['status'],report['integrityChecks'],str(output),flush=True)
    return 0 if report['scopedTransparencyVerified'] else 1


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix',type=Path,default=ROOT/'build/reports/r29-settled-matrix-vanilla-native-fsr-r1.json')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    raise SystemExit(audit(args.matrix,args.output))
