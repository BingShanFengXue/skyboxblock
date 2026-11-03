#!/usr/bin/env python3
"""把 CloudTargetMixin 注册进 skyboxblock.mixins.json 的 client 列表（一次性）。"""
import json
from pathlib import Path

TARGET = Path(__file__).resolve().parent.parent / "src/main/resources/skyboxblock.mixins.json"


def main() -> int:
    data = json.loads(TARGET.read_text(encoding="utf-8"))
    client = data.setdefault("client", [])
    if "CloudTargetMixin" not in client:
        client.append("CloudTargetMixin")
    TARGET.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(TARGET.read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
