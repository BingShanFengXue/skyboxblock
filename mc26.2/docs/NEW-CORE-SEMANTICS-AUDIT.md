# 新核心语义只读审计：SkySceneState / SkyVanillaScene / SkyDistantDepth / ModCompat

> **2026-11-02 精准复核纠错（优先于下面原始报告）**
>
> 本报告原始正文含推导错误、数学错误和把 API 代际变化当缺口的判断。**下方原始 P0/P1 和“确定”口吻不代表已证实事实**；原文保留作审计轨迹，采用结论请查 [CORE-SEMANTICS-REVIEW.md](CORE-SEMANTICS-REVIEW.md)。本次纠错直接读取本机 26.2 / 1.21.1 jar 字节码和实际基线 shader；未启动游戏、未跑 Gradle、未发夹具控制请求。
>
> | 原文位置 | 纠正后的事实 |
> |---|---|
> | A、§2.1、§3“首包”、§8.5、§9#1 | **撤销登录必丢包/P0**。本报告自己在 §2.1 第一项就抄到了 `begin()` 的 `enforced=true`，后续又假定它为 false，逻辑自相矛盾。当前 `SkyboxServerState.java:35-40` 明确置 true；`build/render-regression/port-gl33-r2/client.log:193-200` 显示登录后 `20:40:12 client accepted revision 1`，无须服主再次写配置。 |
> | §2.2 LOCKED“逐tick跳动” | LOCKED 返回常数，**完全锁定**，不是逐tick跳动。RUNNING 才随 gameTick/partial 推进。 |
> | B、§4.2-1、§8.3#28、§9#5 | **撤销“sun_angle 必须联动 moon”**。1.21.1 `VanillaSkyScene.java:83-97` 先构造不含 override 的 `solarMvp`，之后仅给太阳设置另一个 MVP，月亮默认仍用原 `solarMvp`。显式 sun/moon/star override 各自独立；本次保持独立。旧月亮的 `angle-180` 配合 **y=-100**，26.2 原生月亮改成 **y=+100** 与独立 moon angle，两者方位等价，直接补 -180 会画反。超大有限角度的 mod360 精度缺口才是真问题，已补。 |
> | C、§4.1#6、§5.3、§9#2 | **撤销“alpha .7 和1都变 .8”**。`a*=.8` 是线性乘法：1→.8，179/255→.5615686，0→0。1.21.1 原生 `buildClouds` 顶点 alpha 就是 .8，自定义 shader 最后再乘 `CloudOpacity`，与新核心配置 alpha 的 .8 乘数一致。此次没有删除该乘数。 |
> | §5.2、§9#4 | **撤销“旧柱面/新球面”和“旧混雾色”**。真实旧 `vanilla_cloud.vsh:20` 是 `length(viewPosition.xyz)`，旧 fsh:16-18 是 **smoothstep 后只淡 alpha**；两代均球面。已修的是新核心原先的 **linear→smoothstep**，没有改距离形状。 |
> | §5.4、§9#3 | `CloudRenderer.TextureData` **有完整 ARGB**：本机 `CloudRenderer.packCellData` 字节码是 `(long)argb << 4 | 四方向位`。`CloudFaces` 是 GPU 编码缓冲，不等于 `TextureData`；26.2 原生送GPU的3字节face记录确实不带颜色，但这不意味着CPU数据丢了颜色。已用 `(int)(cell>>4)` 恢复资源包颜色/alpha，并保留旧 texture×.8 的 .1 cutout。 |
> | §5.4、§9#11 | **撤销 record.equals 导致换包不重建**。`data != cloudData` 比较引用身份，与 equals 完全无关；native `prepare` 每次成功均 `new TextureData`，`apply` 替换 texture 字段，现有缓存已正确失效。 |
> | E、§6.2、§9#12 | **撤销“有多视图就必丢其它视图”的确定性结论**。必须先证明 capture 与 configure 的实际交错。顺序 `capture(A)→configure(A)→capture(B)→configure(B)` 使用单缓存即可。`configure` 查询的是 DH 当前共享深度纹理；仅缓存旧矩阵不保留像素会造成错配。本次未获得DH+递归门户的真实交错证据，故保持实现，不引入猜测性多键缓存。 |
> | §7、§9#16 | **撤销 GL3.3 记录路径缺 ARB fallback 的判断**。`glIsEnabledi/glEnablei/glColorMaski/glGetBooleani_v` 是桌面 OpenGL 3.0 核心入口；与 GL4.0 / ARB_draw_buffers_blend 的逐buffer blend equation/function 不同。主线真实 GL3.3 9checks 已通过；此次未改 SkyGlScope。 |
> | §4.2-2、§9#7 | 全透明 dawn 是否提交几何没有视觉差异，不列修复问题。旧 dawn 的 `.4/.99/.3/.7/.2` 数学可由本机 `DimensionSpecialEffects.getSunriseColor` 字节码核实，不应标成“数学无法静态判定”。 |
>
> 额外确认并修复：`sun_angle` override 不应改变晨昏带翻转方向（旧版使用独立 daily phase）；local 的 LOCKED/RUNNING 应保留原生 moon phase，而不是无条件按 gameTime 算月相；本机 `SkyRenderer.buildMoonPhases` 证实月亮 UV 为 `(1,1),(0,1),(0,0),(1,0)`，新核心原先误用太阳 UV，现已只对月亮翻转两轴。详见复核报告，回归 **199,045 checks + 68,012 clock assertions**；lunar来源/dawn/reload 的检查明确属于结构断言，月亮 UV/pack颜色/angle 则执行真实字节码，不冒充实机图像测试。

---

## 原始报告（历史记录，冲突处以上述纠错为准）

> **性质**：只读审计。**未改任何源码 / 测试 / 脚本**，**未跑 Gradle**，**未启动游戏**，**未向当前夹具
> （`port-gl33-r2`，100×57，GL3.3）发任何命令**。本文件是本次唯一写入物（新建）。
> **时间**：2026-11-02 20:5x–21:0x（CST）。
> **对象**：`skybox-mod-26.2/src/main/java/dev/yg/skyboxblock/**`（26.2 新核心）对照
> `skybox-mod/src/main/java/dev/yg/skyboxblock/**`（1.21.1 真实主线）。
>
> **证据约定**（本次严格执行"不把文件名/日志/文档宣称当行为证据"）
> - `【源码】` = 直接读到该行代码，本文给出 `文件:行`。
> - `【同一性】` = 两棵树同名文件经整文件文本比对确认**逐字节相同**（列表见 §0.2），一次证明、全文复用。
> - `【需实测】` = 代码能读到、但**运行期取值依赖 26.2 `LevelRenderState` 的字段语义**，本次不可执行验证。
> - `【文档声称】` = 只在项目文档/日志里出现，本次**未复现**，一律不作为结论依据；需要时会点明冲突。
> - 用户点名的"已有 GL3.3 bug 主线已修（`SkyGlScope` ARB 入口分支）"**本文不重写**；仅在 §7 说明审计时新发现的
>   **第二条** GL 路径（`glColorMaski/glEnablei/glGetBooleani_v`）与之一致性，口径是"同一个修复模式，另一批函数"。

---

## 0. 结论摘要（先看这 6 条）

| # | 结论 | 级 | 依据 |
|---|---|---|---|
| **A** | **服务端下发的 settings 在登录阶段 100% 被客户端丢弃**，逐玩家 `environment` / `clouds` / `cloudHeight` / `clock` 的"首次生效"路径断掉 | **P0** | §2.1 |
| **B** | 15 个 `SkyEnvironment` 属性**全部被消费**（逐条查表见 §4），但 3 处语义与 1.21.1 不一致：`sun_angle/moon_angle/star_angle` 少了 360° 归一、`sunrise_sunset_color` 少了一层 alpha 派生、`has_sky_occluder=auto` 时黑盘判据与 1.21.1 不同 | P1 / P2 | §4.2 |
| **C** | `cloud_height` 的"绝对 Y"换算**是对的**（`-0.33` 在 `cloudHeight()` 里减，`cloudRelativeY` 再加回），**但云层灰度梯度 / alpha / UV 取法与 1.21.1 完全不同**：26.2 用**原生 `CloudRenderer.TextureData` 的 occupancy 位**自建 6 面盒（纯白，无贴图采样），1.21.1 调**原生 `buildClouds`**（带 `clouds.png` 每格颜色 + `RenderSystem.setShaderColor` 调制） | P1 | §5 |
| **D** | **custom sky 的 pack 注入存在启动顺序竞态**：`CustomSkyPackMixin` 加 `MultiPackResourceManager`，但其依赖的 `generatedDir` 在 `onInitialize()` 里才赋值，而**无任何代码保证 `onInitialize()` 早于第一个 `MultiPackResourceManager` 构造** | **P1（潜在）** | §6.1 |
| **E** | **DH current-view 校验有一个真实漏洞**：`capture()` 的 `valid=false` 写在 `irisShadowPass()` 早退**之前**是对的，但**`token` 每帧被最后一次 `LodRenderer.render` 覆盖**，而 `configure()` 只在 `viewToken==token` 时生效 → **若视野内有 ≥2 个投影不同的 DH 视图（镜像/传送门），只有"最后提交者"能拿到深度**，其余视图静默丢失 DH 遮挡（不崩、不报错） | P1 | §6.2 |
| **F** | `SkyTimeClock.Mode` 枚举名是 **`LOCKED` 不是 `LOCK`**；服务器命令树是 `/skyboxblock`，`env` 是 `environment` 的**别名**，子节点 `get/set/reset/list/reload/time`；`time` 子节点是 `get/set/lock/unlock/reset`，`set` 的值是**贪心字符串**（数字 0..23999 或预设名）。时间**没有** `running` 子命令（`set` 就产生 RUNNING） | — | §8 |

