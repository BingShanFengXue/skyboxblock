# 光影包在 Minecraft 26.2 + Iris 1.11.4 下的兼容性调查

> **调查性质**：只读静态调查。未运行 gradle、未编译、未启动游戏、未修改/删除任何被调查文件。
> 所有文本解析走 Python（`zipfile` / `gzip`），未用 PowerShell 读写文本文件。
> **调查时间**：2026-11-02
> **调查者**：子个体（只读静态取证）

---

## 结论

**一句话**：日志里**任何一份都没有出现光影程序编译/链接失败**；光影程序是**加载成功**的。所谓"天空全黑"，在本机现有证据下**不是 skyboxblock 造成的**，也**不能归因为"光影包编译失败"**——它是 26.2 上"Iris 接管天空后如何处理天空层"的语义问题，外加我们模组自己的一条**与光影无关的自绘天空路径**抢了显示。

### 分点结论

1. **没有编译失败（强证据）**。26.2 实例的 **14 份日志**（13 份轮转 + `latest.log`）里，同时对
   `Shader compilation failed` / `Compilation error` / `link error` / `error C####` / `IrisException` /
   `failed to compile` / `Failed to link` / `Invalid shader` 全量检索，**命中数 0**。
   4 个 shaderpack（ITRP 0.8.31 / Complementary r5.9.3 / BSL 10.1.1 / photon 1.4）在**同一次会话**
   （`2026-11-02-6.log.gz`）里被依次加载，每个都只有 `Using shaderpack: ...` + `Creating pipeline for dimension minecraft:overworld`，
   没有任何一条报错。"游戏能进、不崩"与"编译成功后画面异常"是自洽的。

2. **"天空异常"高度可能是「光影正常工作 + 我们模组自绘天空覆盖」的组合，而不是光影坏了**。
   26.2 的 `SkyRendererHost` 走的是 **`自绘天空路径`**，日志原文：
   `[skyboxblock] 26.2 client initialized (self-rendered sky path)`。
   这是我们模组**自己画一张 1920×1080 的天空 target** 再贴到方块面/天空上，
   与光影包是否成功渲染天空**是两条并行的链**。只要自绘路径生效，用户看到的就是我们画的东西。
   **"天空全黑"需要用截图/帧哈希去证明是「光影把天空画黑了」还是「我们抢先画了一层」** ——
   本调查无法从日志单独区分，但这直接决定了责任归属（见「还缺什么」）。

3. **ITRP 对 26.x 的支持是真的，但它不通过任何"版本声明键"表达**。
   `shaders.properties` 里只有 `iris.features.required=CUSTOM_IMAGES SSBO`，
   全包 **187 个 `MC_VERSION` 分支的最高阈值只到 `11605`**（没有任何 `121xx` / `26xxxx` 分支），
   也**没有任何 `MTK_VERSION` / `IRIS_VERSION` 版本门槛键**。
   它的 26.x 适配**全部写死在 GLSL 逻辑里**，靠更新日志宣告：
   `0.8.19 修复 MC 26.x 版本中被 Mojang 与 iris 搞坏的半透明渲染`、
   `0.8.24 新增 MC 26.2 版本支持`、`0.8.27 新增 MC 26.3 版本支持`。
   ⇒ **只看声明键会误判"ITRP 不支持 26.x"，这是错的；但它确实无法被静态声明校验。**

4. **ITRP 三个版本在"版本声明"上完全没有差异**：0.8.30 与 0.8.31 的 `shaders.properties`
   **逐字节相同**（1207 行 / 54580 字符，行集合差集为空）；与 0.8.26（1188 行 / 53694 字符）相比，
   新增的 38 行里**只有 1 行**带版本/兼容关键词（`#if SR_CONFIG_SCHEMA_VERSION > 1`，是超分辨率配置 schema，不是 MC 版本）。
   真正变化的是 0.8.26 → 0.8.30 的 **`lang` 版本戳**（`260724` → `261004` → `261005`）和 changelog 内容。
   ⇒ **升级 0.8.26→0.8.31 不会改变"声明支持范围"，因为这三版根本没有声明支持范围。**

5. **Iris 侧没有任何版本约束问题**。`iris-fabric-1.11.4+mc26.2.jar` 的 `fabric.mod.json`：
   `version: 1.11.4+mc26.2`、`depends: {fabricloader: ">=0.12.3", sodium: ["0.9.x"]}` ——
   `sodium-0.9.2+mc26.2` 满足 `0.9.x`；`breaks` 里只有 `distanthorizons <= 2.1.99`
   （实际 `DistantHorizons-3.3.3-26.2` 远高于此），**没有一个 shaderpack 要求特定 Iris 版本号**：
   4 个包里最严的表述是 photon 的 `Requires Iris 1.6 or above` 和 ITRP 的
   `Only Iris/Oculus 1.7+ is Supported`，1.11.4 都满足。

6. **目前的证据不支持"这是 skyboxblock 模组的问题"这一指控，但也尚未洗清**。
   反证：光影包加载链路本身干净（第 1 点）；1.21.1 对照实例里同一个 ITRP 0.8.31 也一样只留下
   无害的 `warning C7050`（第 5 节），说明 ITRP 在两边都不会"编译失败"。
   未排除：我们模组在 Iris 管线里注册了两个 Iris **不认识**的 program（见"证据 E6"），
   以及自绘天空与 Iris 天空层的绘制顺序/深度归属不明。

---

## 证据

### E1 —— 26.2 的 `iris.properties` 与"这次到底开没开光影"

`D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1\config\iris.properties`
（文件长度 309 B，`LastWriteTime = 2026/11/2 19:12:42`）：

