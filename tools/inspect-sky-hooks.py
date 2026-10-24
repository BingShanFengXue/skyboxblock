#!/usr/bin/env python3
"""Inspect the actual packaged Mixin configs/refmaps, without assuming Loom remapping mode."""
from pathlib import Path
import json
import zipfile
ROOT=Path(__file__).resolve().parent.parent
for version in ("1.0.2-test.4","1.0.2-test.5"):
    path=ROOT/"build/libs"/("skyboxblock-"+version+".jar")
    if not path.exists(): continue
    print("===",version,"===")
    with zipfile.ZipFile(path) as archive:
        config=json.loads(archive.read("skyboxblock.mixins.json"))
        print("PACKAGED CONFIG",json.dumps(config,indent=2))
        for name in archive.namelist():
            if "refmap" in name and name.endswith(".json"):
                data=json.loads(archive.read(name))
                print("REFMAP",name)
                print(json.dumps(data.get("mappings",{}).get("dev/yg/skyboxblock/mixin/GameRendererMixin",{}),indent=2))
                print(json.dumps(data.get("mappings",{}).get("dev.yg.skyboxblock.mixin.GameRendererMixin",{}),indent=2))
                print("MAPPING CLASSES",list(data.get("mappings",{})))
