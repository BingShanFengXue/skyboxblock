# API MATRIX：Minecraft 26.2 实测（skyboxblock 移植用）

- 取样时间：2026-11-01
- 取样对象：`minecraft-merged-a8e9c7b123-26.2.jar`（Loom 1.19.3 产出，**未混淆**，10,952 个 class）
- 取样方法（可复现）：

```powershell
$jar = '...\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-a8e9c7b123\26.2\minecraft-merged-a8e9c7b123-26.2.jar'
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\jar.exe' tf $jar > build\mc262-classes.txt
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe' -p -classpath $jar <FQCN>
```

结论以 `jar tf` / `javap` 的**实际输出**为准，不采信文档与记忆。

---

## 1. 类存在性总表（1.21.1 用的名字 → 26.2 的现状）

| 1.21.1 用法 | 26.2 现状 | 影响 |
|---|---|---|
| `net.minecraft.resources.ResourceLocation` | **改名为 `net.minecraft.resources.Identifier`** | 全仓改名（A/B 档也要动） |
| `com.mojang.blaze3d.platform.GlStateManager` | **搬到 `com.mojang.blaze3d.opengl.GlStateManager`**，`_glBindFramebuffer`/`_viewport`/`_blendFuncSeparate`/`_pixelStore`/`_glFramebufferTexture2D`/`_colorMask` **全部保留** | GL 后端下可用；Vulkan 下无效 |
| `com.mojang.blaze3d.systems.RenderSystem` | 仍在，但**状态 API 全删**：没有 `setShader`、`enableBlend`、`depthMask`、`colorMask`、`enableDepthTest`、`setShaderTexture`、`getShader*` | `SkyGlState`/`VanillaSkyScene` 等全部改写 |
| `net.minecraft.client.renderer.ShaderInstance` | **不存在** | 13 条 core shader 全部改用 `RenderPipeline` |
| `com.mojang.blaze3d.vertex.VertexBuffer` | **不存在**；替代物是 `MeshData` + `GpuBuffer` + `StagedVertexBuffer` | `SkyMesh`/`SkyCloudMesh`/`SkyVanillaClouds` 重写 |
| `com.mojang.blaze3d.systems.RenderSystem.setShader` 体系 | 换成 `RenderPass#setPipeline(RenderPipeline)` + `bindTexture` + `setUniform` | 同上 |
| `com.mojang.blaze3d.platform.RenderTarget`（旧的 pipeline 包） | 仍在 `com.mojang.blaze3d.pipeline.RenderTarget`，但内部换成 `GpuTexture`/`GpuTextureView`：`getColorTexture()`、`getColorTextureView()`、`getDepthTexture()`、`getDepthTextureView()`、`copyDepthFrom()`、`blitAndBlendToTexture()` | 抓帧路径要改 |
| 无（新） | `com.mojang.blaze3d.framegraph.FrameGraphBuilder` / `FramePass` | 官方提供的"往帧里插 pass"的正规入口 |
| 无（新） | `com.mojang.blaze3d.systems.GpuDevice`（`createTexture`/`createBuffer`/`createSampler`/`createCommandEncoder`/`precompilePipeline`） | 资源创建统一入口 |
| `net.minecraft.client.renderer.PostChain` | 仍在，并已接入帧图：`addToFrame(FrameGraphBuilder,int,int,TargetBundle)`、`process(RenderTarget,GraphicsResourceAllocator)`；配置类 `PostChainConfig` | 后处理迁移有正规路径 |
| `net.minecraft.client.Screenshot` | 仍在 | 截图回归可保留 |
| `net.minecraft.server.packs.resources.MultiPackResourceManager` | **构造签名不变**：`(PackType, List<PackResources>)`；`PackResources` 仍是 `net.minecraft.server.packs` 下的接口（另有 `AbstractPackResources`/`PathPackResources`/`FilePackResources`/`CompositePackResources`） | **自定义天空盒注入的 mixin 切入点依然成立** |
| `net.minecraft.network.protocol.common.custom.CustomPacketPayload` | 仍在（`$Type`/`$TypeAndCodec`/`FallbackProvider`） | 配置同步只需小改 |
| `net.minecraft.world.level.block.Block` 注册 | 新版把 id 拆到 `net.minecraft.references.BlockIds` / `BlockItemIds` / `ItemIds` | 注册代码要按新写法 |
| `net.minecraft.client.renderer.RenderType` | 搬到 `net.minecraft.client.renderer.rendertype.RenderType`，旁边多了 `RenderTypes`、`RenderSetup`、`PreparedRenderType`、`OutputTarget`、`TextureTransform` | 面绘制入口 |
| `net.minecraft.client.renderer.MultiBufferSource` | **整仓无此类**（`*Source.class` 在 client/renderer 下也不存在） | 需在 P1 继续确认替代物 |

