#!/usr/bin/env python3
"""从 Modrinth 下载一个 Iris 光影包到参考实例的 shaderpacks/ 目录。

默认下载 Complementary Reimagined（对 1.21.1 + Iris 1.8 兼容性最好的一档）。
用法： python tools/fetch-shaderpack.py [项目slug]
"""
from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from local_paths import shaderpacks  # noqa: E402

SHADERPACKS = shaderpacks()
UA = "skyboxblock-test/1.0 (local dev)"


def api(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main() -> int:
    slug = sys.argv[1] if len(sys.argv) > 1 else "complementary-reimagined"
    versions = api(f"https://api.modrinth.com/v2/project/{slug}/version")

    candidates = []
    for version in versions:
        game_versions = version.get("game_versions") or []
        loaders = [l.lower() for l in (version.get("loaders") or [])]
        if "1.21.1" not in game_versions and "1.21" not in game_versions:
            continue
        if loaders and "iris" not in loaders:
            continue
        candidates.append(version)

    if not candidates:
        print("no compatible version found for", slug)
        return 1

    chosen = candidates[0]
    files = chosen.get("files") or []
    primary = next((f for f in files if f.get("primary")), files[0] if files else None)
    if primary is None:
        print("no files in version", chosen.get("version_number"))
        return 1

    SHADERPACKS.mkdir(parents=True, exist_ok=True)
    destination = SHADERPACKS / primary["filename"]
    if destination.exists():
        print("already present:", destination.name)
        return 0

    print(f"downloading {primary['filename']} ({primary.get('size', 0) // 1024} KB) from {chosen.get('version_number')}")
    request = urllib.request.Request(primary["url"], headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=300) as response, destination.open("wb") as out:
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)

    print("saved:", destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
