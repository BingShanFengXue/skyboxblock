#!/usr/bin/env python3
"""Append-only R28 test build delivery; no shader ZIPs, user worlds, or implied formal release approval."""
from pathlib import Path
from datetime import datetime
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NAME = "skyboxblock-test8-r28-20261022"
OUT = ROOT / "build/deliverables"
STAGE = OUT / NAME
PRIMARY = ROOT / "build/milestones/r28-hand-fixed-r2/skyboxblock-1.0.2-test.8.jar"
SOURCES = ROOT / "build/milestones/r28-hand-fixed-r2/skyboxblock-1.0.2-test.8-sources.jar"
EXPECTED = "07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c"
SOURCE_SHA = "3e8316ef0241afea16475eab4b2c6c56c7849298eecfaaab3f5b22bf81184efb"
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
read = lambda path: json.loads(path.read_text(encoding="utf-8"))
final = read(ROOT / "build/reports/r28-final-validation.json")
# This package delivers the verified HAND FIX for testing; it does not override the failed broad gate.
assert final["handFixVerified"] and final["releaseAccepted"] is False
assert all(final["metrics"][name]["allPassed"] for name in ("world", "alignment", "dimensions"))
# Keep the four explicit inconclusive water-reference results red; no confirmed leak may be packaged silently.
for failure in final["criticalFailures"]:
    check = failure.get("check", {})
    assert failure.get("report") == "r28-full-vanilla-r4-transparency"
    assert check.get("evidenceError") == "native-reference-weak-in-all-tested-frequency-families"
    assert check.get("evidenceValid") is False and check.get("leakDetected") is False
    assert check.get("legacyLeakDetected") is False and check.get("multiscaleLeakDetected") is False
assert sha(PRIMARY) == EXPECTED == final["candidateSha256"]
assert sha(SOURCES) == SOURCE_SHA
assert sha(ROOT / "build/libs/skyboxblock-1.0.2-test.7.jar") == "0d82c0e5a1bed8be6e1f13bb2c008a38027e4be2425a7203c836628d63a1e013"
with zipfile.ZipFile(PRIMARY) as z:
    meta = json.loads(z.read("fabric.mod.json"))
    assert meta["id"] == "skyboxblock" and meta["version"] == "1.0.2-test.8" and meta["authors"] == ["Bing6512"]
    assert z.read("assets/skyboxblock/shaders/core/sky_material_background.fsh") == (ROOT / "src/main/resources/assets/skyboxblock/shaders/core/sky_material_background.fsh").read_bytes()
    assert any("LICENSE" in n.upper() for n in z.namelist())
primary_labels = [b["fixture"] for b in final["runtimeBindings"]]
other_labels = ["r28-hand-baseline-native-r1", "r28-hand-glass-control-native-r1", "r28-hand-baseline-fsr-r3",
                "r28-hand-fixed-native-r2", "r28-full-vanilla-r3"]
labels = primary_labels + other_labels
for label in labels:
    directory = ROOT / "build/render-regression" / label
    jar = next((directory / "mods").glob("skyboxblock-*.jar"))
    assert sha(jar) == read(directory / "fixture.json")["jarSha256"], label
    if label in primary_labels:
        assert sha(jar) == EXPECTED, label
    ids = []
    for path in (directory / "mods").glob("*.jar"):
        with zipfile.ZipFile(path) as z:
            if "fabric.mod.json" in z.namelist():
                ids.append(json.loads(z.read("fabric.mod.json"))["id"])
    assert "immersive_portals" not in ids and "distanthorizons" not in ids, ids
STAGE.mkdir(parents=True, exist_ok=False)


def copy(source, destination=None):
    source = ROOT / source if not isinstance(source, Path) else source
    target = STAGE / (destination or source.relative_to(ROOT))
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    assert sha(source) == sha(target)


copy(PRIMARY, "mods/" + PRIMARY.name)
copy(SOURCES, "source/" + SOURCES.name)
for name in ("README.md", "LICENSE", "AUTHORS.md", "THIRD_PARTY_NOTICES.md", "docs/VANILLA-CLOUD-HEIGHT.md",
             "docs/HAND-EXPOSURE-R28-WORKLOG-2026-10-22.md", "docs/HAND-EXPOSURE-R28-DELIVERY-2026-10-22.md",
             "docs/RELEASE-READINESS-2026-10-22.md", "docs/VANILLA-AO-R27-DELIVERY-2026-10-20.md"):
    copy(name)
for path in sorted((ROOT / "build/reports").glob("r28-*")):
    if path.is_file():
        copy(path)
for path in sorted((ROOT / "build/logs").glob("r28-*.log")):
    copy(path)
for label in labels:
    directory = ROOT / "build/render-regression" / label
    for name in ("fixture.json", "run-result.json", "client.log"):
        copy(directory / name, "evidence/" + label + "/" + name)
    for crash in directory.glob("hs_err_pid*.log"):
        copy(crash, "evidence/" + label + "/" + crash.name)
    # Preserve complete raw result surfaces, masks and per-pass captures so every retained red item is inspectable.
    # Only the owned results tree is copied; saves/worlds, third-party mod jars and shader archives stay excluded.
    results = directory / "results"
    for path in sorted(results.rglob("*")):
        if path.is_file():
            copy(path, "evidence/" + label + "/results/" + path.relative_to(results).as_posix())
