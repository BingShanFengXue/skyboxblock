"""生成 HDR 支持性测试素材（本轮只测试，不改动任何模组源码）。

素材矩阵的设计目标：把「扩展名闸门」和「位深闸门」分开验证。

- 扩展名闸门：SkyConfigParser.pictureProblem() 只放行 .png
- 解码闸门：CustomSkyRegistry.materialise() 使用 ImageIO.read()
- 位深闸门：materialise() 输出 BufferedImage(TYPE_INT_ARGB)，再 ImageIO.write(PNG)
"""

import math
import shutil
import struct
import zlib
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT.parent / "skyboxblock素材" / "04-定稿"
OUT = ROOT / "materials"
OUT.mkdir(parents=True, exist_ok=True)

W, H = 2048, 1024


def sky_like_linear(width=W, height=H):
    """等距柱状天空的线性光照：地面暗、天顶亮、太阳核心远大于 1.0。"""
    v = np.linspace(0.0, 1.0, height, dtype=np.float64)[:, None]
    u = np.linspace(0.0, 1.0, width, dtype=np.float64)[None, :]
    base = 0.06 + 0.72 * np.clip(1.0 - v, 0.0, 1.0) ** 1.4
    ripple = 0.05 * np.sin(u * math.tau * 3.0) * (1.0 - v)
    d2 = ((u - 0.31) * 2.2) ** 2 + (v - 0.38) ** 2
    sun = 1.6 * np.exp(-d2 / 0.0009)
    lum = base + ripple + sun
    return np.stack([lum * 1.00, lum * 0.97, lum * 0.86], axis=-1)


def png_chunk(tag: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + tag + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))


def write_png16_rgb(path, arr16):
    """Pillow 不能直接写 16bit RGB PNG，手写一个：bit depth 16, color type 2。"""
    h, w, _ = arr16.shape
    raw = b"".join(b"\x00" + arr16[y].astype(">u2").tobytes() for y in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 16, 2, 0, 0, 0)
    data = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr)
            + png_chunk(b"IDAT", zlib.compress(raw, 9)) + png_chunk(b"IEND", b""))
    path.write_bytes(data)


def main():
    for stale in OUT.iterdir():
        if stale.is_file():
            stale.unlink()

    lin = sky_like_linear()

    # 01 基准：8bit sRGB PNG，正常链路
    rgb8 = (np.clip(lin, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
    Image.fromarray(rgb8, "RGB").save(OUT / "01_png8_srgb.png")

    # 02 16bit/通道 PNG：把 0..1 的显示值铺到 0..65535，验证是否被降到 8bit
    arr16 = (np.clip(lin, 0.0, 1.0) * 65535.0 + 0.5).astype(np.uint16)
    write_png16_rgb(OUT / "02_png16.png", arr16)

    # 03 真 Radiance HDR（1K）改名 .png：探测 ImageIO 是否有 Radiance reader
    hdr1k = ASSETS / "kloppenheim_06_puresky_1k.hdr"
    if hdr1k.exists():
        shutil.copyfile(hdr1k, OUT / "03_radiance_hdr_renamed.png")

    # 04 真 Radiance HDR 保留原名：解析器的扩展名闸门
    if hdr1k.exists():
        shutil.copyfile(hdr1k, OUT / "04_radiance_hdr_real.hdr")

    # 05 4K HDR（约 17.3 MB）改名 .png：体积闸门先于解码生效
    hdr4k = ASSETS / "kloppenheim_06_puresky_4k.hdr"
    if hdr4k.exists():
        shutil.copyfile(hdr4k, OUT / "05_radiance_hdr4k_renamed.png")

    # 06 float32 TIFF 改名 .png：JDK 有 TIFF reader，检查能否读入 >1.0 的值
    ch = Image.fromarray(lin[:, :, 0].astype(np.float32), mode="F")
    ch.save(OUT / "06_float32_tiff_renamed.png", format="TIFF")

    # 07 JPEG 改名 .png：内容嗅探 vs 扩展名
    Image.fromarray(rgb8, "RGB").save(OUT / "07_jpeg_renamed.png", format="JPEG", quality=92)

    # 08 超尺寸 4098x2048：尺寸闸门
    big = sky_like_linear(4098, 2048)
    Image.fromarray((np.clip(big, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8), "RGB").save(
        OUT / "08_oversize_4098.png")

    # 09 16bit 灰度：ImageIO 读成 TYPE_USHORT_GRAY，用于观察 getRGB 是否退化成灰
    gray16 = np.tile(np.linspace(0, 65535, 1024, dtype=np.uint16), (512, 1))
    Image.fromarray(gray16, "I;16").save(OUT / "09_gray16.png")

    for f in sorted(OUT.iterdir()):
        print(f"{f.stat().st_size:>10}  {f.name}")


if __name__ == "__main__":
    main()