---

## 1. 架构对照（只列影响语义的部分）

| 维度 | 1.21.1（`skybox-mod/src/main`） | 26.2（`skybox-mod-26.2/src/main`） |
|---|---|---|
| 状态快照 | `SkyDisplayState`（102 行，`public`，独立采样类） | `SkySceneState`（106 行，**package-private**，构造即消费 `LevelRenderState`） |
| 样本时刻 | `SkyWorldRenderer.Frame` 里 `sky` 延迟到首次 `render()` | `Frame` 构造器内 `sky=new SkySceneState(state,partial)`（`SkyWindowRenderer.java:51`） |
| 画天空 | `VanillaSkyScene`（**自研 core shader**：`vanilla_background/celestial/stars/dawn` + `SkyMesh` + `TextureTarget`） | `SkyVanillaScene`（**自研 raw-GL 程序** `shaders/isolated/scene.{vsh,fsh}`，自带 FBO/采样器） |
| 画云 | `SkyVanillaClouds` → **原生** `LevelRenderer.buildClouds()` + `CloudMeshAccess` + `CloudBuildRangeMixin` | `SkyVanillaScene.drawClouds()` → **原生 `CloudRenderer.TextureData` occupancy** 自行拼盒（`SkyCloudDataAccess` 访问器） |
| 深度/合成 | `SkyWorldRenderer` 441 行、深度拷贝、`SkyCoverage`、`SkySurfaceLayer`、`SkyTemporalEdgeMask` | `SkyWindowRenderer` 258 行：world alias / coverage / transBefore-after / final resolve |
| Iris | `IrisPipelineMixin` / `IrisCompositeMixin` / `IrisExposureComputeMixin` + 261 行 `ModCompat` | 只剩 `SkyIrisFinalMixin` + `SkyIrisTransparentMixin` + 92 行 `ModCompat` |
| DH | `DistantSkyDepth` 经 **`DhApi.events.bind(DhApiBeforeRenderEvent)`**（官方 API） | `SkyDistantDepth` 经 **`@Pseudo @Mixin(LodRenderer)` 的 HEAD 注入**（读私有 `RenderParams` 字段） |
| 时间 | `SkyTimeController` + `SkyTimeClock`（与 26.2 **同实现**） | 直接 `SkyboxServerState.get().clockTicks(...)`（`SkySceneState.java:41-43`） |

> ⚠ 26.2 的 `SkyWindowRenderer` 和 1.21.1 的 `SkyWorldRenderer` **不是同一套编排**，本文只在"输入语义"这一层对照；
> 合成/深度那套（`coverage/transBefore/transAfter/final`）本文不做裁决（用户也没点）。

---

## 2. 联机：服务器 / 逐玩家 settings 与 clockTicks

### 2.0 先说"哪些是真同一份实现"

`【同一性】` 以下文件两棵树**逐字节相同**（整文件比对）：

```
SkyboxSettings.java   SkyEnvironment.java   SkyTimeClock.java
client/SkyEnvironmentMath.java   client/SkyRenderMath.java   custom/SkyConfigParser.java
```

⇒ **`clockTicks()` 的公式在两边是完全一样的**（`SkyboxSettings.java:43-53`），所以"26.2 把时钟改坏了"这种说法
在代码层不成立。下面 A/B 只针对 **26.2 独有的调用方式**（`SkySceneState` / `SkyboxServerState`）与
1.21.1 的调用方式（`SkyDisplayState` / `SkyTimeController`）。

### 2.1 【P0 · 确定】登录快照被 `accept()` 的 `enforced` 门禁吃掉

**代码事实**（26.2，逐行读）

- `SkyboxServerState.begin()`（`client/SkyboxServerState.java:35-41`）：`enforced = true`、`lastRevision = -1L`。
- `SkyboxServerState.end()`（`:43-50`）：`enforced = false`。
- `SkyboxServerState.accept()`（`:26-33`）：**第一句** `if (!enforced || settings.revision() <= lastRevision) return;`
- `register()`（`:52-75`）：
  - `ClientPlayConnectionEvents.INIT` → `begin(handler)`（`:55-57`）
  - `ClientPlayConnectionEvents.DISCONNECT` → `end(handler)`（`:58-60`）
  - `ClientPlayNetworking.registerGlobalReceiver(TYPE, …)`（`:61-74`）→ `accept(payload.settings())`

**服务端一侧**（26.2）

- `SkyboxNetworking.register()`（`network/SkyboxNetworking.java:21-31`）：
  `ServerPlayConnectionEvents.JOIN` → `send(player, SkyboxServerConfig.get(server).forSend(player.getUUID()))`。
- `forSend()`（`server/SkyboxServerConfig.java:207-214`）：`revision = max(effective.revision(), ++sendSequence)`。
  `sendSequence` 是 `Store` 的单调计数器，**从 1 开始**（`:94-95`），所以首包 `revision ≥ 1`。

**推导（纯逻辑，不需要跑游戏）**

- Fabric 的 `ServerPlayConnectionEvents.JOIN` 与 `ClientPlayConnectionEvents.INIT` 属于**同一个连接阶段**的两侧钩子；
  `INIT` 的对称清理钩子是 `DISCONNECT`（同一文件 `:55-60`）。⇒ **进入连接会话时客户端处于 `enforced == false`**。
- 因此服务端 JOIN 发出的那个首包 `revision ≥ 1` 到达时，`accept()` 的条件 `!enforced` 为真 → **静默 `return`**。
- 之后任何一次 `commit()`/`commitPlayer()`（`SkyboxServerConfig.java:186-196`、`:272-285`）都会 `publish()`（`:198-204`）
  → 再走 `forSend()`（`++sendSequence`）→ 这次客户端已 `enforced == true`，**能收下**。

**用户可感行为（这是判决点）**

1. **"重进服就先看到默认天空"**：`SkyboxSettings.DEFAULT`（`SkyboxSettings.java:11-12`）= 无任何 environment 覆盖、
   `clouds=true`、`cloudHeight=192`、`fade 128..384`、`clock=AUTO`。所以玩家一登录，看到的是**服务器默认**而不是
   **服主配的值**，直到服主再敲任意一条命令。
2. **"逐玩家设置看起来没生效"**：`settingsFor()`（`:219-232`）与 `forSend()` 的逐玩家合并**逻辑是对的**，
   但它的**唯一首次送达机会**（JOIN）被丢弃 ⇒ 逐玩家 override 在"服主不重敲命令"的情况下等于不存在。
3. **`reset all` 之后恢复正常？** 不是的——`resetAll()` 会 `commit()`→`publish()`，这时能送达；但**再重登一次又丢**。

> 说明（避免误伤）：`enforced` 同时被 `SkyboxConfig` 的 8 个 getter 当"服务端权威"开关用
> （`client/SkyboxConfig.java:53,135,163-167,204,255,280-281`），**不能**简单删掉 `accept()` 里的 `!enforced`
> 判断。合理的修法只有两条路（**本文不改**，供主线决策）：
> - (i) `begin()` 里就 `enforced = true`、`lastRevision = -1`，把"客户端有权写本地配置"改为只在 `end()` 后成立；
> - (ii) 保留 `enforced` 语义不动，`accept()` 的门禁改成 `connection != sender`（`receiver` 已经算好了 `sender`，见 `:62`）。
>
> 两条路都要确认：`SkyboxConfig.get()` 的 `enforced` 分支（`:53-56`）在"已连接但首包未到"这一帧里会返回
> `clouds=true, simulatedLocalWeather=false, 空维度集` 的临时实例——这是**另一处**同源现象（首包未到时按"服务端默认"渲染，
> 而不是"本地配置"）。

