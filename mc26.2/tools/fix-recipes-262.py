#!/usr/bin/env python3
"""把 9 个配方 JSON 从 1.21.1 形态迁到 26.2 形态。

26.2 的 Ingredient 编码换了：`{"item": "minecraft:glass"}` 不再被接受
（日志报 `No key fabric:type in MapLike[{"item": ...}]`），
最简写法是**纯字符串** `"minecraft:glass"`。

用法：python tools/fix-recipes-262.py [--check]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
RECIPES = PROJECT / "src" / "main" / "resources" / "data" / "skyboxblock" / "recipe"


def as_ingredient(value):
    """把 {"item": id} 收敛成 id 字符串；已经是字符串就原样返回。"""
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and set(value.keys()) == {"item"}:
        return value["item"]
    return value


def convert(recipe: dict) -> dict:
    if "key" in recipe and isinstance(recipe["key"], dict):
        recipe["key"] = {k: as_ingredient(v) for k, v in recipe["key"].items()}
    if "ingredients" in recipe and isinstance(recipe["ingredients"], list):
        recipe["ingredients"] = [as_ingredient(v) for v in recipe["ingredients"]]
    return recipe


def main() -> int:
    check = "--check" in sys.argv
    changed = 0
    for path in sorted(RECIPES.glob("*.json")):
        original = json.loads(path.read_text(encoding="utf-8"))
        updated = convert(json.loads(path.read_text(encoding="utf-8")))
        if original != updated:
            changed += 1
            if not check:
                path.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{'would change' if check else ('changed' if original != updated else 'unchanged')}: {path.name}")
    print(f"total {changed} recipe(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