```properties
#This file stores configuration options for Iris, such as the currently active shaderpack
#Mon Nov 02 19:10:54 CST 2026
allowUnknownShaders=false
colorSpace=SRGB
disableUpdateMessage=false
enableDebugOptions=false
enableShaders=true
maxShadowRenderDistance=32
shaderPack=iterationRP Alpha 0.8.31.zip
```

⚠ **重要前置事实（会误导调查）**：
任务书给的 `E:\DSHworkspace\DSHminecraft\skybox-mod-26.2\build\client-262.log` **确实存在**（444 行），
但它对应 **19:10:46 启动的那次会话**（即 `logs\2026-11-02-7.log.gz`，两文件 444 行内容一致），
而那次会话里：

```
[19:10:55] [Render thread/INFO]: Shaders are disabled because enableShaders is set to false in iris.properties
```

⇒ **那一份日志里根本没有光影在跑**，不能用它判断光影兼容性。
`iris.properties` 的 `enableShaders=true` 是 **19:10:54** 写的（注释行时间戳），
即"启动瞬间还是 false、随后被改成 true"，所以该次会话仍是"无光影"。

**真正带光影的那次会话是 `logs\2026-11-02-6.log.gz`**（665 行，首行 `[19:03:21] ... Loading Minecraft 26.2 with Fabric Loader 0.20.1`），
它在**同一次运行内**依次切换了四个光影包（见 E2）。

### E2 —— 四包依次加载的原文（`logs\2026-11-02-6.log.gz`）

| 行号 | 原文 |
|---|---|
| 448 | `[19:04:33] [Render thread/INFO]: Using shaderpack: iterationRP Alpha 0.8.31.zip` |
| 449 | `[19:04:33] [Render thread/INFO]: Creating pipeline for dimension minecraft:overworld` |
| 570 | `[19:06:16] [Render thread/INFO]: Using shaderpack: ComplementaryReimagined_r5.9.3.zip` |
| 571 | `[19:06:16] [Render thread/INFO]: Creating pipeline for dimension minecraft:overworld` |
| 612 | `[19:06:29] [Render thread/INFO]: Using shaderpack: photon_v1.4.zip` |
| 613 | `[19:06:29] [Render thread/INFO]: Creating pipeline for dimension minecraft:overworld` |
| 635 | `[19:06:53] [Render thread/INFO]: Using shaderpack: BSL_v10.1.1.zip` |
| 636 | `[19:06:53] [Render thread/INFO]: Creating pipeline for dimension minecraft:overworld` |

四个包每一次都是"`Using shaderpack` → `Creating pipeline`"，**没有一条接着报错**。

对照：1.21.1 里有光影正常工作时，Iris 会打**链接日志**（哪怕是 warning），例如
`1.21.1-NeoForge_21.1.264\logs\latest.log:273`：

```
[2610?2026 00:09:10.916] [Render thread/WARN] [net.irisshaders.iris.gl.shader.ProgramCreator/]: Program link log for composite63: Fragment info
-------------
0(592) : warning C7050: "clippingBox.boxCenter" might be used before being initialized
0(592) : warning C7050: "clippingBox.boxVec" might be used before being initialized
0(592) : warning C7050: "clippingBox.aabbMin" might be used before being initialized
0(592) : warning C7050: "clippingBox.aabbMax" might be used before being initialized
0(592) : warning C7050: "clippingBox.boxCenterWeight" might be used before being initialized
```

**26.2 的 14 份日志里 `ProgramCreator` / `Program link log` 命中数全部为 0**。
这**不能**推出"26.2 编译失败"：更直接的解释是 Iris 1.11.4 在 26.2 换了日志实现 / 精简了
`ProgramCreator` 的诊断输出（1.11.4 的 jar 里 `net/irisshaders/iris/gl/shader/ProgramCreator.class`
是否仍打印同一句，见"还缺什么"）。**但它是"26.2 侧缺少正面成功证据"的实质缺口**，必须记下。

### E3 —— 编译失败特征全量检索结果：0

