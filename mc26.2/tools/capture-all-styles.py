#!/usr/bin/env python3
"""逐种天空盒拍实机近景（每种一张图），用于人工验收"显示效果正确性"。

做法：对每种风格各跑一次游戏——夹具里只摆这一列方块，机位固定正对它，截图后按风格命名。

用法：
    python tools/capture-all-styles.py            # 全部 10 种
    python tools/capture-all-styles.py galaxy dusk  # 只拍指定风格

产物：docs/screenshots/styles/<style>.png
"""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAVE = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1\saves\flat262")
PACK = SAVE / "datapacks" / "skybox-fixture"
OUT = ROOT / "docs" / "screenshots" / "styles"
SHOT = ROOT / "build" / "world-frame.png"

STYLES = [
    ("vanilla", "sky_block"),
    ("white", "white_sky_block"),
    ("black", "black_sky_block"),
    ("kloppenheim", "kloppenheim_sky_block"),
    ("syferfontein", "syferfontein_sky_block"),
    ("dusk", "dusk_sky_block"),
    ("galaxy", "galaxy_sky_block"),
    ("lush", "lush_sky_block"),
    ("apocalypse", "apocalypse_sky_block"),
    ("gradient", "gradient"),
]

SETUP_HEAD = """\
difficulty peaceful
weather clear
time set 6000
scoreboard objectives add skyboxq2 dummy
"""

TICK = """\
time set 6000
execute as @a run tp @s 0.5 -60.0 -8 0 0
execute as @a[gamemode=!creative] run gamemode creative @s
"""


def write_fixture(block: str) -> None:
    func = PACK / "data" / "fixture" / "function"
    func.mkdir(parents=True, exist_ok=True)
    (PACK / "data" / "minecraft" / "tags" / "function").mkdir(parents=True, exist_ok=True)
    (PACK / "pack.mcmeta").write_text(
        json.dumps({"pack": {"pack_format": 107, "description": "skybox styles"}}, indent=2) + "\n",
        encoding="utf-8",
    )
    (func / "setup.mcfunction").write_text(
        SETUP_HEAD
        # 先清空整块试验场：datapack 的 fill 只叠加不清除，上一次遗留的墙会串进这次的截图。
        + "fill -60 -64 -16 60 -24 20 air\n"
        + f"fill -2 -60 4 2 -44 4 skyboxblock:{block}\n"
        "setworldspawn 0 -60 0\nsay [skybox-fixture] ready\n",
        encoding="utf-8",
    )
    (func / "tick.mcfunction").write_text(TICK, encoding="utf-8")
    (PACK / "data" / "minecraft" / "tags" / "function" / "load.json").write_text(
        json.dumps({"values": ["fixture:setup"]}) + "\n", encoding="utf-8")
    (PACK / "data" / "minecraft" / "tags" / "function" / "tick.json").write_text(
        json.dumps({"values": ["fixture:tick"]}) + "\n", encoding="utf-8")


def kill_game() -> None:
    subprocess.run([
        "powershell", "-NoProfile", "-Command",
        "Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Stop-Process -Force",
    ], check=False)


def main() -> int:
    wanted = sys.argv[1:]
    targets = [entry for entry in STYLES if not wanted or entry[0] in wanted]
    OUT.mkdir(parents=True, exist_ok=True)
    python = sys.executable
    for name, block in targets:
        print(f"=== {name} ({block}) ===", flush=True)
        write_fixture(block)
        kill_game()
        SHOT.unlink(missing_ok=True)
        subprocess.run([python, str(ROOT / "tools" / "launch-262.py"), "--world", "flat262"], check=False)
        deadline = time.time() + 180
        while time.time() < deadline and not SHOT.exists():
            time.sleep(3)
        if SHOT.exists():
            target = OUT / f"{name}.png"
            target.write_bytes(SHOT.read_bytes())
            print(f"    saved {target.relative_to(ROOT)} ({SHOT.stat().st_size} bytes)", flush=True)
        else:
            print("    TIMEOUT: 没有拿到截图", flush=True)
        kill_game()
        time.sleep(2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