### 2.2 【P1】`AUTO / LOCKED / RUNNING` 的语义：26.2 与 1.21.1 **在公式上一致**，差异在"自动时间的取源"

**公式（【同一性】，两棵树同文）** `SkyboxSettings.java:43-53`：

```java
AUTO    -> automaticTicks
LOCKED  -> clock.ticks()                                     // 常数，partial 不加
RUNNING -> normalize(clock.ticks() + floorMod(gameTick-anchor,24000) + clamp(partial))
```

- `LOCKED` **不加 `partial`**：这一条常被误判成"卡顿/跳帧 bug"，实际是**设计**——锁定就是绝对常数。
- `RUNNING` **每帧加 `partial`**：因此 RUNNING 下天空是**平滑推进**的，LOCKED 下是**逐 tick 跳动**的。
  两者在"一天 24000 tick、1 tick ≈ 0.015° 太阳角"的量级上，肉眼看不出跳变；**不要**为这个改代码。
- 重登一致性：`RUNNING` 的相位只依赖 `(ticks, anchor, gameTick)`，全部来自**服务器持久化文件**
  （`server/SkyboxServerConfig.java:341-345` 写 `skyTimeMode/skyTimeTicks/skyTimeAnchor`），**与客户端时钟无关**
  ⇒ 【源码】两个客户端同 `gameTick` 必然同相位。这条 26.2 做到了。

**真正的差异：`automaticTicks` 从哪来**

| 场景 | 1.21.1 | 26.2 | 差异性质 |
|---|---|---|---|
| 本地天空 + AUTO | `SkyTimeClock.ticksForPhase(level.getTimeOfDay(partial))`（`SkyDisplayState.java:31,39`） | `ticksForPhase(nativeSky.sunAngle/(2π))`（`SkySceneState.java:37`） | **等价性依赖原生 `sunAngle` 与 `getTimeOfDay()` 同曲线**；26.2 用**渲染状态里的实测角度**反解，1.21.1 用**世界查询**。26.2 这条路**在光影改写天空时仍能对上屏幕**，反而更稳；但当**环境属性 `sun_angle` 生效时两者都会跑偏**（见 §4.2-1）。 |
| 非本地（模拟维度）+ AUTO | `normalize(floorMod(gameTime,24000)+partial)`（`SkyDisplayState.java:40`） | **完全相同**（`SkySceneState.java:40`） | 无差异 |
| **服务端权威 + AUTO** | `SkyboxServerState.get().clockTicks(tick,partial,automatic)`，`automatic` 同上（`SkyTimeController.java:23-27`） | 同左（`SkySceneState.java:41-43`） | 无差异 |
| `AUTO` 且 `simulated==false` | `phase=displayPhase(local,nativePhase,...)`，**不重算颜色** | `sunAngle=nativeSky.sunAngle` 原样，**不重算颜色** | 无差异 |

**⇒ 结论**：`AUTO/LOCKED/RUNNING` 三态与 `clockTicks` 在两边是**同一套数学**，"26.2 没还原联机时钟"不成立。
会让用户觉得"没还原"的，是 §2.1 的**首包丢失**，以及下面 §2.3 的**模式集合少了一个"重置"路径**。

### 2.3 【P2】"reset" 在 26.2 只剩服务器命令，客户端本地 `reset` 被禁用

- `SkyTimeController`（1.21.1，`client/SkyTimeController.java:49`）有 `reset()` → `SkyboxConfig.resetSkyTime()`；
  26.2 该文件**只在 `src/legacy-1.21.1`**，`src/main` 无同功能类。
- 26.2 的本地时钟重置入口 = `/skyboxblock environment time reset`（`server/SkyboxServerCommands.java:363-365,402-404`）；
  客户端侧 `SkyboxConfig.update()` 在联机时**直接抛异常**（`client/SkyboxConfig.java:255`）。
- ⇒ **单机**（无服务器权威）时，26.2 没有游戏内重置手段，只能手改 `config/skyboxblock.properties` 删
  `skyTimeMode/skyTimeTicks`。这是**功能缺口**（不影响联机，但"完整还原"要记一笔）。

### 2.4 模拟维度（`usesLocalTime` / `simulatedTimeDimensions` / `simulatedWeather`）

`【同一性】` `SkyboxSettings.usesLocalTime()`（`SkyboxSettings.java:37-40`）两边同文：

```java
if (simulatedTimeDimensions.contains(dimension)) return false;
return localTimeDimensions.contains(dimension) || normalSky;
```

**"normalSky" 的判定两边不同**（这是 26.2 必须做的 API 迁移，方向正确）：

| | 判定 | 位置 |
|---|---|---|
| 1.21.1 | `level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL` | `SkyDisplayState.java:34` |
| 26.2（客户端） | `nativeSky.skybox == DimensionType.Skybox.OVERWORLD`（取自 `frame.skyRenderState`） | `SkySceneState.java:26` |
| 26.2（服务端） | `level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD` | `server/SkyboxServerConfig.java:70-71` |

**需注意的语义变化**（`【需实测】`）：1.21.1 的 `effectsLocation()` 是"未知 ID 也回落到 OverworldEffects"，
而 26.2 的 `Skybox` 是**数据驱动枚举**。⇒ **自定义维度若没声明 `skybox` 字段**，26.2 取到的默认值决定它是
"模拟"还是"本地"。这一段**必须用一个自定义维度实机验证一次**，否则"模拟维度是否还工作"无法下结论。

**26.2 的一处**额外**硬化（正面）**：`SkySceneState.java:28` 把"`!normal`、`!local`、`mode != AUTO`"三者任一
都判为 `simulated=true`，因此**非原版天空维度即使时钟是 AUTO 也会重算颜色/星/dawn**（因为原生 `skyColor` 在
Nether/End 里是无意义的）。1.21.1 的 `nativeAppearance`（`SkyDisplayState.java:53`）只在
`local && normalAppearance && !customClock` 时走原生色，其余也重算 ⇒ **行为等价**，26.2 只是把条件写在一处。

### 2.5 逐玩家 override 的**功能**边界（不是 bug，但必须写进交付说明）

- `/skyboxblock player <targets> set <attribute> <value>`、`… reset <attribute>`、`… reset all`、`… time set <tick>`、
  `… time reset`：全部存在（`server/SkyboxServerCommands.java:97-125`）。
- **`time reset` 实际不可用**：`playerTimeReset()`（`:211-223`）调
  `store.resetPlayerEnvironment(player, "skybox.time")`，而 `"skybox.time"` **不是注册过的属性**
  （`SkyEnvironment.attribute()` 返回 `null` → `requireAttribute()` 抛 `IllegalArgumentException`），
  代码自己 catch 并提示 `"该版本暂不支持按玩家重置时间，请用 reset all"`（`:218-220`）。
  ⇒ **已知缺口，代码已自承**；本文只把它列进"未还原项"清单，不另开问题。
- 逐玩家 scope **只覆盖 environment + clock**，不覆盖 `clouds / cloudHeight / fade / simulatedWeather / 维度集`：
  `commitPlayer()`（`:272-285`）只写玩家自己的 `Properties`，`merged.putAll(global)` 后再 `parse()`，
  也就是**逐玩家能改的键 = 全局键的子集**（凡是 `SkyEnvironment` 注册的 + `skyTimeMode/Ticks/Anchor`）。
  `playerSet()` 的入口只走 `store.setPlayerEnvironment()`（`:153`），因此**云开关/云高/维度集无法按玩家设**。

---

## 3. 逐玩家联机的"证据链"该怎么验（不跑游戏也能定的部分）

| 环节 | 代码位置 | 可静态判定的结论 |
|---|---|---|
| 服务端持久化 | `SkyboxServerConfig.persist()`（`:394-416`）| 原子替换（临时文件 + `ATOMIC_MOVE` + `force(true)`），无"非原子回落" |
| revision 单调 | `forSend()`（`:207-214`） | 每次发送都 `++sendSequence`，因此**同一玩家连收两包不会被 `<=` 挡掉** |
| 客户端去旧 | `accept()`（`SkyboxServerState.java:28`） | `<=` 挡旧包 ✅（但见 §2.1 的 `!enforced`） |
| 跨连接隔离 | `register()`（`:61-74`） | `sender` 与 `epoch` 双校验 ✅（重连后旧包不会串） |
| 断线复位 | `end()`（`:43-50`） | 复位 `snapshot=DEFAULT`、`enforced=false` ✅ |
| **首包** | §2.1 | ❌ 丢失 |
| 逐玩家合并 | `settingsFor()`（`:221-232`） | 合并 `read(file)`(全局) + 玩家键，再 `parse()`；单个玩家键非法只记 warn 并回落全局（`:228-231`） ✅ |

