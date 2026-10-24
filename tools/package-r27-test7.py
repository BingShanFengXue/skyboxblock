#!/usr/bin/env python3
"""Package only the frozen tested R27 bytes, preserving all prior deliveries."""
from datetime import datetime
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NAME = "skyboxblock-test7-r27-20261020"
OUT = ROOT / "build/deliverables"
STAGE = OUT / NAME
PRIMARY = ROOT / "build/milestones/r27-final-r3/skyboxblock-1.0.2-test.7.jar"
SOURCES = ROOT / "build/milestones/r27-final-r3/skyboxblock-1.0.2-test.7-sources.jar"
EXPECTED = "0d82c0e5a1bed8be6e1f13bb2c008a38027e4be2425a7203c836628d63a1e013"
EXPECTED_SOURCE = "0099b15b8276cbf4396705c48296e3ccb77e2bb71cd5e0d15713dee1117522e0"
LEGACY = "54619eda6eaa988be57597e731d80445a9c60f83972d364179df9d5226db3fb1"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


assert sha(PRIMARY) == EXPECTED
assert sha(SOURCES) == EXPECTED_SOURCE
assert sha(ROOT / "build/libs/skyboxblock-1.0.2-test.6.jar") == "c1ceb286dbf106ca86cec9ca5b44f4f2acd7138bf3467a9574b178c957bec0fb"
final_validation = read(ROOT / "build/reports/r27-final-validation.json")
assert final_validation["allRequiredFinalChecksPassed"] and final_validation["repeatFixtureDeclarationIdentical"]
assert final_validation["historicalHighWallIssueClaimedFixed"] is False
assert read(ROOT / "build/reports/r27-runtime-bindings.json")["passed"]
assert read(ROOT / "build/reports/r27-frame-state-check.json")["passed"]
for name in ("r27-ao-baseline-vanilla-r2", "r27-ao-baseline-sodium-r4", "r27-ao-fixed-vanilla-r3", "r27-ao-fixed-sodium-r3"):
    assert read(ROOT / "build/reports" / (name + ".json"))["allChecksPassed"], name
negative = read(ROOT / "build/reports/r27-ao-legacy-negative-control-r2.json")
assert not negative["allChecksPassed"] and any("sky:air-equivalence" in r["name"] for r in negative["failed"])
assert read(ROOT / "build/reports/r27-reference-vanilla-r3.json")["passed"]
for env in ("vanilla", "iris", "fsr"):
    for kind in ("world", "alignment"):
        results = read(ROOT / f"build/reports/r27-smoke-{env}-r3-{kind}.json")
        assert len(results) == 1 and results[0]["passed"], (env, kind)

labels = ["r27-ao-baseline-vanilla-r2", "r27-ao-baseline-sodium-r3", "r27-ao-baseline-sodium-r4", "r27-ao-fixed-vanilla-r3", "r27-ao-fixed-sodium-r3",
          "r27-reference-vanilla-r3", *[f"r27-smoke-{env}-r3" for env in ("vanilla", "iris", "fsr")]]
bindings = []
for label in labels:
    instance = ROOT / "build/render-regression" / label
    result = read(instance / "run-result.json")
    assert result["exitCode"] == 0 and result["finished"] and not result["errors"] and not result["fatalErrors"] and not result["renderThreadViolations"], label
    expected = LEGACY if "baseline" in label else EXPECTED
    installed = instance / "mods" / PRIMARY.name
    assert sha(installed) == expected == read(instance / "fixture.json")["jarSha256"], label
    ids = []
    for path in (instance / "mods").glob("*.jar"):
        with zipfile.ZipFile(path) as z:
            if "fabric.mod.json" in z.namelist():
                ids.append(json.loads(z.read("fabric.mod.json"))["id"])
    assert "immersive_portals" not in ids and "distanthorizons" not in ids, ids
    bindings.append({"fixture": label, "mods": ids, "jarSha256": expected, "runtime": result})
STAGE.mkdir(parents=True, exist_ok=False)


def copy(source, destination=None):
    source = ROOT / source if not isinstance(source, Path) else source
    target = STAGE / (destination or source.relative_to(ROOT))
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    assert sha(source) == sha(target)


copy(PRIMARY, "mods/" + PRIMARY.name)
copy(SOURCES, "source/" + SOURCES.name)
for name in ("README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", "AUTHORS.md", "docs/VANILLA-CLOUD-HEIGHT.md",
             "docs/VANILLA-AO-R27-WORKLOG-2026-10-20.md", "docs/VANILLA-AO-R27-DELIVERY-2026-10-20.md",
             "docs/VANILLA-SKY-R26-DELIVERY-2026-10-20.md"):
    copy(name)
for path in sorted((ROOT / "build/reports").glob("r27-*")):
    if path.is_file():
        copy(path)
