#!/usr/bin/env python3
"""Check the sky_block-only distribution, shader resources and removal of legacy capture code."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", nargs="?", type=Path, default=ROOT / "build/libs/skyboxblock-1.0.2-test.4.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/skybox-test4-package-audit.json")
    args = parser.parse_args()
    checks = []

    def add(name, passed, **data):
        checks.append({"name": name, "passed": bool(passed), **data})

    with zipfile.ZipFile(args.jar) as jar:
        names = set(jar.namelist())
        metadata = json.loads(jar.read("fabric.mod.json"))
        add("mod-id", metadata.get("id") == "skyboxblock")
        add("test4-version", metadata.get("version") == "1.0.2-test.4", actual=metadata.get("version"))
        add("mit-license", metadata.get("license") == "MIT" and "LICENSE_skyboxblock" in names)
        for prefix in ("assets/skyboxblock/blockstates/", "assets/skyboxblock/models/block/",
                       "assets/skyboxblock/models/item/", "data/skyboxblock/recipe/",
                       "data/skyboxblock/loot_table/blocks/"):
            actual = sorted(Path(name).stem for name in names if name.startswith(prefix) and name.endswith(".json"))
            add(prefix + "sky-block-only", actual == ["sky_block"], actual=actual)
        add("no-preset-panorama-textures", not any(name.startswith("assets/skyboxblock/textures/sky/") for name in names))
        legacy_classes = ("SkyStyle", "AutoTest", "SkyProbe", "SkyProbeRenderType", "SkyGeometry", "SkyDeferredRenderer")
        stale = sorted(name for name in names if name.endswith(".class") and Path(name).stem.split("$")[0] in legacy_classes)
        add("no-legacy-sky-capture-classes", not stale, stale=stale)
        stale_shaders = sorted(name for name in names if "/shaders/core/" in name and Path(name).stem in
                               {"sky_probe", "sky_panorama", "style_probe", "void_probe"})
        add("no-legacy-capture-shaders", not stale_shaders, stale=stale_shaders)
        for shader in ("world_sky", "sky_resolve", "vanilla_background", "vanilla_celestial", "vanilla_stars", "vanilla_cloud"):
            location = "assets/skyboxblock/shaders/core/" + shader
            spec = json.loads(jar.read(location + ".json"))
            stages = ["assets/" + namespace + "/shaders/core/" + path + suffix
                      for key, suffix in (("vertex", ".vsh"), ("fragment", ".fsh"))
                      for namespace, path in [spec[key].split(":", 1)]]
            add("shader-" + shader + "-stages-present", all(stage in names for stage in stages), stages=stages)
        illegal_readbacks = sorted(name for name in names if name.startswith("dev/yg/skyboxblock/")
                                   and name.endswith(".class") and "RenderRegression" not in name
                                   and b"glReadPixels" in jar.read(name))
        add("production-has-no-world-color-readback", not illegal_readbacks, readbackClasses=illegal_readbacks)
        compat = jar.read("dev/yg/skyboxblock/client/ModCompat.class")
        add("iris-logical-target-scope-present", "dev/yg/skyboxblock/client/ModCompat$IrisTargetScope.class" in names
            and b"isMainBound" in compat and b"setIsMainBound" in compat)
        math_class = jar.read("dev/yg/skyboxblock/client/SkyRenderMath.class")
        clouds_class = jar.read("dev/yg/skyboxblock/client/SkyVanillaClouds.class")
        add("vanilla-cloud-height-192-constant", b"\x04\x43\x40\x00\x00" in math_class)
        add("cloud-height-not-read-from-shader-or-dimension", b"cloudRelativeY" in clouds_class
            and b"getCloudHeight" not in clouds_class)
        for language in ("en_us", "zh_cn"):
            language_data = json.loads(jar.read("assets/skyboxblock/lang/" + language + ".json"))
            block_keys = sorted(key for key in language_data if key.startswith("block.skyboxblock."))
            add(language + "-sky-block-only", block_keys == ["block.skyboxblock.sky_block"], keys=block_keys)
    result = {"jar": str(args.jar.resolve()), "sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              "bytes": args.jar.stat().st_size, "checks": checks,
              "passedChecks": sum(check["passed"] for check in checks), "totalChecks": len(checks),
              "passed": all(check["passed"] for check in checks)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"{result['passedChecks']}/{result['totalChecks']} {'PASS' if result['passed'] else 'FAIL'}")
    print("SHA256", result["sha256"])
    for check in checks:
        if not check["passed"]:
            print("FAIL", check)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