---

## 4. 15 个 `SkyEnvironment` 属性：是否都被消费（逐条）

### 4.1 消费表（右侧是该属性**实际改变的量**，不是"有没有 `has()` 调用"）

| # | 属性 | 26.2 消费点 | 实际效果 | 1.21.1 对应点 | 一致？ |
|---|---|---|---|---|---|
| 1 | `visual/sky_color` | `SkySceneState.java:82` → `sky` → `SkyVanillaScene.java:37`（`Tint` 画 `UPPER` disc） | 天穹盘底色 | `SkyDisplayState.java:76` → `VanillaSkyScene.java:69` `DrawColor` | ✅ |
| 2 | `visual/fog_color` | `SkySceneState.java:83` → `fog` = FBO 清屏色 + `FogColor` uniform（`SkyVanillaScene.java:32,34`） | 天空雾色 | `:77` → `canvas.setClearColor` + `FogColor`（`:56,66`） | ✅ |
| 3 | `visual/fog_start_distance` | `SkyEnvironmentMath.java:16` `fogStart()` → `SkyVanillaScene.java:35` `FogRange.x`；以及 `cloudStart()`（`:37`） | **只对云层生效**（`scene.fsh:27` Mode==3） | `VanillaSkyScene.java:60` `FogStart` 在 **Mode==2（背景）** 的 shader 里被用 | ⚠ **语义不同：26.2 的 `scene.fsh` 没有给 Mode==0 用 `FogRange.x`（`:22-25` 只吃 `.y`）** |
| 4 | `visual/fog_end_distance` | `SkyEnvironmentMath.java:19`（且 `max(96F, …)`） | 同上 | 同上 + `fogEnd()` 同 `max(96)` | ⚠ 同 #3 |
| 5 | `visual/sky_fog_end_distance` | `SkyEnvironmentMath.java:20-21`（优先于 #4） | 天空雾终点 | 同 | ✅ |
| 6 | `visual/cloud_color` | `SkySceneState.java:84`（`color()` + `cloud.w *= .8F`）→ `SkyVanillaScene.java:102` `Tint` | 云顶点色（整体调制） | `SkyDisplayState.java:78-79`（`cloudColor` + `cloudOpacity` 分开） | ⚠ **alpha 处理不同**：26.2 固定 `×0.8`，1.21.1 用 `alpha/255` |
| 7 | `visual/cloud_height` | `SkyEnvironmentMath.java:28-31`（`- .33F`）→ `drawClouds` 的 `y`（`SkyVanillaScene.java:83`） | 云的绝对世界 Y | `SkyEnvironmentMath` 同文 + `SkyRenderMath.cloudRelativeY` | ✅ **换算正确**（见 §5.1 详解） |
| 8 | `visual/cloud_fog_end_distance` | `SkyEnvironmentMath.java:33` `cloudEnd()` + `:36` `cloudStart` 改 `end/3` | 云淡出终点 | 同 | ✅ |
| 9 | `visual/sunrise_sunset_color` | `SkySceneState.java:85` → `SkyVanillaScene.java:38,42` | dawn 带 | `SkyDisplayState.java:81-85` → `VanillaSkyScene.java:74-80` | ⚠ **alpha 派生口径不同**（§4.2-2） |
| 10 | `visual/sun_angle` | `SkySceneState.java:86` **`Math.toRadians(值)`** → `sunAngle` | 太阳朝向 | `VanillaSkyScene.java:87-88` 走 `SkyEnvironmentMath.angle()`（**取模 360**）再 `toRadians` | ⚠ **26.2 少归一**（§4.2-1） |
| 11 | `visual/moon_angle` | `SkySceneState.java:87` `toRadians(值)`，**独立于 sun** | 月亮朝向 | `VanillaSkyScene.java:92-93` `angle(值) - 180F` | ⚠ **26.2 少 `-180` 且少归一**（§4.2-1） |
| 12 | `visual/star_angle` | `SkySceneState.java:88` `toRadians(值)` → `celestialMvp(...,starAngle)`（`:50`） | 星图朝向 | `VanillaSkyScene.java:103-104` 同 `angle()` 归一 | ⚠ 少归一 |
| 13 | `visual/moon_phase` | `SkySceneState.java:90` `moonPhase=` → `SkyVanillaScene.java:46` 选 `moon/<name>.png` | 月相贴图 | `SkyDisplayState.java:46`（**只在非 local 分支**用 env） | ⚠ **26.2 更正确**（任何模式都吃覆盖） |
| 14 | `visual/star_brightness` | `SkySceneState.java:89` → `SkyVanillaScene.java:48` | 星亮度 | `SkyDisplayState.java:80` → `VanillaSkyScene.java:100` | ✅ |
| 15 | `visual/has_sky_occluder` | `SkySceneState`（不读）→ `SkyVanillaScene.java:53-58` | 半球遮挡 | `VanillaSkyScene.java:109-123` | ⚠ **auto 路径判据不同**（§4.2-3） |

**15/15 都被消费。** 覆盖率不是问题；问题是 **3 条（#3/#4 之雾、#10-12 之角度）语义漂了**。

### 4.2 三处语义差异（按影响排序）

**1) `sun_angle / moon_angle / star_angle`：26.2 把"度"直接转"弧度"且不归一**

```java
// 26.2  SkySceneState.java:86-88
if(environment.has("sun_angle"))  sunAngle=(float)Math.toRadians(environment.number("sun_angle",0));
if(environment.has("moon_angle")) moonAngle=(float)Math.toRadians(environment.number("moon_angle",0));
if(environment.has("star_angle")) starAngle=(float)Math.toRadians(environment.number("star_angle",0));
```

```java
// 1.21.1  SkyEnvironmentMath.angle( )  — 注册表里 documented 为"有限值，不取模"
static float angle(float degrees){ return (float)(((double)degrees%360.0+360.0)%360.0); }
// 调用：VanillaSkyScene.java:87-88 / :92-93 / :103-104 都先 angle(...) 再 toRadians
//       且 moon 额外 -180F、太阳月亮共享 solarView、星用同一 solarMvp
```

- **差异 1**：`SkyEnvironment` 的 `doc()` 写"有限值，不取模"（`SkyEnvironment.java:114-120`），1.21.1 的**执行侧**却取模
  —— 这是 1.21.1 内部**文档/实现不一致**；26.2 选择了"照文档、不取模"。
  后果：角度很大（例如 `54000`）时 26.2 的 `sin/cos` 参数化会**丢精度**（float 弧度大值时周期分辨率下降），
  1.21.1 不会。**实用角度 0..360 内两者一致。**
- **差异 2（更实质）**：1.21.1 的 `moon_angle` **恒等于 `sun_angle - 180°`**（它复用 `solarView`，只把角度 `-180`）；
  26.2 让 `moon_angle` **完全独立**（`SkySceneState.java:87` 单写，且 `SkyVanillaScene.java:69` 只用 `state.moonAngle`）。
  ⇒ **只设 `sun_angle` 时**：1.21.1 太阳/月亮一起动（永远相对）；26.2 太阳动、**月亮停在原位**。
  `SkyEnvironment.java:118` 的 doc 写"原版默认与太阳相差 180°"——**这句话在 26.2 的实现里不再自动成立**。
  这是"完整还原"里**唯一一处确定性行为改变**，建议按 1.21.1 口径补：`moonAngle = has(moon_angle) ? toRadians(angle(moon))-π : sunAngle+π`。
- **差异 3**：26.2 的 `moon_angle` 没有 `-180°`。（在"独立设置"语义下这是对的；在"跟随太阳"语义下是错的。与差异 2 是同一个决策点。）

**2) `sunrise_sunset_color`：alpha=0 时 26.2 完全不画 dawn 几何**

```java
// 26.2  SkyVanillaScene.java:38
if((state.sunrise>>>24)!=0) { ... 画 dawn ... }
// 1.21.1  VanillaSkyScene.java:74
if(dawn!=null && sky.sunrise!=null) { ... 画 dawn ... }
```

