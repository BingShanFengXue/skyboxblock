"""补充素材：用来量化「16bit 输入 → 8bit 输出」到底丢了多少精度。

c10：16bit PNG，整幅图只跨一个 8bit 量化台阶（0x8000..0x80FF）。
     如果输出仍是 16bit，图里应能分辨出 256 个值；
     如果被压成 8bit，输出只会剩 1 个值（或极少数）。
c11：16bit PNG，完整 0..65535 斜坡，用来数输出端实际能分辨的台阶数。
"""

import struct
import zlib
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent / "materials"


def png_chunk(tag: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + tag + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))


def write_png16_rgb(path, arr16):
    h, w, _ = arr16.shape
    raw = b"".join(b"\x00" + arr16[y].astype(">u2").tobytes() for y in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 16, 2, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr)
                     + png_chunk(b"IDAT", zlib.compress(raw, 9)) + png_chunk(b"IEND", b""))


def main():
    W, H = 1024, 512

    # c10：整幅图落在 0x8000..0x80FF，256 个台阶 = 一个 8bit LSB
    band = np.tile(np.linspace(0x8000, 0x80FF, W, dtype=np.uint16), (H, 1))
    arr = np.stack([band, band, band], axis=-1)
    write_png16_rgb(OUT / "10_png16_sub_lsb_band.png", arr)

    # c11：完整斜坡 0..65535，横向 65536 级不够宽，用 1024 宽也够数台阶
    ramp = np.tile(np.linspace(0, 65535, W, dtype=np.uint16), (H, 1))
    arr2 = np.stack([ramp, ramp, ramp], axis=-1)
    write_png16_rgb(OUT / "11_png16_full_ramp.png", arr2)

    for name in ("10_png16_sub_lsb_band.png", "11_png16_full_ramp.png"):
        p = OUT / name
        print(f"{p.stat().st_size:>9}  {name}")


if __name__ == "__main__":
    main()
