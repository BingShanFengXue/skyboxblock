# 26.2 渲染主干实测

- 采样时间：2026-11-01
- 采样对象：`skybox-mod-26.2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-a8e9c7b123\26.2\minecraft-merged-a8e9c7b123-26.2.jar`（Loom 1.19.3 产出，未混淆，client+server 合并）
- 附加采样对象：`fabric-api 0.161.0+26.2` 中的 `fabric-rendering-v1-25.3.3+515ac5339e.jar`（下文标 **Fabric** 的结论来自这个 jar）
- 复现命令（本机 pwsh 实测可跑）：

```powershell
$jar = '...\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-a8e9c7b123\26.2\minecraft-merged-a8e9c7b123-26.2.jar'
$javap = 'C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe'
& $javap -p  -classpath $jar <FQCN>   # 签名
& $javap -p -c -classpath $jar <FQCN> # 字节码（下面所有"offset xxx"都指这个输出）
```

原始 dump 全部落在 `skybox-mod-26.2\build\api\*.txt`（`code_*.txt` 为 `javap -c` 输出），本文只摘取关键片段。

本文补齐 `docs/API-MATRIX-26.2.md` §4 待确认清单里的 R1 / R2 / R3 / R6，外加新增的 R9。
**所有结论以 `javap` / `jar tf` 实际输出为准，不采信文档与记忆；未测到的点显式标「查不到」/「待实测」。**

---

## R1 `MultiBufferSource` 的替代物：`SubmitNodeCollector` + `submitCustomGeometry`（几何从"立刻写缓冲"改成"提交节点"）

### 结论

`net.minecraft.client.renderer.MultiBufferSource` 在 26.2 **整个 jar 里不存在**。严格核验：

```powershell
$all = Get-Content build\mc262-classes.txt
$all | Where-Object { $_ -match 'MultiBufferSource' }                       # 0 行
$all | Where-Object { $_ -match '^net/minecraft/client/renderer/[A-Za-z0-9_]*Source\.class$' }   # 0 行
```

即 `net/minecraft/client/renderer/` **本层**一个 `*Source` 都不剩（子包里还有 `renderer/texture/atlas/SpriteSource` 之类，但那是贴图图集用的，与几何提交无关）。1.21.1 的 `MultiBufferSource` / `MultiBufferSource$BufferSource` / `MultiBufferSource$Immediate` 三个类整体消失。

替代物是一条三层链：

1. **提交口**：`SubmitNodeCollector`（接口）→ 父接口 `OrderedSubmitNodeCollector`。
   方块的四种用途（模型 / 自定义几何 / 形状轮廓 / 移动方块）各有专用 `submitXxx(...)`。
2. **具体 handoff**：`submitCustomGeometry(PoseStack, RenderType, SubmitNodeCollector$CustomGeometryRenderer)`，而 `CustomGeometryRenderer.render(PoseStack$Pose, VertexConsumer)` —— **这里才拿到 `VertexConsumer`**。即 1.21.1 的
   `renderer.render(pose, vertexConsumers.getBuffer(renderType), ...)` 现在写成
   `collector.submitCustomGeometry(pose, renderType, (pose2, vc) -> {...})`。
3. **实现类**：`SubmitNodeStorage implements SubmitNodeCollector`（按 `order(int)` 分桶到 `SubmitNodeCollection`），由 `FeatureRenderDispatcher.prepareFrame(SubmitNodeStorage)` 编译成 `PreparedFrame`，在 framegraph 的 main / always-on-top pass 里被消费。

**`BufferBuilder` / `MeshData` / `ByteBufferBuilder` 都还在**（`com.mojang.blaze3d.vertex.*`），但它们不再是"世界渲染提交几何"的公共通道，而是"你手动建 `GpuBuffer`、自己开 `RenderPass`"这条低层路用的工具。

### `javap` 原始签名摘录

```
# javap -p net.minecraft.client.renderer.SubmitNodeCollector
public interface net.minecraft.client.renderer.SubmitNodeCollector extends net.minecraft.client.renderer.OrderedSubmitNodeCollector {
  public abstract net.minecraft.client.renderer.OrderedSubmitNodeCollector order(int);
}

# javap -p net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer
public interface net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer {
  public abstract void render(com.mojang.blaze3d.vertex.PoseStack$Pose, com.mojang.blaze3d.vertex.VertexConsumer);
}

# javap -p net.minecraft.client.renderer.OrderedSubmitNodeCollector（节选：与面绘制直接相关的四条）
public interface net.minecraft.client.renderer.OrderedSubmitNodeCollector
        extends net.fabricmc.fabric.api.client.renderer.v1.render.FabricOrderedSubmitNodeCollector,
                net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector {
  public abstract void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.rendertype.RenderType, net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer);
  public abstract void submitBlockModel(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.rendertype.RenderType, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>, int[], int, int, int);
  public abstract void submitMovingBlock(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.block.MovingBlockRenderState, int);
  public abstract void submitShapeOutline(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.world.phys.shapes.VoxelShape, net.minecraft.client.renderer.rendertype.RenderType, int, float, boolean);
}

# javap -p net.minecraft.client.renderer.SubmitNodeStorage（节选）
public class net.minecraft.client.renderer.SubmitNodeStorage implements net.minecraft.client.renderer.SubmitNodeCollector {
  public net.minecraft.client.renderer.SubmitNodeCollection order(int);
  public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.rendertype.RenderType, net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer);
  public it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap<net.minecraft.client.renderer.SubmitNodeCollection> getSubmitsPerOrder();
  public void drainPhases(java.util.function.Consumer<net.minecraft.client.renderer.feature.phase.FeatureRenderPhase<?>>);
  public net.minecraft.client.renderer.OrderedSubmitNodeCollector order(int);
}

# javap -p net.minecraft.client.renderer.blockentity.BlockEntityRenderer
# —— 这是 1.21.1 里 render(T, float, PoseStack, MultiBufferSource, int, int) 的 1:1 替代口
public interface net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T extends net.minecraft.world.level.block.entity.BlockEntity, S extends net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {
  public abstract S createRenderState();
  public default void extractRenderState(T, S, float, net.minecraft.world.phys.Vec3, net.minecraft.client.renderer.feature.ModelFeatureRenderer$CrumblingOverlay);
  public abstract void submit(S, com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.state.level.CameraRenderState);
  ...
}

# javap -p com.mojang.blaze3d.vertex.VertexConsumer（顶点写法基本没变）
public interface com.mojang.blaze3d.vertex.VertexConsumer {
  public abstract com.mojang.blaze3d.vertex.VertexConsumer addVertex(float, float, float);
  public abstract com.mojang.blaze3d.vertex.VertexConsumer setColor(int, int, int, int);
  public abstract com.mojang.blaze3d.vertex.VertexConsumer setUv(float, float);
  public abstract com.mojang.blaze3d.vertex.VertexConsumer setUv1(int, int);
  public abstract com.mojang.blaze3d.vertex.VertexConsumer setUv2(int, int);
  public abstract com.mojang.blaze3d.vertex.VertexConsumer setNormal(float, float, float);
  public abstract com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float);
  public default void putBakedQuad(com.mojang.blaze3d.vertex.PoseStack$Pose, net.minecraft.client.resources.model.geometry.BakedQuad, com.mojang.blaze3d.vertex.QuadInstance);
  ...
}

# javap -p net.minecraft.client.renderer.rendertype.RenderType（自建 RenderType 的路）
public class net.minecraft.client.renderer.rendertype.RenderType {
  public static net.minecraft.client.renderer.rendertype.RenderType create(java.lang.String, net.minecraft.client.renderer.rendertype.RenderSetup);
  public net.minecraft.client.renderer.rendertype.PreparedRenderType prepare();
  public com.mojang.blaze3d.vertex.VertexFormat format();
  public com.mojang.blaze3d.PrimitiveTopology primitiveTopology();
  public com.mojang.blaze3d.pipeline.RenderPipeline pipeline();
  public boolean hasBlending();
  ...
}

# javap -p net.minecraft.client.renderer.rendertype.RenderSetup / $RenderSetupBuilder（节选）
public final class net.minecraft.client.renderer.rendertype.RenderSetup {
  public static net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder builder(com.mojang.blaze3d.pipeline.RenderPipeline);
  public java.util.List<net.minecraft.client.renderer.rendertype.PreparedRenderType$Texture> prepareTextures(net.minecraft.client.renderer.texture.TextureManager, com.mojang.blaze3d.systems.SamplerCache, com.mojang.blaze3d.textures.GpuTextureView, com.mojang.blaze3d.textures.GpuTextureView);
}
public class net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder {
  public net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder withTexture(java.lang.String, net.minecraft.resources.Identifier);
  public net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder useLightmap();
  public net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder sortOnUpload();
  public net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder setLayeringTransform(net.minecraft.client.renderer.rendertype.LayeringTransform);
  public net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder setOutputTarget(net.minecraft.client.renderer.rendertype.OutputTarget);
  public net.minecraft.client.renderer.rendertype.RenderSetup$RenderSetupBuilder setTextureTransform(net.minecraft.client.renderer.rendertype.TextureTransform);
  public net.minecraft.client.renderer.rendertype.RenderSetup createRenderSetup();
}
```

