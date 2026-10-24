#!/usr/bin/env python3
"""Display-only contact sheets; numerical measurements always use untouched native images."""
from pathlib import Path
import argparse
import importlib.util
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--label", default="r3")
p.add_argument("--output-tag", default=None)
a = p.parse_args()
tag = a.output_tag or a.label
old = ROOT / "build/render-regression/r27-ao-baseline-vanilla-r2/results"
new = ROOT / f"build/render-regression/r27-ao-fixed-vanilla-{a.label}/results"
out = ROOT / "build/reports"
font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 22)
small = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 17)
canvas = Image.new("RGB", (1920, 914), "#151b25")
draw = ImageDraw.Draw(canvas)
columns = [(old, "sky", "BEFORE: legacy AO 0.2"), (new, "sky", "AFTER: neutral AO 1.0"),
           (new, "air", "AIR reference: same physical surfaces")]
for row, time in enumerate(("day", "night")):
    y = 50 + row * 432
    for column, (directory, material, title) in enumerate(columns):
        name = f"ao-corner-{time}-on-{material}"
        with Image.open(directory / (name + ".png")) as image:
            canvas.paste(image.convert("RGB").resize((640, 400), Image.Resampling.LANCZOS), (column * 640, y))
        draw.text((column * 640 + 12, y - 28), time.upper() + " / " + title, font=font, fill="white")
draw.text((12, 886), "Same pose, gamma, geometry and skylight. Smooth lighting ON. Scaling for display only; metrics use original 1280x800 captures.", font=small, fill="#c5cedd")
path = out / f"r27-ao-before-after-{tag}.png"
if path.exists():
    raise SystemExit("Evidence destination already exists")
canvas.save(path)
print(path)

spec = importlib.util.spec_from_file_location("ao", ROOT / "tools/check-ambient-occlusion-r27.py")
ao = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ao)
heat = Image.new("RGB", (1280, 900), "#151b25")
d = ImageDraw.Draw(heat)
for column, (directory, title) in enumerate(((old, "BEFORE: terrain difference x4"), (new, "AFTER: terrain difference x4"))):
    base = "ao-corner-day-on"
    meta = json.loads((directory / (base + "-air.json")).read_text(encoding="utf-8"))
    masks = ao.masks(meta)
    mask = np.logical_or.reduce([m for name, m in masks.items() if not name.endswith("ContactBand")])
    reference = ao.image(directory / (base + "-air.png"))
    sky = ao.image(directory / (base + "-sky.png"))
    difference = np.clip(np.abs(reference - sky) * 4, 0, 255)
    difference[~mask] = 0
    native = Image.fromarray(difference.astype("uint8"))
    heat.paste(native.resize((640, 400), Image.Resampling.NEAREST), (column * 640, 38))
    d.text((column * 640 + 12, 8), title, font=font, fill="white")
    original = Image.open(directory / (base + "-sky.png")).convert("RGB")
    heat.paste(original.resize((640, 400), Image.Resampling.LANCZOS), (column * 640, 454))
d.text((12, 872), "Only independently ray-selected real floor/walls/ceiling; no hue masks or changed-pixel exclusions.", font=small, fill="#c5cedd")
path = out / f"r27-ao-terrain-difference-{tag}.png"
if path.exists():
    raise SystemExit("Evidence destination already exists")
heat.save(path)
print(path)
