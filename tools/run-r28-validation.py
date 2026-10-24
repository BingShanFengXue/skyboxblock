#!/usr/bin/env python3
"""Serial frozen-JAR hand/exposure and broad non-portal regression; preserves every stage result."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
JAR = ROOT / "build/milestones/r28-hand-fixed-r2/skyboxblock-1.0.2-test.8.jar"
OLD = ROOT / "build/milestones/r28-hand-baseline-r1/skyboxblock-1.0.2-test.8.jar"
PACK = "iterationRP Alpha 0.8.31.zip"
records = []


def run(label, tool, args):
    command = [PY, str(ROOT / "tools" / tool), *map(str, args)]
    path = ROOT / "build/logs" / (label + ".log")
    start = time.monotonic()
    print("START", label, flush=True)
    with path.open("xb") as output:
        code = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT).returncode
    row = {"stage": label, "exitCode": code, "command": command,
           "seconds": round(time.monotonic() - start, 2), "log": str(path.relative_to(ROOT))}
    records.append(row)
    print("END", label, "exit", code, "seconds", row["seconds"], flush=True)
    if code:
        print("\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-22:]), flush=True)
    return code


def fixture(label, profile, suite, fsr=None, jar=JAR, extra=()):
    args = ["--profile", profile, "--label", label, "--jar", jar, "--suite", suite, "--timeout", "900"]
    if fsr is not None:
        args += ["--shader", PACK, "--shader-settings", "instance", "--shader-option", "MOTION_BLUR=false",
                 "--shader-option", "FSR2_SCALE=" + str(fsr)]
    return run(label, "render-regression.py", args + list(extra))


# Repeat native candidate cleanly: the earlier R2 run includes one Yggdrasil networking error, retained separately.
native = "r28-hand-fixed-native-r3"
if fixture(native, "iris", "handexposure", -1, extra=["--no-probe-clouds"]) == 0:
    run(native + "-oracle", "check-hand-exposure-r28.py", ["build/render-regression/" + native,
        "--expect", "fixed", "--baseline", "build/render-regression/r28-hand-baseline-native-r1",
        "--output", "build/reports/" + native + ".json"])
legacy_fsr = "r28-hand-baseline-fsr-r3"
if fixture(legacy_fsr, "iris", "handexposure", 2, jar=OLD, extra=["--no-probe-clouds"]) == 0:
    run(legacy_fsr + "-oracle", "check-hand-exposure-r28.py", ["build/render-regression/" + legacy_fsr,
        "--expect", "legacy", "--output", "build/reports/" + legacy_fsr + ".json"])
fixed_fsr = "r28-hand-fixed-fsr-r3"
if fixture(fixed_fsr, "iris", "handexposure", 2, extra=["--no-probe-clouds"]) == 0:
    run(fixed_fsr + "-oracle", "check-hand-exposure-r28.py", ["build/render-regression/" + fixed_fsr,
        "--expect", "fixed", "--baseline", "build/render-regression/" + legacy_fsr,
        "--output", "build/reports/" + fixed_fsr + ".json"])
for env, fsr in (("native", -1), ("fsr", 2)):
    fixture("r28-blue-" + env + "-r3", "iris", "blue", fsr)
reference = "r28-reference-vanilla-r3"
if fixture(reference, "vanilla", "reference") == 0:
    run(reference + "-oracle", "check-vanilla-reference-r26.py", ["build/render-regression/" + reference,
        "--expect", "23", "--output", "build/reports/" + reference + ".json"])
for env, profile, fsr in (("vanilla", "vanilla", None), ("native", "iris", -1), ("fsr", "iris", 2)):
    label = "r28-full-" + env + "-r3"
    if fixture(label, profile, "all", fsr) == 0:
        results = "build/render-regression/" + label + "/results"
        run(label + "-world", "check-world-regression.py", [results, "--suite", "all", "--output", "build/reports/" + label + "-world.json"])
        run(label + "-alignment", "check-alignment-regression.py", [results, "--output", "build/reports/" + label + "-alignment.json", "--annotate"])
        run(label + "-dimensions", "check-dimension-regression.py", [results, "--output", "build/reports/" + label + "-dimensions.json"])
        run(label + "-transparency", "check-transparency-regression.py", [results, "--output", "build/reports/" + label + "-transparency.json"])
report = {"kind": "R28-non-portal-broad-regression-not-formal-release-gate", "stages": records,
          "allStageExitsZero": all(r["exitCode"] == 0 for r in records),
          "candidateSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(), "portalTestsPerformed": False,
          "DistantHorizonsInstalled": False, "settledTransparencyProtocol": False,
          "originalFailingDiagnosticsRetained": True, "releaseAccepted": False}
path = ROOT / "build/reports/r28-validation-r3.json"
with path.open("x", encoding="utf-8") as output:
    json.dump(report, output, indent=2)
print("SUMMARY", path, "allStageExitsZero", report["allStageExitsZero"], flush=True)
raise SystemExit(0 if report["allStageExitsZero"] else 1)