对 `26.2-Fabric 0.20.1\logs\` 下 **14 个文件**（`2026-11-01-1..6`、`2026-11-02-1..7`、`latest.log`）用正则
`Shader compilation failed|Compilation error|link(ing)? (error|failed)|error C\d{4}|IrisException|ShaderCompileException|failed to compile|Failed to link|Invalid shader`
检索：

```
2026-11-01-1.log.gz      编译失败特征: 0
2026-11-01-2.log.gz      编译失败特征: 0
2026-11-01-3.log.gz      编译失败特征: 0
2026-11-01-4.log.gz      编译失败特征: 0
2026-11-01-5.log.gz      编译失败特征: 0
2026-11-01-6.log.gz      编译失败特征: 0
2026-11-02-1.log.gz      编译失败特征: 0
2026-11-02-2.log.gz      编译失败特征: 0
2026-11-02-3.log.gz      编译失败特征: 0
2026-11-02-4.log.gz      编译失败特征: 0
2026-11-02-5.log.gz      编译失败特征: 0
2026-11-02-6.log.gz      编译失败特征: 0
2026-11-02-7.log.gz      编译失败特征: 0
latest.log               编译失败特征: 0
```

⇒ **判定：光影程序不是"编译失败"，而是"加载成功但画面异常"**（或根本没轮到它决定画面，见 E5）。

### E4 —— 各包在 26.2 上的 WARN/ERROR 原文（全部与版本兼容无关，都是数据映射噪音）

`logs\2026-11-02-6.log.gz`：

| 包 | 行 | 原文 | 性质 |
|---|---|---|---|
| ITRP | 445–446 | `Unable to resolve shader pack option menu element "PT_TEMP_COORD_OFFSET" defined in shaders.properties` / `... "END_PLANET_WEAK_DIFFUSE" ...` | UI 菜单元素缺失，纯显示问题；1.21.1 同款（`latest.log:187-188`） |
| Complementary | 580–581 | `Error while parsing the block ID map entry for "block.10104":` / `- The block minecraft:stone_slab has no property with the name variant, ignoring!` | 方块映射过期（26.2 删了 `variant`） |
| Complementary | 600–611 | `Failed to parse an Identifier in item.properties for the key item.10056: state properties are currently not supported: calibrated_sculk_sensor:sculk_sensor_phase=inactive`（共 12 行同类） | 物品映射里用了 Iris 不支持的状态属性 |
| photon | 622–623 | `Error while parsing the block ID map entry for "block.10035":` / `- The block minecraft:creaking_heart has no property with the name active, ignoring!` | 方块映射过期 |
| BSL | 631–633 | `Unable to resolve shader pack option menu element "FOG_DISTANCE_LOD" ... "LOD_TAPER_WAVES" ...` | UI 菜单元素缺失 |
| 全部 | 572–577 / 614–619 / 637–642 | `Type is VERTEX` / `Type is FRAGMENT`（各 3 组） | Iris 1.11.4 的**空 WARN**（见下注） |

> 注：`Type is VERTEX` / `Type is FRAGMENT` 是 Iris 1.11.4+mc26.2 在初始化几何着色管线时打出的一对
> **无数值、无上下文的 WARN**，纯噪音，不指向任何错误。

**关键反面证据**：上述四条里，**没有任何一条提到版本不支持、程序缺失、uniform 缺失、uniform 块不匹配**。
换句话说，**从 Iris 的角度看，这四个包在 26.2 上"装上去了"**。

### E5 —— 天空为什么"黑"：我们模组的自绘天空路径在抢画面（这是目前最有力的线索）

同一份 `logs\2026-11-02-6.log.gz`（开启 ITRP 的那段）里，我们模组自己的输出：

```
[19:04:33] [Render thread/INFO]: Using shaderpack: iterationRP Alpha 0.8.31.zip
[19:04:34] [Render thread/WARN]: Type is VERTEX
...
[19:04:35] [Render thread/ERROR]: Missing program skyboxblock:haze_blit in override list. This is not a critical problem, but it could lead to weird rendering.
java.lang.Throwable
	at knot//com.mojang.blaze3d.opengl.GlDevice.handler$bhe000$iris$redirectIrisProgram(GlDevice.java:571)
	at knot//com.mojang.blaze3d.opengl.GlDevice.getOrCompilePipeline(GlDevice.java)
	at knot//com.mojang.blaze3d.opengl.GlRenderPass.setPipeline(GlRenderPass.java:83)
	at knot//com.mojang.blaze3d.systems.RenderPass.setPipeline(RenderPass.java:89)
	at knot//dev.yg.skyboxblock.client.SkyHazeBlit.draw(SkyHazeBlit.java:83)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at knot//dev.yg.skyboxblock.client.SkyLayers.invoke(SkyLayers.java:31)
	at knot//dev.yg.skyboxblock.client.SkyLayers.haze(SkyLayers.java:20)
```

以及（`2026-11-02-3.log.gz:306`、`2026-11-02-4.log.gz:306`、`2026-11-02-6.log.gz:493`）：

```
[05:31:48] [Render thread/ERROR]: Missing program skyboxblock:clouds_cpu in override list. This is not a critical problem, but it could lead to weird rendering.
```

**这条堆栈说明了什么**：Iris 在 26.2 上通过 `GlDevice.handler$bhe000$iris$redirectIrisProgram`
**接管了所有 vanilla `RenderPipeline` 的解析**。当管线 id 不属于任何 shaderpack program 时，
Iris 打印 `Missing program <id> in override list`，然后走 fallback。
我们模组的 `skyboxblock:haze_blit` 与 `skyboxblock:clouds_cpu` **正好落在这个 fallback 分支**——
它们不是光影包的 program，Iris 自然不认识。

⇒ 这**不是光影包的问题**（它只是抱怨我们注册了它不认识的 program），
也**不是编译失败**（日志已明说 `This is not a critical problem`）。
但它**证明我们模组确实在 Iris 的管线接管范围内画东西**，而这正是"我们看到自绘原版天空"的机制来源。

启动日志里我们自己也写了（`26.2-Fabric 0.20.1\logs\latest.log` 与 `build\client-262.log` 同文）：

```
[19:10:54] [Render thread/INFO]: [skyboxblock] 26.2 client initialized (self-rendered sky path)
[19:10:54] [Render thread/INFO]: [skyboxblock] 26.2 环境：Iris=true DistantHorizons=true（DH 深度注入在自绘天空路径下不可用）
[19:11:06] [Render thread/INFO]: [skyboxblock] sky target created 1920x1080 (rendererOwnsTarget=true)
[19:11:06] [Render thread/INFO]: [skyboxblock] sky frame #1 1920x1080 skybox=OVERWORLD skyColor=#0 sun=0.0 moon=0.0 star=0.0 dawn=#0
```

⚠ **注意最后一行**：`skyColor=#0 sun=0.0 moon=0.0 star=0.0 dawn=#0`——
**我们第一帧自绘天空的输出本身就是全 0（黑）**，而此时它还没吃到真实环境数据；
第 2/3 帧才变成 `skyColor=#ff78a7ff`。这说明"天空全黑"至少在**我们自己的路径里**有可复现的成因，
而且**与光影包无关**（这次会话 `enableShaders=false`，光影根本没开）。

