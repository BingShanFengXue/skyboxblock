#!/usr/bin/env python3
"""Compare this project's actual shipped sources/assets with the two local reference trees.

Exact byte/pixel matches are facts; similar boilerplate alone is not proof of copying.
This script never edits source/assets or invokes a texture generator's main().
"""
from __future__ import annotations
import argparse
import difflib
import hashlib
import importlib.util
import json
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parent.parent
REFERENCES={"literal-sky-block":ROOT.parent/"reference/literal-sky-block", "shadered":ROOT.parent/"reference/shadered"}

def sha(data): return hashlib.sha256(data).hexdigest()
def pixel(path):
    with Image.open(path) as image:
        rgba=image.convert("RGBA")
        return rgba.size,sha(rgba.tobytes())
def lines(path):
    return [s.strip() for s in path.read_text(encoding="utf-8-sig").splitlines()
            if s.strip() and not s.lstrip().startswith(("package ","import ","//","*","/*"))]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    project=[p for p in (ROOT/"src/main").rglob("*") if p.is_file()]
    report={"note":"Exact matches and long common line blocks only; human provenance review remains required", "references":{},"generatedAssets":{}}
    for name,directory in REFERENCES.items():
        candidates=[p for p in (directory/"src").rglob("*") if p.is_file()]
        byte_index={}; image_index={}
        for p in candidates:
            byte_index.setdefault(sha(p.read_bytes()),[]).append(p)
            if p.suffix.lower()==".png": image_index.setdefault(pixel(p),[]).append(p)
        exact=[]; pixels=[]; common=[]
        upstream_java=[p for p in candidates if p.suffix==".java"]
        for p in project:
            for other in byte_index.get(sha(p.read_bytes()),[]): exact.append({"project":p.relative_to(ROOT).as_posix(),"reference":other.relative_to(directory).as_posix()})
            if p.suffix.lower()==".png":
                for other in image_index.get(pixel(p),[]): pixels.append({"project":p.relative_to(ROOT).as_posix(),"reference":other.relative_to(directory).as_posix()})
            if p.suffix==".java":
                own=lines(p)
                for other in upstream_java:
                    their=lines(other)
                    blocks=[b for b in difflib.SequenceMatcher(None,own,their,autojunk=False).get_matching_blocks()
                            if b.size>=4 and sum(len(s) for s in own[b.a:b.a+b.size])>=160]
                    if blocks: common.append({"project":p.relative_to(ROOT).as_posix(),"reference":other.relative_to(directory).as_posix(),
                        "blocks":[{"lines":b.size,"characters":sum(len(s) for s in own[b.a:b.a+b.size]),"content":own[b.a:b.a+b.size]} for b in blocks]})
        report["references"][name]={"localRoot":str(directory),"files":len(candidates),"exactByteMatches":exact,"pixelMatches":pixels,"longCommonCodeBlocks":common}
    spec=importlib.util.spec_from_file_location("own_texture_generator",ROOT/"tools/gen-skybox-textures.py")
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    sky=module.sky_texture().convert("RGBA")
    for name,expected in (("textures/block/sky_block.png",sky),("icon.png",sky.resize((128,128),Image.Resampling.NEAREST))):
        path=ROOT/"src/main/resources/assets/skyboxblock"/name
        actual_size,actual_hash=pixel(path)
        report["generatedAssets"][name]={"matchesOwnGenerator":actual_size==expected.size and actual_hash==sha(expected.tobytes()),
            "actualPixelSha256":actual_hash,"source":"tools/gen-skybox-textures.py"}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8") as stream: json.dump(report,stream,indent=2,ensure_ascii=False)
    for name,value in report["references"].items(): print(name,"exact",len(value["exactByteMatches"]),"pixel",len(value["pixelMatches"]),"long-code",len(value["longCommonCodeBlocks"]))
    print("own generator:",{k:v["matchesOwnGenerator"] for k,v in report["generatedAssets"].items()})
    print(args.output.resolve())
    return 0
if __name__=="__main__": raise SystemExit(main())
