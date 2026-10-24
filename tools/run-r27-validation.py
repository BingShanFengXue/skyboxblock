#!/usr/bin/env python3
"""Serial owned GPU runs for the scoped no-shader AO fix; no portal work."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
SHADER = "iterationRP Alpha 0.8.31.zip"
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--label", default="r3")
a = p.parse_args()
label = a.label
JAR = ROOT / "build/milestones/r27-final-r3/skyboxblock-1.0.2-test.7.jar"
OLD = ROOT / "build/milestones/r27-ao-baseline-r2/skyboxblock-1.0.2-test.7.jar"
records = []
summary = ROOT / f"build/reports/r27-validation-{label}.json"
if summary.exists():
    raise SystemExit("Choose a new unique label; previous evidence is preserved")


def run(name, tool, args):
    log = ROOT / "build/logs" / (name + ".log")
    command = [PY, str(ROOT / "tools" / tool), *map(str, args)]
    start = time.monotonic()
    print("START", name, flush=True)
    with log.open("xb") as output:
        code = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT).returncode
    records.append({"stage": name, "command": command, "exitCode": code,
                    "seconds": round(time.monotonic() - start, 2), "log": str(log.relative_to(ROOT))})
    print("END", name, "exit", code, "seconds", records[-1]["seconds"], flush=True)
    if code:
        print("\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-24:]), flush=True)
    return code


def fixture(name, profile, suite, extra=(), jar=JAR):
    return run(name, "render-regression.py", ["--profile", profile, "--label", name,
        "--jar", jar, "--suite", suite, "--timeout", "600", *extra])


def ambient(name, profile, expected, jar, baseline=None):
    if fixture(name, profile, "ambient", ["--no-probe-clouds"], jar) == 0:
        extra = ["--baseline", baseline] if baseline else []
        run(name + "-oracle", "check-ambient-occlusion-r27.py", ["build/render-regression/" + name,
            "--expect", expected, "--output", "build/reports/" + name + ".json", *extra])


ambient("r27-ao-baseline-sodium-" + label, "iris", "legacy", OLD)
ambient("r27-ao-fixed-vanilla-" + label, "vanilla", "fixed", JAR,
        "build/render-regression/r27-ao-baseline-vanilla-r2")
ambient("r27-ao-fixed-sodium-" + label, "iris", "fixed", JAR,
        "build/render-regression/r27-ao-baseline-sodium-" + label)
reference = "r27-reference-vanilla-" + label
if fixture(reference, "vanilla", "reference") == 0:
    run(reference + "-oracle", "check-vanilla-reference-r26.py", ["build/render-regression/" + reference,
        "--expect", "23", "--output", "build/reports/" + reference + ".json"])
bugs = ["bug-panel-empty", "bug-panel-sky-hand", "bug-panel-solid-hand", "bug-panel-torch-hand",
        "bug-panel-return-empty", "bug-panel-offhand", "bug-panel-dual-hand", "bug-pillar-near-empty",
        "bug-pillar-far-empty", "bug-pillar-far-sky-hand", "bug-pillar-far-solid-hand", "bug-pillar-farther-empty",
        "bug-pillar-side-left", "bug-pillar-side-right", "bug-pillar-turned", "bug-pillar-resized-hidden",
        "bug-pillar-resized-side", "bug-pillar-restored-side", "bug-clean"]
align = ["align-solid-no-sky", "align-solid-sky", "align-sky-selected", "align-other-solid", "align-oblique",
         "align-resized", "align-reload", "align-sky-removed", "align-clean"]
for environment, profile, extra in (
        ("vanilla", "vanilla", []),
        ("iris", "iris", ["--shader", SHADER, "--shader-settings", "instance", "--shader-option", "MOTION_BLUR=false", "--shader-option", "FSR2_SCALE=-1"]),
        ("fsr", "iris", ["--shader", SHADER, "--shader-settings", "instance", "--shader-option", "MOTION_BLUR=false", "--shader-option", "FSR2_SCALE=2"])):
    name = "r27-smoke-" + environment + "-" + label
    if fixture(name, profile, "all", ["--scenes", ",".join(bugs + align), *extra]) == 0:
        results = "build/render-regression/" + name + "/results"
        run(name + "-world", "check-world-regression.py", [results, "--suite", "bugs", "--output", "build/reports/" + name + "-world.json"])
        run(name + "-alignment", "check-alignment-regression.py", [results, "--output", "build/reports/" + name + "-alignment.json", "--annotate"])
result = {"kind": "R27-scoped-AO-validation-not-full-release-matrix", "stages": records,
          "allStageExitsZero": all(r["exitCode"] == 0 for r in records),
          "frozenCandidateSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
          "portalTestsPerformed": False, "historicalTransparencyIssueClaimedFixed": False,
          "fullPriorReleaseMatrixRevalidated": False}
with summary.open("x", encoding="utf-8") as output:
    json.dump(result, output, indent=2)
print("SUMMARY", summary, "allStageExitsZero", result["allStageExitsZero"], flush=True)
raise SystemExit(0 if result["allStageExitsZero"] else 1)