for path in sorted((ROOT / "build/logs").glob("r27-*.log")):
    copy(path)
for binding in bindings:
    label = binding["fixture"]
    instance = ROOT / "build/render-regression" / label
    for name in ("fixture.json", "run-result.json", "client.log"):
        copy(instance / name, "evidence/" + label + "/" + name)
    for meta in sorted((instance / "results").glob("*.json")):
        copy(meta, "evidence/" + label + "/results/" + meta.name)
        image = meta.with_suffix(".png")
        if image.exists():
            copy(image, "evidence/" + label + "/results/" + image.name)
    # Native sky comparisons and display/source targets retain their untouched original images.
    if "reference" in label:
        for suffix in ("*-native-sky.png", "*-scene-base.png"):
            for path in (instance / "results").glob(suffix):
                copy(path, "evidence/" + label + "/results/" + path.name)
    if "smoke" in label:
        for path in (instance / "results").glob("*-position-oracle.png"):
            copy(path, "evidence/" + label + "/results/" + path.name)
# Keep the initial noisy diagnostic as evidence of the test protocol correction, not a green result.
for name in ("r27-ao-baseline-vanilla-r1.json",):
    copy("build/reports/" + name)
project = STAGE / "source/project-source.zip"
with zipfile.ZipFile(project, "x", zipfile.ZIP_DEFLATED) as z:
    for dirname in ("src", "tools", "docs", "LICENSES"):
        for path in sorted((ROOT / dirname).rglob("*")):
            if path.is_file() and path.suffix in {".java", ".json", ".vsh", ".fsh", ".properties", ".png", ".mcmeta", ".py", ".ps1", ".md", ".txt"} and "__pycache__" not in path.parts:
                z.write(path, path.relative_to(ROOT).as_posix())
    for name in ("build.gradle", "gradle.properties", "settings.gradle", "LICENSE", "AUTHORS.md", "THIRD_PARTY_NOTICES.md", "README.md"):
        z.write(ROOT / name, name)
    for path in sorted((ROOT / "gradle").rglob("*")):
        if path.is_file():
            z.write(path, path.relative_to(ROOT).as_posix())
    for name in ("gradlew", "gradlew.bat"):
        z.write(ROOT / name, name)
readme = """Sky Box Block 1.0.2-test.7 / R27
Minecraft 1.21.1 / Fabric / Java 21. Author: Bing6512.

INSTALL ONLY: mods/skyboxblock-1.0.2-test.7.jar
Keep one skyboxblock version in mods. Fabric API required.
Source jars, source ZIPs and test tools are not mods.
No user worlds, shader archives or third-party mod jars are included.

FIX: neutral neighbor ambient-occlusion brightness for the sky block.
Collision and outline remain full-cube. Actual light blocking/emission remain zero.
Global smooth lighting remains enabled; other blocks are unchanged.

The frozen candidate was validated in owned isolated worlds.
Read docs/VANILLA-AO-R27-DELIVERY-2026-10-20.md for measurements and boundaries.
Portal compatibility work stays stopped; historical transparency/DH/high-wall issues remain separate.
This scoped test candidate is not a claim that the complete old release matrix passed.

Cloud default remains 192:
/skyboxblock cloudheight 240
/skyboxblock cloudheight reset
/skyboxblock reload
"""
(STAGE / "INSTALL.txt").write_text(readme, encoding="utf-8")
manifest = {"kind": "scoped-vanilla-neighbor-AO-fix", "version": "1.0.2-test.7", "batch": "R27", "author": "Bing6512",
            "createdAt": datetime.now().astimezone().isoformat(), "primaryJarSha256": EXPECTED,
            "sourceJarSha256": EXPECTED_SOURCE, "runtimeBindings": bindings,
            "candidateCaptures": sum(b["runtime"]["scenes"] for b in bindings if "baseline" not in b["fixture"]),
            "baselineCaptures": sum(b["runtime"]["scenes"] for b in bindings if "baseline" in b["fixture"]),
            "initialSodiumBaselineRedRetained": True, "finalValidation": final_validation,
            "releaseAccepted": False, "portalWorkStopped": True, "priorCompleteReleaseGateRevalidated": False,
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
      "bytes": archive.stat().st_size, "files": len(z.namelist()), "primaryJarSha256": EXPECTED,
      "sourceJarSha256": EXPECTED_SOURCE, "candidateCaptures": manifest["candidateCaptures"],
      "baselineCaptures": manifest["baselineCaptures"], "allStagedBytesAndZipEntriesVerified": True,
      "releaseAccepted": False, "portalTestsPerformed": False}
with (ROOT / "build/reports/r27-package-qa.json").open("x", encoding="utf-8") as output:
    json.dump(qa, output, indent=2)
print(json.dumps(qa, indent=2))
