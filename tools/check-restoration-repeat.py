#!/usr/bin/env python3
"""Check restored-world residuals against two genuine never-sky native repeats.

The original strict transparency report is preserved. This companion accepts no
other failures and never derives a budget from the candidate's residuals.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile
import numpy as np
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location("native_measurement",ROOT/"tools/measure-native-repeat.py")
measurement=importlib.util.module_from_spec(spec); spec.loader.exec_module(measurement)
protocol_spec=importlib.util.spec_from_file_location("restoration_settled_protocol",ROOT/"tools/settled-transparency-protocol.py")
S=importlib.util.module_from_spec(protocol_spec); protocol_spec.loader.exec_module(S)

def artifact_ids(directory):
    fixture=directory.parent
    manifest=json.loads((fixture/"fixture.json").read_text(encoding="utf-8"))
    mods={}
    for path in (fixture/"mods").glob("*.jar"):
        with zipfile.ZipFile(path) as jar:
            if "fabric.mod.json" not in jar.namelist(): identifier=path.name
            else: identifier=json.loads(jar.read("fabric.mod.json"))["id"]
        mods[identifier]=measurement.digest(path)
    return manifest,mods

def budget(native_pairs):
    if len(native_pairs)<2: return None
    raw_mae=max(v["raw"]["mae"] for v in native_pairs)
    raw_p99=max(v["raw"]["p99"] for v in native_pairs)
    if raw_mae>1.4 or raw_p99>9: return None
    if any(v["lowpass"]["mae"]>1 or v["lowpass"]["p99"]>2 or v["lowpass"]["maximum"]>4 for v in native_pairs): return None
    return dict(mae=max(1.,raw_mae+.10),p99=max(5.,raw_p99+1.),
                lowpassMae=1.,lowpassP99=2.,lowpassMaximum=4.,absoluteMaeCap=1.5,absoluteP99Cap=10.)

def acceptable(metrics,limits):
    return bool(limits) and metrics["raw"]["mae"]<=min(limits["mae"],limits["absoluteMaeCap"]) \
        and metrics["raw"]["p99"]<=min(limits["p99"],limits["absoluteP99Cap"]) \
        and metrics["lowpass"]["mae"]<=limits["lowpassMae"] \
        and metrics["lowpass"]["p99"]<=limits["lowpassP99"] \
        and metrics["lowpass"]["maximum"]<=limits["lowpassMaximum"]

def check(candidate,controls,strict_report):
    report=dict(kind="native-repeat-calibrated-restoration",checks=[],controls=[],candidate=None,
        note="Companion only: retains raw strict failures; calibrated budget is from never-sky controls, capped, and low-frequency checked")
    def add(name,passed,**data): report["checks"].append(dict(name=name,passed=bool(passed),**data))
    try:
        add("two-distinct-native-controls",len(controls)>=2 and len(set(p.resolve() for p in controls))==len(controls))
        observed=[measurement.measure(p.resolve(),True) for p in controls]
        target=measurement.measure(candidate.resolve(),False)
        report["controls"],report["candidate"]=observed,target
        protocol_directories=[Path(value["directory"]).parent for value in observed+[target]]
        protocols=[S.inspect_fixture(directory) for directory in protocol_directories]
        for directory,protocol in zip(protocol_directories,protocols):
            S.verify_warmup_images(directory,protocol,all_no_sky=True)
        report["settledProtocolEvidence"]=protocols
        add("same-declared-and-observed-settlement-protocol",all(p["signature"]==protocols[-1]["signature"] for p in protocols))
        fields=("jarSha256","captureJitterBits","shaderArchiveSha256","inputSettingsSha256","freezeShaderTime")
        add("same-real-package-shader-time-and-phase",all(tuple(v[k] for k in fields)==tuple(target[k] for k in fields) for v in observed)
            and target["freezeShaderTime"] is True)
        directory=Path(target["directory"])
        candidate_manifest,candidate_mods=artifact_ids(directory)
        add("candidate-is-not-native-control",candidate_manifest.get("nativeTransparencyControl") is False)
        for i,value in enumerate(observed):
            path=Path(value["directory"]); manifest,mods=artifact_ids(path)
            add(f"control-{i}:same-supporting-mods-and-profile",mods==candidate_mods and manifest["profile"]==candidate_manifest["profile"])
            log=(path.parent/"client.log").read_text(encoding="utf-8",errors="replace")
            shader_enabled=manifest.get("shader") is not None
            add(f"control-{i}:freeze-actually-active",not shader_enabled or "fixture-only Iris frameTimeCounter=0 active" in log,
                shaderEnabled=shader_enabled,noShaderScope="No shader-time activation claim for explicit vanilla controls" if not shader_enabled else None)
            if not shader_enabled:
                add(f"control-{i}:actual-vanilla-no-Iris-profile",manifest.get("profile")=="vanilla" and "iris" not in mods
                    and manifest.get("shaderOptions")=={})
            for meta_path in path.glob("trans-*.json"):
                meta=json.loads(meta_path.read_text(encoding="utf-8"))
                if shader_enabled and meta.get("irisFrameCounter") is None: raise ValueError("Missing actual Iris frameCounter in "+str(meta_path))
        for front in ("none","glass","water"):
            limits=budget([value["pairs"][front] for value in observed])
            add(front+":native-controls-stable-under-hard-caps",limits is not None,budget=limits)
            add(front+":candidate-restoration-within-native-budget",acceptable(target["pairs"][front],limits),
                metrics=target["pairs"][front],budget=limits)
            first="trans-open-"+front+"-none-marker"; last="trans-removed-"+front
            meta=json.loads((directory/(last+".json")).read_text(encoding="utf-8"))
            add(front+":no-remaining-sky-or-private-write",meta["blocks"]==meta["vertices"]==0
                and meta["depthChangedPixels"]==0 and not meta["finalResolved"]
                and not meta["itrpBackgroundInjected"] and meta["transparency"]["serverSkyBlockCount"]==0)
            final=measurement.img(directory/(last+".png")); pre=measurement.img(directory/(last+"-pre-resolve.png"))
            add(front+":same-frame-no-late-private-color",np.array_equal(final,pre))
            for i,value in enumerate(observed):
                control_meta=json.loads((Path(value["directory"])/(first+".json")).read_text(encoding="utf-8"))
                candidate_meta=json.loads((directory/(first+".json")).read_text(encoding="utf-8"))
                add(f"{front}:control-{i}-same-camera-matrix-and-geometry",all(control_meta[k]==candidate_meta[k] for k in
                    ("camera","inverseDisplayViewProjection","width","height","rasterScale","rasterJitter")))
        strict=json.loads(strict_report.read_text(encoding="utf-8"))
        if isinstance(strict,list): raise ValueError("Expected one transparency report object")
        allowed={"trans-removed-"+front+":removed-world-restored" for front in ("none","glass","water")}
        failures=[item for item in strict["checks"] if not item["passed"]]
        add("strict-report-is-same-candidate",Path(strict["directory"]).resolve()==directory.resolve())
        add("no-other-strict-failure-is-waived",all(item["name"] in allowed for item in failures),
            originalFailedChecks=[item["name"] for item in failures])
        report["strictPassedChecks"]=strict["passedChecks"]; report["strictTotalChecks"]=strict["totalChecks"]
        report["strictReportSha256"]=measurement.digest(strict_report)
    except (ValueError,KeyError,OSError,AssertionError) as error: add("complete-valid-calibration-evidence",False,error=str(error))
    report["passedChecks"]=sum(c["passed"] for c in report["checks"]); report["totalChecks"]=len(report["checks"])
    report["passed"]=bool(report["checks"]) and all(c["passed"] for c in report["checks"])
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate",type=Path)
    parser.add_argument("--control",type=Path,action="append",default=[])
    parser.add_argument("--strict-report",type=Path)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--selftest",action="store_true")
    args=parser.parse_args()
    if args.selftest:
        native=dict(raw=dict(mae=.7,p99=8),lowpass=dict(mae=.1,p99=1,maximum=2))
        bounds=budget([native,native]); assert bounds and acceptable(native,bounds)
        corrupt=dict(raw=dict(mae=.7,p99=8),lowpass=dict(mae=3,p99=10,maximum=30))
        assert not acceptable(corrupt,bounds)
        assert not acceptable(dict(raw=dict(mae=4,p99=30),lowpass=native["lowpass"]),bounds)
        assert budget([native]) is None
        assert budget([native,dict(raw=dict(mae=5,p99=40),lowpass=native["lowpass"])]) is None
        print("5/5 native-calibration control invariants PASS"); return 0
    if not all((args.candidate,args.strict_report,args.output)): parser.error("candidate/strict-report/output are required")
    result=check(args.candidate,args.control,args.strict_report)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8") as output: json.dump(result,output,indent=2,allow_nan=False)
    print(f"{result['passedChecks']}/{result['totalChecks']} {'PASS' if result['passed'] else 'FAIL'}")
    for c in result["checks"]:
        if not c["passed"]: print("FAIL",json.dumps(c))
    return 0 if result["passed"] else 1
if __name__=="__main__": raise SystemExit(main())
