#!/usr/bin/env python3
"""Detector controls plus optional retained real gray/edge-on alignment images.

python -B tools/check-alignment-regression-selftest.py --real-results RESULTS ...
All synthetic actual locations come from pixels; item metadata never defines a
search crop. Existing test-position-oracle.py supplies full unchanged-JSON shifts.
"""
from __future__ import annotations

import argparse
import copy
import importlib.util
import itertools
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("alignment_oracle",Path(__file__).with_name("check-alignment-regression.py"))
o = importlib.util.module_from_spec(spec); spec.loader.exec_module(o)


def metadata():
    p=np.array([[.9,0,0,0],[0,1.4,0,0],[0,0,1.02,-.202],[0,0,1,0.]])
    return dict(width=640,height=400,camera=[.5,141.62,-1.5],
                inverseDisplayViewProjection=np.linalg.inv(p).T.ravel().tolist(),
                alignment=dict(items=[dict(position=[-999,0,0]),dict(position=[999,0,0])]))


def mock_frame(left="gray",right="edge",objects=True):
    h,w=400,640
    yy,xx=np.indices((h,w))
    reference=np.stack((100+yy*.10,160+yy*.05,np.full_like(yy,235)),axis=-1).astype(np.uint8)
    picture=Image.fromarray(reference); draw=ImageDraw.Draw(picture)
    # Large ordinary geometry, an outline, and a hand: none is an emerald candidate.
    draw.rectangle((280,150,380,265),fill=(65,70,75))
    draw.rectangle((275,180,365,260),outline=(250,250,250),width=2)
    draw.rectangle((510,280,639,399),fill=(180,130,100))
    boxes=[]
    if objects:
        for cx,cy,mode in ((230,220,left),(430,220,right)):
            half=3 if mode=="edge" else 22
            color=(35,210,80) if mode=="green" else (55,83,99)
            vertices=[(cx,cy-24),(cx+half,cy-16),(cx+half,cy+16),
                      (cx,cy+24),(cx-half,cy+16),(cx-half,cy-16)]
            draw.polygon(vertices,fill=color)
            if mode=="green-edge":
                draw.rectangle((cx+17,cy-13,cx+20,cy+13),fill=(35,210,80))
                draw.rectangle((cx+8,cy-21,cx+10,cy-18),fill=(35,210,80))
            boxes.append([[cx-half,cy-24],[cx+half,cy+24]])
    return np.asarray(picture).copy(),reference,boxes


def global_match(candidates,expected):
    # Independent reproduction of the unchanged two-entity one-to-one policy.
    choices=list(candidates)+[None]*len(expected)
    indices=min(itertools.permutations(range(len(choices)),len(expected)),key=lambda indices:sum(
        np.linalg.norm(np.asarray(choices[j]["center"])-point) if choices[j] else 1e8
        for point,j in zip(expected,indices)))
    return [choices[j] for j in indices]


def shift(array,dx):
    out=np.zeros_like(array); out[:,dx:]=array[:,:-dx]; return out


