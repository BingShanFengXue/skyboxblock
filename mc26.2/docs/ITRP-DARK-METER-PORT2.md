# ITRP dark-style meter：1.21.1 → 26.2 移植

日期：2026-11-03（Asia/Shanghai）。本轮仅实现与离屏验证；实机触发/A-B 验收交由主线，不将结构断言称作实机证据。

## 依据与范围

技能目录已核对：仅 ACL / Office 系列，本任务无匹配项。已读工作区 AGENTS、docs/LESSONS，以及真实 `../skybox-mod` 的完整 102 行 SkyExposureMeter、IrisExposureComputeMixin、ItrpSkyBridge.exposureMeterInputs、SkyWorldRenderer.needsDarkSkyMeter 和 sky_meter_proxy.fsh；未以 legacy 副本代替真实 1.21.1 源。

严格保留：原 scene-input / opaque MRT 桥、before-composite40 注入、SkyGlScope、native scene/state、相对网格顶点、原有深度容差及 TransparentGuard。未改 fixture、运行脚本、Gradle 配置、注册或语言文件；未运行 Gradle、游戏或 fixture 请求，未创建 git commit。

修改范围：

- [SkyWindowRenderer](../src/main/java/dev/yg/skyboxblock/client/SkyWindowRenderer.java)：只增加只读 MeterView record / active-root accessor，以及 close/reload 路径的代理 release。Frame/Gpu 仍 private，矩阵构造与 accessor 均 defensive copy。
- [ItrpSceneBridge](../src/main/java/dev/yg/skyboxblock/client/ItrpSceneBridge.java)：只新增 meter 输入所有权验证接口；opaque / sceneInput 机制不变。
- 新增 [ItrpDarkMeterProxy](../src/main/java/dev/yg/skyboxblock/client/ItrpDarkMeterProxy.java)、[SkyIrisExposureComputeMixin](../src/main/java/dev/yg/skyboxblock/mixin/SkyIrisExposureComputeMixin.java)，并登记 [mixins](../src/main/resources/skyboxblock.mixins.json)。
- 新增 isolated [vertex shader](../src/main/resources/assets/skyboxblock/shaders/isolated/itrp_dark_meter.vsh) / [fragment shader](../src/main/resources/assets/skyboxblock/shaders/isolated/itrp_dark_meter.fsh)，以及 [GL3.3 regression](../src/test/java/dev/yg/skyboxblock/client/SkyItrpDarkMeterRegression.java)。

## 实际安装字节码与 hook

本机安装 Iris `iris-fabric-1.11.4+mc26.2.jar`；其 fabric 元数据 version 也为 `1.11.4+mc26.2`。

JDK：`C:\Program Files\Java\jdk-25.0.4.1\bin`。用 javap 对安装 jar（不是猜测的旧源码）核对：

1. `ComputeProgram.use()`：GL use → uniforms.update → samplers.update → images.update。
2. `CompositeRenderer.renderAll()`：compute.use → customUniforms.push(compute) → compute.dispatch(float,float)。
3. `ComputeProgram.dispatch` 的实际 descriptor 为 `(FF)V`；内部只有条件 memoryBarrier、indirect 或 getWorkGroups + dispatch；没有 sampler/image 二次更新。
4. 因此精确 hook 为 `@WrapMethod(method="dispatch(FF)V", remap=false)`；`beforeCompute(this)` 后原封不动调用 original，finally 恢复。
5. ComputeOnlyPass 名称来自 ComputeSource.getName；其 `computes` 成员也由 Pass 继承。验证时遍历当前 compositeRenderer.passes，要求成员对象身份与 dispatch 的 this 完全一致、且只出现一次、pass name 为 `composite74`。
6. 原生 `IrisRenderSystem.memoryBarrier` 使用同名 glMemoryBarrier 指针，dispatchCompute 使用同名 glDispatchCompute。新代理不从 class 名称推断 core 版本可用性。

可复核字节码：[ComputeProgram](../build/audit-r2/Iris-1.11.4-ComputeProgram.javap.txt)、[CompositeRenderer](../build/audit-r2/Iris-1.11.4-CompositeRenderer.javap.txt)。

安装 shaderpack 实算 SHA-256：

```text
334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8
```

其 overworld / nether / end 的 `composite74.csh` 均 include `Exposure_CS.glsl`：local size `(32,16,1)`，absolute workGroups `(1,1,1)`；实际采样 tile offset 为 `15/32 + pixelSize.x*12`，tile 宽高均为 atlas 的 `1/64`。原 compute 负责 `imageStore(img_pixelData2D, exposure texel)`，新代理没有 image 绑定或 image 写入代码。

