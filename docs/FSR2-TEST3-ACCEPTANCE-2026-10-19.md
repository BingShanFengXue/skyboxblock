# ITRP FSR2 与最终颜色修复验收（2026-10-19）

## 1. 交付结论

[skyboxblock-1.0.2-test.3.jar](<../build/libs/skyboxblock-1.0.2-test.3.jar>)，**74,964 字节**。

```text
SHA-256: 01f2b04a034a5b44d57df1709f579dcbb26a468b63eddc7044aadfac25334804
```

**实际使用 ITRP 测试，并核对最终显示颜色**。三组环境共 **84 个场景、1,162/1,162 项图像/深度/状态检查通过**；各隔离实例中的实际 jar、fixture 记录与交付包哈希一致。

| 最终构建验证 | 结果 | 运行证据与断言 |
|---|---|---|
| 完整参考模组组 + ITRP，保留用户 `FSR2_SCALE=2` | **28 场景，397/397**；192.22 秒；退出码 0 | [运行](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-full/run-result.json>)、[断言](<../build/reports/fsr2-test3-acceptance-r2-itrp-full.json>) |
| Iris + Sodium + ITRP，隔离配置中 `FSR2_SCALE=-1` | **28 场景，399/399**；125.45 秒；退出码 0 | [运行](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-no-fsr/run-result.json>)、[断言](<../build/reports/fsr2-test3-acceptance-r2-itrp-no-fsr.json>) |
| 无光影 Fabric 对照 | **28 场景，366/366**；105.50 秒；退出码 0 | [运行](<../build/render-regression/fsr2-test3-acceptance-r2-vanilla/run-result.json>)、[断言](<../build/reports/fsr2-test3-acceptance-r2-vanilla.json>) |
| Gradle 完整构建与数学回归 | 构建成功，19 秒；**55,017/55,017** 数学断言 | [构建日志](<../build/fsr2-test3-acceptance-r2-build.log>) |
| 最终包审计 | **20/20** | [包审计](<../build/reports/skybox-test3-package-audit.json>) |
| 三组实机证据身份/完整性复核 | **20/20** | [最终汇总](<../build/reports/fsr2-test3-final-acceptance.json>) |

三组最终天空颜色检查的**最大采样 RGB 误差均为 0**。每组均完成资源重载、缩放和真实第一人称持物测试，不用成功退出或 gbuffer 覆盖代替最终图像验收。当前只保留 `sky_block`；用户世界、原实例 jar 与用户光影设置均未改动。本轮未派发子个体。

## 2. 问题与原因

### 2.1 手持物缺口、柱体遮挡错位

ITRP 在完整 **1280×800** 附件左下角使用 **752×470** 有效光栅，运行时 `fsrRenderScale=(0.5875,0.5875)`；实际 GL viewport 仍是完整附件。按输出尺寸读取深度，或同时缩小 GL viewport 和 shader 坐标，会造成天空面与世界深度错位。

旧架构还在 Iris 第一人称手部之后使用深度，并以旁观者/隐藏手部条件回归，遗漏了用户报告的真实条件。

修复使用实际世界矩阵、运行时 FSR 比例和 TAA jitter，在不透明阶段、手部之前绘制真实世界空间面；有效光栅外的片元丢弃，禁止向 FSR 非活动区域写入颜色或深度。

### 2.2 方块灰白、看起来像纯白色

几何覆盖通过不等于颜色正确。早期 gbuffer 中的蓝天被光影当普通受光材质继续处理；历史侧移采样的独立天空约 `[186,212,255]`，最终约 `[160,161,169]`，蓝色饱和度明显丢失。

修复保留早期世界空间深度/颜色阶段，Iris 最终合成后用同一组世界空间面恢复独立天空颜色。最终阶段采样**手部绘制后的深度**，包含 `uv * rasterScale + jitter * 0.5` 映射，保留真实前景与手持物。

低分辨率世界天空与全分辨率显示天空分别缓存，避免每帧在两种尺寸之间重建目标；日月星使用正确的透明/加法混合，避免黑色贴图方框。正常运行只复制深度，**不读取世界颜色来生成天空**。

### 2.3 资源重载与前景诊断

- 完整模组组早期 `sky-occluded` 截图仍显示旧地形，资源工作线程尚未完成。fixture 现在等待 `reloadResourcePacks()` 的真实 future 完成，再稳定等待；全部最终元数据均为 `resourceReloadPending=false`。
- 原版对照的选择框和雨丝是真实前景，不应与纯天空参考逐像素相等。保持选择框和降水正常开启，在实际批次提交与降水绘制前后记录独立差分掩码；没有根据“天空与参考的差值”生成排除区。
- 字节码核对显示，普通图形模式在线框 `endLastBatch` 才提交选择框，单看顶点生成或其他 `endBatch` 会漏掉。诊断已覆盖真实提交点。最终原版组 **366/366**，包括雨丝实际绘制证据。
- 以上诊断仅在显式回归入口启用，正常模式没有测试颜色读回。

