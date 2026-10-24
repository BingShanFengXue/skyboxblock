from pathlib import Path
import zipfile,hashlib,json
ROOT=Path(__file__).resolve().parents[1]
jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';identity=hashlib.sha256(jar.read_bytes()).hexdigest()
out=ROOT.parent/'artifacts/snapshots/skybox-r34-candidate-before-review-20261024.zip'
assert not out.exists()
files=[p for p in (ROOT/'src').rglob('*') if p.is_file()]+[ROOT/'gradle.properties',ROOT/'build.gradle',jar,ROOT/'build/r34-candidate-build-r4.log',ROOT/'build/reports/r34-package-audit.json']
with zipfile.ZipFile(out,'x',zipfile.ZIP_DEFLATED) as z:
 for p in files:z.write(p,'skybox-mod/'+p.relative_to(ROOT).as_posix())
print(json.dumps(dict(snapshot=str(out),files=len(files),jarSha256=identity,snapshotSha256=hashlib.sha256(out.read_bytes()).hexdigest()),indent=2))
