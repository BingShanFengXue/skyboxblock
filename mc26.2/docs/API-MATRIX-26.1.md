# API MATRIX：26.1 实测（相对 26.2 的差集）

> **2026-11-02 更新：26.1 已经编译通过（`BUILD SUCCESSFUL`），并产出一份同时适配 26.1/26.2 的 jar。**
> 做法：把上面这些差异全部收进 `SkyRendererHost` 的**运行时反射探测**（构造器/方法/字段各探一次），
> 源码保持单一；`BlockEntityType` 改用 `FabricBlockEntityTypeBuilder`（两版都有）。
> 由于 26.1+ 未混淆、且差异都在运行期解析，**26.1 与 26.2 编译出的 class 字节完全一致**，
> 同一个 jar 两版都能用（`output/skyboxblock-1.0.6-test.0-port.1-mc261.jar`，
> SHA256 `3a15b2d04d6caf2ebc4a9058d2020fe9e6704089545d4456cf644ee4cc12ef5d`）。

- 取样时间：2026-11-01
- 构建方式：`$env:MCVER='26.1'; $env:APIVER='0.155.3+26.1.2'; gradlew compileJava`
  （`-Pmcver=26.1` 不可用：Gradle 会把 `26.1` 当数字解析成 `26`，实测踩过，改用环境变量）
- 结果：**7 个编译错误，全部集中在 2 个文件**（`SkyRendererHost`、`SkyboxBlockMod`）

## 差集（按类归并）

| # | 26.2 写法 | 26.1 实际 | 落点 |
|---|---|---|---|
| 1 | `mc.gameRenderer.mainRenderTarget()` | `mc.getMainRenderTarget()` | `SkyRendererHost:70` |
| 2 | `mc.gameRenderer.mainCamera()` | `mc.gameRenderer.getMainCamera()` | `SkyRendererHost:95` |
| 3 | `CommandEncoder.clearColorAndDepthTextures(GpuTexture, Vector4f, GpuTexture, double)` | 第二参是 **int（ARGB）** | `SkyRendererHost:106` |
| 4 | `com.mojang.blaze3d.GpuFormat.RGBA8_UNORM` | 26.1 里 `GpuFormat` 不在 `com.mojang.blaze3d` 下（待确认包名） | `SkyRendererHost:168` |
| 5 | `new SkyRenderer(TextureManager, AtlasManager, RenderTarget)` | 26.1 构造器只有 **2 参**（无 RenderTarget） | `SkyRendererHost:169` |
| 6 | `new BlockEntityType<>(supplier, Set<Block>)` | 26.1 该构造器是 **private** | `SkyboxBlockMod:97` |

## 第 5 条的含义（重要）

26.1 的 `SkyRenderer` 没有"目标"参数 → 它画到**当前绑定的输出**。所以要把它画进自建 target，只能：

```java
RenderSystem.outputColorTextureOverride = target.getColorTextureView();
RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
try { skyRenderer.renderSkyDisc(...); ... }
finally { RenderSystem.outputColorTextureOverride = null; RenderSystem.outputDepthTextureOverride = null; }
```

这正是 26.2 U4 里"能拿到当前帧纹理"的那条路；而 **26.3 把这两个字段删掉了**，
所以 26.3 必须改成"显式附件 + try-with-resources RenderPass"（见 `26.1-26.3-facts.md`）。

## 结论

26.1 回迁 ≈ **一个小的版本适配层**（1 个 `VersionCompat` 类，或把 `SkyRendererHost`/`SkyboxBlockMod`
放进 `src/26.1/java` 覆盖），不是重写；渲染范式（`RenderPipeline`/`RenderPass`/`SubmitNode`）三版一致。

## 复现命令

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4.1'
$env:GRADLE_USER_HOME='E:\DSHworkspace\DSHminecraft\skybox-mod-26.2\.gradle-home'
$env:MCVER='26.1'; $env:APIVER='0.155.3+26.1.2'
.\gradlew.bat --no-daemon --console=plain compileJava   # 日志：build/compile-26.1.log
```

> 注意：并发跑两个 Gradle 会争 `caches\fabric-loom` 锁（实测卡了 20 分钟）。跑之前先
> `Get-Process java | Stop-Process`（只杀 Gradle，不要误杀 Minecraft 客户端）。
