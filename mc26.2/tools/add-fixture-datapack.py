#!/usr/bin/env python3
"""往 flat262 存档里塞一个"世界加载即执行"的 datapack，用来无 cheats 摆出天空盒方块。

用法：
    python tools/add-fixture-datapack.py            # 有墙（默认）
    python tools/add-fixture-datapack.py --no-wall  # 空场景，用于"墙内外同像素"对比

为什么不用聊天命令：服务端生成的世界 level.dat 里通常没有 allowCommands=true，
单人模式默认关闭作弊，`/fill` 会被拒绝。datapack 的 `minecraft:load` 函数以服务器权限执行。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

MC_ROOT = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft")
SAVE = MC_ROOT / "versions" / "26.2-Fabric 0.20.1" / "saves" / "flat262"
PACK = SAVE / "datapacks" / "skybox-fixture"

HEADER = """\
# 生存规则：和平模式（不刷敌对生物）。26.2 里 gamerule 名字有变动，doMobSpawning 解析失败，
# 所以只用 difficulty + weather。
difficulty peaceful
weather clear
time set day
scoreboard objectives add skyboxq2 dummy
"""

# 6 列天空盒墙（覆盖 1.21.1 的四种 shaderMode）：纯白 / 纯黑 / 原版 / 全景 ×2 / 自定义全景
WALL = """\
fill -3 -60 4 -3 -56 4 skyboxblock:white_sky_block
fill -2 -60 4 -2 -56 4 skyboxblock:black_sky_block
fill -1 -60 4 -1 -56 4 skyboxblock:sky_block
fill 0 -60 4 0 -56 4 skyboxblock:galaxy_sky_block
fill 1 -60 4 1 -56 4 skyboxblock:dusk_sky_block
fill 2 -60 4 2 -56 4 skyboxblock:gradient
"""

FOOTER = """\
setworldspawn 0 -60 0
say [skybox-fixture] world ready
"""

# 固定机位：正对墙、平视。要看天顶就把俯仰角换成 -80。
# 每刻把时间锁在 6000：保证"有墙 / 无墙"两次运行的天色一致，才谈得上逐像素对比。
TICK = """\
time set 6000
execute as @a run tp @s 0.5 -60.0 -3.5 0 0
execute as @a[gamemode=!creative] run gamemode creative @s
execute if entity @a unless score #q2 skyboxq2 matches 1 run function fixture:q2
"""

Q2 = """\
scoreboard players set #q2 skyboxq2 1
execute as @a run skyboxblock player @s visual/sky_color #22FF00
execute as @a run skyboxblock player @s show
say [skybox-fixture] q2 command executed
"""

# 验收模式：十种风格各一列（含自定义 gradient），用于逐种截图。
ALL_STYLES = [
    "sky_block", "white_sky_block", "black_sky_block",
    "kloppenheim_sky_block", "syferfontein_sky_block", "dusk_sky_block",
    "galaxy_sky_block", "lush_sky_block", "apocalypse_sky_block",
    "gradient",
]
STYLE_WALL = "".join(
    f"fill {x} -60 4 {x + 4} -48 4 skyboxblock:{style}\n"
    for x, style in zip(range(-27, 30, 6), ALL_STYLES)
)
STYLE_CAMERA = "execute as @a run tp @s 0.5 -60.0 -25 0 0"


def build_style_wall(indices: list[int]) -> tuple[str, str]:
    """按给定的风格下标生成紧排的一段墙 + 对准它的机位（用于逐组拍近景）。"""
    count = len(indices)
    spacing = 5
    total = count * spacing - 1
    left = -(total // 2)
    fills = []
    for slot, index in enumerate(indices):
        x0 = left + slot * spacing
        fills.append(f"fill {x0} -60 4 {x0 + 3} -44 4 skyboxblock:{ALL_STYLES[index]}\n")
    camera = f"execute as @a run tp @s {-left + 1.5} -60.0 {-7} 0 0"
    return "".join(fills), camera


def main() -> int:
    with_wall = "--no-wall" not in sys.argv
    all_styles = "--all-styles" in sys.argv
    style_range = None
    for arg in sys.argv:
        if arg.startswith("--styles-range="):
            lo, hi = arg.split("=", 1)[1].split("-")
            style_range = list(range(int(lo), int(hi)))
    data = PACK / "data"
    (data / "fixture" / "function").mkdir(parents=True, exist_ok=True)
    (data / "minecraft" / "tags" / "function").mkdir(parents=True, exist_ok=True)

    (PACK / "pack.mcmeta").write_text(
        json.dumps({"pack": {"pack_format": 107, "description": "skyboxblock 26.2 fixture"}}, indent=2) + "\n",
        encoding="utf-8",
    )
    if style_range or all_styles:
        wall_text, camera_line = build_style_wall(style_range or list(range(10)))
        setup = HEADER + wall_text + FOOTER
        lines = [ln for ln in TICK.splitlines() if not ln.startswith("execute as @a run tp")]
        tick = camera_line + "\n" + "\n".join(lines) + "\n"
    else:
        setup = HEADER + (WALL if with_wall else "") + FOOTER
        tick = TICK
    (data / "fixture" / "function" / "setup.mcfunction").write_text(setup, encoding="utf-8")
    (data / "fixture" / "function" / "tick.mcfunction").write_text(tick, encoding="utf-8")
    (data / "fixture" / "function" / "q2.mcfunction").write_text(Q2, encoding="utf-8")
    (data / "minecraft" / "tags" / "function" / "load.json").write_text(
        json.dumps({"values": ["fixture:setup"]}, indent=2) + "\n", encoding="utf-8"
    )
    (data / "minecraft" / "tags" / "function" / "tick.json").write_text(
        json.dumps({"values": ["fixture:tick"]}, indent=2) + "\n", encoding="utf-8"
    )
    print(f"datapack -> {PACK} (wall={with_wall})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
