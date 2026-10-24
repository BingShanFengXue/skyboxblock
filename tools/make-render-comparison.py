#!/usr/bin/env python3
"""Create labelled, lossless before/after evidence from regression captures."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 28)
CAPTURES = ROOT / "build" / "render-regression"
OUTPUT = ROOT / "docs" / "screenshots"


def comparison(scene: str, title: str, before_caption: str, after_caption: str) -> Path:
    with Image.open(CAPTURES / "baseline-vanilla" / "results" / (scene + ".png")) as source:
        before = source.convert("RGB")
    with Image.open(CAPTURES / "fixed1-vanilla" / "results" / (scene + ".png")) as source:
        after = source.convert("RGB")
    canvas = Image.new("RGB", (before.width + after.width, max(before.height, after.height) + 104), (20, 24, 34))
    canvas.paste(before, (0, 104))
    canvas.paste(after, (before.width, 104))
    draw = ImageDraw.Draw(canvas)
    draw.text((22, 10), title, font=FONT, fill=(238, 242, 255))
    draw.text((22, 57), before_caption, font=FONT, fill=(255, 167, 162))
    draw.text((before.width + 22, 57), after_caption, font=FONT, fill=(139, 231, 178))
    output = OUTPUT / ("regression-20261018-" + scene + "-before-after.png")
    canvas.save(output)
    print(output)
    return output


if __name__ == "__main__":
    comparison("longitude-seam", "经度接缝：同一场景、同一相机、独立客户端截图",
               "修复前：平均 RGB 误差 5.13815", "修复后：平均 RGB 误差 0.00042")
    comparison("live-night", "实时天空：夜晚场景对照（原版 Fabric）",
               "修复前：仍显示白天烘焙缓存", "修复后：跟随当前夜空，背景对齐误差 0")