`net/minecraft/client/renderer/rendertype/` 下顶层类只有 7 个（`OutputTarget` / `LayeringTransform` / `PreparedRenderType` / `RenderSetup` / `RenderType` / `TextureTransform` / `RenderTypes`）——1.21.1 那套"每帧 `RenderType.getXxx()` 现算 + `ShaderInstance`"的结构整体塌成了"`RenderSetup` 声明 + `RenderType.create` 一次性建好"。

### 对天空盒方块的影响

- **`SkyFaceRenderer` / `SkyBoxBlockRenderer` 里所有 `MultiBufferSource` 参数直接换成 `SubmitNodeCollector`**，方法名从 `render` 改 `submit`（见 `BlockEntityRenderer.submit`）。1.21.1 的
  `var vc = bufferSource.getBuffer(SKYBOX_FACE); ...vc.addVertex(...)` 全部包进
  `collector.submitCustomGeometry(pose, SKYBOX_FACE_TYPE, (pose2, vc) -> {...})`，**顶点代码可以逐行照搬**。
- 面用的自定义 `RenderType` 现在必须**静态建一次、长期复用**：`RenderType.create("skyboxblock:face", RenderSetup.builder(MY_PIPELINE).withTexture("Sampler0", MY_TEXTURE_ID).createRenderSetup())`。
  自建 `RenderPipeline` 时注意：`withDepthStencilState(...)` **只有深度、没有模板**（见 API-MATRIX §2.3），1.21.1 靠 stencil 做"云 cookie-cut / 日出遮挡"的那部分在 Vulkan 后端表达不出来。
- 采样贴图（"抓到的天空图"）在 26.2 是通过 `bindTexture` 绑 `GpuTextureView` 来的，等价物是 `RenderSetup.withTexture(String, Identifier)`（把 `Identifier` 解析成 `AbstractTexture` 的 view）；**没有** `RenderSystem.setShaderTexture`。
- `SubmitNodeCollection` / `OrderedSubmitNodeCollector.order(int)` 说明提交是有**排序层**的：想保证面在某个阶段画（不透/半透/叠在最上），要用 `order(int)` 选桶，而不是 1.21.1 那种"调用的先后即顺序"。

---

## R2 `LevelRenderer` 现行渲染主流程：一个 `render(...)` + 5 个 framegraph pass，天空在 `addSkyPass` 里画

### 结论

- **`LevelRenderer` 里没有 `renderLevel` 方法**（`javap -p` 全量方法表见下）。1.21.1 的 `renderLevel(...)` 拆成了两层：
  - `GameRenderer.renderLevel(DeltaTracker)`（还在，见 R3）做相机/雾/手部等前置，然后调
  - `LevelRenderer.render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc, GpuBufferSlice, Vector4f, boolean)` —— **这是 26.2 的世界渲染主入口**。
- `render(...)` 内部**构造一个局部 `FrameGraphBuilder`**，按顺序 `addPass` 出 5 类 pass，最后 `builder.execute(allocator, inspector)` 一次性提交。字节码里的注册顺序（`code_LevelRenderer.txt`，`render` 方法体内）：

  | offset | 动作 | pass 名 |
  |---|---|---|
  | 295–336 | `addPass` + `readsAndWrites(targets.main)` + 只做 clear 的 `executes` | `clear` |
  | 341–353 | `iload 8; ifeq` → 为真才调 `addSkyPass(...)` | `sky` |
  | 372–387 | `prepareChunkRenders(viewRotationMatrix)` 后调 `addMainPass(...)` | `main` |
  | 390–430 | `ShaderManager.getPostChain(ENTITY_OUTLINE_POST_CHAIN_ID, OUTLINE_TARGETS).addToFrame(...)` | （实体描边后处理） |
  | 442–508 | `cloudStatus != OFF && ARGB.alpha(cloudColor) > 0` 才调 `addCloudsPass(...)` | `clouds` |
  | 511–516 | `addWeatherPass(...)` | `weather` |
  | 519–536 | 透明链 `PostChain.addToFrame(...)` | （`transparency` 后处理） |
  | 539–546 | `addAlwaysOnTopPass(...)` | `always_on_top` |
  | 559–572 | `builder.execute(GraphicsResourceAllocator, FrameGraphBuilder$Inspector)` | — |

- **天空就是在 `addSkyPass` 里画的**：该方法先做三重早退（`FogType.POWDER_SNOW` / `LAVA` / `doesMobEffectBlockSky` 直接 return；`skyRenderState.skybox == Skybox.NONE` 直接 return），然后
  `builder.addPass("sky")` → `readsAndWrites(targets.main)` → `executes(lambda$addSkyPass$0)`。
  `lambda$addSkyPass$0` 的实际绘制序列（字节码 offset 5 / 18 / 73 / 84 / 100 / 125 / 132）：

  ```
  skybox == END  →  skyRenderer.renderEndSky()
                    [endFlashIntensity > 1e-5] skyRenderer.renderEndFlash(pose, f, f, f)
  否则           →  skyRenderer.renderSkyDisc(skyRenderState.skyColor)
                    skyRenderer.renderSunriseAndSunset(pose, sunAngle, sunriseAndSunsetColor)
                    skyRenderer.renderSunMoonAndStars(pose, sunAngle, moonAngle, starAngle, moonPhase, rainBrightness, starBrightness)
                    [shouldRenderDarkDisc] skyRenderer.renderDarkDisc()
  ```

- `LevelRenderer` 把各个子系统都开成了 public getter：`skyRenderer()`、`cloudRenderer()`、`weatherEffectRenderer()`、`worldBorderRenderer()`、`entityOutlineTarget()`、`translucentTarget()`、`itemEntityTarget()`、`particlesTarget()`、`weatherTarget()`、`cloudsTarget()`。**`skyRenderer()` 可以公开拿到**（这就是 R6 的关键）。
- `endFrame()` / `close()` / `resize(int,int)` / `doEntityOutline()` 都是 public；`endFrame()` 由 `Minecraft.renderFrame(boolean)` 在 GUI 之后调（`code_Minecraft.txt` 第 733 行，仍在 `renderFrame` 内）。

### `javap` 原始签名摘录（`javap -p net.minecraft.client.renderer.LevelRenderer` 的全部方法，字段已略）

