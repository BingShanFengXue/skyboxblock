# R34 官方 DeepSeek V4.1 Flash 独立终审任务

## 审查约束

- 用户明确指定官方 `deepseek-official/deepseek-flash`（显示名 DeepSeek-V4.1-Flash）。只派 1 个终审个体，不参与开发。
- 先读工作区 AGENTS 和可用技能目录，按实际匹配使用技能／工具；本任务为 Java/OpenGL 模组审查，无 Office 交付。
- 只读生产源码、工具、实际 JAR 和证据；唯一允许新增的是终审报告。不要运行 Gradle，不改源码、构建产物、阈值或实机结果。
- 需要编译核验时只用工作区 `tools/verify-javac.ps1` 并以 Windows PowerShell 5.1 调用，先检查该工具是否针对本项目；不要套用其他项目脚本。

## 目标与范围

R33 基线后，本轮仅修复指定 ITRP 环境混合天空材质交界的大幅波动，优化无光影原版移动时的锯齿波浪。保留 R33 暗色曝光、R32 双点方向、4K 晴空和 A/B 名称。不把历史透明恢复四红、门户或资源性能长测算作本轮已解决。

## 主体实现

- `client/SkySurfaceLayer.java`：私有 4 样本 RGBA8+Depth32F framebuffer、解析为自有天空色与覆盖率、预乘 alpha 合成，按采样点保留最近的天空表面。
- `client/SkyWorldRenderer.java`：原版不透明阶段先生成覆盖率层，主目标仅写旧中心采样深度，再合成；已匹配 ITRP 的最终恢复使用私有层，其他光影保留旧路径。
- `sky_resolve.fsh/json`：仅私有层不按抖动的低分辨率材质 ID 丢弃显示像素，世界深度与前景保护仍保留。
- 新 `sky_surface_composite.*` 与 shader 注册；资源生命周期随缩放、断开和退出处理。
- `RenderRegression.java` 仅增加 opt-in edges 场景、平台内真实往返行走与资源路径元数据。R32 原有回归分支保持。

## 优先找反例

1. GL depth、sampler、FBO、renderbuffer、MSAA、blend、sample-mask、clip 状态恢复是否完整；私有近天空归属能否漏掉真实手部／玻璃／地形。
2. ITRP 原生／FSR2 的低分辨率所有权与显示几何解耦是否足够，还是只遮掩颜色差异；前后不同天空材质遮挡是否成立。
3. 原版深度先写、颜色覆盖率后合成是否真正改善边缘，是否出现深度／颜色错位或透明层回归；原版 DH 二次恢复有何限度。
4. 新层是否仅保存生成天空；R33 测光与 R32 方向类、PNG 是否真的逐字节保留。
5. 测试 oracle 是否独立于 renderer 的 owner/mask；基线与候选是否同包同配置，真实运动、相机范围、实际 jitter、移除／重载／缩放是否验证。是否有放宽阈值或漏掉无效场景。
6. 首轮 `r34-baseline-r1` 有测试角色走出地板的缺陷，已剔除、保留原证据；有效结果应以 `r34-edge-matrix.json` 和其中 `r34-fixed-r1`／`r34-control-r2` 同包配对为准。

## 证据入口

以主执行者最终交付报告、`build/reports/r34-edge-matrix.json`、`r34-edge-comparison.json`、`r34-regression-matrix.json`、`r34-package-audit.json` 和各 run 的 `fixture.json`／`run-result.json` 为入口；实际截图在 `build/render-regression/<label>/results/`。不要只读摘要就给结论，至少抽取不同环境与前景／缩放／退出帧交叉验证。

## 输出

写入 `docs/INDEPENDENT-R34-DEEPSEEK-READONLY-2026-10-24.md`，包含实际模型路由、读到的候选 JAR 哈希、审查方法、可复现阻断／非阻断问题（有具体行号）、独立复核结果与明确范围化结论。发现阻断时给证据，不自行修改；主线修复后由同一审查个体再次复核。
