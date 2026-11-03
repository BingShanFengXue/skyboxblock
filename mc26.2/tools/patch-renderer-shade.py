#!/usr/bin/env python3
"""把 shade（顶点亮度）参数串进 SkyBlockEntityRenderer 的 face/vertex 调用链。

shaderMode 1 = 纯白、2 = 纯黑：1.21.1 里这两个风格没有贴图，只用顶点色；
26.2 复用同一张纯白贴图，靠顶点色区分黑白。
"""
from pathlib import Path

TARGET = Path(__file__).resolve().parent.parent / "src/main/java/dev/yg/skyboxblock/client/SkyBlockEntityRenderer.java"


def main() -> int:
    text = TARGET.read_text(encoding="utf-8")
    counts = {}

    def swap(old: str, new: str, label: str) -> None:
        counts[label] = text.count(old)
        return None

    for old, label in (
        ("camera, state, panorama, bx, by, bz", "call-sites"),
        ("SkyBoxRenderState state, boolean panorama, float bx, float by, float bz,", "face-signature"),
        ("vertex(pose, vertices, camera, panorama, bx, by, bz,", "vertex-calls"),
        ("boolean panorama, float bx, float by, float bz, float x, float y, float z,", "vertex-signature"),
    ):
        counts[label] = text.count(old)

    text = text.replace("camera, state, panorama, bx, by, bz", "camera, state, panorama, shade, bx, by, bz")
    text = text.replace("SkyBoxRenderState state, boolean panorama, float bx, float by, float bz,",
                        "SkyBoxRenderState state, boolean panorama, int shade, float bx, float by, float bz,")
    text = text.replace("vertex(pose, vertices, camera, panorama, bx, by, bz,",
                        "vertex(pose, vertices, camera, panorama, shade, bx, by, bz,")
    text = text.replace("boolean panorama, float bx, float by, float bz, float x, float y, float z,",
                        "boolean panorama, int shade, float bx, float by, float bz, float x, float y, float z,")
    text = text.replace("                .setColor(255, 255, 255, 255)",
                        "                .setColor(shade, shade, shade, 255)")

    TARGET.write_text(text, encoding="utf-8")
    for label, n in counts.items():
        print(f"{label}: {n}")
    print("setColor swapped:", "shade" in text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