---

## 2. 关键签名摘录（原文摘自 javap）

### 2.1 `RenderPipeline.Builder` —— 状态全部进 pipeline

```
withLocation(...), withVertexShader(...), withFragmentShader(...), withShaderDefine(...)
withBindGroupLayout(BindGroupLayout)
withPolygonMode(PolygonMode), withCull(boolean)
withColorTargetState(ColorTargetState) / (int, ColorTargetState) / withUnusedColorTargetState(int)
withDepthStencilState(DepthStencilState) / (Optional<DepthStencilState>)
withVertexBinding(int, VertexFormat), withPrimitiveTopology(PrimitiveTopology)
buildSnippet(), build()
```

→ 1.21.1 里"每次绘制前手工 setDepthFunc/colorMask/blend"的写法，在 26.2 必须变成**预先声明若干条 pipeline**，绘制时只是 `setPipeline` 切换。

### 2.2 `RenderPass` —— 一次 pass 内的动作

```
setPipeline(RenderPipeline)
bindTexture(String, GpuTextureView, GpuSampler)
setUniform(String, GpuBuffer) / (String, GpuBufferSlice)
setVertexBuffer(int, GpuBufferSlice), setIndexBuffer(GpuBuffer, IndexType)
draw(int,int,int,int) / drawIndexed(...) / multiDraw* / drawIndirect(...)
enableScissor(int,int,int,int) / disableScissor()
pushDebugGroup(String) / popDebugGroup() / close()
```

→ **没有** stencil、没有 blend/depth 开关、没有 clear：这些都在 pipeline 描述里或 pass 创建时决定。

### 2.3 `DepthStencilState` —— **模板缓冲没有抽象**

```
public static final DepthStencilState DEFAULT;
DepthStencilState(CompareOp depthTest, boolean writeDepth)
DepthStencilState(CompareOp depthTest, boolean writeDepth, float biasScale, float biasConstant)
depthTest(), writeDepth(), depthBiasScaleFactor(), depthBiasConstant()
```

→ 26.2 的跨后端抽象里**只有深度，没有模板**。
`SkyCloudStencil`（云端 cookie-cut 依赖 stencil INCR + EQUAL 测试）在 Vulkan 后端**无法表达**；GL 后端下可继续用 `GlStateManager` + `GL11.glStencil*` 裸调，但那就是"放弃跨后端"。

### 2.4 `ColorTargetState` —— 颜色写掩码还在

```
ColorTargetState(BlendFunction)
ColorTargetState(Optional<BlendFunction>, GpuFormat, int writeMask)
WRITE_RED/GREEN/BLUE/ALPHA/COLOR/ALL/NONE, MAX_COLOR_TARGETS
```

→ `RenderSystem.colorMask(false,false,false,false)` 的等价物 = 一条 `writeMask = WRITE_NONE` 的 pipeline。**多 render target（MRT）也在**（`withColorTargetState(int, ...)`）。

### 2.5 `RenderTarget` / `GpuDevice`

```
RenderTarget: getColorTexture(), getColorTextureView(), getDepthTexture(), getDepthTextureView(),
              copyDepthFrom(RenderTarget), blitAndBlendToTexture(GpuTextureView, GpuTextureView), resize(int,int)
GpuDevice:    createTexture(...,GpuFormat,int,int,int,int), createTextureView(GpuTexture),
              createBuffer(Supplier<String>,int,long|ByteBuffer), createSampler(...),
              createCommandEncoder(), precompilePipeline(RenderPipeline[,ShaderSource])
```

→ 抓帧与"自建离屏 target"都还有路，只是从 `glGenFramebuffers` 换成 `GpuTexture` + `RenderPassDescriptor`。

### 2.6 `PostChain`

```
static PostChain load(PostChainConfig, TextureManager, Set<Identifier>, Identifier, Projection, ProjectionMatrixBuffer)
void addToFrame(FrameGraphBuilder, int width, int height, TargetBundle)
void process(RenderTarget, GraphicsResourceAllocator)
```

