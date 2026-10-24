# R34 独立只读终审（官方 DeepSeek-V4.1-Flash）

审查日期：2026-10-24（Asia/Shanghai）
审查个体：本个体为只读终审，不参与开发；未派子个体，未运行 Gradle，未修改生产源码、测试阈值、JAR、既有证据或用户实例。唯一新增文件为本报告。

记录状态：本报告保留三版历史，旧判定不删除：
- 初审：旧包 `787b5636955c3e3ae83e922a828417afae5062292179f9b454d6494d92dc48b2`（§0–§7；当时 B1 为 DH 双合成主张阻断）。
- 中间修复 r34.1/Bec `bec71c6bf0bc291441e50e1a4f559b764ddf257ca177544360c6ce4caf95ad69`：定向 DH 反例 `final==pre` 0 改动，但 649/1347 被 DH 改写的 partial 仍保留第一次合成背景（§8 待验记录）。
- **最终 r34.2 `55337b11fb3e268cb972bf9873bbd77aa25fc8481ddb8054d057224e6f61c022`：已复审通过（声明范围内）；见下方“最终 r34.2 复审结论”与 §9。**

## 最终 r34.2 复审结论（收尾）

**在声明范围内通过（测试候选，不是发布/性能验收），无阻断。** 最终候选 `1.0.3-test.4-r34.2`：本个体亲算 SHA-256 = **`55337b11fb3e268cb972bf9873bbd77aa25fc8481ddb8054d057224e6f61c022`**，16,998,907 字节；`build/r34-2-native-background-build.log` 成功 389385 断言；`r34-2-package-audit.json` **94/94**，无新增/删除成员，变更仅 native repair + regression metadata，manifest 仅版本。`r34-2-final-acceptance.json`：**612 正向场景 / 4191 检查、360 同包控制、972 总计**；10 组 edge 配对（含 Iris/Sodium+DH 关光影实际组合）+ 全部 11 组旧回归通过；仅 `r34-2-regression-materials-dh` 一条 Yggdrasil 外部网络失败，fatal/renderThread 全空，Bec 的 DH regen SQLException 已不再出现。

核心量化（原始值复算）：纯原版移动外轮廓 **4.809367→0.133079（97.23%）**；native DH **4.504651→0.140853（96.87%）**；Iris/Sodium+DH 关光影 **4.637353→0.136950（97.05%）**；ITRP 原生/FSR 混材交界 32 帧×17212 掩码 **6.542775 / 11.007715→0**；手臂白剪切 0%。

**上一轮 B1（DH 理想单次合成）已在受测 native DH 3.3.3 路径解决**：`SkySurfaceLayer.nativeBackgroundColor` 的 owner/尺寸/格式/API 校验 + `SkyWorldRenderer.java:387` 在末段 `render()` 之前捕获 `surfaceLayerUsed`，仅当 opaque 已合成时进入 `composite(..., fullCoverageOnly=true, nativeDepth, nativeColor)` 分支（`:388-395`）；`sky_surface_composite.fsh:10-22` 对 partial 输出 `ownPremulSky+(1-a)*未污染DH背景` 并以 alpha=1 替换，不读已合成 MC 色、不再乘 a；无 DH 内容 discard 保留 first，alpha0 保留前景，API 不满足回退原中心归属。独立 LOD 复核：sky-front **1347 partial / 939 有 DH 背景 / 408 无背景保留 / 561 实际修复**；939 背景 partial 相对同帧干净 DH 背景的理想一次合成 **MAE 0.226079 级（报告 0.226081）、最大 <0.6 级**；sky-behind 81/44/37/0，MAE 0.200147；物理金墙 holdout 112 像素：API 背景对金墙 0.9345、最终一次合成 0.7763（报告值，量级 <1）。`nativeBackgroundReady=true` 覆盖 118/118 固定 native DH sky 场景与 59/59 Iris/Sodium+DH 关光影，无静默 fallback；6 场景 smoke 0 错误。Bec 旧 ~30.47 级背景误差已被真实修复，不只是跳过。

范围边界（与最终 `limits` 一致）：4x 生成天空几何，不是全场景 MSAA/TAA；native 背景重建锁定已验证 DH 3.3.3 apply-source/depth，格式/尺寸/owner 不符走原中心归属；Iris 外轮廓 partial 不在混材 all-sky MAE 内；固定 shader 测试 `MOTION_BLUR=false`；历史严格恢复四红、门户、资源/性能长测仍不在本验收内。上述均不构成本轮阻断。

§0–§8 为 r34 初审与 r34.1/Bec 待验历史，保留原文；最终复核证据与行号见 §9。

## 0. 可直接引用的判定（仅对旧包 787b…）

（历史：以下为 r34 初审结论；其中 B1 已由 r34.2 的 native 背景合成修复，最终判定见顶部与 §9。）

- **R34 两项指定目标未被阻断**：
  1. 指定 ITRP 环境不同天空材质交界的波动：同包 32 帧、23 个实际 jitter（原生 16 个）下，独立几何掩码内交界平均时序标准差由 **10.98821 / 6.53484 → 0.0**；旧路径在该掩码内有 4878 / 2689 个像素仍在跳变（最大 127.5 级）。本个体用原始 PNG 独立重算，逐位复现。
  2. 无光影 Fabric 原版移动硬边：移动外轮廓 4.81270 → **0.13277**（97.24%）；同一 G4 掩码下 walk-16 单帧固定 0.06747、对照 3.19750；静态 0.80734 / 4.62313。该指标是边缘误差，不是整屏画质或帧率。
- **对更强主张构成阻断（主张层面，不是代码提交层面）**：无光影 + Distant Horizons 的“新覆盖率路径已达到理想一次合成”不成立。实拍阶段差分证明该环境存在 **opaque 阶段一次预乘 alpha 合成 + 末段 `resolveView` 第二次预乘 alpha 合成**；第二次合成只在部分覆盖像素生效，全部使边缘更偏向天空色。它解释了该环境几乎全部边缘残差（详见 §4.4），且 24 个移动场景中有 1 个比旧路径更差。
- 因此：**R34 可以按“两项指定目标达成、DH 为带已知残差的补充环境”范围化通过；不能按“DH 也达到理想单次合成/无边缘残差”通过。** 97.24% 只适用于纯原版无光影，不可泛化到 DH（交付报告已如此写，本审查确认该范围声明正确）。
- 非阻断但需澄清的文档/字段问题见 §5：`renderingErrors=0` 的命名与硬编码、`positiveImageChecks` 命名、最终汇总 `limits` 未列 DH 限制、DH 移动 1/24 场景劣于旧路径未写入机器可读汇总。

## 1. 身份核对（本个体亲自计算）

