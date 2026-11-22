#!/usr/bin/env python3
"""Regenerate the tiny synthetic fixtures with OpenEXR 3.5.4 + NumPy (test-only)."""
from pathlib import Path
import numpy as np
import OpenEXR as exr

root = Path(__file__).resolve().parents[1] / 'src/test/resources/exr'
root.mkdir(parents=True, exist_ok=True)


def write(name, channels, compression, **kwargs):
    header = {'compression': compression, **kwargs}
    with exr.File(header, channels) as fixture:
        fixture.write(str(root / (name + '.exr')))


y, x = np.indices((35, 37))
channels = {
    'A': np.ones(x.shape, dtype=np.float16),
    'B': ((x % 17 + y % 5) / 64).astype(np.float16),
    'G': ((x % 9 + y % 13) / 70).astype(np.float32),
    'R': ((x * 5 + y * 3) / 128).astype(np.float16),
    'Z': (x * 9 + y * 3).astype(np.uint32),
}
window = {'dataWindow': (np.array([-7, -5], dtype=np.int32), np.array([29, 29], dtype=np.int32))}
for name, compression in [('raw', exr.NO_COMPRESSION), ('piz', exr.PIZ_COMPRESSION),
                          ('zip', exr.ZIP_COMPRESSION), ('zips', exr.ZIPS_COMPRESSION)]:
    write('scan-' + name, channels, compression, **window)

tiles = exr.TileDescription()
tiles.xSize, tiles.ySize = 35, 33
write('tile-piz', channels, exr.PIZ_COMPRESSION, type=exr.tiledimage, tiles=tiles, **window)

# 16,768 distinct half-word values in the first chunk exercise the modulo wavelet.
# Duplicate every row in the first chunk so the writer actually compresses that chunk.
x = np.arange(1048, dtype=np.uint16)
words = x[None, :] + (np.arange(32, dtype=np.uint16)[:, None] // 2) * x.size + 1
for name, compression in [('raw', exr.NO_COMPRESSION), ('piz', exr.PIZ_COMPRESSION)]:
    write('wide-' + name, {'Y': words.view(np.float16)}, compression)
