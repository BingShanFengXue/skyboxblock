#!/usr/bin/env python3
"""Temporary synthetic archive contracts only; never archives the real release or touches history."""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,tempfile,zipfile
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parent.parent
s=importlib.util.spec_from_file_location('archive_contract',ROOT/'tools/archive-test5.py');a=importlib.util.module_from_spec(s);s.loader.exec_module(a)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def text(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(v,encoding='utf-8')
def data(p,v):text(p,json.dumps(v))
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);args=p.parse_args();checks=[]
 def check(name,ok,**detail):checks.append(dict(name=name,passed=bool(ok),**detail));print('PASS' if ok else 'FAIL',name)
 with tempfile.TemporaryDirectory(prefix='skybox-archive-contract-') as raw:
  temp=Path(raw).resolve();assert temp.parent==Path(tempfile.gettempdir()).resolve() and temp.name.startswith('skybox-archive-contract-')
  root=temp/'workspace'/'skybox-mod';root.mkdir(parents=True);snap=root.parent/'artifacts'/'snapshots';snap.mkdir(parents=True)
  text(root/'src/main/java/TEMP.java','// Synthetic TEMP archive contract, not production code\n')
  text(root/'tools/TEMP.py','# Synthetic archive contract\n')
  text(root/'README.md','# TEMP fixture\n[Source](<src/main/java/TEMP.java>)\n')
  for name in ['LICENSE','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSES/TEMP-MIT.txt']:
   text(root/name,'Synthetic TEMP notice: '+name+'\n')
  text(root/'gradle.properties','mod_version=TEMP\n')
  text(root/'build.gradle','// Synthetic TEMP build metadata; never executed\n')
  text(root/'settings.gradle','rootProject.name="TEMP"\n')
  text(root/'gradle/wrapper/gradle-wrapper.properties','distributionUrl=TEMP-not-executable\n')
  jar=root/'build/libs/TEMP.jar';jar.parent.mkdir(parents=True)
  with zipfile.ZipFile(jar,'w') as z:z.writestr('fabric.mod.json',json.dumps(dict(id='skyboxblock',version='TEMP',authors=['Bing6512'])))
  source=jar.with_name('TEMP-sources.jar')
  with zipfile.ZipFile(source,'w') as z:z.writestr('TEMP.java','// TEMP synthetic\n')
  harness=jar.with_name('TEMP-harness.jar')
  with zipfile.ZipFile(harness,'w') as z:z.writestr('fabric.mod.json',json.dumps(dict(id='skybox_ip_fixture',version='TEMP')))
  fixture=root/'build/render-regression/TEMP';data(fixture/'fixture.json',dict(synthetic=True))
  data(fixture/'run-result.json',dict(exitCode=0,finished=True,synthetic=True))
  text(fixture/'client.log','SYNTHETIC TEMP metadata, not an actual Minecraft run\n')
  data(fixture/'results/TEMP.json',dict(synthetic=True));image=root/'docs/screenshots/TEMP.png';image.parent.mkdir(parents=True);Image.new('RGB',(2,2),(20,40,60)).save(image)
  acceptance=root/'build/reports/TEMP-acceptance.json'
  valid=dict(passed=True,jar=str(jar.relative_to(root)),jarSha256=sha(jar),sourceJar=str(source.relative_to(root)),sourceJarSha256=sha(source),harnessJar=str(harness.relative_to(root)),harnessSha256=sha(harness),fixtures=[dict(directory=str(fixture.relative_to(root)))],selectedEvidence=[str(image.relative_to(root))],reports=[])
  data(acceptance,valid);destination=snap/'TEMP-final.zip';result=a.archive_acceptance(acceptance,destination,project_root=root)
  check('synthetic-valid-acceptance-creates-new-archive',destination.is_file())
  original=sha(destination)
  with zipfile.ZipFile(destination) as z:
   names=z.namelist();check('archive-CRC-integrity',z.testzip() is None)
   for name in ['AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSES/TEMP-MIT.txt','src/main/java/TEMP.java']:
    check('archive-preserves-'+name,any(n==name or n.endswith('/'+name) for n in names))
   check('selected-image-included',any(n.endswith('/docs/screenshots/TEMP.png') or n=='docs/screenshots/TEMP.png' for n in names))
   check('all-three-package-identities-are-included',all(any(n.endswith('/'+f.name) or n==f.name for n in names) for f in (jar,source,harness)))
  sidecar=destination.with_suffix('.manifest.json');check('success-sidecar-exists',sidecar.is_file())
  def rejected(name,patch,dest=None):
   candidate=dict(valid);candidate.update(patch);data(acceptance,candidate)
   target=dest or snap/(name+'.zip')
   try:a.archive_acceptance(acceptance,target,project_root=root);ok=False
   except a.ArchiveError:ok=True
   check(name,ok)
  rejected('existing-archive-is-never-overwritten',{},destination)
  check('original-archive-bytes-remain-identical',sha(destination)==original)
  rejected('literal-false-acceptance-rejected',dict(passed=False))
  rejected('string-true-is-not-boolean-acceptance',dict(passed='true'))
  rejected('integer-one-is-not-boolean-acceptance',dict(passed=1))
  rejected('main-package-pin-mismatch-rejected',dict(jarSha256='0'*64))
  rejected('source-package-pin-mismatch-rejected',dict(sourceJarSha256='0'*64))
  rejected('harness-package-pin-mismatch-rejected',dict(harnessSha256='0'*64))
  outside=temp/'outside.jar';outside.write_bytes(b'Synthetic TEMP outside-root artifact')
  rejected('project-containment-escape-rejected',dict(jar=str(outside),jarSha256=sha(outside)))
  rejected('destination-outside-snapshots-rejected',{},root/'build/not-snapshots.zip')
  check('real-workspace-history-never-touched',not root.is_relative_to(ROOT) and destination.is_relative_to(temp))
 report=dict(kind='TEMP-synthetic-archive-selftest-not-real-release',passed=all(x['passed'] for x in checks),passedChecks=sum(x['passed'] for x in checks),totalChecks=len(checks),checks=checks,toolSha256=sha(ROOT/'tools/archive-test5.py'))
 output=args.output.resolve();output.relative_to(ROOT);output.parent.mkdir(parents=True,exist_ok=True)
 with output.open('x',encoding='utf-8') as f:json.dump(report,f,indent=2,allow_nan=False)
 print(f"{report['passedChecks']}/{report['totalChecks']} TEMP archive contracts",output)
 return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
