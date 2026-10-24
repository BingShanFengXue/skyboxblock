#!/usr/bin/env python3
"""Validate one real remote-direct control against one existing fixed portal capture.

A successful CONTROL report is not blanket portal compatibility or a component
root-cause verdict. Clocks, jitter, exposure and temporal histories can differ.
Only independently projected ordinary pillar/stripe evidence is classified;
cross-run RGB differences are observations, with no resize or fitted warp.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("remote_direct_oracle", ROOT / "tools/check-portal-regression.py")
oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def configuration(fixture, manifest):
    mods = {}
    actual_files = {path.name for path in (fixture / "mods").glob("*.jar")}
    if actual_files != {item["file"] for item in manifest["mods"]}:
        raise ValueError("Actual installed jar set differs from the manifest")
    for item in manifest["mods"]:
        path = oracle.safe_child(fixture / "mods", item["file"])
        with zipfile.ZipFile(path) as archive: info = json.loads(archive.read("fabric.mod.json"))
        if info["id"] != item["id"] or sha(path) != item["sha256"]: raise ValueError("Installed mod identity/hash mismatch")
        if info["id"] in mods: raise ValueError("Duplicate installed mod ID")
        mods[info["id"]] = item["sha256"]
    pack_hash, text = None, None
    if manifest["shader"]:
        pack = oracle.safe_child(fixture / "shaderpacks", manifest["shader"])
        pack_hash = sha(pack)
        if pack_hash != manifest["shaderSha256"]: raise ValueError("Actual shader archive hash mismatch")
        options = pack.with_name(pack.name + ".txt")
        if options.exists(): text = options.read_text(encoding="utf-8")
    settings = oracle.settings_evidence(manifest, text, actual_pack_hash=pack_hash)
    if not settings["matched"]: raise ValueError("Actual semantic settings do not match manifest")
    selection = oracle.properties((fixture / "config/iris.properties").read_text(encoding="utf-8"))
    if selection.get("enableShaders", "").lower() != str(bool(manifest["shader"])).lower() \
            or (manifest["shader"] and selection.get("shaderPack") != manifest["shader"]):
        raise ValueError("Actual shader selection differs")
    options = settings["actualEffective"]
    profile = dict(iris=bool(manifest["shader"]), shaderHash=pack_hash,
                   fsrOption=int(options.get("FSR2_SCALE", -1)) if pack_hash == oracle.ITRP_SHA256 else None,
                   customResolution=options.get("CUSTOM_RENDER_RESOLUTION", "false").lower() == "true")
    return dict(mods=mods, settings=settings, profile=profile, selection=selection)


def actual_geometry(evidence, variant, require_no_portals=True):
    expected = {}
    for x in range(29, 36):
        for y in range(140, 147):
            expected[(x, y, 8)] = "minecraft:red_concrete" if x % 2 else "minecraft:white_concrete"
            expected[(x, y, 6)] = "skyboxblock:sky_block" if variant == "sky" else "minecraft:air"
    for y in range(140, 144): expected[(32, y, 4)] = "minecraft:stone"
    for x in range(-12, 49):
        for z in range(-12, 13): expected[(x, 139, z)] = "minecraft:bedrock"
    seen, good = set(), True
    for item in evidence["blocks"]:
        position = tuple(item["position"])
        good &= position in expected and position not in seen and item["actual"] == expected.get(position)
        seen.add(position)
    digest = hashlib.sha256(json.dumps(evidence["blocks"], ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    return bool(good and seen == expected.keys() and evidence["sampleCount"] == len(expected)
                and digest == evidence["blockEvidenceSha256"]
                and (not require_no_portals or (evidence["portalEntityCount"] == 0
                     and evidence["portalEntityUuids"] == [] and evidence["globalPortalCount"] == 0))), dict(
                    observed=len(seen), required=len(expected), verifiedBlockEvidenceSha256=digest)


def check(portal_path, direct_path, scene="01-single-front"):
    checks, observation = [], {}
    def add(name, passed, **details): checks.append(dict(name=name, passed=bool(passed), **details))
    result = dict(schemaVersion=1, passed=False, protocolPassed=False, directGeometryPassed=None,
                  portalGeometryPassed=None, portalPathOnlyDifferenceObserved=None, checks=checks,
                  observations=observation, scope="One same-overworld fixed child-camera replay; no portal is created in the direct run.")
    try:
        pr = oracle.results_path(Path(portal_path)); dr = oracle.results_path(Path(direct_path))
        pm = json.loads((pr.parent / "fixture.json").read_text(encoding="utf-8"))
        dm = json.loads((dr.parent / "fixture.json").read_text(encoding="utf-8"))
        source_file = pr / (scene + ".json")
        p = json.loads(source_file.read_text(encoding="utf-8"))
        d = json.loads((dr / "00-remote-direct.json").read_text(encoding="utf-8"))
        ds = json.loads((dr / "summary.json").read_text(encoding="utf-8"))
        run = json.loads((dr.parent / "run-result.json").read_text(encoding="utf-8"))
        pc, dc = configuration(pr.parent, pm), configuration(dr.parent, dm)
        add("actual-harness-identities", pc["mods"].get("skybox_ip_fixture") == pm["harnessSha256"]
            and dc["mods"].get("skybox_ip_fixture") == dm["harnessSha256"])
        add("reference-fixed-scene", scene in oracle.FIXED and p["scene"] == scene)
        add("explicit-direct-mode", dm.get("mode") == d.get("mode") == ds.get("mode") == "remote-direct")
        add("real-single-direct-capture", run["exitCode"] == 0 and run["finished"] is True and run["scenes"] == 1
            and not run["fatalErrors"] and ds["success"] is True and ds["remoteDirectVerified"] is True
            and ds["scenes"] == ["00-remote-direct"] and d["screenshotWritten"] is True and not d["errors"])
        plan = d["remoteDirect"]["plan"]
        add("reference-pinned", plan["sourceScene"] == scene and plan["sourceMetadataSha256"] == sha(source_file)
            and plan["sourceImageSha256"] == sha(pr / (scene + ".png")))
        add("same-variant-primary-presence", pm["variant"] == dm["variant"] == p["variant"] == d["variant"]
            and pm["skyboxblockPresent"] == dm["skyboxblockPresent"] == d["skyboxblockLoaded"]
            and ("skyboxblock" in pc["mods"]) == ("skyboxblock" in dc["mods"]) == (pm["variant"] != "baseline"))
        add("same-nonharness-mods", {k: v for k, v in pc["mods"].items() if k != "skybox_ip_fixture"}
            == {k: v for k, v in dc["mods"].items() if k != "skybox_ip_fixture"})
        add("same-actual-pack-settings-selection", pm["shaderSha256"] == dm["shaderSha256"]
            and pc["settings"]["actualEffective"] == dc["settings"]["actualEffective"] and pc["selection"] == dc["selection"])
        observation["harnessHashes"] = dict(reference=pm["harnessSha256"], direct=dm["harnessSha256"],
                                            intentionallyRebuiltForOptionalControl=pm["harnessSha256"] != dm["harnessSha256"])
        for key in oracle.GEOMETRY:
            add("same-declared-geometry:" + key, np.allclose(p["fixtureGeometry"][key], d["fixtureGeometry"][key], atol=1e-6, rtol=0)
                and np.allclose(d["fixtureGeometry"][key], oracle.GEOMETRY[key], atol=1e-6, rtol=0))
        add("no-portal-views-created", not d["portalCreation"]["forwardCreated"] and not d["portalCreation"]["reverseCreated"]
            and d["portalViewCount"] == 0 and not any(view["portalRendering"] for view in d["views"])
            and d["rootReturnRenderState"]["portalLayer"] == 0 and not d["rootReturnRenderState"]["worldRenderInfoActive"])
        world_ok, world_metrics = actual_geometry(d["serverPlayer"]["remoteDirectWorld"], d["variant"])
        add("server-actual-ordinary-geometry-and-portal-inventory", world_ok, **world_metrics)
        reference_world = p["serverPlayer"].get("actualWorldGeometry")
        reference_verified = False
        if reference_world is not None:
            reference_verified, ref_metrics = actual_geometry(reference_world, p["variant"], require_no_portals=False)
            add("reference-actual-geometry-evidence", reference_verified, **ref_metrics)
            add("same-actual-block-evidence", reference_world["blockEvidenceSha256"]
                == d["serverPlayer"]["remoteDirectWorld"]["blockEvidenceSha256"])
        observation["referenceActualGeometry"] = dict(available=reference_world is not None, verified=reference_verified,
            legacyLimitation="Old captures have declared/source-reviewed geometry only; rerun the fixed portal pose with the new harness before stronger causal interpretation." if reference_world is None else None)
        result["referenceActualGeometryVerified"] = reference_verified
        g = oracle.geometry(p, pc["profile"])
        direct_raw = oracle.harness_root(d)
        child = [view for view in p["views"] if view["portalRendering"] and not view["irisShadow"]
                 and np.linalg.norm(oracle.camera(view) - g["childOrigin"]) <= 1e-4]
        if len(child) != 1: raise ValueError("Ambiguous reference child")
        aligned, metrics = oracle.root_child_match(child[0], direct_raw, np.zeros(3))
        add("actual-raw-camera-and-matrices-match-child", aligned, **metrics)
        direct_world = oracle.actual_world(direct_raw, dc["profile"])
        aligned_world, metrics_world = oracle.root_child_match(g["childWorld"], direct_world, np.zeros(3))
        add("actual-material-camera-and-matrices-match-child", aligned_world, **metrics_world)
        add("same-output-grid-no-warp", p["width"] == d["width"] and p["height"] == d["height"])
        for label, meta, profile in (("reference-child", dict(p, rootView=child[0]), pc["profile"]), ("direct", d, dc["profile"])):
            if profile["iris"] and profile["shaderHash"] == oracle.ITRP_SHA256:
                ok, evidence = oracle.live_fsr_settings(meta, profile)
                add(label + ":actual-FSR", ok, **evidence)
        observation["clocksAndJitter"] = dict(reference=child[0].get("rasterHints"), direct=direct_raw.get("rasterHints"),
                                               note="Recorded, not forcibly synchronized; legacy captures may have no clock fields.")
        protocol = all(item["passed"] for item in checks)
        result["protocolPassed"] = protocol
        if protocol:
            a, b = oracle.rgb(pr / (scene + ".png")), oracle.rgb(dr / "00-remote-direct.png")
            if a.shape != b.shape or a.shape != (p["height"], p["width"], 3): raise ValueError("Output pixel grid mismatch")
            rays = oracle.rays(direct_world, d["width"], d["height"])
            origin = oracle.camera(direct_world)
            pillar_distance = oracle.box_distance(origin, rays, d["fixtureGeometry"]["remotePillarBounds"])
            behind_distance = oracle.box_distance(origin, rays, d["fixtureGeometry"]["behindBounds"])
            pillar_mask = g["childPillar"] & np.isfinite(pillar_distance) & (pillar_distance < behind_distance)
            p_ok, p_metrics = oracle.pillar_evidence(a, pillar_mask)
            d_ok, d_metrics = oracle.pillar_evidence(b, pillar_mask)
            observation["pillar"] = dict(portal=dict(passed=p_ok, **p_metrics), direct=dict(passed=d_ok, **d_metrics))
            if p["variant"] != "sky":
                ps, psm = oracle.stripe_evidence(a, g); ds_ok, dsm = oracle.stripe_evidence(b, g)
                observation["stripes"] = dict(portal=dict(passed=ps, **psm), direct=dict(passed=ds_ok, **dsm))
                p_ok, d_ok = p_ok and ps, d_ok and ds_ok
            _, difference = oracle.compare_images(a, b, oracle.erode(pillar_mask, oracle.LIMITS["margin"]))
            observation["same-grid-pillar-RGB-difference-not-a-causal-pass-threshold"] = difference
            result.update(passed=bool(d_ok), directGeometryPassed=bool(d_ok), portalGeometryPassed=bool(p_ok),
                          portalPathOnlyDifferenceObserved=bool(d_ok and not p_ok),
                          strongerCausalInterpretationReady=bool(reference_verified and d_ok and not p_ok))
    except (OSError, ValueError, KeyError, TypeError, IndexError, zipfile.BadZipFile) as exception:
        add("complete-trustworthy-control-evidence", False, error=str(exception))
        result["protocolPassed"] = False
        result["passed"] = False
    result["passedChecks"] = sum(item["passed"] for item in checks)
    result["totalChecks"] = len(checks)
    result["interpretation"] = ("passed means a valid direct control with expected ordinary geometry, NOT full portal support. "
        "A normal direct baseline and abnormal portal baseline with skyboxblock absent supports a portal-path combination boundary "
        "under these exact settings, not a unique IP/Iris/ITRP component root cause. Invalid protocols yield no visual verdict.")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--portal", type=Path, required=True)
    parser.add_argument("--direct", type=Path, required=True)
    parser.add_argument("--scene", choices=oracle.FIXED, default="01-single-front")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = check(args.portal, args.direct, args.scene)
    if args.output:
        path = oracle.fresh(args.output); path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as stream: json.dump(result, stream, indent=2, allow_nan=False); stream.write("\n")
        print("report:", path)
    print("DIRECT CONTROL", "PASS" if result["passed"] else "NOT VERIFIED", "protocol=", result["protocolPassed"],
          "directGeometry=", result["directGeometryPassed"], "portalGeometry=", result["portalGeometryPassed"],
          "portalPathDifferenceObserved=", result["portalPathOnlyDifferenceObserved"])
    for item in result["checks"]:
        if not item["passed"]: print("CHECK FAILED", json.dumps(item))
    return 0 if result["passed"] else 1


if __name__ == "__main__": raise SystemExit(main())
