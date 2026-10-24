#!/usr/bin/env python3
"""Baseline-only IP basic-mode assessment; imports the unchanged geometric oracle.

This never substitutes one fixture for baseline/empty/sky and is not a release gate.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import zipfile

SPEC = importlib.util.spec_from_file_location("portal_oracle", Path(__file__).with_name("check-portal-regression.py"))
ORACLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORACLE)
EVIDENCE_SPEC = importlib.util.spec_from_file_location("portal_practical_evidence", Path(__file__).with_name("portal-practical-evidence.py"))
EVIDENCE = importlib.util.module_from_spec(EVIDENCE_SPEC)
EVIDENCE_SPEC.loader.exec_module(EVIDENCE)
EXPECTED_RENDERER = "qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer"
EXPECTED_PIPELINE = "net.irisshaders.iris.pipeline.IrisRenderingPipeline"
EXPECTED_HARNESS = "612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7"
EXPECTED_IP = "bca05068056bea26dab60491bd536efa458eb3c9979ef6541b1e501983b497ea"


def check(path):
    checks = []
    def add(kind, name, passed, **metrics):
        checks.append(dict(kind=kind, name=name, passed=bool(passed), **metrics))
    try:
        data = ORACLE.load(path)
        manifest, summary, run = (data[k] for k in ("manifest", "summary", "run"))
        add("protocol", "genuine-baseline", manifest["variant"] == summary["variant"] == "baseline"
            and manifest["skyboxblockPresent"] is False and summary["skyboxblockLoaded"] is False)
        mods = {}
        for jar in sorted((path / "mods").glob("*.jar")):
            with zipfile.ZipFile(jar) as archive:
                info = json.loads(archive.read("fabric.mod.json"))
            if info["id"] in mods:
                raise ValueError("duplicate actual mod ID: " + info["id"])
            mods[info["id"]] = dict(sha256=ORACLE.sha(jar), version=info["version"])
        add("protocol", "actual-no-primary-mod", "skyboxblock" not in mods)
        add("protocol", "pinned-harness", mods["skybox_ip_fixture"]["sha256"] == manifest["harnessSha256"] == EXPECTED_HARNESS)
        add("protocol", "pinned-IP", mods["immersive_portals"]["sha256"] == EXPECTED_IP
            and mods["immersive_portals"]["version"] == "6.0.6")
        for mod in manifest["mods"]:
            add("protocol", "actual-mod-hash:" + mod["id"], mods[mod["id"]]["sha256"] == mod["sha256"])
        config_path = path / "config" / "immersive_portals.json"
        actual_config = json.loads(config_path.read_text(encoding="utf-8"))
        add("protocol", "manifest-basic-config", manifest["ipRenderMode"] == "basic"
            and manifest["portalConfig"]["compatibilityRenderMode"] is True)
        # IP reserializes its real fields, dropping the launcher's historical aliases.
        # Compare the intended meanings with the actual persisted field names and
        # disclose dropped aliases; do not pretend those aliases were honored.
        config_aliases = {"crossPortalView": "enableCrossPortalView", "clipping": "enableClippingMechanism",
            "depthClamp": "useDepthClampForPortalRendering"}
        effective_config = {config_aliases.get(k, k): v for k, v in manifest["portalConfig"].items()}
        add("protocol", "actual-basic-config", actual_config["compatibilityRenderMode"] is True
            and all(actual_config.get(k) == v for k, v in effective_config.items()),
            actualEffective={k: actual_config.get(k) for k in effective_config},
            launchAliasesAbsentAfterIPReserialization=[k for k in config_aliases if k not in actual_config])
        add("protocol", "actual-run", run["exitCode"] == 0 and run["finished"] is True
            and run["scenes"] == 9 and not run["fatalErrors"] and summary["success"] is True and not summary["errors"])
        add("protocol", "nine-real-captures", summary["expectedScenes"] == summary["capturedScenes"] == 9
            and tuple(summary["scenes"]) == ORACLE.SCENES)
        for result in ORACLE.movement_checks(summary["movementEvidence"]):
            add("protocol", result.pop("name"), result.pop("passed"), **result)
        pack = ORACLE.safe_child(path / "shaderpacks", manifest["shader"])
        pack_hash = ORACLE.sha(pack)
        add("protocol", "pinned-shader-pack", pack_hash == manifest["shaderSha256"] == ORACLE.ITRP_SHA256)
        settings_path = pack.with_name(pack.name + ".txt")
        settings = ORACLE.settings_evidence(manifest, settings_path.read_text(encoding="utf-8") if settings_path.exists() else None, pack_hash)
        add("protocol", "actual-effective-settings", settings["matched"], **settings)
        iris_properties = ORACLE.properties((path / "config" / "iris.properties").read_text(encoding="utf-8"))
        add("protocol", "actual-shader-selection", iris_properties.get("enableShaders", "").lower() == "true"
            and iris_properties.get("shaderPack") == manifest["shader"])
        profile = dict(iris=True, shaderHash=pack_hash, fsrOption=int(settings["actualEffective"]["FSR2_SCALE"]),
            customResolution=settings["actualEffective"]["CUSTOM_RENDER_RESOLUTION"] == "true")
        for scene in ORACLE.SCENES:
            meta, pixels = data["metadata"][scene], data["pictures"][scene]
            physical_ok, physical_metrics = EVIDENCE.portal_protocol(meta, summary)
            add("protocol", scene + ":actual-mode-and-physical-evidence", physical_ok, **physical_metrics)
            add("protocol", scene + ":capture", meta["scene"] == scene and meta["variant"] == "baseline"
                and meta["screenshotWritten"] is True and meta["skyboxblockLoaded"] is False
                and not meta["errors"] and pixels.shape == (meta["height"], meta["width"], 3)
                and (meta["width"], meta["height"]) == (1280, 800) and meta["skyViews"] == [])
            root = ORACLE.harness_root(meta)
            add("protocol", scene + ":actual-renderer", all(v["rendererClass"] == EXPECTED_RENDERER
                and v["pipelineClass"] == EXPECTED_PIPELINE and v["shaderpack"] == manifest["shader"]
                for v in [root, *meta["views"], meta["rootReturnRenderState"]]))
            add("protocol", scene + ":actual-Iris-active", all(v["rasterHints"]["irisActive"] is True for v in [root, *meta["views"]]))
            ok, metrics = ORACLE.live_fsr_settings(meta, profile)
            add("protocol", scene + ":live-FSR", ok, **metrics)
            state = meta["rootReturnRenderState"]
            add("protocol", scene + ":root-return", state["portalRendering"] is False and state["portalLayer"] == 0
                and state["worldRenderInfoActive"] is False and meta["glBeforeScreenshot"] == meta["glAfterScreenshot"])
            observed = [v for v in meta["views"] if v["portalRendering"] and not v["irisShadow"]]
            add("protocol", scene + ":actual-child", meta["portalViewObserved"] == bool(observed)
                and meta["portalViewCount"] >= len(observed) and (len(observed) == 1 if scene in ORACLE.PORTAL_SCENES else not observed))
            g = ORACLE.geometry(meta, profile)
            if scene in ORACLE.PORTAL_SCENES:
                ok, metrics = ORACLE.root_child_match(root, observed[0], g["translation"])
                add("protocol", scene + ":translation-matrices", ok, **metrics)
            if scene in ORACLE.FIXED:
                ok, metrics = ORACLE.stripe_evidence(pixels, g)
                add("geometry", scene + ":behind-stripes", ok, **metrics)
                ok, metrics = ORACLE.pillar_evidence(pixels, g["childPillar"])
                add("geometry", scene + ":projected-pillar", ok, **metrics)
            if scene == "05-through-after":
                ok, metrics = ORACLE.pillar_evidence(pixels, g["directPillar"])
                add("geometry", scene + ":arrival-direct-pillar", ok, **metrics)
        extra = dict(profile=profile, actualMods=mods, actualConfigSha256=ORACLE.sha(config_path),
            runErrors=run.get("errors"), run=run,
            rendererClass=EXPECTED_RENDERER, pipelineClass=EXPECTED_PIPELINE)
    except (KeyError, ValueError, TypeError, IndexError, OSError, zipfile.BadZipFile) as error:
        add("protocol", "complete-evidence", False, error=str(error))
        extra = {}
    protocol = all(c["passed"] for c in checks if c["kind"] == "protocol")
    geometry = [c for c in checks if c["kind"] == "geometry"]
    geometry_passed = all(c["passed"] for c in geometry) if protocol and len(geometry) == 9 else None
    return dict(fixture=str(path.resolve()), protocolPassed=protocol, baselineGeometryPassed=geometry_passed,
        passedChecks=sum(c["passed"] for c in checks), totalChecks=len(checks), checks=checks, **extra)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = dict(reportType="baseline-only-basic-IP-assessment", releaseAccepted=False,
        oracleSha256=ORACLE.sha(Path(__file__).with_name("check-portal-regression.py")),
        assessorSha256=ORACLE.sha(Path(__file__)), unchangedLimits=ORACLE.LIMITS,
        interpretation="Baseline-only; no primary mod, empty/sky or new direct controls. A red ordinary baseline ends the bounded attempt; no unique primary-mod attribution.",
        results=[check(p) for p in args.baseline])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    for result in report["results"]:
        print(json.dumps({k: result[k] for k in ("fixture", "protocolPassed", "baselineGeometryPassed", "passedChecks", "totalChecks")}, ensure_ascii=False))
        for failure in (c for c in result["checks"] if not c["passed"]):
            print(json.dumps(failure, ensure_ascii=False))
    return 0 if all(r["protocolPassed"] and r["baselineGeometryPassed"] for r in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