- `SkySceneState.java:79`：`simulated` 分支在非 dawn 区间把 `sunrise` 置 **`0`**（= `#00000000`）。
- `SkySceneState.java:85`：显式覆盖时 `sunrise = environment.color(...)`（ARGB 原样）。
- ⇒ **26.2**：`sunrise_sunset_color = #00RRGGBB` → `>>>24 == 0` → **跳过整段 dawn 绘制**（连 GL 状态变更都不发生）。
  **1.21.1**：`sky.sunrise` 是**非 null 的 float[4]**（`#00RRGGBB` 解析成 `a=0`）→ **照画**，只是全透明。
- 视觉上**都为"看不见 dawn"**；但**GL 状态轨迹不同**（26.2 少一次 `glEnable(BLEND)` + 一次 draw），
  在"每帧状态必须可逆"的框架里会改变回归基线。**如果回归断言在数 `GL` 调用次数，这一条会造成假阳性。**
- 另：1.21.1 在 `simulated` 分支用 `OVERWORLD.getSunriseColor(phase,partial)` 生成 float[4]（`SkyDisplayState.java:81-85`），
  26.2 在 `simulated` 分支**自算** `argb`（`SkySceneState.java:75-79`：`t=c/.4*.5+.5`、`alpha=(1-(1-sin(tπ))*.99)^2`、
  `r=t*.3+.7 / g=t²*.7+.2 / b=.2`）。**这两段数学是否等价，本文无法静态判定**（1.21.1 的
  `getSunriseColor` 是原版实现）。`【需实测】` **建议做成一次"同 tick 同 partial 对拍"**：把 1.21.1 的
  `DimensionSpecialEffects.OverworldEffects#getSunriseColor` 与 26.2 的 `SkySceneState:75-79` 在 `c∈[-.4,.4]` 上采样比对。

**3) `has_sky_occluder = auto` 时，26.2 的黑盘判据是 `normal && nativeSky.shouldRenderDarkDisc`**

```java
// 26.2  SkySceneState.java:80
darkDisc = normal && nativeSky.shouldRenderDarkDisc;
// 1.21.1  VanillaSkyScene.java:117-119
if (!env.has("has_sky_occluder")
        && frame.level.effects().skyType()==SkyType.NORMAL
        && frame.camera.y < frame.level.getLevelData().getHorizonHeight(frame.level)) { ...画 LOWER disc... }
```

- 26.2 **信任原生 `shouldRenderDarkDisc`**（渲染状态里已经算好的"是否该画虚空黑盘"）；
  1.21.1 **自己重算**"相机低于地平线"这一条件。
- ⇒ 26.2 更贴近 26.2 的真实判定（且不必再实现 `getHorizonHeight`）；1.21.1 的写法在 26.2 上不成立（`effects()` 已删）。
  **这是合理的 API 迁移，不是缺口**——但两边的黑盘出现条件**不再逐 tick 相等**，验收时不要拿 1.21.1 的截图当基准。
- 另：26.2 的 `LOWER` disc 用 `translate(0,12,0)`（`SkyVanillaScene.java:57`），1.21.1 同样 `translate(0,12,0)`（`VanillaSkyScene.java:120`）✅。

---

## 5. 云：height / fade / color / native alpha

### 5.1 `cloud_height`：换算**正确**（这条可以放心）

```java
// SkyEnvironmentMath.java:28-31（【同一性】，两棵树同文）
static float cloudHeight(SkyEnvironment env,float legacyNominalHeight) {
    return env.has("cloud_height") ? env.number("cloud_height",192.33F) - .33F : legacyNominalHeight;
}
// SkyRenderMath.cloudRelativeY(double cameraY, float height) { return height - cameraY + .33F; }
```

- 语义链：属性 = **绝对最终世界 Y**；`-0.33` 抵消 `cloudRelativeY` 里补的 `.33`，所以**最终画在 `attribute` 指定的 Y**。
- `SkyEnvironmentRenderRegression.java:48-49` 断言 `cloud_height=192.33` → `cloudHeight()` 位等于 `192F` ✅
  （`192.33 - 0.33` 在 float 下确实回到 `192.0`，该断言可信）。
- **26.2 的调用点**：`SkyVanillaScene.java:83`
  `float y = SkyEnvironmentMath.cloudHeight(env, config.vanillaCloudHeight()) + .33F - (float)camera.y;`
  → 与 `cloudRelativeY` 数学相同，**且 `.33` 加了一次、减了一次**，净效果 = `attribute - camera.y` ✅
  **没有**"额外偏移"。

> ⚠ 唯一要盯的是**默认值**：`config.vanillaCloudHeight()` 在联机时取 `SkyboxSettings.cloudHeight()`
> （`client/SkyboxConfig.java:165` → `SkyboxSettings.DEFAULT` 是 `192F`），本地时取 `vanillaCloudHeight` 配置项
> （默认 `192`，`SkyboxConfig.java:77`）。**两条路都是 192**，与原版 `OverworldEffects` 的 192 一致 ✅。

### 5.2 `fade`：26.2 用**球面距离**，1.21.1 用**原生云雾**

| | 26.2 | 1.21.1 |
|---|---|---|
| 起点/终点 | `cloudStart()` / `cloudEnd()`（`SkyEnvironmentMath.java:32-38`） | 同名同文（【同一性】） |
| 终点传给谁 | `scene.fsh` 的 `FogRange`（`SkyVanillaScene.java:104`）→ 顶点 `sphericalDistance=length(pos)`（`scene.vsh:15`） | 原生 cloud shader 的 `CloudFogStart/CloudFogEnd`（`SkyVanillaClouds.java:238-239`） |
| 距离度量 | **球面**（`length(pos)`，含 y） | **原版**（`FogShape` 相关的柱面/球面混合） |
| 生效方式 | `color.a *= 1-fog`（`scene.fsh:28`），**只淡 alpha** | shader 内混合到雾色 |

- 起点带 `end/3` 的联动（`cloudStart`：设了 `cloud_fog_end_distance` 就 `start=end/3`，再被 `fog_start_distance` 覆盖）
  **两边完全一致** ✅。
- **差异**：26.2 的 `sphericalDistance` 是**到相机原点**的距离（`pos=Position+PositionOffset` 后 `length`），
  而 `Position` 已含 `-dx,-dz` 相机分数修正（`SkyVanillaScene.java:103`）⇒ **云淡出是以相机为球心的球面**。
  1.21.1 用原版 fog，是**柱面（绕相机竖轴）**。⇒ 抬头看天顶时，**26.2 会把天顶的云淡得更早**。
  这是"完整还原"里第二条确定性视觉差异（`cloud_fog_end_distance` 越大越明显）。

### 5.3 `cloud_color` 与原生 alpha

```java
// 26.2  SkySceneState.java:84
if(environment.has("cloud_color")) { color(cloud, environment.color("cloud_color",0)); cloud.w *= .8F; }
// 26.2  SkyVanillaScene.java:102  setup(..., Tint = state.cloud.x/y/z/w)
// 1.21.1 SkyDisplayState.java:78-79
cloudColor=…; cloudOpacity=(argb>>>24)/255F;
// 1.21.1 SkyVanillaClouds.java:189,240  用 render 时 already-encoded color（含 alpha），并单独 set CloudOpacity
```

- **26.2 丢弃了 `cloud_color` 的 alpha**：`color()` 正确解析出 `w=alpha`，但下一行**无条件 `*= .8F`**
  （`SkySceneState.java:84`）⇒ `#FFRRGGBB`（alpha=255）得到 `0.8`，`#00RRGGBB`（alpha=0）得到 `0.0`。
  **`#00RRGGBB` 仍能隐藏云** ✅（`SkyEnvironment` doc 承诺的"alpha=0 即隐藏云"成立），
  但 **`#B3…`（alpha=0.7）和 `#FF…`（alpha=1.0）会被压成同一个 0.8 不可区分**。
- 1.21.1：`cloudOpacity = alpha/255`，**线性保真**。
- ⇒ **原生 alpha 语义未还原**（用户点名的"cloud … nativealpha"这一项**不通过**）。
  另外 26.2 在 `SkyVanillaScene` 的云像素 alpha 恒为 `1`（`SkyVanillaScene.java:92` `float r=1,g=1,b=1,alpha=1;`），
  全靠 `Tint.w` 统一调制；`setup(..., alpha=state.cloud.w)` → `scene.fsh:18` `color=Tint*vertexColor` ⇒ 等效于"整体 0.8"。

### 5.4 【关键】26.2 的云**不采样 `clouds.png`**，1.21.1 采样

