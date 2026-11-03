#!/usr/bin/env python3
"""把 1.21.1 主线源码机械搬运到 26.2 移植线，并做第一批**确定性**改名。

为什么要这个脚本而不是 PowerShell：
    工作区红线 —— 绝不用 PowerShell 读写 .java（GBK 解码会不可逆地毁掉中文）。
    Python 这里全程 bytes -> utf-8 解码 -> 替换 -> utf-8 编码，非 UTF-8 会直接抛错而不是静默毁坏。

只做「已经由 javap 实测确认」的改名，其余保持原样，让编译错误把工作列出来：
    ResourceLocation            -> Identifier        （net.minecraft.resources）
    blaze3d.platform.GlStateManager -> blaze3d.opengl.GlStateManager

用法：
    python tools/port_copy.py [--dry-run]
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
SRC = PROJECT.parent / "skybox-mod" / "src" / "main"
DST = PROJECT / "src" / "main"

# 顺序有意义：先换全限定名，再换裸类名。
RULES: list[tuple[str, str, str]] = [
    (
        "com.mojang.blaze3d.platform.GlStateManager",
        "com.mojang.blaze3d.opengl.GlStateManager",
        "GlStateManager 包移动（blaze3d.platform -> blaze3d.opengl）",
    ),
    (
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.resources.Identifier",
        "ResourceLocation 全限定名 -> Identifier",
    ),
    ("ResourceLocation", "Identifier", "ResourceLocation 裸类名 -> Identifier"),
]

TEXT_SUFFIXES = {".java", ".json", ".mcmeta", ".md", ".txt", ".fsh", ".vsh", ".properties"}


def convert_text(path: Path) -> tuple[str, dict[str, int]]:
    raw = path.read_bytes()
    # strict：非 UTF-8 立刻炸，绝不静默替换成 '?'
    text = raw.decode("utf-8")
    counts: dict[str, int] = {}
    for old, new, label in RULES:
        n = text.count(old)
        if n:
            text = text.replace(old, new)
            counts[label] = counts.get(label, 0) + n
    return text, counts


def main() -> int:
    dry = "--dry-run" in sys.argv
    if not SRC.is_dir():
        print(f"源目录不存在：{SRC}")
        return 2

    files = 0
    changed_files = 0
    totals: dict[str, int] = {}
    per_file: list[str] = []

    for src_path in sorted(SRC.rglob("*")):
        if not src_path.is_file():
            continue
        rel = src_path.relative_to(SRC)
        dst_path = DST / rel
        files += 1
        if dry:
            continue

        dst_path.parent.mkdir(parents=True, exist_ok=True)

        if src_path.suffix.lower() in TEXT_SUFFIXES:
            text, counts = convert_text(src_path)
            dst_path.write_bytes(text.encode("utf-8"))
            if counts:
                changed_files += 1
                for k, v in counts.items():
                    totals[k] = totals.get(k, 0) + v
                per_file.append(f"{rel.as_posix()}  " + ", ".join(f"{k}×{v}" for k, v in counts.items()))
        else:
            shutil.copyfile(src_path, dst_path)

    print(f"复制文件数：{files}")
    print(f"发生改名的文件数：{changed_files}")
    for label, n in sorted(totals.items(), key=lambda kv: -kv[1]):
        print(f"  {n:5d}  {label}")
    if per_file:
        print("--- 明细（前 40）---")
        print("\n".join(per_file[:40]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
