# 编译缺口清单：1.21.1 源码 → 26.2（实测）

- 时间：2026-11-01
- 做法：把 `skybox-mod/src/main` **原样**（仅做 `ResourceLocation`→`Identifier`、`GlStateManager` 包移动两处确定性改名）复制到 `skybox-mod-26.2/src/main`，
  然后 `gradlew compileJava`（`-Xmaxerrs 4000`）数真实缺口。
- 结果：**357 个编译错误**。原始日志：`build/compile-26.2-full.log`（4,050 行）。
- 复制脚本：`tools/port_copy.py`（Python，UTF-8 strict；文件数 163，改名命中 19 个文件 / 53 处）。

> 这张表就是 P2 的真实工作清单。每一行都是一类"改一次能消一片"的东西，按消掉后的收益排序。

## 1. 缺失符号（按出现次数）

| 出现 | 符号 | 26.2 现状 / 替代 |
|---|---|---|
| 49 | `net.minecraft.client.renderer.ShaderInstance` | **类已删除**；改为 `RenderPipeline` + `RenderPipeline.Builder`（见 API-MATRIX §2.1） |
| 8 | `net.minecraft.client.renderer.texture.SpriteTicker` | 包位置或名字变了（待 R1/R4 实测确认） |
| 6 | `com.mojang.blaze3d.vertex.VertexBuffer` | **已删除**；改为 `GpuBuffer` + `MeshData` + `StagedVertexBuffer` |
| 5 | `net.minecraft.client.renderer.RenderType` | 搬到 `net.minecraft.client.renderer.rendertype.RenderType` |
| 4 | `net.minecraft.client.renderer.LightTexture` | 位置/名字变了（待查） |
| 3 | `com.mojang.blaze3d.vertex.Tesselator` | 已删除；`BufferBuilder` 直接构造 + `MeshData` |
| 3 | `net.minecraft.client.renderer.DimensionSpecialEffects` | 位置/名字变了；天空接管方式要重新选（见 R6） |
| 2 | `net.minecraft.client.renderer.RenderStateShard` | 已删除；新体系是 `RenderSetup` / `RenderTypes` |
| 2 | `net.minecraft.server.packs.metadata.MetadataSectionSerializer` | 包位置或名字变了（直接影响 `CustomSkyPack`） |
| 2 | `net.minecraft.client.renderer.FogRenderer` | 位置/名字变了 |
| 2 | `com.mojang.blaze3d.shaders.Uniform` | 搬到 `com.mojang.blaze3d.opengl.Uniform`（GL 专用），抽象层用 `setUniform` + UBO |
| 2 | `net.minecraft.client.renderer.MultiBufferSource` | **已删除**（待 R1 找替代） |
| 1 | `com.mojang.blaze3d.vertex.BufferUploader` | 已删除 |
| 1 | `net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback` | Fabric 渲染 API 改版（待查新入口） |
| 1 | `net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup` | Fabric 物品组 API 改版（待查） |
| 1 | `net.minecraft.util.ParticleStatus` | 位置/名字变了 |
| 1 | `net.fabricmc.fabric.api.client.command.v2.ClientCommandManager` | Fabric 客户端命令 API 改版 |
| 1 | `net.minecraft.Util` | 位置/名字变了 |
| 4 | `com.seibel.distanthorizons.api.*` | **不是版本问题**：DH compileOnly jar 还没接到 26.2 工程；DH 是否有 26.2 版本本身也是待定项 |

## 2. 非"缺类"错误（签名/语义变化）

| 现象 | 位置 | 说明 |
|---|---|---|
| `ResourceKey<Level>.location()` 不存在 | `BlockCollectionDiagnostics` | `location()` 很可能改名（`Identifier` 体系） |
| `ChunkPos.asLong(int,int)` 不存在 | `BlockCollectionDiagnostics` | 静态工厂改名或移除 |
| `BlockEntityRenderer<T>` 类型参数需要 2 个 | `SkyBlockEntityRenderer` | 26.2 的 BER 泛型化了（多了 render state 参数） |
| `addProperty(String, <null>)` 调用歧义 | `BlockCollectionDiagnostics` | `state==null ? null : …` 的 `null` 分支让重载解析失败（Java 9+ 行为），要显式转型 |

## 3. 修复顺序（按收益/风险）