| 项目 | 值 |
|---|---|
| 实际派发路由 | `deepseek-official/deepseek-flash`（显示名 DeepSeek-V4.1-Flash），按用户指定与父会话派发信息；本个体运行时不暴露可自省的计费路由字段，故路由以派发记录为准 |
| 候选 JAR | `skybox-mod/build/libs/skyboxblock-1.0.3-test.4-r34.jar` |
| 本个体 `Get-FileHash -Algorithm SHA256` | **`787b5636955c3e3ae83e922a828417afae5062292179f9b454d6494d92dc48b2`**（与交付、最终汇总、各 fixture、各 run 一致） |
| JAR 大小 / mtime | 16,996,027 字节 / 2026-10-24T00:03:00.386+08:00 |
| `fabric.mod.json` 版本 | `1.0.3-test.4-r34` |
| 构建日志 | `build/r34-candidate-build-r4.log`，mtime 00:03:04，`BUILD SUCCESSFUL`，`PASS: 389385 assertions` |
| 冻结源码 ZIP | `artifacts/snapshots/skybox-r34-candidate-before-review-20261024.zip`，SHA-256 `dbb3257a054f3314b9d4677f1e714691167331ad5d85775cd60149fea886fe76`，33,894,932 字节 |
| ZIP 内源码/资源 vs 当前工作区 | **151/151 字节一致**（0 差异、0 缺失；覆盖 `src/main` 的 `.java/.json/.fsh/.vsh`）；主线另报包含其他文件的 167 项全一致，本个体只对上述过滤子集做字节比较 |
| 本次审查写入的文件 | 仅本文件；未写任何临时脚本/图片/JSON；数值分析均以内联 Python（Pillow/NumPy）在内存完成 |

来源绑定补充：`-sources.jar` 是 Loom 重映射后的 intermediary 源码（`class_5944` 等），不能与工作区源码逐字节比较；改用字节码行号表交叉验证：`SkySurfaceLayer.class` 的 `available` 行号 17–19、`render` 起始行 23 与工作区源码一致，`SkyWorldRenderer`/`SkySurfaceLayer` 的字符串常量（如 `skyboxblock.regression.negativeSurfaceLayer`、`SurfaceLayer`）在类中可见。这支持“JAR 由同布局源码编译”，但不是逐字节证明；包审计对 152 个未改资源 + 4 个保留类的字节约束见 §3.1。

## 2. 审查范围与方法

- 已读：`AGENTS.md`、会话技能目录（仅 Office 类技能，与本 Java/OpenGL 审查不匹配，未加载；无匹配 MCP，未调用）、`R34-READONLY-AUDIT-BRIEF`、`EDGES-R34-DELIVERY`、`EDGES-R34-WORKLOG`、`r34-final-acceptance.json`、`r34-edge-matrix.json`、`r34-edge-comparison.json`、`r34-regression-matrix.json`、`r34-dh-edge-matrix.json`、`r34-static-pose-pairs.json`、`r34-package-audit.json`、`r34-coverage-properties.json`、`r34-oracle-slab-equivalence.json`、`r34-oracle-counterfactual-final.json`、`r34-dh-fixed/control` 与 `r34-fixed/control` 的逐场景 JSON、原始 PNG、`-before/-opaque/-pre-resolve/-post-hand-mask` 阶段图、各 run 的 `fixture.json`/`run-result.json`/`client.log`。
- 源码：`SkySurfaceLayer.java`、`SkyWorldRenderer.java`、`SkyGlState.java`、`SkyMesh.java`、`SkyCoverage.java`、`ItrpSkyBridge.java`、`SkyTemporalEdgeMask.java`、`VanillaSkyScene.java`、`SkyboxBlockClient.java`、`LevelRendererMixin.java`、`GameRendererMixin.java`、`IrisPipelineMixin.java`、`IrisCompositeMixin.java`、`sky_resolve.fsh/json`、`sky_surface_composite.fsh/vsh/json`、`sky_resolve` R33→R34 差分。
- 独立重算：JAR/冻结包哈希；JAR 条目集合与变更；4K PNG IHDR 与 R33 字节比较；4 个保留类字节比较；shader ZIP 全 SHA；32 帧逐像素时序 STD；G4 几何掩码与 16 点面积积分；阶段差分（`final` vs `pre-resolve` vs `opaque`）；覆盖/前景/屏幕边界排除；逐场景 MAE 配对；全量场景 JSON 的 GL/越界扫描；全量日志 ERROR 扫描。
- 未做：Gradle、实机重跑、修改任何证据、图片或阈值。DH 残差的结论来自既有实拍阶段图与源码，可复现但未新采集。

## 3. 独立核对结果

### 3.1 包身份与变更范围

- R33→R34 JAR 条目：**新增 4 个**（`SkySurfaceLayer.class`、`sky_surface_composite.{fsh,vsh,json}`），**修改 10 个**（`SkyWorldRenderer(.$Frame)`、`SkyboxBlockClient`、`RenderRegression(.$Scene/.$WorldCapture)`、`sky_resolve.{fsh,json}`、`MANIFEST.MF`、`fabric.mod.json`），**删除 0 个**。全部修改项都落在 `verify-r34-package.py:10` 声明的允许范围内；`fabric.mod.json` 仅版本变化。
- 包审计 `161/161`：152 项为“与 R33 字节相同”的类/资源（`.class/.png/.fsh/.vsh/.json`，排除允许变更项与 `fabric.mod.json`），另 9 项为版本、两张 4K PNG、四个保留类、预乘 blend、无验证探针。本个体独立抽查：
  - `kloppenheim.png`、`syferfontein.png`：IHDR 均为 **4096×2048**，与 R33 JAR 字节相同；
  - `SkyExposureMeter/ItrpSkyBridge/SkyTemporalEdgeMask/SkyRenderMath` 四个 `.class` 与 R33 字节相同；
  - `sky_surface_composite.json` blend 为 `one / 1-srcalpha / zero / one`，与源码和属性测试一致；
  - 两种光影模式实际 ZIP 全 SHA-256 均为 `334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8`。
- 控制/修复 fixture 除 `negativeSurfaceLayer` 外逐字段相同（fsr2/native/vanilla/dh 四组均如此，本个体逐键 diff 验证）；DH 启动重试 fixture 与 `r34-control-r2-fsr2` 在 jar、shader、shaderOptions、mods、profile、negativeSurfaceLayer 上完全相同。
- 源码冻结包 151/151 与工作区一致；构建日志与 JAR 时间一致。**残余审计限制**：重映射 sources jar 不能直接证明 10 个修改类与冻结源码逐字节对应；已用行号表/字符串做交叉验证，最终仍依赖主线的“构建后未改”声明 + 包审计的未改项字节约束。

### 3.2 ITRP 混材交界（目标 1）

- 独立时序重算（掩码由 `check-r34-edges.py` 的几何 ownership 生成，统计只用原始 PNG）：`edge-mixed-static-00..31` 共 32 帧，掩码 **17212** 像素，`sameCamera` 相机/逆 VP 差 **0.0**，distinct jitter **23**（native **16**）。
  - FSR2 固定：平均 STD **0.0**，最大 **0.0**，>0.5 的像素 **0**。
  - FSR2 旧路径：平均 STD **10.988205909729004**，最大 **127.5**，>0.5 的像素 **4878**。
  - 原生固定：**0.0 / 0.0 / 0**；原生旧路径：**6.5348429679870605**，最大 127.5，2689 像素。
  - 这逐位复现了交付的 10.98821→0、6.53484→0；固定路径在掩码内逐帧完全相同，不是“只把配置开关关掉”。
- 边缘 MAE（`r34-fixed-r1-fsr2-oracle.json` / `r34-control-r2-fsr2-oracle.json`，本个体用同一 checker 内部实现复算一致）：
  - mixed 静态：**7.66866 → 1.04100**（-86.43%）；mixed 移动：**10.69560 → 0.35647**（-96.67%）；
  - native 混材静态：**5.90614 → 1.04100**（-82.37%）；移动：**7.29285 → 0.35359**（-95.15%）；
  - `edge-mixed-static-00` 大误差占比 0.0699 → **0.0028**，内部原色 MAE 0.2005/0.2154（阈值 <2）。
