#!/usr/bin/env python3
"""启动 PCL 实例 "26.2-Fabric 0.20.1"（MC 26.2 / Fabric 0.20.1 / Java 25），用于实机验证移植线。

与 skybox-mod/tools/launch-client.py 同构，但：
  * 目标版本目录不同（26.2 实例）；
  * 用 JDK 25（26.2 最低 Java 25）；
  * 带 26.2 需要的 JVM 开关：--sun-misc-unsafe-memory-access=allow、--enable-native-access=ALL-UNNAMED；
  * assetIndex 从版本 json 读（不写死）。

用法：
  python tools/launch-262.py              # 进主菜单
  python tools/launch-262.py --world 新界  # 直接进存档
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

MC_ROOT = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft")
VERSION_NAME = "26.2-Fabric 0.20.1"
VERSION_DIR = MC_ROOT / "versions" / VERSION_NAME
JAVA = Path(r"C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe")
NATIVES = VERSION_DIR / f"{VERSION_NAME}-natives"

LOG = Path(__file__).resolve().parent.parent / "build" / "client-262.log"
CMD_DUMP = Path(__file__).resolve().parent.parent / "build" / "client-262-command.txt"


def rule_allows(lib: dict) -> bool:
    rules = lib.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os", {})
        name = os_rule.get("name")
        if name is not None and name != "windows":
            continue
        arch = os_rule.get("arch")
        if arch is not None and arch not in ("x86_64", "amd64"):
            continue
        allowed = rule.get("action") == "allow"
    return allowed


def maven_path(name: str) -> str | None:
    parts = name.split(":")
    if len(parts) < 3:
        return None
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    filename = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    return str(Path(*group.split(".")) / artifact / version / filename)


def build_classpath(version: dict) -> list[str]:
    entries: list[str] = []
    for lib in version.get("libraries", []):
        if not rule_allows(lib):
            continue
        artifact = lib.get("downloads", {}).get("artifact")
        if artifact and artifact.get("path"):
            entries.append(str(MC_ROOT / "libraries" / artifact["path"]))
            continue
        rel = maven_path(lib.get("name", ""))
        if rel:
            entries.append(str(MC_ROOT / "libraries" / rel))
    entries.append(str(VERSION_DIR / f"{VERSION_NAME}.jar"))
    return entries


def main() -> int:
    version = json.loads((VERSION_DIR / f"{VERSION_NAME}.json").read_text(encoding="utf-8"))
    classpath = build_classpath(version)
    asset_index = version.get("assetIndex", {}).get("id", "32")

    missing = [p for p in classpath if not Path(p).exists()]
    if missing:
        print("missing classpath entries:", *missing, sep="\n  ")
        return 2

    args = [
        str(JAVA),
        "-Xms2G", "-Xmx4G",
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
        f"-Djava.library.path={NATIVES / 'java'}",
        f"-Djna.tmpdir={NATIVES / 'jna'}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={NATIVES / 'lwjgl'}",
        f"-Dio.netty.native.workdir={NATIVES / 'netty'}",
        "-Dminecraft.launcher.brand=PCL",
        "-Dminecraft.launcher.version=2.0",
        "-Dfabric.log.level=info",
        "-cp", os.pathsep.join(classpath),
        version["mainClass"],
        "--username", "Dev",
        "--version", "26.2",
        "--gameDir", str(VERSION_DIR),
        "--assetsDir", str(MC_ROOT / "assets"),
        "--assetIndex", asset_index,
        "--uuid", "00000000000000000000000000000000",
        "--accessToken", "0",
        "--userType", "legacy",
        "--versionType", "release",
        "--width", "1280",
        "--height", "800",
    ]

    if "--world" in sys.argv:
        world = sys.argv[sys.argv.index("--world") + 1]
        args += ["--quickPlaySingleplayer", world]

    CMD_DUMP.parent.mkdir(parents=True, exist_ok=True)
    CMD_DUMP.write_text(" ".join(f'"{a}"' if " " in a else a for a in args), encoding="utf-8")

    print("launching:", JAVA)
    print("gameDir  :", VERSION_DIR)
    print("log      :", LOG)
    with LOG.open("wb") as log_file:
        proc = subprocess.Popen(args, stdout=log_file, stderr=subprocess.STDOUT,
                                cwd=str(VERSION_DIR), creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    print("pid:", proc.pid)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
