#!/usr/bin/env python3
"""启动 PCL 实例 "Rapid Optimization"（MC 1.21.1 / Fabric），用于实机验证 mod。

它做的事等价于 PCL 的启动流程：
  1. 读版本 json，按 rules 过滤 libraries，拼 classpath；
  2. 用 Kotlin 启动器自带的 java-runtime-delta（21.0.3）；
  3. 以 KnotClient 启动，可带 --quickPlaySingleplayer 直接进世界。

用法：
  python tools/launch-client.py            # 进主菜单
  python tools/launch-client.py --world 新界   # 直接进存档
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

MC_ROOT_FALLBACK = None  # 机器路径不再写死：见 tools/local_paths.py
sys.path.insert(0, str(Path(__file__).resolve().parent))
from local_paths import java_home, version_dir  # noqa: E402

VERSION_DIR = version_dir()
VERSION_NAME = VERSION_DIR.name
MC_ROOT = VERSION_DIR.parent.parent
JAVA = java_home() / "bin" / "java.exe"
NATIVES = VERSION_DIR / f"{VERSION_NAME}-natives"

LOG = Path(__file__).resolve().parent.parent / "build" / "client-launch.log"
CMD_DUMP = Path(__file__).resolve().parent.parent / "build" / "client-command.txt"


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
    """把 group:artifact:version[:classifier] 转成 libraries 下的相对路径。"""
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
        # PCL 生成的版本 json 里，fabric-loader / intermediary 只写了 maven 坐标。
        rel = maven_path(lib.get("name", ""))
        if rel:
            entries.append(str(MC_ROOT / "libraries" / rel))
    entries.append(str(VERSION_DIR / f"{VERSION_NAME}.jar"))
    return entries


def main() -> int:
    version = json.loads((VERSION_DIR / f"{VERSION_NAME}.json").read_text(encoding="utf-8"))
    classpath = build_classpath(version)

    missing = [p for p in classpath if not Path(p).exists()]
    if missing:
        print("missing classpath entries:", *missing, sep="\n  ")
        return 2

    args = [
        str(JAVA),
        "-Xms2G",
        "-Xmx4G",
        f"-Djava.library.path={NATIVES}",
        f"-Djna.tmpdir={NATIVES}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={NATIVES}",
        f"-Dio.netty.native.workdir={NATIVES}",
        "-Dminecraft.launcher.brand=PCL",
        "-Dminecraft.launcher.version=2.0",
        "-Dfabric.log.level=info",
    ]

    if "--autotest" in sys.argv:
        args += ["-Dskyboxblock.autotest=true", "-Dskyboxblock.debug=true"]

    args += [
        "-cp",
        os.pathsep.join(classpath),
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "--username", "Dev",
        "--version", "1.21.1",
        "--gameDir", str(VERSION_DIR),
        "--assetsDir", str(MC_ROOT / "assets"),
        "--assetIndex", "17",
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