def run(real_results):
    cases=[]
    def require(name,condition,detail=""):
        cases.append(bool(condition)); print(("PASS " if condition else "FAIL ")+name+(": "+detail if detail else ""),flush=True)
    meta=metadata(); expected=[np.array([230.5,220.5]),np.array([430.5,220.5])]
    for left,right,label in (("green","green","green"),("gray","gray","gray"),
                             ("gray","edge","edge-on"),("green-edge","green","narrow-green-edge")):
        final,reference,boxes=mock_frame(left,right)
        candidates,details=o.item_candidates(meta,"align-sky-selected",final,reference)
        chosen=global_match(candidates,expected)
        errors=[np.linalg.norm(np.asarray(c["center"])-p) if c else np.inf for c,p in zip(chosen,expected)]
        require(label+":two-complete-actual-bodies",len(candidates)==2 and max(errors)<=1.,json.dumps(candidates))
        require(label+":bbox-is-full-silhouette",all(c["bounds"]==box for c,box in zip(chosen,boxes)))
        require(label+":whole-sky-not-expected-center-search",details["skySearchPixels"]>10000
                and details["searchUsesExpectedItemCenters"] is False)
    final,reference,_=mock_frame("gray","edge")
    original,_=o.item_candidates(meta,"align-sky-selected",final,reference)
    altered=copy.deepcopy(meta); altered["alignment"]["items"]=[dict(position=[0,10000,10000])]
    actual,_=o.item_candidates(altered,"align-sky-selected",final,reference)
    require("detector-independent-of-expected-item-metadata",[c["bounds"] for c in actual]==[c["bounds"] for c in original])
    without,reference,_=mock_frame(objects=False)
    candidates,_=o.item_candidates(meta,"align-sky-selected",without,reference)
    require("solid-cube-outline-and-hand-not-accepted-as-items",not candidates)
    green,reference,_=mock_frame("green","green")
    candidates,details=o.item_candidates(meta,"align-solid-no-sky",green,None)
    require("no-sky-baseline-keeps-global-original-image-features",len(candidates)==2 and not details["referenceUsed"])
    gray,reference,_=mock_frame("gray","gray")
    candidates,_=o.item_candidates(meta,"align-solid-no-sky",gray,None)
    require("missing-color-independent-evidence-never-invents-coordinates",not candidates)
    final,reference,boxes=mock_frame("gray","green-edge")
    missing=final.copy(); (x0,y0),(x1,y1)=boxes[0];missing[y0:y1+1,x0:x1+1]=reference[y0:y1+1,x0:x1+1]
    candidates,_=o.item_candidates(meta,"align-sky-selected",missing,reference)
    assigned=global_match(candidates,expected)
    require("truly-missing-item-is-null-not-other-item-fragment",len(candidates)==1 and assigned[0] is None and assigned[1] is not None)
    for move_reference in (False,True):
        shifted=shift(final,60); shifted_reference=shift(reference,60) if move_reference else reference
        candidates,_=o.item_candidates(meta,"align-sky-selected",shifted,shifted_reference)
        assigned=global_match(candidates,expected)
        errors=[np.linalg.norm(np.asarray(c["center"])-p) if c else np.inf for c,p in zip(assigned,expected)]
        tolerance=max(10.,meta["width"]*.018)
        require("whole-final"+("-and-background" if move_reference else "")+"-shift-still-position-fails",
                all(e>tolerance for e in errors),str(errors))
    for root in real_results:
        try:
            report=o.check(root)
            item_checks=[c for c in report["checks"] if ":item-" in c["name"]]
            require(str(root)+":actual-retained-images-all-pass",report["passed"] and len(item_checks)==16,
                    str([c["name"] for c in report["checks"] if not c["passed"]]))
            for c in item_checks:
                scene=c["name"].split(":")[0]
                m=json.loads((root/(scene+".json")).read_text(encoding="utf-8"))
                require(scene+":"+c["name"].split(":")[1]+":original-position-tolerance",
                        abs(c["tolerancePixels"]-round(max(10.,m["width"]*.018),3))<1e-9)
                if scene in ("align-reload","align-sky-selected"):
                    print("REAL-ITEM",json.dumps(c,allow_nan=False),flush=True)
            require(str(root)+":line-tolerance-still-3-raster-px",all(c.get("toleranceRasterPixels")==3
                    for c in report["checks"] if c["name"].endswith(":all-primary-face-outline-edges-covered")))
        except (OSError,ValueError,KeyError) as error:
            require(str(root)+":actual-retained-images-all-pass",False,str(error))
    print(f"{sum(cases)}/{len(cases)} alignment detector controls passed")
    return 0 if all(cases) else 1


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--real-results",nargs="*",type=Path,default=[])
    raise SystemExit(run(parser.parse_args().real_results))