→ 后处理（`sky_resolve`/`sky_material_background`/`sky_meter_proxy`/`sky_surface_composite`）应当走 `PostChain` + `FrameGraphBuilder`，而不是自建 FBO 乒乓。

---

### 2.7 提交几何 / 渲染主干 / 天空 / 帧图（R1 R2 R6 R9 实测，详见 `docs/api/R1-R2-render-graph.md`）

**提交几何换了模型：`MultiBufferSource` 整仓不存在，改成"提交节点"。**

```
SubmitNodeCollector            （父接口 OrderedSubmitNodeCollector）
submitCustomGeometry(PoseStack, RenderType, SubmitNodeCollector$CustomGeometryRenderer)
  → 回调 CustomGeometryRenderer.render(PoseStack$Pose, VertexConsumer)   ← 顶点代码可原样搬
BlockEntityRenderer<S>.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)   ← BE 侧 1:1 替身
RenderType.create(String, RenderSetup) ← RenderSetup.builder(RenderPipeline)
```

**`LevelRenderer` 没有 `renderLevel`：**

```
render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc,
       GpuBufferSlice, Vector4f, boolean)
内部建局部 FrameGraphBuilder，pass 顺序：clear → sky（仅第 8 个 boolean 为真）→ main →
  entity_outline(后处理) → clouds → weather → transparency(后处理) → always_on_top → execute(...)
天空在 addSkyPass 里：早退条件（POWDER_SNOW / LAVA / doesMobEffectBlockSky / skybox==NONE），
  然后 addPass("sky").readsAndWrites(targets.main) → SkyRenderer.renderSkyDisc / renderEndSky /
  renderSunriseAndSunset / renderSunMoonAndStars / renderDarkDisc
```

**官方天空钩子：没有。** `DimensionSpecialEffects` 已删除，只剩数据驱动枚举 `DimensionType.skybox()`（`NONE/OVERWORLD/END`，无回调）。
但 `SkyRenderer` 构造器与 6 个渲染方法**全部 public 且自带 `RenderPass`**（画进构造时传入的 `RenderTarget`）→ **可以自己 new 一个、画进自己的 target**；但主世界那个是 `LevelRenderer` 内部 new 的，没有 setter。

**`FrameGraphBuilder` 拿不到。** 它是 `render(...)` 的局部变量；public 消费者只有 `PostChain.addToFrame(FrameGraphBuilder,int,int,TargetBundle)` 与 `PostPass.addToFrame(...)`。

### 2.8 资源包 / 注册 / 命令 / 网络（R4 R5 R7 R10 R11 实测，详见 `docs/api/R4-R5-packs-blocks.md`、`docs/api/R7-commands-network.md`）

**资源包**

```
PackResources       方法集不变，唯 getMetadataSection 形参：
                    MetadataSectionSerializer<T>  →  MetadataSectionType<T>（旧类整仓 0 命中）
AbstractPackResources  只剩"存 location + 惰性 metadata"，metadata 全来自 getRootResource("pack.mcmeta")，
                       返回 null ⇒ ResourceMetadata.EMPTY（字节码确认）
内存 pack 最少实现 5 个：getRootResource / getResource / listResources / getNamespaces(Set<String>) / close
MultiPackResourceManager(PackType, List<PackResources>)   一字未变
                    —— Fabric 自己就是 @Mixin(MultiPackResourceManager) @Inject(<init>, TAIL)，seam 成立
Fabric 侧没有运行时生成 pack 的公开入口：registerBuiltin*Pack 全要 ModContainer；PackRepository 只有 addPack(id)
                    ⇒ mixin 路线必须保留（现状方案成立）
```

**注册（三个真坑）**

```
BlockEntityType.Builder        已删除 → 公开构造器 BlockEntityType(BlockEntitySupplier, Set<Block>)
                                      或 FabricBlockEntityTypeBuilder.create(...).build()
Item / Block 的 Properties     构造 Item 时会调 Properties.itemIdOrThrow() → 必须先 .setId(ResourceKey<…>)
FabricItemGroup（itemgroup.v1）已删除 → net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
                                       往原版 tab 加物品：CreativeModeTabEvents.modifyOutputEvent
BlockIds / BlockItemIds / ItemIds   只是 ResourceKey 常量表，与注册动作无关
```