### E6 —— 1.21.1 对照：ITRP 在那边同样"没有编译失败"，但也没有"干净成功"

**比对样本**：`1.21.1-NeoForge_21.1.264\logs\latest.log`（Iris **1.8.14-beta.1+mc1.21.1**，Sodium 0.8.13+mc1.21.1）：

```
178| [skyboxblock] independent vanilla simulation initialized (iris=true(pack=false),ip=false,dh=false)
190| [2610?2026 00:09:06.611] [Render thread/INFO] [Iris/]: Using shaderpack: iterationRP Alpha 0.8.31.zip
272| [2610?2026 00:09:09.873] [Render thread/INFO] [Iris/]: Creating pipeline for dimension minecraft:overworld
273| [2610?2026 00:09:10.916] [Render thread/WARN] [net.irisshaders.iris.gl.shader.ProgramCreator/]: Program link log for composite63: Fragment info
274| -------------
275| 0(592) : warning C7050: "clippingBox.boxCenter" might be used before being initialized
...
```

**对照结论（务必按这个口径读）**：
- ✅ **ITRP 0.8.31 确实在 1.21.1 上跑起来了**：`Using shaderpack` → `Creating pipeline` → 之后有玩家进服
  （`BingShanFengXue logged in with entity id 1`）、JEI 加载、配方/进度加载，**会话持续运行到 00:13 之后**。
- ✅ **1.21.1 侧同样是 0 条编译失败**（该实例 `logs\` 全部 11 个文件，`Program link log` 只有 3 条 `C7050` warning）。
- ⚠ **1.21.1 侧唯一的光影报错是 `c7050` 未初始化告警**，是 `composite63` 片元着色器的**warning 不是 error**，且**没有**影响运行。
- ❌ **不能说"1.21.1 侧完全无报错"**：它有这条 warning。也不能说"26.2 侧有同类 warning"：
  26.2 侧一条 `Program link log` 都没有（见 E2 末注，属于诊断输出缺口，不是错误）。
- ❌ **1.21.1 那次会话里 `skyboxblock` 的状态是 `iris=true(pack=false)`**——
  即模组启动时**判定光影未启用**（当时 `pack=false`），随后 Iris 才加载了 ITRP。
  **这提示"模组对 Iris 状态的判定与 Iris 实际状态存在时序错位"**，1.21.1 与 26.2 都有，属于待查项。

**Rapid Optimization 实例（1.21.1 Fabric，Iris 1.8.1 / 1.8.14-beta.1）** 也全部符合：大量
`Using shaderpack: iterationRP Alpha 0.8.26 hotfix.zip` / `0.8.30` / `0.8.31` / `photon_v1.4` / `BSL_v10.1.1` /
`ComplementaryReimagined_r5.9.3`，以及中文 `[System] [CHAT] 光影包已切换到 iterationRP Alpha 0.8.31.zip！`，
**编译失败特征 0 条**。

### E7 —— Iris jar 的依赖与版本（`mods\iris-fabric-1.11.4+mc26.2.jar` 内 `fabric.mod.json`）

```
id: iris
name: Iris
version: 1.11.4+mc26.2
schemaVersion: 1
environment: client
depends: {"fabricloader": ">=0.12.3", "sodium": ["0.9.x"]}
breaks:  {"embeddium": "*", "iris-explorer": "*", "originsenhanced": "<=1.3.2", "colormatic": "<=3.0.0",
          "bedrockwaters": "<=1.6.0", "batchedentityrendering": "*", "canvas": "*", "optifabric": "*",
          "worldeditcui": "<1.18.1", "physicsmod": "<=3.0.13", "distanthorizons": "<=2.1.99",
          "immersive_portals": "<=1.4.2"}
authors: ['coderbot', 'IMS212', 'Justsnoopy30', 'FoundationGames']
```

- `sodium: ["0.9.x"]` ← 实例里是 `sodium-fabric-0.9.2+mc26.2`，**满足**。
- `distanthorizons: "<=2.1.99"` ← 实例里是 `DistantHorizons-3.3.3-26.2`，**不在 breaks 范围**。
- jar 内含 `io.github.douira.glsl-transformer 3.0.0-pre3`（日志 `|-- io_github_douira_glsl-transformer 3.0.0-pre3`）。
- **jar 里没有任何"支持哪些 shaderpack 版本"的约束**；Iris 是"包声明能力，Iris 尽力满足"的模型。
- ⚠ **实例现状**：`mods\` 目录当前是
  `DistantHorizons-3.3.3-26.2-fabric-neoforge.jar`、`fabric-api-0.161.0+26.2.jar`、
  `iris-fabric-1.11.4+mc26.2.jar`、`[钠] sodium-fabric-0.9.2+mc26.2.jar`、**`_skyboxblock.disabled.jar`**。
  **skyboxblock 的 jar 现在处于禁用状态（文件被改名为 `.disabled`）**——
  任务书里写的 `skyboxblock-1.0.6-test.1-port.1-mc26.2.jar` 已不存在。
  本次调查的日志（`2026-11-02-6/-7`）都是**禁用之前**跑出来的（那时它还叫正常名，日志里有 `skyboxblock 1.0.6-test.1-port.1` 与 `[skyboxblock]` 行）。
  ⇒ **"禁用 skyboxblock 后再开光影，天空是否恢复正常"这个决定性实验，目前还没有日志**。

### E8 —— 各包 `MC_VERSION` 分支的最高阈值（静态代码证明）

对每个包内所有 `.glsl/.vsh/.fsh/.gsh/.csh/.properties/.lang/.txt/.json` 扫描 `MC_VERSION (>=|<=|>|<|==) N`，
取出现的最高阈值：

| 包 | 最高 `MC_VERSION` 阈值 | 出现的所有阈值 | 是否显式处理 26.x |
|---|---|---|---|
| **iterationRP Alpha 0.8.31** | **11605** | 11605:4, 11604:7, 11600:1, 11500:7, 11400:1, 11300:7 | ❌ **完全没有 `121xx` / `26xxxx` 分支** |
| ComplementaryReimagined_r5.9.3 | **260200** | 260200:2, 260100:1, 12111:2, 12109:5, 12106:3, 12105:2, 12102:2, 12000:1, 11800:1, 11700:2, 11605:3, 11500:2, 11300:26 | ✅ 有 `#if MC_VERSION >= 260200`（`shaders/lib/common.glsl`）、`>= 260100`（`shaders/program/gbuffers_water.glsl`） |
| photon_v1.4 | **260300** | 260300:3, 260100:4, 12111:5, 12102:1, 11700:1, 11300:3 | ✅ 有 `#if MC_VERSION >= 260300`（`shaders/shaders.properties`、`gbuffers_damagedblock.fsh`）、`gbuffers_skytextured.vsh/.fsh` 里的 `>= 260100` |
| BSL_v10.1.1 | **12109** | 12109:1, 12106:2, 11800:11, 11605:3, 11500:4, 11300:9, 10900:1, 10800:5, 10710:2 | ⚠ 最高 `12109`，**没有 26.x 分支** |