- 机制核对：R33→R34 `sky_resolve.fsh` 的差异只有新增 `uniform int SurfaceLayer` 与把低分辨率材质 ID 丢弃条件改为 `SurfaceLayer == 0 && ...`（`sky_resolve.fsh:46-47`）；深度指纹、DH 距离、前景历史、9 邻域保护未改。私有层显示分辨率逐样本深度决定前后归属，`fragColor = vec4(skyColor(uv), 1.0)`（`sky_resolve.fsh:90`），未覆盖样本保留清屏 (0,0,0,0)，因此 MSAA 解析后的 RGB 天然是覆盖率预乘、alpha 是覆盖率（`SkySurfaceLayer.java:62-77` 用 `ONE, ONE_MINUS_SRC_ALPHA` 合成）。
- 重要范围事实：ITRP 的“混材交界”两侧面板共面且铺满像素，私有层在该像素 4 个样本全部有天空归属，**alpha=1**；第二次合成对 alpha=1 的像素是恒等替换（dst 系数 1-1=0），所以“两阶段 alpha”不会污染 ITRP 的混材交界本身。这与实测 0 时序 STD、内部原色保持、1.04 的 4x-vs-16点量化残差一致。
- 范围注记：`check-r34-edges.py:88-91` 对 ITRP 只把 `allsky`（覆盖率>0.999）像素计入 `valid`，因此 ITRP 的外轮廓部分覆盖像素不在该 MAE 中；本轮目标本来就是混材交界，报告没有把该数字外推到 ITRP 外轮廓 AA。若未来要主张 ITRP 外轮廓，需要另测。

### 3.3 无光影原版移动边缘（目标 2）

- 单合成路径确认：vanilla 的 `-pre-resolve.png` 与 `-opaque.png` 逐像素相同（MAE 0.0，max 0），`finalResolved=false`，`surfaceLayerUsed=true, samples=4`；没有第二次合成。因此 97.24% 的测量对象就是一次预乘 alpha 合成。
- 逐帧抽查（本个体用 checker 的几何掩码独立复算，和报告数值一致）：walk-16 固定 **0.067466**、对照 **3.19750**；static-00 固定 **0.80734**、对照 **4.62313**；两者 `largeErrorFraction=0`、`interiorMAE=0`。24 个 walk 场景均值 0.13277 vs 4.81270（97.24%），静态 0.80734 vs 4.62313（82.54%）。
- 16 点面积参考与 4 点硬件覆盖的差异被限制在外轮廓一两个像素带内；检查器用 16 子像素面积积分 + `before.png` 背景，反事实（保持元数据/4x 标志、仅平移 RGB 40 像素）真实检出 **2** 项失败：内部原色 MAE 22.945、边缘 MAE 80.927、大误差占比 0.4969（`r34-oracle-counterfactual-final.json`）。说明该 checker 不是读元数据放行。
- 独立求交优化：90 万组（含零方向、边界原点）与原向量归约 **逐 IEEE 位相等**（`test-r34-slabs.py`）；覆盖率属性 10 万样本、最近表面四种遍历顺序一致、预乘恒等式通过，旧 alpha 平方错误被 **80813** 例检出（`r34-coverage-properties.json`）。
- 初版 oracle 漏地板：旧报告 `r34-fixed-r1-fsr2-oracle-before-platform-model.json` 保留，唯一失败为 `bounded geometric edge error` 9.1716、largeErrorFraction 0.16046（>0.16）；修正只把真实砂岩平台加入遮挡盒体，阈值不动，同包重查 `r34-fixed-r1-fsr2-oracle.json` 0 失败。这与执行记录一致。

### 3.4 无光影 + DH 的两阶段 alpha（重点额外审查）

**源码路径（明确行号）**

1. 末段恢复触发：`SkyWorldRenderer.endView()` 在 `LevelRenderer.renderLevel` 的 RETURN 被调用（`LevelRendererMixin.java:56-60`）；当 `!irisActive && DISTANT_HORIZONS && DistantSkyDepth.current(frame)` 时执行 `resolveView(frame)`（`SkyWorldRenderer.java:173-174`）。
2. 第一阶段（opaque）：`nativeView && resolveShader != null && SkySurfaceLayer.available(frame)` 时复制世界深度并渲染私有层（`SkyWorldRenderer.java:301-313`）；随后主目标以 `colorMask(false)` 只写原中心采样深度（`:324-328`），再 `SkySurfaceLayer.composite(nativeLayer, frame.viewport)` 做**第一次**预乘 alpha 合成（`:329-333`）。
3. 第二阶段（末段）：`resolveView` 再次 `SkySurfaceLayer.render(...)`（`:384-385`）并 `composite`（`:386`），把同一覆盖率层**第二次**合成到已经包含第一次天空边缘的最终颜色上；`:388` 置 `finalResolved=true`。
4. 合成公式：`sky_surface_composite.fsh:6-7` 直接取私有层 RGBA（RGB 已按样本数预乘、A=覆盖率），blend 为 `ONE, 1-SRC_ALPHA`（`SkySurfaceLayer.java:69-70`）。对同一像素覆盖率 `a`、天空色 `C`、背景 `B`：第一次 `aC+(1-a)B`；第二次 `aC+(1-a)(aC+(1-a)B)`，相对理想一次合成多出 **`a(1-a)(C-B)`**，符号朝天空色。

**实拍阶段差分（模型无关，最强证据）**

同一场景的 `-pre-resolve.png` 是第一次合成后的主目标；`final` 是第二次合成后。对 DH fixed 与 control、以及 vanilla 逐像素比较：

| 场景 | 末段第二次合成改动像素 | 改动像素覆盖率范围 | 平均 \|Δ\|（检查器边缘带内） | 最大 \|Δ\| | 方向 | 第一次合成 MAE（pre vs 理想） | 最终 MAE |
|---|---:|---|---:|---:|---|---:|---:|
| DH fixed static-00 | 1153（全图 1590） | 0.125–0.938，主峰 0.25/0.5/0.75 | 2.879 | 20.0 | 100% 更亮 | **0.7900** | **3.0445** |
| DH fixed walk-05 | 727 | 0.125–0.938 | 2.970 | 19.0 | 100% 更亮 | **0.1206** | **2.9668** |
| DH fixed walk-16 | 826 | 0.25–0.812 | 3.458 | 20.0 | 100% 更亮 | **0.0820** | **3.4205** |
| DH fixed resize | 624 | 0.188–0.938 | 2.929 | 20.0 | 100% 更亮 | **0.1566** | **2.9261** |
| DH control（同一场景） | **0** | — | 0 | 0 | — | 4.5902（static-00） | 4.5902 |
| vanilla fixed（同一场景） | **0** | — | 0 | 0 | — | 0.8073（static-00，与 final 相同） | 0.8073 |

