"""Append-only final deliverables, no installed instance or save writes."""
from pathlib import Path
import json,hashlib,zipfile,shutil
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 result=json.loads((ROOT/'build/reports/r34-2-final-acceptance.json').read_text(encoding='utf-8'));assert result['passed']
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.2.jar';assert sha(jar)==result['jarSha256']
 report=ROOT/'docs/EDGES-R34-2-DELIVERY-2026-10-24.md';review=ROOT/'docs/INDEPENDENT-R34-DEEPSEEK-READONLY-2026-10-24.md';assert report.is_file() and review.is_file()
 review_text=review.read_text(encoding='utf-8');assert result['jarSha256'] in review_text,'Independent review missing final SHA'
 out=ROOT.parent/'output/skyboxblock-1.0.3-test.4-r34.2';out.mkdir(exist_ok=False)
 files=[jar,ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.2-sources.jar',report,review,ROOT/'docs/screenshots/r34-2-final-comparison-20261024.png',ROOT/'docs/screenshots/r34-2-final-jitter-20261024.gif',ROOT/'build/reports/r34-2-final-acceptance.json',ROOT/'build/reports/r34-2-package-audit.json',ROOT/'build/reports/r34-2-lod-background-oracle.json',ROOT/'LICENSE',ROOT/'AUTHORS.md',ROOT/'THIRD_PARTY_NOTICES.md']
 for p in files:shutil.copy2(p,out/p.name)
 source=out/'skybox-r34-2-source-and-evidence.zip'
 inputs=[p for folder in ['src','tools','gradle','LICENSES'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
 inputs += [ROOT/n for n in ['README.md','build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat','LICENSE','AUTHORS.md','THIRD_PARTY_NOTICES.md']]
 inputs += [p for p in (ROOT/'docs').glob('*R34*.md')]+[p for p in (ROOT/'build/reports').glob('r34-*.json')]
 inputs += [ROOT/'build/r34-2-native-background-build.log',ROOT/'build/r34-2-backdrop-probe-build.log',ROOT/'docs/screenshots/r34-2-final-comparison-20261024.png',ROOT/'docs/screenshots/r34-2-final-jitter-20261024.gif']
 for r in result['runs']:
  d=ROOT/'build/render-regression'/r['label'];inputs += [d/'fixture.json',d/'run-result.json']
  scenes=sorted((d/'results').glob('*.json'))
  for index in sorted({0,len(scenes)//2,len(scenes)-1}):p=scenes[index];inputs += [p,p.with_suffix('.png')]
 lod=ROOT/'build/render-regression/r34-2-lod-edge-stages';inputs += [lod/'r30-owned.json',lod/'run-result.json']
 inputs += [p for p in (lod/'r30-results').glob('*') if p.is_file() and p.suffix in ['.json','.png','.f32','.bin'] and 'nanoseconds' not in p.name]
 with zipfile.ZipFile(source,'x',zipfile.ZIP_DEFLATED) as z:
  for p in dict.fromkeys(inputs):z.write(p,'skybox-mod/'+p.relative_to(ROOT).as_posix())
  probe=ROOT/'build/libs/skybox-validation-probe-r34.2.jar';z.write(probe,'validation-only/'+probe.name)
 manifest=dict(version=result['version'],jarSha256=sha(jar),independentReviewRoute='deepseek-official/deepseek-flash',noUserInstallationOrSaveWrites=True,files=[dict(name=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in out.iterdir() if p.is_file()])
 (out/'MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
 snapshot=ROOT.parent/'artifacts/snapshots/skybox-r34-2-final-delivery-20261024.zip';assert not snapshot.exists()
 with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED) as z:
  for p in out.iterdir():z.write(p,p.relative_to(out))
 with zipfile.ZipFile(snapshot) as z:assert z.testzip() is None
 assert all(sha(out/f['name'])==f['sha256'] for f in manifest['files'])
 print(json.dumps(dict(delivery=str(out),archive=str(snapshot),jarSha256=sha(jar),files=len(manifest['files']),verified=True),indent=2))
if __name__=='__main__':main()
