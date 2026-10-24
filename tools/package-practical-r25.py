#!/usr/bin/env python3
"""Append-only practical test bundle, explicitly distinct from a formal release archive.

Does not invoke or weaken archive-test5/verify-test5-acceptance. Packages the
unchanged R23 primary/source JAR, compatibility snippet, bounded evidence and
scope. No third-party shader/mod archives or user instances are included.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
from datetime import datetime
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
NAME='skybox-test5-practical-r25-20261020'
DEST=ROOT/'build/deliverables'/NAME
MAIN='9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129'
SOURCE='5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab'


def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    report=json.loads((ROOT/'build/reports/practical-r25-runtime-summary-20261020-r2.json').read_text(encoding='utf-8'))
    if report['evidenceBindingChecksPassed'] is not True or report['releaseAccepted'] is not False:
        raise ValueError('practical evidence binding invalid or mislabeled as release')
    files={
        'README.txt':'docs/PRACTICAL-R25-BUNDLE-README.txt',
        'mods/skyboxblock-1.0.2-test.5.jar':'build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5.jar',
        'source/skyboxblock-1.0.2-test.5-sources.jar':'build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5-sources.jar',
        'compatibility/ITRP-IP-options-snippet.properties':'docs/ITRP-IP-options-snippet.properties',
        'reports/runtime-summary.json':'build/reports/practical-r25-runtime-summary-20261020-r2.json',
        'reports/native-portal-diagnostics.json':'build/reports/ip-r25-native-no-motion-practical-r2.json',
        'reports/fsr-portal-diagnostics.json':'build/reports/ip-r25-fsr-no-motion-practical-r1.json',
        'reports/helper-selftest.json':'build/reports/ip-r25-practical-helper-selftest-r2.json',
        'reports/delta-review.md':'build/reports/ip-r25-practical-delta-review-20261020.md',
        'reports/delta-review.json':'build/reports/ip-r25-practical-delta-review-20261020.json',
        'evidence/portal-motion-blur-comparison.png':'build/reports/ip-r25-motion-blur-compat-comparison-20261020.png',
        'evidence/full-fsr-position-oracle.png':'build/render-regression/test5-r25-practical-smoke-full-fsr/results/align-sky-selected-position-oracle.png',
        'LICENSE':'LICENSE','AUTHORS.md':'AUTHORS.md','THIRD_PARTY_NOTICES.md':'THIRD_PARTY_NOTICES.md',
        'LICENSES/Literal-Sky-Block-MIT.txt':'LICENSES/Literal-Sky-Block-MIT.txt',
    }
    for target,relative in files.items():
        if not (ROOT/relative).is_file():raise FileNotFoundError(relative)
    if sha(ROOT/files['mods/skyboxblock-1.0.2-test.5.jar'])!=MAIN or sha(ROOT/files['source/skyboxblock-1.0.2-test.5-sources.jar'])!=SOURCE:
        raise ValueError('pinned candidate/source changed')
    if DEST.exists() or DEST.with_suffix('.zip').exists():raise FileExistsError(DEST)
    DEST.mkdir(parents=True,exist_ok=False)
    entries=[]
    for relative,source in files.items():
        target=DEST/relative;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(ROOT/source,target)
        if sha(target)!=sha(ROOT/source):raise ValueError('copy mismatch')
        entries.append(dict(path=relative,bytes=target.stat().st_size,sha256=sha(target),source=source))
    manifest=dict(kind='practical-test-candidate-not-official-release',createdAt=datetime.now().astimezone().isoformat(),
        projectAuthor='Bing6512',minecraft='1.21.1',loader='Fabric',modVersion='1.0.2-test.5',configurationBatch='R25',
        releaseAccepted=False,automatedPortalAcceptance=None,primaryRendererJarChanged=False,
        jarSha256=MAIN,sourceJarSha256=SOURCE,requiredOption='ITRP MOTION_BLUR=false',
        verifiedPracticalScope='Same-overworld pure translation IP, real walking/return and 3-environment 28-scene main-view smoke; manually reviewed original images',
        notClaimed=['Original complete release matrix passing','automatic pale-material depth/geometry proof',
            'rotated/scaled/mirrored/nested/cross-dimension portal support','dynamic-DH acceptance','unreproduced high-wall defect fixed'],
        totalRealCaptures=138,worldChecksPassed=831,alignmentChecksPassed=342,
        entries=entries)
    with (DEST/'manifest.json').open('x',encoding='utf-8') as f:json.dump(manifest,f,ensure_ascii=False,indent=2);f.write('\n')
    archive=DEST.with_suffix('.zip')
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(DEST.rglob('*')):
            if p.is_file():z.write(p,p.relative_to(DEST).as_posix())
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None:raise ValueError('archive CRC failure')
        if len(z.namelist())!=len(entries)+1 or len(set(z.namelist()))!=len(z.namelist()):raise ValueError('archive inventory mismatch')
        for entry in entries:
            if hashlib.sha256(z.read(entry['path'])).hexdigest()!=entry['sha256']:raise ValueError('archive entry SHA mismatch')
        archived_manifest=json.loads(z.read('manifest.json'))
        if archived_manifest!=manifest:raise ValueError('manifest changed in archive')
    output=dict(kind='practical-candidate-package-QA-not-release-acceptance',passed=True,releaseAccepted=False,
        archive=dict(path=archive.relative_to(ROOT).as_posix(),bytes=archive.stat().st_size,sha256=sha(archive)),
        directory=DEST.relative_to(ROOT).as_posix(),entryCount=len(entries)+1,allEntryCrcAndSha256Verified=True,
        jarSha256=MAIN,sourceJarSha256=SOURCE,sourceFileHashesUnchanged=True)
    qa=ROOT/'build/reports/practical-r25-bundle-qa-20261020.json'
    with qa.open('x',encoding='utf-8') as f:json.dump(output,f,indent=2);f.write('\n')
    print(json.dumps(output,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