- 改动像素的 16 点覆盖率全部严格在 (0,1)，**没有** alpha=0/1 的像素；hand mask 重合 **0**；内部原色 MAE **0**；6 个上部/侧面背景检查 MAE **0**；无 GL/越界/深度错误。也就是说，双合成的足迹严格限定在天空方块外轮廓的部分覆盖像素，不会产生洞、错位或内部变色。
- 第一次合成（`pre`）对面积参考的残差只有 0.08–0.79，已经接近纯原版 walk 的 0.07–0.13；**最终 2.93–3.42 的残差几乎全部由第二次合成贡献**（阶段差分本身 2.88–3.46）。这是“可避免的设计性双合成”，不是 4x 覆盖率精度、不是深度归属、不是 checker 伪影。
- 逐场景配对（`r34-dh-*-oracle.json`）：移动 24 帧中 **1 帧劣于旧路径**：`edge-silhouette-walk-16` 固定 **3.4205** vs 对照 **2.5232**（+0.897）；其余 23 帧为 -1.42…-3.71。静态 32 帧 0 帧劣于对照。交付的“移动 4.542667→2.833585，37.62% 改善”是 24 帧均值，数值正确；但“DH 也改善”不能逐帧无条件表述。
- 措辞核对：交付正文把该残差称为“二次恢复通路的亚像素残差”。方向正确，但“亚像素”容易被读成 4x 覆盖率量化误差；实测 `pre` 对面积参考的残差只有 0.08–0.79，而第二次合成单独贡献 2.88–3.46、最大约 20 级，属于**覆盖率尺度的 alpha 二次计入**，不是亚像素抖动。建议后续把该句改为“末段二次预乘 alpha 合成导致部分覆盖边缘偏移，移动平均 2.83、单帧最差 3.42”。
- 59/60 场景真正 `finalResolved`（第 60 个是 removed/0 blocks），fixed 与 control 均如此；所以上述残差不是偶发未恢复帧。DH 静态均值 3.0156 vs 4.5596（32 帧），移动 2.8336 vs 4.5427（24 帧）。

**判定**

- 这是**真实、可复现、已在源码与阶段图中定位**的残差；它不会破坏 ITRP 混材交界（那里 alpha=1），也不影响纯原版（单合成）；它只影响“无光影+DH”外轮廓的部分覆盖像素。
- 对接**受支持环境**的诚实表述：DH 路径的新 AA 被第二次 alpha 合成部分抵消，边缘被推向天空色约 13 级（最大 20），平均边缘误差 ~3；旧路径平均更差，但个别姿态（walk-16）更接近面积理想。**如果发布结论声称“DH 也达到理想单次合成”，该主张被本证据阻断**；如果结论按交付现有范围（DH 为已知残差补充环境，97.24% 不外推），则**不阻断本轮两项指定目标**。
- 修复方向（本轮不改代码，仅建议）：DH 的第一阶段只写全覆盖样本（alpha=1），部分覆盖像素留给末段一次合成；或 DH 跳过第一阶段的部分覆盖混合。这样 `pre` 的 0.08–0.79 残差就是可达到的水平。需要新一轮实拍验证，不能仅靠本轮阈值放行。

### 3.5 日志、启动异常与分类

本个体扫描全部 `r34-*` run 的 `client.log` 与 `run-result.json`：

- 全部 25 个 `r34-*` run 目录（含 baseline/smoke/control/fixed/regression/DH/重试）`exitCode=0`、`finished=true`、`fatalErrors=[]`、`renderThreadViolations=[]`；2010 个场景 JSON 中 `initialGlError/finalGlError` 全 0、`outsideRasterChangedPixels/Depth` 全 0。
- 原始日志仅 **3 条 ERROR**：
  1. `r34-control-r2-fsr2` 00:19:40 `DH-ClientTickTimer ... LodQuadTree shouldn't be updating renderSections below the expected detail level: [9]`；首张 `CAPTURED` 为 00:19:52（严格早于采集）。
  2. `r34-control-r2-native` 00:38:46 `Yggdrasil Key Fetcher ... Failed to request yggdrasil public key`。
  3. `r34-regression-panomotion-fsr2` 01:25:16 同类 Yggdrasil 公钥失败。
- DH 启动错误的分类证据：同包同配置（jar SHA、shader、FSR2_SCALE=2、MOTION_BLUR=false、mods、negativeSurfaceLayer）重试 `r34-dh-startup-retry-control-fsr2` 完整 2 场景、48.39 秒、0 错误；错误的引用文本在重试日志中不存在。该错误是 DH 客户端 tick 的启动期四叉树断言，发生在任何采集之前，且 180 场景 control 仍全部完成、边缘 oracle 只有允许的旧路径失败。**结论：将其单列为 `nonfatalStartupDhErrors` 有充分证据**；需要保留的限定是“重试只有 2 场景，不能逐位复现 180 场景 control 的 DH LOD 内部状态”，但它不影响“启动期、非致命、采集前、不可复现”的窄结论。
- Yggdrasil 公钥失败按外部网络单列：两条均为启动/回归期 Mojang 公钥 HTTP 失败，run 继续完成且无 fatal；与模组渲染无关。该分类成立。
- 初轮 `r34-baseline-r1` 排除：baseline 的 walk 相机 y 最低 **-61.38**、x 最大 **49.94**（走出平台坠落），fixed 的 walk y 恒 141.62、x ∈ [-2.11, 2.15]；baseline 三环境均已从最终 runs 剔除，原图/原报告保留。排除理由有数据支撑。
- 字段命名评估：`renderingErrors=0` 的**意图**（采集阶段无致命/渲染线程异常）是合理的，但该字段在 `finalize-r34.py:43` 是**硬编码 0**，不是从 run-result 读取；真正的证据是 `fatalErrors=[]` 与 `renderThreadViolations=[]`。同时 `errors` 数组确实非空（上述 3 条）。因此字段名容易被读成“原始日志总 ERROR=0”。建议（非阻断）：改为 `fatalErrors:0, renderThreadViolations:0`，或增加 `"renderingErrorsScope": "capture-phase fatal/render-thread only; classified startup/network errors listed separately"`。同理 `positiveImageChecks=5508` 实际是 oracle 总检查数（含几何/状态/行走/场景数检查），建议改名 `positiveChecks`。

### 3.6 保留项与历史范围

- R33 测光：`SkyExposureMeter.class` 与 R33 字节相同；15 场景 hand exposure（native/fsr2）182 检查通过，`whiteFraction248` 最大 **0.0**、`handResolveDifference` 最大 **0**、proxy/restored 记录存在。R33 暗室/曝光未被本轮改动覆盖。
- R32 方向：`SkyRenderMath.class` 与 R33 字节相同；4 套 panomotion 28 场景/组回归通过；4K 晴空两张 PNG 与 R33 字节相同。
- 名称/注册：`SkyStyle`、lang、模型、blockstate 等 152 项资源与 R33 字节相同；新增 shader 注册在 `SkyboxBlockClient.java:18-33`，资源释放挂接 `DISCONNECT`/`CLIENT_STOPPING`（`:34-37`）。
- 未替换用户安装包/存档/shader ZIP/配置：本个体只读，未触碰用户实例；执行记录说明工作区无 Git，本个体也未做任何提交/推送。
- 历史严格恢复四红、门户、资源/性能长测：本轮证据未覆盖，交付报告已声明不计作已修复；本审查同意该范围。

### 3.7 状态恢复与作用域（源码抽查）

- `SkyGlState` 保存/恢复 draw/read FBO、read buffer、viewport、active texture、2D 绑定、sampler object、program、VAO/array buffer、depth/cull/blend/scissor/stencil、depth func、front face、cull face、blend func/equation、color/depth mask、shader color、clear color/depth、depth range、clip distance 0、draw buffers，并在 finally 中最后恢复物理纹理与 sampler（`SkyGlState.java:132-188`）。ITRP 私有 target 作用域由 `ModCompat.privateTargetScope()` 包住。
- `SkySurfaceLayer.render` 额外保存 renderbuffer 绑定与 MSAA/alpha-to-coverage/sample-coverage/alpha-to-one/sample-mask 使能，finally 恢复（`SkySurfaceLayer.java:25-59`）；`prepare` 只动私有 FBO 的 draw buffer，不改调用者 FBO 状态；`release` 删除 FBO/RBO/texture 并关闭 quad，`SkyMesh` 懒重建（`SkyMesh.java:27-30`）。
- 私有层仅在 `frame.root && !portal && !shadow && MAX_SAMPLES>=4` 且像素数 ≤16,777,216 时启用（`SkySurfaceLayer.java:16-24`），FBO 不完整返回 0 回退旧路径（`:35,:50`）。私有层只写 `skyColor(uv)`，未采样世界 RGB；世界遮挡依赖复制的 `WorldDepth` 与逐样本深度测试，前景历史/手部/玻璃保护仍在 shader 中保留（`sky_resolve.fsh:46-89`）。
- 未发现 GL 状态泄漏或未恢复路径；2010 个场景的 GL 错误/越界扫描为 0。

