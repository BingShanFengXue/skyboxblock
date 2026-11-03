# 新渲染核心精准语义复核

日期：2026-11-02，Asia/Shanghai。方法：当前源码、真实 1.21.1 基线源码/自定义 shader、本机 Minecraft jar 的 `javap -p -c` 与独立数值回归交叉验证。

**边界**：未启动游戏、未跑 Gradle、未发实例控制请求；仅只读查看现有 client.log。未改 SkyGlScope、SkyWindowRenderer、mixins、fixture、构建或启动工具。当前运行 jar 不含本次修改；GPU 图像验收由主线重建后执行。会话 skills 只有 ACL/Office 类，本任务无匹配项，未 load。没有再派子个体。

## 1. 结论与真实改动

| 项 | 判定 | 本次动作 |
|---|---|---|
| sun/moon 是否应联动 | 旧版显式 override 本来独立；“sun override 必联动 moon”错误 | 保持独立，不补 `moon=sun+π` |
| 大有限角度 | 新核心绕过了原基线的 mod360，转 float 弧度后失精度 | 调用已有 `SkyEnvironmentMath.angle` 再转弧度 |
| moon 的 `-180` | 旧 y=-100 配合 angle-180；26.2 原生 y=+100 配合 native moonAngle | 保持新坐标约定，不加 -180 |
| moon 贴图朝向 | 原生 26.2 moon UV 与 sun UV 两轴相反，新核心原先误共用 | 仅 moon 两轴翻转，位置/角度不变 |
| dawn 方位 | 原版基线用 daily phase，原新核心被 sun_angle override 带动 | 快照 override 前 sunriseAngle，仅给 dawn 使用 |
| cloud 配置 alpha×.8 | 与旧原生顶点 alpha=.8 × CloudOpacity 一致，原审计数学错 | 保留 .8 乘数 |
| cloud pack RGB/alpha | CPU TextureData 已包含完整 ARGB，原新核心硬编码白/alpha1 | 解码 cell 高位；保留面亮度梯度；恢复旧 texel alpha cutout |
| 资源包 reload identity | native prepare 新建 TextureData；已有 `!=` 身份键正确 | 不改缓存 |
| 云 fog 度量 | 两边均球面，不是旧柱面/新球面 | 不改距离形状；仅把 linear 改回旧 smoothstep |
| local LOCKED/RUNNING 月相 | 原新核心无条件按 gameTime 重算，背离旧 local 分支/native 月相 | 仅非 local 才按 gameTime 模拟；显式 moon_phase 仍最后覆盖 |
| palette/clock 主公式 | 真实 palette 和 server/local 时钟链已接入 | 不重构、不塞调图常数 |
| DH 单缓存 | 多视图数量本身不证明捕获/消费交错；缺少实际失败时序 | 不改 SkyDistantDepth；不缓存旧矩阵去配当前共享纹理 |

## 2. 可复现的原生证据

### 2.1 jar 身份

- 26.2：`.gradle-home/caches/fabric-loom/26.2/minecraft-client.jar`
  - SHA256 `40896EE9F1E2BEC3C934DAAC7E93D41E9E3D9C2F8AE0CA366D52FFBFD1AFA290`
- 1.21.1 official-mapped：`../skybox-mod/.gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar`
  - SHA256 `7AB7DB4425AFEB42BA5301F860509D766FDC28FB2EF4692413C399E14F90FD60`

JDK：Oracle 25.0.4.1；`javap.exe` 位于 `C:\Program Files\Java\jdk-25.0.4.1\bin`。生成证据均在 build 下独立命名，没有碰运行实例内容：

- [26.2 SkyRenderer / TextureData 字节码](../build/core-semantics-native-javap.txt)
- [26.2 CloudRenderer 字节码](../build/core-semantics-cloud-javap.txt)
- [26.2 NativeImage 字节码](../build/core-semantics-pixels-javap.txt)
- [26.2 MoonPhase / EnvironmentAttributes 字节码](../build/core-semantics-attributes-javap.txt)
- [1.21.1 LevelRenderer / OverworldEffects 字节码](../build/core-semantics-legacy-javap.txt)
- [1.21.1 DimensionSpecialEffects dawn 字节码](../build/core-semantics-dawn-javap.txt)
- [jar 内原始 dimension / timeline / biome / clouds shader 文本](../build/core-semantics-jar-resources.txt)