```java
// 26.2 SkyVanillaScene.java:90-99
long cell = cell(data, cellX+cx, cellZ+cz); if(cell==0) continue;
// Native 26.2 CloudFaces stores occupancy/direction, not the texture's RGB/alpha.
float r=1,g=1,b=1,alpha=1;
clouds.add(quad(... r*.7F,g*.7F,b*.7F,alpha));   // 底面 0.7
clouds.add(quad(... r,g,b,alpha));               // 顶面 1.0
if((cell&8)!=0) ... *.8   (cell&4) ... *.9   (cell&2) ... *.8   (cell&1) ... *.9
```

```java
// 1.21.1 SkyVanillaClouds.java:198  （原生构建）
mesh = access.skyboxblock$buildClouds(Tesselator.getInstance(), x, y, z, color);
// 贴图：TEXTURE = textures/environment/clouds.png（:26,234-235），原生 UV/颜色直接来自该 PNG
```

- **26.2 的结论是对的**（26.2 的 `CloudRenderer.TextureData` 确实只给 occupancy/朝向，不给 RGB），
  所以它改用 **6 个面的亮度梯度**（顶 1.0 / 底 0.7 / 四侧 0.8~0.9）模拟体积感。
- **但**：1.21.1 的云**每一格的颜色来自 `clouds.png` 的像素**（有浅灰/深灰/纯白的随机纹理），
  26.2 的云**每格都是同一个纯白 × grad**。⇒ **云看起来是"没有纹理的纯色盒子"**。
  这是"完整还原"的**第三条、也是最显眼的一条**视觉差异（在 `clouds.png` 有花纹的情况下肉眼即可分辨）。
- 1.21.1 的 `clouds.png` 采样在**资源包重载**后自动跟随（原生 `CloudRenderer` 重建 `TextureData`）；
  26.2 也跟随（`SkyCloudDataAccess` 直接读 `cloudRenderer` 的 `texture` 字段，每次 `render` 都重新取，`:78`），
  **但没有 `dirty` 失效机制**：`cloudData != data` 才重建（`:87`），而 `TextureData` 是 **record**（值相等即 `equals`）
  ⇒ **若资源包换的是一张"格数相同"的 clouds.png，`equals` 可能仍为真，26.2 不重建**。`【需实测】`
  （这一条与 §6.1 的 pack 重载是同一族问题：`SkyTextureReloadMixin` 只 `SkyWindowRenderer.invalidate()`，不 `invalidate` 云）。

### 5.5 云的范围与缓存键：26.2 自己起了第 5 套参数（易误判）

- `SkyVanillaScene.java:85`：`radius = ceil(end/12)+1`（**12 格/unit**），单元缓存键 = `(data, cellX, cellZ, radius)`。
- `SkyVanillaClouds.java:181-187`（1.21.1）：`x=(cam+anim)/12`、`z=cam.z/12+.33`、`y/4` 分桶、`x,z` 各自 `-floor(x/2048)*2048` 包裹。
- 26.2 的 `x = camera.x + (cloudTime%(data.width()*400L))*.03`、`z = camera.z + 3.9600000381469727`
  （`SkyVanillaScene.java:80`）——**这是原生 26.2 的动画公式（不同分辨率下常数不同）**，与 1.21.1 的 `.03*(gameTime+partial)` 不同。
  ⇒ **整体漂移速度**：1.21.1 = `0.03 block/tick`；26.2 = `0.03 * (24000*w%…)` 的**取模后**速度 ——
  **`cloudTime` 是 `frame.gameTime+partial`（按 tick 线性）**，取模不改变速度，只改变相位 ⇒ **速度一致 ✅**，相位可能不同。
- ⚠ **文档口径**：`docs/STATUS-26.2.md:221,262` 描述的是**旧路径**（"逐格生成朝下的水平面，UV 取该格贴图坐标"），
  与当前 `SkyVanillaScene.drawClouds()`（**无 UV、无贴图**）**根本不同**。不要拿那段文档当现状。

---

## 6. custom sky 资源包 / 素材 / DH current-view

### 6.1 【P1 · 潜在】生成 pack 的注入存在启动顺序竞态

**链路**

1. `SkyboxBlockMod.onInitialize()`（`SkyboxBlockMod.java:104-105`）→ `CustomSkyRegistry.load(gameDir)`，
   其中 `generatedDir = gameDir/config/skyboxblock/generated` 并 `Files.createDirectories`（`custom/CustomSkyRegistry.java:66-70`）。
2. `CustomSkyPacks.resources()`（`client/CustomSkyPacks.java:88-92`）：**每次都新建** `CustomSkyPack(generated, location())`，
   `generated == null || !isDirectory()` 时返回 `null`。
3. `CustomSkyPackMixin`（`mixin/CustomSkyPackMixin.java:26-35`）：`@ModifyVariable(method="<init>", at=@At("HEAD"), argsOnly=true, index=2)`
   在 **`MultiPackResourceManager` 每次构造**时把该 pack **追加到列表末尾**。

**风险**

- `MultiPackResourceManager` 在**客户端启动早期**（`ReloadableResourceManager` 首次 `createReload`，包里含 builtin/vanilla/fabric）
  **就会构造一次**，**早于 `ModInitializer.onInitialize()`**。那一刻 `CustomSkyRegistry.generatedDir` 仍是 `null`
  ⇒ `resources()` 返回 `null` ⇒ **静默不追加**。
- 事件顺序本身**不是本仓库能证实的**（`【需实测】`），但**代码里没有任何"晚到补偿"**：
  - 没有 `ClientLifecycleEvents.CLIENT_STARTED` / `ResourceManagerHelper.registerBuiltinResourcePack` 之类的二次注入；
  - `CustomSkyPacks.markInstalled()`（`:37`）只是置位 flag，**没有任何地方读 `installed()` 去补注**（grep：`installed()` 在 `src/main` 内**零调用方**）。
- ⇒ 若首帧构造确实早于 `onInitialize()`，**自定义天空盒的贴图会 404**（方块仍注册成功，因为 `CustomSkyRegistry.styles()`
  在注册阶段已算好 `Identifier`，只是纹理加载不到）。表现会是"自定义方块全白/全洋红，但列表里有它"。
- **同一处还有第二个风险**：append 到**列表末尾** ⇒ 优先级**最低**。若 `clouds.png`/`sun.png` 之类被更高层包覆盖，
  26.2 的 `SkyVanillaScene` 用 `Minecraft.getInstance().getTextureManager().getTexture(Identifier.withDefaultNamespace(...))`
  （`SkyVanillaScene.java:64`）**拿的是资源栈最终结果**（含包覆盖）✅ 这一条是对的；但**方块自己的** `textures/sky/<id>.png`
  若被用户包覆盖，26.2 的 `SkyWindowRenderer.texture(skin.texture())`（`:242`）同样走资源栈 ✅ 也对。
  ⇒ **"素材有没有实际接入"结论：接入路径正确（走 TextureManager + 资源栈），风险只在 §6.1 的注入时序。**

### 6.2 【P1 · 确定】DH current-view 校验的漏洞：多视图时只有"最后提交者"能拿到深度

```java
// SkyDistantDepth.capture()  SkyDistantDepth.java:18-42
valid=false;  if(ModCompat.irisShadowPass()) return;
...
token = SkyWindowRenderer.transaction();  level = Minecraft.getInstance().level;   // ← 每次都覆盖
...
// SkyDistantDepth.configure()  :44-49
if(!ModCompat.DISTANT_HORIZONS || !valid || viewToken != token || level != Minecraft.getInstance().level) return;
double dx = camera.x-cameraX, ...; if(dx*dx+dy*dy+dz*dz > 1e-6) return;
for(row,col) if(|view.get(col,row) - viewRotation[row*3+col]| > 1e-4F) return;
```

**漏洞链（静态可证）**

1. `SkyDhRenderMixin`（`mixin/SkyDhRenderMixin.java:15-16`）在 `LodRenderer.render(RenderParams,IProfilerWrapper)` 的
   **HEAD** 注入 `capture(params)`；**每次 DH 视图渲染都会写一遍** `token / camera / viewRotation`。
2. `configure()` 的准入条件是 `viewToken == token` **且**相机 1e-6 内相等 **且** 3×3 视图旋转 1e-4 内相等。
3. 但 `token` 是**单份静态变量**，`capture()` **无条件覆盖** ⇒ **同一帧内若有 N 个 Dh 视图**
   （DH 的 shadow pass 已被 `irisShadowPass()` 挡掉，但**镜像 / 传送门 / 多相机** 仍在），`token` 里只剩**最后一次**的值。