```
public class net.minecraft.client.renderer.LevelRenderer implements java.lang.AutoCloseable {
  public net.minecraft.client.renderer.LevelRenderer(net.minecraft.client.renderer.entity.EntityRenderDispatcher, net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher, net.minecraft.client.resources.model.ModelManager, net.minecraft.client.renderer.texture.TextureManager, net.minecraft.client.resources.model.sprite.AtlasManager, net.minecraft.client.renderer.ShaderManager, net.minecraft.client.renderer.GameRenderer, int, int);
  public void render(com.mojang.blaze3d.resource.GraphicsResourceAllocator, net.minecraft.client.DeltaTracker, boolean, net.minecraft.client.renderer.state.level.CameraRenderState, org.joml.Matrix4fc, com.mojang.blaze3d.buffers.GpuBufferSlice, org.joml.Vector4f, boolean);
  private void submitFeatures(net.minecraft.client.renderer.state.level.LevelRenderState, net.minecraft.client.renderer.SubmitNodeCollector, boolean);
  private void repositionCamera(net.minecraft.client.renderer.state.level.CameraRenderState);
  private void addSkyPass(com.mojang.blaze3d.framegraph.FrameGraphBuilder, net.minecraft.client.renderer.state.level.CameraRenderState, com.mojang.blaze3d.buffers.GpuBufferSlice);
  private void addMainPass(com.mojang.blaze3d.framegraph.FrameGraphBuilder, net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame, com.mojang.blaze3d.buffers.GpuBufferSlice, net.minecraft.client.renderer.state.level.LevelRenderState, net.minecraft.util.profiling.ProfilerFiller, net.minecraft.client.renderer.chunk.ChunkSectionsToRender);
  private void addCloudsPass(com.mojang.blaze3d.framegraph.FrameGraphBuilder, net.minecraft.client.CloudStatus, net.minecraft.world.phys.Vec3, long, float, int, float, int);
  private void addWeatherPass(com.mojang.blaze3d.framegraph.FrameGraphBuilder, com.mojang.blaze3d.buffers.GpuBufferSlice);
  private void addAlwaysOnTopPass(com.mojang.blaze3d.framegraph.FrameGraphBuilder, net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame, com.mojang.blaze3d.buffers.GpuBufferSlice);
  public net.minecraft.client.renderer.chunk.ChunkSectionsToRender prepareChunkRenders(org.joml.Matrix4fc);
  private void compileSections(net.minecraft.client.renderer.state.level.CameraRenderState);
  private void checkPoseStack(com.mojang.blaze3d.vertex.PoseStack);
  private void submitEntities(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.state.level.LevelRenderState, net.minecraft.client.renderer.SubmitNodeCollector);
  private void submitBlockEntities(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.state.level.LevelRenderState, net.minecraft.client.renderer.SubmitNodeCollector);
  private void submitBlockDestroyAnimation(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.state.level.LevelRenderState);
  private void submitBlockOutline(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.state.level.LevelRenderState);
  private void submitHitOutline(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.SubmitNodeCollector, net.minecraft.client.renderer.rendertype.RenderType, net.minecraft.client.renderer.state.level.BlockOutlineRenderState, int, float, boolean);
  public void resize(int, int);
  public void endFrame();
  public void close();
  public void doEntityOutline();
  public void invalidateCompiledGeometry(net.minecraft.client.multiplayer.ClientLevel, net.minecraft.client.Options, net.minecraft.client.Camera, net.minecraft.client.color.block.BlockColors);
  private net.minecraft.client.renderer.PostChain getTransparencyChain();
  private void scheduleTranslucentSectionResort(net.minecraft.world.phys.Vec3);
  private void scheduleResort(net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection, net.minecraft.client.renderer.chunk.TranslucencyPointOfView, net.minecraft.world.phys.Vec3, boolean, boolean);
  public void clearVisibleSections();
  public void resetLevelRenderData();
  public boolean hasRenderedAllSections();
  public boolean isSectionCompiledAndVisible(net.minecraft.core.BlockPos);
  public net.minecraft.client.renderer.chunk.SectionRenderDispatcher sectionRenderDispatcher();
  public net.minecraft.client.renderer.entity.EntityRenderDispatcher entityRenderDispatcher();
  public net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher blockEntityRenderDispatcher();
  public com.mojang.blaze3d.pipeline.RenderTarget entityOutlineTarget();
  public com.mojang.blaze3d.pipeline.RenderTarget translucentTarget();
  public com.mojang.blaze3d.pipeline.RenderTarget itemEntityTarget();
  public com.mojang.blaze3d.pipeline.RenderTarget particlesTarget();
  public com.mojang.blaze3d.pipeline.RenderTarget weatherTarget();
  public com.mojang.blaze3d.pipeline.RenderTarget cloudsTarget();
  public net.minecraft.client.renderer.CloudRenderer cloudRenderer();
  public net.minecraft.client.renderer.SkyRenderer skyRenderer();
  public net.minecraft.client.renderer.WeatherEffectRenderer weatherEffectRenderer();
  public net.minecraft.client.renderer.WorldBorderRenderer worldBorderRenderer();
  public net.minecraft.client.renderer.ViewArea viewArea();
  public it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection> visibleSections();
  public it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection> nearbyVisibleSections();
  public it.unimi.dsi.fastutil.longs.LongCollection expectedChunks();
  public net.minecraft.client.renderer.SectionOcclusionGraph sectionOcclusionGraph();
  public net.minecraft.gizmos.Gizmos$TemporaryCollection collectPerFrameRenderThreadGizmos();
  private void finalizeGizmoCollection();
  public void addMainThreadGizmos(java.util.List<net.minecraft.gizmos.SimpleGizmoCollector$GizmoInstance>);
  private static void lambda$prepareChunkRenders$1(int, com.mojang.blaze3d.buffers.GpuBufferSlice[], com.mojang.blaze3d.systems.RenderPass$UniformUploader);
  private static java.util.List lambda$prepareChunkRenders$0(int);
  private void lambda$addAlwaysOnTopPass$0(com.mojang.blaze3d.buffers.GpuBufferSlice, com.mojang.blaze3d.resource.ResourceHandle, net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame);
  private void lambda$addWeatherPass$0(com.mojang.blaze3d.buffers.GpuBufferSlice, int);
  private void lambda$addCloudsPass$0(int, net.minecraft.client.CloudStatus, float, int, net.minecraft.world.phys.Vec3, long, float);
  private void lambda$addMainPass$0(com.mojang.blaze3d.buffers.GpuBufferSlice, net.minecraft.client.renderer.state.level.LevelRenderState, net.minecraft.util.profiling.ProfilerFiller, net.minecraft.client.renderer.chunk.ChunkSectionsToRender, com.mojang.blaze3d.resource.ResourceHandle, net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame, com.mojang.blaze3d.resource.ResourceHandle, com.mojang.blaze3d.resource.ResourceHandle, com.mojang.blaze3d.resource.ResourceHandle, com.mojang.blaze3d.resource.ResourceHandle);
  private void lambda$addSkyPass$0(com.mojang.blaze3d.buffers.GpuBufferSlice, net.minecraft.client.renderer.state.level.SkyRenderState);
  private void lambda$render$0(org.joml.Vector4f);
  static {};
}
```

`addSkyPass` 主体（`javap -p -c`，含早退条件）：

```
private void addSkyPass(FrameGraphBuilder, CameraRenderState, GpuBufferSlice);
       1: getfield      // CameraRenderState.fogType
       8: getstatic     // FogType.POWDER_SNOW   -> if_acmpeq 32
      16: getstatic     // FogType.LAVA          -> if_acmpeq 32
      26: getfield      // CameraEntityRenderState.doesMobEffectBlockSky -> ifne 32
      32: return                       <-- 以上任一命中就不画天空
      37: getfield      // LevelRenderState.shouldResetSkyRenderer -> ifne 50
      44: getfield      // this.skyRenderer    （为 null 就重建）
      61: invokevirtual // SkyRenderer.close()
      84: invokespecial // new SkyRenderer(TextureManager, AtlasManager, GameRenderer.mainRenderTarget())
     101: getfield      // SkyRenderState.skybox
     104: getstatic     // DimensionType$Skybox.NONE -> if_acmpne 111
     110: return                      <-- skybox=NONE 的维度不画天空
     112: ldc_w         // String "sky"
     115: invokevirtual // FrameGraphBuilder.addPass("sky") -> FramePass
     126: invokeinterface // FramePass.readsAndWrites(targets.main) -> ResourceHandle
     147: invokedynamic   // lambda$addSkyPass$0(GpuBufferSlice, SkyRenderState)
     152: invokeinterface // FramePass.executes(Runnable)
```

### 对天空盒方块的影响

- **1.21.1 的 `@Inject(at = @At("TAIL"), method = "renderLevel")` 全部要改成注入 `render`（或 `addSkyPass`）**；`renderLevel` 这个名字在 `LevelRenderer` 上已经查不到，编译期就会报"找不到目标"，别浪费时间去猜签名。
- 拿"天空画完、还没画地形"的那一帧图像：**这一瞬间只有 `main` 这个 target 里装着天空**，而 `FrameGraphBuilder` 是 `render(...)` 的局部变量、`main` 的 `ResourceHandle` 也只存在 `LevelTargetBundle`（`LevelRenderer.targets` 是 private，没有 getter）。
  → **想抓"纯天空帧"，唯一的干净位置就是 `addSkyPass` / `main` pass 之间插自己的 pass，必须 mixin。** 帧末从 `GameRenderer.mainRenderTarget()` 读到的已经是"天空 + 地形 + 云 + 雨"的合成结果。
- 好消息：`skyRenderer()` 是 public，而且 `SkyRenderer` 的渲染方法本身就是**"自带 RenderPass"的独立过程**（见 R6），所以"不抓帧、自己重画一份天空到离屏 target"这条路是零 mixin 可行的。
- 是否走天空 pass 由 `render(...)` 第 8 个 `boolean` 决定（`GameRenderer` 传 `!bossOverlay.shouldCreateWorldFog()`）；boss 血条压暗世界时**整个 sky pass 都不注册**——移植后"方块面显示天空"在这种状态下要自己给降级值，否则会采样到上一帧的旧图。