**读法**：ITRP 的 `MC_VERSION` 分支结构**停留在 1.16 时代**，所有 1.17～26.x 都走"最新那一条"。
这在 1.21.1 上能正常工作，因为 ITRP 靠 Iris 的实际行为而非编译期分支适配。
**它不构成"26.2 不支持"的证据**，但意味着**ITRP 无法从代码上区分 1.21 与 26.x**——
这也是它把 26.2 适配做成"修被 Mojang 与 Iris 搞坏的半透明"这种**运行时修补**的原因（见 E9）。

### E9 —— ITRP 的 26.x 支持全部写在 changelog 里（`iterationRP Alpha 0.8.31.zip!ChangeLog_CN.txt`）

原文摘录（中文原样）：

```
0.8.31
修复开启"Super Resolution 超分辨率"模组"DLSS-RR光线重建"算法时，景深出现大量亮点
    -⚠️需要使用"DLSS-RR光线重建"的F预设
修复开启"Super Resolution 超分辨率"模组"DLSS-RR光线重建"算法时，屏幕空间反射中天空渲染消失

0.8.30
新增"Super Resolution 超分辨率"模组的实体运动矢量支持
    -⚠️需求：MC版本 26.1+，SR模组版本 0.9.2-alpha.1 及更新
...
0.8.27
新增 MC 26.3 版本支持
    -新增杨树、混凝土、羊毛及其变体和麦秆床的方块体素编码
...
0.8.24
新增 MC 26.2 版本支持
    -新增朱砂及硫磺相关方块体素编码
    -修复硫方怪半透明部分缺失
...
0.8.21
修复 MC 26.x 版本中粒子遮挡错误
...
0.8.19
修复 MC 26.x 版本中被 Mojang 与 iris 搞坏的半透明渲染
    -修复第三人称玩家渲染缺失
    -修复玩家头颅渲染缺失
    -修复实体半透明部分缺失(如史莱姆、闪电苦力怕等)
    -修复实体眼部发光缺失(如蜘蛛、末影人等)
    -修复旗帜图案缺失
    -修复普通玻璃边框半透明
    -修复告示牌文字缺失
    -修复半透明掉落物渲染缺失
...
0.7.26
新增对1.21.10的支持
```

**两条关键读法**：
1. `0.8.19` 明确写了「**被 Mojang 与 iris 搞坏的半透明渲染**」——**作者自己承认 26.x 上有 Iris 侧回归**。
   但它修的是**半透明**，changelog 里**从未提到过"天空"**，所以**不能拿这条证明"天空黑是已知 Iris 问题"**。
2. `0.8.24` 说"新增 MC 26.2 版本支持"，`0.8.27` 说"新增 MC 26.3 版本支持"，
   且 0.8.30 里出现 `MC版本 26.1+` 的硬需求 —— **26.2 是 ITRP 明文支持的版本**。
   ⇒ **"ITRP 不支持 26.2"这个假设被推翻**。

### E10 —— 三版 ITRP 的声明差异（逐项对照结果）

| 对照项 | 0.8.26 hotfix | 0.8.30 | 0.8.31 |
|---|---|---|---|
| `shaders.properties` 行数 / 字符数 | 1188 / 53694 | **1207 / 54580** | **1207 / 54580** |
| `iris.features.required` | `CUSTOM_IMAGES SSBO` | `CUSTOM_IMAGES SSBO` | `CUSTOM_IMAGES SSBO` |
| `iris.features.optional` | 无 | 无 | 无 |
| `#define MC_VERSION` 自设 | 无 | 无 | 无 |
| `IRIS_VERSION` 宏引用 | 0 次 | 0 次 | 0 次 |
| `MTK_VERSION` 类版本门槛 | 无 | 无 | 无 |
| `pack.mcmeta` | **缺失**（zip 内无此文件） | **缺失** | **缺失** |
| `lang` 版本戳 `value.iterationRP_VERSION.BT` | `260724` | `261004` | `261005` |
| `lang` 版本名 `value.iterationRP_VERSION.AT` | `Alpha 0.8.26 hotfix` | `Alpha 0.8.30` | `Alpha 0.8.31` |
| Iris 要求（lang 文案） | `Only Iris/Oculus 1.7+ is Supported` | 同左 | 同左 |
| 最高 `MC_VERSION` 阈值 | 11605 | 11605 | 11605 |
| `shaders.properties` 行集合差集 | — | vs 0.8.26：+38 / −19 | vs 0.8.30：**+0 / −0（逐字节相同）** |

