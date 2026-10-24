#!/usr/bin/env python3
"""Derived-image negative tests: prove the oracle detects displacement with all metadata unchanged.

These are explicitly synthetic controls, not additional Minecraft runtime runs.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("position_oracle", ROOT / "tools/check-alignment-regression.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=False)
    checks = []
    for kind in ("line-shift-20-raster-px", "item-image-shift-60-display-px"):
        target = args.destination / kind
        target.mkdir()
        for path in args.source.glob("align-*"):
            # Preserve only required independent canvas evidence, not every debug PNG.
            # Deliberate transformations below still affect ONLY line mask or final PNG.
            if (path.suffix == ".json" or path.name.endswith(("-line-mask-full.png", "-scene-display-clouds.png", "-scene-clouds.png"))
                    or path.name in {name + ".png" for name in oracle.NAMES}):
                shutil.copy2(path, target / path.name)
        if kind.startswith("line"):
            for path in target.glob("*-line-mask-full.png"):
                with Image.open(path) as image:
                    pixels = np.array(image.convert("RGB"))
                shifted = np.zeros_like(pixels)
                shifted[:, 20:] = pixels[:, :-20]
                Image.fromarray(shifted).save(path)
        else:
            for name in oracle.NAMES - {"align-clean"}:
                path = target / (name + ".png")
                with Image.open(path) as image:
                    pixels = np.array(image.convert("RGB"))
                shifted = np.zeros_like(pixels)
                shifted[:, 60:] = pixels[:, :-60]
                Image.fromarray(shifted).save(path)
        result = oracle.check(target)
        failed = [c for c in result["checks"] if not c["passed"]]
        wanted = ":line-pixels-at-correct-projected-location" if kind.startswith("line") else "-at-correct-projected-location"
        expected_count = 8 if kind.startswith("line") else 16
        actual = [c for c in failed if c["name"].endswith(wanted)
                  and (kind.startswith("line") or ":item-" in c["name"])]
        state = [c for c in result["checks"] if "iris-logical" in c["name"] or "iris-world" in c["name"]]
        identical = all(hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256((target/path.name).read_bytes()).digest()
                        for path in args.source.glob("align-*.json"))
        checks.append({"name": kind, "passed": not result["passed"] and len(actual) == expected_count
                       and all(c["passed"] for c in state) and identical, "expectedPositionFailures": expected_count,
                       "actualPositionFailures": len(actual), "allLogicalStateChecksStillPass": all(c["passed"] for c in state),
                       "metadataByteIdentical": identical})
    output = {"source": str(args.source.resolve()), "syntheticDerivedImageControls": True,
              "checks": checks, "passed": all(c["passed"] for c in checks)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, indent=2))
    return 0 if output["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
