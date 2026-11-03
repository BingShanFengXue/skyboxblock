#!/usr/bin/env python3
"""天空盒"透视窗"对齐度的可重复验证。

做法：
1. 用数据包夹具在**同一机位、同一时刻**（`fixture/tick` 每刻 `time set 6000`）拍两张图：
   - 有墙（`add-fixture-datapack.py`，默认）
   - 无墙（`add-fixture-datapack.py --no-wall`）
2. 两张图里"原版天空风格"那一列占据的屏幕区域，逐像素比较。

判据：平均绝对差 < 2、最大差 < 12、差异>8 的像素占比 < 1% ⇒ 方块面与真实天空不可区分。

用法：
    python tools/verify-sky-parity.py build/nw.png build/ww.png
"""
from __future__ import annotations

import sys

import numpy as np
from PIL import Image

# 原版天空风格那一列（根据 1280x800 机位、六列墙的布局标定）
COLUMN = (676, 742)      # x0, x1
ROWS = (90, 600)         # y0, y1


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    no_wall = np.asarray(Image.open(sys.argv[1]).convert("RGB"), dtype=np.int16)
    wall = np.asarray(Image.open(sys.argv[2]).convert("RGB"), dtype=np.int16)
    x0, x1 = COLUMN
    y0, y1 = ROWS
    a = no_wall[y0:y1, x0:x1]
    b = wall[y0:y1, x0:x1]
    diff = np.abs(a - b)
    mean = diff.reshape(-1, 3).mean(axis=0)
    worst = int(diff.max())
    bad = float((diff.max(axis=2) > 8).mean())

    print(f"pixels            : {diff.shape[0] * diff.shape[1]}")
    print(f"mean abs diff (RGB): {mean.round(2)}")
    print(f"max diff          : {worst}")
    print(f"share >8          : {bad * 100:.2f}%")

    ok = mean.max() < 2.0 and worst < 12 and bad < 0.01
    print("VERDICT:", "PASS（方块面与真实天空不可区分）" if ok else "FAIL（仍可区分）")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