## 3. 最终画面

### 同一实机帧：颜色恢复前 / 后

以下不是不同天气或相机的拼接；两侧来自同一帧的合成前后，世界与手部不变。

![ITRP FSR2=2，同帧颜色恢复前后](<screenshots/fsr2-test3-color-before-after-20261019.png>)

### 手持砂岩与柱体侧移

![手持砂岩，最终蓝天保留，选择框保留](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-full/results/bug-panel-solid-hand.png>)

![侧移后柱体保留前景遮挡](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-full/results/bug-pillar-side-left.png>)

[完全遮挡最终图](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-full/results/sky-occluded.png>)、[夜空最终图](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-full/results/sky-night.png>)、[原版选择框独立掩码](<../build/render-regression/fsr2-test3-acceptance-r2-vanilla/results/bug-panel-solid-hand-world-overlay-mask.png>)、[原版雨丝独立掩码](<../build/render-regression/fsr2-test3-acceptance-r2-vanilla/results/sky-rain-world-overlay-mask.png>)。

主对照图、持物、柱体与独立掩码均已实际读取检查。

## 4. 检查方法与环境

### 场景

- **19 个问题场景**：7 个面板主/副手场景；柱前近/远/更远、持物切换、左右侧移、转头、窗口缩放/恢复与清理。
- **9 个天空场景**：白天、夜晚、资源重载、窗口缩放、雨天、放晴、实心墙完全遮挡、方块内部与清理。
- 第一人称、创造模式、`hideGui=false`；测试相机锁定，避免资源重载时 OS 焦点变化造成方向漂移。

### 独立判据

1. 用 NumPy 射线/AABB 参考核对**有效光栅**中的可见面；以深度变化判定绘制，避免同色 RGB 像素误报缺面。
2. 所有场景非活动光栅颜色/深度写入为 **0**，超出可见轮廓的泄漏为 **0**，可见内部覆盖完整；轮廓 IoU 至少 **0.95**，边缘允许 2 像素光栅容差。
3. 最终天空颜色最大 RGB 容差 **1/255**，三组实际均为 **0**。Iris 通过独立前后深度识别真实前景；原版通过实际前景绘制前后颜色记录识别选框和降水。
4. 排除真实前景后，采样量仍至少达到天空内部面积的 **40%** 且不少于 32 像素；防止空采样或过宽掩码通过。
5. Iris 手部保护另行比较恢复前后像素，误差要求 **0**；最终颜色的世界越界另行检查。
6. 汇总工具核对三组实际 jar、声明哈希与交付包一致，所有 28 场景完成、无未完成重载、无致命或渲染线程错误。

主要版本：MC 1.21.1、Fabric API 0.116.15+1.21.1、Iris 1.8.1、Sodium 0.6.7、DH 3.3.3。IP 文件名 6.0.7，实际加载元数据 6.0.6，按运行事实记录。

完整组[运行清单](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-full/fixture.json>)保留用户 FSR2 选项，设置 SHA-256 为 `c52eb82680fb6b19c17c742512de22caa2eb64c5310734b13826f7a061505a88`。[FSR 关闭组](<../build/render-regression/fsr2-test3-acceptance-r2-itrp-no-fsr/fixture.json>)仅在隔离配置中覆盖为 `-1`；[原版组](<../build/render-regression/fsr2-test3-acceptance-r2-vanilla/fixture.json>)无光影。

完整组保留 **61 条第三方 ERROR 日志行**，包括数据修复器、Yuushya 模型/配方、Trail and Tales 数据与 IP/C2ME 区块加载；**致命错误 0、渲染线程错误 0**。FSR 关闭组及原版组 ERROR 均为 0。不把完整组描述为整包零 ERROR。

## 5. 失败对照与历史证据

历史证据不覆盖、不清理，不混作当前交付验证：

- **最新严格检查器的灰白负对照**：[223/235，12 个预期最终颜色失败](<../build/reports/fsr2-test3-final-oracle-negative.json>)。柱体侧移的色差 MAE 仍约 55–58，确认前景排除没有掩盖真正灰白问题。
- [未保护非活动光栅](<../build/reports/fsr2-itrp-inactive-negative.json>)：208/222，14 项预期失败；面板约向非活动区写入 498,560 个颜色/深度像素。
- [前期完整组世界阶段](<../build/reports/fsr2-takeover-itrp-r2-world.json>)：325/328，3 项完全遮挡失败；后续等待真实重载 future 修正 fixture。
- [原版初次最终颜色检查](<../build/reports/fsr2-test3-final-vanilla.json>)：339/347，选框与雨丝被当作天空颜色错误；[中间诊断](<../build/reports/fsr2-test3-acceptance-vanilla.json>)为 359/366，仅漏截的 7 个选框。最终以真实 `endLastBatch` 提交证据修正，未放宽 RGB 或最小面积阈值。
- [前一版已通过 ITRP 的包](<../build/baselines/skyboxblock-1.0.2-test.3-before-vanilla-oracle.jar>)保留，哈希 `9047123d16c491817ac8617353fbe0a16d5689126369c23923fcaa949479ce0d`。交付前完善了原版诊断，最后重新跑齐三组，当前交付只以本报告首表为准。