1. **`ShaderInstance`（49 处）**：这是最大的一块，也是 S2 路线的核心。做法：把 13 条 core shader 改成 `RenderPipeline` 常量 + `RenderPass` 提交；`ShaderInstance` 的 `getUniform`/`apply` 全部换成 `setUniform(GpuBufferSlice)`。
2. **`RenderType` / `RenderStateShard` / `MultiBufferSource`（9 处）**：定下新的"提交几何"写法（`rendertype` 包 + `MeshData`），一次定完，后面所有绘制都跟着改。
3. **`VertexBuffer` / `Tesselator` / `BufferUploader`（10 处）**：并入第 2 步。
4. **`SpriteTicker` / `LightTexture` / `FogRenderer` / `DimensionSpecialEffects` / `Util` / `ParticleStatus` / `MetadataSectionSerializer`**：纯"找新名字"，`javap` 或全量 class 列表一搜就有；顺手把 `Import` 修行。
5. **Fabric API 两处**（`CoreShaderRegistrationCallback`、`FabricItemGroup` / `ClientCommandManager`）：查 0.161.0+26.2 的替代 API。
6. **DH 相关**：先 `compileOnly` 挂起，或整套 disable，等 DH 有 26.2 版本再谈。

## 4. 一个已经能确定的好消息

`custom/` 三个文件里只有 `MetadataSectionSerializer` 一处缺失 —— 说明**自定义天空盒（Q1）的 Pack 机制在 26.2 基本没变**，只要补这一个类名就能编译。
`server/`（命令 + 存档）与 `SkyEnvironment`/`SkyConfigParser`（纯逻辑）**零错误**，A 档搬运是真的"搬"。

## 5. 已定位的修法（R4 / R5 / R7 实测，可直接照改）

| 现象 | 修法 | 落点 |
|---|---|---|
| `MetadataSectionSerializer` 不存在 | 换 `MetadataSectionType<T>`；`ModResourcePack` 已 deprecated → 直接实现 `PackResources` 或继承 `AbstractPackResources`（只需 5 个方法，metadata 由 `getRootResource("pack.mcmeta")` 提供） | `custom/CustomSkyPack.java` |
| `FabricItemGroup` 不存在 | `net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()`；往原版 tab 加物品用 `CreativeModeTabEvents.modifyOutputEvent` | `SkyboxBlockMod.java` |
| `BlockEntityType.Builder` 不存在（尚未报错，属运行期雷） | 公开构造器 `BlockEntityType(BlockEntitySupplier, Set<Block>)` 或 `FabricBlockEntityTypeBuilder.create(...).build()` | `SkyboxBlockMod.java` |
| `Item` 构造抛 `itemIdOrThrow`（运行期雷） | `Properties.setId(ResourceKey<Item>)`；方块侧同理 `BlockBehaviour.Properties.setId(ResourceKey<Block>)` | `SkyboxBlockMod.java` / 自定义 style 注册 |
| `CommandSourceStack.hasPermission(int)` 不存在 | `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`（vanilla `TimeCommand` 同写法） | `server/SkyboxServerCommands.java:285`（12 处 `.requires` 依赖它） |
| `PayloadTypeRegistry.playS2C()` 不存在 | `clientboundPlay()` | `network/SkyboxNetworking.java:23` |
| `CoreShaderRegistrationCallback` 不存在 | 直接建 `RenderPipeline`（core shader 走 pipeline 体系） | `client/SkyboxBlockClient.java` |
| `MultiBufferSource` / `BlockEntityRenderer<T>` 要 2 个类型参数 | `SubmitNodeCollector` + `BlockEntityRenderer<S>.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` | `client/SkyBlockEntityRenderer.java` |
| 自定义天空盒物品**图标不显示**（运行期雷） | 内存 pack 改产 `assets/<ns>/items/<id>.json`（`{"model":{"type":"minecraft:model","model":"…"}}`），不再产 `models/item/` | `custom/CustomSkyRegistry.java` |

**可以直接标"已验证可原样保留"的**：`/skyboxblock player <targets> <attr> [value]`（`EntityArgument` 未变）、`SkyboxConfigPayload.CODEC`（`StreamCodec` 方法名未变）、`MultiPackResourceManager` 注入 seam（Fabric 自己也这么注）。
