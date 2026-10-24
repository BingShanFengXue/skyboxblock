#!/usr/bin/env python3
"""生成 skyboxblock 模组的方块贴图与 mod 图标（16x16 像素风）。

用法：python tools/gen-skybox-textures.py
输出：src/main/resources/assets/skyboxblock/textures/block/*.png 与 icon.png
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "skyboxblock"
TEX = ASSETS / "textures" / "block"

SKY_TOP = (74, 144, 217)
SKY_BOTTOM = (176, 215, 240)
FRAME_SKY = (32, 74, 120)
FRAME_SKY_LIGHT = (94, 156, 214)
CLOUD = (247, 251, 255)
CLOUD_SHADE = (214, 231, 244)
SUN = (255, 244, 189)

VOID_TOP = (26, 16, 46)
VOID_BOTTOM = (8, 5, 16)
FRAME_VOID = (74, 52, 120)
FRAME_VOID_LIGHT = (120, 92, 178)
STAR = (206, 196, 255)


def lerp(a: int, b: int, t: float) -> int:
    return int(round(a + (b - a) * t))


def blend(base: tuple[int, int, int], over: tuple[int, int, int], alpha: float) -> tuple[int, int, int]:
    return (
        lerp(base[0], over[0], alpha),
        lerp(base[1], over[1], alpha),
        lerp(base[2], over[2], alpha),
    )


def sky_texture(size: int = 16) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()

    # 内部渐变
    for y in range(size):
        t = y / (size - 1)
        base = (lerp(SKY_TOP[0], SKY_BOTTOM[0], t),
                lerp(SKY_TOP[1], SKY_BOTTOM[1], t),
                lerp(SKY_TOP[2], SKY_BOTTOM[2], t))
        for x in range(size):
            px[x, y] = (*base, 255)

    # 几朵像素云（避开边框）
    clouds = [
        [(4, 5), (5, 5), (6, 5), (5, 4), (4, 6), (5, 6)],
        [(10, 8), (11, 8), (12, 8), (11, 7), (10, 9), (11, 9), (12, 9)],
        [(3, 11), (4, 11), (5, 11), (4, 11)],
    ]
    for cloud in clouds:
        for (x, y) in cloud:
            if 1 <= x < size - 1 and 1 <= y < size - 1:
                px[x, y] = (*CLOUD, 255)
        # 云底阴影
        for (x, y) in cloud:
            yy = y + 1
            if 1 <= x < size - 1 and 1 <= yy < size - 1 and px[x, yy][:3] != CLOUD:
                px[x, yy] = (*CLOUD_SHADE, 255)

    # 右上角小太阳
    px[size - 4, 3] = (*SUN, 255)
    px[size - 5, 3] = (*blend(SUN, CLOUD, 0.35), 255)
    px[size - 4, 4] = (*blend(SUN, CLOUD, 0.35), 255)

    # 双层边框：外深内浅，像一块"玻璃盒子"
    for i in range(size):
        px[i, 0] = (*FRAME_SKY, 255)
        px[i, size - 1] = (*FRAME_SKY, 255)
        px[0, i] = (*FRAME_SKY, 255)
        px[size - 1, i] = (*FRAME_SKY, 255)
    for i in range(1, size - 1):
        px[i, 1] = (*FRAME_SKY_LIGHT, 255) if i % 2 == 0 else (*FRAME_SKY, 255)
    for i in range(1, size - 1):
        px[1, i] = (*FRAME_SKY_LIGHT, 255) if i % 2 == 0 else (*FRAME_SKY, 255)

    return img


def void_texture(size: int = 16) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()

    for y in range(size):
        t = y / (size - 1)
        base = (lerp(VOID_TOP[0], VOID_BOTTOM[0], t),
                lerp(VOID_TOP[1], VOID_BOTTOM[1], t),
                lerp(VOID_TOP[2], VOID_BOTTOM[2], t))
        for x in range(size):
            # 一点点噪声，避免纯色死板
            n = (math.sin(x * 2.3 + y * 1.7) * 0.5 + 0.5) * 6
            px[x, y] = (min(255, base[0] + int(n)), min(255, base[1] + int(n)), min(255, base[2] + int(n)), 255)

    stars = [(3, 3), (7, 2), (12, 4), (5, 8), (10, 7), (13, 11), (4, 12), (8, 13)]
    for (x, y) in stars:
        px[x, y] = (*STAR, 255)

    for i in range(size):
        px[i, 0] = (*FRAME_VOID, 255)
        px[i, size - 1] = (*FRAME_VOID, 255)
        px[0, i] = (*FRAME_VOID, 255)
        px[size - 1, i] = (*FRAME_VOID, 255)
    for i in range(1, size - 1, 2):
        px[i, 1] = (*FRAME_VOID_LIGHT, 255)
        px[1, i] = (*FRAME_VOID_LIGHT, 255)

    return img


def main() -> None:
    TEX.mkdir(parents=True, exist_ok=True)
    sky = sky_texture()
    void = void_texture()
    sky.save(TEX / "sky_block.png")
    void.save(TEX / "void_block.png")
    sky.resize((128, 128), Image.NEAREST).save(ASSETS / "icon.png")
    print("wrote", TEX / "sky_block.png")
    print("wrote", TEX / "void_block.png")
    print("wrote", ASSETS / "icon.png")


if __name__ == "__main__":
    main()
