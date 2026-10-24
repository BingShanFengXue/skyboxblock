#!/usr/bin/env python3
"""Repeat the JVM-crashed vanilla run with unchanged candidate and arguments; retain the initial failure."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
JAR = ROOT / "build/milestones/r28-hand-fixed-r2/skyboxblock-1.0.2-test.8.jar"
NAME = "r28-full-vanilla-r4"
records = []


def run(label, tool, args):
    command = [PY, str(ROOT / "tools" / tool), *map(str, args)]
    path = ROOT / "build/logs" / (label + ".log")
    start = time.monotonic()
    print("START", label, flush=True)
    with path.open("xb") as output:
        code = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT).returncode
    records.append({"stage": label, "command": command, "exitCode": code,
                    "seconds": round(time.monotonic() - start, 2), "log": str(path.relative_to(ROOT))})
    print("END", label, code, flush=True)
    if code:
        print("\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-20:]), flush=True)
    return code


code = run(NAME, "render-regression.py", ["--profile", "vanilla", "--label", NAME,
    "--jar", JAR, "--suite", "all", "--timeout", "900"])
if code == 0:
    results = "build/render-regression/" + NAME + "/results"
    for suffix, tool, extra in (("world", "check-world-regression.py", ["--suite", "all"]),
                                ("alignment", "check-alignment-regression.py", ["--annotate"]),
                                ("dimensions", "check-dimension-regression.py", []),
                                ("transparency", "check-transparency-regression.py", [])):
        run(NAME + "-" + suffix, tool, [results, "--output", "build/reports/" + NAME + "-" + suffix + ".json", *extra])
old = json.loads((ROOT / "build/render-regression/r28-full-vanilla-r3/fixture.json").read_text(encoding="utf-8"))
new = json.loads((ROOT / "build/render-regression" / NAME / "fixture.json").read_text(encoding="utf-8"))
report = {"kind": "R28-same-JAR-vanilla-retry-after-native-JVM-startup-fault", "stages": records,
          "sameFullFixtureDeclaration": old == new,
          "candidateSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
          "allRetryStagesZero": all(s["exitCode"] == 0 for s in records),
          "originalJvmFailureRetained": True, "gcOrHeapSettingsChanged": False,
          "modStartupBugClaimedFixed": False, "releaseAccepted": False}
path = ROOT / "build/reports/r28-vanilla-retry-r4.json"
with path.open("x", encoding="utf-8") as output:
    json.dump(report, output, indent=2)
print(json.dumps(report, indent=2), flush=True)
raise SystemExit(0 if report["allRetryStagesZero"] and report["sameFullFixtureDeclaration"] else 1)
