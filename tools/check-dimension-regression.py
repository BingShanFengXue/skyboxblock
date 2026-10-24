#!/usr/bin/env python3
"""Independent clock/appearance oracle for real dimension fixtures, not a renderer enable-flag check."""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image

EXPECTED = {
    "dim-overworld-day": ("minecraft:overworld", "local-sky"),
    "dim-overworld-time-set": ("minecraft:overworld", "local-sky"),
    "dim-overworld-moon-next": ("minecraft:overworld", "local-sky"),
    "dim-overworld-frozen": ("minecraft:overworld", "local-sky"),
    "dim-overworld-jump-day": ("minecraft:overworld", "local-sky"),
    "dim-nether-day": ("minecraft:the_nether", "simulated-world-ticks"),
    "dim-nether-time-set": ("minecraft:the_nether", "simulated-world-ticks"),
    "dim-nether-night": ("minecraft:the_nether", "simulated-world-ticks"),
    "dim-nether-moon-next": ("minecraft:the_nether", "simulated-world-ticks"),
    "dim-end-day": ("minecraft:the_end", "simulated-world-ticks"),
    "dim-end-night": ("minecraft:the_end", "simulated-world-ticks"),
    "dim-end-reload": ("minecraft:the_end", "simulated-world-ticks"),
    "dim-fixed-day": ("skybox_fixture:normal_fixed", "local-sky"),
    "dim-fixed-command": ("skybox_fixture:normal_fixed", "local-sky"),
    "dim-dark-night": ("skybox_fixture:normal_dark", "local-sky"),
    "dim-dark-command": ("skybox_fixture:normal_dark", "local-sky"),
    "dim-return": ("minecraft:overworld", "local-sky"),
}

def image(path):
    with Image.open(path) as source:
        return np.asarray(source.convert("RGB"))

def phase(tick, partial):
    fraction=((tick % 24000)+partial)/24000-.25
    fraction-=math.floor(fraction)
    return float(np.float32(np.float32(2*fraction+.5-math.cos(fraction*math.pi)/2)/np.float32(3)))