## 4. 阻断/非阻断清单（含行号）

### 阻断（主张层面，需在最终结论中显式排除）

- **B1：无光影+DH 的第二次 alpha 合成**。来源：`SkyWorldRenderer.java:173-174`（DH 末段触发）、`:301-313` + `:324-333`（opaque 第一次合成）、`:384-386`（末段第二次合成）；`sky_surface_composite.fsh:6-7` 与 `SkySurfaceLayer.java:69-70` 的预乘 blend。证据：§3.4 阶段差分（改动像素 1153/727/826/624，全部 0<a<1，100% 更亮，最大 20；pre MAE 0.08–0.79 vs final 2.93–3.42）；反例 `edge-silhouette-walk-16` 3.4205 vs 2.5232。**阻断的是“DH 也达到理想单次合成/无残差”的主张，不阻断两项指定目标。** 按用户要求，未修代码、未改阈值。

### 非阻断（但必须写进范围/文档）

- **N1** `renderingErrors=0` 在 `finalize-r34.py:43` 硬编码，字段名不等于“原始日志 0 ERROR”。建议改为 `fatalErrors`/`renderThreadViolations` 或加 `renderingErrorsScope` 定义。
- **N2** `positiveImageChecks=5508` 实为 oracle 总检查数（含几何/状态/行走），建议 `positiveChecks`。计数本身正确（本个体按 1019×2+1078 + 5×212 + 4×152 + 2×182 + 360 = 5508 复核；852 场景 = 540+110+112+30+60）。
- **N3** `r34-final-acceptance.json:491-496` 的 `limits` 没有 DH 残差条目，而 `nativeDhSupplement`（`:190-253`）只给 MAE/background 与 `passed:true`。仅读该 JSON 会看不到“DH 双合成/1/24 反例”。建议把交付报告 §“另做无光影＋DH” 的限制原文补入 `limits`。
- **N4** ITRP 的 edge oracle 对 `itrpMaterialWrites` 只用 `allsky` 像素（`check-r34-edges.py:88-91`），ITRP 外轮廓部分覆盖像素不进入 MAE；本轮目标为混材交界，报告未外推，属范围注记而非缺陷。
- **N5** DH 移动 1/24 场景（walk-16）劣于旧路径未在机器可读汇总中单列；交付正文只给均值 37.62%。建议在 DH supplement 增加 `worse-than-control-scenes` 或逐场景表。
- **N6** 本次无法逐字节证明 10 个修改类与冻结源码同源（重映射 sources jar）；已用行号表/字符串/包审计交叉。属证据强度注记，不影响两项目标的数值结论。

### 未发现阻断的项（明确记录）

- ITRP 混材交界的私有层选择、低分辨率 ID 降级为 provenance、`SurfaceLayer` gate：0 时序 STD、内部原色保持、旧路径 4878/2689 跳变像素被消除；未发现颜色/深度错位或前景泄漏。
- 纯原版单次预乘合成：walk 0.13277、static 0.80734，`final==pre` 证实无第二次合成；4x 覆盖率、16 点面积参考、反事实与 slab 等价检查均能区分错误，未发现阈值放宽或元数据放行。
- GL 状态/资源生命周期：`SkyGlState` 覆盖的恢复项完整；2010 场景 GL 错误 0、越界 0；缩放/移除/重载场景通过。
- 身份与保留项：JAR SHA、版本、4K 字节、4 个保留类、152 个未改资源、shader ZIP 全 SHA 均一致；未改用户实例。

## 5. 范围化结论

1. **R34 的两项指定目标成立**：指定 ITRP 混材交界在 32 帧真实 jitter 下从 10.98821/6.53484 时序 STD 降到 0，边缘 MAE 7.67/5.91→1.04；无光影 Fabric 原版移动外轮廓 4.81270→0.13277（97.24%），静态 4.62313→0.80734。两项均在“4x 几何覆盖率、非全屏 MSAA/TAA”范围内，不外推为整屏画质或帧率。
2. **不把 97.24% 泛化给 DH**：DH 的 37.62% 是 24 帧均值，且存在 §3.4 的双合成残差与 walk-16 单帧反例；DH 的“理想单次合成”主张被阻断，但这不是本轮两项指定目标的失败。
3. **不把本轮结果覆盖历史红项**：严格恢复四红、门户、资源/性能长测仍未验证；本轮只是指定版本/指定环境的范围化测试候选。
4. **发布措辞建议**：在最终无阻断结论中同时写“ITRP/纯原版目标达成”与“DH 为带双合成已知残差的补充环境；1/24 移动帧劣于旧路径；97.24% 不适用于 DH”；不要仅凭 `nativeDhSupplement.passed=true` 推导 DH 边缘正确。

## 6. 复现入口（本个体实际使用）

- 身份：`Get-FileHash -Algorithm SHA256 build/libs/skyboxblock-1.0.3-test.4-r34.jar`；`zipfile` 对比冻结 ZIP 与工作区 151 个源码/资源；`zipfile` 对比 R33/R34 JAR 条目；`javap -p -l` 抽查行号表。
- 时序：读取 `r34-fixed-r1-fsr2|native` 与 `r34-control-r2-fsr2|native` 的 `edge-mixed-static-00..31.png`，用 `check-r34-edges.py` 的 ownership 生成 17212 像素掩码，NumPy `std` 逐像素；fsr2 23 / native 16 个 distinct jitter。
- 阶段：读取 `r34-dh-fixed-r1-dh` / `r34-dh-control-r1-dh` / `r34-fixed-r1-vanilla` 的 `*.png`、`-before.png`、`-opaque.png`、`-pre-resolve.png`、`-post-hand-mask.png`，比较 final/pre/opaque 差分，按 16 点 subpixel ownership 计算覆盖率并聚类改动像素。
- 日志：`Select-String -Pattern '/ERROR]'` 全量扫描 25 个 `client.log`；`run-result.json` 与 `fixture.json` 逐字段 diff；重试 run 的 2 场景/0 错误核对。
- 报告/包：`r34-final-acceptance.json`、`r34-package-audit.json`、`r34-coverage-properties.json`、`r34-oracle-slab-equivalence.json`、`r34-oracle-counterfactual-final.json`、`r34-static-pose-pairs.json`、各 oracle JSON 与原始 PNG。

## 7. 附：审查中确认的关键计数

- 正向场景 852 = 3×180（fsr2/native/vanilla）+ 5×22（materials）+ 4×28（panomotion）+ 2×15（handexposure）+ 60（DH）；控制 600 = 3×180 + 60；合计 1452。
- 正向检查 5508 = 1019×2 + 1078 + 5×212 + 4×152 + 2×182 + 360；本个体逐项相加一致。
- 2010 个场景 JSON：GL 错误 0、越界 0；ITRP 177/177 sky 场景 finalResolved；DH 59/59；vanilla 0（设计上单合成，`finalResolved=false`）。
- 37.62% = 1 - 2.8335851778586707 / 4.5426673491795855；97.24% = 1 - 0.13276731253912052 / 4.8127001623312635；两式均按报告原始值复算。

