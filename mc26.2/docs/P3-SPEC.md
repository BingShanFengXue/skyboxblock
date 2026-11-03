# P3 实施规格：26.2 客户端渲染重建（自绘天空 + BE 提交）

- 依据：`docs/API-MATRIX-26.2.md` §2.7/§2.8、`docs/api/R1-R2-render-graph.md`、决策 §6c/§6d
- 目标：把 1.21.1 那套「抓主帧 + 换 framebuffer + 自研 shader + GL 状态机」换成「自绘天空到自有 target + BE 提交几何」。

## 0. 一句话架构

```
每帧 START_MAIN
   └─ SkyRendererHost：把当前维度的天空画进自己的 RenderTarget（一次性 setup，按需 resize）
方块实体渲染
   └─ SkyBlockEntityRenderer.submit(state, pose, collector, camera)
         └─ collector.submitCustomGeometry(pose, SKY_FACE_RENDER_TYPE, (pose, vc) -> 写 6 个面)
               └─ 采样 SkyRendererHost 的 GpuTextureView
```

## 1. 新文件清单（全部新写）

| 文件 | 职责 | 依赖的实测签名 |
|---|---|---|
| `client/SkyPipelines.java` | 定义 `RenderPipeline` 常量（面绘制用 `POSITION_COLOR_TEX`，无深度写入，可选 blend） | `RenderPipeline.Builder#withVertexShader/withFragmentShader/withVertexBinding/withDepthStencilState/withColorTargetState/withBindGroupLayout/build` |
| `client/SkyRenderType.java` | `RenderType.create("skyboxblock:sky_face", RenderSetup.builder(PIPELINE).withTexture("Sampler0", TEX).createRenderSetup())`，纹理用运行期 `GpuTextureView`，因此改为"每次 target 重建时重建 RenderType" | `RenderType.create(String, RenderSetup)`；`RenderSetup.builder(RenderPipeline)`；`withTexture(String, Identifier)` |
| `client/SkyRendererHost.java` | 持有 `RenderTarget`（`new RenderTarget(label, useDepth, GpuFormat)`）+ 一个 `SkyRenderer`；每帧在 `LevelRenderEvents.START_MAIN` 里把天空画进去；尺寸变化时 `resize` | `SkyRenderer(TextureManager, AtlasManager, RenderTarget)`、`renderSkyDisc/renderSunriseAndSunset/renderSunMoonAndStars/renderDarkDisc(...)`；`RenderTarget#resize/getColorTextureView` |
| `client/state/SkyBoxRenderState.java` | BE 渲染状态（`BlockEntityRenderState` 子类），携带 `SkySkin` + 面朝向可见性 | `BlockEntityRenderer<T, S extends BlockEntityRenderState>`：`createRenderState()` / `extractRenderState(T,S,float,Vec3,ModelFeatureRenderer$CrumblingOverlay)` / `submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` |
| `client/SkyBlockEntityRenderer.java`（重写） | 只做 `submit(...)`：取 `SkyRendererHost` 的纹理 → `submitCustomGeometry` 写 6 个面（顶点写法照搬 1.21.1 的 `SkyMesh`） | `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`；`CustomGeometryRenderer.render(PoseStack$Pose, VertexConsumer)`；`VertexConsumer.addVertex/setColor/setUv/setUv1/setUv2/setNormal` |
| `client/SkyboxBlockClient.java`（重写） | 注册 BER（`BlockEntityRendererFactories` 或 Fabric `BlockEntityRendererRegistry`）+ `LevelRenderEvents.START_MAIN` + 资源重载钩子 | `LevelRenderEvents`（Fabric rendering v1 level 包） |

## 2. 留档（移出编译，不删）

`SkyGlState`、`SeamScan`、`SkyCloudStencil`、`SkyVanillaClouds`、`VanillaSkyScene`、`SkyTemporalEdgeMask`、
`SkyCoverage`、`SkyExposureMeter`、`SkyWorldRenderer`、`ItrpSkyBridge`、`PortalViewCapture`、`SkySurfaceLayer`、
`SkyMesh`、`SkyCloudMesh`、`SkyVanillaGeometry`、`RenderRegression`、`FixtureClientControl`、`FixtureDistantTerrain`、
`FixtureStartup`、`BlockCollectionDiagnostics`、`DistantSkyDepth`、`ModCompat`、`SkyDisplayState`、`SkyTimeController`

mixin 同步留档：`BufferSourceMixin`、`CloudBuildRangeMixin`、`CloudMeshAccess`、`FogColorAccess`、`GameRendererMixin`、
`IrisCompositeMixin`、`IrisExposureComputeMixin`、`IrisPipelineMixin`、`LevelRendererMixin`、`Regression*` ×3
（**只保留 `CustomSkyPackMixin`**）

保留在主源集里的纯逻辑：`SkyStyle`、`SkySkin`、`SkyBlock`、`SkyBlockEntity`、`SkyboxSettings`、`SkyEnvironment`、
`SkyTimeClock`、`SkyboxBlockMod`、`SkyboxNetworking`、`SkyboxConfigPayload`、`server/*`、`custom/*`、
`SkyRenderMath`、`SkyEnvironmentMath`、`SkyCloudRange`。

## 3. 资源侧同步项

- 9 个内置方块的物品模型：`assets/skyboxblock/models/item/*.json` → `assets/skyboxblock/items/*.json`，
  内容改为 `{"model":{"type":"minecraft:model","model":"skyboxblock:block/<id>"}}`；
- `fabric.mod.json`：`depends` 的 `minecraft` 版本改 `>=26.1 <27`，`fabricloader` `>=0.20.1`；
- `skyboxblock.mixins.json`：只留 `CustomSkyPackMixin`，并**删除 refmap 配置**（26.x 不需要）。

## 4. 验收（P3 分三段）

1. **能编过**：`compileJava` 0 error（client 包新代码 + 上述留档）。
2. **能显示**：进游戏放下天空盒方块，六面显示天空纹理（先用纯色/固定色验证几何），无崩溃。
3. **能对上**：同一 seed/视角下，方块面与真实天空方向一致（截图归档 `docs/screenshots/`）。

## 5. 已知待实测（写代码时会立刻暴露的点）

- `SkyRenderer` 在 `START_MAIN` 时刻是否已具备当帧投影/雾（报告 R6 标注的唯一未验证项）；
- `RenderTarget` 的 `GpuFormat` 取值与色彩空间；
- `LevelRenderEvents.START_MAIN` 的回调参数形状（是否给 `GameRenderer`/`LevelRenderer`）；
- `BlockEntityRendererFactories` 在 26.2 的注册入口（原版 `BlockEntityRendererProvider`）。
