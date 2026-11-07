#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import hashlib,json,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1];e=ROOT/'docs/evidence/custom-owner-20261108';accept=json.loads((e/'acceptance.json').read_text(encoding='utf-8'));assert accept['ok']
listing=ROOT/'build/owner-package-files.txt'
with listing.open('wb') as f:subprocess.run(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=ROOT,stdout=f,check=True)
paths=set()
for text in listing.read_bytes().decode('utf-8').split('\0'):
 if not text:continue
 p=Path(text);parts=p.parts
 if parts[0] in ('src','gradle','LICENSES') or parts[:2] in (('mc26.2','src'),('mc26.2','gradle'),('mc26.2','LICENSES')):paths.add(p)
 if parts[0]=='tools' and p.suffix in ('.py','.ps1','.mjs'):paths.add(p)
 if parts[:2]==('mc26.2','tools') and p.suffix in ('.py','.ps1','.mjs'):paths.add(p)
 if p.name in ('README.md','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE','NOTICE','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat') and len(parts)<=2:paths.add(p)
paths.add(Path('docs/CUSTOM-OWNER-FIX-2026-11-08.md'));paths.update(p.relative_to(ROOT) for p in e.rglob('*') if p.is_file());paths.update(Path(p['jar']) for p in accept['packages'])
dest=ROOT.parent/'artifacts/snapshots';dest.mkdir(parents=True,exist_ok=True);archive=dest/('skybox-custom-owner-fix-source-and-evidence-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.zip')
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p in sorted(paths):z.write(ROOT/p,'skybox-mod/'+p.as_posix())
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
result={'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size,'files':len(paths),'packages':accept['packages'],'report':str(ROOT/'docs/CUSTOM-OWNER-FIX-2026-11-08.md'),'comparison':str(e/'before-after.png')};(ROOT/'build/custom-owner-delivery.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2))
