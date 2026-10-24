#!/usr/bin/env python3
"""Create a lossless same-frame comparison of shaderpack output and final sky color resolve."""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--scene", default="sky-day")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; retain historical evidence and choose a new path")
    with Image.open(args.directory / (args.scene + "-pre-resolve.png")) as source:
        before = source.convert("RGB")
    with Image.open(args.directory / (args.scene + ".png")) as source:
        after = source.convert("RGB")
    if before.size != after.size:
        parser.error("same-frame captures must have identical sizes")
    header = 108
    canvas = Image.new("RGB", (before.width * 2, before.height + header), (20, 24, 34))
    canvas.paste(before, (0, header))
    canvas.paste(after, (before.width, header))
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 27)
    draw.text((22, 10), "ITRP FSR2=2｜同一帧、同一相机：只恢复方块天空颜色，保留世界与手部", font=font,
              fill=(238, 242, 255))
    draw.text((22, 60), "恢复前：光影材质合成后的灰白天空", font=font, fill=(255, 167, 162))
    draw.text((before.width + 22, 60), "恢复后：独立蓝天与原版立体云", font=font, fill=(139, 231, 178))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(args.output)
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
