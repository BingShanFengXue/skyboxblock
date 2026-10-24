#!/usr/bin/env python3
"""Display-only before/after panels and a measured exposure chart; native captures remain untouched."""
from pathlib import Path
import json
import math
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "build/reports"
font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 22)
small = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 17)
bold = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 25)
read = lambda p: json.loads(p.read_text(encoding="utf-8"))
rows = [("Native", "r28-hand-baseline-native-r1", "r28-hand-fixed-native-r3"),
        ("FSR2", "r28-hand-baseline-fsr-r3", "r28-hand-fixed-fsr-r3")]
canvas = Image.new("RGB", (1280, 946), "#151b25")
d = ImageDraw.Draw(canvas)
d.text((12, 8), "ENCLOSED SKY ROOM / DAY / BLOOM AND AUTO EXPOSURE ON", font=bold, fill="white")
for row, (environment, before, after) in enumerate(rows):
    y = 84 + row * 430
    for column, (label, title) in enumerate(((before, "BEFORE"), (after, "AFTER"))):
        directory = ROOT / "build/render-regression" / label / "results"
        scene = "hand-inside-30"
        meta = read(directory / (scene + ".json"))["handExposure"]
        meter = meta["exposure"]["averageLuminance"]
        title = f"{environment} / {title} / {meta['elapsedSinceTransitionMilliseconds']/1000:.1f}s / meter {meter:.3g}"
        d.text((column * 640 + 12, y - 28), title, font=font, fill="white")
        with Image.open(directory / (scene + ".png")) as source:
            canvas.paste(source.convert("RGB").resize((640, 400), Image.Resampling.LANCZOS), (column * 640, y))
d.text((12, 924), "Original 1280x800 captures; resize is for display only. The hand is selected by actual depth, never by color.", font=small, fill="#c8d0dd")
path = OUT / "r28-hand-before-after.png"
if path.exists():
    raise SystemExit("Evidence already exists")
canvas.save(path)
print(path)

chart = Image.new("RGB", (1280, 770), "#151b25")
d = ImageDraw.Draw(chart)
d.text((20, 14), "MEASURED SCENE LUMINANCE: ENTRY -> 30s STAY", font=bold, fill="white")
d.text((20, 50), "Owner-verified ITRP pixelData2D.r, logarithmic Y axis. Half-float limit: 65504.", font=small, fill="#c8d0dd")
x0, x1, y0, y1 = 105, 1230, 104, 630
X = lambda t: x0 + max(0, min(t, 35)) / 35 * (x1 - x0)
Y = lambda v: y1 - max(0, min(math.log10(max(v, 1)), 5)) / 5 * (y1 - y0)
for value in (1, 10, 100, 1000, 10000, 65504):
    y = Y(value)
    d.line((x0, y, x1, y), fill="#374252", width=1)
    d.text((12, y - 10), str(value), font=small, fill="#c8d0dd")
for t in range(0, 36, 5):
    x = X(t)
    d.line((x, y0, x, y1), fill="#273140", width=1)
    d.text((x - 6, y1 + 10), str(t), font=small, fill="#c8d0dd")
series = [("Legacy/native", "r28-hand-baseline-native-r1-oracle-v2", "#ff6b6b"),
          ("Fixed/native", "r28-hand-fixed-native-r3-oracle-v3", "#58c4ff"),
          ("Fixed/FSR2", "r28-hand-fixed-fsr-r3", "#ffd166"),
          ("Glass control", "r28-hand-glass-control-native-r1", "#64df9b")]
for index, (label, report, color) in enumerate(series):
    records = read(OUT / (report + ".json"))["measurements"]
    selected = [r for r in records if r["scene"] == "hand-enter-00" or r["scene"].startswith("hand-inside-")]
    points = [(X(r["millisecondsSinceTransition"] / 1000), Y(r["averageLuminance"])) for r in selected]
    d.line(points, fill=color, width=4)
    for x, y in points:
        d.ellipse((x - 4, y - 4, x + 4, y + 4), fill=color)
    x = 20 + index * 310
    d.rectangle((x, 695, x + 24, 707), fill=color)
    d.text((x + 32, 686), label, font=font, fill="white")
d.text((500, 653), "Observed seconds since entry", font=small, fill="#c8d0dd")
d.text((20, 737), "No exposure clamp, bloom toggle, skin replacement or world-color readback is used by the production fix.", font=small, fill="#c8d0dd")
path = OUT / "r28-exposure-timeline.png"
if path.exists():
    raise SystemExit("Evidence already exists")
chart.save(path)
print(path)
