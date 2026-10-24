#!/usr/bin/env python3
"""Freeze an explicitly NOT-release-accepted engineering audit subject, append-only."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
from datetime import datetime
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "build/milestones/steps14-r23-ip-audit-checkpoint-20261019"
PINNED = {
    "main": ("build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5.jar", "9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129"),
    "sources": ("build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5-sources.jar", "5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab"),
    "harness": ("build/milestones/steps14-r23-settled/skybox-portal-fixture-1.0.0.jar", "612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7"),
}


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    if DEST.exists():
        raise FileExistsError("append-only audit checkpoint already exists: " + str(DEST))
    artifacts = {}
    for name, (relative, expected) in PINNED.items():
        path = ROOT / relative
        actual = sha(path)
        if actual != expected:
            raise ValueError("pinned artifact changed: " + relative)
        artifacts[name] = dict(path=relative, sha256=actual, bytes=path.stat().st_size)
    source_files = set()
    for base in ("src", "tools", "LICENSES", "gradle/wrapper"):
        source_files.update(p for p in (ROOT / base).rglob("*") if p.is_file() and "__pycache__" not in p.parts)
    source_files.update(p for p in (ROOT / "docs").rglob("*.md") if p.is_file())
    source_files.update(p for p in ROOT.iterdir() if p.is_file() and (p.suffix in (".md", ".gradle", ".kts", ".properties")
        or p.name in ("LICENSE", "gradlew", "gradlew.bat", ".gitignore")))
    evidence = set()
    evidence.update(p for p in (ROOT / "build/reports").iterdir() if p.is_file() and p.suffix in (".json", ".txt", ".log", ".md"))
    evidence.update(p for p in (ROOT / "build").glob("*r23*.log") if p.is_file())
    evidence.update(p for p in (ROOT / "build").glob("diag-ip-stage-sol-*.txt") if p.is_file())
    evidence.update(p for p in (ROOT / "build").glob("diag-ip-stage-sol-*.json") if p.is_file())
    fixtures = sorted((ROOT / "build/render-regression").glob("test5-r23-*"))
    fixtures += [ROOT / "build/render-regression/test5-r24-exact-prefix-high-wall"]
    fixtures += sorted((ROOT / "build/portal-fixture").glob("ip-r23-*"))
    fixture_index = []
    for fixture in fixtures:
        if not fixture.is_dir():
            raise FileNotFoundError(str(fixture))
        chosen = set(p for p in fixture.iterdir() if p.is_file() and p.suffix in (".json", ".log", ".txt"))
        for base in ("results", "config", "logs", "mods", "shaderpacks"):
            chosen.update(p for p in (fixture / base).rglob("*") if p.is_file())
        evidence.update(chosen)
        fixture_index.append(dict(path=fixture.relative_to(ROOT).as_posix(), pinnedFiles=len(chosen)))
    for base in ("build/milestones/steps14-r23-settled", "build/milestones/steps14-r24-high-diagnostic", "build/diagnostics/native-stall-r1"):
        evidence.update(p for p in (ROOT / base).rglob("*") if p.is_file())
    DEST.mkdir(parents=True, exist_ok=False)
    snapshot = DEST / "engineering-source-snapshot.zip"
    with zipfile.ZipFile(snapshot, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
        for path in sorted(source_files):
            output.write(path, path.relative_to(ROOT).as_posix())
    entries = []
    total_bytes = 0
    for path in sorted(source_files | evidence):
        size = path.stat().st_size
        total_bytes += size
        entries.append(dict(path=path.relative_to(ROOT).as_posix(), bytes=size, sha256=sha(path),
            role="current-source-tools-docs" if path in source_files else "captured-evidence"))
    subject = dict(schemaVersion=1, kind="engineering-checkpoint-audit-subject", projectAuthor="Bing6512",
        frozenAt=datetime.now().astimezone().isoformat(), producerSessionId=os.environ.get("DSH_SESSION_ID"),
        releaseAccepted=False, releaseMatrixReady=False,
        scope="End bounded IP testing and basic compatibility attempt, then fresh independent GPT6.1SOL audit; no further engineering expansion.",
        distinction="Not a release acceptance matrix or archive-test5 bypass. Current source includes R24 read-only diagnostics; R23 frozen sources and binary remain separate authoritative artifacts.",
        knownOpen=["R23 native core first five high-wall scenes missing sky and ordinary backing; R24 diagnostic replay did not reproduce, not fixed",
            "Controlled full settled transparency has three strict removed-world-restored failures; no waiver",
            "ITRP IP normal and basic no-primary-mod baselines fail ordinary stripe geometry; direct controls also fail geometry",
            "Final same-jar seam/state/bridge negative controls and full release matrix remain incomplete",
            "Dynamic DH, rotated/scaled/nested/cross-dimension portals not accepted"],
        artifacts=artifacts, sourceSnapshot=dict(path=snapshot.relative_to(ROOT).as_posix(), sha256=sha(snapshot), bytes=snapshot.stat().st_size),
        fixtures=fixture_index, fileCount=len(entries), totalPinnedBytes=total_bytes, files=entries)
    subject_path = DEST / "audit-subject.json"
    with subject_path.open("x", encoding="utf-8") as output:
        json.dump(subject, output, indent=2, ensure_ascii=False)
        output.write("\n")
    binding = dict(kind="engineering-checkpoint-subject-binding", releaseAccepted=False,
        subjectPath=subject_path.relative_to(ROOT).as_posix(), auditSubjectSha256=sha(subject_path),
        sourceSnapshot=subject["sourceSnapshot"], artifacts=artifacts, producerSessionId=subject["producerSessionId"],
        fileCount=len(entries), totalPinnedBytes=total_bytes)
    with (DEST / "subject-binding.json").open("x", encoding="utf-8") as output:
        json.dump(binding, output, indent=2, ensure_ascii=False)
        output.write("\n")
    print(json.dumps(binding, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
