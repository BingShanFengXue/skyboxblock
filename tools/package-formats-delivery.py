#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
files=[]
for rel in [
 'README.md','mc26.2/README.md','build.gradle','gradle.properties','mc26.2/build.gradle','mc26.2/gradle.properties',
 'docs/FORMATS-IMPORT-DELIVERY-2026-11-13.md',
 'src/main/java/dev/yg/skyboxblock/custom/OpenExr.java','src/main/java/dev/yg/skyboxblock/custom/PanoramaImageDecoder.java','src/main/java/dev/yg/skyboxblock/custom/RadianceHdr.java','src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java','src/main/java/dev/yg/skyboxblock/custom/SkyConfigParser.java',
 'mc26.2/src/main/java/dev/yg/skyboxblock/custom/OpenExr.java','mc26.2/src/main/java/dev/yg/skyboxblock/custom/PanoramaImageDecoder.java','mc26.2/src/main/java/dev/yg/skyboxblock/custom/RadianceHdr.java','mc26.2/src/main/java/dev/yg/skyboxblock/custom/CustomSkyRegistry.java','mc26.2/src/main/java/dev/yg/skyboxblock/custom/SkyConfigParser.java',
 'src/test/java/dev/yg/skyboxblock/custom/OpenExrRegression.java','src/test/java/dev/yg/skyboxblock/custom/HdrRegistryRegression.java','src/test/java/dev/yg/skyboxblock/custom/SkyConfigRegression.java','src/test/java/dev/yg/skyboxblock/SkyMaterialIdentityRegression.java',
 'mc26.2/src/test/java/dev/yg/skyboxblock/custom/OpenExrRegression.java','mc26.2/src/test/java/dev/yg/skyboxblock/custom/HdrRegistryRegression.java','mc26.2/src/test/java/dev/yg/skyboxblock/custom/SkyConfigRegression.java','mc26.2/src/test/java/dev/yg/skyboxblock/SkyMaterialIdentityRegression.java',
 'tools/verify-hdr-import.py','tools/verify-command-locales.py','tools/audit-custom-owner.py']:
    p=ROOT/rel
    if p.is_file():files.append(p)
for jar in [ROOT/'build/libs/skyboxblock-1.0.7-test.3-formats.jar',ROOT/'mc26.2/build/libs/skyboxblock-1.0.7-test.3-formats-port.1-mc26.2.jar']:
    files.append(jar)
archive_dir=ROOT.parent/'artifacts/snapshots';archive_dir.mkdir(parents=True,exist_ok=True)
archive=archive_dir/('skybox-formats-1.0.7-source-and-evidence-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.zip')
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in files:z.write(p,'skybox-mod/'+p.relative_to(ROOT).as_posix())
manifest={'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'files':len(files),'packages':[]}
for p in files:
    if p.suffix=='.jar':manifest['packages'].append({'path':str(p.relative_to(ROOT)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size})
(ROOT/'build/formats-final-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(manifest,ensure_ascii=False,indent=2))
