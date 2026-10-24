#!/usr/bin/env python3
"""Audit the test.5 distribution, real Mixin refmaps and explicit fixture-only readback guards."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
ROOT=Path(__file__).resolve().parent.parent

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar",nargs="?",type=Path,default=ROOT/"build/libs/skyboxblock-1.0.2-test.5.jar")
    parser.add_argument("--sources",type=Path,help="Companion source jar; defaults to the same directory and version")
    parser.add_argument("--output",type=Path,default=ROOT/"build/reports/skybox-test5-package-audit.json")
    args=parser.parse_args()
    checks=[]
    def add(name,passed,**data): checks.append({"name":name,"passed":bool(passed),**data})
    with zipfile.ZipFile(args.jar) as archive:
        names=set(archive.namelist())
        metadata=json.loads(archive.read("fabric.mod.json"))
        add("mod-id-and-version",metadata.get("id")=="skyboxblock" and metadata.get("version")=="1.0.2-test.5")
        add("mit-license",metadata.get("license")=="MIT" and "LICENSE_skyboxblock" in names)
        add("project-author-is-Bing6512",metadata.get("authors")==["Bing6512"])
        add("minimum-loader-covers-new-injector",metadata.get("depends",{}).get("fabricloader")==">=0.16.14")
        contributors="\n".join(metadata.get("contributors",[]))
        add("AI-stage-contributors-are-explicit",all(token in contributors for token in
            ("ChatGPT (OpenAI)","DeepSeek V4.1 Flash","GPT6.1SOL")))
        notices={"LICENSE_skyboxblock":ROOT/"LICENSE","AUTHORS.md":ROOT/"AUTHORS.md",
                 "THIRD_PARTY_NOTICES.md":ROOT/"THIRD_PARTY_NOTICES.md",
                 "META-INF/licenses/skyboxblock/Literal-Sky-Block-MIT.txt":ROOT/"LICENSES/Literal-Sky-Block-MIT.txt"}
        for entry,path in notices.items():
            add("binary-notice-"+entry,entry in names and archive.read(entry)==path.read_bytes())
        source_path=args.sources or args.jar.with_name(args.jar.stem+"-sources.jar")
        add("source-distribution-exists",source_path.is_file(),path=str(source_path))
        if source_path.is_file():
            with zipfile.ZipFile(source_path) as sources:
                source_names=set(sources.namelist())
                for entry,path in notices.items():
                    add("source-notice-"+entry,entry in source_names and sources.read(entry)==path.read_bytes())
        add("private-canvas-clip-isolation-is-packaged",b"negativePrivateSkyClip" in archive.read("dev/yg/skyboxblock/client/VanillaSkyScene$RenderFlags.class"))
        add("independent-fixture-not-bundled-in-core",not any(n.startswith("dev/yg/skyboxfixture/") for n in names))
        for prefix in ("assets/skyboxblock/blockstates/","assets/skyboxblock/models/block/","assets/skyboxblock/models/item/",
                       "data/skyboxblock/recipe/","data/skyboxblock/loot_table/blocks/"):
            actual=sorted(Path(n).stem for n in names if n.startswith(prefix) and n.endswith(".json"))
            add(prefix+"only-sky-block",actual==["sky_block"],actual=actual)
        add("no-preset-sky-textures",not any(n.startswith("assets/skyboxblock/textures/sky/") for n in names))
        legacy={"SkyStyle","AutoTest","SkyProbe","SkyProbeRenderType","SkyGeometry","SkyDeferredRenderer"}
        stale=sorted(n for n in names if n.endswith(".class") and Path(n).stem.split("$")[0] in legacy)
        add("no-legacy-capture-classes",not stale,stale=stale)
        for shader in ("world_sky","sky_resolve","sky_depth_marker","sky_material_background","seam_diagnostic",
                       "vanilla_background","vanilla_celestial","vanilla_stars","vanilla_cloud"):
            location="assets/skyboxblock/shaders/core/"+shader
            spec=json.loads(archive.read(location+".json"))
            stages=["assets/"+ns+"/shaders/core/"+path+suffix
                    for key,suffix in (("vertex",".vsh"),("fragment",".fsh")) for ns,path in [spec[key].split(":",1)]]
            add("shader-"+shader+"-complete",all(stage in names for stage in stages),stages=stages)
        vertex=archive.read("assets/skyboxblock/shaders/core/world_sky.vsh")
        resolve=archive.read("assets/skyboxblock/shaders/core/sky_resolve.fsh")
        background=archive.read("assets/skyboxblock/shaders/core/vanilla_background.fsh")
        material=archive.read("assets/skyboxblock/shaders/core/sky_material_background.fsh")
        add("position-invariance",b"invariant gl_Position" in vertex)
        add("sky-depth-provenance-with-foreground-fingerprint",b"SkyCoverage" in resolve and b"ownSky.y" in resolve
            and b"sceneDepth - ownSky.x" in resolve)
        add("universal-overworld-appearance-no-native-end-texture",b"SkyType" not in background and b"EndSky" not in background)
        add("HDR-bridge-samples-only-owned-sky-and-depth",b"WorldOpaqueDepth" in material and b"SkyCoverage" in material
            and b"colortex" not in material and b"WorldColor" not in material)
        def code(stem): return archive.read("dev/yg/skyboxblock/client/"+stem+".class")
        add("shared-endpoints-single-rounding",b"relativeGridVertex" in code("SkyWorldRenderer$Frame"))
        add("view-lifetime-and-once-only-resolve",b"GAME_VIEWS" in code("SkyWorldRenderer") and b"resolveIrisView" in code("SkyWorldRenderer")
            and b"finalResolveCount" in code("SkyWorldRenderer$Frame"))
        add("uniform-clock-display-state",b"local-sky" in code("SkyDisplayState") and b"simulated-world-ticks" in code("SkyDisplayState")
            and b"displayPhase" in code("SkyRenderMath") and b"simulatedMoonPhase" in code("SkyRenderMath"))
        add("vanilla-cloud-height-192",b"\x04\x43\x40\x00\x00" in code("SkyRenderMath") and b"cloudRelativeY" in code("SkyVanillaClouds")
            and b"getCloudHeight" not in code("SkyVanillaClouds"))
        add("owned-cloud-buffers-with-guarded-legacy-control",b"SkyCloudMesh" in code("SkyVanillaClouds")
            and b"negativeCloudBuffer" in code("SkyVanillaClouds")
            and "dev/yg/skyboxblock/client/SkyCloudMesh.class" in names)
        add("Iris-logical-state-scope",b"isMainBound" in code("ModCompat") and b"setIsMainBound" in code("ModCompat")
            and "dev/yg/skyboxblock/client/ModCompat$IrisTargetScope.class" in names)
        add("exact-pack-hash-bridge",b"334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8" in code("ItrpSkyBridge")
            and b"getDepthTextureNoTranslucents" in code("ItrpSkyBridge$Schema"))
        fixtures={"RenderRegression","SeamScan","PortalViewCapture","FixtureDistantTerrain"}
        readbacks=[]
        for name in names:
            if not name.startswith("dev/yg/skyboxblock/") or not name.endswith(".class"): continue
            stem=Path(name).stem.split("$")[0]
            body=archive.read(name)
            if stem not in fixtures and any(token in body for token in (b"glReadPixels",b"glGetTexImage",b"downloadTexture",b"takeScreenshot")):
                readbacks.append(name)
        add("production-has-no-world-color-readback",not readbacks,classes=sorted(readbacks))
        if "dev/yg/skyboxblock/client/FixtureDistantTerrain.class" in names:
            add("distant-depth-observer-is-owned-fixture-only",all(token in code("FixtureDistantTerrain") for token in
                (b"skyboxblock.regression",b"distantTerrainDiagnostics",b"ownedGameDir",b"Skybox render fixture",b"render-regression")))
        add("seamscan-readback-has-fixture-guards",all(token in code("SeamScan") for token in
            (b"skyboxblock.regression",b"seamscan",b"Skybox render fixture",b"render-regression")))
        add("portal-observer-readback-has-transaction-and-owner-guards",all(token in code("PortalViewCapture") for token in
            (b"skyboxfixture.enabled",b"skyboxfixture.captureScene",b"skyboxfixture.gameDir",b"portal-fixture",b"Skybox render fixture")))
        mixins=json.loads(archive.read("skyboxblock.mixins.json"))
        add("real-refmap-is-packaged",mixins.get("refmap")=="skyboxblock-refmap.json" and mixins["refmap"] in names)
        mapping=json.loads(archive.read(mixins["refmap"]))["mappings"]
        if "RegressionFluidMixin" in mixins.get("mixins",[]):
            fluid=mapping.get("dev/yg/skyboxblock/mixin/RegressionFluidMixin",{})
            add("scheduled-fluid-fixture-hook-has-runtime-mapping",bool(fluid.get("tickFluid")),actual=fluid.get("tickFluid"))
            body=archive.read("dev/yg/skyboxblock/RegressionFluidControl.class")
            add("scheduled-fluid-hook-is-owned-fixture-only",all(token in body for token in
                (b"skyboxblock.regression",b"freezeFluidTicks",b"negativeGlobalFluidFreeze",b"Skybox render fixture",b"ownedGameDir",b"render-regression")))
        game=mapping.get("dev/yg/skyboxblock/mixin/GameRendererMixin",{})
        add("view-wrap-method-has-real-runtime-mapping",game.get("renderLevel")=="Lnet/minecraft/class_757;method_3188(Lnet/minecraft/class_9779;)V",actual=game.get("renderLevel"))
        add("native-hand-span-has-runtime-mapping",bool(game.get("renderItemInHand")),actual=game.get("renderItemInHand"))
        add("optional-Iris-hooks-registered",all(n in mixins.get("client",[]) for n in
            ("IrisPipelineMixin","IrisCompositeMixin","RegressionShaderTimeMixin")))
        if "RegressionWaterTextureMixin" in mixins.get("client",[]):
            texture=mapping.get("dev/yg/skyboxblock/mixin/RegressionWaterTextureMixin",{})
            add("water-sprite-fixture-hook-has-runtime-mapping",bool(texture.get("createTicker")),actual=texture.get("createTicker"))
            control=code("FixtureClientControl")
            add("water-sprite-and-input-controls-are-owned-fixture-only",all(token in control for token in
                (b"skyboxblock.regression",b"freezeWaterTextures",b"ownedGameDir",b"Skybox render fixture",b"render-regression",b"block/water_still",b"block/water_flow")))
            add("settled-capture-requires-real-time-and-render-frames",b"settledTransparency" in code("RenderRegression") and b"poseStableFrames" in code("RenderRegression"))
        for language in ("en_us","zh_cn"):
            data=json.loads(archive.read("assets/skyboxblock/lang/"+language+".json"))
            add(language+"-only-one-block",sorted(k for k in data if k.startswith("block.skyboxblock."))==["block.skyboxblock.sky_block"])
    report={"jar":str(args.jar.resolve()),"bytes":args.jar.stat().st_size,"sha256":hashlib.sha256(args.jar.read_bytes()).hexdigest(),
            "checks":checks,"passedChecks":sum(c["passed"] for c in checks),"totalChecks":len(checks),"passed":all(c["passed"] for c in checks)}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(f"{report['passedChecks']}/{report['totalChecks']} {'PASS' if report['passed'] else 'FAIL'}",report["sha256"])
    for item in checks:
        if not item["passed"]: print("FAIL",json.dumps(item))
    return 0 if report["passed"] else 1
if __name__=="__main__": raise SystemExit(main())
