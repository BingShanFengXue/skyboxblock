#!/usr/bin/env python3
"""为 26.2 客户端造一个可加载的 superflat 世界。

为什么要服务端：客户端 `saves/` 是空的，而 `--quickPlaySingleplayer` 只能进已存在的存档。
最稳的办法是用官方 26.2 服务端生成一次世界，再把 `world/` 复制成客户端存档。

用法：
  python tools/make-flat-world-262.py [--force]
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
WORK = PROJECT / "build" / "server-262"
SERVER_JAR = WORK / "server.jar"
JAVA = Path(r"C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe")
MC_ROOT = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft")
SAVE_NAME = "flat262"
SAVE_DIR = MC_ROOT / "versions" / "26.2-Fabric 0.20.1" / "saves" / SAVE_NAME

SERVER_URL = "https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar"

PROPERTIES = """\
online-mode=false
level-type=minecraft:flat
level-seed=262
spawn-protection=0
max-players=2
view-distance=8
simulation-distance=6
sync-chunk-writes=false
enable-command-block=true
motd=skyboxblock 26.2 fixture
"""


def download() -> None:
    if SERVER_JAR.is_file() and SERVER_JAR.stat().st_size > 1_000_000:
        print(f"server.jar 已存在: {SERVER_JAR.stat().st_size} B")
        return
    WORK.mkdir(parents=True, exist_ok=True)
    print("downloading server.jar ...")
    urllib.request.urlretrieve(SERVER_URL, SERVER_JAR)
    print(f"downloaded: {SERVER_JAR.stat().st_size} B")


def generate() -> None:
    (WORK / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (WORK / "server.properties").write_text(PROPERTIES, encoding="utf-8")

    args = [str(JAVA), "-Xmx2G", "-jar", str(SERVER_JAR), "nogui"]
    print("starting server ...")
    proc = subprocess.Popen(args, cwd=str(WORK), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    deadline = time.time() + 300
    ready = False
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip()
        if "Done (" in line or "Starting remote control" in line:
            ready = True
            print("server ready:", line)
            break
        if time.time() > deadline:
            break
    if not ready:
        print("server did not report ready in time")
    assert proc.stdin is not None
    proc.stdin.write("stop\n")
    proc.stdin.flush()
    try:
        proc.wait(timeout=120)
    except subprocess.TimeoutExpired:
        proc.kill()
    print("server exit:", proc.returncode)


def copy_world() -> None:
    source = WORK / "world"
    if not (source / "level.dat").is_file():
        raise SystemExit(f"未生成世界: {source}")
    SAVE_DIR.parent.mkdir(parents=True, exist_ok=True)
    if SAVE_DIR.exists():
        shutil.rmtree(SAVE_DIR)
    shutil.copytree(source, SAVE_DIR)
    print(f"save -> {SAVE_DIR}")


def main() -> int:
    download()
    if "--force" in sys.argv or not (WORK / "world" / "level.dat").is_file():
        generate()
    copy_world()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
