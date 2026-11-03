# API MATRIX：26.3 实测（相对 26.2 的差集）

> **2026-11-02 更新：26.3 已经编译通过（`BUILD SUCCESSFUL`）。**
> 做法：`src/26.3/java` 放 4 份版本实现，`MCVER=26.3` 时把 main 里同名的 3 个文件排除：
>
> | 26.3 专属实现 | 被排除的 main 文件 | 原因 |
> |---|---|---|
> | `client/v263/SkyRendererHost263.java` | `client/SkyRendererHost.java` | `SkyRenderer.render(GpuBufferSlice, SkyRenderState)` 合并入口；`skyColor`/`sunriseAndSunsetColor` 换类型；renderpearl 包名；`TextureTarget` 改双格式参数 |
> | `SkyBlock.java` | `SkyBlock.java` | 26.3 删除了方块 `codec()` / `simpleCodec(...)` |
> | `BlockPropertyCompat.java` | `BlockPropertyCompat.java` | `isViewBlocking` 的 lambda 从 3 参变 4 参（多 `AABB`） |
>
> 界面选择靠 `SkyHost` 接口 + `SkyHosts`（反射挑实现），所以 `SkyBlockEntityRenderer` / `SkyboxBlockClient` 不用改。
>
> 产物：`output/skyboxblock-1.0.6-test.0-port.1-mc263.jar`，SHA256 `16031924f38e779e913fd8e0b7a9e2d4b2bedcb4aed8a8aa93c5d5cdfcddc735`。
> **注意：切换 `MCVER` 必须 `clean`**——Gradle 曾把 26.3 的 class 当成 26.2 的产物（已在
> `build.gradle` 里给 `JavaCompile` 声明 `targetMinecraftVersion` 输入，但仍建议 clean）。

- 取样时间：2026-11-02
- 构建方式：`$env:MCVER='26.3'; $env:APIVER='0.165.0+26.3'; gradlew compileJava`
- 结果：**29 个编译错误**（日志 `build/compile-26.3.log`）。26.3 的改动比 26.1 大一个量级，符合 Fabric 文档与 primer 的预告。

## 1. 包级搬迁：blaze3d → Renderpearl

| 26.2 | 26.3 |
|---|---|
| `com.mojang.blaze3d.GpuFormat` | `com.mojang.renderpearl.api.GpuFormat` |
| `com.mojang.blaze3d.systems.CommandEncoder` | `com.mojang.renderpearl.api.commands.CommandEncoder` |
| `com.mojang.blaze3d.textures.FilterMode` | `com.mojang.renderpearl.api.textures.FilterMode` |
| `com.mojang.blaze3d.systems.RenderSystem` | **仍在** `com.mojang.blaze3d.systems.RenderSystem` |
| `com.mojang.blaze3d.pipeline.RenderTarget` / `TextureTarget` | **仍在** `com.mojang.blaze3d.pipeline` |

（26.3 里 `com/mojang/blaze3d/` 只剩 158 个条目，其余全在 `com/mojang/renderpearl/{api,backend,frontend,util}`。）

## 2. 天空渲染入口被合并成一个方法（最重要）

| | 26.2 | 26.3 |
|---|---|---|
| 入口 | `renderSkyDisc(int)` / `renderSunriseAndSunset(pose,float,int)` / `renderSunMoonAndStars(pose,…)` / `renderDarkDisc()` / `renderEndSky()` | **`render(GpuBufferSlice, SkyRenderState)`** 一个方法全包 |
| 构造器 | `SkyRenderer(TextureManager, AtlasManager, RenderTarget)` | 同 26.2（仍有 target 参数） |
| 状态抽取 | `extractRenderState(ClientLevel, float, Camera, SkyRenderState)` | 同 |

→ 26.3 的宿主反而更简单：`renderer.render(projectionBuffer.getBuffer(matrix), STATE)` 一句即可。

## 3. `SkyRenderState` 颜色字段类型变了

| 字段 | 26.2 | 26.3 |
|---|---|---|
| `skyColor` | `int` (ARGB) | **`org.joml.Vector3fc`** |
| `sunriseAndSunsetColor` | `int` (ARGB) | **`org.joml.Vector4fc`** |

→ 我们读 `STATE.skyColor` 做清屏色的地方必须按类型分派。

## 4. 资源包 API 变形

| | 26.2 | 26.3 |
|---|---|---|
| 基类 | `AbstractPackResources`（存 location + 惰性 metadata） | **整个类不存在** |
| `PackResources` | 独立接口，含 `getMetadataSection(MetadataSectionType)` | 变成 `PackResources extends PackMetadataResources`，接口里不再有 metadata/location/close |
| `Pack.ResourcesSupplier` | `openPrimary(info)` + `openFull(info, Metadata)` | **`openMetadata(info)` + `openResources(info, Metadata)`** |

→ `CustomSkyPack` / `CustomSkyPacks` 必须为 26.3 单独实现。

## 5. 其它

- `Util.getPlatform().openFile(File)` 在 26.3 不可用（`CustomSkyCommand` 的"打开文件夹"）；
- `RenderSystem.outputColorTextureOverride / outputDepthTextureOverride` 已删除（26.1 用来重定向输出的那招在 26.3 失效）。

## 6. 结论：26.3 需要一套版本源码

26.1/26.2 靠反射 + 一份源码就够；26.3 的差异是**接口形状**（不是签名细节），反射塞不进去。方案：

```
src/main/java/...                      # 26.1/26.2 共用（现状）
src/26.3/java/.../client/SkyRendererHost.java     # render(slice,state) 版
src/26.3/java/.../client/CustomSkyPacks.java      # openMetadata/openResources 版
src/26.3/java/.../custom/CustomSkyPack.java       # 直接实现 PackResources 版
src/26.3/java/.../client/CustomSkyCommand.java    # 打开文件夹改用 Desktop/新版 API
```
Gradle 侧：`MCVER=26.3` 时把这些文件从 `src/main` 排除并加入 `src/26.3/java`。
（注意：`src/26.3/java` 与 `src/main/java` 会撞类名，必须用 `java.srcDirs` + 精确 `exclude` 绝对路径，或改用"接口 + 版本实现类"的间接层——上一轮试过朴素写法会撞名，要在下一轮用后者更稳。）

## 复现

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.4.1'
$env:GRADLE_USER_HOME='E:\DSHworkspace\DSHminecraft\skybox-mod-26.2\.gradle-home'
$env:MCVER='26.3'; $env:APIVER='0.165.0+26.3'
.\gradlew.bat --no-daemon --console=plain compileJava   # 29 errors
```
