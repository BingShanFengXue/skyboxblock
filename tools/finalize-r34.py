"""Join actual installed-package identities, client results and scoped independent image checks."""
from pathlib import Path
import json,hashlib
ROOT=Path(__file__).resolve().parents[1]
def load(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def validate_startup_errors(directory,run,fixture):
    network=[e for e in run['errors'] if '[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in e]
    extra=[e for e in run['errors'] if e not in network]
    startup=[]
    if extra:
        text=(directory/'client.log').read_text(encoding='utf-8',errors='replace');first=text.find('[skybox-regression] CAPTURED')
        assert fixture.get('negativeSurfaceLayer') and first>=0
        for error in extra:
            assert '[DH-ClientTickTimer/ERROR]' in error and "LodQuadTree shouldn't be updating renderSections below the expected detail level" in error
            assert 0<=text.find(error)<first
            startup.append(error)
        retry_dir=ROOT/'build/render-regression/r34-dh-startup-retry-control-fsr2';retry=load(retry_dir/'run-result.json');rf=load(retry_dir/'fixture.json')
        assert retry['exitCode']==0 and retry['finished'] and retry['scenes']==2 and not retry['errors'] and not retry['fatalErrors'] and not retry['renderThreadViolations']
        assert rf['jarSha256']==fixture['jarSha256'] and rf['shaderOptions']==fixture['shaderOptions'] and rf['negativeSurfaceLayer']
    return startup
def main():
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';identity=sha(jar);reports=ROOT/'build/reports'
 matrix=load(reports/'r34-edge-matrix.json');regression=load(reports/'r34-regression-matrix.json');comparison=load(reports/'r34-edge-comparison.json');package=load(reports/'r34-package-audit.json')
 dh=load(reports/'r34-dh-edge-matrix.json')
 assert matrix['complete'] and matrix['passed'] and regression['complete'] and regression['passed'] and comparison['passed'] and package['passed'] and dh['complete'] and dh['passed']
 assert matrix['jarSha256']==regression['jarSha256']==package['jarSha256']==identity
 runs=[];positive_scenes=positive_checks=control_scenes=dh_scenes=0
 for row in matrix['runs']+regression['runs']+dh['runs']:
  label=row['label'];directory=ROOT/'build/render-regression'/label;fixture=load(directory/'fixture.json');run=load(directory/'run-result.json');oracle=load(reports/f'{label}-oracle.json')
  assert row['client']==row['oracle']==run['exitCode']==0 and run['finished']
  assert fixture['jarSha256']==identity and sha(next((directory/'mods').glob('skyboxblock-*.jar')))==identity
  assert not run['fatalErrors'] and not run['renderThreadViolations']
  external=[e for e in run['errors'] if '[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in e]
  startup_dh=validate_startup_errors(directory,run,fixture)
  negative=fixture.get('negativeSurfaceLayer',False)
  if negative:
   assert all(f['name']=='bounded geometric edge error' for f in oracle['failures']);control_scenes+=run['scenes']
  else:
   assert oracle['passed'];positive_scenes+=run['scenes'];positive_checks+=oracle['checks']
  if fixture['shader']:
   assert fixture['shader']=='iterationRP Alpha 0.8.31.zip' and fixture['shaderOptions']['MOTION_BLUR']=='false'
  runs.append(dict(label=label,scenes=run['scenes'],checks=oracle['checks'],negativeSurfaceLayer=negative,externalNetworkErrors=external,nonfatalStartupDhErrors=startup_dh,renderingErrors=0,glAndOraclePassed=oracle['passed'] if not negative else None,expectedOldEdgeFailures=len(oracle['failures']) if negative else 0,jarSha256=identity))
 props=load(reports/'r34-coverage-properties.json');counterfactual=load(reports/'r34-oracle-counterfactual-final.json');poses=load(reports/'r34-static-pose-pairs.json');assert props['passed'] and poses['passed'] and len(counterfactual['failures'])==2
 assert 'PASS: 389385 assertions' in (ROOT/'build/r34-candidate-build-r4.log').read_text(encoding='utf-8')
 hand=[load(reports/f'r34-regression-handexposure-{e}-oracle.json') for e in ['native','fsr2']]
 max_white=max(m['whiteFraction248'] for r in hand for m in r['measurements'])
 report=dict(version='1.0.3-test.4-r34',jarSha256=identity,passed=True,positiveScenes=positive_scenes,positiveImageChecks=positive_checks,controlScenes=control_scenes,allScenes=positive_scenes+control_scenes,packageChecks=package['checks'],mathAssertions=389385,coveragePropertyCases=props['samples'],counterfactualExpectedFailures=2,maximumMixedRoomHandWhiteFraction248=max_white,comparison=comparison['results'],nativeDhSupplement=dh,runs=runs,
  scope='MC1.21.1/Fabric; exact Iris1.8.1/Sodium0.6.7/DH3.3.3/ITRP Alpha0.8.31 native+FSR2; no-shader Fabric, Iris/Sodium disabled-pack and native DH foreground controls',
  limits=['4x geometric sky coverage, not full-scene MSAA/TAA','Historical strict restoration four red items, portals and resource/performance long tests remain outside this scoped acceptance','Initial drift-contaminated r34-baseline-r1 is excluded; valid controls use same candidate package with only negativeSurfaceLayer=true','Fixture cloud phase held; shader time, automatic exposure and bloom remain active'])
 (reports/'r34-final-acceptance.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k not in ['runs','comparison']},indent=2))
 return 0
if __name__=='__main__':raise SystemExit(main())
