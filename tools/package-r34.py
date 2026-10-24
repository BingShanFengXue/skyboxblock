"""Archive-only delivery: never touches the installed instance, worlds or shader configuration."""
from pathlib import Path
import zipfile,hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 acceptance=json.loads((ROOT/'build/reports/r34-final-acceptance.json').read_text(encoding='utf-8'));assert acceptance['passed']
 report=ROOT/'docs/EDGES-R34-DELIVERY-2026-10-24.md';review=ROOT/'docs/INDEPENDENT-R34-DEEPSEEK-READONLY-2026-10-24.md';assert report.is_file() and review.is_file()
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';assert sha(jar)==acceptance['jarSha256']
 frozen=ROOT.parent/'artifacts/snapshots/skybox-r34-candidate-before-review-20261024.zip'
 with zipfile.ZipFile(frozen) as z:
  for name in z.namelist():
   relative=Path(name).relative_to('skybox-mod')
   if str(relative).startswith('src') and relative.suffix in ['.java','.fsh','.vsh','.json','.png']:
    assert (ROOT/relative).read_bytes()==z.read(name),f'Production changed since candidate freeze: {relative}'
 destination=ROOT.parent/'output/skyboxblock-1.0.3-test.4-r34';destination.mkdir(exist_ok=False)
 files=[jar,ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34-sources.jar',report,review,ROOT/'docs/EDGES-R34-WORKLOG-2026-10-23.md',ROOT/'docs/screenshots/r34-edge-comparison-20261024.png',ROOT/'docs/screenshots/r34-itrp-jitter-20261024.gif',ROOT/'build/reports/r34-final-acceptance.json',ROOT/'build/reports/r34-package-audit.json',ROOT/'build/reports/r34-edge-comparison.json',ROOT/'LICENSE',ROOT/'THIRD_PARTY_NOTICES.md',ROOT/'AUTHORS.md']
 for p in files:shutil.copy2(p,destination/p.name)
 source=destination/'skybox-r34-source-and-evidence.zip'
 inputs=[p for folder in ['src','tools'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
 inputs += [ROOT/n for n in ['README.md','gradle.properties','build.gradle','settings.gradle','gradlew','gradlew.bat','LICENSE','THIRD_PARTY_NOTICES.md','AUTHORS.md']]
 inputs += [p for p in (ROOT/'gradle').rglob('*') if p.is_file()]
 inputs += [p for p in (ROOT/'LICENSES').rglob('*') if p.is_file()]
 inputs += [p for p in (ROOT/'docs').glob('*R34*.md')]
 inputs += [p for p in (ROOT/'build/reports').glob('r34-*.json') if p.is_file()]
 inputs += [ROOT/'build/r34-candidate-build-r4.log',ROOT/'docs/screenshots/r34-edge-comparison-20261024.png',ROOT/'docs/screenshots/r34-itrp-jitter-20261024.gif']
 # Include first/middle/last raw images and metadata from each valid run, not entire worlds or shader ZIPs.
 accept=json.loads((ROOT/'build/reports/r34-final-acceptance.json').read_text(encoding='utf-8'))
 for run in accept['runs']:
  d=ROOT/'build/render-regression'/run['label'];inputs += [d/'fixture.json',d/'run-result.json']
  scenes=sorted((d/'results').glob('*.json'))
  for index in sorted({0,len(scenes)//2,len(scenes)-1}):
   p=scenes[index];inputs += [p,p.with_suffix('.png')]
 with zipfile.ZipFile(source,'x',zipfile.ZIP_DEFLATED) as z:
  for p in dict.fromkeys(inputs):z.write(p,'skybox-mod/'+p.relative_to(ROOT).as_posix())
 manifest=dict(version=acceptance['version'],jarSha256=sha(jar),independentReview='deepseek-official/deepseek-flash',files=[dict(name=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in destination.iterdir() if p.is_file()],noInstanceOrWorldWrites=True)
 (destination/'MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
 snapshot=ROOT.parent/'artifacts/snapshots/skybox-r34-final-delivery-20261024.zip';assert not snapshot.exists()
 with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED) as z:
  for p in destination.iterdir():z.write(p,p.relative_to(destination))
 print(json.dumps(dict(delivery=str(destination),snapshot=str(snapshot),jarSha256=sha(jar),files=len(manifest['files'])),indent=2))
if __name__=='__main__':main()
