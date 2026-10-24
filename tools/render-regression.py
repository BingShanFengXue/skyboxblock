#!/usr/bin/env python3
"""Run an isolated rendering fixture; never copies or opens the user's worlds.

Examples:
  python tools/render-regression.py --profile vanilla --label baseline-vanilla
  python tools/render-regression.py --profile compat --shader ComplementaryReimagined_r5.9.3.zip
"""
from __future__ import annotations

import argparse
import gzip
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("skybox_launcher", ROOT / "tools" / "launch-client.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def text(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def payload(kind: int, value) -> bytes:
    if kind in {1, 2, 3, 4, 5, 6}:
        return struct.pack({1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}[kind], value)
    if kind == 8:
        return text(value)
    if kind == 9:
        element, items = value
        return bytes([element]) + struct.pack(">i", len(items)) + b"".join(payload(element, x) for x in items)
    if kind == 10:
        return b"".join(bytes([k]) + text(name) + payload(k, val) for name, (k, val) in value.items()) + b"\0"
    if kind == 11:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i", x) for x in value)
    raise ValueError(kind)


def make_world(directory: Path, extra_dimensions: bool = False, terrain: str = "flat") -> None:
    if terrain not in {"flat", "normal"}:
        raise ValueError("Unknown owned-fixture terrain: " + terrain)
    def noise(kind: str, preset: str) -> dict:
        return {"type": (8, "minecraft:" + kind), "generator": (10, {
            "type": (8, "minecraft:noise"), "settings": (8, "minecraft:" + preset),
            "biome_source": (10, {"type": (8, "minecraft:the_end")} if preset == "end" else {
                "type": (8, "minecraft:multi_noise"), "preset": (8, "minecraft:overworld" if preset == "overworld" else "minecraft:nether")})})}
    dimensions = {
        "minecraft:overworld": (10, {"type": (8, "minecraft:overworld"), "generator": (10, {
            "type": (8, "minecraft:flat"), "settings": (10, {
                "biome": (8, "minecraft:plains"), "features": (1, 0), "lakes": (1, 0),
                "structure_overrides": (9, (8, [])), "layers": (9, (10, [
                    {"height": (3, 1), "block": (8, "minecraft:bedrock")}]))})})}),
        "minecraft:the_nether": (10, noise("the_nether", "nether")),
        "minecraft:the_end": (10, noise("the_end", "end")),
    }
    if terrain == "normal":
        dimensions["minecraft:overworld"] = (10, noise("overworld", "overworld"))
    if extra_dimensions:
        import copy
        for name in ("normal_fixed", "normal_dark"):
            stem = copy.deepcopy(dimensions["minecraft:overworld"][1])
            stem["type"] = (8, "skybox_fixture:" + name)
            dimensions["skybox_fixture:" + name] = (10, stem)
    data = {
        "DataVersion": (3, 3955), "version": (3, 19133),
        "Version": (10, {"Id": (3, 3955), "Name": (8, "1.21.1"), "Series": (8, "main"), "Snapshot": (1, 0)}),
        "DragonFight": (10, {"NeedsStateScanning": (1, 0), "DragonKilled": (1, 1),
                              "PreviouslyKilled": (1, 1), "IsRespawning": (1, 0)}),
        "LevelName": (8, "Skybox render fixture"), "GameType": (3, 3), "allowCommands": (1, 1),
        "hardcore": (1, 0), "Difficulty": (1, 0), "initialized": (1, 1),
        "SpawnX": (3, 0), "SpawnY": (3, 140), "SpawnZ": (3, 0),
        "Time": (4, 6000), "DayTime": (4, 6000), "LastPlayed": (4, int(time.time() * 1000)),
        "GameRules": (10, {k: (8, v) for k, v in {"doDaylightCycle": "false", "doWeatherCycle": "false",
            "doMobSpawning": "false", "spawnChunkRadius": "0", "randomTickSpeed": "0"}.items()}),
        "DataPacks": (10, {"Enabled": (9, (8, ["vanilla"] + (["file/skybox-fixture"] if extra_dimensions else []))), "Disabled": (9, (8, []))}),
        "WorldGenSettings": (10, {"seed": (4, 42), "generate_features": (1, 0), "bonus_chest": (1, 0),
            "dimensions": (10, dimensions)}),
    }
    directory.mkdir(parents=True, exist_ok=False)
    if extra_dimensions:
        pack = directory / "datapacks/skybox-fixture"
        types = pack / "data/skybox_fixture/dimension_type"
        types.mkdir(parents=True)
        (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48,
            "description": "Isolated skybox time-policy regression dimensions"}}), encoding="utf-8")
        for name, fixed, skylight in (("normal_fixed", 6000, True), ("normal_dark", 18000, False)):
            kind = {"ultrawarm": False, "natural": True, "piglin_safe": False,
                "respawn_anchor_works": False, "bed_works": True, "has_raids": True,
                "has_skylight": skylight, "has_ceiling": False, "coordinate_scale": 1.0,
                "ambient_light": 0.0, "min_y": -64, "height": 384, "logical_height": 384,
                "infiniburn": "#minecraft:infiniburn_overworld", "effects": "minecraft:overworld",
                "fixed_time": fixed, "monster_spawn_block_light_limit": 0, "monster_spawn_light_level": 0}
            (types / (name + ".json")).write_text(json.dumps(kind), encoding="utf-8")
    (directory / "level.dat").write_bytes(gzip.compress(bytes([10]) + text("") + payload(10, {"Data": (10, data)})))


