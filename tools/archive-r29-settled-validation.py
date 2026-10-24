#!/usr/bin/env python3
"""Archive completed R29 evidence without turning retained failures into passes.
No game runs, deletions, source rewrites, or official publication.
"""
from __future__ import annotations
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import tempfile
import zipfile
from zoneinfo import ZoneInfo

ROOT=Path(__file__).resolve().parent.parent
WORKSPACE=ROOT.parent
PIN='07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c'
SOURCE_PIN='edf9c2100a57a5634a5ef6123eff6636b26091c0b80b952865d5988e08184263'
STORED={'.png','.jar','.zip','.gz','.ogg','.jpg','.webp'}


def sha(path):
    result=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            result.update(block)
    return result.hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def archive_files(output,files,kind):
    """Append one new archive, retaining exact bytes and a per-file hash index."""
    output=Path(output).resolve()
    if output.exists():raise FileExistsError('Retain existing archive: '+str(output))
    selected=sorted({Path(path).resolve(strict=True) for path in files})
    if not selected:raise ValueError('No archive inputs')
    if any(not p.is_file() or not p.is_relative_to(WORKSPACE.resolve()) for p in selected):
        raise ValueError('Archive input escapes the declared workspace')
    records=[]
    with zipfile.ZipFile(output,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=3,allowZip64=True) as archive:
        for path in selected:
            before=path.stat();name=path.relative_to(WORKSPACE).as_posix();identity=sha(path)
            archive.write(path,name,compress_type=zipfile.ZIP_STORED if path.suffix.lower() in STORED else zipfile.ZIP_DEFLATED)
            after=path.stat()
            if before.st_size!=after.st_size or before.st_mtime_ns!=after.st_mtime_ns or sha(path)!=identity:
                raise RuntimeError('Input changed during archive creation; keep the failed archive for diagnosis: '+str(path))
            records.append(dict(path=name,bytes=before.st_size,sha256=identity))
        index=dict(kind=kind,createdAt=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                   sourceBytesPreserved=True,releaseAccepted=False,entries=records,
                   note='Historical absolute evidence paths remain unchanged. Reverify from the original workspace layout.')
        archive.writestr('R29-ARCHIVE-INDEX.json',json.dumps(index,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
    with zipfile.ZipFile(output) as archive:
        bad=archive.testzip()
        if bad is not None:raise RuntimeError('Archive CRC failure: '+bad)
        for record in records:
            with archive.open(record['path']) as stream:
                result=hashlib.sha256()
                for block in iter(lambda:stream.read(1024*1024),b''):result.update(block)
            if result.hexdigest()!=record['sha256']:raise RuntimeError('Archive byte identity failure: '+record['path'])
    return dict(path=str(output),bytes=output.stat().st_size,sha256=sha(output),files=len(records),allEntryHashesVerified=True)


def verify_source_snapshot():
    receipt=load(ROOT/'build/reports/r28-delivery-receipt.json')
    snapshot=Path(receipt['finalSourceSnapshot']).resolve(strict=True)
    if sha(snapshot)!=SOURCE_PIN:raise ValueError('Frozen R28 source snapshot identity differs')
    matches=[]
    with zipfile.ZipFile(snapshot) as archive:
        names=archive.namelist()
        for path in sorted((ROOT/'src').rglob('*')):
            if not path.is_file():continue
            name=path.relative_to(ROOT).as_posix()
            entries=[entry for entry in names if entry==name or entry.endswith('/'+name)]
            if len(entries)!=1 or archive.read(entries[0])!=path.read_bytes():
                raise ValueError('Production/source file differs from frozen R28 snapshot: '+name)
            matches.append(name)
    if len(matches)!=83:raise ValueError('Expected the complete 83-file frozen src tree')
    return dict(snapshot=str(snapshot),sha256=SOURCE_PIN,matchingSrcFiles=len(matches))


def selftest():
    with tempfile.TemporaryDirectory(prefix='r29-archive-selftest-',dir=ROOT/'build') as temp:
        temp=Path(temp);text=temp/'text.txt';binary=temp/'payload.png'
        text.write_text('UTF-8 归档字节验证\n',encoding='utf-8');binary.write_bytes(bytes(range(256))*4)
        output=temp/'synthetic.zip'
        result=archive_files(output,[text,binary],'SYNTHETIC SELFTEST ONLY')
        with zipfile.ZipFile(output) as archive:
            assert archive.read(text.relative_to(WORKSPACE).as_posix())==text.read_bytes()
            assert archive.read(binary.relative_to(WORKSPACE).as_posix())==binary.read_bytes()
            assert load_index(archive)['releaseAccepted'] is False
        try:archive_files(output,[text],'overwrite-negative')
        except FileExistsError:protected=True
        else:protected=False
        assert protected and result['files']==2 and result['allEntryHashesVerified']
    print('4/4 archive source-byte, hash, release-boundary and overwrite controls PASS')
    return 0


def load_index(archive):
    return json.loads(archive.read('R29-ARCHIVE-INDEX.json'))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix',type=Path,default=ROOT/'build/reports/r29-settled-matrix-vanilla-native-fsr-r1.json')
    parser.add_argument('--audit',type=Path,default=ROOT/'build/reports/r29-whole-objective-audit-r1.json')
    parser.add_argument('--delivery',type=Path,default=ROOT/'docs/TRANSPARENCY-R29-DELIVERY-2026-10-22.md')
    parser.add_argument('--revision',default='r1')
    parser.add_argument('--selftest',action='store_true')
    args=parser.parse_args()
    if args.selftest:return selftest()
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):parser.error('Use r1, r2, ...')
    matrix=load(args.matrix);audit=load(args.audit)
    if matrix.get('collectionAndAnalysisComplete') is not True or matrix.get('error') is not None:
        raise ValueError('All three environments must have finished actual collection and analysis')
    if audit.get('wholeEvidenceComplete') is not True:
        raise ValueError('Evidence-integrity audit must be complete; strict image failures may remain openly recorded')
    if not args.delivery.is_file():raise FileNotFoundError('Final scoped delivery report must exist before archiving')
    if sha(ROOT/'build/libs/skyboxblock-1.0.2-test.8.jar')!=PIN:raise ValueError('Frozen production JAR changed')
    if any(sha(ROOT/'tools'/name)!=identity for name,identity in matrix['toolSha256'].items()):
        raise ValueError('Live matrix tools changed; retain evidence and investigate before archiving')
    source_identity=verify_source_snapshot()
    destination=(WORKSPACE/'artifacts/snapshots').resolve(strict=True)
    if not destination.is_relative_to(WORKSPACE.resolve()):raise ValueError('Archive destination escapes workspace')
    prefix='skyboxblock-r29-settled-validation-20261022-'+args.revision
    source_zip=destination/(prefix+'-source.zip');evidence_zip=destination/(prefix+'-evidence.zip')
    output_receipt=ROOT/'build/reports'/('r29-archive-receipt-'+args.revision+'.json')
    if any(p.exists() for p in (source_zip,evidence_zip,output_receipt)):
        raise FileExistsError('Keep existing archives/receipt and select a new revision')
    common={args.matrix.resolve(strict=True),args.audit.resolve(strict=True),args.delivery.resolve(strict=True)}
    for path in ROOT.glob('*'):
        if path.is_file() and path.name in ('README.md','AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE',
                'build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat'):
            common.add(path)
    for directory in ('src','gradle','LICENSES'):
        common.update(p for p in (ROOT/directory).rglob('*') if p.is_file())
    common.update(p for p in (ROOT/'tools').iterdir() if p.is_file())
    common.update(p for p in (ROOT/'docs').glob('*.md') if 'R29' in p.name or p.name=='RELEASE-READINESS-2026-10-22.md')
    common.update(p for p in (ROOT/'docs/screenshots').glob('r29*.png'))
    common.update(p for p in (ROOT/'build/reports').glob('r29*') if p.is_file())
    common.update(p for p in (ROOT/'build/logs').glob('r29*') if p.is_file())
    common.add(destination/'skybox-r29-protocol-before-20261022T1529.zip')
    for name in ('r28-final-validation.json','r28-delivery-receipt.json','r28-runtime-state-bindings.json',
                 'r28-full-vanilla-r4-transparency.json','r28-full-native-r3-transparency.json','r28-full-fsr-r3-transparency.json'):
        path=ROOT/'build/reports'/name
        if path.exists():common.add(path)
    source=archive_files(source_zip,common,'R29 source/tools/scoped reports; production JAR not rebuilt')
    evidence=set(common)
    for env in matrix['environments'].values():
        for relative in env['fixtures'].values():
            fixture=(ROOT/relative).resolve(strict=True)
            if not fixture.is_relative_to((ROOT/'build/render-regression').resolve()):raise ValueError('Fixture escapes owned tree')
            for directory in ('results','mods','shaderpacks','config','crash-reports'):
                evidence.update(p for p in (fixture/directory).rglob('*') if p.is_file())
            for name in ('fixture.json','run-result.json','client.log','options.txt','saves/fixture/level.dat'):
                path=fixture/name
                if path.is_file():evidence.add(path)
    for name in ('trans-open-water-none-marker','trans-sky-water-none-marker'):
        for suffix in ('.json','.png'):
            evidence.add(ROOT/'build/render-regression/r28-full-vanilla-r4/results'/(name+suffix))
    evidence.add(ROOT/'build/libs/skyboxblock-1.0.2-test.8.jar')
    full=archive_files(evidence_zip,evidence,'R29 complete owned fixture captures and raw failures; NOT official release')
    receipt=dict(kind='R29-completed-validation-archive-receipt',source=source,evidence=full,
                 frozenProductionJarSha256=PIN,sourceIdentity=source_identity,
                 wholeEvidenceComplete=audit['wholeEvidenceComplete'],
                 scopedTransparencyVerified=audit.get('scopedTransparencyVerified',False),
                 strictTransparentChecksAllPassed=audit.get('strictTransparentChecksAllPassed',False),
                 releaseAccepted=False,productionJarRebuilt=False,userWorldsIncluded=False,
                 existingArchivesModified=False,originalFailuresPreserved=True)
    with output_receipt.open('x',encoding='utf-8') as stream:
        json.dump(receipt,stream,ensure_ascii=False,indent=2,allow_nan=False);stream.write('\n')
    print(json.dumps(receipt,ensure_ascii=False),flush=True)
    return 0


if __name__=='__main__':
    raise SystemExit(main())
