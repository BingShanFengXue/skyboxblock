#!/usr/bin/env python3
"""Create an append-only, byte-verified scoped test.6 deliverable, never a formal-release override."""
from pathlib import Path
from datetime import datetime
import hashlib,json,shutil,zipfile
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'build/deliverables'
NAME='skyboxblock-test6-r26-20261020'
STAGE=OUT/NAME
PRIMARY=ROOT/'build/libs/skyboxblock-1.0.2-test.6.jar'
SOURCES=ROOT/'build/libs/skyboxblock-1.0.2-test.6-sources.jar'
EXPECTED='c1ceb286dbf106ca86cec9ca5b44f4f2acd7138bf3467a9574b178c957bec0fb'

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8'))
assert sha(PRIMARY)==EXPECTED
assert sha(SOURCES)=='5ba7b431e8f82b7a9aff30bb3c9036f801edcdf1bd5dea3a70773199b3d8d4d6'
assert sha(ROOT/'build/libs/skyboxblock-1.0.2-test.4.jar')=='59ccfd220789db8ebbc0b041f1818d747ae4e84ce915a70ca0658a49398f567d'
assert read(ROOT/'build/reports/r26-test6-reference-final-r5.json')['passed']
assert read(ROOT/'build/reports/r26-blue-iris-r5.json')['blueCastImproved']
assert read(ROOT/'build/reports/r26-blue-fsr-r5.json')['blueCastImproved']
assert read(ROOT/'build/reports/r26-oracle-controls.json')['passed']
assert read(ROOT/'build/reports/r26-runtime-bindings.json')['runtimeBindingPassed']
validation=read(ROOT/'build/reports/r26-validation-summary-r5.json')
assert validation['portalTestsPerformed'] is False
for env in ['vanilla','iris','fsr']:
    for kind in ['world','alignment']:
        checks=read(ROOT/f'build/reports/r26-test6-smoke-{env}-r5-{kind}.json')
        assert len(checks)==1 and checks[0]['passed'],(env,kind)
labels=['r26-test6-reference-final-r5',*[f'r26-test6-smoke-{e}-r5' for e in ['vanilla','iris','fsr']],
        'r26-test6-transparency-iris-r5','r26-test6-blue-iris-r5','r26-test6-blue-fsr-after-r5']
runtimes=[]
for label in labels:
    instance=ROOT/'build/render-regression'/label
    runtime=read(instance/'run-result.json')
    assert runtime['exitCode']==0 and runtime['finished'] and not runtime['errors'] and not runtime['fatalErrors'] and not runtime['renderThreadViolations'],label
    installed=instance/'mods'/PRIMARY.name;assert sha(installed)==EXPECTED,label
    ids=[]
    for mod in (instance/'mods').glob('*.jar'):
        with zipfile.ZipFile(mod) as z:
            if 'fabric.mod.json' in z.namelist():ids.append(json.loads(z.read('fabric.mod.json'))['id'])
    assert not any('immersive' in i or i=='distanthorizons' for i in ids),ids
    runtimes.append({'fixture':label,'mods':ids,'jarSha256':sha(installed),'runtime':runtime})
STAGE.mkdir(parents=True,exist_ok=False)
def copy(source,target=None):
    source=ROOT/source if not isinstance(source,Path) else source
    dest=STAGE/(target or source.relative_to(ROOT))
    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest)
    assert sha(source)==sha(dest)
copy(PRIMARY,'mods/'+PRIMARY.name);copy(SOURCES,'source/'+SOURCES.name)
for file in ['LICENSE','AUTHORS.md','docs/VANILLA-CLOUD-HEIGHT.md','docs/VANILLA-SKY-R26-DELIVERY-2026-10-20.md']:
    copy(file)
reports=['r26-test6-reference-final-r5.json','r26-blue-iris-r5.json','r26-blue-fsr-r5.json','r26-oracle-controls.json',
         'r26-validation-summary-r5.json','r26-runtime-bindings.json','r26-test6-transparency-iris-r5.json',
         'r26-blue-iris-r4.json',
         'r26-vanilla-reference-comparison.png','r26-blue-halo-before-after.png']