前期 4G 完整组在退出阶段出现过堆内存不足；最终完整组使用 8G，两个对照组使用 6G。本轮曾出现一次 JVM 内部崩溃，按工作区要求先原样重跑后成功；未据此修改无关源码。

## 6. 关键源码与复现

- [世界面、早期深度与最终颜色恢复](<../src/main/java/dev/yg/skyboxblock/client/SkyWorldRenderer.java>)。
- [Iris FBO 与 FSR/TAA 坐标适配](<../src/main/java/dev/yg/skyboxblock/client/ModCompat.java>)。
- [独立背景、日月星与双尺寸天空目标](<../src/main/java/dev/yg/skyboxblock/client/VanillaSkyScene.java>)、[投影数学](<../src/main/java/dev/yg/skyboxblock/client/SkyRenderMath.java>)。
- [最终深度匹配 shader](<../src/main/resources/assets/skyboxblock/shaders/core/sky_resolve.fsh>)、[世界阶段 shader](<../src/main/resources/assets/skyboxblock/shaders/core/world_sky.fsh>)。
- [回归、重载等待、颜色/深度诊断](<../src/main/java/dev/yg/skyboxblock/client/RenderRegression.java>)、[真实前景提交点](<../src/main/java/dev/yg/skyboxblock/mixin/LevelRendererMixin.java>)。
- [隔离运行器](<../tools/render-regression.py>)、[图像/深度检查器](<../tools/check-world-regression.py>)、[包审计](<../tools/verify-skybox-package.py>)、[三组身份复核](<../tools/verify-render-acceptance.py>)、[同帧对比图工具](<../tools/make-resolve-comparison.py>)。

本会话技能目录无 Minecraft 渲染专项，沿用项目 Gradle、隔离运行器与 NumPy/Pillow 检查工具。微软/Mojang JDK 21.0.3 由构建脚本选择；项目 PowerShell 脚本用 Windows PowerShell 5.1 调用。

工作目录为项目根，`python` 应具备 NumPy/Pillow。下列 label 是本次证据，复跑时使用新的唯一 label。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build

python tools/render-regression.py --profile full --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --suite all --heap 8G --label fsr2-test3-acceptance-r2-itrp-full --timeout 600
python tools/check-world-regression.py build/render-regression/fsr2-test3-acceptance-r2-itrp-full/results --output build/reports/fsr2-test3-acceptance-r2-itrp-full.json

python tools/render-regression.py --profile iris --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --shader-option FSR2_SCALE=-1 --suite all --heap 6G --label fsr2-test3-acceptance-r2-itrp-no-fsr --timeout 360
python tools/check-world-regression.py build/render-regression/fsr2-test3-acceptance-r2-itrp-no-fsr/results --output build/reports/fsr2-test3-acceptance-r2-itrp-no-fsr.json

python tools/render-regression.py --profile vanilla --suite all --heap 6G --label fsr2-test3-acceptance-r2-vanilla --timeout 360
python tools/check-world-regression.py build/render-regression/fsr2-test3-acceptance-r2-vanilla/results --output build/reports/fsr2-test3-acceptance-r2-vanilla.json

python tools/verify-skybox-package.py
python tools/verify-render-acceptance.py build/render-regression/fsr2-test3-acceptance-r2-itrp-full build/render-regression/fsr2-test3-acceptance-r2-itrp-no-fsr build/render-regression/fsr2-test3-acceptance-r2-vanilla --output build/reports/fsr2-test3-final-acceptance.json
```

旧抓帧、预设及 CPU/NDC 变形路径已删除；回归入口默认关闭。当前仅正常绘制独立天空与复制深度，测试颜色读回需显式开启回归。

## 7. 备份、安装与剩余边界

- [本轮源码、工具、jar 与验收证据快照](<../../artifacts/snapshots/skybox-test3-itrp-acceptance-20261019-final.zip>)，归档只新增，不覆盖历史。
- [重构前快照](<../../artifacts/snapshots/skybox-before-vanilla-rewrite-20261019-011602.zip>)、[FSR2 接手快照](<../../artifacts/snapshots/skybox-fsr2-takeover-20261019-034900.zip>)、[用户原始包](<../build/baselines/skyboxblock-1.0.1-user-reported.jar>)均保留。
- 本次未自动替换参考实例 jar。安装时以[交付包](<../build/libs/skyboxblock-1.0.2-test.3.jar>)替换旧同 ID 版本，避免双版本；含已删除旧方块的世界先备份。
- **门户内部最终颜色恢复、玻璃/水等透明前景、实际 DH LOD 遮挡**仍待专项验证。其他光影包未在本轮架构上复测；旧 Complementary 通过数不作为本版兼容结论。
