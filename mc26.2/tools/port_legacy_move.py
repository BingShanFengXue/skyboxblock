#!/usr/bin/env python3
"""把「1.21.1 专用 / 已决策移出范围」的源码搬到 legacy 目录（移出编译，但保留源码）。

目录约定：
    src/main/java/dev/yg/skyboxblock/...        -> 参与 26.2 编译
    src/legacy-1.21.1/java/dev/yg/skyboxblock/... -> 仅留档，不参与编译

为什么用 Python 而不是 PowerShell：工作区红线规定不许用 PowerShell 读写 .java（GBK 解码不可逆）。
这里用 shutil.move，纯字节搬运，不经过任何解码。
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
SRC_ROOT = PROJECT / "src" / "main" / "java"
DST_ROOT = PROJECT / "src" / "legacy-1.21.1" / "java"

# 相对 src/main/java 的路径
LEGACY = [
    # ── 抓帧 / GL 状态机 / 自研云：自绘方案下不再需要 ──────────────────────────
    "dev/yg/skyboxblock/client/SkyGlState.java",
    "dev/yg/skyboxblock/client/SeamScan.java",
    "dev/yg/skyboxblock/client/SkyCloudStencil.java",
    "dev/yg/skyboxblock/client/SkyVanillaClouds.java",
    "dev/yg/skyboxblock/client/VanillaSkyScene.java",
    "dev/yg/skyboxblock/client/SkyTemporalEdgeMask.java",
    "dev/yg/skyboxblock/client/SkyCoverage.java",
    "dev/yg/skyboxblock/client/SkyExposureMeter.java",
    "dev/yg/skyboxblock/client/SkySurfaceLayer.java",
    "dev/yg/skyboxblock/client/SkyMesh.java",
    "dev/yg/skyboxblock/client/SkyCloudMesh.java",
    "dev/yg/skyboxblock/client/SkyVanillaGeometry.java",
    "dev/yg/skyboxblock/client/SkyWorldRenderer.java",
    # ── IP / Iris 兼容：移出范围 ────────────────────────────────────────────────
    "dev/yg/skyboxblock/client/ItrpSkyBridge.java",
    "dev/yg/skyboxblock/client/PortalViewCapture.java",
    "dev/yg/skyboxblock/client/ModCompat.java",
    "dev/yg/skyboxblock/client/DistantSkyDepth.java",
    # ── 旧回归 / 夹具 / 诊断：等主链路跑通后再按新 API 重写 ────────────────────
    "dev/yg/skyboxblock/client/RenderRegression.java",
    "dev/yg/skyboxblock/client/FixtureClientControl.java",
    "dev/yg/skyboxblock/client/FixtureDistantTerrain.java",
    "dev/yg/skyboxblock/client/FixtureStartup.java",
    "dev/yg/skyboxblock/client/BlockCollectionDiagnostics.java",
    "dev/yg/skyboxblock/client/SkyDisplayState.java",
    "dev/yg/skyboxblock/client/SkyTimeController.java",
    "dev/yg/skyboxblock/RegressionFluidControl.java",
    # ── mixin：除资源包注入外全部留档 ──────────────────────────────────────────
    "dev/yg/skyboxblock/mixin/BufferSourceMixin.java",
    "dev/yg/skyboxblock/mixin/CloudBuildRangeMixin.java",
    "dev/yg/skyboxblock/mixin/CloudMeshAccess.java",
    "dev/yg/skyboxblock/mixin/FogColorAccess.java",
    "dev/yg/skyboxblock/mixin/GameRendererMixin.java",
    "dev/yg/skyboxblock/mixin/IrisCompositeMixin.java",
    "dev/yg/skyboxblock/mixin/IrisExposureComputeMixin.java",
    "dev/yg/skyboxblock/mixin/IrisPipelineMixin.java",
    "dev/yg/skyboxblock/mixin/LevelRendererMixin.java",
    "dev/yg/skyboxblock/mixin/DistantBlockIgnoreMixin.java",
    "dev/yg/skyboxblock/mixin/RegressionFluidMixin.java",
    "dev/yg/skyboxblock/mixin/RegressionShaderTimeMixin.java",
    "dev/yg/skyboxblock/mixin/RegressionWaterTextureMixin.java",
]


def main() -> int:
    dry = "--dry-run" in sys.argv
    moved = 0
    missing = []
    for rel in LEGACY:
        src = SRC_ROOT / rel
        if not src.is_file():
            missing.append(rel)
            continue
        dst = DST_ROOT / rel
        if dry:
            print(f"would move {rel}")
            moved += 1
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
        moved += 1
    print(f"moved: {moved}")
    if missing:
        print("missing (already moved or never existed):")
        for m in missing:
            print("  " + m)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
