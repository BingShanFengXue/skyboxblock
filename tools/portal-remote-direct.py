#!/usr/bin/env python3
"""Reference-locked remote-direct preparation/runner, called by portal-regression.py.

No world, shader ZIP or reference fixture is modified. The only runtime change is
rendering a fresh same-geometry world directly from the observed child camera,
without creating any portal. Shader time/history are observed, not frozen here.
"""
from __future__ import annotations
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("portal_direct_existing_oracle", ROOT / "tools/check-portal-regression.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def project_path(path, root=ROOT):
    path = Path(path)
    path = (path if path.is_absolute() else root / path).resolve(strict=True)
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Reference/control input must resolve inside the project: " + str(path))
    return path


def reference_plan(path, scene="01-single-front", variant=None, root=ROOT):
    path = project_path(path, root)
    if path.is_file():
        if path.suffix.lower() != ".json" or path.parent.name != "results":
            raise ValueError("Reference file must be a captured results/SCENE.json")
        scene, results = path.stem, path.parent
    else:
        results = path if path.name == "results" else path / "results"
    results = project_path(results, root)
    fixture = results.parent
    if scene not in oracle.FIXED:
        raise ValueError("Only the four existing fixed pure-translation portal poses are accepted")
    meta_path = project_path(results / (scene + ".json"), root)
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    manifest = json.loads((fixture / "fixture.json").read_text(encoding="utf-8"))
    run = json.loads((fixture / "run-result.json").read_text(encoding="utf-8"))
    if run.get("exitCode") != 0 or not run.get("finished") or run.get("fatalErrors"):
        raise ValueError("Reference run did not complete normally; its image verdict is a separate question")
    if meta.get("errors") or not meta.get("screenshotWritten"):
        raise ValueError("Reference capture is missing or contains harness errors")
    if variant is not None and manifest["variant"] != variant:
        raise ValueError("Direct control variant must match the selected reference")
    if meta["variant"] != manifest["variant"]:
        raise ValueError("Reference capture/manifest variant mismatch")
    for key, expected in oracle.GEOMETRY.items():
        if key not in meta["fixtureGeometry"] or not np.allclose(meta["fixtureGeometry"][key], expected, atol=1e-6, rtol=0):
            raise ValueError("Unverified reference geometry: " + key)
    parent = oracle.harness_root(meta)
    shift = np.asarray(meta["fixtureGeometry"]["translation"], dtype=float)
    children = [view for view in meta["views"] if view["portalRendering"] and not view["irisShadow"]
                and np.linalg.norm(oracle.camera(view) - oracle.camera(parent) - shift) <= 1e-4]
    if len(children) != 1:
        raise ValueError("Reference must have exactly one observed translated child view")
    child = children[0]
    aligned, error = oracle.root_child_match(parent, child, shift)
    if not aligned:
        raise ValueError("Reference is not a verified pure translation: " + repr(error))
    if child["dimension"] != "minecraft:overworld":
        raise ValueError("Remote-direct v1 covers same-overworld translation only")
    player = meta["player"]
    eye_height = oracle.camera(parent)[1] - float(player["position"][1])
    if not .5 < eye_height < 2.5:
        raise ValueError("Unverified first-person camera/feet offset")
    camera = oracle.camera(child)
    feet = camera - [0, eye_height, 0]
    for key in ("yaw", "pitch"):
        if not np.isfinite(float(player[key])):
            raise ValueError("Nonfinite source orientation")
    actual_mods = {}
    for item in manifest["mods"]:
        if Path(item["file"]).name != item["file"]:
            raise ValueError("Unsafe reference mod filename")
        jar = project_path(fixture / "mods" / item["file"], root)
        if sha(jar) != item["sha256"]:
            raise ValueError("Reference installed mod changed: " + item["id"])
        actual_mods[item["id"]] = {**item, "source": str(jar)}
    if ("skyboxblock" in actual_mods) != (manifest["variant"] != "baseline"):
        raise ValueError("Reference primary-mod presence does not match variant")
    pack_path, actual_text, pack_hash = None, None, None
    if manifest["shader"]:
        pack_path = project_path(fixture / "shaderpacks" / manifest["shader"], root)
        pack_hash = sha(pack_path)
        if pack_hash != manifest["shaderSha256"]:
            raise ValueError("Reference shader ZIP changed")
        options = pack_path.with_name(pack_path.name + ".txt")
        if options.exists():
            actual_text = options.read_text(encoding="utf-8")
    settings = oracle.settings_evidence(manifest, actual_text, actual_pack_hash=pack_hash)
    if not settings["matched"]:
        raise ValueError("Reference actual/declared semantic shader settings mismatch")
    selection = oracle.properties((fixture / "config/iris.properties").read_text(encoding="utf-8"))
    if selection.get("enableShaders", "").lower() != str(bool(manifest["shader"])).lower() \
            or (manifest["shader"] and selection.get("shaderPack") != manifest["shader"]):
        raise ValueError("Reference actual shader selection differs from its manifest")
    effective = settings["actualEffective"]
    profile = dict(iris=bool(manifest["shader"]), shaderHash=pack_hash,
                   fsrOption=int(effective.get("FSR2_SCALE", -1)) if pack_hash == oracle.ITRP_SHA256 else None,
                   customResolution=effective.get("CUSTOM_RENDER_RESOLUTION", "false").lower() == "true")
    actual_child = oracle.actual_world(child, profile)
    live_fsr = None
    if profile["iris"] and pack_hash == oracle.ITRP_SHA256:
        live_ok, live_fsr = oracle.live_fsr_settings(dict(meta, rootView=child), profile)
        if not live_ok: raise ValueError("Reference actual child FSR hints do not match declared effective settings")
    plan = dict(schemaVersion=1, mode="remote-direct", variant=manifest["variant"],
                sourceFixture=str(fixture), sourceScene=scene, sourceMetadataSha256=sha(meta_path),
                sourceImageSha256=sha(project_path(results / (scene + ".png"), root)),
                dimension=child["dimension"], camera=camera.tolist(), playerPosition=feet.tolist(),
                yaw=float(player["yaw"]), pitch=float(player["pitch"]),
                view=child["view"], projection=child["projection"],
                width=meta["width"], height=meta["height"], geometry=meta["fixtureGeometry"],
                referenceChild=child, referenceMaterialProjection=actual_child["projection"],
                referenceMaterialView=actual_child["view"], actualFSREvidence=live_fsr,
                semanticShaderOptions=settings["actualEffective"],
                semanticSettingsSha256=settings["actualSemanticSha256"],
                note="Real child-camera replay with no portal. No forced matrix, clock freeze or cross-run pixel equality.")
    return dict(fixture=fixture, manifest=manifest, plan=plan, mods=actual_mods,
                pack=pack_path, settings=settings)


def run(args, world):
    ref = reference_plan(args.direct_from, args.direct_scene, args.variant)
    manifest, plan = ref["manifest"], ref["plan"]
    if args.shader is not None and args.shader != manifest["shader"]:
        raise ValueError("remote-direct locks the reference shader; --shader cannot change it")
    if args.full and not manifest["full"]:
        raise ValueError("remote-direct locks the reference supporting mod set")
    if args.jar is not None and args.variant != "baseline" and sha(args.jar.resolve()) != manifest["jarSha256"]:
        raise ValueError("An explicit --jar must match the reference frozen main jar")
    options = dict(ref["settings"]["actualEffective"])
    for raw in args.shader_option:
        key, _, value = raw.partition("=")
        options[key] = value
    if options != ref["settings"]["actualEffective"]:
        raise ValueError("remote-direct does not permit changing reference shader options")
    harness = args.harness_jar.resolve(strict=True)
    with zipfile.ZipFile(harness) as archive:
        identity = json.loads(archive.read("fabric.mod.json"))
        if "dev/yg/skyboxfixture/RemoteDirectControl.class" not in archive.namelist() \
                or b"remote-direct" not in archive.read("dev/yg/skyboxfixture/PortalFixtureClient.class"):
            raise ValueError("The harness jar predates remote-direct support; rebuild it serially first")
    if identity["id"] != "skybox_ip_fixture":
        raise ValueError("Expected the independently rebuilt portal fixture harness")
    instance = ROOT / "build/portal-fixture" / args.label
    instance.mkdir(parents=True, exist_ok=False)
    (instance / "mods").mkdir()
    selected = []
    for identifier, item in ref["mods"].items():
        if identifier == "skybox_ip_fixture": continue
        source = Path(item["source"])
        shutil.copy2(source, instance / "mods" / source.name)
        selected.append({key: item[key] for key in ("id", "version", "file", "sha256")})
    shutil.copy2(harness, instance / "mods" / harness.name)
    selected.append(dict(id="skybox_ip_fixture", version=identity["version"], file=harness.name, sha256=sha(harness)))
    shutil.copytree(ref["fixture"] / "config", instance / "config")
    source_options = (ref["fixture"] / "options.txt").read_text(encoding="utf-8")
    lines = []
    for line in source_options.splitlines():
        key, separator, value = line.partition(":")
        if key == "pauseOnLostFocus": value = "false"
        if key == "maxFps": value = "90"
        if key == "resourcePacks":
            for pack in json.loads(value):
                if not pack.startswith("file/"): continue
                source = project_path(ref["fixture"] / "resourcepacks" / pack[5:])
                target = instance / "resourcepacks" / pack[5:]
                if not target.resolve().is_relative_to(instance.resolve()): raise ValueError("Unsafe resourcepack path")
                target.parent.mkdir(parents=True, exist_ok=True)
                if source.is_dir(): shutil.copytree(source, target)
                else: shutil.copy2(source, target)
        lines.append(key + separator + value)
    (instance / "options.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    settings_text = "\n".join(key + "=" + value for key, value in sorted(options.items())) + "\n"
    if ref["pack"] is not None:
        (instance / "shaderpacks").mkdir()
        shutil.copy2(ref["pack"], instance / "shaderpacks" / ref["pack"].name)
        (instance / "shaderpacks" / (ref["pack"].name + ".txt")).write_text(settings_text, encoding="utf-8")
    # Preserve the reference's actual Iris selection/config verbatim; semantic options are locked separately.
    world.make_world(instance / "saves/fixture")
    (instance / "direct-plan.json").write_text(json.dumps(plan, indent=2, allow_nan=False), encoding="utf-8")
    new_manifest = dict(manifest, mode="remote-direct", settings=settings_text, mods=selected,
                        harnessSha256=sha(harness), settleTicks=args.settle_ticks,
                        sourceHarnessSha256=manifest["harnessSha256"], remoteDirect=plan)
    (instance / "fixture.json").write_text(json.dumps(new_manifest, indent=2, allow_nan=False), encoding="utf-8")
    print("remote-direct fixture:", instance, "reference:", plan["sourceFixture"], plan["sourceScene"], flush=True)
    if args.prepare_only:
        print("PREPARED ONLY: no client started; no image/control verdict exists", flush=True)
        return 0
    launcher = world.launcher
    version = json.loads((launcher.VERSION_DIR / (launcher.VERSION_NAME + ".json")).read_text(encoding="utf-8"))
    command = [str(launcher.JAVA), "-Xms1G", "-Xmx" + args.heap, "-XX:+UseZGC", "-XX:+ZGenerational",
               f"-Djava.library.path={launcher.NATIVES}", f"-Dorg.lwjgl.system.SharedLibraryExtractPath={launcher.NATIVES}",
               "-Dskyboxfixture.enabled=true", "-Dskyboxfixture.mode=remote-direct", f"-Dskyboxfixture.variant={args.variant}",
               f"-Dskyboxfixture.gameDir={instance}", f"-Dskyboxfixture.output={instance / 'results'}",
               f"-Dskyboxfixture.directPlan={instance / 'direct-plan.json'}", f"-Dskyboxfixture.settleTicks={args.settle_ticks}",
               "-Dskyboxfixture.exit=true", "-Dskyboxblock.regression=false", "-Dskyboxblock.debug=true"]
    if args.debug_mixins: command.append("-Dmixin.debug.export=true")
    command += ["-cp", os.pathsep.join(launcher.build_classpath(version)), "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--username", "SkyboxPortalTest", "--version", "1.21.1", "--gameDir", str(instance),
                "--assetsDir", str(launcher.MC_ROOT / "assets"), "--assetIndex", "17", "--uuid", "00000000000000000000000000000000",
                "--accessToken", "0", "--userType", "legacy", "--versionType", "release", "--width", str(plan["width"]),
                "--height", str(plan["height"]), "--quickPlaySingleplayer", "fixture"]
    started, timed_out = time.monotonic(), False
    with (instance / "client.log").open("wb") as log:
        process = subprocess.Popen(command, cwd=instance, stdout=log, stderr=subprocess.STDOUT)
        print("owned fixture pid:", process.pid, flush=True)
        try: code = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            process.terminate()
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            code = 124
    log_text = (instance / "client.log").read_text(encoding="utf-8", errors="replace")
    summary_path = instance / "results/summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
    result = dict(exitCode=code, timedOut=timed_out, seconds=round(time.monotonic() - started, 2),
                  finished="[skybox-portal-fixture] FINISHED:" in log_text,
                  scenes=len(list((instance / "results").glob("[0-9][0-9]-*.json"))), summary=summary,
                  errors=[line for line in log_text.splitlines() if "/ERROR]" in line],
                  renderThreadViolations=[line for line in log_text.splitlines() if "Rendersystem called from wrong thread" in line],
                  fatalErrors=[line for line in log_text.splitlines() if any(marker in line for marker in
                     ("Uncaught exception", "OutOfMemoryError", "Encountered an unexpected exception", "Failed to compile shader",
                      "Shader compilation failed", "Rendersystem called from wrong thread"))])
    (instance / "run-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "summary"}, indent=2), flush=True)
    return 0 if code == 0 and result["finished"] and result["scenes"] == 1 and not result["fatalErrors"] \
        and summary.get("success") and summary.get("remoteDirectVerified") else 1