## 运行条件与所有权

仅以下条件全部成立才替换 colortex8：

- active root、slot 0、非 portal、非 shadow，drawn 且尚未 resolved，有 `skin.darkSky()` 材质，scene bridge 已对本 view 注入。
- Minecraft 精确 `26.2`、Iris 精确 `1.11.4+mc26.2`、当前 shaderpack archive 的真实 hash 命中上述值。
- view 保存的 pipeline / renderTargets / composite 与当前 pipeline 对象一致，composite 的 pipeline/targets/COMPOSITE stage 一致。
- 当前 GL program 与 dispatch 的 ComputeProgram.getProgramId 一致；compute 身份来自该 composite74；无 indirect pointer、absolute groups 1×1×1，GL20.glGetProgramiv 查询到真实 local size 32×16×1。
- img_pixelData2D 与 pixelData2D 采样器实际指向同一个、pipeline.customImages 所有的 512×513 RG16F exposure image；image level/layered/format 也一致。
- colortex8 sampler 当前 texture 属于该 renderTargets[8] 的 main/alt，存储为正尺寸 RGBA16F / RGBA16。
- opaqueDepth 来自当前 targets 的 no-translucents GpuTexture/真实 GlTexture，尺寸与 view 相同且为 depth 格式；coverage 为私有 RG32F，generated canvas 为真实私有 RGBA8；全部输入 texture 名称互不别名。
- 实际 sampler unit / texture / sampler 对象在 render scope 前后再核对，失配则保留 generic 输入，不绑定猜测纹理。

## 保留的 1.21.1 语义

- 只向私有 RGBA16F atlas 的曝光 tile 加 2 texel bilinear margin 进行 scissor draw；不复制世界 RGB，也不清整张 atlas。tile 以外不供该曝光 compute 使用。
- dark coverage material+1 值为 3 / 7 / 9（black / galaxy / apocalypse），配合原 `1.2e-7` opaque-depth provenance 比较；普通前景与非 dark 材质保持原 meter 输入。
- 8×8 子样本、32×16 metering cell、linear 解码 exponent 2.2、18% neutral metering floor、每子样本 reference/exposure 上限 2048，与原 meter 定义一致。
- `GeneratedSky` 来自 renderer 私有 vanilla canvas，绝不是 black/panorama 的展示纹理。26.2 canvas 已按 rasterProjection 绘制，故 native q 对 coverage 和 generated canvas 都映射到 `dq=q*scale+jitter*.5`；这是 canvas 坐标契约适配，不重新渲染或改 scene RGB。
- 原 exposure compute、工作组、自动曝光及曝光 image 保持不变；不处理 hand overlay，不改场景或手部亮度，不禁自动曝光。
- compute 入口仅临时替换 colortex8 单元的 texture + sampler；原 dispatch 后的 finally 恢复真实 texture、sampler 与入口 activeUnit，即使 dispatch 抛异常。
- close / reload 释放代理 texture、FBO、program、VAO、samplers。

## GL3.3 真机离屏证据

测试申请的是隐藏 GL3.3 core forward-compatible 窗口，非 4.5 离屏替代；不启动 Minecraft。

```text
GL 3.3.0 NVIDIA 621.01 / NVIDIA GeForce RTX 4060 Ti/PCIe/SSE2
core40=false core42=false core43=false
ARB_compute=true ARB_image=true
glDispatchCompute=140732585312880
glMemoryBarrier=140732585417136
glBindImageTexture=140732585252976
```

指针是该次进程地址，仅证明该进程实际加载非零函数入口。生产 capability gate 同时检查 core/ARB capability 与指针；在该 context 下真实执行 `ARBShaderImageLoadStore.glMemoryBarrier`、`ARBComputeShader.glDispatchCompute`、`GL20.glGetProgramiv(...COMPUTE_WORK_GROUP_SIZE...)`，GL error=0。没有调用 core40-only blend API。

新回归直接调用生产 `substitute/renderProxy` 和生产 isolated GLSL，不复制代理算法。compute 是独立 instrumented atlas-sampling oracle，用于证明原 dispatch 仍负责 image 输出，不冒充完整 ITRP compute 或实机模组加载。

测得：

| 指标 | 实测 |
|---|---:|
| dark proxy RGB | 1.0585938 |
| 普通 opaque 前景 meter RGB | 0.010002136（原值） |
| 非 dark material meter RGB | 0.010002136（原值） |
| 同一个 oracle compute，meter on average | 0.5097656 |
| 同一个 oracle compute，fixture negative average | 0.010002136 |