示例命令（项目根目录；纯 disassembly，非游戏）：

```powershell
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe' -classpath '.gradle-home\caches\fabric-loom\26.2\minecraft-client.jar' -p -c net.minecraft.client.renderer.CloudRenderer
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe' -classpath '.gradle-home\caches\fabric-loom\26.2\minecraft-client.jar' -p -c net.minecraft.client.renderer.SkyRenderer
```

### 2.2 角度、月相和月纹

基线 [VanillaSkyScene](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/VanillaSkyScene.java#L74-L104)：daily `solarMvp` 在 83-85 行先生成；87-88 行的 sun override 只设置该次 draw uniform；92-97 行 moon 默认重新设置原 `solarMvp`。因此**单设太阳角不该移动月亮，也不该移动晨昏带**。本次只为 dawn 单独保存 display daily angle。

基线 moon 显式 override 使用 `angle(moon)-180`，顶点 y=-100；原生 26.2 `renderMoon` 在反编译 1585-1613 行清楚使用 y=+100，而 `renderSunMoonAndStars` 1404-1458 行为三天体分别 push/rotate/pop。**只看旧 `-180` 而漏掉几何 y 翻转会导致修反**。新增360角度数值对照验证中心位置等价，100单位半径的 float 误差容差为 0.0001。

原生 `buildMoonPhases`（537-623行）明确：在几何 `(-1,0,-1),(1,0,-1),(1,0,1),(-1,0,1)` 上设置 `U1V1,U0V1,U0V0,U1V0`；太阳 `buildCelestialQuad`（423-483行）则是 `U0V0,U1V0,U1V1,U0V1`。当前新核心原先太阳/月亮都用后者，形成月纹180°错误。本次 [SkyVanillaScene](../src/main/java/dev/yg/skyboxblock/client/SkyVanillaScene.java#L63-L69) 仅对 moon 翻转UV，不改native angle或纹理路径。这个判断来自**同一26.2 jar内部对照**，没有把资源/API代际变化当错误。

本机 `MoonPhase` enum 的顺序/index 为 full、waning_gibbous、third_quarter、waning_crescent、new、waxing_crescent、first_quarter、waxing_gibbous，均与 `values()[index]` 一致。`data/minecraft/timeline/moon.json` 的周期是192000，8个24000 tick keyframe依次采用同名相位；新的拆图路径是原生变化，不是缺图bug。

[SkySceneState](../src/main/java/dev/yg/skyboxblock/client/SkySceneState.java#L73-L96) 现在：三个显式对象角度只各自改变自己，均先取mod360；native AUTO角度原样保留，未被强制变成旧版角度曲线。

### 2.3 cloud alpha 与 pack颜色

旧 [SkyDisplayState](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/SkyDisplayState.java#L78-L79) 分离 RGB 与 `CloudOpacity=argbAlpha/255`；旧 `LevelRenderer.buildClouds` 字节码的各次 `setColor(FFFF)` 之前明确加载顶点 alpha `.8F`。旧 [vanilla_cloud.fsh](../../skybox-mod/src/main/resources/assets/skyboxblock/shaders/core/vanilla_cloud.fsh#L13-L19) 实际公式：

```text
sample = textureRGBA * vertexRGBA   // vertex alpha = .8
if sample.a < .1: discard
sample.a *= configuredOpacity * (1 - fade)
```

因此新核心原有 `configuredAlpha *= .8` 是一致的基底，不是强制赋值：`255/255*.8=.8`，`179/255*.8=.5615686`，`0*.8=0`。此次保留它。

但新核心原先把每格纹理颜色/alpha全置1，确实丢了彩云与半透明贴图。26.2原生 `prepare` 的65号offset调用 `NativeImage.getPixel`；`packCellData`（294-336行）把整个int先 `i2l`、再 `lshl 4`，随后OR四个方向位。`NativeImage.getPixel`（566-573行）通过 `ARGB.fromABGR` 返回ARGB而不是ABGR。**正确提取是 `(int)(cell>>4)`，不是重新读PNG，也不需要改mixin**。新增回归执行的就是jar中的真实 `packCellData` 字节码，涵盖所有16种方向位、透明度边界、随机ARGB，包含有符号long情形。

`CloudFaces` 是native渲染时的GPU `isamplerBuffer`。`encodeFace`（942-979行）只写 x/z/dirAndFlags 三字节，确实不带RGB；但这不改变CPU TextureData还持有ARGB的事实。本次恢复1.21.1 pack视觉，比26.2原生GPU路径多消费了已有信息，没有触碰原生CloudRenderer状态。

修后 [SkyVanillaScene](../src/main/java/dev/yg/skyboxblock/client/SkyVanillaScene.java#L90-L106) 使用每格ARGB乘原有 `.7/1/.8/.9` 面梯度。[scene.fsh](../src/main/resources/assets/skyboxblock/shaders/isolated/scene.fsh#L26-L35) 恢复旧的 `.8*texelAlpha < .1` cutout，而且在配置透明度之前判断；以8bit alpha说，31会被裁掉，32会保留。native occupancy只要求alpha>=10，因此邻居alpha为10..31时其native face bit会误隐藏一堵本应露出的墙；本次构网格时也按旧cutout判断可见性，并为四方向的cutout邻居补露墙面。这是同一纹理alpha恢复所必需的边界处理，默认opaque白云的拓扑不变。不删除深度预通道、不改变雨或透明保护。

### 2.4 cloud fade 是球面，真实差异只有曲线

旧 [vanilla_cloud.vsh](../../skybox-mod/src/main/resources/assets/skyboxblock/shaders/core/vanilla_cloud.vsh#L15-L20) 明确计算 `length(viewPosition.xyz)`；旧 fsh 明确使用 `smoothstep`，只乘alpha，不混雾色。新核心 `scene.vsh` 已是 `length(pos)` 的球面距离，因此“原柱面→新球面”推导错误。

本次只在云 Mode3 恢复 **smoothstep** 和 `end<=start` 的 `step` 分支，天空Mode0保留26.2原生fog语义。例如128..384区间，在距离192时旧baseline visibility=.84375，而原新核心线性=.75，这是真数值差异，不是截图调参。起止距离仍来自现有同一套环境属性/配置，不塞常数。

### 2.5 reload identity 已经正确

native `prepare` 213-223 offsets 每次成功均 `new TextureData(cells,width,height)`；native `apply` 把新值 `putfield texture`。现有 `data != cloudData` 比较的是**引用身份**，与record `equals`无关，即使同尺寸/同内容也会重建。现有缓存无需另加dirty、不应因误读record而重构。相关字节码存在性与源码identity条件均加入回归。

## 3. 模拟 / LOCKED / RUNNING / palette

对照基线 [SkyDisplayState](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/SkyDisplayState.java#L34-L75)：

- normal+local+AUTO继续读取原生render state；non-normal或nonlocal或自定义时钟进入模拟配色。
- normal模拟使用相机biome；非normal使用注册Plains，同时使用注册Overworld dimension attributes。这是有真实源的palette，不是手写蓝色常数。本机overworld数据给cloud `#ccffffff`、fog `#c0d8ff`、sky `#78a7ff`；Plains给sky `#78a7ff`，day timeline再给动态调制。静态palette与daily模拟曲线分开，避免重复应用native当前时刻的timeline。
- 服务端权威仍走 `SkyboxSettings.clockTicks(gameTick,partial,automatic)`；本地走每ClientLevel的WeakHashMap clock。把CLOCKS作为clock owner不等于多个level共用同一clock：每level的值本来不同，并避免value通过owner强引用WeakHashMap key。没有为此改代码。
- LOCKED返回固定ticks、不加partial；RUNNING按anchor/gameTick加partial。旧审计“LOCKED逐tick跳动”错误。
- **真缺口是月相的来源**：基线无论LOCKED/RUNNING，只要`local`仍采用世界月相，而原新核心因模拟路径把它无条件换成`simulatedMoonPhase(gameTime)`。当世界日历时间与总运行gameTime不同，切换显示时钟就会换月相。本次仅非local才计算模拟月相，显式`moon_phase`仍最后优先。没有改变月亮每日方向或给native角度硬套旧曲线。
- 现有天气、雨亮度、天空/雾色、transparentGuard均保持；不围绕夹具残留rainLevel改核心。

旧dawn数学不需要猜：1.21.1 `DimensionSpecialEffects.getSunriseColor` 字节码含 `.4/.99/.3/.7/.2` 以及alpha平方，与现有模拟表达式一致；Mth查表与Math三角函数/ARGB量化有表示精度区别，不支持“漏了alpha派生”结论。全透明几何是否提交不是视觉缺口，不列修复。

## 4. DH：撤销“确定失败”，保留校验

[SkyDistantDepth](../src/main/java/dev/yg/skyboxblock/client/SkyDistantDepth.java) 本次零改动。当前capture记录的是一份projection、transaction、level、camera、rotation，configure校验这些量及实时DH depth texture尺寸。这个结构支持：

```text
capture(A) -> configure(A) -> capture(B) -> configure(B)
```

它对下面的交错可能跳过A：

```text
capture(A) -> capture(B) -> configure(B) -> configure(A)
```

但“同帧有两个view”并不证明第二种调度，更不证明A还需要旧DH pixels。当前 [SkyWindowRenderer](../src/main/java/dev/yg/skyboxblock/client/SkyWindowRenderer.java#L71-L91) 维护嵌套GameView/Frame栈，drawOpaque/final/endLevel在各自回调里消费当前Frame；不是把所有view积累后集中configure。只有证明capture与这些消费点实际交错，才能把条件风险升级为漏洞。本项目当前26.2依赖/实例jar检索也没有DH jar可用来落实该外部调度。

尤其configure每次重新调用DH代理取**当前共享texture**。给projection元数据做多key缓存而不保存对应深度像素，会把A的旧projection绑定到B的像素；比当前保守跳过更糟。因此此次没有按原报告建议引入多key缓存。若主线以后提供真实DH+嵌套trace，需一起记录捕获token/camera、texture id及内容代数、configure消费次序，再决定是否需要按view持有深度副本；本次没有为这个启动实例或加观测工具。

## 5. 原审计其它明显错误

- 登录必丢包/P0：源码 `SkyboxServerState.begin:35-40` 明确置 `enforced=true`；[现有client.log](../build/render-regression/port-gl33-r2/client.log#L193-L200) 显示登录后 `20:40:12 client accepted revision 1`。这直接反驳原文“登录100%丢首包、必须再敲配置命令”。没有改网络后端。
- GL3.3：`glIsEnabledi/glEnablei/glColorMaski/glGetBooleani_v` 是桌面GL3.0核心，不是GL4.0/ARB_draw_buffers_blend逐buffer blend equation/function。原文把两类API混同。主线已报告真实GL3.3 9checks/GL4.5 18checks通过，本次没有重跑这些GPU检查，也没有改SkyGlScope。
- 对custom pack启动顺序、gbuffer投影混搭等未在本次六项内执行完整生命周期验证，不沿用原报告的确定性评价。尤其已通过的逆VP/同材质逐像素连续性是应尊重的实机证据，不因原报告猜测而重写。

原报告已增加显眼的优先纠错表，旧正文保留为历史审计轨迹，不再作为P0/P1事实。

## 6. 回归与复现

新增 [SkySceneSemanticsRegression](../src/test/java/dev/yg/skyboxblock/client/SkySceneSemanticsRegression.java)：**199,045 checks**，全程无Minecraft/GL初始化、无游戏进程。

- 用ASM从**实际jar/本次编译class**抽取并执行native `packCellData`、生产 `cloudCellColor` / `cloudCellVisible`、`angleRadians`、`flipMoonUv`，不是测试里另写同名算法假装覆盖生产。
- 核对原生moon UV方法调用序列；执行moon UV变换及360个角度中心等价对照。
- 4096个ARGB×16方向组合，验证颜色完整恢复/方向位保持；含负int、负long、高alpha及非白色负样本。
- 颜色alpha线性、texel×.8×配置opacity、31/32 cutout边界、smoothstep的1001点oracle与原linear负样本。
- 月相local条件、dawn快照顺序及实际消费者、reload identity、实际shader关键表达式属于**结构断言**；native prepare/apply用class-file断言核实。没有把这些描述成实机图像/游戏constructor的集成覆盖。

执行命令（读取的classpath文件为UTF8 txt，未用PowerShell读写任何java/md/json/mjs）：

```powershell
$cp=(Get-Content -Raw -Encoding UTF8 build\compile-classpath.txt).Trim()
$cp="$cp;build\classes\java\main;build\classes\java\test"
javac -proc:none -encoding UTF-8 -cp $cp -d build\core-semantics-classes src\main\java\dev\yg\skyboxblock\client\SkySceneState.java src\main\java\dev\yg\skyboxblock\client\SkyVanillaScene.java src\test\java\dev\yg\skyboxblock\client\SkySceneSemanticsRegression.java
java -cp "build\core-semantics-classes;$cp" dev.yg.skyboxblock.client.SkySceneSemanticsRegression
java -cp "build\core-semantics-classes;$cp" dev.yg.skyboxblock.client.SkyTimeRegression
java -cp "build\core-semantics-classes;$cp" dev.yg.skyboxblock.client.SkyWindowCoreRegression
java -cp "build\core-semantics-classes;$cp" dev.yg.skyboxblock.client.SkyEnvironmentRegression
```

结果：独立javac **0错误**；新增semantics **199,045**；既有time **68,012**（inverse phase worst error `0.0020949404715793207 ticks`）；core **808**；environment **283**。JOML在JDK25报告sun.misc.Unsafe弃用warning，未出现回归失败。新增测试尚未接入Gradle任务，按用户要求没有改构建文件；主线可用以上独立命令执行，或后续自行注册任务。

## 7. 改动清单与主线验收面

仅以下生产/测试/文档文件改变：

1. [SkySceneState.java](../src/main/java/dev/yg/skyboxblock/client/SkySceneState.java)：mod360、dawn daily角分离、local moon phase保留。
2. [SkyVanillaScene.java](../src/main/java/dev/yg/skyboxblock/client/SkyVanillaScene.java)：moon-only UV翻转、cloud cell ARGB恢复、dawn读取快照角。
3. [scene.fsh](../src/main/resources/assets/skyboxblock/shaders/isolated/scene.fsh)：cloud smoothstep/退化范围step及旧texel cutout；其它Mode不变。
4. [SkySceneSemanticsRegression.java](../src/test/java/dev/yg/skyboxblock/client/SkySceneSemanticsRegression.java)：新增独立回归。
5. [NEW-CORE-SEMANTICS-AUDIT.md](NEW-CORE-SEMANTICS-AUDIT.md)：新增优先纠错表，撤销错误确定性结论。
6. 本复核报告。

未改 `SkyDistantDepth.java`、`scene.vsh`；也未改用户排除的核心、mixins和工具。build下仅生成独立class与disassembly/resource证据，未复制/替换正在运行的jar。

主线重建后的有意义图像检查：有辨识度的月相纹理方向；彩色/半透明clouds.png以及同尺寸换包；fade中段而非只看端点；local世界日历与gameTime不同情况下切换LOCKED/RUNNING时月相保持；sun_angle跨越半周时太阳变化而dawn方位保持。以上是**待重建后的图像验收**，不是本轮已实测的声明。
