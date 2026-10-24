#!/usr/bin/env python3
"""Repeat the unchanged Sodium baseline once; keep the original failed batch visible."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
name = "r27-ao-baseline-sodium-r4"
jar = ROOT / "build/milestones/r27-ao-baseline-r2/skyboxblock-1.0.2-test.7.jar"
main = json.loads((ROOT / "build/reports/r27-validation-r3.json").read_text(encoding="utf-8"))
# Final candidate stages may not be excused by this baseline repeat.
allowed = {"r27-ao-baseline-sodium-r3-oracle"}
assert all(s["exitCode"] == 0 or s["stage"] in allowed for s in main["stages"]), "Investigate any candidate-stage failure separately"
records = []


def run(label, tool, args):
    command = [PY, str(ROOT / "tools" / tool), *map(str, args)]
    path = ROOT / "build/logs" / (label + ".log")
    start = time.monotonic()
    print("START", label, flush=True)
    with path.open("xb") as output:
        code = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT).returncode
    records.append({"stage": label, "exitCode": code, "command": command,
                    "seconds": round(time.monotonic() - start, 2), "log": str(path.relative_to(ROOT))})
    print("END", label, code, flush=True)
    return code


assert run(name, "render-regression.py", ["--profile", "iris", "--label", name, "--jar", jar,
    "--suite", "ambient", "--timeout", "600", "--no-probe-clouds"]) == 0
oracle = run(name + "-oracle", "check-ambient-occlusion-r27.py", ["build/render-regression/" + name,
    "--expect", "legacy", "--output", "build/reports/" + name + ".json"])
old_fixture = json.loads((ROOT / "build/render-regression/r27-ao-baseline-sodium-r3/fixture.json").read_text(encoding="utf-8"))
new_fixture = json.loads((ROOT / "build/render-regression" / name / "fixture.json").read_text(encoding="utf-8"))
assert old_fixture == new_fixture, "The repeated baseline must retain the complete original fixture declaration"
report = {"kind": "R27-final-scoped-validation-with-retained-baseline-red", "initialBatch": "r27-validation-r3.json",
          "initialBatchAllStageExitsZero": main["allStageExitsZero"],
          "retainedInitialRedStages": [s for s in main["stages"] if s["exitCode"] != 0],
          "sameJarRepeat": True, "repeatFixtureDeclarationIdentical": old_fixture == new_fixture,
          "repeatStages": records, "allRequiredFinalChecksPassed": oracle == 0,
          "candidateSha256": main["frozenCandidateSha256"], "portalTestsPerformed": False,
          "fullPriorReleaseMatrixRevalidated": False,
          "historicalHighWallIssueClaimedFixed": False,
          "interpretation": "Original Sodium air-only reference missed fixture terrain. Its red result remains. An unchanged full 40-scene same-JAR repeat is reported separately; no threshold/scene exclusion or production fix for that intermittent event."}
path = ROOT / "build/reports/r27-final-validation.json"
with path.open("x", encoding="utf-8") as output:
    json.dump(report, output, indent=2)
print(json.dumps(report, indent=2), flush=True)
raise SystemExit(0 if report["allRequiredFinalChecksPassed"] else 1)
