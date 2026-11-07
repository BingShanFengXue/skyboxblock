"""量化「16bit 输入 → 模组输出」的精度损失。

对每个已生成的输出 PNG，统计：
- IHDR 位深（8 还是 16）
- 横向 1024px 上出现多少个不同的 8bit 样本值

判定：
- c10（输入整幅只跨一个 8bit LSB：0x8000..0x80FF）
  8bit 输出 → 只有 1~2 个台阶；16bit 输出 → 应保留 256 个台阶
- c11（输入 0..65535 完整斜坡）
  8bit 输出 → 最多 256 个台阶；16bit 输出 → 上千个台阶
"""

import struct
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
TARGETS = {
    "r38-c10": ROOT / "runs/r38-c10/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_c10_lsb.png",
    "port2-c10": ROOT / "runs/port2-c10/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_c10_lsb.png",
    "r38-c11": ROOT / "runs/r38-c11/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_c11_ramp.png",
    "port2-c11": ROOT / "runs/port2-c11/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_c11_ramp.png",
    "input-c10": ROOT / "materials/10_png16_sub_lsb_band.png",
    "input-c11": ROOT / "materials/11_png16_full_ramp.png",
    "input-c2": ROOT / "materials/02_png16.png",
}


def ihdr(path: Path):
    head = path.read_bytes()[:33]
    assert head[:8] == b"\x89PNG\r\n\x1a\n", path
    width, height = struct.unpack(">II", head[16:24])
    return width, height, head[24], head[25]


def main():
    print(f"{'case':<12}{'size':>12}  {'bitDepth':>8} {'colorType':>9}  {'distinct8':>9}  {'row0min':>8} {'row0max':>8}")
    for name, path in TARGETS.items():
        if not path.exists():
            print(f"{name:<12}  MISSING {path}")
            continue
        w, h, depth, ctype = ihdr(path)
        img = Image.open(path)
        row = [img.getpixel((x, h // 2)) for x in range(w)]
        if isinstance(row[0], int):
            row = [(v, v, v) for v in row]
        row = [tuple(p)[:3] if isinstance(p, tuple) else (p, p, p) for p in row]
        distinct = len({p[0] for p in row})
        print(f"{name:<12}{f'{w}x{h}':>12}  {depth:>8} {ctype:>9}  {distinct:>9}  "
              f"{min(p[0] for p in row):>8} {max(p[0] for p in row):>8}")


if __name__ == "__main__":
    main()