reports += [f'r26-test6-smoke-{e}-r5-{k}.json' for e in ['vanilla','iris','fsr'] for k in ['world','alignment']]
for name in reports:copy('build/reports/'+name)
copy('build/logs/r26-test6-build-r5.log')
for name in ['ref-moon-0','ref-moon-1','ref-horizon-0','ref-horizon-12000','ref-horizon-23000']:
    for suffix in ['-native-sky.png','-scene-base.png']:
        copy(ROOT/'build/render-regression/r26-test6-reference-final-r5/results'/(name+suffix),'evidence/vanilla/'+name+suffix)
for label,folder in [('r26-test6-blue-iris-r3','before'),('r26-test6-blue-iris-r5','after')]:
    copy(ROOT/'build/render-regression'/label/'results/blue-night-settled.png','evidence/blue/'+folder+'.png')
project=STAGE/'source/project-source.zip'
with zipfile.ZipFile(project,'x',zipfile.ZIP_DEFLATED) as z:
    for root in ['src','tools']:
        for path in sorted((ROOT/root).rglob('*')):
            if path.is_file() and path.suffix in {'.java','.json','.vsh','.fsh','.properties','.png','.mcmeta','.py','.ps1'} and '__pycache__' not in path.parts:
                z.write(path,path.relative_to(ROOT).as_posix())
    for file in ['build.gradle','gradle.properties','settings.gradle','LICENSE','AUTHORS.md']:
        path=ROOT/file
        if path.exists():z.write(path,file)
README='''Sky Box Block 1.0.2-test.6 / R26 - practical test candidate\nAuthor: Bing6512\nMinecraft 1.21.1 / Fabric / Java 21\n\nINSTALL: Install only mods/skyboxblock-1.0.2-test.6.jar, keep one skyboxblock version.\nDo not install the source jars or test tools as mods. Fabric API is required.\nNo shader ZIP, user world, or third-party mod is included.\n\nCloud height commands (client-local, only the vanilla sky block):\n/skyboxblock cloudheight 240\n/skyboxblock cloudheight\n/skyboxblock cloudheight reset\n/skyboxblock reload\nConfig: config/skyboxblock.properties, vanillaCloudHeight=240.0\nDefault is 192. No change to native dimension clouds or other sky-block types.\n\nMoon UV and sunrise/sunset now match actual vanilla base-sky rendering.\nITRP night halo uses exposure-relative HDR input; bloom stays enabled.\nPortal compatibility work is stopped. This is NOT a formal release-gate pass.\nRead docs/VANILLA-SKY-R26-DELIVERY-2026-10-20.md for scope, evidence and retained limits.\n'''
(STAGE/'README.txt').write_text(README,encoding='utf-8')
trans=read(ROOT/'build/reports/r26-test6-transparency-iris-r5.json')
manifest={'kind':'scoped-practical-test-candidate','version':'1.0.2-test.6','batch':'R26','author':'Bing6512',
          'createdAt':datetime.now().astimezone().isoformat(),'releaseAccepted':False,'portalWorkStopped':True,
          'primaryJarSha256':EXPECTED,'sourceJarSha256':sha(SOURCES),'runtimeBindings':runtimes,
          'transparencyDiagnosticPassed':trans.get('passed'),'priorCompleteReleaseGateRevalidated':False,
          'files':[{'path':p.relative_to(STAGE).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(STAGE.rglob('*')) if p.is_file()]}
(STAGE/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
archive=OUT/(NAME+'.zip')
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(STAGE.rglob('*')):
        if p.is_file():z.write(p,p.relative_to(STAGE).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert len(z.namelist())==len([p for p in STAGE.rglob('*') if p.is_file()])
    for name in z.namelist():assert hashlib.sha256(z.read(name)).hexdigest()==sha(STAGE/name),name
qa={'passed':True,'archive':str(archive.relative_to(ROOT)),'archiveSha256':sha(archive),'bytes':archive.stat().st_size,
    'entries':len(z.namelist()),'primaryJarSha256':EXPECTED,'sourceJarSha256':sha(SOURCES),
    'candidateCaptures':sum(x['runtime']['scenes'] for x in runtimes),'releaseAccepted':False,
    'allStagedBytesAndZipEntriesVerified':True}
with (ROOT/'build/reports/r26-package-qa.json').open('x',encoding='utf-8') as f:json.dump(qa,f,indent=2)
print(json.dumps(qa,indent=2))