---

## R6 官方天空钩子：**vanilla 侧没有**（`DimensionSpecialEffects` 已删除）；能用的只有"数据驱动的 `Skybox` 枚举" + "public 的 `SkyRenderer`" + "Fabric 的 `LevelRenderEvents`（不含天空）"

### 结论

1. **`DimensionSpecialEffects` 在 26.2 不存在。** 全量类表 `build/mc262-classes.txt` 里 `DimensionSpecialEffects` 命中 **0 行**（同样 `SkyRenderer` 只有 `net/minecraft/client/renderer/SkyRenderer` 一个，没有任何子类/接口形式）。1.21.1 "注册 `DimensionSpecialEffects` 接管天空"的整条路彻底没了。
2. 替代它的是**纯数据**：`DimensionType.skybox()` 返回 `DimensionType.Skybox` 枚举 `{NONE, OVERWORLD, END}`（带 `Codec`，写在 dimension_type JSON 里）。它只能表达"不画 / 主世界风格 / 末地风格"，**没有任何可注册的回调**。
3. `SkyRenderer` 是**具体类、构造器 public、渲染方法 public**，但 `LevelRenderer` 自己 `new` 它、没有 setter。所以能"另建一个画到自己的 target"，不能"替换掉主世界那个"。
4. **Fabric API 0.161.0 有官方的世界渲染事件，但事件表里没有天空**：`LevelRenderEvents` 只有 `START_MAIN / AFTER_OPAQUE_TERRAIN / COLLECT_SUBMITS / AFTER_SOLID_FEATURES / AFTER_TRANSLUCENT_FEATURES / BEFORE_BLOCK_OUTLINE / BEFORE_GIZMOS / BEFORE_TRANSLUCENT_TERRAIN / AFTER_TRANSLUCENT_TERRAIN / END_MAIN`（全部发生在 `main` pass 内部）+ 两个抽取期事件。**对照 `LevelRendererMixin` 的注入方法名（`beforeRender` / `prepareChunkRenders` / `wrapRenderOpaqueTerrain` / `afterCollectSubmits` / `afterRenderSolidFeatures` / `afterRenderTranslucentFeatures` / `beforeRenderBlockOutline` / `beforeCollectGizmos` / `wrapRenderTranslucentTerrain` / `endMainRender`）——没有任何一条挂在 `addSkyPass` 上。**
5. 因此"**不 mixin 接管天空**"在 26.2 **查不到官方入口**。能做到的是"不 mixin 地**自己重画一份天空**"（因为 `SkyRenderer` 够开放）。

### `javap` 原始签名摘录

```
# 全量类表事实（不是 javap，是 jar tf 的派生结果）
$ Get-Content build\mc262-classes.txt | Where-Object { $_ -match 'DimensionSpecialEffects' }
（0 行）
$ ... | Where-Object { $_ -match 'SpecialEffects|Skybox' }
net/minecraft/world/level/biome/BiomeSpecialEffects$Builder.class
net/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier$1.class
net/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier$2.class
net/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier$3.class
net/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier.class
net/minecraft/world/level/biome/BiomeSpecialEffects.class
net/minecraft/world/level/dimension/DimensionType$Skybox.class

# javap -p net.minecraft.world.level.dimension.DimensionType$Skybox
public final class net.minecraft.world.level.dimension.DimensionType$Skybox extends java.lang.Enum<net.minecraft.world.level.dimension.DimensionType$Skybox> implements net.minecraft.util.StringRepresentable {
  public static final net.minecraft.world.level.dimension.DimensionType$Skybox NONE;
  public static final net.minecraft.world.level.dimension.DimensionType$Skybox OVERWORLD;
  public static final net.minecraft.world.level.dimension.DimensionType$Skybox END;
  public static final com.mojang.serialization.Codec<net.minecraft.world.level.dimension.DimensionType$Skybox> CODEC;
  public java.lang.String getSerializedName();
}

# javap -p net.minecraft.world.level.dimension.DimensionType（节选）
public final class net.minecraft.world.level.dimension.DimensionType extends java.lang.Record {
  private final net.minecraft.world.level.dimension.DimensionType$Skybox skybox;
  public net.minecraft.world.level.dimension.DimensionType$Skybox skybox();
  public boolean hasSkyLight();
  public boolean hasEndFlashes();
  ...
}

# javap -p net.minecraft.client.renderer.state.level.SkyRenderState
# —— 天空的"全部输入"都在这 12 个 public 字段里，抽取期可改
public class net.minecraft.client.renderer.state.level.SkyRenderState implements net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState {
  public net.minecraft.world.level.dimension.DimensionType$Skybox skybox;
  public boolean shouldRenderDarkDisc;
  public float sunAngle;
  public float moonAngle;
  public float starAngle;
  public float rainBrightness;
  public float starBrightness;
  public int sunriseAndSunsetColor;
  public net.minecraft.world.level.MoonPhase moonPhase;
  public int skyColor;
  public float endFlashIntensity;
  public float endFlashXAngle;
  public float endFlashYAngle;
  public net.minecraft.client.renderer.state.level.SkyRenderState();
  public void reset();
}

# javap -p net.minecraft.client.renderer.SkyRenderer
# —— 构造器 public，六个 draw 方法 public，全部"自带 RenderPass"
public class net.minecraft.client.renderer.SkyRenderer implements java.lang.AutoCloseable {
  public net.minecraft.client.renderer.SkyRenderer(net.minecraft.client.renderer.texture.TextureManager, net.minecraft.client.resources.model.sprite.AtlasManager, com.mojang.blaze3d.pipeline.RenderTarget);
  public void renderSkyDisc(int);
  public void extractRenderState(net.minecraft.client.multiplayer.ClientLevel, float, net.minecraft.client.Camera, net.minecraft.client.renderer.state.level.SkyRenderState);
  public void renderDarkDisc();
  public void renderSunMoonAndStars(com.mojang.blaze3d.vertex.PoseStack, float, float, float, net.minecraft.world.level.MoonPhase, float, float);
  public void renderSunriseAndSunset(com.mojang.blaze3d.vertex.PoseStack, float, int);
  public void renderEndSky();
  public void renderEndFlash(com.mojang.blaze3d.vertex.PoseStack, float, float, float);
  public void close();
}

# javap -p -c net.minecraft.client.renderer.SkyRenderer  → renderSkyDisc 自己开 pass（节选）
public void renderSkyDisc(int);
       0: invokestatic  // RenderSystem.getDynamicUniforms()
       3: invokestatic  // RenderSystem.getModelViewMatrixCopy()
      10: invokevirtual // DynamicUniforms.writeTransform(Matrix4f, Vector4f) -> GpuBufferSlice
      15: getfield      // this.renderTarget                       <-- 构造时传进来的那个 RenderTarget
      18: invokevirtual // RenderTarget.getColorTextureView()
      26: invokevirtual // RenderTarget.getDepthTextureView()
      34: invokevirtual // GpuDevice.createCommandEncoder()
      51: invokevirtual // CommandEncoder.createRenderPass(Supplier, GpuTextureView, Optional, GpuTextureView, OptionalDouble)
      61: invokevirtual // RenderPass.setPipeline(RenderPipeline)
      66: invokestatic  // RenderSystem.bindDefaultUniforms(RenderPass)
      75: invokevirtual // RenderPass.setUniform("DynamicTransforms", GpuBufferSlice)
      88: invokevirtual // RenderPass.setVertexBuffer(int, GpuBufferSlice)
```

