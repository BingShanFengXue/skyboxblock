#!/usr/bin/env python3
"""Append-only final-acceptance snapshot; never launches Minecraft or a build.

Inputs are absolute or PROJECT-relative. Destination is a NEW .zip directly in
WORKSPACE/artifacts/snapshots (normally ../artifacts/snapshots/NAME.zip).
The acceptance must contain the literal JSON boolean passed=true, jar, jarSha256,
fixtures:[{directory}], and optional reports:[path], selectedEvidence:[path],
sourceJar/sourceJarSha256, harnessJar/harnessSha256.

All src (including main/portalFixture), tools except bytecode caches, top-level
Markdown docs, build metadata/wrapper, package(s), acceptance and fixture JSON/log
metadata are included. Results JSON is recursive; results images are NEVER bulk
included. List each chosen image in selectedEvidence. Referenced docs/screenshots
images are also treated as curated documentation evidence; other referenced build
images require selectedEvidence. Missing document links are noted, not fabricated.

No failed/partial archive is removed or overwritten. A failed post-check leaves a
new forensic .zip but no success sidecar. Existing snapshots remain untouched.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import unquote, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_NAME = "snapshot-manifest.json"
CHUNK_SIZE = 1024 * 1024
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
LINK = re.compile(r"!?\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))")
REFERENCE_LINK = re.compile(r"^\s*\[[^\]]+\]:\s*(?:<([^>]+)>|([^\s]+))", re.MULTILINE)


class ArchiveError(RuntimeError):
    """A failed acceptance, containment, stability or append-only invariant."""


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _hash_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(CHUNK_SIZE), b""):
            digest.update(block)
    return digest.hexdigest()


def _under(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
        return True
    except ValueError:
        return False


def _project_path(root: Path, value: str | Path, *, directory: bool = False) -> Path:
    if not isinstance(value, (str, Path)) or not str(value).strip():
        raise ArchiveError("Input paths must be nonempty strings or Path objects")
    path = Path(value)
    if not path.is_absolute():
        path = root / path
    path = path.resolve(strict=True)
    if not _under(path, root) or path == root:
        raise ArchiveError("Input resolves outside the project: " + str(value))
    valid = path.is_dir() if directory else path.is_file()
    if not valid:
        raise ArchiveError(("Expected directory: " if directory else "Expected regular file: ") + str(value))
    return path


def _sha(value: Any, field: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise ArchiveError(field + " must be a 64-hex SHA-256")
    return value.lower()


def _path_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ArchiveError(field + " must be an array of nonempty path strings")
    return value


def _signature(path: Path) -> tuple[int, int, int, int]:
    stat = path.stat()
    return stat.st_size, stat.st_mtime_ns, stat.st_dev, stat.st_ino


def _write_member(archive: zipfile.ZipFile, path: Path, name: str) -> dict[str, Any]:
    before = _signature(path)
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source, archive.open(name, "w", force_zip64=True) as destination:
        for block in iter(lambda: source.read(CHUNK_SIZE), b""):
            destination.write(block)
            digest.update(block)
            size += len(block)
    if _signature(path) != before or size != before[0]:
        raise ArchiveError("Input changed while being archived: " + name)
    return {"path": name, "size": size, "sha256": digest.hexdigest()}


def _verify_pins(pins: dict[Path, str], root: Path) -> None:
    for path, expected in pins.items():
        # Re-resolve before reading, so an input replaced by an escaping symlink is not followed.
        current = _project_path(root, path)
        if current != path or _hash_path(current) != expected:
            raise ArchiveError("Accepted/pinned artifact changed: " + path.relative_to(root).as_posix())


def archive_acceptance(acceptance_path: str | Path, destination_path: str | Path,
                       *, project_root: Path | None = None) -> dict[str, Any]:
    """Create and verify one snapshot. project_root exists for isolated, temporary unit fixtures only."""
    root = (ROOT if project_root is None else Path(project_root)).resolve(strict=True)
    acceptance_file = _project_path(root, acceptance_path)
    acceptance_bytes = acceptance_file.read_bytes()
    try:
        acceptance = json.loads(acceptance_bytes.decode("utf-8-sig"))
    except (UnicodeError, json.JSONDecodeError) as exception:
        raise ArchiveError("Acceptance is not valid UTF-8 JSON") from exception
    if not isinstance(acceptance, dict) or acceptance.get("passed") is not True:
        raise ArchiveError("Only a final acceptance with literal passed=true may be archived")

    destination = Path(destination_path)
    if not destination.is_absolute():
        destination = root / destination
    destination = destination.resolve()
    snapshots = (root.parent / "artifacts" / "snapshots").resolve()
    sidecar = destination.with_suffix(".manifest.json")
    if destination.parent != snapshots or destination.suffix.lower() != ".zip":
        raise ArchiveError("Destination must be a new .zip directly in WORKSPACE/artifacts/snapshots")
    if os.path.lexists(destination) or os.path.lexists(sidecar):
        raise ArchiveError("Archive or sidecar already exists; choose a new snapshot name")

    selected: set[Path] = set()
    reasons: dict[Path, set[str]] = {}
    notes: list[dict[str, str]] = []
    pins: dict[Path, str] = {acceptance_file: _digest(acceptance_bytes)}
    artifacts: dict[str, dict[str, Any]] = {}

    def add(value: str | Path, reason: str) -> Path:
        path = _project_path(root, value)
        if path.relative_to(root).as_posix() == MANIFEST_NAME:
            raise ArchiveError("Project input conflicts with reserved " + MANIFEST_NAME)
        selected.add(path)
        reasons.setdefault(path, set()).add(reason)
        return path

    def add_tree(value: str | Path, reason: str, *, json_only: bool = False) -> int:
        directory = _project_path(root, value, directory=True)
        count = 0
        for path in sorted(directory.rglob("*")):
            if "__pycache__" in path.relative_to(directory).parts or path.suffix.lower() in {".pyc", ".pyo"}:
                continue
            if path.is_symlink() and path.is_dir():
                target = path.resolve(strict=True)
                if not _under(target, root):
                    raise ArchiveError("Directory symlink escapes project: " + str(path))
                raise ArchiveError("Recursive source/evidence directory symlinks need explicit regular-file inputs: " + str(path))
            if path.is_file() and (not json_only or path.suffix.lower() == ".json"):
                add(path, reason)
                count += 1
        return count

    def pinned_artifact(field: str, hash_field: str, *, inferred: Path | None = None) -> Path:
        value = acceptance.get(field, inferred)
        if value is None:
            raise ArchiveError("Missing acceptance artifact: " + field)
        path = add(value, field)
        if path.suffix.lower() != ".jar":
            raise ArchiveError(field + " must identify a .jar file")
        expected = _sha(acceptance[hash_field], hash_field) if hash_field in acceptance else None
        if expected is None and field in {"jar", "harnessJar"}:
            raise ArchiveError("Missing accepted hash: " + hash_field)
        actual = _hash_path(path)
        if expected is not None and actual != expected:
            raise ArchiveError("Package no longer matches acceptance: " + field)
        pins[path] = actual
        artifacts[field] = {"path": path.relative_to(root).as_posix(), "size": path.stat().st_size, "sha256": actual}
        return path

    jar = pinned_artifact("jar", "jarSha256")
    sources = pinned_artifact("sourceJar", "sourceJarSha256", inferred=jar.with_name(jar.stem + "-sources.jar"))
    if sources == jar:
        raise ArchiveError("sourceJar must differ from the delivered jar")
    if ("harnessJar" in acceptance) != ("harnessSha256" in acceptance):
        raise ArchiveError("harnessJar and harnessSha256 must be supplied together")
    if "harnessJar" in acceptance:
        harness = pinned_artifact("harnessJar", "harnessSha256")
        if harness in {jar, sources}:
            raise ArchiveError("The independent harness must have its own artifact path")
        inferred_harness_sources = harness.with_name(harness.stem + "-sources.jar")
        if inferred_harness_sources.is_file():
            extra = add(inferred_harness_sources, "optional-harness-sources")
            pins[extra] = _hash_path(extra)

    for name in ("README.md", "LICENSE", "gradle.properties"):
        add(root / name, "project-metadata")
    # Snapshot the CURRENT author/notice text, without rewriting any historical artifact.
    for pattern in ("AUTHORS*", "NOTICE*", "*NOTICES*"):
        for path in root.glob(pattern):
            if path.is_file(): add(path, "current-authors-and-third-party-notices")
    if (root / "LICENSES").exists():
        add_tree("LICENSES", "complete-third-party-license-tree")
    for alternatives in (("build.gradle", "build.gradle.kts"), ("settings.gradle", "settings.gradle.kts")):
        present = [root / name for name in alternatives if (root / name).is_file()]
        if not present:
            raise ArchiveError("Missing project build metadata: " + " / ".join(alternatives))
        for path in present:
            add(path, "project-metadata")
    for name in ("gradlew", "gradlew.bat", ".gitignore", ".gitattributes"):
        if (root / name).is_file():
            add(root / name, "project-metadata")
    add_tree("src", "all-source-and-resources")
    add_tree("tools", "tools-without-bytecode-cache")
    add_tree("gradle/wrapper", "gradle-wrapper")
    docs = _project_path(root, "docs", directory=True)
    markdown = sorted(docs.glob("*.md"))
    for path in markdown:
        add(path, "top-level-documentation")
    add(acceptance_file, "final-acceptance")

    reports = [add(path, "explicit-report") for path in _path_list(acceptance.get("reports", []), "reports")]
    evidence = {add(path, "explicit-selected-evidence") for path in _path_list(acceptance.get("selectedEvidence", []), "selectedEvidence")}
    for report in reports:
        if report.suffix.lower() in IMAGE_SUFFIXES and report not in evidence:
            raise ArchiveError("Image reports must also appear in selectedEvidence: " + report.relative_to(root).as_posix())

    fixtures = acceptance.get("fixtures")
    if not isinstance(fixtures, list):
        raise ArchiveError("fixtures must be an array of {directory: project-relative-or-absolute-path}")
    fixture_directories: list[str] = []
    seen_fixtures: set[Path] = set()
    for item in fixtures:
        if not isinstance(item, dict) or not isinstance(item.get("directory"), str):
            raise ArchiveError("Each fixtures entry requires a directory string")
        directory = _project_path(root, item["directory"], directory=True)
        if directory in seen_fixtures:
            raise ArchiveError("Duplicate fixture directory: " + item["directory"])
        seen_fixtures.add(directory)
        fixture_directories.append(directory.relative_to(root).as_posix())
        for name in ("fixture.json", "run-result.json", "client.log"):
            add(directory / name, "fixture-manifest-run-log")
        if add_tree(directory / "results", "recursive-fixture-result-json", json_only=True) == 0:
            raise ArchiveError("Fixture results contain no JSON evidence: " + item["directory"])

    # Documentation is data: follow only local image references, never commands or external URLs.
    # Curated docs/screenshots images may be included automatically. Bulk fixture results never are.
    curated_docs_images = (root / "docs" / "screenshots").resolve()
    for document in markdown:
        text = document.read_text(encoding="utf-8-sig")
        links = list(LINK.finditer(text)) + list(REFERENCE_LINK.finditer(text))
        for match in links:
            target = (match.group(1) or match.group(2) or "").strip()
            if not target or target.startswith("#"):
                continue
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc:
                continue
            decoded = unquote(parsed.path)
            if Path(decoded).suffix.lower() not in IMAGE_SUFFIXES:
                continue
            candidate = (document.parent / decoded).resolve()
            label = document.relative_to(root).as_posix()
            if not _under(candidate, root) or not candidate.is_file():
                notes.append({"document": label, "image": target, "reason": "missing-or-out-of-project-document-image"})
                continue
            if candidate in evidence or (_under(candidate, curated_docs_images) and "results" not in candidate.relative_to(root).parts):
                add(candidate, "referenced-curated-document-image")
            else:
                notes.append({"document": label, "image": candidate.relative_to(root).as_posix(), "reason": "image-not-listed-in-selectedEvidence"})

    # Nothing is created in snapshots until all input selection, accepted hashes and scope checks succeed.
    _verify_pins(pins, root)
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "project": root.name,
        "acceptance": acceptance_file.relative_to(root).as_posix(),
        "acceptanceSha256": pins[acceptance_file],
        "artifacts": artifacts,
        "fixtures": fixture_directories,
        "reports": [path.relative_to(root).as_posix() for path in reports],
        "selectedEvidence": sorted(path.relative_to(root).as_posix() for path in evidence),
        "documentationImageNotes": notes,
        "note": "All source and result JSON metadata; only curated/explicit visual evidence. The manifest excludes its own self-referential entry; sidecar pins its hash.",
        "files": [],
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.parent.resolve() != snapshots:
        raise ArchiveError("Snapshot parent changed before exclusive creation")
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
        for path in sorted(selected, key=lambda item: item.relative_to(root).as_posix()):
            current = _project_path(root, path)
            if current != path:
                raise ArchiveError("Input path target changed during selection: " + str(path))
            entry = _write_member(archive, path, path.relative_to(root).as_posix())
            if path in pins and entry["sha256"] != pins[path]:
                raise ArchiveError("Pinned input changed while being archived: " + entry["path"])
            entry["reasons"] = sorted(reasons[path])
            manifest["files"].append(entry)
        manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        archive.writestr(MANIFEST_NAME, manifest_bytes)

    with zipfile.ZipFile(destination, "r") as archive:
        failed = archive.testzip()
        if failed is not None:
            raise ArchiveError("Zip CRC/integrity check failed: " + failed)
        names = archive.namelist()
        expected_names = {entry["path"] for entry in manifest["files"]} | {MANIFEST_NAME}
        if len(names) != len(set(names)) or set(names) != expected_names:
            raise ArchiveError("Zip entry set is duplicated or does not match the manifest")
        if archive.read(MANIFEST_NAME) != manifest_bytes:
            raise ArchiveError("Snapshot manifest bytes changed")
        for entry in manifest["files"]:
            digest = hashlib.sha256()
            size = 0
            with archive.open(entry["path"], "r") as source:
                for block in iter(lambda: source.read(CHUNK_SIZE), b""):
                    digest.update(block)
                    size += len(block)
            if size != entry["size"] or digest.hexdigest() != entry["sha256"]:
                raise ArchiveError("Snapshot entry hash/size mismatch: " + entry["path"])

    # Detect a rebuild, replacement or changed acceptance after the snapshot has actually been written.
    _verify_pins(pins, root)
    result = {
        "schemaVersion": 1,
        "archive": str(destination),
        "zipSize": destination.stat().st_size,
        "zipSha256": _hash_path(destination),
        "snapshotManifestSha256": _digest(manifest_bytes),
        "acceptanceSha256": pins[acceptance_file],
        "jarSha256": artifacts["jar"]["sha256"],
        "payloadFileCount": len(manifest["files"]),
        "entryCount": len(manifest["files"]) + 1,
        "documentationImageNoteCount": len(notes),
        "integrity": "PASS",
    }
    if "harnessJar" in artifacts:
        result["harnessSha256"] = artifacts["harnessJar"]["sha256"]
    _verify_pins(pins, root)
    with sidecar.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--acceptance", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = archive_acceptance(args.acceptance, args.destination)
    except (ArchiveError, OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exception:
        print("FAILED: " + str(exception), file=sys.stderr)
        print("Existing history and any newly created partial archive were retained; no success sidecar was published by this failed operation.", file=sys.stderr)
        return 1
    print(json.dumps({key: result[key] for key in ("archive", "zipSize", "zipSha256", "jarSha256", "payloadFileCount", "entryCount", "integrity")}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
