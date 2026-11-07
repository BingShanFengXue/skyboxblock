#!/usr/bin/env python3
"""Append-only source/evidence snapshot; no installed instance is modified."""
from pathlib import Path
from datetime import datetime
import hashlib, json, subprocess, zipfile
ROOT=Path(__file__).resolve().parents[1]
evidence=ROOT/'docs/evidence/hdr-import-20261108'
report=json.loads((evidence/'acceptance.json').read_text(encoding='utf-8'))
assert report['ok'] and len(report['checks'])==27
listing=ROOT/'build/hdr-tracked-files.txt'
with listing.open('wb') as f:subprocess.run(['git','ls-files','-z'],cwd=ROOT,stdout=f,check=True)
tracked=listing.read_bytes().decode('utf-8').split('\0')
paths=set()
for name in tracked:
 if not name:continue
 p=Path(name);parts=p.parts
 if parts[0] in ('src','gradle','LICENSES') or parts[:2] in (('mc26.2','src'),('mc26.2','gradle'),('mc26.2','LICENSES')):paths.add(p)
 if parts[0]=='tools' and p.suffix in ('.py','.ps1','.mjs'):paths.add(p)
 if parts[:2]==('mc26.2','tools') and p.suffix in ('.py','.ps1','.mjs'):paths.add(p)
 if p.name in ('README.md','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE','NOTICE','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat') and len(parts)<=2:paths.add(p)
for tree in ['src/main/java/dev/yg/skyboxblock/custom','src/test/java/dev/yg/skyboxblock/custom','src/r30Probe/java/dev/yg/skyboxprobe/mixin','mc26.2/src/main/java/dev/yg/skyboxblock/custom','mc26.2/src/test/java/dev/yg/skyboxblock/custom']:
 paths.update(p.relative_to(ROOT) for p in (ROOT/tree).rglob('*.java'))
for pattern in ['hdr-*.py','hdr-*.ps1','check-hdr-live.py','verify-hdr-import.py','verify-command-locales.py','audit-hdr-delivery.py','package-hdr-delivery.py']:
 paths.update(p.relative_to(ROOT) for p in (ROOT/'tools').glob(pattern))
paths.add(Path('docs/HDR-IMPORT-DELIVERY-2026-11-08.md'))
paths.update(p.relative_to(ROOT) for p in evidence.rglob('*') if p.is_file())
for package in report['packages']:paths.add(Path(package['jar']))
archive_dir=ROOT.parent/'artifacts/snapshots';archive_dir.mkdir(parents=True,exist_ok=True)
archive=archive_dir/('skybox-hdr-1.0.7-source-and-evidence-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.zip')
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for rel in sorted(paths):
  source=ROOT/rel
  if not source.is_file():raise FileNotFoundError(source)
  z.write(source,'skybox-mod/'+rel.as_posix())
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
manifest={'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'files':len(paths),'bytes':archive.stat().st_size,'packages':report['packages'],'report':str(ROOT/'docs/HDR-IMPORT-DELIVERY-2026-11-08.md'),'overview':str(evidence/'overview.png')}
(ROOT/'build/hdr-delivery-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(manifest,ensure_ascii=False,indent=2))
