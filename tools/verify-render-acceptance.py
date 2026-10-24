#!/usr/bin/env python3
"""Bind final sky + position evidence and a failing state-leak control to the exact delivered jar."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", nargs="+", type=Path)
    parser.add_argument("--jar", type=Path, default=ROOT / "build/libs/skyboxblock-1.0.2-test.4.jar")
    parser.add_argument("--negative", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    jar_hash = digest(args.jar)
    checks, fixtures = [], []

    def add(name, passed, **data):
        checks.append({"name": name, "passed": bool(passed), **data})

    for directory in args.fixtures:
        fixture = load(directory / "fixture.json")
        run = load(directory / "run-result.json")
        report_path = ROOT / "build/reports" / (directory.name + ".json")
        position_path = ROOT / "build/reports" / (directory.name + "-alignment.json")
        report, position = load(report_path)[0], load(position_path)[0]
        captures = [load(path) for path in (directory / "results").glob("*.json")]
        copied_hash = digest(directory / "mods" / args.jar.name)
        label = directory.name
        add(label + ":identical-delivered-jar", jar_hash == fixture["jarSha256"] == copied_hash,
            declared=fixture["jarSha256"], copied=copied_hash)
        add(label + ":all-37-scenes-finished", run["exitCode"] == 0 and run["finished"]
            and run["scenes"] == len(captures) == 37 and fixture["suite"] == "all")
        add(label + ":production-not-negative-control", not fixture["negativeIrisState"])
        add(label + ":no-fatal-or-render-thread-errors", not run["fatalErrors"] and not run["renderThreadViolations"])
        add(label + ":all-sky-visual-checks-pass", report["passed"] and report["passedChecks"] == report["totalChecks"]
            and report["totalChecks"] >= 300)
        add(label + ":all-independent-position-checks-pass", position["passed"]
            and position["passedChecks"] == position["totalChecks"] and position["totalChecks"] >= 100)
        add(label + ":no-pending-resource-reloads", all(not c["resourceReloadPending"] for c in captures))
        add(label + ":iris-logical-state-exactly-restored", all(c["irisMainBoundBefore"] == c["irisMainBoundAfter"]
            and c["irisOverridesBefore"] == c["irisOverridesAfter"] for c in captures))
        add(label + ":vanilla-cloud-height-192", all(c["cloudWorldHeight"] == 192 for c in captures))
        solid = next(c for c in captures if c["scene"] == "bug-panel-solid-hand")
        if fixture["profile"] == "full":
            add(label + ":user-fsr2-setting-preserved", fixture["shaderOptions"].get("FSR2_SCALE") == "2"
                and solid["rasterScale"] == [.5875, .5875] and solid["activeViewport"] == [0, 0, 752, 470])
        else:
            mode = (fixture["shader"] is None and not solid["finalResolved"]) if fixture["profile"] == "vanilla" else (
                fixture["shader"] == "iterationRP Alpha 0.8.31.zip"
                and fixture["shaderOptions"].get("FSR2_SCALE") == "-1" and solid["finalResolved"])
            add(label + ":native-resolution-control", solid["rasterScale"] == [1., 1.]
                and solid["activeViewport"] == [0, 0, 1280, 800] and mode)
        color_checks = [c for c in report["checks"] if c["name"].endswith(":final-display-matches-independent-sky")]
        item_errors = [e for s in position["scenes"].values() for e in s["itemCenterErrorsPixels"]]
        fixtures.append({"label": label, "profile": fixture["profile"], "shader": fixture["shader"],
                         "shaderOptions": fixture["shaderOptions"], "seconds": run["seconds"], "scenes": run["scenes"],
                         "skyPassedChecks": report["passedChecks"], "skyTotalChecks": report["totalChecks"],
                         "positionPassedChecks": position["passedChecks"], "positionTotalChecks": position["totalChecks"],
                         "runtimeErrorLines": len(run["errors"]), "fatalErrors": len(run["fatalErrors"]),
                         "renderThreadViolations": len(run["renderThreadViolations"]),
                         "maximumFinalSkyRgbError": max(c["maximumRgbError"] for c in color_checks),
                         "maximumItemCenterErrorPixels": round(max(item_errors), 3),
                         "minimumOutlinePixelPrecision": min(s["linePixelPrecision"] for s in position["scenes"].values()),
                         "minimumVisibleEdgeCoverage": min(s["minimumEdgeCoverage"] for s in position["scenes"].values()),
                         "visualReport": str(report_path.resolve()), "positionReport": str(position_path.resolve()),
                         "jarSha256": copied_hash})
    add("all-three-environments-covered", {f["profile"] for f in fixtures} == {"full", "iris", "vanilla"}
        and len(fixtures) == 3)
    negative = load(args.negative / "fixture.json")
    negative_run = load(args.negative / "run-result.json")
    negative_report = load(ROOT / "build/reports" / (args.negative.name + "-alignment.json"))[0]
    negative_copied_hash = digest(args.negative / "mods" / args.jar.name)
    failed = [c for c in negative_report["checks"] if not c["passed"]]
    add("negative-control-uses-identical-jar-and-fsr2", negative["jarSha256"] == negative_copied_hash == jar_hash
        and negative["negativeIrisState"] and negative["shaderOptions"].get("FSR2_SCALE") == "2")
    add("negative-control-completes-normally", negative_run["exitCode"] == 0 and negative_run["finished"]
        and negative_run["scenes"] == 9 and not negative_run["fatalErrors"])
    add("negative-control-fails-real-outline-position", not negative_report["passed"] and any(
        c["name"].endswith(":line-pixels-at-correct-projected-location") for c in failed))
    add("negative-control-fails-real-item-position", any("item-" in c["name"] and c["name"].endswith(
        "-at-correct-projected-location") for c in failed))
    baseline = [c for c in negative_report["checks"] if c["name"].startswith("align-solid-no-sky:")]
    add("negative-control-no-sky-baseline-still-passes", bool(baseline) and all(c["passed"] for c in baseline))
    controls = load(ROOT / "build/reports/state-test4-position-oracle-controls.json")
    add("synthetic-position-controls-fail-with-state-metadata-unchanged", controls["passed"]
        and controls["syntheticDerivedImageControls"] and len(controls["checks"]) == 2
        and all(c["actualPositionFailures"] == c["expectedPositionFailures"]
                and c["allLogicalStateChecksStillPass"] for c in controls["checks"]))
    package = load(ROOT / "build/reports/skybox-test4-package-audit.json")
    add("package-audit-matches-delivered-jar", package["passed"] and package["sha256"] == jar_hash)
    result = {"jar": str(args.jar.resolve()), "jarSha256": jar_hash, "jarBytes": args.jar.stat().st_size,
              "fixtures": fixtures, "negativeControl": {"directory": str(args.negative.resolve()),
              "passedChecks": negative_report["passedChecks"], "totalChecks": negative_report["totalChecks"],
              "expectedFailures": len(failed)}, "checks": checks,
              "passedChecks": sum(c["passed"] for c in checks), "totalChecks": len(checks),
              "passed": all(c["passed"] for c in checks)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{result['passedChecks']}/{result['totalChecks']} acceptance checks {'PASS' if result['passed'] else 'FAIL'}")
    print(json.dumps(fixtures, ensure_ascii=False, indent=2))
    print("SHA256", jar_hash)
    for c in checks:
        if not c["passed"]:
            print("FAIL", c)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
