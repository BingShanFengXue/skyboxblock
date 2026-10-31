#!/usr/bin/env python3
"""Freeze verified R35 files; refresh only this session's verified output, never overwrite archives."""
from __future__ import annotations
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
VERSION='1.0.4-test.1-r35'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--refresh',action='store_true');args=parser.parse_args()
    report=ROOT/'build/reports/r35-final-validation.json';verification=json.loads(report.read_text(encoding='utf-8'))
    if verification.get('status')!='PASS':raise SystemExit('Final validation must explicitly PASS before packaging')
    jar=ROOT/f'build/libs/skyboxblock-{VERSION}.jar'
    if sha(jar)!=verification['productionJarSha256']:raise SystemExit('Production jar changed after validation')
    out=ROOT.parent/'output'/f'skyboxblock-{VERSION}'
    if args.refresh:
        old=json.loads((out/'SHA256SUMS.json').read_text(encoding='utf-8'))
        assert old[jar.name]==sha(jar),'Only the verified same-version output may be refreshed'
        for name,digest in old.items():
            path=out/name;assert path.resolve().parent==out.resolve() and sha(path)==digest,('Output was modified',name)
    else:out.mkdir(parents=True,exist_ok=False)
    archive_dir=ROOT.parent/'artifacts/snapshots';archive_dir.mkdir(parents=True,exist_ok=True)
    archived=archive_dir/('skybox-r35-source-and-evidence-20261031-final.zip' if args.refresh else 'skybox-r35-source-and-evidence-20261031.zip')
    if archived.exists():raise FileExistsError(archived)
    for path in [jar,ROOT/f'build/libs/skyboxblock-{VERSION}-sources.jar',
            ROOT/'docs/ENVIRONMENT-ATTRIBUTES-R35-DELIVERY-2026-10-31.md',ROOT/'docs/VANILLA-ENVIRONMENT-ATTRIBUTES.md',
            report,ROOT/'docs/screenshots/r35-environment-comparison-20261031.png']:
        if not path.is_file():raise FileNotFoundError(path)
        shutil.copy2(path,out/path.name)
    files=[]
    for folder in ['src','gradle','LICENSES']:
        files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    for name in ['build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','gradle.local.properties.example',
            'README.md','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE']:
        path=ROOT/name
        if path.is_file():files.append(path)
    files.extend(p for p in (ROOT/'tools').glob('*') if p.suffix in {'.py','.ps1'} or p.name=='local.paths.example.json')
    files.extend(p for p in (ROOT/'docs').glob('*R35*') if p.is_file())
    files.extend([ROOT/'docs/VANILLA-ENVIRONMENT-ATTRIBUTES.md',ROOT/'docs/screenshots/r35-environment-comparison-20261031.png',ROOT/'build/r35-build-r4.log'])
    files.extend((ROOT/'build/reports').glob('r35-*'));files.extend((ROOT/'build/verify-r35-final-quoted').glob('javac-*.txt'))
    for label in ['r35-env-vanilla-r1','r35-env-iris-native-r1','r35-env-dh-r1','r35-env-iris-dh-fsr-r1']:
        fixture=ROOT/'build/render-regression'/label
        files.extend(fixture/name for name in ['run-result.json','r30-owned.json','client.log'])
        files.extend((fixture/'r30-results').glob('*.json'));files.extend((fixture/'r30-results').glob('*.png'))
    archive=out/'skybox-r35-source-and-evidence.zip';pending=out/'skybox-r35-source-and-evidence.zip.pending'
    assert archive.resolve().parent==out.resolve() and pending.resolve().parent==out.resolve()
    with zipfile.ZipFile(pending,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
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