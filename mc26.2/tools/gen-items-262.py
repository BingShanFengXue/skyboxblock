#!/usr/bin/env python3
"""为 9 个内置方块生成 26.2 形态的物品模型。

26.2 起物品模型入口从 `assets/<ns>/models/item/<id>.json` 改成
`assets/<ns>/items/<id>.json`，内容形如：
    {"model": {"type": "minecraft:model", "model": "<ns>:block/<id>"}}

旧的 models/item 文件保留（当 parent 目标用），不再由加载器读取。
"""
from __future__ import annotations

import json
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
ASSETS = PROJECT / "src" / "main" / "resources" / "assets" / "skyboxblock"
NS = "skyboxblock"

BLOCKS = [
    "sky_block",
    "white_sky_block",
    "black_sky_block",
    "kloppenheim_sky_block",
    "syferfontein_sky_block",
    "dusk_sky_block",
    "galaxy_sky_block",
    "lush_sky_block",
    "apocalypse_sky_block",
]


def main() -> int:
    out_dir = ASSETS / "items"
    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for block in BLOCKS:
        payload = {"model": {"type": "minecraft:model", "model": f"{NS}:block/{block}"}}
        (out_dir / f"{block}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        written += 1
    print(f"wrote {written} item models -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