```
# Fabric：javap -p net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
public final class ...LevelRenderEvents {
  public static final Event<LevelRenderEvents$StartMain> START_MAIN;
  public static final Event<LevelRenderEvents$AfterOpaqueTerrain> AFTER_OPAQUE_TERRAIN;
  public static final Event<LevelRenderEvents$CollectSubmits> COLLECT_SUBMITS;
  public static final Event<LevelRenderEvents$AfterSolidFeatures> AFTER_SOLID_FEATURES;
  public static final Event<LevelRenderEvents$AfterTranslucentFeatures> AFTER_TRANSLUCENT_FEATURES;
  public static final Event<LevelRenderEvents$BeforeBlockOutline> BEFORE_BLOCK_OUTLINE;
  public static final Event<LevelRenderEvents$BeforeGizmos> BEFORE_GIZMOS;
  public static final Event<LevelRenderEvents$BeforeTranslucentTerrain> BEFORE_TRANSLUCENT_TERRAIN;
  public static final Event<LevelRenderEvents$AfterTranslucentTerrain> AFTER_TRANSLUCENT_TERRAIN;
  public static final Event<LevelRenderEvents$EndMain> END_MAIN;
  public static final Event<LevelExtractionEvents$AfterBlockOutlineExtraction> AFTER_BLOCK_OUTLINE_EXTRACTION;
  public static final Event<LevelExtractionEvents$EndExtraction> END_EXTRACTION;
}

# 各事件的回调形状
public interface LevelRenderEvents$StartMain     { void startMain(LevelTerrainRenderContext); }
public interface LevelRenderEvents$CollectSubmits{ void collectSubmits(LevelRenderContext); }
public interface LevelRenderEvents$EndMain       { void endMain(LevelRenderContext); }

public interface LevelRenderContext extends LevelTerrainRenderContext {
  net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector();
  com.mojang.blaze3d.vertex.PoseStack poseStack();
}
public interface LevelTerrainRenderContext extends AbstractLevelRenderContext {
  net.minecraft.client.renderer.chunk.ChunkSectionsToRender sectionsToRender();
}
public interface AbstractLevelRenderContext {
  net.minecraft.client.renderer.GameRenderer gameRenderer();
  net.minecraft.client.renderer.LevelRenderer levelRenderer();
  net.minecraft.client.renderer.state.level.LevelRenderState levelState();
}
public interface LevelExtractionContext extends AbstractLevelRenderContext {
  net.minecraft.client.multiplayer.ClientLevel level();
  net.minecraft.client.Camera camera();
  net.minecraft.client.DeltaTracker deltaTracker();
}
public interface LevelExtractionEvents$EndExtraction { void endExtraction(LevelExtractionContext); }

# Fabric：javap -p net.fabricmc.fabric.mixin.client.rendering.LevelRendererMixin
# —— 注入点全表，用来证明"没有一条挂在 addSkyPass 上"
private void beforeRender(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc, GpuBufferSlice, Vector4f, boolean, CallbackInfo);
private void prepareChunkRenders(CallbackInfoReturnable<ChunkSectionsToRender>);
private void wrapRenderOpaqueTerrain(ChunkSectionsToRender, ChunkSectionLayerGroup, GpuSampler, Operation<Void>);
private com.mojang.blaze3d.vertex.PoseStack onCreatePoseStack(PoseStack);
private void afterCollectSubmits(CallbackInfo);
private void afterRenderSolidFeatures(CallbackInfo);
private void afterRenderTranslucentFeatures(CallbackInfo);
private void beforeRenderBlockOutline(PoseStack, SubmitNodeCollector, LevelRenderState, CallbackInfo);
private void beforeCollectGizmos(CallbackInfo);
private void wrapRenderTranslucentTerrain(ChunkSectionsToRender, ChunkSectionLayerGroup, GpuSampler, Operation<Void>);
private void endMainRender(CallbackInfo);
```

### 对天空盒方块的影响

- 1.21.1 里"注册自定义 `DimensionSpecialEffects` 抢天空"这条主线**没有对等物，必须放弃**。`DimensionType.skybox` 只认三个枚举值、且走 JSON/数据包，不是代码接缝。
- "方块面显示当前维度真实天空"在 26.2 有两条现实路径：
  - **A（零 mixin，推荐先做）**：自己 `new SkyRenderer(textureManager, atlasManager, 我的 RenderTarget)`，在帧内某个"相机/投影与世界一致"的位置按 `lambda$addSkyPass$0` 的同样顺序调用 `renderSkyDisc / renderSunriseAndSunset / renderSunMoonAndStars / renderDarkDisc`（或用 `extractRenderState` + `LevelRenderState.skyRenderState` 的字段），把结果当作面贴图。全部是 public API。
  - **B（要 mixin）**：在 `addSkyPass` 之后插一个 pass，把 `targets.main` 的颜色拷到自己的 target —— 这样拿到的才是**别的天空 mod / Iris 光影真正画出来的那一片天空**。零 mixin 拿不到这一层（见 R2 的第 2 条影响）。
- `SkyRenderState` 的 12 个字段全部 public 且可经 `LevelExtractionEvents.END_EXTRACTION` 拿到 `LevelRenderState` 改写 —— 如果只想"改天空颜色/日月角度"，这是唯一无需 mixin 的官方改法。
- **待实测**：`renderSkyDisc` 依赖 `RenderSystem.getModelViewMatrixCopy()` / `bindDefaultUniforms(pass)` 绑定的"当帧投影 + 雾 + 光照"，在 `LevelRenderEvents.START_MAIN` 里调用时这些状态是否已经就绪，本次只从字节码确认了"它依赖这些全局状态"，没跑游戏验证。

---

## R3 `GameRenderer` 的"世界画完、GUI 之前"：**没有专用钩子方法**，窗口就是 `render(DeltaTracker, boolean)` 内部 `renderLevel(...)` 返回到 `gui` profiler push 之间

### 结论

- `GameRenderer` 的方法表里**没有一个"帧末/合成"专用方法**（没有 `renderEnd`、没有 `afterWorld` 之类）。真正的帧末顺序写在 `render(DeltaTracker, boolean)` 的方法体里，字节码（`code_GameRenderer.txt`）的实测顺序：

  | offset | 动作 |
  |---|---|
  | 250–254 | `profiler.push("world")` |
  | **261** | **`invokevirtual GameRenderer.renderLevel(DeltaTracker)`** |
  | 264 | `tryTakeScreenshotIfNeeded()` |
  | 275 | `Minecraft.levelRenderer.doEntityOutline()` |
  | 278–326 | 若 `postEffectId != null && effectActive`：`ShaderManager.getPostChain(postEffectId, LevelTargetBundle.MAIN_TARGETS).process(mainRenderTarget, resourcePool)` |
  | 329–330 | `profiler.pop()` |
  | 339 | `fogRenderer.endFrame()` |
  | 344–356 | `GpuDevice.createCommandEncoder().clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0)` |
  | 359–366 | `lighting().setupFor(Lighting$Entry.ITEMS_3D)` |
  | 371 | `useUiLightmap = true` |
  | **374** | **`profiler.push("gui")`** ← 世界后 / GUI 前的分界线 |
  | 387 / 394 | `GuiRenderer.render()` / `GuiRenderer.endFrame()` |
  | 409 | `RenderBuffers.endFrame()` |
  | 416 | `CrossFrameResourcePool.endFrame()` |

- 也就是说：**"世界渲染完之后、GUI 之前"= `render(...)` 内 offset 264 ~ 374 这段**（约 110 条字节码的空间），里面已经挤了截图、实体描边、原版 post effect、雾收尾、深度清理、光照切换。**没有留任何 public 方法给模组插进去。**
- `renderLevel(DeltaTracker)` 本身是 public 的，且它内部只调一次 `LevelRenderer.render(...)`（offset 405）+ 手部渲染；它是"整帧世界"的最小可替换单元，但"它之后"仍然没有钩子。
- 相关可用的 public 面：`renderBuffers()`、`gameRenderState()`、`featureRenderDispatcher()`、`mainRenderTarget()`、`mainCamera()`、`lightmap()`、`levelLightmap()`、`panorama()`、`currentPostEffect()`、`clearPostEffect()`、`togglePostEffect()`、`projectPointToScreen(Vec3)`、`projectHorizonToScreen()`、`lighting()`。

### `javap` 原始签名摘录（`javap -p net.minecraft.client.renderer.GameRenderer` 的 public 部分）

