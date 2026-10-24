#!/usr/bin/env python3
"""Verify actual same-jar native-cloud buffer alias reproduction and owned-buffer repair."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import zipfile
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parent.parent
SPEC=importlib.util.spec_from_file_location("cloud_dimension_oracle",ROOT/"tools/check-dimension-regression.py")
dimensions=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(dimensions)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def load(path): return json.loads(path.read_text(encoding="utf-8"))
def img(path):
    with Image.open(path) as value: return np.asarray(value.convert("RGB")).astype(int)
def mods(root):
    result={}
    for path in (root/"mods").glob("*.jar"):
        with zipfile.ZipFile(path) as archive:
            name=load_json(archive.read("fabric.mod.json"))["id"] if "fabric.mod.json" in archive.namelist() else path.name
        if name in result: raise ValueError("Duplicate mod identity "+name)
        result[name]=sha(path)
    return result
def load_json(value): return json.loads(value)
def logical_vertices(hex_value,stride):
    data=bytes.fromhex(hex_value)
    # The game's 28-byte native layout includes a non-semantic padding byte after the normal.
    if stride!=28 or len(data)!=stride*4: raise ValueError("Unverified diagnostic native cloud layout")
    return tuple(data[i:i+27] for i in range(0,len(data),stride))

def evaluate(positive,negative):
    checks=[]
    def add(name,passed,**data): checks.append(dict(name=name,passed=bool(passed),**data))
    p,n=load(positive/"fixture.json"),load(negative/"fixture.json")
    pm,nm=mods(positive),mods(negative)
    add("same-actual-candidate-and-supporting-mods",pm==nm and pm.get("skyboxblock")==p["jarSha256"]==n["jarSha256"])
    add("opposite-fixture-only-buffer-controls",p.get("negativeCloudBuffer") is False and n.get("negativeCloudBuffer") is True)
    add("same-profile-pack-input-and-time-policy",all(p[k]==n[k] for k in
        ("profile","shader","shaderSettingsSha256","freezeClouds","freezeShaderTime")))
    for key,root in (("positive",positive),("negative",negative)):
        run=load(root/"run-result.json")
        add(key+":completed-real-runtime",run["exitCode"]==0 and run["finished"] is True
            and not run["fatalErrors"] and not run["renderThreadViolations"],runtimeErrors=run["errors"])
    rp,rn=dimensions.check(positive/"results"),dimensions.check(negative/"results")
    expected={name+":clouds-exist-in-this-dimension" for name in ("dim-fixed-day","dim-fixed-command")}
    failures={c["name"] for c in rn["checks"] if not c["passed"]}
    add("all-positive-dimensions-and-clocks-pass",rp["passed"],passedChecks=rp["passedChecks"],totalChecks=rp["totalChecks"])
    add("negative-reproduces-exact-two-fixed-day-cloud-losses",failures==expected,failedChecks=sorted(failures))
    for scene in ("dim-fixed-day","dim-fixed-command"):
        a,b=load(positive/"results"/(scene+".json")),load(negative/"results"/(scene+".json"))
        add(scene+":same-camera-policy-and-output-size",all(a[k]==b[k] for k in
            ("camera","inverseDisplayViewProjection","width","height","rasterScale","cloudWorldHeight"))
            and a["displaySky"]["phase"]==b["displaySky"]["phase"]==0)
        for stage in ("clouds","display-clouds"):
            pd,nd=[load(root/"results/cloud-diagnostics"/(scene+"-"+stage+".json")) for root in (positive,negative)]
            label=scene+":"+stage
            needed=pd["meshVertices"]*pd["meshStride"]
            add(label+":same-game-generated-cloud-vertices",pd["meshVertices"]==nd["meshVertices"]
                and pd["meshIndices"]==nd["meshIndices"] and logical_vertices(pd["meshHeadHex"],pd["meshStride"])
                ==logical_vertices(nd["meshHeadHex"],nd["meshStride"]))
            add(label+":real-program-sampler-and-canvas-are-correct",all(
                d["programExpected"]==d["programActual"] and d["samplerTexture"]==d["expectedTexture"]
                and d["drawFramebuffer"]==d["drawFramebufferAtSnapshot"] and not d["clipEnabled"]
                and d["textureWidth"]==d["textureHeight"]==256 for d in (pd,nd)))
            add(label+":owned-vao-vbo-ebo-and-bytes-match",pd["cloudBufferPath"]=="owned-raw-cloud-mesh"
                and pd["vao"]==pd["expectedVao"] and pd["elementBuffer"]==pd["expectedEbo"]
                and pd["attributes"]["Position"]["buffer"]==pd["expectedVbo"]
                and pd["vertexBytes"]==needed and pd["gpuHeadMatchesMesh"] is True,requiredBytes=needed,actualBytes=pd["vertexBytes"])
            moon=struct.unpack_from("<fff",bytes.fromhex(nd["gpuHeadHex"]))
            add(label+":legacy-cloud-attribute-aliases-celestial-quad",nd["cloudBufferPath"]=="legacy-world-VertexBuffer"
                and nd["vertexBytes"]==120 and nd["gpuHeadMatchesMesh"] is False and moon==(-20.,-100.,20.),
                expectedCloudBytes=needed,legacyVertexBytes=nd["vertexBytes"],actualFirstPosition=moon)
            suffix="-scene-"+stage+".png"; base="-scene-"+("display-base" if stage.startswith("display-") else "base")+".png"
            changes=[]
            for root in (positive,negative):
                diff=np.max(np.abs(img(root/"results"/(scene+suffix))-img(root/"results"/(scene+base))),axis=-1)
                changes.append(int((diff>2).sum()))
            add(label+":actual-pixels-prove-repair-and-negative-loss",changes[0]>32 and changes[1]==0,
                positiveChanged=changes[0],negativeChanged=changes[1])
    return dict(kind="same-jar-cloud-buffer-causal-pair",jarSha256=p["jarSha256"],
        positive=str(positive.resolve()),negative=str(negative.resolve()),checks=checks,
        passedChecks=sum(c["passed"] for c in checks),totalChecks=len(checks),passed=all(c["passed"] for c in checks),
        conclusion="Legacy cloud attributes address a 120-byte celestial quad instead of the 150528-byte game cloud mesh; the owned raw path preserves actual mesh bytes")

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--positive",type=Path,required=True); parser.add_argument("--negative",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True); args=parser.parse_args()
    result=evaluate(args.positive,args.negative)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8") as output: json.dump(result,output,indent=2,allow_nan=False)
    print(f"{result['passedChecks']}/{result['totalChecks']} {'PASS' if result['passed'] else 'FAIL'}")
    for check in result["checks"]:
        if not check["passed"]: print("FAIL",json.dumps(check))
    return 0 if result["passed"] else 1
if __name__=="__main__": raise SystemExit(main())
