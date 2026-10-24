#!/usr/bin/env python3
"""Capture named vanilla sky/time bytecode from the project's actual compile classpath."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from local_paths import java_home  # noqa: E402

JDK = java_home()
cp = (ROOT / "build/compile-classpath.txt").read_text(encoding="utf-8").strip()
classes = ["net.minecraft.client.multiplayer.ClientLevel", "net.minecraft.world.level.Level",
           "net.minecraft.world.level.dimension.DimensionType",
           "net.minecraft.client.renderer.DimensionSpecialEffects$OverworldEffects"]
output = ROOT / "build/vanilla-sky-time-bytecode.txt"
with output.open("wb") as stream:
    for name in classes:
        stream.write(("\n=== " + name + " ===\n").encode("utf-8"))
        stream.flush()
        result = subprocess.run([str(JDK / "bin/javap.exe"), "-classpath", cp, "-p", "-c", name],
                                cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit(result.returncode)
print(output)
# Preserve the original UTF-16 PowerShell log while making the observed text readable by host tools.
original = ROOT / "build/steps1-test5-build.log"
if original.exists():
    raw = original.read_bytes()
    decoded = raw.decode("utf-16") if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else raw.decode("utf-8")
    (ROOT / "build/steps1-test5-build-utf8.log").write_text(decoded, encoding="utf-8")