```
public class net.minecraft.client.renderer.GameRenderer implements java.lang.AutoCloseable, net.minecraft.world.waypoints.TrackedWaypoint$Projector {
  public net.minecraft.client.renderer.GameRenderer(net.minecraft.client.Minecraft, net.minecraft.client.renderer.ItemInHandRenderer, net.minecraft.client.resources.model.ModelManager);
  public void close();
  public net.minecraft.client.renderer.RenderBuffers renderBuffers();
  public net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureRenderDispatcher();
  public net.minecraft.client.renderer.state.GameRenderState gameRenderState();
  public void setRenderBlockOutline(boolean);
  public void clearPostEffect();
  public void togglePostEffect();
  public void checkEntityPostEffect(net.minecraft.world.entity.Entity);
  public void processBlurEffect();
  public void preloadUiShader(net.minecraft.server.packs.resources.ResourceProvider);
  public void tick();
  public net.minecraft.resources.Identifier currentPostEffect();
  public void resize(int, int);
  public static float nightVisionScale(net.minecraft.world.entity.LivingEntity, float);
  public void update(net.minecraft.client.DeltaTracker);
  public void extract(net.minecraft.client.DeltaTracker, boolean);
  public void render(net.minecraft.client.DeltaTracker, boolean);
  public void renderLevel(net.minecraft.client.DeltaTracker);
  public void resetData();
  public void displayItemActivation(net.minecraft.world.item.ItemStack);
  public float bossOverlayWorldDarkening(float);
  public net.minecraft.client.Camera mainCamera();
  public com.mojang.blaze3d.textures.GpuTextureView lightmap();
  public com.mojang.blaze3d.textures.GpuTextureView levelLightmap();
  public net.minecraft.client.renderer.texture.OverlayTexture overlayTexture();
  public com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget();
  public net.minecraft.world.phys.Vec3 projectPointToScreen(net.minecraft.world.phys.Vec3);
  public double projectHorizonToScreen();
  public com.mojang.blaze3d.platform.Lighting lighting();
  public void setLevel(net.minecraft.client.multiplayer.ClientLevel);
  public net.minecraft.client.renderer.Panorama panorama();
  public void registerPanoramaTextures(net.minecraft.client.renderer.texture.TextureManager);
}
```

`render` 里"世界后 / GUI 前"那段的字节码原文：

```
       259: aload_0
       260: aload_1
       261: invokevirtual #1017   // Method renderLevel:(Lnet/minecraft/client/DeltaTracker;)V
       264: aload_0
       265: invokevirtual #1020   // Method tryTakeScreenshotIfNeeded:()V
       268: aload_0
       269: getfield      #206    // Field minecraft:Lnet/minecraft/client/Minecraft;
       272: getfield      #619    // Field Minecraft.levelRenderer:Lnet/minecraft/client/renderer/LevelRenderer;
       275: invokevirtual #1023   // Method LevelRenderer.doEntityOutline:()V
       ...
       316: aload         7
       318: aload_0
       319: getfield      #358    // Field mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;
       322: aload_0
       323: getfield      #165    // Field resourcePool:Lcom/mojang/blaze3d/resource/CrossFrameResourcePool;
       326: invokevirtual #466    // Method PostChain.process:(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;)V
       ...
       344: invokestatic  #477    // RenderSystem.getDevice()
       345: invokevirtual #955    // GpuDevice.createCommandEncoder()
       348: aload_0
       349: getfield      #358    // mainRenderTarget
       352: invokevirtual #966    // Method RenderTarget.getDepthTexture()
       356: invokevirtual #1030   // Method CommandEncoder.clearDepthTexture(GpuTexture, double)
       ...
       374: ldc_w         #1044   // String "gui"
       378: invokeinterface #869 // ProfilerFiller.push(String)
       383: aload_0
       384: getfield      #327    // Field guiRenderer:Lnet/minecraft/client/gui/render/GuiRenderer;
       387: invokevirtual #1046   // Method GuiRenderer.render:()V
       394: invokevirtual #1047   // Method GuiRenderer.endFrame:()V
```

### 对天空盒方块的影响

- 1.21.1 那个"`GameRenderer` 帧末做一次合成"的注入点**依然存在但是私有的**：必须 mixin `GameRenderer.render(DeltaTracker, boolean)`，目标位置 = `renderLevel` 调用之后、`profiler.push("gui")` 之前。没有 `@At` 友好的锚点（那段全是 aload/getfield/invokevirtual），建议用 `@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER))`。
- 合成写法照抄原版 post effect 那三行即可（全 public）：
  `ShaderManager.getPostChain(Identifier, Set<Identifier>)` → `PostChain`；
  `PostChain.process(RenderTarget, GraphicsResourceAllocator)`；
  `GraphicsResourceAllocator` 直接用 `GameRenderer` 里那个 `CrossFrameResourcePool` 的同类实例，或 `GraphicsResourceAllocator.UNPOOLED`。
- 采样源：`GameRenderer.mainRenderTarget()` 是 public，`RenderTarget.getColorTextureView()/getDepthTextureView()` 也是 public —— "帧末把世界图贴到方块面上"这一步不缺 API。
- 想完全零 mixin 的替代位只有 Fabric 的 `LevelRenderEvents.END_MAIN`，但它在 **`main` pass 末尾**（云、雨、always-on-top 都还没画），语义和 1.21.1 的"帧末"不同，会有"云/雨不进去"的可见差异。

---

## R9 `FrameGraphBuilder` / `FramePass`：一个"声明式 pass 图 + 延迟执行"，`FramePass` 只负责"声明依赖 + 塞 Runnable"

### 结论

- `FrameGraphBuilder` 是个**纯 CPU 的描述器**：你只往里 `addPass(String)`、把资源句柄登记成"读/写"，最后 `execute(allocator[, inspector])` 时它才做 pass 排序（按读写依赖）、资源生命周期分配（`assignResourceLifetimes`）、无用 pass 裁剪（`identifyPassesToKeep`）。

- **核心 API 只有 4 个**：
  - `addPass(String) -> FramePass`：注册一个 pass，返回可继续声明的句柄。
  - `importExternal(String, T) -> ResourceHandle<T>`：把"外部已存在的资源"（例如 `GameRenderer.mainRenderTarget()` 那种 `RenderTarget`）纳入图。
  - `createInternal(String, ResourceDescriptor<T>) -> ResourceHandle<T>`：让图自己按描述符申请一个临时资源（由 allocator 池化）。
  - `execute(GraphicsResourceAllocator[, FrameGraphBuilder$Inspector])`：真正跑。

- **`FramePass` 的语义**（6 个方法，全部必须在 `executes(...)` **之前**声明完）：
  - `reads(handle)` —— 只读依赖；
  - `readsAndWrites(handle)` —— 读写（**会改写句柄本身**，原版就是这么用的：`targets.main = pass.readsAndWrites(targets.main)`，见 `addSkyPass` offset 126–138）；
  - `createsInternal(String, ResourceDescriptor)` —— 本 pass 内申请的资源；
  - `requires(FramePass)` —— 声明"即使没人读也得执行"的顺序依赖（配合裁剪用）；
  - `disableCulling()` —— 关掉"没人消费就裁掉这个 pass"的优化；
  - `executes(Runnable)` —— 真正的 GPU 动作，**在 `execute()` 期间被调用**，此时句柄才有效（`ResourceHandle.get()` 才有真对象）。

- 原版的用法样例（`LevelRenderer.addSkyPass` / `render` 的 `clear` pass）已经把"标准套路"摆出来了：

  ```
  FramePass p = builder.addPass("sky");
  targets.main = p.readsAndWrites(targets.main);   // 依赖 + 回写句柄
  p.executes(() -> { ...这里画... });              // 命令录制
  ...
  builder.execute(resourceAllocator, inspector);   // 统一提交
  ```

- **没有公开入口能拿到 `FrameGraphBuilder`**：`FrameGraphBuilder` 是 `LevelRenderer.render(...)` 的**局部变量**（局部变量槽 13）；全仓只有两个 public 方法接受它 ——
  `PostChain.addToFrame(FrameGraphBuilder, int, int, PostChain$TargetBundle)` 和
  `PostPass.addToFrame(FrameGraphBuilder, Map<Identifier, ResourceHandle<RenderTarget>>, GpuBufferSlice)`。
  这俩是"原版把后处理挂进图"的正规口，不是"给模组开的口"。

- 资源句柄的形状：`ResourceHandle<T>` 只有 `T get()`（加一个 `invalid()` 哨兵）。真正的句柄容器是 `LevelTargetBundle`（`targets`），public 字段 `main/translucent/itemEntity/particles/weather/clouds/entityOutline` + `replace(Identifier, ResourceHandle<RenderTarget>)` + `get(Identifier)` + `clear()`。

### `javap` 原始签名摘录

