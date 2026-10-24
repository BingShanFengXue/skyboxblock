#!/usr/bin/env python3
"""Print exact float32 jitter bits from a real captured frame, without rounding guesses."""
import argparse
import json
from pathlib import Path
import struct
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("capture",type=Path)
args=parser.parse_args()
meta=json.loads(args.capture.read_text(encoding="utf-8"))
print(":".join(f"{struct.unpack('>I',struct.pack('>f',float(value)))[0]:08x}" for value in meta["rasterJitter"]))
