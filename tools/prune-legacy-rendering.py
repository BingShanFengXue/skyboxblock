#!/usr/bin/env python3
"""Explicitly remove the archived screen-capture/preset implementation. Dry-run by default."""
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parent.parent
SOURCES = "src/main/java/dev/yg/skyboxblock/"
RESOURCE = "src/main/resources/"
FILES = [SOURCES + name for name in (
    "SkyStyle.java", "client/AutoTest.java", "client/SkyProbe.java", "client/SkyProbeRenderType.java",
    "client/SkyGeometry.java", "client/SkyDeferredRenderer.java")]
for prefix in ("sky_probe", "sky_panorama", "style_probe", "void_probe"):
    FILES += [RESOURCE + "assets/skyboxblock/shaders/core/" + prefix + "." + ext for ext in ("json", "vsh", "fsh")]
for style in ("void", "space", "ocean", "forest", "stormy", "end", "aurora"):
    block = "void_block" if style == "void" else style + "_sky_block"
    FILES += [RESOURCE + part.format(block=block) for part in (
        "assets/skyboxblock/blockstates/{block}.json", "assets/skyboxblock/models/block/{block}.json",
        "assets/skyboxblock/models/item/{block}.json", "assets/skyboxblock/textures/block/{block}.png",
        "data/skyboxblock/recipe/{block}.json", "data/skyboxblock/loot_table/blocks/{block}.json")]
    FILES.append(RESOURCE + "assets/skyboxblock/textures/sky/" + style + ".png")
FILES.append("tools/gen-sky-styles.py")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    targets = []
    for relative in FILES:
        target = (ROOT / relative).resolve()
        if not target.is_relative_to(ROOT.resolve()): raise RuntimeError("Unexpected target: " + str(target))
        if target.exists():
            if not target.is_file(): raise RuntimeError("Not a regular file: " + str(target))
            targets.append(target)
    manifest = ROOT / "build" / "vanilla-rewrite-delete-manifest.txt"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text("\n".join(str(p) for p in targets) + "\n", encoding="utf-8")
    print("Verified project root:", ROOT.resolve())
    print("Verified exact file targets:", len(targets))
    print("Manifest:", manifest)
    if args.apply:
        for target in targets:
            print("DELETE", target)
            target.unlink()
        print("Removed", len(targets), "legacy files; no archive or user-instance path was touched")