```
# javap -p com.mojang.blaze3d.framegraph.FrameGraphBuilder
public class com.mojang.blaze3d.framegraph.FrameGraphBuilder {
  private final java.util.List<com.mojang.blaze3d.framegraph.FrameGraphBuilder$InternalVirtualResource<?>> internalResources;
  private final java.util.List<com.mojang.blaze3d.framegraph.FrameGraphBuilder$ExternalResource<?>> externalResources;
  private final java.util.List<com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass> passes;
  public com.mojang.blaze3d.framegraph.FrameGraphBuilder();
  public com.mojang.blaze3d.framegraph.FramePass addPass(java.lang.String);
  public <T> com.mojang.blaze3d.resource.ResourceHandle<T> importExternal(java.lang.String, T);
  public <T> com.mojang.blaze3d.resource.ResourceHandle<T> createInternal(java.lang.String, com.mojang.blaze3d.resource.ResourceDescriptor<T>);
  private <T> com.mojang.blaze3d.framegraph.FrameGraphBuilder$InternalVirtualResource<T> createInternalResource(java.lang.String, com.mojang.blaze3d.resource.ResourceDescriptor<T>, com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass);
  public void execute(com.mojang.blaze3d.resource.GraphicsResourceAllocator);
  public void execute(com.mojang.blaze3d.resource.GraphicsResourceAllocator, com.mojang.blaze3d.framegraph.FrameGraphBuilder$Inspector);
  private java.util.BitSet identifyPassesToKeep();
  private void discoverAllRequiredPasses(com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass, java.util.BitSet, java.util.Deque<com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass>);
  private void resolvePassOrder(com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass, java.util.BitSet, java.util.BitSet, java.util.List<com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass>);
  private void assignResourceLifetimes(java.util.Collection<com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass>);
  private java.lang.String lambda$resolvePassOrder$0(int);
}

# javap -p com.mojang.blaze3d.framegraph.FramePass
public interface com.mojang.blaze3d.framegraph.FramePass {
  public abstract <T> com.mojang.blaze3d.resource.ResourceHandle<T> createsInternal(java.lang.String, com.mojang.blaze3d.resource.ResourceDescriptor<T>);
  public abstract <T> void reads(com.mojang.blaze3d.resource.ResourceHandle<T>);
  public abstract <T> com.mojang.blaze3d.resource.ResourceHandle<T> readsAndWrites(com.mojang.blaze3d.resource.ResourceHandle<T>);
  public abstract void requires(com.mojang.blaze3d.framegraph.FramePass);
  public abstract void disableCulling();
  public abstract void executes(java.lang.Runnable);
}

# javap -p com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass（唯一实现，包私有）
class com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass implements com.mojang.blaze3d.framegraph.FramePass {
  private final int id;
  private final java.lang.String name;
  private final java.util.List<com.mojang.blaze3d.framegraph.FrameGraphBuilder$Handle<?>> writesFrom;
  private final java.util.BitSet requiredResourceIds;
  private final java.util.BitSet requiredPassIds;
  private java.lang.Runnable task;
  private final java.util.List<...InternalVirtualResource<?>> resourcesToAcquire;
  private final java.util.BitSet resourcesToRelease;
  private boolean disableCulling;
  public <T> com.mojang.blaze3d.resource.ResourceHandle<T> createsInternal(java.lang.String, com.mojang.blaze3d.resource.ResourceDescriptor<T>);
  public <T> void reads(com.mojang.blaze3d.resource.ResourceHandle<T>);
  public <T> com.mojang.blaze3d.resource.ResourceHandle<T> readsAndWrites(com.mojang.blaze3d.resource.ResourceHandle<T>);
  public void requires(com.mojang.blaze3d.framegraph.FramePass);
  public void disableCulling();
  public void executes(java.lang.Runnable);
}

# 句柄与分配器
public interface com.mojang.blaze3d.resource.ResourceHandle<T> {
  public static final com.mojang.blaze3d.resource.ResourceHandle<?> INVALID_HANDLE;
  public static <T> com.mojang.blaze3d.resource.ResourceHandle<T> invalid();
  public abstract T get();
}
public interface com.mojang.blaze3d.resource.GraphicsResourceAllocator {
  public static final com.mojang.blaze3d.resource.GraphicsResourceAllocator UNPOOLED;
  public abstract <T> T acquire(com.mojang.blaze3d.resource.ResourceDescriptor<T>);
  public abstract <T> void release(com.mojang.blaze3d.resource.ResourceDescriptor<T>, T);
}

# 句柄容器（原版的 LevelTargetBundle，public 字段 + 替换/查询）
public class net.minecraft.client.renderer.LevelTargetBundle implements net.minecraft.client.renderer.PostChain$TargetBundle {
  public static final net.minecraft.resources.Identifier MAIN_TARGET_ID;          // 以及 TRANSLUCENT/ITEM_ENTITY/PARTICLES/WEATHER/CLOUDS/ENTITY_OUTLINE_TARGET_ID
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> main;
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> translucent;
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> itemEntity;
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> particles;
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> weather;
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> clouds;
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> entityOutline;
  public void replace(net.minecraft.resources.Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>);
  public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> get(net.minecraft.resources.Identifier);
  public void clear();
}

# 唯一两个 public 的"接受 FrameGraphBuilder"的方法
public void PostChain.addToFrame(com.mojang.blaze3d.framegraph.FrameGraphBuilder, int, int, net.minecraft.client.renderer.PostChain$TargetBundle);
public void PostPass.addToFrame(com.mojang.blaze3d.framegraph.FrameGraphBuilder, java.util.Map<net.minecraft.resources.Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>>, com.mojang.blaze3d.buffers.GpuBufferSlice);
public void PostChain.process(com.mojang.blaze3d.pipeline.RenderTarget, com.mojang.blaze3d.resource.GraphicsResourceAllocator);   // 不进图也能单独跑
```

`executes(Runnable)` 里能做什么（= 26.2 的"录制命令"全集，全部 public）：

```
public class com.mojang.blaze3d.systems.RenderPass implements java.lang.AutoCloseable {
  public void setPipeline(com.mojang.blaze3d.pipeline.RenderPipeline);
  public void bindTexture(java.lang.String, com.mojang.blaze3d.textures.GpuTextureView, com.mojang.blaze3d.textures.GpuSampler);
  public void setUniform(java.lang.String, com.mojang.blaze3d.buffers.GpuBufferSlice);   // 或 (String, GpuBuffer)
  public void setVertexBuffer(int, com.mojang.blaze3d.buffers.GpuBufferSlice);
  public void setIndexBuffer(com.mojang.blaze3d.buffers.GpuBuffer, com.mojang.blaze3d.IndexType);
  public void draw(int, int, int, int) / drawIndexed(...) / multiDraw* / drawIndirect(...) / drawMultipleIndexed(...)
  public void enableScissor(int, int, int, int) / disableScissor();
  public void close();
}
public class com.mojang.blaze3d.systems.CommandEncoder {
  public com.mojang.blaze3d.systems.RenderPass createRenderPass(Supplier<String>, GpuTextureView, Optional<Vector4fc>, GpuTextureView, OptionalDouble[, RenderPass$RenderArea]);
  public com.mojang.blaze3d.systems.RenderPass createRenderPass(com.mojang.blaze3d.systems.RenderPassDescriptor);
  public void clearColorTexture(GpuTexture, Vector4fc);
  public void clearColorAndDepthTextures(GpuTexture, Vector4fc, GpuTexture, double[, int,int,int,int]);
  public void clearDepthTexture(GpuTexture, double);
  public void copyTextureToTexture(GpuTexture, GpuTexture, int,int,int,int, int,int,int);
  public void copyTextureToBuffer(GpuTexture, GpuBuffer, long, Runnable, int[, int,int,int,int]);
}
```

### 对天空盒方块的影响

- 1.21.1 那套"`glGenFramebuffers` 乒乓 + `RenderSystem.setShader`"在 26.2 的正规替身就是**一张 `FrameGraphBuilder` 图**：离屏 target → `importExternal` 或 `createInternal(..., RenderTargetDescriptor)`；伪 pass → `addPass(...).executes(...)`；依赖靠 `reads/readsAndWrites`。
- 但**图是你自己的就得你自己 `execute(...)`**，而"接进游戏主图"目前只能借 `PostChain.addToFrame(...)`；想把 pass 插到原版 `sky` 和 `main` 之间，必须 mixin `LevelRenderer.render` / `addSkyPass`（因为 builder 是局部变量，没暴露）。
- `disableCulling()` 很关键：如果一个 pass 的输出"只有模组自己用、原版没人读"，不调它会被 `identifyPassesToKeep` 裁掉（现象：代码跑了但画面没动）。
- `execute()` 只是**录制**，真正 GPU 提交在命令编码器 `submit()`；`PostChain.process(...)` 是"绕过图、立刻跑一遍"的便捷口，做"每帧一次的合成"比自建图省事。

---

## 对移植的直接影响

### 现状一句话