`0.8.26 → 0.8.30` 新增的 38 行里，**唯一带版本/兼容关键词的**是：
```
#if SR_CONFIG_SCHEMA_VERSION > 1
```
（超分辨率配置 schema，与 MC 版本无关）。其余是 `program.composite44/46/47/48.enabled` 的
`false` 前置分支和 `composite29` 的重排。

⇒ **结论：三个 ITRP 版本在"版本声明"上没有任何实质差异。新版本并没有"更新对 26.x 的声明"，因为从来没有过这种声明。**

### E11 —— 四包对 Iris 版本的要求（唯一出现的两类表述）

| 包 | 出处 | 原文 |
|---|---|---|
| iterationRP 0.8.31 | `shaders/lang/en_us.lang` | `option.iterationRP_INFO.comment=Only Iris/Oculus 1.7+ is Supported. English Comments are Not Added` |
| Complementary r5.9.3 | `shaders/shaders.properties` | `# Check for block emission attribute to detect Iris 1.7 and above because the ref-res causes an error in 1.6.10 and below` |
| photon v1.4 | `shaders/lang/en_US.lang` | `option.COLORED_LIGHTS.comment = Variable-color block lighting using flood fill algorithm. Requires Iris 1.6 or above` |
| BSL 10.1.1 | — | **没有任何 Iris 版本要求**，只有 `iris.features.optional=CUSTOM_IMAGES` |

实例是 **Iris 1.11.4**，**全部满足**上述"1.7+ / 1.6+"要求。

各包的 `iris.features.*` 声明：

| 包 | 声明 |
|---|---|
| iterationRP 0.8.31 | `iris.features.required=CUSTOM_IMAGES SSBO` |
| Complementary r5.9.3 | `iris.features.optional = CUSTOM_IMAGES SSBO BLOCK_EMISSION_ATTRIBUTE FADE_VARIABLE ENTITY_TRANSLUCENT` |
| photon v1.4 | `iris.features.optional = CUSTOM_IMAGES ENTITY_TRANSLUCENT` |
| BSL 10.1.1 | `iris.features.optional=CUSTOM_IMAGES` |

全部是**基础能力**（`CUSTOM_IMAGES` / `SSBO` / `ENTITY_TRANSLUCENT` 都是 Iris 1.2+ 就有的）。
**没有任何一个包声明 `required` 了 26.2 新增、而 Iris 1.11.4 未实现的能力。**

---

## 各光影包版本声明对照表

### 表 A：包基本信息 + 自称支持的版本

| 项目 | iterationRP 0.8.31 | ComplementaryReimagined r5.9.3 | BSL v10.1.1 | photon v1.4 |
|---|---|---|---|---|
| 文件 | `iterationRP Alpha 0.8.31.zip` (21,891,360 B) | `ComplementaryReimagined_r5.9.3.zip` (553,397 B) | `BSL_v10.1.1.zip` (1,125,052 B) | `photon_v1.4.zip` (3,857,333 B) |
| `pack.mcmeta` | **无** | **无**（只有 `shaders/pack.json`，只含 `shaderDescription`） | **无** | **无** |
| 显式声明支持的 MC 版本 | **无声明键**；changelog 称 `新增 MC 26.2 版本支持`(0.8.24)、`MC 26.3 版本支持`(0.8.27) | **无声明键**；代码分支到 `MC_VERSION >= 260200` | **无声明键**；代码分支只到 `MC_VERSION >= 12109` | **无声明键**；代码分支到 `MC_VERSION >= 260300` |
| `iris.features.required` | `CUSTOM_IMAGES SSBO` | （无 required） | （无 required） | （无 required） |
| `iris.features.optional` | （无） | `CUSTOM_IMAGES SSBO BLOCK_EMISSION_ATTRIBUTE FADE_VARIABLE ENTITY_TRANSLUCENT` | `CUSTOM_IMAGES` | `CUSTOM_IMAGES ENTITY_TRANSLUCENT` |
| 要求的 Iris 版本 | `Only Iris/Oculus 1.7+ is Supported` | 隐含 ≥1.7（注释：靠 `BLOCK_EMISSION_ATTRIBUTE` 检测） | 无要求 | `Requires Iris 1.6 or above`（仅彩色光照项） |
| 最高 `MC_VERSION` 分支 | **11605** | **260200** | **12109** | **260300** |
| 26.2 上的加载结果（日志） | 加载成功（`Using shaderpack` → `Creating pipeline`），无编译错误 | 同左 | 同左 | 同左 |
| 26.2 上的唯一 WARN | 2 条 UI 菜单项缺失 | 1 条 `stone_slab:variant` 映射失效 + 12 条 `item.properties` 状态属性不支持 | 2 条 UI 菜单项缺失 | 1 条 `creaking_heart:active` 映射失效 |

### 表 B：ITRP 三个版本（0.8.26 / 0.8.30 / 0.8.31）声明差异