检查合计 64 项：51 GPU/contract + 13 明确标识的 structural。包含 unit 11 超出 SkyGlScope 私有 0..5 范围的替换、入口 activeUnit 13 恢复、私有单元 0..5 纹理+sampler 恢复、异常 finally / 幂等 restore、atlas scissor+2 margin、前景/non-dark/3个dark材质、18% floor / 2048 finite cap、invalid exposure、raster scale+jitter、错误 atlas/sampler 保留 generic、原 scene/bloom 全纹理不变、原 image 在 dispatch 前逐值不变、dispatch 后只有 oracle 的 selected texel 改变，以及 lifecycle release。

日志：[64 项新回归](../build/audit-r2/SkyItrpDarkMeterRegression.log)。

既有相邻回归均通过：

- [SkyGl33Regression](../build/audit-r2/SkyGl33Regression.log)：11 checks。
- [SkyItrpSceneInputRegression](../build/audit-r2/SkyItrpSceneInputRegression.log)：16 checks，原 opaque MRT / HDR / refraction / foreground / transparent guard 均保留。
- [SkyWindowCoreRegression](../build/audit-r2/SkyWindowCoreRegression.log)：1189 checks。
- [SkySceneSemanticsRegression](../build/audit-r2/SkySceneSemanticsRegression.log)：199046 checks。

完整 61 个 main Java 源 + 上述 5 个 test Java 源，用既有 compile-classpath、JDK25、`javac -proc:none -encoding UTF-8 --release 25` 编译，exit=0。工作区通用 verify-javac 脚本硬编码 ohwhale-mod，故未用它去验证错误项目，也未修改该脚本；本轮等价直接 javac 到独立 [输出目录](../build/audit-r2/javac-dark-meter)。JDK/LWJGL 仅有既有 sun.misc.Unsafe 弃用警告，非 GL / 编译错误。

复现命令（在项目根目录；不生成运行脚本、不运行 Gradle）：

```powershell
$cp=(Get-Content -LiteralPath 'build\compile-classpath.txt' -Raw).Trim()
$out='build\audit-r2\javac-dark-meter'
# source argfile 已由全 61 个 main 源路径生成，UTF-8 无 BOM / 正斜杠。
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\javac.exe' -nowarn -proc:none -encoding UTF-8 --release 25 -cp $cp -d $out '@build/audit-r2/dark-meter-sources.txt'
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\javac.exe' -nowarn -proc:none -encoding UTF-8 --release 25 -cp ($out+';'+$cp) -d $out 'src\test\java\dev\yg\skyboxblock\client\SkyItrpDarkMeterRegression.java'
$natives=@(Get-ChildItem -LiteralPath '.gradle-home\caches\modules-2\files-2.1\org.lwjgl' -Recurse -File -Filter '*-natives-windows.jar' | Where-Object { $_.FullName -match '\\3\.4\.1\\' } | ForEach-Object FullName) -join ';'
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe' --enable-native-access=ALL-UNNAMED -cp ($out+';src\main\resources;'+$cp+';'+$natives) dev.yg.skyboxblock.client.SkyItrpDarkMeterRegression
```

## 主线实机 A/B 入口及待验项

同一 jar 的唯一 meter negative control：

```text
-Dskyboxblock.fixture.control=<现有fixture控制文件>
-Dskyboxblock.fixture.disableItrpDarkMeter=true
```

缺少非空 fixture.control 时 disable flag 不生效。on 组不设置 disable 或设 false；其余 scene bridge / auto exposure 配置完全相同。无需增加 fixture API 或变更脚本。

实机应收集的已埋日志（每个 activation/restoration 最多前三次）：

```text
[skyboxblock] ITRP dark meter: composite74 sampler proxy active view=... atlas=... proxy=... exposure=... unit=... local=32x16x1 core42=false core43=false ARB_compute=true ARB_image=true dispatchPtr=true barrierPtr=true
[skyboxblock] ITRP dark meter: original atlas texture+sampler+activeUnit restored after compute view=...
```

negative 组的一次日志：

```text
[skyboxblock] ITRP dark meter: fixture negative control; original exposure compute retained
```

本子任务没有启动游戏，故上述实机 activation/restoration 尚待主线采集。仍需同 jar、同 fixture、同收敛时长，对比 black 背景下的砂岩亮度/曝光，同时确认白背景、普通 concrete、玻璃粉色 refraction 与既有三段 scene bridge 激活日志保持稳定。当前 [PORT2-VALIDATION](PORT2-VALIDATION.md) 留给主线管理，本轮未编辑。
