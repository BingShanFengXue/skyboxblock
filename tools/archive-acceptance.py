#!/usr/bin/env python3
"""Append-only snapshot of sources, the delivered jar, and selected acceptance evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
WORKSPACE = ROOT.parent


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--acceptance", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    acceptance = json.loads(args.acceptance.read_text(encoding="utf-8"))
    if not acceptance["passed"]:
        raise RuntimeError("Only a passed final acceptance may be archived")
    destination = args.destination.resolve()
    expected_parent = (WORKSPACE / "artifacts/snapshots").resolve()
    if destination.parent != expected_parent or destination.exists():
        raise RuntimeError("Destination must be a new file directly in artifacts/snapshots")
    selected: set[Path] = set()

    def add(path: Path):
        path = path.resolve()
        if path.is_file():
            path.relative_to(ROOT)  # All archived input must belong to this project.
            selected.add(path)

    for name in ("README.md", "LICENSE", "build.gradle", "settings.gradle", "gradle.properties",
                 "gradlew", "gradlew.bat", ".gitignore"):
        add(ROOT / name)
    for prefix in ("src", "tools", "gradle/wrapper"):
        for path in (ROOT / prefix).rglob("*"):
            if "__pycache__" not in path.parts:
                add(path)
    for path in (ROOT / "docs").glob("*.md"):
        add(path)
    for name in ("user-items-offset-20261019.png", "user-outline-offset-20261019.png",
                 "state-test4-before-after-20261019.png"):
        add(ROOT / "docs/screenshots" / name)
    jar = Path(acceptance["jar"])
    if digest(jar.read_bytes()) != acceptance["jarSha256"]:
        raise RuntimeError("Delivered jar no longer matches final acceptance")
    add(jar)
    add(jar.with_name(jar.stem + "-sources.jar"))
    add(args.acceptance)
    add(ROOT / "build/reports/skybox-test4-package-audit.json")
    add(ROOT / "build/reports/render-math-regression.txt")
    add(ROOT / "build/reports/state-test4-position-oracle-controls.json")
    add(ROOT / "build/reports/state-test4-final-itrp-full-alignment-initial-edge-visibility.json")
    add(ROOT / "build/reports/state-test4-final-vanilla-alignment-initial-dark-items.json")
    add(ROOT / "build/state-test4-final-build.log")
    for name in ("iris-target-hooks-bytecode.txt", "iris-framebuffer-tracking-bytecode.txt",
                 "iris-buffer-source-bytecode.txt", "vanilla-cloud-height-bytecode.txt", "item-renderer-bytecode.txt"):
        add(ROOT / "build" / name)
    for name in ("Line_VS.glsl", "Entities_VS.glsl"):
        add(ROOT / "build/itrp-inspect/shaders/Lib/Programs/Gbuffers" / name)
    labels = [item["label"] for item in acceptance["fixtures"]]
    labels.append(Path(acceptance["negativeControl"]["directory"]).name)
    labels.append("state-test4-fixed-align-r3")
    for label in labels:
        fixture = ROOT / "build/render-regression" / label
        for name in ("fixture.json", "run-result.json", "client.log"):
            add(fixture / name)
        for suffix in (".json", "-alignment.json"):
            add(ROOT / "build/reports" / (label + suffix))
        for path in (fixture / "results").glob("*.json"):
            add(path)
        for scene in ("align-solid-no-sky", "align-solid-sky", "align-sky-selected", "align-oblique",
                      "align-resized", "align-reload", "align-sky-removed", "bug-panel-solid-hand",
                      "bug-panel-dual-hand", "bug-pillar-side-left", "sky-day", "sky-night", "sky-rain"):
            for suffix in (".png", "-position-oracle.png", "-line-mask-full.png"):
                add(fixture / "results" / (scene + suffix))
    manifest = {"acceptance": str(args.acceptance.resolve()), "jarSha256": acceptance["jarSha256"],
                "note": "Sources and all capture metadata; selected visual evidence only. Full fixtures remain in workspace.",
                "files": []}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(selected):
            relative = path.relative_to(ROOT).as_posix()
            data = path.read_bytes()
            archive.writestr(relative, data)
            manifest["files"].append({"path": relative, "bytes": len(data), "sha256": digest(data)})
        archive.writestr("snapshot-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Snapshot zip integrity check failed")
        for entry in manifest["files"]:
            if digest(archive.read(entry["path"])) != entry["sha256"]:
                raise RuntimeError("Snapshot member hash mismatch: " + entry["path"])
    result = {"archive": str(destination), "bytes": destination.stat().st_size,
              "sha256": digest(destination.read_bytes()), "files": len(manifest["files"]),
              "jarSha256": acceptance["jarSha256"], "integrity": "PASS"}
    with destination.with_suffix(".manifest.json").open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