## 8. 复审记录（待新包；追加，不作预判）

状态：**等待主线提供新 JAR SHA、构建日志与新证据。** 主线已声明修复方向：在 native opaque 已成功四采样合成时，DH 末段恢复仅恢复 alpha==1 的完整天空像素，保留已混合的部分覆盖边缘；Iris 与纯原版仍全覆盖率合成。该方向与 §3.4 的阶段差分一致：第一次合成（`pre`）在旧 DH 天空背景场景上已接近理想单次合成（MAE 0.08–0.79），第二次合成对 alpha=1 像素恒等、对 alpha<1 像素多计一次 alpha。但“第一次合成的背景是否等于最终背景”在 **DH 地形真正遮挡天空边缘**的像素上不成立；若 DH 在部分覆盖像素上改写了背景，仅恢复 alpha==1 会保留第一次合成的旧背景混合。因此复审必须包含该类边缘，不能只复测天空背景上的轮廓，也不能把这类像素算作修复通过。

复审将只抽取受影响路径与主要回归（不重走旧 1452 场景），并按以下证据核对：

1. **身份**：新 JAR SHA 与新构建日志；源码/资源相对旧冻结包的差异必须只包含声明的 DH 末段恢复修复；包审计对未改资源的字节约束仍成立；4K PNG/保留类/shader ZIP 不变；版本号更新。
2. **DH 阶段差分（模型无关）**：对新 DH fixed 与 control 的 `final` vs `pre-resolve` 逐像素比较：
   - 覆盖率 `<1` 的像素，第二次合成改动必须为 **0**（`final` 与 `pre` 逐像素相同）；同时用该场景的 `before`/世界背景证明第一次合成的背景与最终背景在该像素一致，否则显式列为“DH 地形改写部分覆盖像素”的未覆盖限制，不能算修复通过。若 `final` 仍改动任何 partial 像素，直接判失败。
   - 覆盖率 `==1` 的像素允许因 DH 覆盖而恢复，且恢复值应与 pre 的天空色一致；full 像素的 `pre→final` 差异只能出现在被 DH 改写过的位置。
   - `edge-silhouette-walk-16` 等旧反例必须不再劣于 control；24 帧移动与 32 帧静态的 fixed MAE 应回到 `pre` 量级（旧包 0.08–0.79），且逐帧不得出现新的劣于 control 的场景。
   - `59/59` sky 场景 `finalResolved`、内部原色 0、6 处背景 MAE 0、hand mask 0 重合、GL/越界 0 继续保持。
3. **纯原版**：仍为单次合成（`final==pre`），97.24% 量级的 walk 指标不退化；不得因 DH 修复改变 vanilla 的 opaque 合成分支。
4. **ITRP**：`SurfaceLayer` gate 不变；32 帧、17212 掩码、23/16 个实际 jitter 的时序 STD 仍为 0；混材静态/移动 MAE 1.041/0.356 量级不退化；ITRP 的 alpha=1 混材交界不受 DH 修复影响。
5. **其他光影/门户/阴影**：未审计的光影包仍走旧路径（`itrpMaterialWrites=false` 分支）；portal/shadow 仍不启用私有层；`SkyGlState` 恢复路径不因新条件产生泄漏。
6. **日志**：新包所有正向 run 无新增未分类 ERROR；若再次出现启动期 DH/Yggdrasil 记录，仍按旧标准单列并给出同包同配置重试；`renderingErrors` 若保留，必须给出 scope 或改名为 `fatalErrors`/`renderThreadViolations`。
7. **回归**：11 组既有回归、hand exposure、panomotion 通过；受影响路径必须包含至少一个 DH 地形/近景遮挡天空边缘的场景，不能只用天空背景轮廓证明“部分覆盖不再二次合成”。

在收到新 SHA 与证据前，本报告不给出新包结论；收到后在同一文件 §8 追加“复审证据 / 结论 / 仍未覆盖范围”。初审的旧 SHA、反例（walk-16 3.4205 vs 2.5232）、阶段差分数字与 §3.4 行号均保留，作为修复前后对照。

**待验实现细节（主线 2026-10-24T02:32 通报，尚未收到证据，不作结论）**

- 新版本 `1.0.3-test.4-r34.1`，旧包保留；修复条件为“nativeOpaque 已成功合成时，DH 末段仅恢复 alpha==1 的完整天空像素”。
- 关键时序：主线称在调用 `SkySurfaceLayer.render()` **之前**捕获 `frame.surfaceLayerUsed`，避免把末段首次绘制误认成已有 opaque。复审必须确认：
  1. 捕获点确实在 `resolveView` 的末段 `render()` 之前，且该值只反映 opaque 阶段是否成功合成；
  2. 若 opaque 未成功合成（首帧、重载/缩放后、FBO 不完整回退、surface 返回 0），末段必须走**完整**合成，不能因“未捕获到 opaque”而跳过部分覆盖像素，否则这些像素会没有天空色；
  3. 该条件只对 native DH 生效，Iris 与纯原版分支不得改变；`frame.surfaceLayerUsed` 在末段 `render()` 内仍会被置 true，不能在同一帧后续逻辑里被二次读取当作“opaque 已合成”；
  4. `alpha==1` 必须是**逐像素解析 alpha**（或私有层逐样本覆盖率）判定，不能退化为整帧标志；需确认 8-bit 量化下全覆盖像素 alpha=255、部分覆盖像素 alpha<255，且不会因混合产生 254/255 的误判。
- partial 背景被 DH 改写的风险：若末段跳过 alpha<1，第一次合成（pre-DH 背景）的混合会被保留；当 DH 地形在部分覆盖像素上改写背景时，正确结果应是对 **DH 后背景**的一次合成，而不是旧背景。主线将该风险列为单独验证项；复审将要求至少一个 DH 地形/近景遮挡天空边缘的场景，并给出该像素的 first/final 背景差与误差量级；若仍存在，按“未覆盖限制”记录，不能算修复通过。
- 定向初审反例 7 场景与后续完整复验、遮挡物、11 组旧回归同新 JAR 的证据尚未收到；本报告不提前给出新包通过/失败结论。

**新包身份与定向结果（2026-10-24T02:44 通报 + 本个体初步核对；仍未结论）**

- 新 JAR `build/libs/skyboxblock-1.0.3-test.4-r34.1.jar`：本个体 `Get-FileHash` = **`bec71c6bf0bc291441e50e1a4f559b764ddf257ca177544360c6ce4caf95ad69`**（与主线通报一致），16,996,261 字节，mtime 02:29:07；旧包 `…r34.jar` 保留。
- 构建日志 `build/r34-1-review-fix-build.log`：`compileJava` 执行、`PASS: 389385 assertions`、`BUILD SUCCESSFUL in 25s`，mtime 02:29:11。
- 定向 run `r34-1-dh-fixed-dh`：7 场景、50.89 秒、exit 0、`fatalErrors/renderThreadViolations/errors` 全空；fixture jar 为新 SHA、profile `dh`、`negativeSurfaceLayer=false`。
- 本个体对 `edge-silhouette-static-00 / walk-16 / resize / walk-00 / walk-05` 的 `final` vs `pre-resolve` 逐像素复核：**改动 >0.5 的像素 0、max 0**；`pre` vs `opaque` 也为 0；`finalResolved=true`、`surfaceLayerUsed=true, samples=4`、blocks=90。也就是说，在旧反例的天空背景场景上，末段恢复现在不再改动任何像素，第一次合成即最终颜色。
- 这仍**不是**复审结论：完整 8 组边缘配对（DH silhouette+foreground、真实砂岩/玻璃近景遮挡）、新包 11 组回归、R30 真实 LOD 五阶段（天空在后→天空在前→恢复）与 partial 背景被 DH 改写的单独验证项尚未收到。上述定向结果只证明旧反例路径的第二次合成已消失；它不证明 DH 地形改写部分覆盖背景时仍正确。