26.2 的世界渲染 = **`GameRenderer.render` → `GameRenderer.renderLevel` → `LevelRenderer.render` → 一张 `FrameGraphBuilder` 图（`clear` / `sky` / `main` / `clouds` / `weather` / `always_on_top`）**；
"提交几何"从 `MultiBufferSource` 变成 `SubmitNodeCollector`；"天空"从可注册的 `DimensionSpecialEffects` 变成一个 public 的 `SkyRenderer` 具体类 + 一个只有三值的 `DimensionType.Skybox` 枚举。

### 最省事的 3 个切入点（按"零 mixin 优先"排序）

**切入点 1 —— 方块面本身的绘制（零 mixin，改动面最大但最机械）**

- 类：`net.minecraft.client.renderer.blockentity.BlockEntityRenderer`
  签名：`public abstract void submit(S state, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera)`
- 提交几何：`net.minecraft.client.renderer.SubmitNodeCollector`
  签名：`public abstract void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.rendertype.RenderType, net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer)`
  回调：`SubmitNodeCollector$CustomGeometryRenderer.render(com.mojang.blaze3d.vertex.PoseStack$Pose, com.mojang.blaze3d.vertex.VertexConsumer)`
- 面用的 RenderType（建一次、静态复用）：
  `net.minecraft.client.renderer.rendertype.RenderType.create(java.lang.String, net.minecraft.client.renderer.rendertype.RenderSetup)`
  ← `net.minecraft.client.renderer.rendertype.RenderSetup.builder(com.mojang.blaze3d.pipeline.RenderPipeline)` → `withTexture(String, Identifier)` / `useLightmap()` / `setOutputTarget(...)` → `createRenderSetup()`
- 为什么最省事：这条链上没有任何 private 成员要碰，1.21.1 的顶点代码可以原样搬进 lambda；`RenderPipeline` 只是把"深度/混合/写掩码"从运行时调用改成静态声明。

**切入点 2 —— "拿到天空图"（零 mixin，绕开抓帧）**

- 类：`net.minecraft.client.renderer.SkyRenderer`
  构造：`public SkyRenderer(net.minecraft.client.renderer.texture.TextureManager, net.minecraft.client.resources.model.sprite.AtlasManager, com.mojang.blaze3d.pipeline.RenderTarget)`（target 传**你自己的** `RenderTarget`）
  画：`public void renderSkyDisc(int skyColor)` / `public void renderSunriseAndSunset(com.mojang.blaze3d.vertex.PoseStack, float sunAngle, int sunriseAndSunsetColor)` / `public void renderSunMoonAndStars(com.mojang.blaze3d.vertex.PoseStack, float sunAngle, float moonAngle, float starAngle, net.minecraft.world.level.MoonPhase, float rainBrightness, float starBrightness)` / `public void renderDarkDisc()` / `public void renderEndSky()`
  填状态：`public void extractRenderState(net.minecraft.client.multiplayer.ClientLevel, float, net.minecraft.client.Camera, net.minecraft.client.renderer.state.level.SkyRenderState)`
- 调用时机（Fabric，零 mixin）：
  `net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.START_MAIN` → `LevelRenderEvents$StartMain.startMain(LevelTerrainRenderContext)`；
  上下文可取 `levelRenderer()` / `levelState()` / `sectionsToRender()`，`LevelRenderState.skyRenderState` 是 public 字段。
- 局限（必须写清楚）：这条路是**自己重画**天空，所以拿不到"Iris 光影真正画出的那片天"；要那一层就得走 mixin（`LevelRenderer.addSkyPass` 之后再插 pass，拿 `targets.main`）。
- **待实测**：`SkyRenderer` 的绘制依赖 `RenderSystem.getModelViewMatrixCopy()` / `bindDefaultUniforms(RenderPass)` 所绑定的当帧投影与雾，`START_MAIN` 时刻这些全局状态是否已就绪，本次未跑游戏验证。

**切入点 3 —— 帧末合成（这里绕不开 mixin，但合成本身全 public）**

- 注入点：`net.minecraft.client.renderer.GameRenderer.render(net.minecraft.client.DeltaTracker, boolean)`，位置 = `renderLevel` 调用之后、`profiler.push("gui")` 之前（字节码 offset 261 与 374 之间）。
- 合成执行（照抄原版 post effect 的三行）：
  `net.minecraft.client.renderer.ShaderManager.getPostChain(net.minecraft.resources.Identifier, java.util.Set<net.minecraft.resources.Identifier>)` → `PostChain`
  `public void net.minecraft.client.renderer.PostChain.process(com.mojang.blaze3d.pipeline.RenderTarget, com.mojang.blaze3d.resource.GraphicsResourceAllocator)`
  （`GraphicsResourceAllocator.UNPOOLED` 可直接用；要池化就学 `GameRenderer` 里的 `CrossFrameResourcePool`）
- 取实例全走 public（已核验）：`net.minecraft.client.Minecraft.getInstance()`、`public final net.minecraft.client.renderer.LevelRenderer Minecraft.levelRenderer`、`public final net.minecraft.client.renderer.GameRenderer Minecraft.gameRenderer`、`public net.minecraft.client.renderer.ShaderManager Minecraft.getShaderManager()`、`public com.mojang.blaze3d.pipeline.RenderTarget GameRenderer.mainRenderTarget()`。
- 若坚持零 mixin：退而用 `LevelRenderEvents.END_MAIN` → `LevelRenderEvents$EndMain.endMain(LevelRenderContext)`，但它位于 **`main` pass 末尾**（云/雨/always-on-top 尚未绘制），合成结果会缺云缺雨 —— 这是语义降级，不是等价替换。

### 需要 mixin 的三处（明确列出，避免后续再猜）

| 需求 | 只能 mixin 的对象 | 原因（实测） |
|---|---|---|
| 抓"纯天空"那一帧（含光影） | `LevelRenderer.addSkyPass` / `LevelRenderer.render` | `FrameGraphBuilder` 是 `render` 的局部变量；`LevelRenderer.targets` 无 getter；`sky` pass 无 Fabric 事件 |
| 替换而不是并列主世界的 `SkyRenderer` | `LevelRenderer` 的 `skyRenderer` 字段 + `addSkyPass` 的 `new SkyRenderer(...)` | `SkyRenderer` 无 setter 注入点，`LevelRenderer` 自己 new |
| 真正"帧末、GUI 前"的合成 | `GameRenderer.render(DeltaTracker, boolean)` | 该方法内无任何 public 分流点；`END_MAIN` 语义不等价 |

### 仍然查不到的

- vanilla 侧**任何**形式的"天空渲染回调/接口"：`DimensionSpecialEffects` 已删除，`DimensionType.Skybox` 只是三值枚举，`SkyRenderer` 无子类化点（构造器 public 但 `LevelRenderer` 内部直接 `new`）。→ **"不 mixin 接管天空"在 26.2 查不到官方入口。**
- `FrameGraphBuilder` 的公开获取途径：查不到（`LevelRenderer.render` 局部变量，只有 `PostChain.addToFrame` / `PostPass.addToFrame` 两个 public 消费者）。

### 附：本次新增的 dump 索引（`skybox-mod-26.2\build\api\`）

- 签名：`net_minecraft_client_renderer_LevelRenderer.txt`、`net_minecraft_client_renderer_GameRenderer.txt`、`net_minecraft_client_renderer_SkyRenderer.txt`、`net_minecraft_client_renderer_CloudRenderer.txt`、`net_minecraft_client_renderer_SubmitNodeCollector*.txt`、`net_minecraft_client_renderer_OrderedSubmitNodeCollector.txt`、`net_minecraft_client_renderer_SubmitNodeStorage.txt`、`net_minecraft_client_renderer_LevelTargetBundle.txt`、`net_minecraft_client_renderer_state_level_*.txt`、`net_minecraft_client_renderer_rendertype_*.txt`、`net_minecraft_world_level_dimension_DimensionType_Skybox.txt`、`com_mojang_blaze3d_framegraph_*.txt`、`com_mojang_blaze3d_vertex_{VertexConsumer,MeshData,BufferBuilder}.txt`、`com_mojang_blaze3d_resource_*.txt`、`com_mojang_blaze3d_systems_{RenderPass,CommandEncoder}.txt`、`net_minecraft_client_renderer_blockentity_BlockEntityRenderer.txt`、`fabric_*Level*.txt`
- 字节码：`code_LevelRenderer.txt`、`code_GameRenderer.txt`、`code_SkyRenderer.txt`、`code_CloudRenderer.txt`、`code_Minecraft.txt`
