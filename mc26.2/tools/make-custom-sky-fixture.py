#!/usr/bin/env python3
"""为 Q1（自定义天空盒）造一份实机夹具：config/skyboxblock/skyboxes 下的配置 + 全景图。

生成的 2:1 等距柱状图是有明显方向的渐变（顶部深蓝、地平线橙、底部黑），
和原版天空区分度足够高，截图一眼就能看出"方块面用的是自己的全景图"。

用法：python tools/make-custom-sky-fixture.py
"""
from __future__ import annotations

import json
from pathlib import Path

from PIL import Image

MC_ROOT = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft")
GAME_DIR = MC_ROOT / "versions" / "26.2-Fabric 0.20.1"
CONFIG_DIR = GAME_DIR / "config" / "skyboxblock" / "skyboxes"

WIDTH, HEIGHT = 1024, 512


def panorama() -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT))
    pixels = image.load()
    for y in range(HEIGHT):
        v = y / (HEIGHT - 1)          # 0 = 天顶, 1 = 天底
        if v < 0.5:
            t = v / 0.5
            r = int(20 + (250 - 20) * t)
            g = int(60 + (140 - 60) * t)
            b = int(160 + (60 - 160) * t)
        else:
            t = (v - 0.5) / 0.5
            r = int(250 * (1 - t))
            g = int(140 * (1 - t))
            b = int(60 * (1 - t))
        for x in range(WIDTH):
            # 左侧加一条竖带，方便判断 u 方向
            stripe = 40 if 120 <= x < 200 else 0
            pixels[x, y] = (max(r - stripe, 0), max(g - stripe, 0), max(b - stripe, 0))
    return image


CONFIG = """\
# 由 tools/make-custom-sky-fixture.py 生成，用于 Q1 实机验证。
enable = true

gradient = {
    id = "skyboxblock:gradient"
    picture = "gradient.png"
    creative_inventory = true
    lang_zh_cn = { name = "夹具·渐变全景" }
    lang_en_us = { name = "Fixture Gradient" }
}
"""


def main() -> int:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    panorama().save(CONFIG_DIR / "gradient.png", "PNG")
    (CONFIG_DIR / "01_gradient.conf").write_text(CONFIG, encoding="utf-8")
    print(f"fixture -> {CONFIG_DIR}")
    print("files:", sorted(p.name for p in CONFIG_DIR.iterdir()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
