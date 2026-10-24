#!/usr/bin/env python3
"""Read the existing exact-pack reference; extract no executable assets into user instances."""
from pathlib import Path
import hashlib,zipfile
ROOT=Path(__file__).resolve().parent.parent
pack=ROOT/'build/render-regression/test5-r25-practical-smoke-native/shaderpacks/iterationRP Alpha 0.8.31.zip'
assert hashlib.sha256(pack.read_bytes()).hexdigest()=='334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8'
folder=ROOT/'build/r26-itrp-source';folder.mkdir(exist_ok=False)
with zipfile.ZipFile(pack) as z:
    for name in z.namelist():
        if name.endswith(('.glsl','.fsh','.vsh','.properties')):
            text=z.read(name).decode('utf-8-sig')
            path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8')
print(folder)
