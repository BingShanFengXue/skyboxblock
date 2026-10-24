#!/usr/bin/env python3
"""Measure never-sky repeat variability independently; this tool does not change acceptance thresholds."""
from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
from PIL import Image, ImageFilter
ROOT=Path(__file__).resolve().parent.parent

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def img(path):
    with Image.open(path) as source: return np.asarray(source.convert("RGB"))
def expand(mask,n=2):
    for _ in range(n):
        p=np.pad(mask,1)
        mask=p[1:-1,1:-1]|p[:-2,1:-1]|p[2:,1:-1]|p[1:-1,:-2]|p[1:-1,2:]
    return mask

def hand(directory,name,meta,shape):
    low=img(directory/(name+"-post-hand-mask.png"))[...,0]>0
    ah,aw=low.shape; h,w=shape; tw,th=meta["targetViewport"][2:]
    sx,sy=meta["rasterScale"]; jx,jy=meta["rasterJitter"]
    x=np.floor((np.arange(w)+.5)/w*sx*tw+jx*.5*tw).astype(int)
    y=ah-1-np.floor((1-(np.arange(h)+.5)/h)*sy*th+jy*.5*th).astype(int)
    return low[np.clip(y,0,ah-1)[:,None],np.clip(x,0,aw-1)[None,:]]

def metrics(a,b,mask):
    delta=np.abs(a.astype(float)-b.astype(float))[mask]
    return dict(pixels=int(mask.sum()),mae=float(delta.mean()),p99=float(np.percentile(delta,99)),maximum=float(delta.max()))

def measure(root,control):
    directory=root/"results" if (root/"results").is_dir() else root
    fixture=directory.parent
    manifest=json.loads((fixture/"fixture.json").read_text(encoding="utf-8"))
    metas={p.stem:json.loads(p.read_text(encoding="utf-8")) for p in directory.glob("trans-*.json")}
    if control:
        assert manifest.get("nativeTransparencyControl") is True
        assert len(metas)==51
        for name,meta in metas.items():
            t=meta["transparency"]
            assert t.get("nativeRepeatControl") is True and t.get("skyPresent") is False
            assert t.get("serverSkyBlockCount")==0 and meta["blocks"]==meta["vertices"]==0,(name,"not genuinely never-sky")
    result=dict(directory=str(directory.resolve()),manifestSha256=digest(fixture/"fixture.json"),
        jarSha256=manifest["jarSha256"],freezeShaderTime=manifest.get("freezeShaderTime"),
        shader=manifest.get("shader"),shaderArchiveSha256=digest(fixture/"shaderpacks"/manifest["shader"]) if manifest.get("shader") else None,
        inputSettingsSha256=manifest.get("shaderSettingsSha256"),
        captureJitterBits=manifest.get("captureJitterBits"),pairs={})
    for front in ("none","glass","water"):
        first="trans-open-"+front+"-none-marker"; last="trans-removed-"+front
        a,b=metas[first],metas[last]
        assert all(a[key]==b[key] for key in ("camera","inverseDisplayViewProjection","width","height","rasterScale","rasterJitter"))
        ia,ib=img(directory/(first+".png")),img(directory/(last+".png"))
        mask=~expand(hand(directory,first,a,ia.shape[:2])|hand(directory,last,b,ib.shape[:2]))
        radius=max(1,round(4/min(a["rasterScale"])))
        ba=np.asarray(Image.fromarray(ia).filter(ImageFilter.GaussianBlur(radius)))
        bb=np.asarray(Image.fromarray(ib).filter(ImageFilter.GaussianBlur(radius)))
        result["pairs"][front]=dict(raw=metrics(ia,ib,mask),lowpass=metrics(ba,bb,mask),radiusDisplay=radius,
            firstCounter=a.get("irisFrameCounter"),lastCounter=b.get("irisFrameCounter"),
            firstFrames=a.get("sceneRenderFrames"),lastFrames=b.get("sceneRenderFrames"),
            firstPngSha256=digest(directory/(first+".png")),lastPngSha256=digest(directory/(last+".png")))
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control",type=Path,action="append",required=True)
    parser.add_argument("--candidate",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    assert len(args.control)>=2,"At least two independent never-sky runs are required"
    controls=[measure(p.resolve(),True) for p in args.control]
    candidate=measure(args.candidate.resolve(),False) if args.candidate else None
    assert all(c["freezeShaderTime"] is True for c in controls)
    fields=("jarSha256","captureJitterBits","shaderArchiveSha256","inputSettingsSha256")
    assert len({tuple(c[k] for k in fields) for c in controls})==1
    if candidate: assert tuple(candidate[k] for k in fields)==tuple(controls[0][k] for k in fields)
    report=dict(note="Measurement only; no acceptance threshold was changed",controls=controls,candidate=candidate)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8") as output: json.dump(report,output,indent=2,allow_nan=False)
    for i,c in enumerate(controls): print("CONTROL",i+1,{k:v["raw"] for k,v in c["pairs"].items()})
    if candidate: print("CANDIDATE",{k:v["raw"] for k,v in candidate["pairs"].items()})
    print(args.output.resolve())
    return 0
if __name__=="__main__": raise SystemExit(main())