4. ⇒ 对**前面那些视图**：`viewToken != token` → `configure()` 直接 `return`（**不设置 `DistantEnabled`**），
   即"该视图的天空窗口**没有 DH 深度遮挡**"，且**不报错、不 warn**（`configured` 只记录成功那次，`:62`）。
5. 更细的一层：`valid` 也是**单份**。若最后一个 DH 视图的矩阵朝向判定失败（`:25-27` 的 `return`），
   `valid=false` 会把**前面所有视图**一起作废。

**与 1.21.1 的对照（这条 26.2 反而"更统一"，但代价是丢视图）**

- 1.21.1 `DistantSkyDepth`（`legacy-1.21.1/.../DistantSkyDepth.java:19-40`）用
  **`DhApi.events.bind(DhApiBeforeRenderEvent)`**，并在回调里先做
  `SkyWorldRenderer.Frame frame = lastRoot(); if(frame==null||!frame.root||frame.shadow) return;` ——
  **只记 root 视图**，且 `transaction = frame.rootTransaction`（root 的 token，不会被别的视图覆盖）；
  `configure()` 里还有 `if(iris) { ... SkyWorldRenderer.activeGameFrame()!=frame ... return; }` 覆盖 Iris 分支。
- 26.2 改成了 **Mixin 直接读 DH 私有 `RenderParams` 字段**（`dhProjectionMatrix/mcModelViewMatrix/exactCameraPosition`），
  拿的是**真实提交的投影**（这点比 1.21.1 的 API 值更可信），但**没有"这是哪个视图"的身份**，
  于是只能退回"最后写者胜"。

**建议的判定式修法（只写文档，不改码）**

- 把 `token/valid/camera/viewRotation` 收进一个小记录对象，并在 `configure()` 里用**视图身份键**
  （最省事：`(viewToken, camera 六位取整, 3×3 旋转的 9 个 float bits)` 作 key）**缓存最近 K 个 DH 视图**（K=2~4），
  `configure()` 按同一 key 查表。这样镜像/传送门不会互相顶掉，也不会误用别的视图的深度。
- 次优：像 1.21.1 那样**只在 root 视图记录**（`SkyWindowRenderer` 已经有 `slot` 与 `FRAMES` 深度，`SkyWindowRenderer.java:48-52`），
  非 root 视图干脆不设置 `DistantEnabled`（明确"无 DH 遮挡"），至少**不会错配**。

**其余 DH 校验强度（正面结论）**

- `level` 相等、相机 1e-6、视图旋转 1e-4、**深度纹理尺寸 == 当前渲染宽高**（`:58-59`）——四道都在，"张冠李戴"基本堵住了。
- 纹理有效性检查 `glIsTexture` + `payload instanceof Number`（`:55-56`）也在 ✅。
- `ndcScale/ndcBias` 走 `getDepthRange()` 而不是猜（`:30-31`）✅ —— 与 1.21.1 一致。

### 6.3 `ModCompat`：读取大量**私有实现细节**，失败模式是"全静默"

| 项 | 26.2 实现 | 失败模式 | 与 1.21.1 |
|---|---|---|---|
| `irisActive/irisShadowPass` | `IrisApi` 反射（`:16-22`） | 返回 `false` | 同 API ✅ |
| `pipeline()` | `Iris.getPipelineManager().getPipelineNullable()`（`:23-26`） | 抛 → `-1` | 1.21.1 同思路 |
| `framebuffer(...)` | `ShaderMap.getShader(ShaderKey.BLOCK_ENTITY)` 的 `writingToBeforeTranslucent / writingToAfterTranslucent` 字段（`:30-40`） | `warn` 一次 + `-1` | 同名思路，26.2 少了 `privateTargetScope` / `isMainBound` 那套 |
| `view()` | `CapturedRenderingState.INSTANCE.getGbufferModelView()`（`:42-47`） | 保留原 view | **1.21.1 同时取 `getGbufferProjection()`**（`legacy ModCompat.java:46-56`），**26.2 只取 modelView** |
| `raster()` | `customUniforms` 里 `fsrRenderScale / taaJitter / fsrScreenSize / screenSize / gbufferProjection0`（`:48-64`） | 静默 | 同（但 1.21.1 有两个投影来源分支） |
| `portalClip()` | `qouteall…PortalRendering.isRendering()/getRenderingPortal().getInnerClipping()`（`:74-84`） | `null` | 同，但**26.2 的 `framebuffer` 里混进了 `portalClip` 的 import 依赖**（本文件全是反射，无硬依赖） |

**关于 `view()` 只取 modelView 这一条**（值得主线知道）：

- 26.2 的投影来自 `SkyWindowRenderer.PROJECTION`（`SkyGameRenderMixin.java:21-23` 的 `ModifyArg` 抓
  `ProjectionMatrixBuffer.getBuffer(Matrix4f)` 的实参），因此**在 Iris 下它是光影改写后的投影**——
  这比 1.21.1 用 `gbufferProjection` 覆盖更贴近"真实提交的矩阵"。
- 但 `view()` 又在 Iris 下把 view 换成 `gbufferModelView`，于是 **投影=原版实参、视图=Iris 视图**，若 Iris 把 bob/hurt
  放在**投影**里（1.21.1 注释 `SkyRenderMath.java:60-64` 正是为此写的），这个混搭会让 `RayInverse=proj·view` **重复或漏掉一次变换**。
  26.2 的 `SkyWindowMath.rayInverse()` 用 `proj·view` 求逆（`SkyWindowRenderer.java:219`），**没有** 1.21.1 的
  `jitteredSkyProjection` 那类补偿。⇒ `【需实测 · 光影下】`，非 GL3.3 主线范围，本文只标记。

---

## 7. 与"已修的 GL3.3 主线 bug"的关系（审计时新发现的同族问题）

> 要求：**不重写 `SkyGlScope` 的 ARB 入口分支修复**。以下只是指出**另一个类**里存在**同一模式**的问题。

`SkyGlScope.close()`（`client/SkyGlScope.java:91-106`）用
`coreIndexedBlend = OpenGL40`、`arbIndexedBlend = GL_ARB_draw_buffers_blend`、`indexedBlend = 两者或`
选择 `GL40.glBlendFuncSeparatei` / `ARBDrawBuffersBlend.*ARB` / 全局回落 —— 这是已修的那条。

**同族的第二条**：`SkyGlScope` **构造/析构**里的这三个调用**没有 ARB 回落**：

```java
GL30.glIsEnabledi(GL11.GL_BLEND, i)          // SkyGlScope.java:48   (no suffix: GLES-only name in LWJGL)
GL30.glColorMaski(i, ...)                    // SkyGlScope.java:105
GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, i, mask)   // SkyGlScope.java:52
```

- `glColorMaski` / `glGetBooleani_v` / `glEnablei` 在 **GL 3.3 core** 里是 `ARB_draw_buffers_blend` 扩展函数；
  LWJGL 的 `GL30.glColorMaski` 走的是核心 3.0 入口（`glColorMaski` 本身是 3.0），**通常可用**；
  但 `glIsEnabledi`（无后缀）是 **OpenGL ES** 的名字，桌面 GL 需 `GL30.glIsEnabledi`（3.0 core 有）——
  这正是夹具 **100×57 / GL3.3** 环境最可能踩到的点。
- **本文不宣称这是 bug**（`【需实测】`，且当前主线已修过一轮同族问题）；只提一句：
  `SkyGlScope` 的**恢复路径**已经做了能力分支，**记录路径**没有。若后续在 GL3.3 上再出
  `NullFunctionError / NoSuchMethod`，**先看这两处**，不要回头改已经修好的 `close()` 分支。

---

## 8. 服务器命令验收顺序（可执行，namespace 全部按源码核实）

> **来源**：`server/SkyboxServerCommands.java` 的 `buildTree()`（`:81-88`）与各节点定义。
> **根命令字面量 = `skyboxblock`**（`:82`，无命名空间前缀）；**子节点**（`:58-65`）：
> `environment`（`:59`，别名 `env`）、`player`（`:99`）、`attribute`、`value`、`time`。
> ⚠ 命令**不带** `minecraft:` / 模组命名空间前缀；attribute 参数用 `IdentifierArgument.id()`，**可写三种形式**
> （`sky_color` / `visual/sky_color` / `minecraft:visual/sky_color`，`SkyEnvironment.attribute()` 都解析）。
> ⚠ **`LOCK` 不存在**，模式名是 `locked`；也没有 `/skyboxblock environment time running`，`set` 即产生 `RUNNING`。

### 8.1 只读基线（任何人可执行，权限等级 0）

