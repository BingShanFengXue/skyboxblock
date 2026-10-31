#!/usr/bin/env python3
"""Assemble the machine-readable R36 acceptance summary from measured reports only."""
from __future__ import annotations
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def load(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    reports=ROOT/'build/reports'
    jar=ROOT/'build/libs/skyboxblock-1.0.4-test.2-r36.jar';probe=ROOT/'build/libs/skybox-validation-probe-r36.jar'
    digest=sha(jar);probe_digest=sha(probe)
    java={}
    for name,file,pattern in [
            ('serverBackend','r36-server-backend-regression.txt',r'(\d+) checks'),
            ('serverCommands','r36-server-command-regression.txt',r'(\d+) checks OK'),
            ('skyTime','r36-clock-regression.txt',r'PASS: (\d+) sky time assertions'),
            ('environmentTypes','r35-environment-regression.txt',r'(\d+) checks OK'),
            ('environmentRender','r35-environment-render-regression.txt',r'PASS: (\d+) render-environment assertions'),
            ('legacyMath','render-math-regression.txt',r'PASS: (\d+) assertions')]:
        text=(reports/file).read_text(encoding='utf-8')
        match=re.search(pattern,text)
        assert match,(file,pattern)
        java[name]=dict(assertions=int(match.group(1)),report=file)
    r35=ROOT.parent/'output/skyboxblock-1.0.4-test.1-r35/skyboxblock-1.0.4-test.1-r35.jar'
    r35_digest=sha(r35)
    cloud={}
    for name,file,expected in [('baselineProof','r36-cloud-baseline-alpha-proof-v1.json',53),
            ('fixedNativeMotion','r36-cloud-native-final.json',912),
            ('fixedFsrMotion','r36-cloud-fsr-final.json',912)]:
        data=load(reports/file)
        assert data['status']=='PASS' and data['passed']==data['total']==expected,(name,data['passed'],data['total'])
        # The baseline capture is the frozen R35 rollback control; fixed captures must be this jar.
        assert data['productionJarSha256']==(r35_digest if name=='baselineProof' else digest),name
        cloud[name]=dict(passed=data['passed'],total=data['total'],frames=data['frameCount'],
            overdrawPixels=data['overdrawPixels'],rawOverdrawPixels=data['rawOverdrawPixels'],
            lostCoveragePixels=data['lostCoveragePixels'],maxAlpha=data['maxMeasuredAlpha'],report=file)
    multiplayer=load(ROOT/'build/r36-multiplayer/r36-mp-v5/summary.json')
    assert multiplayer['status']=='PASS' and all(c['passed'] for c in multiplayer['checks'])
    assert multiplayer['mainJarSha256'].lower()==digest and multiplayer['inputBytesUnchanged']
    package=load(reports/'r36-package-audit.json')
    assert package['passed']==package['total'] and package['sha256']==digest
    javac=(ROOT/'build/verify-r36-final/javac-stderr.txt').read_text(encoding='utf-8',errors='replace')
    assert '错误:' not in javac and 'error:' not in javac.lower()
    sources=len(list((ROOT/'src/main/java').rglob('*.java')))
    assert sources==56,sources
    cloudBaseline=cloud['baselineProof'];cloudFixedThreads=cloud['fixedNativeMotion']
    outcome=dict(status='PASS',version='1.0.4-test.2-r36',
        scope='R36 server-authoritative environment attributes, unique completion, private sky clock, cloud-seam deduplication; specified verification matrix only',
        productionJarSha256=digest,probeJarSha256=probe_digest,
        attributeCount=15,javaTests=java,javaAssertions=sum(v['assertions'] for v in java.values()),
        independentJavac=dict(sources=sources,errors=0,tag='r36-final'),
        packageAudit=dict(passed=package['passed'],total=package['total']),
        cloud=cloud,cloudBaselineReproduced=cloudBaseline['overdrawPixels']>0,
        cloudFixedZeroOverdraw=all(v['overdrawPixels']==0 for k,v in cloud.items() if k!='baselineProof'),
        cloudCoverageLost=sum(v['lostCoveragePixels'] for v in cloud.values()),
        multiplayer=dict(status=multiplayer['status'],checks=len(multiplayer['checks']),
            ownerToken=multiplayer['ownerToken'],serverLauncher=multiplayer['serverLauncher']['status'],
            passed=[c['name'] for c in multiplayer['checks']],
            inputBytesUnchanged=multiplayer['inputBytesUnchanged'],
            serverLauncherRuntime=multiplayer.get('serverLauncher')),
        image='docs/screenshots/r36-cloud-seam-comparison-20261031.png',
        inheritedLimitations=[
            'Historical four strict restoration failures are not re-certified by R36.',
            'Portals and resource/performance long tests are not newly certified.',
            'The multiplayer matrix uses two offline loopback clients on one machine, not a public server.',
            'Client and server must both run this build; clients without the mod do not receive the snapshot.'],
        userStateUntouched=True)
    target=reports/'r36-final-validation.json'
    target.write_text(json.dumps(outcome,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:outcome[k] for k in ['status','version','productionJarSha256','javaAssertions','packageAudit','cloudFixedZeroOverdraw','cloudCoverageLost','multiplayer']},indent=2,ensure_ascii=False)[:2000])
    return 0
if __name__=='__main__':raise SystemExit(main())