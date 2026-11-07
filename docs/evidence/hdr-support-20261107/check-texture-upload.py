"""把「纹理上传链路的位宽」也钉成证据。

模组把全景图交给 Minecraft 的纹理管理器（1.21.1 走 TextureManager/AbstractTexture →
NativeImage.upload；26.2 走 GpuTexture/GlTexture + GlConst.GL_RGBA8）。两代实现都只把
图像当 8bit RGBA 上传，从 16bit 源图到 GPU 的中间没有任何浮点或半浮点纹理路径。
这里不伪造结论，只把可核查的字节码证据落盘。
"""

import re
import subprocess
import zipfile
from pathlib import Path

JAVAP = r"C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe"
WORKSPACE = Path(r"E:\DSHworkspace\DSHminecraft")
J121 = WORKSPACE / "skybox-mod/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-f556725616/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-merged-f556725616-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar"
J262 = WORKSPACE / "skybox-mod-26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a8e9c7b123/26.2/minecraft-merged-a8e9c7b123-26.2.jar"


def javap(jar: Path, cls: str) -> str:
    result = subprocess.run([JAVAP, "-p", "-c", "-classpath", str(jar), cls],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    return result.stdout + result.stderr


def summarize(name: str, text: str, patterns: list[str]) -> dict:
    hits = {}
    for pattern in patterns:
        found = sorted({m.group(0) for m in re.finditer(pattern, text)})
        hits[pattern] = found
    return {"target": name, "patterns": hits}


def main():
    report = []

    text = javap(J121, "com.mojang.blaze3d.platform.NativeImage")
    report.append(summarize("1.21.1 NativeImage._upload", text,
                            [r"glTexSubImage2D", r"glTexImage2D", r"\b5121\b", r"\b32856\b", r"\b6408\b"]))
    text = javap(J121, "com.mojang.blaze3d.platform.NativeImage$Format")
    report.append(summarize("1.21.1 NativeImage.Format <clinit>", text,
                            [r"sipush\s+6408", r"sipush\s+6407", r"sipush\s+6403", r"bipush\s+8", r"bipush\s+16"]))
    text = javap(J262, "com.mojang.blaze3d.opengl.GlTexture")
    report.append(summarize("26.2 GlTexture", text,
                            [r"glTexImage2D", r"glTexSubImage2D", r"\b5121\b", r"\b32856\b"]))
    text = javap(J262, "com.mojang.blaze3d.opengl.GlConst")
    report.append(summarize("26.2 GlConst", text,
                            [r"int 32856", r"GL_RGBA8", r"int 34842", r"GL_RGBA16F"]))

    for entry in report:
        print(entry["target"])
        for pattern, found in entry["patterns"].items():
            print(f"   {pattern:<24} -> {found if found else 'NONE'}")
    out = WORKSPACE / "tmp-hdr-support-probe/results/texture-upload-evidence.txt"
    out.write_text("\n".join(f'{e["target"]}: {e["patterns"]}' for e in report), encoding="utf-8")
    print("written", out)


if __name__ == "__main__":
    main()