def check(directory):
    metadata={p.stem: json.loads(p.read_text(encoding="utf-8")) for p in directory.glob("dim-*.json")}
    report={"directory": str(directory), "checks": [], "scenes": {}}
    def add(name, passed, **data):
        report["checks"].append({"name": name, "passed": bool(passed), **data})
    add("all-real-dimension-scenes-present", set(metadata)==set(EXPECTED), actual=sorted(metadata), required=sorted(EXPECTED))
    brightness={}
    for name,(dimension,source) in EXPECTED.items():
        if name not in metadata:
            continue
        meta=metadata[name]
        state=meta.get("displaySky", {})
        add(name+":real-dimension-and-policy", meta.get("dimension")==dimension and state.get("timeSource")==source,
            dimension=meta.get("dimension"), timeSource=state.get("timeSource"))
        if not state:
            continue
        wanted=(state["nativePhase"] if source=="local-sky" else phase(state["gameTime"],state["partialTick"]))
        add(name+":actual-phase", abs(state["phase"]-wanted)<=1e-7,
            actual=state["phase"], expected=wanted, gameTime=state["gameTime"], dayTime=state["dayTime"])
        wanted_moon=state["nativeMoonPhase"]%8 if source=="local-sky" else state["gameTime"]//24000%8
        add(name+":consistent-moon-phase", state["moonPhase"]==wanted_moon, actual=state["moonPhase"], expected=wanted_moon)
        if "dimensionFixture" in meta:
            fixture=meta["dimensionFixture"] or {}
            add(name+":actual-server-and-client-geometry-ready",
                fixture.get("expectedDimension")==fixture.get("actualClientDimension")==dimension
                and all(fixture.get(side,{}).get(key)==count for side in ("server","client")
                        for key,count in (("sky",110),("backing",156),("floor",169)))
                and fixture.get("clientReadyTicks",-1)>=20,evidence=fixture)
        add(name+":fixed-vanilla-cloud-height", meta.get("cloudWorldHeight")==192)
        add(name+":no-render-state-errors", meta.get("initialGlError")==meta.get("finalGlError")==0
            and meta.get("irisMainBoundBefore")==meta.get("irisMainBoundAfter")
            and meta.get("irisOverridesBefore")==meta.get("irisOverridesAfter"))
        base_path=directory/(name+"-scene-base.png")
        clouds_path=directory/(name+"-scene-clouds.png")
        add(name+":private-sky-images-present",base_path.exists() and clouds_path.exists())
        if base_path.exists() and clouds_path.exists():
            base,clouds=image(base_path),image(clouds_path)
            changes=int((np.max(np.abs(base.astype(int)-clouds.astype(int)),axis=-1)>2).sum())
            add(name+":clouds-exist-in-this-dimension",changes>32, changedPixels=changes)
            brightness[name]=float(clouds.mean())
        report["scenes"][name]=state
    def pair(a,b,suffix,predicate):
        if a not in metadata or b not in metadata:
            add(suffix,False,missing=[x for x in (a,b) if x not in metadata])
            return
        left,right=metadata[a]["displaySky"],metadata[b]["displaySky"]
        add(suffix,predicate(left,right),left=left,right=right)
    pair("dim-overworld-day","dim-overworld-time-set","overworld-time-command-changes-actual-sky",
         lambda a,b: abs(a["phase"]-b["phase"])>.4)
    pair("dim-overworld-moon-next","dim-overworld-frozen","frozen-native-time-stays-frozen",
         lambda a,b: a["phase"]==b["phase"] and a["dayTime"]==b["dayTime"] and a["moonPhase"]==b["moonPhase"])
    pair("dim-overworld-time-set","dim-overworld-moon-next","native-day-jump-advances-native-moon",
         lambda a,b: b["moonPhase"]==(a["moonPhase"]+1)%8)
    pair("dim-overworld-time-set","dim-overworld-jump-day","native-night-to-day-jump-respected",
         lambda a,b: abs(a["phase"]-b["phase"])>.4)
    pair("dim-nether-day","dim-nether-time-set","nether-native-fixed-time-not-used",
         lambda a,b: a["nativePhase"]==b["nativePhase"] and a["gameTime"]<b["gameTime"]
         and abs(a["phase"]-b["phase"])<.05 and a["phase"]!=a["nativePhase"])
    pair("dim-nether-night","dim-nether-moon-next","simulated-moon-advances-with-world-runtime",
         lambda a,b: b["moonPhase"]==(a["moonPhase"]+1)%8)
    for prefix in ("nether","end"):
        a,b="dim-"+prefix+"-day","dim-"+prefix+"-night"
        pair(a,b,prefix+":simulated-day-night-despite-fixed-native-time",
             lambda left,right: left["nativeFixedTime"] and right["nativeFixedTime"]
             and abs(left["phase"]-right["phase"])>.35 and left["nativePhase"]==right["nativePhase"])
        add(prefix+":visible-day-night-difference", a in brightness and b in brightness
            and brightness[a]>brightness[b]+10, day=brightness.get(a),night=brightness.get(b))
    pair("dim-fixed-day","dim-fixed-command","normal-sky-fixed-time-respected",
         lambda a,b: a["nativeFixedTime"] and a["phase"]==b["phase"]==0 and a["dayTime"]!=b["dayTime"])
    pair("dim-dark-night","dim-dark-command","normal-sky-without-skylight-still-follows-native-time",
         lambda a,b: not a["hasSkylight"] and not b["hasSkylight"] and a["phase"]==b["phase"]==.5)
    report["passedChecks"]=sum(c["passed"] for c in report["checks"])
    report["totalChecks"]=len(report["checks"])
    report["passed"]=bool(metadata) and all(c["passed"] for c in report["checks"])
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory",type=Path)
    parser.add_argument("--output",type=Path)
    args=parser.parse_args()
    report=check(args.directory)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2,allow_nan=False)+"\n",encoding="utf-8")
    print(f"{report['passedChecks']}/{report['totalChecks']} {'PASS' if report['passed'] else 'FAIL'}")
    for item in report["checks"]:
        if not item["passed"]:
            print("FAIL",json.dumps(item,allow_nan=False))
    return 0 if report["passed"] else 1
if __name__=="__main__":
    raise SystemExit(main())