```text
1.  /skyboxblock                                            ← 用法 + "服务器统一生效" 提示
2.  /skyboxblock env list                                   ← 15 项全表 + 每项 effective（auto 表示未覆盖）
3.  /skyboxblock env get sky_color                          ← 单条读取（短 id 形式）
4.  /skyboxblock env get minecraft:visual/cloud_height       ← 命名空间形式（证明别名解析）
5.  /skyboxblock environment time                           ← "time = <tick> / <mode>"
6.  /skyboxblock player @s show                              ← 逐玩家：跟随服务器 / 有独立覆盖 + revision
7.  /skyboxblock player @s cloud_height                      ← 逐玩家读单个属性（注意：此处 @s 是 targets 参数）
```

### 8.2 时钟与逐玩家（需要权限等级 2 = `Commands.LEVEL_GAMEMASTERS`，`:284-288`）

```text
 8.  /skyboxblock environment time set 6000                  ← 产生 RUNNING（原 LOCKED 会保持 LOCKED，:152-153）
 9.  /skyboxblock environment time get                       ← 立刻读回，应 ≈ 6000 + Δ
10.  /skyboxblock environment time lock                      ← 冻结在"此刻"（不带值就取当前，:160）
11.  /skyboxblock environment time lock 18000                ← 冻结在指定 tick
12.  /skyboxblock environment time unlock                    ← 从冻结 tick 继续跑（:165-171）
13.  /skyboxblock environment time reset                     ← 回到 AUTO
14.  /skyboxblock player @s time set 1000                    ← 逐玩家时钟（:113-119）
15.  /skyboxblock player @s set star_brightness 0.5          ← 逐玩家属性覆盖
16.  /skyboxblock player @s star_brightness                  ← 应显示 "0.5（玩家覆盖）"
17.  /skyboxblock player @s reset all                        ← 清空该玩家全部覆盖（唯一可用的"按玩家重置"）
```

### 8.3 环境属性覆盖（重点验 §4 的 15 项 + §5 的云）

```text
18.  /skyboxblock env set sky_color #7BA4FF
19.  /skyboxblock env set fog_color #AABBCC
20.  /skyboxblock env set cloud_color #FFB0D8FF              ← ARGB 6/8 位都收；alpha=0 应隐藏云
21.  /skyboxblock env set cloud_color #00FFFFFF              ← 验证"alpha=0 隐藏云"（§5.3 成立）
22.  /skyboxblock env set cloud_height 300                   ← 绝对世界 Y（§5.1），值 300 应让云在 y=300
23.  /skyboxblock env set cloud_fog_end_distance 768         ← 同时把 cloud start 变 768/3=256（§5.2）
24.  /skyboxblock env set fog_start_distance -64             ← 负值合法（注意：26.2 只作用于云雾，§4.2-1）
25.  /skyboxblock env set fog_end_distance 1024              ← 最小 96 夹紧（SkyEnvironmentMath:19）
26.  /skyboxblock env set sky_fog_end_distance 2048          ← 优先级高于 fog_end
27.  /skyboxblock env set sunrise_sunset_color #80FF8040     ← ★ 重点看 alpha=0 的对照：
    /skyboxblock env set sunrise_sunset_color #00FF8040      ←   26.2 会"完全不画 dawn"（§4.2-2）
28.  /skyboxblock env set sun_angle 45                       ← ★ 重点：1.21.1 的月亮会跟着转，26.2 不会（§4.2-1）
29.  /skyboxblock env set moon_angle 180
30.  /skyboxblock env set star_angle 90
31.  /skyboxblock env set star_brightness 0.25
32.  /skyboxblock env set moon_phase waxing_gibbous          ← 也接受 0..7
33.  /skyboxblock env set has_sky_occluder true              ← 强制半球遮挡（覆盖后 §4.2-3 的差异不再出现）
34.  /skyboxblock env reset sun_angle                        ← 单项回 auto
35.  /skyboxblock env reset all                              ← 15 项全 auto + 删时钟键（resetAll():129-135）
```

### 8.4 逐玩家覆盖 vs 全局（验 §2.5 的边界）

```text
36.  /skyboxblock env set sky_color #112233                  ← 全局
37.  /skyboxblock player @a set sky_color #445566             ← 逐玩家；@a 应各拿各的
38.  /skyboxblock player @a show                               ← 应逐人显示 revision（forSend 每次 ++）
39.  /skyboxblock player @a reset sky_color                    ← 逐人清除单属性
40.  /skyboxblock env reset all                                ← 全局清空；玩家的覆盖**仍在**
```

### 8.5 【关键】联机首包验收（直接指向 §2.1）

```text
A. 服主先做一次全局设置（保证服务器文件里有非默认值）：
   /skyboxblock env set sky_color #00FF00
   确认生效（任意在线客户端天空变绿）。

B. ★ 让第二个客户端**重新登录**（不要动任何命令）。
   预期（当前代码）：新登录的客户端天空是**默认色**，不是 #00FF00。
   这就是 §2.1 的 P0 证据：`accept()` 的 `!enforced` 门禁吃掉了 JOIN 首包。

C. 复现后用任意一条命令触发 publish（例如 /skyboxblock environment time get 不够——它不 commit；
   需要一次**写**操作，如 /skyboxblock env set star_brightness 0.75），
   新客户端应立刻变成正确值 ⇒ 反证"只有首包丢、后续包都通"。
```

> ⚠ 上面 A/B/C 我**没有对 `port-gl33-r2` 执行**（按要求不对当前夹具发命令）。请在**另一个空闲实例**上跑，
> 或等主线 GPU 矩阵结束。

### 8.6 客户端侧只读命令（无权限要求）

```text
/skyboxcustom                 ← 打开 studio 目录
/skyboxcustom list            ← 已启用自定义风格 + 被跳过的警告数（§6.1 的现场证据）
```
（来源：`client/CustomSkyCommand.java:28-34`，客户端命令树，与服务器 `/skyboxblock` 是两棵树。）

---

## 9. 未还原 / 未验证清单（交给主线决策）

| # | 项 | 状态 | 依据 |
|---|---|---|---|
| 1 | 登录首包 settings | **P0 功能性断裂** | §2.1 |
| 2 | `cloud_color` 的原生 alpha | **未还原**（固定 0.8） | §5.3 |
| 3 | 云格纹理（`clouds.png` 的 RGB） | **未还原**（纯白 + 面梯度） | §5.4 |
| 4 | 云淡出距离度量（球面 vs 柱面） | **语义漂移** | §5.2 |
| 5 | `sun_angle` 联动月亮 / `-180°` | **行为改变** | §4.2-1 |
| 6 | 三个角度的 360° 归一 | 实现/文档口径分歧 | §4.2-1 |
| 7 | `sunrise_sunset_color` alpha=0 是否提交几何 | **不一致（GL 轨迹不同）** | §4.2-2 |
| 8 | 26.2 自算 dawn 颜色 vs 原版 `getSunriseColor` | `【需实测】` 等价性未知 | §4.2-2 |
| 9 | 自定义维度 `Skybox` 枚举默认值 → `normalSky` | `【需实测】` | §2.4 |
| 10 | custom pack 注入时序 / `resources()` 首调 | `【需实测】**潜在** 404 | §6.1 |
| 11 | `clouds.png` 换包后 `TextureData.equals` 是否触发重建 | `【需实测】` | §5.4 |
| 12 | DH 多视图只有最后提交者有深度 | **P1 确定** | §6.2 |
| 13 | `view()` 只取 `gbufferModelView` 而投影取原版实参 | `【需实测 · 光影】` | §6.3 |
| 14 | 逐玩家 `time reset` | 代码自承不支持 | §2.5 |
| 15 | 单机无游戏内时钟重置入口 | 功能缺口 | §2.3 |
| 16 | `SkyGlScope` 记录路径无 ARB 回落（`glIsEnabledi`） | `【需实测】`，不重复已修项 | §7 |

---

## 10. 本次审计**没有**做的事（守住边界）

- ❌ 未改任何 `.java` / `.json` / `.mjs` / 测试 / 脚本（本文件是唯一新增）。
- ❌ 未跑 `gradlew`（任何任务）。
- ❌ 未启动游戏、未连服务器。
- ❌ 未向当前夹具 `port-gl33-r2`（100×57 / GL3.3）发任何命令或截图请求。
- ❌ 未重写 `SkyGlScope` 的 ARB 入口分支（已修主线）。
- ❌ 未把 `docs/AUDIT-2026-11-02.md` / `STATUS-26.2.md` / `PARITY*` 里的"✅"当证据；凡引用的地方都标了
  `【文档声称】` 或指出与代码冲突。
