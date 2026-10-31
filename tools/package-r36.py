#!/usr/bin/env python3
"""Freeze the R36 release package from verified artifacts; never overwrite a prior output folder."""
from __future__ import annotations
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
VERSION='1.0.4-test.2-r36'

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--refresh',action='store_true');args=parser.parse_args()
    report=ROOT/'build/reports/r36-final-validation.json'
    verification=json.loads(report.read_text(encoding='utf-8'))
    if verification.get('status')!='PASS':raise SystemExit('R36 final validation must PASS before packaging')
    jar=ROOT/f'build/libs/skyboxblock-{VERSION}.jar'
    if sha(jar)!=verification['productionJarSha256']:raise SystemExit('Production jar changed after validation')
    out=ROOT.parent/'output'/f'skyboxblock-{VERSION}'
    if args.refresh:
        old=json.loads((out/'SHA256SUMS.json').read_text(encoding='utf-8'))
        assert old[jar.name]==sha(jar),'Only the same verified jar may be repackaged'
        for name,digest in old.items():
            path=out/name
            assert path.resolve().parent==out.resolve() and sha(path)==digest,('Output was modified',name)
    else:out.mkdir(parents=True,exist_ok=False)
    archive_dir=ROOT.parent/'artifacts/snapshots';archive_dir.mkdir(parents=True,exist_ok=True)
    archived=archive_dir/f"skybox-r36-source-and-evidence-20261031-{sha(jar)[:8]}.zip"
    if archived.exists():raise FileExistsError(archived)
    for path in [jar,ROOT/f'build/libs/skyboxblock-{VERSION}-sources.jar',
            ROOT/'docs/R36-DELIVERY-2026-10-31.md',ROOT/'docs/COMMANDS-R36-2026-10-31.md',
            ROOT/'docs/VANILLA-ENVIRONMENT-ATTRIBUTES.md',report,
            ROOT/'docs/screenshots/r36-cloud-seam-comparison-20261031.png']:
        if not path.is_file():raise FileNotFoundError(path)
        shutil.copy2(path,out/path.name)
    files=[]
    for folder in ['src','gradle','LICENSES']:
        files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    for name in ['build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat',
            'gradle.local.properties.example','README.md','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE']:
        path=ROOT/name
        if path.is_file():files.append(path)
    files.extend(p for p in (ROOT/'tools').glob('*') if p.suffix in {'.py','.ps1'} or p.name=='local.paths.example.json')
    files.extend(p for p in (ROOT/'docs').glob('*R36*') if p.is_file())
    files.extend([ROOT/'docs/VANILLA-ENVIRONMENT-ATTRIBUTES.md',
        ROOT/'docs/screenshots/r36-cloud-seam-comparison-20261031.png',ROOT/'build/r36-full-build-r17.log'])
    files.extend((ROOT/'build/reports').glob('r36-*'))
    files.extend((ROOT/'build/verify-r36-final').glob('javac-*.txt'))
    # Owned runtime evidence: cloud captures and the multiplayer matrix, JSON + screenshots only.
    for folder in ['r36-cloud-native-v3','r36-cloud-fsr-v3','r36-cloud-baseline-quick-v1']:
        fixture=ROOT/'build/render-regression'/folder
        if not fixture.is_dir():continue
        files.extend(fixture/name for name in ['run-result.json','r30-owned.json','client.log'] if (fixture/name).is_file())
        files.extend(p for p in (fixture/'r30-results').rglob('*') if p.is_file() and p.suffix in {'.json','.png'})
    mp=ROOT/'build/r36-multiplayer/r36-mp-v5'
    if mp.is_dir():
        files.extend(p for p in mp.rglob('*') if p.is_file() and p.suffix in {'.json','.log','.png','.properties','.txt'})
    archive=out/'skybox-r36-source-and-evidence.zip';pending=out/'skybox-r36-source-and-evidence.zip.pending'
    assert archive.resolve().parent==out.resolve() and pending.resolve().parent==out.resolve()
    with zipfile.ZipFile(pending,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(set(files)):
            if path.is_file():z.write(path,'skybox-mod/'+path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(pending) as z:assert z.testzip() is None
    pending.replace(archive)
    shutil.copy2(archive,archived)
    hashes={p.name:sha(p) for p in out.iterdir() if p.is_file() and p.name!='SHA256SUMS.json'}
    (out/'SHA256SUMS.json').write_text(json.dumps(hashes,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps(dict(output=str(out),files=hashes,archive=str(archived),refreshed=args.refresh),indent=2,ensure_ascii=False))
    return 0
if __name__=='__main__':raise SystemExit(main())