**Bec（r34.1）完整证据到达后的待验状态（2026-10-24T03:59 通报 + 本个体初步核对；未结论，待 r34.2）**

- 主线通报：8 组边缘配对 + 11 组回归全通过；真实 LOD 五阶段 384.641s/ERROR0，原 26/26 顺序检查通过；raw 在 `build/render-regression/r34-1-lod-edge-stages/r30-results`，含 opaque/pre/post 与私有 RGBA 层、同一 tx、首个真实阶段保留。
- 本个体核对到的证据：`r34-1-final-acceptance.json` 版本 `1.0.3-test.4-r34.1`、JAR SHA `bec71c…`、`passed=true`、正向 552 场景、控制 300、19 runs、package 92、math 389385；`r34-1-edge-matrix.json` 8 组完成（DH fixed/control 各 120，vanilla/native/fsr2 各 60/60）；`r34-1-lod-occlusion-oracle.json` 26/26 且 `releaseAccepted=false`；22 个 `r34-1-*` run 目录全部 exit 0/finished。
- **日志新项（需单列，不能宣称 ERROR0）**：`r34-1-regression-materials-fsr2` 在 03:20:26（首采 03:19:01 之后）有一条 `DH-Regen Queue ... java.sql.SQLException: stmt pointer is closed`；run 仍 22 场景、exit 0、oracle 通过，属 DH 后台 LOD regen 队列异常，不是渲染线程/fatal。另有 `r34-1-edge-control-vanilla` 一条 Yggdrasil 公钥失败（外部网络）。Bec 的“11 组回归全通过”若指 oracle 成立，但日志不是全 0；该项应在 r34.2 的审计中按旧标准分类。
- **已知残差（主线观察，与 §8 风险一致）**：sky-front 有 1347 个 partial 像素，其中 649 在 opaque→pre 被 DH 覆盖，post 不变。Bec 只跳过 alpha<1，因此这 649 个像素保留第一次合成（pre-DH 背景）的混合；`r34-1-final-acceptance.json` 的 `limits` 也写明“Native DH late repair never reblends fractional sky coverage… single-sample world-background limitations; measured above, not claimed ideal”。这不是新发现的隐藏项，但 r34.2 要修的就是它。
- r34.2 拟案（主线 03:59）：仅对已校验 API 同 owner/同尺寸/受支持格式的原生 DH 末段 partial，输出 `ownPremulSky + (1-a)*currentDHbackground` 作为 alpha=1 替换（不再乘 a）；DH 无内容保留 first；alpha=0 保留前景；Iris 不进此分支；DH 颜色只作未覆盖背景、不作天空来源、无 CPU 读色；API 不满足回退 R33 中心归属。
- 复审将重点核对：`currentDHbackground` 必须是**未被第一次合成污染的 DH 背景**（不能是当前已合成的目标色），否则 `ownPremulSky+(1-a)*current` 会再次重复计入；`a` 必须与私有层覆盖率同源；alpha=1 替换必须只落在 DH 有内容/被覆盖的 partial 像素；DH 无内容与 alpha=0 的保留分支正确；Iris/vanilla 分支不变；回退路径不会在受支持包上静默触发。上述任何一项未满足，r34.2 仍不能算修复通过，尤其不能只看“8 组通过”而忽略 649 个被 DH 改写的 partial。

**Bec 状态（历史）：待验；最终 r34.2 复审见 §9。**

## 9. 最终 r34.2 复审证据与结论（收尾）

### 9.1 身份、构建与包审计

- 本个体 `Get-FileHash -Algorithm SHA256`：`build/libs/skyboxblock-1.0.3-test.4-r34.2.jar` = **`55337b11fb3e268cb972bf9873bbd77aa25fc8481ddb8054d057224e6f61c022`**，16,998,907 字节，mtime 2026-10-24T04:08:08；`fabric.mod.json` 版本 `1.0.3-test.4-r34.2`；旧包 `…r34.jar` 与 `…r34.1.jar` 均保留。
- 构建日志 `build/r34-2-native-background-build.log`：`compileJava` 执行、`PASS: 389385 assertions`、`BUILD SUCCESSFUL in 19s`，mtime 04:08:11；mtime 顺序为 JAR 04:08:08 → 日志 04:08:11。
- `r34-2-package-audit.json`：**94/94**，`failedChecks` 空；关键项包括“changes restricted to native repair and regression metadata”“no added or removed members”“manifest only version changed”“new version”，以及 blockstate/model/lang/texture/4K/sky 资产、四个保留类、`world_sky`/`sky_material_background`/`sky_meter_proxy` 的保留、`native repair replaces with one generated-sky/backdrop composition`、`native clear depth keeps previous fringe`、`repair disabled by default`、`premultiplied RGB and preserved destination alpha`、`no diagnostic probe in production`。
- R34.1→R34.2 JAR 条目：新增/删除均为 0；修改 10 项：`MANIFEST.MF`、`sky_surface_composite.{fsh,json}`、`RenderRegression(.$Scene/.$WorldCapture)`、`SkySurfaceLayer`、`SkyWorldRenderer(.$Frame)`、`fabric.mod.json`。与“native repair + regression metadata”范围一致，无其它生产资源变化。
- `r34-2-final-acceptance.json`：**612 正向场景 / 4191 检查、360 同包控制、972 总计**；`realLodPhases=5`、`realLodOrderingChecks=26/26`、`packageChecks=94`、`mathAssertions=389385`、`maximumHandWhiteFraction248=0.0`。计数分解：edge fixed 120+60×4=360 场景 / 719+360×4=2159 检查；11 组回归 = 5×22 + 4×28 + 2×15 = 252 场景 / 5×212 + 4×152 + 2×182 = 2032 检查；合计 612 / 4191。控制 360 = 120+60×4。
- 10 组 edge 配对（DH、Iris/Sodium+DH 关光影、vanilla、native、fsr2 各 fixed/control）与全部 11 组旧回归在 `runs` 中均 `passed=true`、`fatalErrors=[]`、`renderThreadViolations=[]`。

### 9.2 源码/着色器与背景合成路径（抽查）