| 项目 | 0.8.26 hotfix | 0.8.30 | 0.8.31 | 差异 |
|---|---|---|---|---|
| `value.iterationRP_VERSION.AT` | `Alpha 0.8.26 hotfix` | `Alpha 0.8.30` | `Alpha 0.8.31` | 仅版本名 |
| `value.iterationRP_VERSION.BT` | `260724` | `261004` | `261005` | 仅构建戳 |
| `iris.features.required` | `CUSTOM_IMAGES SSBO` | 同 | 同 | **无差异** |
| Iris 版本要求文案 | `Only Iris/Oculus 1.7+ is Supported` | 同 | 同 | **无差异** |
| 最高 `MC_VERSION` 分支 | `11605` | `11605` | `11605` | **无差异** |
| `MC_VERSION` 阈值集合 | `{11300,11400,11500,11600,11604,11605}` | 同 | 同 | **无差异** |
| 是否新增 26.x 契约 | — | **否** | **否** | **无差异** |
| `shaders.properties` 行/字符 | 1188 / 53694 | 1207 / 54580 | 1207 / 54580 | 0.8.30 = 0.8.31（**逐字节**） |
| changelog 相关条目 | `修复AMD显卡兼容性` | `新增"Super Resolution"实体运动矢量支持`（`需求：MC版本 26.1+`）、`新增 DLSS-RR` | 仅修 SR 的 DLSS-RR 两个 bug | — |

**关于问题 2 的明确回答**：**新版本（0.8.30/0.8.31）并没有"明确更新对 26.x 的支持声明"，因为在任何版本里都不存在这种声明。**
0.8.30 与 0.8.31 的 `shaders.properties` **完全相同**；26.x 支持是 0.8.19～0.8.27 期间一次性做完的运行时修补，
到 0.8.30 已经在吃老本。**所以"换 0.8.26/0.8.30/0.8.31 试"对 26.2 的天空问题不会有区别。**

---

## 还缺什么

以下缺口按"对结论的影响度"排序。

### 🔴 关键缺口 1：缺少"决定性对照实验"的日志

**缺的实验**：`mods\` 里 skyboxblock 现在是 `_skyboxblock.disabled.jar`（已禁用）。
需要在**禁用 skyboxblock** 的前提下，开 ITRP 进 26.2 看天空是否正常。
- 若**正常** ⇒ 天空异常的主因是我们模组（自绘路径 / Iris 管线抢占），光影清白。
- 若**仍然黑** ⇒ 主因在 Iris 26.2 的天空管线 / ITRP，与模组无关。
**目前 26.2 实例的所有日志都产生于禁用之前，所以这个判据空缺。**
另外注意：`2026-11-02-7.log.gz`（= `build\client-262.log`）虽然 `enableShaders=false`，但
**模组是加载着的**（日志有 `[skyboxblock] sky frame #1 ... skyColor=#0`），
**它就是"无光影 + 有模组"的对照组**——可惜没有对应的截图/帧哈希来判断那时的天空是否也黑。

### 🔴 关键缺口 2：缺少 `enableShaders=false` 与 `enableShaders=true` 的**同场景帧对比**

现有材料只有日志，没有像素级对照：
- `build\sky-frame.png` / `build\world-frame.png`（由 19:11 那次**无光影**会话 dump）需要与
  "开 ITRP / 开 Complementary 的同机位帧"做逐字节或直方图比对。
- 日志里唯一的量化指标是 `sky frame #N ... skyColor=#...`。无光影时第 1 帧是 `#0`（全黑），
  第 2/3 帧是 `#ff78a7ff`。**开光影时的 `skyColor` 序列没有记录**（`2026-11-02-6.log.gz` 里
  ITRP 加载前后的 `sky frame` 行存在与否需要逐帧核对，本调查未逐帧对齐渲染帧编号）。

### 🟠 缺口 3：Iris 1.11.4 在 26.2 上是否仍然打印 `Program link log`

若 `net/irisshaders/iris/gl/shader/ProgramCreator` 在 1.11.4 里已不再打 `Program link log`，
那"26.2 侧 0 条链接日志"就只是**诊断输出缺失**，不能作为"无告警"的正面证据。
**需要**：反编译或 `javap -c` 该 class，确认它是否仍调用 `System.out` / logger 打这句。
（本次为只读调查，未做反编译。）

### 🟠 缺口 4：`GL_KHR_debug` / `GL_DEBUG_OUTPUT` 的 OpenGL 错误日志

26.2 侧有：
```
[19:10:55] [Render thread/INFO]: Enabling GL_DEBUG_OUTPUT_SYNCHRONOUS to force the NVIDIA driver to disable threaded command submission
```
但 `latest.log` 里**没有任何 GL 错误回调的内容**。
DH 还专门说 `Iris detected, ... OpenGL error logging won't be sent in the chat due to Iris throwing known (harmless) OpenGL errors`。
**需要**：用 GL debug callback（或 RenderDoc / apitrace）抓一次开 ITRP 的帧，
看 `gbuffers_skybasic` / `gbuffers_skytextured` 这两个 pass 是否真的被 Iris 画了、画进了哪个 framebuffer。

### 🟡 缺口 5：`skyboxblock` 与 Iris 天空层的绘制顺序 / 深度归属

`Missing program skyboxblock:haze_blit|clouds_cpu in override list`（E5）说明我们的管线落在 Iris 的
fallback 分支。但**谁先画、谁覆盖谁**在日志里看不出来。
**需要**：在 `SkyRendererHost` / `SkyLayers` 里加一次性日志（frame 内 pass 顺序 + 当前 Iris 激活的
`Pipeline` id），或者用 RenderDoc 抓一帧看 draw call 顺序。
（本次为只读调查，按要求未改任何代码。）

### 🟡 缺口 6："天空全黑"到底指哪一层——**话语需要收敛**

任务描述是"天空全黑/异常；在我们模组方块里显示的是我们自绘的原版天空"。这其实是**两个现象**：
1. **世界天空（抬头看到的天）黑**
2. **方块面里显示的是我们自绘的原版天空**