# Full source and all test tools; generated build caches and third-party shader archives are excluded.
project = STAGE / "source/project-source.zip"
with zipfile.ZipFile(project, "x", zipfile.ZIP_DEFLATED) as z:
    for folder in ("src", "tools", "docs", "LICENSES"):
        for path in sorted((ROOT / folder).rglob("*")):
            if path.is_file() and path.suffix in {".java", ".json", ".vsh", ".fsh", ".properties", ".png", ".mcmeta", ".py", ".ps1", ".md", ".txt"} and "__pycache__" not in path.parts:
                z.write(path, path.relative_to(ROOT).as_posix())
    for name in ("build.gradle", "gradle.properties", "settings.gradle", "README.md", "LICENSE", "AUTHORS.md", "THIRD_PARTY_NOTICES.md", "gradlew", "gradlew.bat"):
        z.write(ROOT / name, name)
    for path in sorted((ROOT / "gradle").rglob("*")):
        if path.is_file():
            z.write(path, path.relative_to(ROOT).as_posix())
install = """Sky Box Block 1.0.2-test.8 / R28
Minecraft 1.21.1 / Fabric / Java 21. Author: Bing6512.

INSTALL ONLY mods/skyboxblock-1.0.2-test.8.jar; keep one skyboxblock version.
Fabric API required. Source jars/tools are not mods.
No shader ZIP, user world or third-party mod JAR is included.

FIX: enclosed sky-wall HDR metering feedback that gradually bleached first-person hands.
Production delta relative to the matched diagnostic baseline: one background fragment shader.
Bloom and automatic exposure remain enabled. Skin, collision and world light levels are unchanged.

Read docs/HAND-EXPOSURE-R28-DELIVERY-2026-10-22.md for exact measurements and retained diagnostics.
Read docs/RELEASE-READINESS-2026-10-22.md for overall progress and concrete release gaps.
The hand/exposure fix is verified for this scoped TEST build.
The broad rendering gate is NOT passed: transparency retains five recovery failures and four inconclusive water references.
No failed criterion is waived or relabeled passed by this package. It is not a stable/formal release approval.
Portal compatibility work stays stopped. Dynamic DH and independent final audit are not completed.
Full scene suite means all 113 scenes in vanilla or lean Iris/Sodium profiles, not the full user modpack.

Cloud default remains 192:
/skyboxblock cloudheight 240
/skyboxblock cloudheight reset
/skyboxblock reload
"""
(STAGE / "INSTALL.txt").write_text(install, encoding="utf-8")
manifest = {"kind": "R28-scoped-hand-exposure-test-build", "version": "1.0.2-test.8", "author": "Bing6512",
            "createdAt": datetime.now().astimezone().isoformat(), "primaryJarSha256": EXPECTED, "sourceJarSha256": SOURCE_SHA,
            "primaryCandidateCaptures": final["primaryCandidateCaptures"], "runtimeBindings": final["runtimeBindings"],
            "retainedRecoveryDiagnostics": final["retainedRecoveryDiagnostics"], "runtimeWarningsRetained": final["runtimeWarningsRetained"],
            "handFixVerified": final["handFixVerified"], "broaderRenderingAcceptancePassed": final["candidateTestBuildAccepted"],
            "unresolvedTransparencyChecks": final["metrics"]["transparency"]["total"] - final["metrics"]["transparency"]["passed"],
            "inconclusiveWaterReferenceChecks": len(final["criticalFailures"]),
            "releaseAccepted": False, "portalWorkStopped": True, "independentFinalAuditCompleted": False,
            "files": [{"path": f.relative_to(STAGE).as_posix(), "bytes": f.stat().st_size, "sha256": sha(f)} for f in sorted(STAGE.rglob("*")) if f.is_file()]}
(STAGE / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
archive = OUT / (NAME + ".zip")
with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as z:
    for path in sorted(STAGE.rglob("*")):
        if path.is_file():
            z.write(path, path.relative_to(STAGE).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert len(z.namelist()) == len([f for f in STAGE.rglob("*") if f.is_file()])
    for name in z.namelist():
        assert hashlib.sha256(z.read(name)).hexdigest() == sha(STAGE / name), name
qa = {"passed": True, "archive": str(archive.relative_to(ROOT)), "archiveSha256": sha(archive),
      "bytes": archive.stat().st_size, "files": len(z.namelist()), "primaryJarSha256": EXPECTED, "sourceJarSha256": SOURCE_SHA,
      "primaryCandidateCaptures": final["primaryCandidateCaptures"], "allStagedBytesAndZipEntriesVerified": True,
      "retainedRecoveryRedItems": len(final["retainedRecoveryDiagnostics"]),
      "inconclusiveWaterReferenceChecks": len(final["criticalFailures"]),
      "packageIntegrityOnly": True, "broaderRenderingAcceptancePassed": final["candidateTestBuildAccepted"],
      "handFixVerified": final["handFixVerified"], "releaseAccepted": False}
with (ROOT / "build/reports/r28-package-qa.json").open("x", encoding="utf-8") as output:
    json.dump(qa, output, indent=2)
print(json.dumps(qa, indent=2))