- 版本门：`SkySurfaceLayer.java:13-14` 的 `NATIVE_DH_COLOR` 仅在 DH 版本严格等于 `3.3.3` 时为真，反射只在 native 分支执行。
- 背景来源校验：`SkySurfaceLayer.java:24-53` 依次要求 `!irisActive && root && !portal && !shadow && DistantSkyDepth.current && frame.distantDepthEnabled`；`DhApi$Delayed.renderProxy.isNativeRenderer()`；depth/color 查询 `success` 与 `payload`；`GlDhMetaRenderer.INSTANCE` 的 active depth/color 与 payload 一致；`depth == frame.distantDepthTexture`；color 不等于私有层、`ownSkyColorTexture`、MC 主目标；`glIsTexture`；宽高等于 viewport 与 `frame.targetViewport`；color 格式在 RGBA8/RGB8/RGBA16F/RGB16F/RGBA32F/RGB32F 内；MC 主目标为 RGBA8。任一不满足返回 0，不设置 `nativeBackgroundReady`。
- 末段分支：`SkyWorldRenderer.java:387` 在 `SkySurfaceLayer.render()` **之前**捕获 `preserveNativeFringe=!iris && frame.surfaceLayerUsed`（避免把末段自己的首次绘制误判为已有 opaque）；`:388-389` 渲染私有层；`:390-393` 若 `surface>0 && preserveNativeFringe` 且背景 API 可用，则 `composite(surface,viewport,true,frame.distantDepthTexture,background)`，否则 `drawMaterialFaces(...)` 回退原中心归属；`:394-395` 无 opaque 首次合成时走常规全覆盖率 `composite`；Iris 仍走其常规私有层 composite，纯原版在 `resolveView` 入口因非 Iris/非 DH 早退。
- 合成着色器：`sky_surface_composite.fsh:10-22` 取 `SurfaceColor`（RGB 已预乘、A=覆盖率）；`a<=0` discard；`FullCoverageOnly!=0 && a<0.99999` 时，若 `NativeBackgroundEnabled==0` discard；用 `NativeDepth` 判 DH 是否有内容（0/1 均表示未替换背景），若有则 `background=clamp(NativeColor.rgb)`，输出 `vec4(premulSky+(1-a)*background,1.0)`；不采样已合成 MC 色、不再乘 a。`sky_surface_composite.json:7` 声明 `FullCoverageOnly`/`NativeBackgroundEnabled`。
- `SkySurfaceLayer.java:97-116` 的 composite 重载设置 `FullCoverageOnly`、`NativeBackgroundEnabled`、`NativeDepth`/`NativeColor` sampler；`SkyGlState(6)` 负责恢复。回退/无背景/alpha0 均不会静默进入“第二次乘 a”的旧行为。
- 实际证据：118/118 固定 native DH sky 场景、59/59 Iris/Sodium+DH 关光影场景 `nativeBackgroundReady=true`；6 场景 `r34-2-native-bg-smoke-dh` exit 0/0 错误；未出现静默 fallback。

### 9.3 独立数值复算（抽样，不遍历 972）

- LOD 真实背景（`r34-2-lod-edge-stages/r30-results`，同 tx 70758，1280×800）：`sky-front` 私有层 alpha 严格 partial **1347**；`native-dh-depth-interior-mask` 下 **939** 有 DH 背景、**408** 无背景；`post` 相对 `pre` 在 partial 上实际改动 **561** 像素，与报告 1347/939/408/561 逐项一致。对 939 个背景 partial 用 `premulSky+(1-a)*post-native-background` 复算理想一次合成，**MAE 0.226079 级**（报告 0.226081）、本个体最大 0.452 级（报告 0.5608，mask/取整差异），量级均 <0.6 级；`sky-behind` 81/44/37/0、MAE 0.200147；物理金墙 holdout 112 像素：API 背景对金墙 0.9345、最终一次合成对金墙 0.7763（报告值，<1 级）。
- 旧 Bec ~30.47 级背景误差：Bec 未捕获 native-background PNG，本个体未能从旧阶段逐位复算该数；但新阶段在同一类 939 个背景 partial 上相对干净 DH 背景的 MAE 已降到 0.226 级，且 561 像素实际被改写（不是跳过），足以确认“修复而非仅遮掉”。
- 边缘代表值（`r34-2-*-oracle.json`）：DH fixed static-00 **0.791478** / walk-16 **0.098960**，control **4.595346 / 2.521336**；Iris/Sodium+DH 关光影 fixed **0.794035 / 0.082441**，control **4.659805 / 4.086744**；vanilla fixed **0.789101 / 0.064883**，control **4.635078 / 4.337203**；三组 `largeErrorFraction=0`、`interiorMAE=0`。移动均值按 final acceptance：DH **4.504651→0.140853（96.87%）**、Iris/Sodium+DH 关光影 **4.637353→0.136950（97.05%）**、vanilla **4.809367→0.133079（97.23%）**；静态均值分别 4.558629→0.778564、4.623956→0.782802、4.635078→0.789101。
- ITRP：本个体用同一 17212 像素独立几何掩码重算 32 帧，fixed **0.000000**（max 0），control FSR2 **11.007716**、native **6.542776**（max 127.5）；与 final acceptance 的 11.007715 / 6.542775→0 一致。混材边缘 fixed 1.041001（native/fsr2 static）、walk 0.349959 / 0.353706，较旧路径 5.910209/7.671169、7.555330/10.089386 不退化。
- 手部：`maximumHandWhiteFraction248=0.0`；11 组回归 oracle 全过；无新增 GL/越界（edge 场景逐 JSON `initialGlError/finalGlError` 0）。

### 9.4 日志与分类

- `r34-2-*` 共 23 个 run 目录（10 edge + 1 LOD 阶段 + 1 native-bg smoke + 11 regression）：10 edge、11 regression、smoke 的 `run-result` 均 `exitCode=0`、`finished=true`、`fatalErrors=[]`、`renderThreadViolations=[]`；LOD 阶段 `exitCode=0`、`complete=true`、5/5 phases、`fatalErrors=[]`、`errors=[]`（诊断格式，无 `finished` 字段）。
- 原始日志仅 `r34-2-regression-materials-dh` 一条 `[05:02:35] Yggdrasil Key Fetcher ... Failed to request yggdrasil public key`；final acceptance 同时把它记入 `runtimeLogErrors` 与 `externalNetworkErrors`（真实数组，不再有硬编码 `renderingErrors`）。Bec 的 `DH-Regen Queue ... stmt pointer is closed` 在 r34.2 未再出现；LOD 阶段与 smoke 均 0 ERROR。
- 该条为外部 Mojang 公钥 HTTP 失败，run 继续完成且 oracle 通过；与渲染路径无关。除此之外没有需要单列的启动期 DH/渲染异常。

### 9.5 最终判定与范围

- **通过（声明范围内），无阻断。** 上一轮 B1（DH 理想单次合成）在受测 native DH 3.3.3 / 同 owner / 同尺寸 / 支持格式 / MC 目标 RGBA8 路径上解决：939 个 DH 背景 partial 被一次合成修复到理想 MAE 0.226 级，408 个无背景 partial 保留第一次合成，alpha0 保留前景，Iris/vanilla 路径不变，API 不满足回退原中心归属。
- 范围化保留：4x 生成天空几何，非全场景 MSAA/TAA；native 背景重建锁定已验证 DH 3.3.3 apply-source/depth，其他 DH 版本/不匹配格式走原中心归属；Iris 外轮廓 partial 仍不在混材 all-sky MAE 内；固定 shader 测试 `MOTION_BLUR=false`；历史严格恢复四红、门户、资源/性能长测仍不在本验收内。LOD 阶段自身 `dynamicDhAccepted=false`、`performanceAccepted=false`、`releaseAccepted=false`，26/26 只作受控顺序/背景证据，不外推为性能或发布验收。上述均不构成本轮阻断，但不得外推为任意 DH/光影包/GPU/分辨率的通用保证。
- 历史保留：本报告 §0–§8 保留 r34 初审（旧 SHA `787b…`、walk-16 反例、DH 双合成阻断）与 r34.1/Bec 待验记录；最终结论只对 r34.2 SHA `55337b…` 与上述证据范围成立。


