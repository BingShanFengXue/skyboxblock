# 移植计划：skyboxblock 1.21.1 → Minecraft 26.2（+ 邻版 26.1 / 26.3）

- 建立时间：2026-11-01
- 母版基线：`skybox-mod` = `1.0.5-test.1-r38`（jar SHA256 `2904bb8d35e93b2d0effefd625c60d348ac2cfe6e9783ae11fc8f3ed94110817`）
- 移植线目录：`skybox-mod-26.2/`（**1.21.1 主线原样保留、可继续构建发布**）
- 目标版本：26.2「Chaos Cubed」（母版）→ 26.1、26.3（邻版，**不做光影测试**）

---

## 0. 一句话结论

这次不是"改改包名"。**26.2 引入了可切换的 Vulkan 后端，官方明确「不支持裸 OpenGL」**
（[Fabric 文档原文](https://docs.fabricmc.net/develop/rendering/basic-concepts)：*Use of raw OpenGL is not supported on Minecraft 26.2 … you must use the `Blaze3D` abstraction layer*）。
而本模组的全部价值都压在 GPU 底层：**17 个文件、106 行 `org.lwjgl` 直调、270 处 Blaze3D/GL 符号**
（`GL30.glGenFramebuffers` / `glFramebufferTexture2D` / 模板缓冲 / `GlStateManager._*` 私有访问器 / 13 条自研 core shader）。
所以本次移植的工程量 ≈ **把渲染层按 Blaze3D 抽象重写一遍**，其余部分（配置、命令、存档、网络、自定义天空盒）是"搬运 + 适配"。

> **路线已定（2026-11-01，用户决策）：S2 —— 全量按 Blaze3D 抽象层重写**，不做"只跑 OpenGL 后端"的 S1 退路。
> 连带代价：模板缓冲（云/星空的 cookie-cut）在抽象层不存在，必须换算法（见 §6c）。

---

## 1. 版本事实（实测抓取，2026-11-01）

| 项 | 1.21.1（现状） | 26.2（目标） | 26.3（邻版） |
|---|---|---|---|
| 名称 | — | Chaos Cubed | Wilderness Bound |
| 发布 | 2024-08 | 2026-06-16 | 2026-09-15 |
| 最低 Java | 21 | **25** | **25** |
| 协议号 | 767 | 776 | 777 |
| 数据版本 | 3955 | 4903 | 5023 |
| 资源包格式 | 34 | **88.0** | **97.1** |
| 数据包格式 | 48 | **107.1** | **121.0** |
| 关键变化 | — | 可选 Vulkan 后端；`BlockIds`/`ItemIds` 拆分；`Gui`/`Hud` 重组 | 内容向（生态群系/方块），渲染面待 diff |

来源：[Java Edition 26.2](https://minecraft.wiki/w/Java_Edition_26.2)、[Java Edition 26.3](https://minecraft.wiki/w/Java_Edition_26.3)、[Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html)。

**邻版事实（26.1「Tiny Takeover」，2026-03-24，Java 25，协议 775，数据版本 4786，资源包 84.0，数据包 101.1）**：
**26.1 已经完成** `ResourceLocation`→`Identifier` 改名、`ShaderInstance` 删除、`RenderSystem` 状态 API 清空、`RenderPipeline`/`RenderPass`/`GpuDevice`/`FrameGraphBuilder`/`SubmitNode` 上线（仅 OpenGL 后端）。
⇒ **26.1 与 26.2 是同一套渲染范式**，回迁 26.1 的差距是窄的（见 §9 P9）。

**26.3 则是包级地震**：整个 `com.mojang.blaze3d.*` 迁到 **Renderpearl**（`com.mojang.renderpearl.api|backend|frontend.*`），
且 **`RenderSystem#outputColorTextureOverride` / `#outputDepthTextureOverride` 被删除**；shader 语法换代（`#moj_import`→`#include`、`in/out` 强制 `layout(location=N)`）。
⇒ 26.3 不能"顺手带过"，要单独一轮（见 §9 P9）。


**工具链（已核对可用）**

| 组件 | 1.21.1 现状 | 26.2 目标 | 依据 |
|---|---|---|---|
| Gradle | 8.10.2 | **9.8.2** | Fabric 博客写的是 9.5.1，但 Loom 1.19.3 实测要求 ≥ 9.8.0（见 §9 踩坑 K1）；腾讯镜像 200 |
| Loom | 1.7.4 | **1.19.3**（stable，非 alpha） | `maven.fabricmc.net/.../fabric-loom/maven-metadata.xml` 实际列表 |
| Loader | 0.16.14 | **0.20.1** | 实例目录 + 示例模组 `gradle.properties` |
| Fabric API | 0.116.15+1.21.1 | **0.161.0+26.2** | 实例 `mods/` 内 2.5 MB jar 同版本 |
| JDK | 21 | **25**（`C:\Program Files\Java\jdk-25.0.4.1`，`javac 25.0.4.1` 已验证） | 26.2 最低 Java 25 |
| 映射 | Mojang 官方映射 | **没有映射（26.1 起官方不再混淆）** | 见 §9 K2：`officialMojangMappings()` 在 26.2 上直接报错 |

---

## 2. 源码盘点与风险分级

主源集 **68 个 Java 文件 / 约 9,342 行**（含 1,373 行实机回归），另加 2,053 行回归测试、1,152 行门户夹具、约 1,700 行 R30 探针。

| 档 | 含义 | 主要文件（行数） | 小计 |
|---|---|---|---|
| **A** | 纯逻辑，几乎原样搬运 | `SkyEnvironment`(447)、`SkyboxServerCommands`(509)、`SkyboxServerConfig`(370)、`SkyConfigParser`(521)、`SkyboxConfigPayload`(111)、`SkyboxNetworking`(46)、`SkyStyle`/`SkySkin`/`SkyBlock`/`SkyBlockEntity`/`SkyTimeClock`/`SkyboxSettings`(219) | ≈ 2,260 行 |
| **B** | 注册/资源/命令帧，少量适配 | `SkyboxBlockMod`(80)、`CustomSkyRegistry`(281)、`CustomSkyPack`(94)、`CustomSkyPacks`(66)、`CustomSkyCommand`(65) | ≈ 590 行 |
| **C** | **必须重写**（GL → Blaze3D） | `ItrpSkyBridge`(560)、`SeamScan`(497)、`SkyWorldRenderer`(433)、`ModCompat`(250)、`SkyVanillaClouds`(267)、`SkyGlState`(182)、`VanillaSkyScene`(158)、`SkySurfaceLayer`(144)、`PortalViewCapture`(118)、`SkyDisplayState`/`SkyExposureMeter`(198)、`SkyTemporalEdgeMask`(90)、`SkyCoverage`(87)、`DistantSkyDepth`(81)、`SkyCloudStencil`/`SkyCloudMesh`(156)、`SkyVanillaGeometry`/`SkyMesh`(131)、`SkyRenderMath`/`SkyEnvironmentMath`/`SkyCloudRange`(136 纯数学，可保留) | ≈ 3,490 行 |
| **D** | mixin 逐个重定位 | 14 个（`LevelRendererMixin`、`GameRendererMixin`、`CloudBuildRangeMixin`、`Iris*`×3、`DistantBlockIgnoreMixin` 等） | ≈ 420 行 |
| **E** | 回归/夹具/探针 | `RenderRegression`(1,373)、`BlockCollectionDiagnostics`(257)、`FixtureDistantTerrain`(377)、7 个 test(2,053)、portalFixture(1,152)、r30Probe(约 1,700) | ≈ 6,900 行 |

**决策后的范围变化（§6d）**：`ItrpSkyBridge`(560)、`PortalViewCapture`(118) 与 3 个 Iris/IP mixin **移出范围**；
`SkyGlState`(182)、`SeamScan`(497)、`SkyCloudStencil`(78)、`SkyVanillaClouds`(267)、`VanillaSkyScene`(158)、
`SkyTemporalEdgeMask`(90)、`SkyCoverage`(87)、`SkyExposureMeter`(99) 因"天空自绘"**不再需要移植**。
⇒ C 档从约 3,490 行缩到约 **700–900 行**（`SkyBlockEntityRenderer` + 新建的 pipeline/提交层 + 保留的纯数学 `SkyRenderMath`/`SkyEnvironmentMath`/`SkyCloudRange`）。

**为什么 C 档原来这么大**：本模组的技术核心是"抓当前帧 → 换 framebuffer → 用自己的 shader 把天空画到方块面上 → 抠深度/模板保证云和日出正确 → 还原 GL 状态"。
这条路在 1.21.1 是可行的，因为 `GlStateManager._*` 与 `org.lwjgl` 都在。26.2 把它变成**后端无关的 `GpuDevice`/`RenderPipeline`/`RenderPass`**，
`GlStateManager` 这类"直接摸 GL 状态"的接口是 Vulkan 后端下无法映射的东西 —— 必须逐条找到抽象层等价物，找不到的就要改设计（见 §6 未知项 U4/U5/U7）。

---

## 3. 阶段计划（每阶段都有门禁，过不了不进下一阶段）

> **2026-11-01 决策后，P3/P5 的形态已改写**：天空自绘（零 mixin）+ BE 提交几何，IP 退出范围。详见 §6c/§6d。

### P0 立项与基线 ✅（本轮已开工）

- 新建 `skybox-mod-26.2/`：Gradle wrapper 指向 9.5.1（腾讯镜像）、`gradle.properties` 写入 26.2/0.20.1/1.19.3/0.161.0+26.2、JDK 25 登记到 toolchain。
- 版本号：`1.0.6-test.0-port.N`（内部试作），实机跑通后才转 `1.0.6-test.1-262`。
- **门禁**：`gradlew classes` 成功 + named jar 落地 + `javap` 能读出目标类。

### P1 API 探针 ✅ 第一轮完成（2026-11-01）

- 方法：`dumpCp` 导出 classpath → `jar tf` 全量类表 → `javap -p -s` 逐个核对签名；必要时 `gradlew genSources`（只读反编译）。
- 产出：`docs/API-MATRIX-26.2.md`，逐条"存在/改名/签名/替代品"。
- **门禁**：§6 的 U1–U12 全部有实测答案，不允许"大概应该有"。

### P2 骨架搬运（能进游戏）—— **已开工**

现状：`tools/port_copy.py` 已把 163 个源文件搬进 `skybox-mod-26.2/src/main`（仅两处确定性改名），
`gradlew compileJava` 报 **357 → 338 个错误**（第一轮按 `COMPILE-GAP §5` 修掉 19 处：`clientboundPlay`、`PermissionSet`、
`MetadataSectionType`、`FabricCreativeModeTab`、`BlockEntityType` 构造器、`Properties.setId`、`items/` 资源路径、
`ResourceKey.identifier()`、`ChunkPos.pack()`、`addProperty(null)` 歧义），剩余缺口见 `docs/COMPILE-GAP-26.2.md`，
渲染层重建规格见 `docs/P3-SPEC.md`。

- 搬 A 档 + B 档 + `SkyBlock`/`SkySkin`；渲染先用最朴素的 `RenderType`/`RenderPipeline` 画 6 个面（美术贴图当假材质）。
- 这一步同时验证 26.x 的四件事：**注册表冻结时机、`BlockIds`/`ItemIds` 拆分、命令参数可序列化性、`CustomPacketPayload` codec**。
- **门禁**：客户端加载 0 ERROR；方块可放置/可显示；`/skyboxblock environment` 与 `/skyboxblock player` 全部命令生效；`skyboxblock-players.properties` 真正落盘（r38 遗留的实机未验项）。

### P3 渲染主干重建（决策后已大幅简化）

| 子步 | 内容 | 门禁 |
|---|---|---|
| P3.1 | **自绘天空（零 mixin）**：`new SkyRenderer(textureManager, atlasManager, 自建 RenderTarget)` + `LevelRenderEvents.START_MAIN`，每帧渲染一次到自有 target，先导出 PNG 验证 | 导出的天空纹理与原版同视角截图方向/颜色一致 |
| P3.2 | **面绘制（零 mixin）**：`BlockEntityRenderer<S>.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` + `submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`；`RenderType.create(String, RenderSetup.builder(pipeline))` | 方块六面显示天空；可放置/可破坏/掉落正常 |
| P3.3 | **状态语义**：depth/blend/colorMask 全进 pipeline 描述（`CompareOp`/`BlendFunction`/`writeMask`），不再有 `RenderSystem` 状态调用 | 与 1.21.1 版同 seed 同视角逐像素对照 |
| P3.4 | **云/星空/日出**：交给 `SkyRenderer` 自己画（`renderSunMoonAndStars` / `renderDarkDisc` …），**自研 cookie-cut 作废** | 昼/夜/末地三场景截图 |
| P3.5 | **兼容**：Sodium（必须跑通）、Iris（只需不崩）、DH（用户装好后接 compileOnly） | 实例里逐个跑一遍 |

- **门禁**：昼/夜/末地三张截图与 1.21.1 版**同 seed 同视角对照**（`docs/screenshots/` 归档）。

### P4 shader 迁移

- 13 条 pipeline（`world_sky`、`sky_resolve`、`sky_material_background`、`sky_meter_proxy`、`sky_surface_composite`、`seam_diagnostic`、`sky_depth_marker`、`sky_edge_history`、`vanilla_*`×6）迁到 26.2 的 JSON/VSH/FSH 格式。
- 后处理类优先改走官方后处理链，而不是继续自建 FBO 乒乓。

### P5 mixin 重定位 —— 决策后只剩 **1 处必需**

| mixin | 26.2 状态 |
|---|---|
| `CustomSkyPackMixin`（`@ModifyVariable` 往 `MultiPackResourceManager` 追加 pack） | **必需**，且 Fabric 自己也是 `@Mixin(MultiPackResourceManager) + @Inject(<init>, TAIL)`，seam 成立 |
| `GameRendererMixin`（帧末合成） | **暂不需要**（自绘路径不抓帧） |
| `LevelRendererMixin` | **不需要**（不改主世界天空；自绘走 `LevelRenderEvents.START_MAIN`） |
| `Iris*` ×3、`ItrpSkyBridge`/`PortalViewCapture` | **移出范围**（IP 不做；Iris 只需不崩） |
| `CloudBuildRangeMixin`/`CloudMeshAccess`/`BufferSourceMixin`/`FogColorAccess`/`Regression*` ×3 | 随自研云作废，删/留档 |

### P6 回归与夹具移植

- 7 个回归程序按新 API 改写，**断言数不得下降**：`SkyEnvironmentRegression` 283、`ServerCommandRegression` 782、`SkyboxServerBackendRegression` 109，其余 42,257 / 389,385 / 68,012 全绿。
- `tools/verify-javac.ps1` 需要为 26.2 重新导出 classpath（`dumpCp`）。

### P7 实机矩阵（26.2，含光影）

- 测试实例：`D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1`
  （已含 `fabric-api-0.161.0+26.2`、`iris-fabric-1.11.4+mc26.2`、`sodium-fabric-0.9.2+mc26.2`）
- 场景：原版 → Sodium → Iris + Complementary → 多人（按玩家命令生效）。

### P8 打包发布

- `1.0.6-test.1-262` + README/docs 更新 + 旧产物归档到 `output/archive-superseded/`。

### P9 邻版 26.1 / 26.3（母版跑通之后）—— 两个版本难度**完全不同**

**26.1（窄）**：与 26.2 同一套渲染范式（`RenderPipeline`/`RenderPass`/`GpuDevice`/`FrameGraphBuilder`/`SubmitNode`、`Identifier` 都已在 26.1 落地）。
实际差集只有三块（来自官方示例 26.1.2 vs 26.2 的代码级 diff）：

| 差集 | 26.1 写法 | 26.2 写法 |
|---|---|---|
| 顶点提交 | `BufferBuilder` + `MappableRingBuffer` + `mapBuffer/memCopy` | `StagedVertexBuffer.appendDraw/upload/getExecuteInfo/endFrame` |
| 索引提交 | `uploadImmediateIndexBuffer` / `getSequentialBuffer` | `ExecuteInfo` |
| MRT 附件 | `createRenderPass(..., OptionalInt)` | `createRenderPass(..., Optional<GpuTextureView>)` |
| 其它 | `setVertexBuffer(0, GpuBuffer)`、`drawIndexed(baseVertex,0,count,1)`、`getModelViewMatrix()`、`client.getMainRenderTarget()` | `(0, GpuBufferSlice)`、`drawIndexed(count,1,first,base,0)`、`getModelViewMatrixCopy()`、`gameRenderer.mainRenderTarget()` |

→ 布局：**共享主干 + `src/26.1/java` 覆盖 1–3 个文件**（提交层 + 附件层），`-Pmcver=26.1` 切构建。

**26.3（大）**：`com.mojang.blaze3d.*` 整体搬进 **Renderpearl**（`com.mojang.renderpearl.api|backend|frontend.*`），
`RenderSystem.outputColorTextureOverride/outputDepthTextureOverride` **被删除**，shader 语法换代（`#include` + `layout(location=N)`），资源包 88.0 → 97.1（9 次 bump）。
→ 布局：**渲染层整类分叉**（独立 `src/26.3/java` 的 GPU 层），并**重设计抓帧机制**（不再依赖 override 字段，改成"显式附件 + try-with-resources RenderPass"）。
→ 26.3 是"第二轮移植"，不是"顺手带过"。

- 两版都先用**同一套探针**生成 API-MATRIX 做三方 diff，diff 结果落到 `docs/API-MATRIX-26.1.md` / `-26.3.md`。
- **26.1 / 26.3 不做光影测试**：只验原版渲染 + 命令 + 多人 + 自定义天空盒；Iris 只在 26.2 编入验证。

---

## 4. 验收标准（什么叫"移植完成"）

1. 26.2：`gradlew build` 全绿，回归断言数 ≥ 1.21.1 版。
2. 26.2：实机四场景通过（原版 / Sodium / **Iris+Complementary（只验不崩）** / 多人）。
3. 26.2：自定义天空盒（Q1）与按玩家设置（Q2）功能与 1.21.1 版行为一致。
4. 26.1 / 26.3：原版渲染 + 命令 + 多人通过，光影一栏标记"未测（按计划跳过）"。
5. 文档：`API-MATRIX-26.2.md`、`PORT-NOTES.md`（每个被替换的 GL 调用列出"原语义 → 新写法"）。

---

## 5. 工期估算（按 1 人 + 子个体并行）

| 阶段 | 估时 | 备注 |
|---|---|---|
| P0–P1 | 0.5–1 天 | 主要是下载与核对 |
| P2 | 1–2 天 | 有 A/B 档可搬，风险低 |
| P3 | **3–5 天** | 自绘路径砍掉了抓帧/GL 状态机/自研云，剩余是"建 pipeline + BE 提交" |
| P4 | 1–2 天 | 13 条 pipeline |
| P5 | 1–2 天 | 14 个 mixin |
| P6 | 1–2 天 | 回归改写 |
| P7–P8 | 1–2 天 | 实机 + 打包 |
| P9 | 1–3 天/版本 | 取决于 diff 大小 |

---

## 6. P1 第一轮实测结论（2026-11-01，详见 `API-MATRIX-26.2.md`）

工具链已经点亮：`GRADLE 9.8.2 + Loom 1.19.3 + JDK 25 + MC 26.2 + Fabric API 0.161.0+26.2`，
`gradlew classes dumpCp` **BUILD SUCCESSFUL**，产出未混淆的 `minecraft-merged-…-26.2.jar`（10,952 个 class）。

| 原未知项 | 实测答案 |
|---|---|
| U1 `ShaderInstance` / core shader JSON | **类已不存在**；改为 `RenderPipeline`（Builder：`withVertexShader`/`withFragmentShader`/`withShaderDefine`/`withDepthStencilState`/`withColorTargetState`/`withBindGroupLayout`…） |
| U2 `RenderPipeline`/`RenderPass` 字段 | 见矩阵 §2.1/§2.2：状态写在 pipeline，pass 只负责 `setPipeline`/`bindTexture`/`setUniform`/`draw*` |
| U3 模板缓冲是否有等价物 | **没有**。`DepthStencilState` 只有 `depthTest/writeDepth/depthBias`，无 stencil 段 → 云的「cookie-cut」方案必须重设计 |
| U4 能否拿到当前帧颜色/深度纹理 | 26.2 能：`RenderTarget#getColorTexture()/getColorTextureView()/getDepthTexture()`，且 `RenderSystem.outputColorTextureOverride` 仍是 public 字段。**但 26.3 已把这两个 override 字段删除** ⇒ 抓帧机制从设计起就不许依赖它，改用"显式附件 + try-with-resources `RenderPass`" |
| U5 `GlStateManager._*` 是否还在 | **还在**，但搬到 `com.mojang.blaze3d.opengl`（GL 后端专用）→ Vulkan 下无效 |
| U6 `VertexBuffer`/`BufferBuilder` | `VertexBuffer` **已删除**；替代为 `MeshData` + `GpuBuffer` + `StagedVertexBuffer` |
| U7 MRT（`glDrawBuffers`） | 抽象层支持：`withColorTargetState(int, …)` + `withUnusedColorTargetState(int)`，`MAX_COLOR_TARGETS` 常量 |
| U8 `MultiPackResourceManager` 构造签名 | **完全没变**：`(PackType, List<PackResources>)` → 自定义天空盒注入方案原样可用 |
| U9 `BlockIds`/`ItemIds` 拆分 | 确认存在：`net.minecraft.references.BlockIds` / `BlockItemIds` / `ItemIds` |
| U10 命令参数序列化 | 待 R5/R2 轮确认（`EntityArgument` 仍在，收窄验证放到 P2） |
| U11 `CustomPacketPayload` | 仍在（`$Type`/`$TypeAndCodec`） |
| U12 Iris 注入点 | 需实机验证；Iris 1.11.4+mc26.2 已在测试实例里，P3.5 处理 |

**额外发现（比原计划更关键的改动）**

- `net.minecraft.resources.ResourceLocation` → **`Identifier`**：全仓机械改名，A/B 档也要动。
- `RenderSystem` 的**状态 API 全删**（无 `setShader`/`enableBlend`/`depthMask`/`colorMask`/`setShaderTexture`），只剩设备、矩阵、雾/投影 buffer、scissor、`outputColorTextureOverride`。
- 新增 `com.mojang.blaze3d.framegraph.FrameGraphBuilder`/`FramePass`：官方"往帧里插 pass"的正规入口；`PostChain` 已接帧图（`addToFrame`）。
- `RenderType` 搬到 `net.minecraft.client.renderer.rendertype`（旁边是新体系 `RenderTypes`/`RenderSetup`/`PreparedRenderType`/`OutputTarget`）。

## 6b. 尚未实测的项（P1 第二轮）

| ID | 待确认 | 查法 |
|---|---|---|
| R1 | `MultiBufferSource` 的替代物是谁 | `jar tf` 全量 + 反查 `LevelRenderer` 签名 |
| R2 | `LevelRenderer` 现行渲染主流程与 pass 划分（决定 `LevelRendererMixin` 怎么写） | `javap -p LevelRenderer` |
| R3 | `GameRenderer` 帧末钩子位置 | `javap -p GameRenderer` |
| R4 | `PackResources` 接口方法是否变形 | `javap -p PackResources` |
| R5 | `BlockIds`/`ItemIds` 拆分后的注册写法 | `javap -p net.minecraft.references.BlockIds` + `Blocks` |
| R6 | 是否有官方 sky 钩子可替代自家 `LevelRendererMixin` | 同 R2 |
| R7 | `EntityArgument` 是否仍参与命令树网络序列化 | `javap` + 实机 P2 验证 |
| R8 | 26.1 / 26.3 的同一套矩阵（P9 用） | 换 `minecraft_version` 后重跑同一脚本 |

---

## 6c. 路线决策记录：S2（抽象层重写）已选定

**2026-11-01 决策：走 S2，全量改用 `RenderPipeline` / `RenderPass` / `GpuTexture` / `FrameGraphBuilder`。**

| | S1（未采用） | **S2（采用）** |
|---|---|---|
| 做法 | 保留裸 GL，`GlStateManager` 换包名，`RenderSystem` 删掉的状态 API 用裸 GL 顶 | 一切绘制进 pipeline 描述；资源走 `GpuDevice`；帧内插入走 `FrameGraphBuilder` |
| Vulkan 后端 | ❌ | ✅ |
| 云/星空 cookie-cut | ✅ 保原方案 | 必须重设计（见下） |
| 工期 | 2–3 天到"能看" | **1–2 周** |

**模板缓冲的替代设计（S2 下的强制项）**

抽象层没有 stencil，但 **MRT 还在**（`RenderPipeline.Builder#withColorTargetState(int, ColorTargetState)` + `withUnusedColorTargetState(int)`，`ColorTargetState` 带 `writeMask`）。所以云端"哪些像素已经画过天空"这件事，从"stencil 位"改成"遮罩纹理"：

1. 第一遍：画天空面，同时用第二个颜色附件只写 R 通道（`writeMask = WRITE_RED`）记录覆盖度；
2. 第二遍：画云，在片元着色器里采样该遮罩，按覆盖度决定是否丢弃 / 调制；
3. 第三遍：把遮罩附件清空（或每帧重建），成本 = 一张 1 通道纹理。

原先 stencil 的另一个用途（同一面片多材质叠加只画一次）改用"深度预写 + `CompareOp.EQUAL`"在 pipeline 的 `DepthStencilState(depthTest, writeDepth)` 里表达，已实测可用。

## 6d. 决策记录 2：天空来源 + 兼容范围（2026-11-01）

**天空内容 = 自绘（零 mixin）**。用户口径：*"本来也就是跟无光影下一致吧…只是要兼容光影运行"* —— 即
**验收标准是"无光影下与原版天空一致"**，光影只要能正常运行（不崩、不穿帮到不可用）即可；
光影环境下的细微不一致允许存在、后续可调。

⇒ P3 走 R6 的第②个切入点：

```java
new SkyRenderer(textureManager, atlasManager, 自建 RenderTarget)   // 全部 public
  + LevelRenderEvents.START_MAIN（Fabric，零 mixin）              // 每帧渲染一次天空到自己的 target
  → 方块面用 BlockEntityRenderer.submit(...) + submitCustomGeometry(...) 采样这张纹理
```

**这直接砍掉了原来的核心难点**：不需要抓主帧、不需要换 framebuffer、不需要保存/恢复 GL 状态，
`SkyGlState`(182) / `SeamScan`(497) / `SkyCloudStencil`(78) / `SkyVanillaClouds`(267) / `VanillaSkyScene`(158) /
`SkyTemporalEdgeMask`(90) / `SkyCoverage`(87) / `SkyExposureMeter`(99) 这一大批**整体不再需要移植**（约 1,400 行）。

**兼容范围 = 只做 DH，移除 IP**。用户口径：*"只考虑 DH。我会在测试版本加入 DH。IP 本身没有兼容更新版本的版本，所以不用。"*
⇒ `ItrpSkyBridge`(560) + `PortalViewCapture`(118) + 3 个 Iris/IP mixin 移出移植范围（源码留档不删）；
`ModCompat`(250) + `DistantSkyDepth`(81) 保留，等用户在测试实例里装好 26.2 的 DH 后接 compileOnly。

**Iris 的处理**：26.2 只要求"装了 Iris + Complementary 能正常进游戏、方块能画、不崩"，
不再追"方块面与光影天空逐像素一致"。

## 7. 风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| 26.2 抽象层拿不到"任意时刻读写当前帧纹理"的能力 | 中 | 退一步：只在 sky pass 内用 `RenderPass` 采样；最坏情况把"抓帧"降级为"按维度重建天空"（功能缩水，需用户确认） |
| 模板缓冲无等价物 | **已确认（实测）** | 走 §6c 的 MRT 遮罩纹理方案；stencil 的"只画一次"用途用深度预写 + `CompareOp.EQUAL` 表达 |
| 1.21.1→26.2 之间 12 个版本的 API 漂移叠加 | 高 | 不设中间跳板，直接对 26.2 重写；靠 P1 探针而非记忆 |
| 光影（Iris）在 26.2 的 API 变了 | 中 | 3 个 Iris mixin 独立成组，可单独禁用，退化为"光影下不接管天空" |
| 26.1 与 26.2 渲染抽象差异过大 | **已排除（实测）** | 26.1 已是同一套 `RenderPipeline`/`RenderPass`/`FrameGraph`，差异只在提交层与附件类型 |
| 26.3 是包级重建（Renderpearl）+ 抓帧字段被删 | **已确认** | 26.3 单列一轮；抓帧机制从设计上就不依赖 `outputColorTextureOverride`，改用"显式附件 + try-with-resources RenderPass" |
| 邻版无光影测试导致兼容黑洞 | 低 | 明确写入 README："26.1/26.3 未做光影验证" |

---

## 8. 下一步（P1 第二轮）

已完成：工具链点亮（`BUILD SUCCESSFUL`）、26.2 class 表、`RenderSystem`/`GlStateManager`/`RenderPipeline`/`RenderPass`/`GpuDevice`/`RenderTarget`/`DepthStencilState`/`ColorTargetState`/`PostChain`/`MultiPackResourceManager` 的签名核对 → `docs/API-MATRIX-26.2.md`。

待办（顺序执行，每项都是 `javap` 一次就能出结果）：

1. R1/R2/R6：`LevelRenderer` + `GameRenderer` 的现行结构与 sky 钩子（决定 P5 mixin 怎么写）。
2. R4/R5：`PackResources` 接口 + `BlockIds`/`ItemIds` 注册写法（决定 P2）。
3. R7：命令参数序列化面（决定 Q2 命令能否原样保留）。
4. 路线决策（S1/S2）落定后，开始第一批真代码：`ResourceLocation`→`Identifier` 机械改名 + A 档搬运。

---

## 9. 踩坑记录（移植期实测）

### K1 Loom 1.19.3 要求 Gradle ≥ 9.8.0

Fabric 博客（2026-06-15 发）写的是「Loom 1.17 + Gradle 9.5.1」，但 maven 上 stable 已是 **1.19.3**。
在 Gradle 9.5.1 下直接失败：

```
Plugin net.fabricmc:fabric-loom:1.19.3 requires at least Gradle 9.8.0. This build uses Gradle 9.5.1.
```

→ 换 Gradle **9.8.2**（腾讯镜像实测：9.8.2 / 9.8.0 都有，9.7 / 9.6 / 9.8 没有）。
教训：文档里的"推荐版本"会随 Loom 发版漂移，**以插件自己报的最低 Gradle 为准**。

### K2 26.1 起官方不再混淆：既没有 mappings，也没有 remap

第二次失败：

```
Failed to setup Minecraft, java.lang.RuntimeException: Failed to find official mojang mappings for 26.2
```

原因见 [Migrating Mappings 文档](https://docs.fabricmc.net/develop/porting/mappings/)：Mojang 从 26.1 起发布**未混淆**的 Java 版，
Fabric 同步停止维护第三方映射（Yarn 于 2025 年停止）。Minecraft 26.1+ 直接带可读类名与参数名。

正确的 26.2 `build.gradle`（对照 [fabric-example-mod 26.2 分支](https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/build.gradle)）：

```groovy
plugins { id 'net.fabricmc.fabric-loom' version "${loom_version}" }   // 插件 id 变了，不再是 'fabric-loom'
dependencies {
    minecraft "com.mojang:minecraft:26.2"
    implementation "net.fabricmc:fabric-loader:0.20.1"              // 不再 modImplementation
    implementation "net.fabricmc.fabric-api:fabric-api:0.161.0+26.2"
}
```

**连带影响（移植时必须同步处理）**：

| 项 | 1.21.1 写法 | 26.2 写法 |
|---|---|---|
| 映射 | `mappings loom.officialMojangMappings()` | **整行删除** |
| 依赖 | `modImplementation` / `modCompileOnly` | `implementation` / `compileOnly` |
| mixin refmap | `loom.mixin.useLegacyMixinAp = true` + `add sourceSets.main, 'xxx-refmap.json'` | **不再需要**（目标就是真名），配置要删 |
| access widener / class tweaker | 需要映射迁移 | 直接写真名 |
| 多版本 | 每版一套 intermediary | **26.1/26.2/26.3 共用同一套真名**，只剩 API 兼容问题 |

最后一条对 §5 的邻版计划影响很大：**邻版不再有"映射分叉"这一层**，26.1/26.3 的工作量比原估计低。

### K3 `RenderSystem` 的状态 API 被清空

26.2 的 `com.mojang.blaze3d.systems.RenderSystem` 只剩：设备（`getDevice`）、矩阵栈、`setShaderFog`/`setShaderLights`（`GpuBufferSlice`）、`setProjectionMatrix`、scissor、`outputColorTextureOverride`。
`setShader` / `enableBlend` / `depthMask` / `colorMask` / `enableDepthTest` / `setShaderTexture` / `getShaderTexture` **全部不存在**。
模组里 34 处 `RenderSystem.*` 调用（`SkyGlState` 一家就占 20 处）必须逐条找替代：进 pipeline 描述、进 `RenderPass`，或用裸 GL。

### K4 `GlStateManager` 搬进了 `blaze3d.opengl`

类还在（106 个方法），`_glBindFramebuffer`/`_viewport`/`_blendFuncSeparate`/`_pixelStore`/`_glFramebufferTexture2D`/`_colorMask`/`_clearBuffer` 都在，但**包名变成了 `com.mojang.blaze3d.opengl`**。
语义上它现在是"OpenGL 后端专用"的东西 —— Vulkan 后端下调用它没有意义。这条决定了 §6c 的 S1/S2 分叉。

### K5 `ShaderInstance` 消失，`PostChain` 接进了帧图

13 条自研 core shader 不能再 `new ShaderInstance(...)`。替代：
- 面绘制：`RenderPipeline.Builder#withVertexShader/withFragmentShader/build()`；
- 后处理：`PostChain.load(PostChainConfig, …)` + `addToFrame(FrameGraphBuilder, w, h, TargetBundle)`。

### K6 抽象层没有模板缓冲

`DepthStencilState` 只有 `(CompareOp depthTest, boolean writeDepth, float biasScale, float biasConstant)`。
云端的「stencil 抠洞 + EQUAL 测试」在 Vulkan 后端无法表达 —— 这条路只能在 S1（GL 独占）里保留，S2 要换算法（深度预写 + 两遍绘制 + 全屏遮罩）。

### K7 `ResourceLocation` → `Identifier`

`net.minecraft.resources.Identifier` 才是现在的名字。全仓 100+ 处引用，属于机械改名，建议**第一批就做完**（编译错误从几千降到几百，后面才好分批推进）。

### K8 好消息：天空盒注入的切入点没变

`MultiPackResourceManager(PackType, List<PackResources>)` 签名一字未改，`PackResources` 仍是 `net.minecraft.server.packs` 下的接口。
→ `CustomSkyPackMixin`（`@ModifyVariable` 追加自建 pack）可以原样搬，只需处理 `Identifier` 改名与 `PackResources` 内部方法签名。


---

## 附录：证据来源

- [Fabric for Minecraft 26.2（官方博客）](https://fabricmc.net/2026/06/15/262.html)
- [移植到 26.2（Fabric 文档）](https://docs.fabricmc.net/zh_cn/develop/porting/)
- [Basic Rendering Concepts 26.2（含"不支持裸 OpenGL"声明）](https://docs.fabricmc.net/develop/rendering/basic-concepts)
- [fabric-example-mod 26.2 分支 gradle.properties](https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle.properties)
- [Java Edition 26.2](https://minecraft.wiki/w/Java_Edition_26.2) / [Java Edition 26.3](https://minecraft.wiki/w/Java_Edition_26.3)
- 本机实测：`maven.fabricmc.net` 200、`piston-meta.mojang.com` 200、腾讯 Gradle 镜像 200、`jdk-25.0.4.1` 的 `javac 25.0.4.1`