第 2 条**本来就是设计行为**（`shaderMode 0` 的语义就是"方块面 = 当前维度真实天空"，见
`docs\STATUS-26.2.md` 第 598 行：`| shaderMode 0（原版） | 方块面 = 当前维度真实天空 | ✅ 自绘天空帧（屏幕投影采样） |`）。
**如果用户对第 2 条的预期是"方块面应该显示光影渲染后的天空"，那这是需求分歧，不是兼容 bug。**
第 1 条才是需要查的真问题。**需要**：明确"全黑"具体指哪个视角、哪张截图。

### 🟢 缺口 7（低优先）：26.2 客户端是不是真的把 ITRP 的 `gbuffers_skybasic` 起了

ITRP 包内**确实有** `shaders/gbuffers_skybasic.fsh/.vsh`、`shaders/gbuffers_skytextured.fsh/.vsh`、
`shaders/world-1/...skybasic|skytextured...`（共 18 个 sky 相关文件）。
它**没有**因此报"找不到程序"，但也没有任何一条日志证明这些 pass **执行了**。
**需要**：Iris 的 debug 输出（`enableDebugOptions`）或 renderdoc 的 pipeline 列表。

---

## 附：本次调查用到的只读脚本（未修改任何被调查文件）

均位于 `E:\DSHworkspace\DSHminecraft\skybox-mod-26.2\tools\`：

| 脚本 | 作用 |
|---|---|
| `_scratch_shader_probe.py` | 首轮广扫（遍历 zip 内所有 `*.properties`/`*.mcmeta`/`*.lang`/`*.json`） |
| `_scratch_shader_probe2.py` | 只抽版本/Iris 能力声明键；列出 1.21.1 与 Rapid Optimization 的 `shaderpacks/` |
| `_scratch_shader_probe3.py` | 过滤 UI 噪音后的 `shaders.properties` 有效行 + ITRP 三版 diff |
| `_scratch_shader_probe4.py` | 各包 `MC_VERSION` 阈值分布与最高阈值 |
| `_scratch_shader_probe5.py` | ITRP changelog 全文 + 全包 `26./1.21` 文本行 + Iris 特性使用统计 |
| `_scratch_iris_probe.py` | `iris-fabric-1.11.4+mc26.2.jar` 的 `fabric.mod.json` |
| `_scratch_log_probe*.py`（共 9 个） | 26.2 全部日志的 shader/Iris 检索、`Program link log` 检索、1.21.1 与 Rapid 实例对照、`iris.properties` 与 log4j 配置对比 |

**被调查但未改动的文件**（只读）：4 个 shaderpack zip、`iris-fabric-1.11.4+mc26.2.jar`、
`config\iris.properties`、`versions\26.2-Fabric 0.20.1\logs\` 下 14 个日志、
`1.21.1-NeoForge_21.1.264\logs\` 下 11 个日志、`Rapid Optimization\logs\` 下全部日志、
`build\client-262.log`。

---

## 附：主线补做的决定性 A/B 对照（2026-11-02 19:10–19:20，实机）

子个体指出"缺少禁用模组 + 开 ITRP 的对照日志"——这一条主线补做了。**方法**：把模组改名成
`skyboxblock.jar.disabled`（注意：`_xxx.jar` 仍会被 Fabric 加载，必须让文件名不以 `.jar` 结尾），
`iris.properties` 保持 `enableShaders=true`，`shaderPack=iterationRP Alpha 0.8.31.zip`，同机位进 `flat262`，
用 `tools/capture-window.py`（PrintWindow）抓窗口。

| 条件 | shaderpack 是否加载 | 世界天空 | 截图 |
|---|---|---|---|
| 无我们的模组 + ITRP | ✅ `Using shaderpack: iterationRP Alpha 0.8.31.zip` | **正常**（ITRP 的亮雾霾天空 + 云） | `screenshots/conditions/itrp-nomod-real2.png` |
| **有**我们的模组 + ITRP | ✅ 同样 `Using shaderpack: ...` | **全黑**（只有我们的方块面显示自绘蓝天） | `screenshots/conditions/itrp-withmod-real.png` |
| **有**我们的模组 + Complementary r5.9.3 | ✅ | **正常**（光影云可见） | `screenshots/conditions/complementary-withmod-real.png` |

**修正结论（以实测为准）**：

1. **ITRP 本身在 26.2 上是能正常渲染天空的**（无模组时），"光影包不兼容 26.2"这个假设**不成立**；
2. **"ITRP + 我们的模组 → 天空全黑"是可复现的**，而 **Complementary + 我们的模组正常** ⇒ 是**特定光影 + 我们模组**的交互问题；
3. 与子个体查到的机制吻合：Iris 在 26.2 用 `GlDevice.handler$bhe000$iris$redirectIrisProgram` 接管所有 vanilla
   `RenderPipeline`，我们的 `skyboxblock:haze_blit` / `skyboxblock:clouds_cpu` 走 fallback
   （`Missing program ... in override list. This is not a critical problem, but it could lead to weird rendering.`），
   而自绘天空本身也是**在 Iris 之外**调用 `SkyRenderer`。

**下一步该验的两件事**（未完成，被用户叫停）：

- 有模组 + ITRP + `SKYBOX_CLOUDS=0 SKYBOX_HAZE_BLIT=0`（关掉两条自建 pass）→ 天空是否恢复：
  能区分"是自建 pass 污染了 Iris 状态"还是"`SkyRenderer` 帧外调用本身"；
- 若能定位到具体环节，最小修法是：**检测到 Iris 光影启用时，跳过自绘天空与自建 pass**（安全降级），
  或在自绘前后完整保存/恢复 GL 状态（program、VAO、framebuffer、viewport、blend、depth）。
