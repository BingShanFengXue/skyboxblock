#!/usr/bin/env python3
"""按条件拍一张实机截图：用于「DH 开关」与「光影开关」的 A/B 对比。

用法示例：
    python tools/capture-conditional.py --label dh-on
    python tools/capture-conditional.py --label dh-off --no-dh
    python tools/capture-conditional.py --label itrp --shader "iterationRP Alpha 0.8.31.zip"
    python tools/capture-conditional.py --label vanilla --shader ""

产物：docs/screenshots/conditions/<label>.png（外加同名 .log.txt 抓关键日志行）
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INSTANCE = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1")
MODS = INSTANCE / "mods"
IRIS = INSTANCE / "config" / "iris.properties"
SHOT = ROOT / "build" / "world-frame.png"
LOG = ROOT / "build" / "client-262.log"
OUT = ROOT / "docs" / "screenshots" / "conditions"

DH_JAR = "DistantHorizons-3.3.3-26.2-fabric-neoforge.jar"
DH_PARKED = INSTANCE / ("_" + DH_JAR + ".disabled")

KEY_LINES = ("26.2 环境", "sky target created", "云层 CPU 开销", "render skin=",
             "Shader", "shaderpack", "Iris", "Exception", "ERROR")


def kill_game() -> None:
    subprocess.run([
        "powershell", "-NoProfile", "-Command",
        "Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Stop-Process -Force",
    ], check=False)


def set_shader(pack: str) -> None:
    text = IRIS.read_text(encoding="utf-8")
    lines = [ln for ln in text.splitlines() if not ln.startswith("shaderPack=")]
    lines.append(f"shaderPack={pack}")
    IRIS.write_text("\n".join(lines) + "\n", encoding="utf-8")


def set_dh(enabled: bool) -> None:
    active = MODS / DH_JAR
    if enabled:
        if DH_PARKED.exists():
            shutil.move(str(DH_PARKED), str(active))
        print(f"    DH: 启用 ({active.name})")
    else:
        if active.exists():
            shutil.move(str(active), str(DH_PARKED))
        print(f"    DH: 停用（移到 {DH_PARKED.name}）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--no-dh", action="store_true")
    parser.add_argument("--shader", default=None, help="shaderpack 文件名；空串表示关闭光影")
    parser.add_argument("--src", default="build/world-frame.png")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    set_dh(not args.no_dh)
    if args.shader is not None:
        set_shader(args.shader)
        print(f"    shaderPack = {args.shader or '(关闭)'}")

    kill_game()
    shot = ROOT / args.src
    shot.unlink(missing_ok=True)
    subprocess.run([str(sys_executable()), str(ROOT / "tools" / "launch-262.py"), "--world", "flat262"], check=False)
    deadline = time.time() + 240
    while time.time() < deadline and not shot.exists():
        time.sleep(3)

    if shot.exists():
        target = OUT / f"{args.label}.png"
        target.write_bytes(shot.read_bytes())
        print(f"    截图 -> {target.relative_to(ROOT)}")
    else:
        print("    TIMEOUT: 没有拿到截图")

    if LOG.exists():
        kept = [ln for ln in LOG.read_text(encoding="utf-8", errors="ignore").splitlines()
                if any(k in ln for k in KEY_LINES)]
        (OUT / f"{args.label}.log.txt").write_text("\n".join(kept[-60:]) + "\n", encoding="utf-8")
        print(f"    日志摘要 -> docs/screenshots/conditions/{args.label}.log.txt（{len(kept)} 行）")

    kill_game()
    return 0


def sys_executable() -> str:
    import sys
    return sys.executable


if __name__ == "__main__":
    raise SystemExit(main())