**资源 JSON 形态（内存 pack 必须按这个产出）**

- `blockstates/<id>.json`：仍是 `{"variants":{…}}`，值可为数组；
- **物品模型入口变成 `assets/<ns>/items/<id>.json`**：`{"model":{"type":"minecraft:model","model":"<ns>:block/…"}}`
  （`models/item/` 只剩 1272 个当 parent 用；`BlockStateModelGenerator` 已不存在，`BlockModelGenerators` 只用于 datagen）

**命令**

```
EntityArgument            完整保留（players()/getPlayers()… 签名未变）→ /skyboxblock player <targets> 可原样保留
ResourceLocationArgument  → IdentifierArgument（注册名仍是 minecraft:resource_location）
可序列化判据              ArgumentTypeInfos.BY_CLASS + BuiltInRegistries.COMMAND_ARGUMENT_TYPE
                          闸门：ClientboundCommandsPacket.createEntry → unpack → byClass（只查 argument 节点）
                          mod 侧正路：ArgumentTypeRegistry.registerArgumentType(Identifier, Class, ArgumentTypeInfo)
Commands.validate()       只在 SharedConstants.IS_RUNNING_IN_IDE 时跑 ⇒ 正式服无预检，错类型在发树时才炸
CommandSourceStack.hasPermission(int)   已删除 → Commands.hasPermission(Commands.LEVEL_GAMEMASTERS) 等
CommandRegistrationCallback.register(CommandDispatcher, CommandBuildContext, CommandSelection)  未变
```

**网络**

```
PayloadTypeRegistry.playS2C()/playC2S()/configurationS2C()   已无
  → clientboundPlay() / serverboundPlay() / clientboundConfiguration() / serverboundConfiguration()
  新增 registerLarge(Type, StreamCodec, int|IntSupplier) 与分片阈值 API
StreamCodec / StreamEncoder / StreamDecoder                 方法名一个没改（of/composite/dispatch/map/…）
ServerPlayNetworking.send / ClientPlayNetworking.registerGlobalReceiver(Type, PlayPayloadHandler)  形状不变
```

## 3. 由这些事实直接推出的移植策略

1. **两条路必须选一条**（详见 `PORT-PLAN.md` §6）：
   - **S1 GL 独占**：保留裸 GL，只把 `GlStateManager` 的包名改到 `.opengl`，`RenderSystem` 删掉的状态 API 用裸 GL 顶；代价 = 只能跑 OpenGL 后端，Vulkan 下直接放弃自定义面。
   - **S2 抽象层**：`RenderPipeline` + `RenderPass` + `GpuTexture` 重写，能上 Vulkan；代价 = 模板缓冲功能必须重新设计（云/星空）。
2. **`MultiPackResourceManager` 没变** → Q1 自定义天空盒的注入方式（`@ModifyVariable` 追加 `CustomSkyPack`）可以**原样搬运**，只需改 `ResourceLocation`→`Identifier`、`PackResources` 内部方法签名。
3. **`PostChain` 已接帧图** → 后处理迁移不是新发明，是"照官方写法接进去"。
4. **`ResourceLocation`→`Identifier` 是全仓机械改名**，可以先做（低风险、高频），做完编译错误会从"几千"降到"几百"，便于分批推进。

---

## 4. 还没实测的（下一轮 P1 收尾清单）

| ID | 待确认 | 查法 |
|---|---|---|
| R1 | `MultiBufferSource` 的替代物是谁（`BufferBuilder`/`MeshData`/`rendertype` 体系里找） | `jar tf` 全量 + `javap` 反查 `LevelRenderer` 的方法签名 |
| R2 | `LevelRenderer` 的渲染主流程（`renderLevel` 签名与 pass 划分） | `javap -p LevelRenderer` |
| R3 | `GameRenderer` 的帧末钩子（末尾 blit/后处理位置） | `javap -p GameRenderer` |
| R4 | `PackResources` 接口方法（`getRootResource`/`listResources`/`getMetadataSection` 是否变形） | `javap -p PackResources` |
| R5 | 方块/物品注册在 `BlockIds`/`ItemIds` 拆分后的正确写法 | `javap -p Blocks` + `net.minecraft.references.BlockIds` |
| R6 | `LevelRenderer` 是否有官方 sky 钩子可替代自家 mixin | 同上 R2 |
