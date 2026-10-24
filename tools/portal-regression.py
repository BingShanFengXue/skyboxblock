#!/usr/bin/env python3
"""Run the independent portal harness, including a genuinely absent-skyboxblock baseline."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile

ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location("world_fixture",ROOT/"tools/render-regression.py")
world=importlib.util.module_from_spec(spec)
spec.loader.exec_module(world)
launcher=world.launcher

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant",choices=["baseline","empty","sky"],required=True)
    parser.add_argument("--label",required=True)
    parser.add_argument("--mode",choices=["portal","remote-direct"],default="portal",
                        help="portal keeps the original nine scenes; remote-direct replays one observed child pose without portals")
    parser.add_argument("--direct-from",type=Path,help="Existing fixture root/results or a captured scene JSON; read-only source")
    parser.add_argument("--direct-scene",choices=["01-single-front","02-single-oblique","03-single-near","04-through-before"],default="01-single-front")
    parser.add_argument("--prepare-only",action="store_true",help="remote-direct only: prepare a new owned fixture without starting a client")
    parser.add_argument("--jar",type=Path,default=None)
    parser.add_argument("--harness-jar",type=Path,default=ROOT/"build/libs/skybox-portal-fixture-1.0.0.jar")
    parser.add_argument("--shader")
    parser.add_argument("--shader-settings",choices=["defaults","instance"],default="instance")
    parser.add_argument("--shader-option",action="append",default=[])
    parser.add_argument("--full",action="store_true",help="Copy the full reference mod set, keeping skyboxblock absent in baseline")
    parser.add_argument("--timeout",type=int,default=600)
    parser.add_argument("--heap",default="6G")
    parser.add_argument("--settle-ticks",type=int,default=90)
    parser.add_argument("--ip-render-mode",choices=["normal","basic"],default="normal",
                        help="owned portal fixture only: basic enables IP compatibilityRenderMode; remote-direct inherits its source config")
    parser.add_argument("--debug-mixins",action="store_true")
    args=parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+",args.label): parser.error("Use a simple unique label")
    if not re.fullmatch(r"[1-9][0-9]*[MG]",args.heap,re.I): parser.error("Invalid heap")
    overrides={}
    for option in args.shader_option:
        key,equals,value=option.partition("=")
        if not equals or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*",key) or "\n" in value or "\r" in value:
            parser.error("--shader-option expects NAME=VALUE")
        overrides[key]=value
    if overrides and not args.shader and args.mode!="remote-direct": parser.error("Shader overrides require a shader")
    if args.mode=="remote-direct":
        if args.direct_from is None: parser.error("remote-direct requires --direct-from")
        direct_spec=importlib.util.spec_from_file_location("portal_remote_direct",ROOT/"tools/portal-remote-direct.py")
        direct=importlib.util.module_from_spec(direct_spec)
        direct_spec.loader.exec_module(direct)
        return direct.run(args,world)
    if args.direct_from is not None or args.prepare_only: parser.error("Direct control flags require --mode remote-direct")
    args.jar=args.jar or ROOT/"build/libs/skyboxblock-1.0.2-test.5.jar"
    instance=ROOT/"build/portal-fixture"/args.label
    instance.mkdir(parents=True,exist_ok=False)
    (instance/"mods").mkdir()
    wanted={"fabric-api","sodium","immersive_portals"}
    if args.shader: wanted.add("iris")
    selected=[]
    for source in (launcher.VERSION_DIR/"mods").glob("*.jar"):
        with zipfile.ZipFile(source) as archive:
            if "fabric.mod.json" not in archive.namelist():
                if not args.full: continue
                info={"id":"unidentified:"+source.name,"version":"unknown"}
            else:
                info=json.loads(archive.read("fabric.mod.json"))
        identifier=info.get("id")
        if identifier in {"skyboxblock","skybox_ip_fixture"}: continue
        if not args.full and identifier not in wanted: continue
        shutil.copy2(source,instance/"mods"/source.name)
        wanted.discard(identifier)
        selected.append({"id":identifier,"version":info.get("version"),"file":source.name,"sha256":sha(source)})
    if wanted: raise RuntimeError("Missing required mods: "+repr(wanted))
    for source,identifier in ((args.harness_jar,"skybox_ip_fixture"),(args.jar,"skyboxblock")):
        if identifier=="skyboxblock" and args.variant=="baseline": continue
        source=source.resolve()
        with zipfile.ZipFile(source) as archive:
            info=json.loads(archive.read("fabric.mod.json"))
            if info["id"]!=identifier: raise RuntimeError("Wrong jar identity: "+str(source))
        shutil.copy2(source,instance/"mods"/source.name)
        selected.append({"id":identifier,"version":info["version"],"file":source.name,"sha256":sha(source)})
    if args.full:
        shutil.copytree(launcher.VERSION_DIR/"config",instance/"config")
        lines=[]
        for line in (launcher.VERSION_DIR/"options.txt").read_text(encoding="utf-8").splitlines():
            key,_,value=line.partition(":")
            if key=="resourcePacks":
                for pack in json.loads(value):
                    if pack.startswith("file/"):
                        source=launcher.VERSION_DIR/"resourcepacks"/pack[5:]
                        target=instance/"resourcepacks"/pack[5:]
                        if source.exists():
                            target.parent.mkdir(parents=True,exist_ok=True)
                            if source.is_dir(): shutil.copytree(source,target)
                            else: shutil.copy2(source,target)
            if key=="pauseOnLostFocus": value="false"
            if key=="maxFps": value="90"
            lines.append(key+":"+value)
        (instance/"options.txt").write_text("\n".join(lines)+"\n",encoding="utf-8")
    else:
        (instance/"config").mkdir()
        (instance/"options.txt").write_text("version:3955\nenableVsync:false\nmaxFps:90\npauseOnLostFocus:false\n"
            "renderDistance:8\nsimulationDistance:5\nguiScale:2\nlang:en_us\nbobView:false\nrenderClouds:\"false\"\n"
            "resourcePacks:[]\nonboardAccessibility:false\ntutorialStep:none\n",encoding="utf-8")
    (instance/"config/iris.properties").write_text("enableShaders="+("true" if args.shader else "false")
        +"\ndisableUpdateMessage=true\n"+("shaderPack="+args.shader+"\n" if args.shader else ""),encoding="utf-8")
    # Do not modify the user's config; explicit fixture settings are recorded below.
    portal_config={"compatibilityRenderMode":args.ip_render_mode=="basic",
        "maxPortalLayer":2,"enableCrossPortalView":True,"enableClippingMechanism":True,
        "useDepthClampForPortalRendering":True,"initialScreenShown":True}
    (instance/"config/immersive_portals.json").write_text(json.dumps(portal_config,indent=2),encoding="utf-8")
    dh=instance/"config/DistantHorizons.toml"
    if dh.exists():
        dh.write_text(dh.read_text(encoding="utf-8").replace("enableAutoUpdater = true","enableAutoUpdater = false"),encoding="utf-8")
    settings_text=""
    if args.shader:
        (instance/"shaderpacks").mkdir()
        source=launcher.VERSION_DIR/"shaderpacks"/args.shader
        shutil.copy2(source,instance/"shaderpacks"/source.name)
        settings=source.with_name(source.name+".txt")
        if args.shader_settings=="instance" and settings.exists(): settings_text=settings.read_text(encoding="utf-8")
        lines=[line for line in settings_text.splitlines() if line.partition("=")[0].strip() not in overrides]
        settings_text="\n".join(lines+[key+"="+value for key,value in overrides.items()])+"\n"
        (instance/"shaderpacks"/(args.shader+".txt")).write_text(settings_text,encoding="utf-8")
    world.make_world(instance/"saves/fixture")
    version=json.loads((launcher.VERSION_DIR/(launcher.VERSION_NAME+".json")).read_text(encoding="utf-8"))
    command=[str(launcher.JAVA),"-Xms1G","-Xmx"+args.heap,"-XX:+UseZGC","-XX:+ZGenerational",
        f"-Djava.library.path={launcher.NATIVES}",f"-Dorg.lwjgl.system.SharedLibraryExtractPath={launcher.NATIVES}",
        "-Dskyboxfixture.enabled=true",f"-Dskyboxfixture.variant={args.variant}",
        f"-Dskyboxfixture.gameDir={instance}",f"-Dskyboxfixture.output={instance/'results'}",
        "-Dskyboxfixture.exit=true",f"-Dskyboxfixture.settleTicks={args.settle_ticks}",
        "-Dskyboxblock.regression=false","-Dskyboxblock.debug=true"]
    if args.debug_mixins: command.append("-Dmixin.debug.export=true")
    command += ["-cp",os.pathsep.join(launcher.build_classpath(version)),"net.fabricmc.loader.impl.launch.knot.KnotClient",
        "--username","SkyboxPortalTest","--version","1.21.1","--gameDir",str(instance),
        "--assetsDir",str(launcher.MC_ROOT/"assets"),"--assetIndex","17","--uuid","00000000000000000000000000000000",
        "--accessToken","0","--userType","legacy","--versionType","release","--width","1280","--height","800",
        "--quickPlaySingleplayer","fixture"]
    manifest={"variant":args.variant,"full":args.full,"shader":args.shader,"settings":settings_text,
        "shaderSha256":sha(launcher.VERSION_DIR/"shaderpacks"/args.shader) if args.shader else None,
        "mods":selected,"skyboxblockPresent":args.variant!="baseline","harnessSha256":sha(args.harness_jar),
        "jarSha256":sha(args.jar) if args.variant!="baseline" else None,"settleTicks":args.settle_ticks,
        "ipRenderMode":args.ip_render_mode,"portalConfig":portal_config}
    (instance/"fixture.json").write_text(json.dumps(manifest,indent=2),encoding="utf-8")
    print("fixture:",instance,flush=True)
    started=time.monotonic()
    with (instance/"client.log").open("wb") as log:
        process=subprocess.Popen(command,cwd=instance,stdout=log,stderr=subprocess.STDOUT)
        print("pid:",process.pid,flush=True)
        try: code=process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            process.terminate(); process.wait(timeout=20)
            print("FAILED: fixture timeout",flush=True)
            return 124
    log=(instance/"client.log").read_text(encoding="utf-8",errors="replace")
    summary_path=instance/"results/summary.json"
    summary=json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
    result={"exitCode":code,"seconds":round(time.monotonic()-started,2),"finished":"[skybox-portal-fixture] FINISHED:" in log,
        "scenes":len(list((instance/"results").glob("[0-9][0-9]-*.json"))) if (instance/"results").exists() else 0,
        "errors":[line for line in log.splitlines() if "/ERROR]" in line],"summary":summary,
        "renderThreadViolations":[line for line in log.splitlines() if "Rendersystem called from wrong thread" in line],
        "fatalErrors":[line for line in log.splitlines() if any(marker in line for marker in
            ("Uncaught exception","OutOfMemoryError","Encountered an unexpected exception","Failed to compile shader",
             "Shader compilation failed","Rendersystem called from wrong thread"))]}
    (instance/"run-result.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
    print(json.dumps({key:value for key,value in result.items() if key!="summary"},indent=2),flush=True)
    print("summary success:",summary.get("success"),"through:",summary.get("actualThroughCompleted"),
          "return:",summary.get("actualReturnCompleted"),flush=True)
    return 0 if code==0 and result["finished"] and result["scenes"]==9 and not result["fatalErrors"] and summary.get("success") else 1
if __name__=="__main__": raise SystemExit(main())
