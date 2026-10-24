#!/usr/bin/env python3
"""Pair two real runtime captures; clearly label that these are separate fixture runs, not one frame."""
from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--before-label", default="负对照：不恢复 Iris 逻辑状态")
    parser.add_argument("--after-label", default="test.4：恢复状态，选框与掉落物回到正确位置")
    parser.add_argument("--caption", default="同一 jar、相同相机与 FSR2_SCALE=2；两次独立实机运行，掉落物旋转 / 浮动相位可不同。")
    args = parser.parse_args()
    before, after = Image.open(args.before).convert("RGB"), Image.open(args.after).convert("RGB")
    if before.size != after.size:
        raise ValueError("Capture dimensions must match; comparisons are never rescaled")
    width, height = before.size
    canvas = Image.new("RGB", (width * 2, height + 130), (24, 29, 39))
    canvas.paste(before, (0, 82))
    canvas.paste(after, (width, 82))
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 32)
    small = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 23)
    draw.text((24, 20), args.before_label, font=font, fill=(255, 143, 143))
    draw.text((width + 24, 20), args.after_label, font=font, fill=(133, 255, 173))
    draw.text((24, height + 94), args.caption,
              font=small, fill=(221, 227, 239))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(args.output)
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
