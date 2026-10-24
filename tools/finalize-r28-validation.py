#!/usr/bin/env python3
"""Summarize selected final-candidate checks without turning retained diagnostics into release acceptance."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parent.parent
read = lambda p: json.loads(p.read_text(encoding="utf-8"))
EXPECTED = "07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c"
ALLOWED_RECOVERY = {"trans-removed-" + material + ":removed-world-restored" for material in ("none", "glass", "water")}
reports, critical, recovery = {}, [], []


def get(name):
    obj = read(ROOT / "build/reports" / (name + ".json"))
    if isinstance(obj, list):
        assert len(obj) == 1
        obj = obj[0]
    reports[name] = obj
    return obj


for name in ("r28-hand-fixed-native-r3-oracle-v3", "r28-hand-fixed-fsr-r3"):
    value = get(name)
    if not value["allChecksPassed"]:
        critical.append({"report": name, "failed": value["failed"]})
for name in ("r28-blue-native-r3", "r28-blue-fsr-r3", "r28-reference-vanilla-r3", "r28-hdr-unit-properties",
             "r28-bloom-exposure-settings", "r28-bloom-exposure-default-proof"):
    value = get(name)
    if not value["passed"]:
        critical.append({"report": name, "failed": value.get("failed", "report did not pass")})
labels = {"vanilla": "r28-full-vanilla-r4", "native": "r28-full-native-r3", "fsr": "r28-full-fsr-r3"}
for environment, label in labels.items():
    for kind in ("world", "alignment", "dimensions", "transparency"):
        name = label + "-" + kind
        value = get(name)
        if not value["passed"]:
            failed = [c for c in value["checks"] if not c["passed"]]
            for item in failed:
                target = recovery if kind == "transparency" and item["name"] in ALLOWED_RECOVERY else critical
                target.append({"report": name, "check": item})
primary_labels = ["r28-hand-fixed-native-r3", "r28-hand-fixed-fsr-r3", "r28-blue-native-r3", "r28-blue-fsr-r3",
                  "r28-reference-vanilla-r3", *labels.values()]
bindings, warnings, bad_frames = [], [], []
for label in primary_labels:
    directory = ROOT / "build/render-regression" / label
    fixture, runtime = read(directory / "fixture.json"), read(directory / "run-result.json")
    installed = next((directory / "mods").glob("skyboxblock-*.jar"))
    actual = hashlib.sha256(installed.read_bytes()).hexdigest()
    if actual != EXPECTED or actual != fixture["jarSha256"] or runtime["exitCode"] != 0 or not runtime["finished"] or runtime["fatalErrors"] or runtime["renderThreadViolations"]:
        critical.append({"fixture": label, "problem": "runtime completion/identity/fatal/render-thread check"})
    for line in runtime["errors"]:
        known_network = "[Yggdrasil Key Fetcher/ERROR]" in line and "Failed to request yggdrasil public key" in line
        record = {"fixture": label, "line": line, "classification": "Yggdrasil startup network diagnostic" if known_network else "unclassified ERROR"}
        warnings.append(record)
        if not known_network:
            critical.append(record)
    frames = list((directory / "results").glob("*.json"))
    for path in frames:
        frame = read(path)
        if frame["initialGlError"] or frame["finalGlError"] or frame["irisMainBoundBefore"] != frame["irisMainBoundAfter"] or frame["irisOverridesBefore"] != frame["irisOverridesAfter"] or frame["outsideRasterChangedPixels"] or frame["outsideRasterDepthChangedPixels"]:
            bad_frames.append({"fixture": label, "frame": path.stem})
    bindings.append({"fixture": label, "profile": fixture["profile"], "scenes": runtime["scenes"],
                     "jarSha256": actual, "runtimeErrorLines": runtime["errors"], "frameMetadataCount": len(frames),
                     "fullSceneSuiteNotFullUserModpack": label.startswith("r28-full-")})
if bad_frames:
    critical.append({"frameStateFailures": bad_frames})
initial = read(ROOT / "build/reports/r28-validation-r3.json")
retry = read(ROOT / "build/reports/r28-vanilla-retry-r4.json")
if not retry["sameFullFixtureDeclaration"]:
    critical.append({"problem": "vanilla repeat declaration differs"})
metrics = {}
for kind in ("world", "alignment", "dimensions", "transparency"):
    selected = [reports[label + "-" + kind] for label in labels.values()]
    def counts(value):
        checks = value.get("checks", [])
        return (value.get("passedChecks", sum(bool(c["passed"]) for c in checks)), value.get("totalChecks", len(checks)))
    values = [counts(value) for value in selected]
    metrics[kind] = {"passed": sum(v[0] for v in values), "total": sum(v[1] for v in values),
                     "allPassed": all(value["passed"] for value in selected)}
report = {"kind": "R28-final-scoped-feature-and-broad-regression-summary", "candidateSha256": EXPECTED,
          "handFixVerified": all(reports[n]["allChecksPassed"] for n in ("r28-hand-fixed-native-r3-oracle-v3", "r28-hand-fixed-fsr-r3")),
          "handChecksPassed": sum(reports[n]["passed"] for n in ("r28-hand-fixed-native-r3-oracle-v3", "r28-hand-fixed-fsr-r3")),
          "handChecksTotal": sum(reports[n]["checks"] for n in ("r28-hand-fixed-native-r3-oracle-v3", "r28-hand-fixed-fsr-r3")),
          "candidateTestBuildAccepted": not critical, "criticalFailures": critical, "retainedRecoveryDiagnostics": recovery,
          "primaryCandidateCaptures": sum(b["scenes"] for b in bindings), "runtimeBindings": bindings,
          "runtimeWarningsRetained": warnings, "frameStateFailures": bad_frames, "metrics": metrics,
          "initialBatchAllStageExitsZero": initial["allStageExitsZero"],
          "initialBatchNonzeroStagesRetained": [s for s in initial["stages"] if s["exitCode"]],
          "sameJarVanillaRepeat": retry, "releaseAccepted": False,
          "portalTestsPerformed": False, "dynamicDHValidated": False, "settledTransparencyProtocolCompleted": False,
          "independentFinalAuditCompleted": False,
          "interpretation": "A verified hand/exposure test build. Full scene suite means all 113 scenes in vanilla or the lean Iris/Sodium profile, not the full user modpack or a formal release-gate pass."}
path = ROOT / "build/reports/r28-final-validation.json"
with path.open("x", encoding="utf-8") as output:
    json.dump(report, output, indent=2)
print(json.dumps(report, indent=2))
raise SystemExit(0 if report["candidateTestBuildAccepted"] else 1)
