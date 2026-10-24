#!/usr/bin/env python3
"""解析本机路径，避免把用户名/盘符写死进仓库。

查找顺序（每个键都一样）：
  1. 环境变量（显式名，或 KEY 的大写形式）
  2. `tools/local.paths.json`（已 git-ignore；从 `local.paths.example.json` 复制）
  3. 本模块自带的推导（仅用于 Minecraft 启动器自带的 JDK）
  4. 调用方给的默认值

缺路径时打印可执行的提示并 `SystemExit`，让新克隆的仓库给出说明而不是堆栈。
"""
from __future__ import annotations

import json
import os
from pathlib import Path

CONFIG = Path(__file__).resolve().with_name("local.paths.json")
_MISSING = object()


def load_config() -> dict:
    if not CONFIG.exists():
        return {}
    try:
        return json.loads(CONFIG.read_text(encoding="utf-8"))
    except Exception as exc:  # 配置由人手工维护
        raise SystemExit(f"[paths] 无法解析 {CONFIG}：{exc}")


def _mojang_runtime() -> Path | None:
    """Minecraft 启动器自带的 java-runtime-*，从 LOCALAPPDATA 推导，不写死用户名。"""
    local = os.environ.get("LOCALAPPDATA")
    if not local:
        return None
    root = Path(local) / "Packages" / "Microsoft.4297127D64EC6_8wekyb3d8bbwe" / "LocalCache" / "Local" / "runtime"
    if not root.is_dir():
        return None
    for exe in sorted(root.glob("*/windows-x64/*/bin/java.exe")):
        return exe.parent.parent
    return None


def _resolve(key: str, env: str | None) -> str | None:
    env_names = [env] if env else []
    env_names.append(key.upper())
    for name in env_names:
        if not name:
            continue
        value = os.environ.get(name)
        if value:
            return value
    value = load_config().get(key)
    return str(value) if value else None


def local_path(key: str, env: str | None = None, default=_MISSING, kind: str = "dir", hint: str = "") -> Path:
    raw = _resolve(key, env)
    if raw is None:
        if default is not _MISSING:
            return Path(default)
        names = "、".join(dict.fromkeys([n for n in (env, key.upper()) if n]))
        raise SystemExit(
            f"[paths] 未配置 {key}：设置环境变量 {names}，"
            f"或把 {key} 写进 {CONFIG.name}（示例见 local.paths.example.json）。{hint}"
        )
    path = Path(raw).expanduser()
    ok = path.is_file() if kind == "file" else path.is_dir()
    if not ok:
        raise SystemExit(f"[paths] {key}={path} 不是有效的{'文件' if kind == 'file' else '目录'}。{hint}")
    return path


def java_home(hint: str = "") -> Path:
    """JDK 目录（含 bin/java.exe）。缺省回落到启动器自带的 java-runtime。"""
    found = _resolve("java_home", "JAVA_HOME")
    if found:
        return local_path("java_home", "JAVA_HOME", hint=hint)
    runtime = _mojang_runtime()
    if runtime is None:
        raise SystemExit(
            "[paths] 未找到 JDK：设置 JAVA_HOME，或把 java_home 写进 tools/local.paths.json。"
            "Minecraft 启动器安装的 java-runtime-* 也会被自动识别。" + hint
        )
    return runtime


def mc_root(hint: str = "") -> Path:
    """Minecraft 实例根（含 versions/、mods/、shaderpacks/）。"""
    return local_path("mc_root", "MC_ROOT", hint=hint or "（示例：D:/Games/Minecraft/PCL/.minecraft）")


def version_dir(name_env: str = "MC_VERSION_NAME", default: str = "Rapid Optimization") -> Path:
    name = os.environ.get(name_env) or load_config().get("mc_version_name") or default
    return mc_root() / "versions" / str(name)


def shaderpacks() -> Path:
    found = _resolve("shaderpacks", "SHADERPACKS")
    if found:
        return local_path("shaderpacks", "SHADERPACKS")
    return version_dir() / "shaderpacks"
