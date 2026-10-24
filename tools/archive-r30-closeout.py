#!/usr/bin/env python3
"""Append-only source and selected-evidence delivery. Never packages third-party mod/shader binaries."""
from __future__ import annotations
import hashlib,json,shutil,zipfile,datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent;WORKSPACE=ROOT.parent
DEST=WORKSPACE/'artifacts/snapshots'
PIN='50dd054fded07bd8721916cdfe8d97263eb100148dd33f5dee343bbc3a06619d'
def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()
def read(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def archive(path,files,kind):
    files=sorted(set(p.resolve(strict=True) for p in files if p.is_file()),key=str)
    ledger={str(p.relative_to(WORKSPACE)).replace('\\','/'):dict(sha256=sha(p),bytes=p.stat().st_size) for p in files}
    description=dict(kind=kind,sourceRoot=str(WORKSPACE),candidateSha256=PIN,resourceTests='SKIPPED_BY_USER',
        content='Project source/tools or selected evidence, not all historical raw data; no third-party mod/shader JAR/ZIP assets',files=ledger)
    with zipfile.ZipFile(path,'x',zipfile.ZIP_DEFLATED,compresslevel=1,allowZip64=True) as z:
        for p in files:z.write(p,str(p.relative_to(WORKSPACE)).replace('\\','/'))
        z.writestr('R30-ARCHIVE-MANIFEST.json',json.dumps(description,indent=2,ensure_ascii=False))
    checked=0
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None,'CRC failed'
        for name,item in ledger.items():
            h=hashlib.sha256()
            with z.open(name) as f:
                for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
            assert h.hexdigest()==item['sha256'],name;checked+=1
    return dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path),indexedFiles=len(files),decompressedHashesVerified=checked,crcPassed=True)
def main():
    closure=read(ROOT/'build/reports/r30-user-scope-completion-r1.json');assert closure['passed']
    jar=ROOT/'build/libs/skyboxblock-1.0.2-test.9-r30.8.jar';assert sha(jar)==PIN
    assert sha(ROOT/'build/libs/skyboxblock-1.0.2-test.8.jar')=='07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c'
    delivered=DEST/'skyboxblock-1.0.2-test.9-r30.8-20261023.jar'
    assert not delivered.exists(),'Append-only delivery exists';shutil.copyfile(jar,delivered);assert sha(delivered)==PIN
    src=[]
    for directory in ['src','tools','gradle','LICENSES']:
        for p in (ROOT/directory).rglob('*'):
            if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.class'}:src.append(p)
    src += list((ROOT/'docs').glob('*.md'))
    for name in ['README.md','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE','build.gradle','gradle.properties','gradlew','gradlew.bat','.gitignore']:
        p=ROOT/name
        if p.is_file():src.append(p)
    src += [jar,ROOT/'build/libs/skyboxblock-1.0.2-test.9-r30.8-sources.jar',ROOT/'build/libs/skybox-validation-probe-r30.6.jar']
    report_files=[]
    for pattern in ['r30*.json','r30*.md','r30-render-math-candidate8-r1.txt','r30-candidate8-build-r1.log']:
        report_files+=list((ROOT/'build/reports').glob(pattern))
    for folder in ['r30-candidate8-final-r1-validation','r30-candidate1-dynamic-r1-matrix']:
        p=ROOT/'build/reports'/folder
        if p.exists():report_files += [f for f in p.rglob('*') if f.is_file()]
    src += report_files
    evidence=list(report_files)
    for fixture in sorted((ROOT/'build/render-regression').glob('r30-candidate8-*')):
        if not fixture.is_dir():continue
        for name in ['r30-owned.json','owned-process.json','run-result.json','r30-command.json','options.txt','client.log','SCOPE-SKIPPED-BY-USER.md']:
            p=fixture/name
            if p.is_file():evidence.append(p)
        if (fixture/'config').exists():evidence += [p for p in (fixture/'config').glob('*') if p.is_file() and p.suffix in {'.properties','.toml'}]
        if (fixture/'r30-results').exists():evidence += [p for p in (fixture/'r30-results').rglob('*') if p.is_file()]
    selections=[]
    for version in [1,5]:
        fixture=ROOT/'build/render-regression'/f'r30-candidate{version}-glass-fsr2-temporal-r1';folder=fixture/'r30-results'
        for name in ['run-summary.json','glass-jitter-contact-sheet.png']:
            if (folder/name).is_file():evidence.append(folder/name)
        evidence += list((folder/'temporal').glob('*.json'))
        summary=read(folder/'run-summary.json')
        for phase in summary['phases']:
            stem=f"{phase['index']:02d}-{phase['name']}";meta=sorted((folder/'temporal').glob(stem+'-frame-*.json'))
            entries=[(p,read(p)) for p in meta]
            chosen={0,len(entries)-1,min(range(len(entries)),key=lambda k:entries[k][1]['rasterJitterX']),max(range(len(entries)),key=lambda k:entries[k][1]['rasterJitterX'])}
            for index in sorted(chosen):
                p,m=entries[index];image=folder/'temporal'/m['image'];evidence += [p,image];selections.append(dict(case=stem,variant=version,index=index,image=str(image.relative_to(ROOT)),criterion='first,last,min-jitter-X,max-jitter-X; deterministic, not fitted to outcome'))
    evidence += [ROOT/'docs/screenshots/r30-user-glass-jitter-20261022.png']
    source_result=archive(DEST/'skyboxblock-r30-test9-20261023-final-source.zip',src,'SOURCE_TOOLS_DECLARATIONS')
    evidence_result=archive(DEST/'skyboxblock-r30-test9-20261023-final-evidence.zip',evidence,'CURRENT_JAR_AND_SELECTED_TEMPORAL_EVIDENCE')
    result=dict(at=datetime.datetime.now().astimezone().isoformat(),deliveredJar=dict(path=str(delivered),bytes=delivered.stat().st_size,sha256=sha(delivered)),source=source_result,evidence=evidence_result,temporalSelection=selections,resourceTests='SKIPPED_BY_USER',auditRoute='deepseek-official/deepseek-flash',auditMode='READ_ONLY',auditBlockers=0,globalLegacyReleaseGatePassed=False)
    with (ROOT/'build/reports/r30-closeout-archives-r1.json').open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    print(json.dumps({k:v for k,v in result.items() if k!='temporalSelection'},indent=2),flush=True);return 0
if __name__=='__main__':raise SystemExit(main())