def prepare_mods(instance: Path, profile: str, mod_jar: Path) -> list[dict]:
    wanted = {"fabric-api"}
    if profile in {"iris", "compat", "iris-dh"}:
        wanted |= {"iris", "sodium"}
    if profile in {"dh", "iris-dh"}:
        wanted.add("distanthorizons")
    if profile == "compat":
        # Historical portal profile only; R30 DH profiles deliberately omit IP.
        # IP supplies cloth-config and DimLib as nested jars; Fabric loads those itself.
        wanted |= {"immersive_portals", "distanthorizons"}
    mods = instance / "mods"
    mods.mkdir()
    selected = []
    for path in (launcher.VERSION_DIR / "mods").glob("*.jar"):
        with zipfile.ZipFile(path) as jar:
            if "fabric.mod.json" not in jar.namelist():
                if profile != "full":
                    continue
                metadata = {"id": "unidentified:" + path.name, "version": "unknown"}
            else:
                metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata.get("id") == "skyboxblock":
            continue
        if profile != "full" and metadata.get("id") not in wanted:
            continue
        shutil.copy2(path, mods / path.name)
        wanted.discard(metadata["id"])
        selected.append({"id": metadata["id"], "version": metadata["version"], "file": path.name})
    if wanted and profile != "full":
        raise RuntimeError(f"missing fixture mods: {wanted}")
    shutil.copy2(mod_jar, mods / mod_jar.name)
    return selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=["vanilla", "iris", "dh", "iris-dh", "compat", "full"], default="vanilla",
                        help="dh/iris-dh install actual DH without portals; compat is the historical IP+DH profile")
    parser.add_argument("--shader")
    parser.add_argument("--shader-settings", choices=["defaults", "instance"],
                        help="Copy instance shader options; defaults to instance for full profile")
    parser.add_argument("--shader-option", action="append", default=[], metavar="NAME=VALUE",
                        help="Override a shader option only inside this isolated fixture")
    parser.add_argument("--label", default=time.strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--jar", type=Path, default=ROOT / "build" / "libs" / "skyboxblock-1.0.2-test.5.jar")
    parser.add_argument("--timeout", type=int, default=None,
                        help="Fixture timeout seconds; default 1800 for settled transparency, otherwise 360")
    parser.add_argument("--suite", choices=["all", "clouds", "sky", "reference", "blue", "ambient", "handexposure", "bugs", "alignment", "seams", "seamscan", "dimensions", "transparency", "materials", "panomotion", "edges"], default="all")
    parser.add_argument("--negative-surface-layer", action="store_true", help="R34 same-binary control: retain single-sample geometry and jittered material IDs")
    parser.add_argument("--negative-dark-meter", action="store_true", help="R33 control: leave artistic dark skies in the physical exposure meter")
    parser.add_argument("--negative-panorama-rays", action="store_true", help="R32 only: reproduce translated per-face panorama directions while walking")
    parser.add_argument("--negative-material-ownership", action="store_true", help="R31 test-only control: let rear sky styles overwrite front ownership")
    parser.add_argument("--negative-sky-coverage", action="store_true",
                        help="Regression-only control: retain old per-face depth rejection at folded sky joints")
    parser.add_argument("--negative-seams", action="store_true",
                        help="Regression-only control: retain separately rounded shared-edge endpoints")
    parser.add_argument("--negative-transparency", action="store_true",
                        help="Regression-only control: disable exact-pack MRT/background bridge")
    parser.add_argument("--negative-private-sky-clip", action="store_true",
                        help="Regression-only control: inherit world clip distance inside the private sky canvas")
    parser.add_argument("--negative-cloud-buffer", action="store_true",
                        help="Regression-only control: send native clouds through the old world VertexBuffer hooks")
    parser.add_argument("--negative-iris-state", action="store_true",
                        help="Regression-only negative control: reproduce the old un-restored Iris flag")
    parser.add_argument("--heap", default="6G", help="Fixture JVM maximum heap, without changing the user's launcher")
    parser.add_argument("--no-probe-clouds", action="store_true")
    parser.add_argument("--hand-material", choices=["sky", "air", "glass", "stone", "black", "galaxy", "apocalypse", "mixed"], default="sky",
                        help="Owned hand/exposure fixture shell material; never changes the user instance")
    parser.add_argument("--scenes",help="Explicit comma-separated fixture scenes within the chosen suite; default keeps the complete suite")
    parser.add_argument("--negative-fixture-clock",action="store_true",help="Fixture-only diagnostic: reproduce absolute backwards gameTime assignments")
    parser.add_argument("--collection-diagnostics",action="store_true",help="Read-only fixture diagnostics: sample actual client/server blocks, BE attempts and Sodium render lists without refreshing terrain")
    parser.add_argument("--distant-terrain-diagnostics",action="store_true",help="Owned fixture only: read actual Iris DH depth and existing LOD fingerprints without canceling rendering or freezing updates")
    parser.add_argument("--negative-entity-validity",action="store_true",help="Regression-only control: accept stale cached block entity instances as before")
    parser.add_argument("--negative-fixture-startup",action="store_true",help="Fixture-only control: reproduce late render-distance change and skip the initial compiled-section precondition")
    parser.add_argument("--negative-global-fluid-freeze",action="store_true",help="Fixture-only negative control: use the old global server freeze instead of only scheduled water ticks")
    parser.add_argument("--marker-pattern",choices=["striped","solid"],default="striped",help="Fixed physical post materials; solid retains the earlier weak-reference control without changing geometry or thresholds")
    parser.add_argument("--dh-worldgen",choices=["instance","disabled"],default="instance",help="Explicit owned-fixture world-generation policy; disabled stops new distant generation while retaining DH renderer and all installed mods")
    parser.add_argument("--settled-transparency",action="store_true",help="Explicit 53-scene protocol: two fixed sky-free warmups, then every original transparency measurement requires >=15 real seconds and >=300 root frames at the target pose; canonical water sprite frame")
    parser.add_argument("--history-warmup","--never-sky-warmup",dest="history_warmup",action="store_true",help="Legacy short two-pose preconditioning control, not the settled time/frame protocol")
    parser.add_argument("--native-transparency-control", action="store_true",
                        help="Fixture-only timeline control: never place sky blocks in the transparency suite")
    parser.add_argument("--capture-jitter-bits", metavar="XXXXXXXX:YYYYYYYY",
                        help="Select a previously measured finite jitter vector by its exact float32 bits")
    parser.add_argument("--animate-shader-time", action="store_true",
                        help="Keep live shader frameTimeCounter for an explicit non-frozen diagnostic")
    options = parser.parse_args()
    if options.timeout is None:
        options.timeout = 1800 if options.settled_transparency else 360
    if options.timeout <= 0:
        parser.error("--timeout must be positive")
    if options.settled_transparency and options.timeout < 51 * 15:
        parser.error("settled transparency requires at least 765 measurement seconds plus startup/warmups; use --timeout 1800 or more")
    import re
    overrides = {}
    for option in options.shader_option:
        name, equals, value = option.partition("=")
        if not equals or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) or "\n" in value or "\r" in value:
            parser.error("--shader-option expects one NAME=VALUE property")
        overrides[name] = value
    if overrides and not options.shader:
        parser.error("--shader-option requires --shader")
    if not re.fullmatch(r"[1-9][0-9]*[MG]", options.heap, re.IGNORECASE):
        parser.error("--heap expects a positive size such as 6G")
    if options.native_transparency_control and options.suite != "transparency":
        parser.error("--native-transparency-control requires the transparency suite")
    warmup_scenes=[]
    if options.settled_transparency:
        if options.suite!="transparency" or options.scenes or options.history_warmup:
            parser.error("--settled-transparency requires the complete transparency suite, not an ad hoc subset or legacy warmup")
        warmup_scenes=["warmup-empty-front","warmup-empty-oblique"]
    if options.history_warmup:
        if options.suite!="transparency" or options.scenes:
            parser.error("--history-warmup requires an otherwise complete transparency suite")
        warmup_scenes=["align-solid-no-sky","align-clean"]
        measured=[f"trans-{slot}-{front}-{protection}-{state}" for slot in ("open","sky")
                  for front in ("none","glass","water") for protection in ("none","pillar","hand","both")
                  for state in ("empty","marker")]+["trans-removed-"+front for front in ("none","glass","water")]
        options.suite="all"
        options.scenes=",".join(warmup_scenes+measured)
    if options.capture_jitter_bits and not re.fullmatch(r"[0-9a-fA-F]{8}:[0-9a-fA-F]{8}", options.capture_jitter_bits):
        parser.error("--capture-jitter-bits expects two exact float32 hexadecimal words")
    selected_scenes = options.scenes.split(",") if options.scenes else []
    if selected_scenes and (len(set(selected_scenes)) != len(selected_scenes) or any(not re.fullmatch(r"[a-z0-9][a-z0-9-]*",name) for name in selected_scenes)):
        parser.error("--scenes requires unique comma-separated exact scene names")
    settings_mode = options.shader_settings or ("instance" if options.profile == "full" else "defaults")
    instance = ROOT / "build" / "render-regression" / options.label
    instance.mkdir(parents=True, exist_ok=False)
    selected = prepare_mods(instance, options.profile, options.jar.resolve())
    if options.profile == "full":
        shutil.copytree(launcher.VERSION_DIR / "config", instance / "config")
        original_options = (launcher.VERSION_DIR / "options.txt").read_text(encoding="utf-8")
        copied_lines = []
        active_packs = []
        for line in original_options.splitlines():
            key, _, value = line.partition(":")
            if key == "resourcePacks":
                active_packs = json.loads(value)
            if key == "pauseOnLostFocus": value = "false"
            if key == "maxFps": value = "90"
            copied_lines.append(key + ":" + value)
        (instance / "options.txt").write_text("\n".join(copied_lines) + "\n", encoding="utf-8")
        for pack in active_packs:
            if not pack.startswith("file/"):
                continue
            source = launcher.VERSION_DIR / "resourcepacks" / pack[5:]
            if source.exists():
                target = instance / "resourcepacks" / pack[5:]
                target.parent.mkdir(parents=True, exist_ok=True)
                if source.is_dir(): shutil.copytree(source, target)
                else: shutil.copy2(source, target)
    else:
        (instance / "config").mkdir()
        (instance / "options.txt").write_text(
            "version:3955\nenableVsync:false\nmaxFps:90\npauseOnLostFocus:false\nrenderDistance:6\n"
            "simulationDistance:5\nguiScale:2\nlang:en_us\nbobView:false\nrenderClouds:\"false\"\n"
            "resourcePacks:[]\nonboardAccessibility:false\ntutorialStep:none\n", encoding="utf-8")
    # Set the intended fixture view distance before the world/renderer starts, not during the first fill.
    initial_render_distance = 6 if options.negative_fixture_startup else 12
    fixture_options = instance / "options.txt"
    option_lines = [line for line in fixture_options.read_text(encoding="utf-8").splitlines()
                    if line.partition(":")[0] != "renderDistance"]
    fixture_options.write_text("\n".join(option_lines + ["renderDistance:" + str(initial_render_distance)]) + "\n", encoding="utf-8")
    dh_policy={"requested":options.dh_worldgen,"scope":"owned isolated fixture only; source config unchanged"}
    if options.dh_worldgen!="instance" and options.profile not in {"dh","iris-dh","compat","full"}:
        parser.error("--dh-worldgen disabled requires a profile that actually installs Distant Horizons")
    if options.profile in {"dh", "iris-dh", "compat", "full"}:
        dh = instance / "config" / "DistantHorizons.toml"
        if dh.exists():
            original=dh.read_text(encoding="utf-8")
            data=original.replace("enableAutoUpdater = true", "enableAutoUpdater = false")
            if options.dh_worldgen=="disabled":
                import hashlib,tomllib
                parsed=tomllib.loads(data)
                before=parsed["common"]["worldGenerator"]["generatorPlan"]
                renderer=parsed["client"]["advanced"]["debugging"]["rendererMode"]
                if renderer!="DEFAULT": raise ValueError("Controlled worldgen fixture requires existing DEFAULT DH rendering; renderer mode is never silently changed")
                lines=[];section="";changed=0
                for line in data.splitlines():
                    stripped=line.strip()
                    if stripped.startswith("[") and stripped.endswith("]"):section=stripped
                    if section=="[common.worldGenerator]" and stripped.partition("=")[0].strip()=="generatorPlan":
                        line=line[:len(line)-len(line.lstrip())]+'generatorPlan = "DISABLED"';changed+=1
                    lines.append(line)
                if changed!=1: raise ValueError("Expected exactly one known DH generatorPlan entry")
                data="\n".join(lines)+"\n"
                if tomllib.loads(data)["common"]["worldGenerator"]["generatorPlan"]!="DISABLED":raise ValueError("Distant-generation override did not stick")
                dh_policy.update(originalGeneratorPlan=before,actualGeneratorPlan="DISABLED",rendererModeUnchanged=renderer,
                    inputConfigSha256=hashlib.sha256(original.encode("utf-8")).hexdigest(),preparedConfigSha256=hashlib.sha256(data.encode("utf-8")).hexdigest(),
                    limitation="Controlled near-world test; actual dynamic distant-generation/LOD compatibility is not established")
            dh.write_text(data, encoding="utf-8")
        else:
            if options.dh_worldgen!="instance":raise ValueError("Explicit controlled-world policy requires the actual copied DH config")
            dh.write_text('_version = 5\n[client.advanced.autoUpdater]\nenableAutoUpdater = false\nenableSilentUpdates = false\n', encoding="utf-8")
    (instance / "config" / "iris.properties").write_text(
        "enableShaders=" + ("true" if options.shader else "false") + "\ndisableUpdateMessage=true\n"
        + ("shaderPack=" + options.shader + "\n" if options.shader else ""), encoding="utf-8")
    if options.shader:
        (instance / "shaderpacks").mkdir()
        shutil.copy2(launcher.VERSION_DIR / "shaderpacks" / options.shader, instance / "shaderpacks" / options.shader)
        settings = launcher.VERSION_DIR / "shaderpacks" / (options.shader + ".txt")
        fixture_settings = instance / "shaderpacks" / settings.name
        if settings_mode == "instance" and settings.exists():
            shutil.copy2(settings, fixture_settings)
        if overrides:
            existing = fixture_settings.read_text(encoding="utf-8") if fixture_settings.exists() else ""
            preserved = [line for line in existing.splitlines() if line.partition("=")[0].strip() not in overrides]
            fixture_settings.write_text("\n".join(preserved + [key + "=" + value for key, value in overrides.items()]) + "\n",
                                        encoding="utf-8")
    make_world(instance / "saves" / "fixture", extra_dimensions=options.suite in {"all", "dimensions"})
    version = json.loads((launcher.VERSION_DIR / (launcher.VERSION_NAME + ".json")).read_text(encoding="utf-8"))
    classpath = launcher.build_classpath(version)
    import hashlib
    args = [str(launcher.JAVA), "-Xms1G", "-Xmx" + options.heap, "-XX:+UseZGC", "-XX:+ZGenerational",
        f"-Djava.library.path={launcher.NATIVES}", f"-Dorg.lwjgl.system.SharedLibraryExtractPath={launcher.NATIVES}",
        "-Dskyboxblock.debug=true", "-Dskyboxblock.regression=true", "-Dskyboxblock.regression.exit=true",
        f"-Dskyboxblock.regression.ownedGameDir={instance.resolve()}",
        "-Dskyboxblock.regression.freezeShaderTime=" + str(options.suite in {"all", "transparency"} and not options.animate_shader_time).lower(),
        "-Dskyboxblock.regression.nativeTransparencyControl=" + str(options.native_transparency_control).lower(),
        "-Dskyboxblock.regression.markerPattern=" + options.marker_pattern,
        "-Dskyboxblock.regression.captureJitterBits=" + (options.capture_jitter_bits or ""),
        f"-Dskyboxblock.regression.suite={options.suite}", "-Dskyboxblock.regression.freezeClouds=true",
        "-Dskyboxblock.regression.scenes=" + (options.scenes or ""),
        "-Dskyboxblock.regression.handMaterial=" + options.hand_material,
        "-Dskyboxblock.regression.negativeFixtureClock=" + str(options.negative_fixture_clock).lower(),
        "-Dskyboxblock.regression.collectionDiagnostics=" + str(options.collection_diagnostics).lower(),
        "-Dskyboxblock.regression.distantTerrainDiagnostics=" + str(options.distant_terrain_diagnostics).lower(),
        "-Dskyboxblock.regression.negativeEntityValidity=" + str(options.negative_entity_validity).lower(),
        "-Dskyboxblock.regression.negativeFixtureStartup=" + str(options.negative_fixture_startup).lower(),
        "-Dskyboxblock.regression.negativeGlobalFluidFreeze=" + str(options.negative_global_fluid_freeze).lower(),
        "-Dskyboxblock.regression.freezeFluidTicks=" + str(options.suite in {"all","transparency"}).lower(),
        "-Dskyboxblock.regression.settledTransparency=" + str(options.settled_transparency).lower(),
        "-Dskyboxblock.regression.freezeWaterTextures=" + str(options.settled_transparency).lower(),
        "-Dskyboxblock.regression.negativeIrisState=" + str(options.negative_iris_state).lower(),
        "-Dskyboxblock.regression.negativeSeams=" + str(options.negative_seams).lower(),
        "-Dskyboxblock.regression.negativeSkyCoverage=" + str(options.negative_sky_coverage).lower(),
        "-Dskyboxblock.regression.negativeMaterialOwnership=" + str(options.negative_material_ownership).lower(),
        "-Dskyboxblock.regression.negativePanoramaRays=" + str(options.negative_panorama_rays).lower(),
        "-Dskyboxblock.regression.negativeDarkMeter=" + str(options.negative_dark_meter).lower(),
        "-Dskyboxblock.regression.negativeSurfaceLayer=" + str(options.negative_surface_layer).lower(),
        "-Dskyboxblock.regression.negativeTransparency=" + str(options.negative_transparency).lower(),
        "-Dskyboxblock.regression.negativePrivateSkyClip=" + str(options.negative_private_sky_clip).lower(),
        "-Dskyboxblock.regression.negativeCloudBuffer=" + str(options.negative_cloud_buffer).lower(),
        "-Dskyboxblock.clouds=" + ("false" if options.no_probe_clouds else "true"),
        f"-Dskyboxblock.regression.output={instance / 'results'}", "-cp", os.pathsep.join(classpath),
        "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", "SkyboxTest", "--version", "1.21.1",
        "--gameDir", str(instance), "--assetsDir", str(launcher.MC_ROOT / "assets"), "--assetIndex", "17",
        "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userType", "legacy",
        "--versionType", "release", "--width", "1280", "--height", "800", "--quickPlaySingleplayer", "fixture"]
    settings_text = fixture_settings.read_text(encoding="utf-8") if options.shader and fixture_settings.exists() else ""
    shader_options = {key.strip(): value.strip() for line in settings_text.splitlines()
                      if not line.lstrip().startswith(("#", "!")) and "=" in line
                      for key, value in [line.split("=", 1)]}
    (instance / "fixture.json").write_text(json.dumps({"profile": options.profile, "shader": options.shader,
        "shaderSettingsMode": settings_mode, "shaderOptions": shader_options,
        "shaderSettingsSha256": hashlib.sha256(settings_text.encode("utf-8")).hexdigest(),
        "jar": str(options.jar.resolve()), "jarSha256": hashlib.sha256(options.jar.read_bytes()).hexdigest(),
        "suite": options.suite, "requestedScenes": selected_scenes, "negativeFixtureClock": options.negative_fixture_clock,
        "handMaterial": options.hand_material,
        "collectionDiagnostics": options.collection_diagnostics, "distantTerrainDiagnostics": options.distant_terrain_diagnostics,
        "initialRenderDistance": initial_render_distance,
        "negativeEntityValidity": options.negative_entity_validity, "negativeFixtureStartup": options.negative_fixture_startup,
        "negativeGlobalFluidFreeze": options.negative_global_fluid_freeze,
        "freezeFluidTicks": options.suite in {"all","transparency"} and not options.negative_global_fluid_freeze,
        "probeClouds": not options.no_probe_clouds, "freezeClouds": True,
        "freezeShaderTime": options.suite in {"all", "transparency"} and not options.animate_shader_time,
        "nativeTransparencyControl": options.native_transparency_control, "captureJitterBits": options.capture_jitter_bits,
        "markerPattern": options.marker_pattern, "distantGenerationPolicy": dh_policy,
        "fixtureInputIsolation": "owned-client-START_CLIENT_TICK-releaseAll-and-resetToggleKeys",
        "settledTransparency": options.settled_transparency,
        "freezeWaterTextures": options.settled_transparency,
        "waterSpritePhase": "first-declared-resource-frame" if options.settled_transparency else "live",
        "settlementPolicy": {"minimumRealMilliseconds":15000,"minimumRootFrames":300,"pose":"actual target eye position stable"} if options.settled_transparency else None,
        "fixedHistoryWarmupScenes": warmup_scenes,
        "neverSkyWarmupScenes": warmup_scenes if options.native_transparency_control else [],
        "neverSkyMeasurementSceneCount": 51 if options.native_transparency_control else None,
        "negativeIrisState": options.negative_iris_state, "negativeSeams": options.negative_seams,
        "negativeSkyCoverage": options.negative_sky_coverage, "negativeTransparency": options.negative_transparency,
        "negativeMaterialOwnership": options.negative_material_ownership,
        "negativePanoramaRays": options.negative_panorama_rays,
        "negativeDarkMeter": options.negative_dark_meter,
        "negativeSurfaceLayer": options.negative_surface_layer,
        "negativePrivateSkyClip": options.negative_private_sky_clip,
        "negativeCloudBuffer": options.negative_cloud_buffer,
        "heap": options.heap, "timeoutSeconds": options.timeout, "mods": selected}, indent=2), encoding="utf-8")
    print("fixture:", instance, flush=True)
    start = time.monotonic()
    with (instance / "client.log").open("wb") as log:
        process = subprocess.Popen(args, cwd=instance, stdout=log, stderr=subprocess.STDOUT)
        print("pid:", process.pid, flush=True)
        try:
            code = process.wait(timeout=options.timeout)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=20)
            print("FAILED: fixture timeout", flush=True)
            return 124
    log_text = (instance / "client.log").read_text(encoding="utf-8", errors="replace")
    scan = options.suite == "seamscan"
    capture_dir = instance / "results" / "seamscan" if scan else instance / "results"
    scenes = list(capture_dir.glob("stage-*.json" if scan else "*.json")) if capture_dir.exists() else []
    scan_summary_path = capture_dir / "seamscan-summary.json"
    scan_summary = json.loads(scan_summary_path.read_text(encoding="utf-8")) if scan and scan_summary_path.exists() else {}
    result = {"exitCode": code, "seconds": round(time.monotonic() - start, 2), "scenes": len(scenes),
        "finished": ("[skybox-seamscan] FINISHED" if scan else "[skybox-regression] FINISHED") in log_text,
        "scanSummary": scan_summary if scan else None,
        "errors": [line for line in log_text.splitlines() if "/ERROR]" in line],
        "renderThreadViolations": [line for line in log_text.splitlines()
                                   if "Rendersystem called from wrong thread" in line],
        "fatalErrors": [line for line in log_text.splitlines() if any(marker in line for marker in (
            "Uncaught exception", "OutOfMemoryError", "Exception stopping the server",
            "Encountered an unexpected exception", "Failed to compile shader", "Shader compilation failed",
            "A fatal error has been detected by the Java Runtime Environment", "EXCEPTION_ACCESS_VIOLATION"))]}
    (instance / "run-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "scanSummary"}, indent=2), flush=True)
    if scan:
        print("scan:", {key: value for key, value in scan_summary.items() if key != "stages"}, flush=True)
    import re
    finished_count = re.search(r"\[skybox-regression\] FINISHED: (\d+) scenes", log_text)
    expected = scan_summary.get("plannedStages", -1) if scan else (int(finished_count.group(1)) if finished_count else -1)
    actual_count = len(scan_summary.get("stages", [])) if scan else len(scenes)
    return 0 if (code == 0 and result["finished"] and actual_count == expected
                 and (not scan or scan_summary.get("phaseComplete", False))
                 and not result["renderThreadViolations"] and not result["fatalErrors"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
