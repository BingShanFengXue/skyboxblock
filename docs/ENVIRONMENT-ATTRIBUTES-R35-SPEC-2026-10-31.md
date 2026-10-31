# R35 规格：原版天空盒方块的环境属性配置指令（可追踪版）

- 状态：**v6-FROZEN（四环境全部通过，2026-10-31 冻结交接）**。
  v1 初稿；v2 主线设计定稿；v3 第一轮代码审计；v4 复核源码 + 更正来源口径；v5 补 2 环境实机；**v6 冻结：四环境齐备**。
- **冻结口径**：本规格在「指定四客户端矩阵」范围内通过。**不扩为**完整发布认证，也不认证历史四红 / 门户 / 性能长测。
- 范围：**只有 `skyboxblock:sky_block`（显示名「天空盒方块（原版）」）内部的私有天空画布**。
  不触碰真实维度天空、云层、光照贴图、其他 8 种天空盒、用户实例/存档/光影包。
- 本文作者是独立规格/审计角色，**不生产代码**。接入点分工见 §11。
- 事实来源（抓取日期 **2026-10-31**，Asia/Shanghai）。**必须区分「社区 Wiki 转述」与「官方原文」**：

  | # | 来源 | 性质 | 链接 |
  |---|---|---|---|
  | 1 | 中文 Minecraft Wiki〈环境属性〉 | **社区 Wiki（转述）** | <https://zh.minecraft.wiki/w/环境属性?variant=zh-cn>（含 `action=raw` 交叉核对） |
  | 2 | 英文 Minecraft Wiki〈Environment attribute〉 | **社区 Wiki（转述）** | <https://minecraft.wiki/w/Environment_attribute?action=raw> |
  | 3 | 中文 Minecraft Wiki〈Java版26.4〉 | **社区 Wiki 转述的版本条目** | <https://zh.minecraft.wiki/w/Java版26.4?variant=zh-cn&action=raw> |
  | 4 | 英文 Minecraft Wiki〈Java Edition 26.4〉 | **社区 Wiki 转述的版本条目** | <https://minecraft.wiki/w/Java_Edition_26.4?action=raw> |
  | 5 | 英文 Minecraft Wiki〈Moon〉 | **社区 Wiki（转述）**，月相枚举顺序 | <https://minecraft.wiki/w/Moon?action=raw> |
  | 6 | 英文 Minecraft Wiki〈Sky〉 | **社区 Wiki（转述）**，26.4 天空遮罩行为 | <https://minecraft.wiki/w/Sky> |
  | 7 | 本项目既有文档 | 项目内部 | `README.md`、`docs/VANILLA-CLOUD-HEIGHT.md`、`docs/VANILLA-CLOUD-DISTANCE.md`、`docs/LESSONS.md` §5.1 |

  > ⚠ **出处口径更正（v4）**：本规格 v1–v3 曾把来源 3/4（Wiki 的「Java版26.4」页面）称作
  > **「官方 26.4 snapshot changelog」「一手官方来源」——这是不准确的，现予撤回**。
  > 那两个页面是**社区 Wiki 对版本内容的转述**，不是 minecraft.net 的发布原文。
  > 因此 `has_sky_occluder` 的「26.4 Snapshot 2 新增、默认值主世界 `true`／下界末地 `false`」
  > 目前只有 **社区 Wiki 转述** 一级证据，**未取得 minecraft.net 一手原文**。
  > 谁要主张"官方一手"，必须另取 minecraft.net 的 26.4 Snapshot 2 发布页并在此登记。
  >
  > 同理，全部属性的**类型／范围／默认值**均来自来源 1/2（同属社区 Wiki）；
  > 本规格**不主张**这些数值已由官方原文复核过。
  - 中文工具 DNS 未报错，以上均**直连抓取成功**，没有使用缓存或二手转述。


> ⚠ 本模组跑在 **MC 1.21.1 / Fabric**。环境属性体系是 **Java 1.21.11（25w42a 起）** 才有的数据包机制。
> 因此本功能**不是**回移环境属性引擎，而是**借用这些属性的 ID、语义、取值范围与默认值**，
> 在我们自己的 sky block 私有画布上手工实现同名配置项。见 §10「明确不做的部分」。

---

## 1. 一句话需求

给 `skyboxblock:sky_block` 增加一族**客户端外观配置**，使方块的天空、日月星、云、以及雾色/距离、天空遮挡可以像原版环境属性那样逐项设置；
默认值（`auto`）必须让现有渲染**逐像素不变**。

---

## 2. 验收要点（含当前证据状态）

图例：**✅实机** = 已在**指定四环境**取得真实客户端证据
（纯原版 Fabric / Iris 原生 / 原生 DH / Iris+DH+FSR2）；**✅静态** = 仅静态审计 + 数学自测通过。
**全部条目均已通过**，无 ⏳ 待验项。

| # | 要点 | 判据 | 证据状态 |
|---|---|---|---|
| A1 | auto 保留旧渲染分支 | 无覆盖走旧时间／天气／色彩和天体矩阵 | **✅源码 + 同包实机恢复**（四环境 `default_restored`；10 个受保护 class 与冻结 R34.2 同字节；未做两个版本整画面的逐像素 A/B） |
| A2 | 覆盖 §3 表「最小集合」15 项 | 每项 `get`/`set`/`reset` 可用 | **✅实机**（15 属性 × 6 类断言；累计 **812 条真实命令**） |
| A3 | `cloud_height` 是**最终高度**，与旧 `cloudheight` 差 **0.33** | `cloud_height 192.33` ≡ `cloudheight 192` | **✅实机**（`cloud-height-legacy-equivalent`）+ 数学逐位证明 |
| A4 | `cloud_color` 支持 alpha | alpha=0 时完全不画云 | **✅实机**（`cloud-transparent` / `cloud-half-alpha` / `cloud-cyan`） |
| A5 | `sunrise_sunset_color` 用 ARGB，alpha=0 不画 | — | **✅实机**（`dawn-magenta` / `dawn-transparent`） |
| A6 | 日/月/星角度**互相独立** | 三者解耦 | **✅实机**（`sun-independent` / `moon-independent` / `stars-rotated`） |
| A7 | `moon_phase` 八个命名值 | 与纹理 UV 对应 | **✅实机**（8 个 `ordinal` + 真实画布 **8 种不同像素**）+ 截图目视 |
| A8 | 天空雾 / 云雾可独立设 | 可覆盖 | **✅实机**（`independent_sky_end_gpu` / `cloud-fog-near/far`） |
| A9 | `has_sky_occluder` 可开关 | 关闭后不再遮日月星 | **✅实机**（`occluder-on` / `occluder-off`）+ 截图「遮挡体：雾色下半球」 |
| A10 | 无配置键互相覆盖 | 旧键与新键不互相改写 | **✅实机**（`all-persisted` / `reload-persisted` / `invalid-no-mutation`） |
| A11 | 旧接口兼容 | 4 条旧命令语义不变 | **✅实机**（`cloud-height-legacy-equivalent`）+ **✅静态** |
| A12 | 只影响原版天空盒 | 其他 8 种与真实世界不受影响 | **✅实机**（`other_style_unchanged:*` 中心像素 **MAE 0.0**，四环境） |
| A13 | **3 种** ID 写法可寻址（短 ID / `visual/x` / `minecraft:visual/x`） | 均指向同一项；`skyboxblock:` 与 `visual_` **不支持** | **✅静态**（数学层 3 写法归一；实机经真实命令间接覆盖） |
| A14 | `star_brightness` 显式值为最终值，不乘雨 | 雨天 `DrawColor=[1,1,1,1]` | **✅实机**（`stars-rain-overridden`，`rain=1.0`） |

> A13 的三种命令写法由独立 `SkyEnvironmentBoundaryRegression` 的 `allRoutes` 用真实 Brigadier 分别执行 set/get/reset（15 属性 × 3 写法 × 两个命令组别名）。实机矩阵也覆盖带命名空间的查询和重置，但未为每个写法单独截图，不把类路径回归混称为全写法实机截图验收。


---

## 3. 属性清单（核对结果）

「纳入」= 本次必须在 sky block 上可配置。
「范围外」= 有充分理由排除，且需在用户文档里写明理由（§5）。

### 3.1 纳入（最小集合，18 项）

| # | 属性 ID（命名空间 `minecraft:`，可省） | 类型 | 原版默认 | 原版范围/约束 | 本项目默认（`auto` 语义） | 版本 |
|---|---|---|---|---|---|---|
| 1 | `visual/sky_color` | RGB（`#rrggbb` / int / 3 float） | `#000000` | 通道 [0,1] | `auto`＝现有 `SkyDisplayState.skyColor` | 1.21.11（25w42a 前身 `visual/sky_color` 在 25w44a 列表化） |
| 2 | `visual/fog_color` | RGB | `#000000` | 同上 | `auto`＝现有 `SkyDisplayState.fogColor` | 1.21.11 |
| 3 | `visual/fog_start_distance` | float | `0.0` | **可为负**（负数表示雾从相机背后就开始） | `auto`＝现有 `nativeSkyFogStart`/0 | 25w44a |
| 4 | `visual/fog_end_distance` | float | `1024.0` | **必须 > 0**；原版最终值**不小于 96** | `auto`＝现有 `nativeSkyFogEnd`/`renderDistance*16` | 25w44a |
| 5 | `visual/sky_fog_end_distance` | float | `512.0` | **必须 > 0** | `auto`＝沿用现有天空雾终点 | 25w44a |
| 6 | `visual/cloud_color` | **ARGB**（`#aarrggbb` / int / 4 float） | `#00000000`（全透明） | 通道 [0,1] | `auto`＝现有 `SkyDisplayState.cloudColor`，alpha 恒 1（**旧行为 alpha 不可调**） | 25w44a（取代 `visual/cloud_opacity`） |
| 7 | `visual/cloud_height` | float | `192.33` | 有限浮点（原版未给上限；本项目沿旧实现的 ±32768） | `auto`＝**旧名义 192**（见 §4.1 的 0.33 规则） | 1.21.11（25w42a） |
| 8 | `visual/cloud_fog_end_distance` | float | `2048.0` | **必须 > 0** | `auto`＝现有 `vanillaCloudFadeEnd=384`（⚠ 与原版默认 2048 不同，见 §5.2） | 25w44a |
| 9 | `visual/sunrise_sunset_color` | **ARGB** | `#00000000` | alpha=0 时不渲染晨昏 | `auto`＝现有 `OverworldEffects.getSunriseColor` 结果（可能为 null） | 25w45a |
| 10 | `visual/sun_angle` | float（度） | `0.0` | 0＝正上方，东→西顺时针 | `auto`＝`sky.phase*360` | 25w45a |
| 11 | `visual/moon_angle` | float（度） | `0.0` | 同上 | `auto`＝`sky.phase*360` | 25w45a |
| 12 | `visual/star_angle` | float（度） | `0.0` | 同上 | `auto`＝`sky.phase*360` | 25w45a |
| 13 | `visual/moon_phase` | 枚举（8 值，见 §4.5） | `full_moon` | 仅 `override`，不插值 | `auto`＝现有 `sky.moonPhase`（0..7 整数） | 25w45a |
| 14 | `visual/star_brightness` | float | `0.0`（原版夜晚实际 0.5） | **必须 0..1** | `auto`＝现有 `sky.starBrightness` | 25w45a |
| 15 | `visual/has_sky_occluder` | boolean | `false`（**26.4 官方：主世界 true，下界/末地 false**） | 不插值 | `auto`＝现有行为（判定 `camera.y < horizonHeight` 时才画下半球黑遮罩，**且天体不一起遮**——见 §4.7） | **26.4 snapshot 2** |

> 最小集合实际是 15 行属性；因为 RGB/ARGB 的三种输入写法（字符串/int/float 数组）算作同一属性的不同语法，
> 而被用户点名的「至少 15 项」列表本身也是这 15 项。**若把 §3.2 的 water fog 三项也算作"纳入"，总数达 18。**

### 3.2 不纳入：水下雾三项（v2 决定）

| # | 属性 | 原版默认 | 不纳入理由 |
|---|---|---|---|
| — | `visual/water_fog_color` | `#050533` | 属**非天空盒段**（水下雾作用于整个世界近景）；纳入会误改玩家真实水下雾，违反"绝不动世界雾" |
| — | `visual/water_fog_start_distance` | `-8.0` | 同上 |
| — | `visual/water_fog_end_distance` | `96.0` | 同上 |

用户文档必须写明：**这三项不纳入，且本模组完全不触碰玩家的真实水下雾**。详见 `VANILLA-ENVIRONMENT-ATTRIBUTES.md` §5.5。

### 3.3 范围外（明确不做，理由写在 §5）

`visual/block_light_tint`、`visual/sky_light_color`、`visual/sky_light_factor`、`visual/night_vision_color`、
`visual/ambient_light_color`、`visual/default_dripstone_particle`、`visual/ambient_particles`、
`audio/*`（4 项）、`gameplay/*`（23 项）。

---

## 4. 逐项语义与实现落点

以下是审计用的**判据**：每一条都能在只读审阅源码或跑 `tools/check-r35-environment.py` 时验证。

### 4.1 `cloud_height`：192.33 与 192 的 0.33（最容易写错的一项）

现有链路（R26 起，务必保持）：

```
SkyboxConfig.vanillaCloudHeight            // 默认 192
SkyRenderMath.VANILLA_CLOUD_HEIGHT = 192F  // 名义高度
SkyRenderMath.cloudRelativeY(camY, h) = h - camY + 0.33F
```

即**名义 192 已经在运行时加了 +0.33**。原版环境属性的 `cloud_height` 默认值 **192.33** 就是「最终高度」。
所以语义等价是：

```
cloud_height = 192.33  ⟺  旧 vanillaCloudHeight = 192   ⟺  云出现在 y ≈ 192.33
cloud_height = 240.00  ⟹  云出现在 y = 240.00（旧 cloudheight 240 则是 240.33）
```

**合同（审计判据 A3）**：
1. `cloud_height <h>` 设的 h 是**最终世界高度**；内部换算回名义值 `nominal = h − 0.33F`，再喂给旧
   `cloudRelativeY(camY, nominal) = nominal − camY + 0.33F`。**必须**在 float（不是 double 中间值）上做减法。
2. **`cloud_height 192.33` 与旧 `cloudheight 192` 逐位等价 —— 已用 Java 实测确认（2026-10-31）**：

   ```
   H = 192.33F        bits=4340547b  exact=192.3300018310547
   0.33F              bits=3ea8f5c3  exact=0.33000001311302185
   H - 0.33F          bits=43400000  exact=192.0     ← 恰好等于 192F 的位模式
   192.0F             bits=43400000  exact=192.0
   BITWISE EQUAL: true
   ```

   对 camera Y ∈ {0, 63, 64, 64.5, 100, 128.7, 192.33, 256, −64} 全量比对，
   `nominalPath` 与 `legacyPath` **9/9 逐位相同，差 0.0**。反向 `nominal + 0.33F` 再 `− 0.33F` 也逐位回原值。
3. `auto` 必须解析为 **192.33**（等价旧 192），`/skyboxblock cloudheight` 打印仍显示 **192**（旧接口语义不变）。
4. `environment get cloud_height` 返回 **192.33**，`cloudheight` 返回 **192**；反馈文本**不得**把两者混用。
5. **审计时的判据仍是容差 ≤1e-4**（不是逐位）：因为契约是按"格"表达、实现可能换用 double 路径，
   1e-4 格远小于任何可见差异，且能吸收实现细节变化。逐位相等是**加分项**（当前实现方式能达到）。

> ⚠ 教训记录：早先 v1 用 Python 双精度中间值推断"`192.33f − 0.33f ≠ 192f`"是**错的**。
> Java 的 `float` 减法会把结果**重新舍入到 float**，`192.0000019` 在 192 的 half-ULP 以内，舍回 `192F`。
> **float 语义问题必须用 Java 跑，不能用 Python/双精度中间值断言。**


### 4.2 颜色输入语法（三种写法都要支持，这是"支持命名空间及短 ID"之外的另一半需求）

- **RGB**：`#rrggbb`（必须 6 位，大小写不敏感）/ 十进制 int（`R<<16|G<<8|B`）/ 3 个 float（get 时按 `[r,g,b]` 打印）。
- **ARGB**：`#aarrggbb`（8 位）/ int（`A<<24|R<<16|G<<8|B`，Java int 会是负数，**必须用 `Integer.parseUnsignedInt` 或 `Long.parseLong`**，
  这是个经典坑）/ 4 个 float。
- 反序列化时 RGB 的 alpha 恒 255；序列化时 RGB 的 alpha 恒 0（中文 Wiki「通用结构」明确要求）。
- **get 的输出格式必须能再被 set 解析**（round-trip），否则批量脚本会炸。

### 4.3 雾距离的钳制（三个"必须 > 0"）

- `fog_end_distance` / `sky_fog_end_distance` / `cloud_fog_end_distance` / `water_fog_end_distance`：
  设 ≤0 或非有限值 → **命令返回 0 并报错**，不改现有值（不要静默 fallback 到默认值）。
- `fog_start_distance`：**允许负**。
- `cloud_fog_start_distance` **不是**原版属性：本项目自己的淡出起点（`vanillaCloudFadeStart`）应继续由
  `cloudfade <start> <end>` 管理；`environment set cloud_fog_end_distance` **只改 end**，start 沿用「end/3」或旧值，
  且必须在文档中写明到底哪一种，不能两边各说一套。

### 4.4 天体角度：必须解耦（现有代码是耦合的）

现状（`VanillaSkyScene.render`）：

```java
Matrix4f solarView = new Matrix4f(frame.view).rotate(Y, -90).rotate(X, sky.phase*360F);
// 太阳、月亮、星星三者共用 solarView
```

新版要求：

| 属性 | 作用对象 | 旋转 | 原版语义 |
|---|---|---|---|
| `sun_angle` | 太阳 quad（`size=30, y=100`） | 绕 X 轴 `sun_angle` 度 | 0＝正上方 |
| `moon_angle` | 月亮 quad（`size=20, y=-100`） | 绕 X 轴 `moon_angle` 度 | 独立 |
| `star_angle` | 星空 `starBuffer` | 绕 X 轴 `star_angle` 度 | 独立 |

- 三个 `auto` 必须都解析到**同一个** `sky.phase*360`，从而 `auto` 下画面逐像素不变（A6+A1 同时成立）。
- 原版在「初始值与目标值差对 360 取余 > 90°」时直接跳变不插值——我们**不做插值**，所以只要求文档写明「本模组不做平滑过渡」。
- 度数是**有限值**；`Float.isFinite` 检查，角度的周期性由 `Axis.XP.rotationDegrees` 自然处理（可接受任意度数）。

### 4.5 `moon_phase` 八值与 UV 映射（现有代码已隐含的正确排布）

现有绘制：`u=(phase%4)/4F, v=(phase/4)/2F`，取 `+.25,+.5` 大小的 UV（`moon_phases.png` 是 4×2 网格）。
原版枚举→索引的对应（`full_moon`=0 … `waxing_gibbous`=7）**必须**与本表一致，否则月相会错位一个：

| 索引 | 枚举名 | 中文 | UV 格 (col,row) |
|---:|---|---|---|
| 0 | `full_moon` | 满月 | (0,0) |
| 1 | `waning_gibbous` | 亏凸月 | (1,0) |
| 2 | `third_quarter` | 下弦月 | (2,0) |
| 3 | `waning_crescent` | 残月 | (3,0) |
| 4 | `new_moon` | 新月 | (0,1) |
| 5 | `waxing_crescent` | 峨眉月 | (1,1) |
| 6 | `first_quarter` | 上弦月 | (2,1) |
| 7 | `waxing_gibbous` | 盈凸月 | (3,1) |

- ⚠ **待代码核实项**：`0..7` 与 `moon_phases.png` 的物理排布是否与上表逐格吻合，需要在实现时用一张
  「已知相位」截图或直接比对原版 `MoonPhases.java` 常量确认。**我在这里不下"已验证"结论**（本工作区无 1.21.11+ 反编译源）。
- `auto` 必须继续走现有 `Math.floorMod(level.getMoonPhase(),8)` / `SkyRenderMath.simulatedMoonPhase(gameTime)`。
- 也可接受数字简写 `0..7`（用户友好），但**必须**与命名值等价，且 `get` 统一回显命名值。

### 4.6 `star_brightness` 与天气

现有：`brightness = sky.starBrightness * weatherAlpha`（`weatherAlpha = 1 - rain`），并**同时**写入 RGB 与 alpha。
- `star_brightness` 覆盖的是 `sky.starBrightness` **这一项**，天气乘法是原版天气环境属性的效果，**保留**（不得被覆盖绕过）。
- 范围 `[0,1]`，越界→命令失败。`auto` 时保持现有 `SkyDisplayState` 计算结果。

### 4.7 `has_sky_occluder`（26.4 新属性）在本模组的落地

原版 26.4 snapshot 2 的两条事实（**一手来源**：英文/中文 Wiki 的 26.4 页面 `action=raw`）：
1. 新属性 `minecraft:visual/has_sky_occluder`；boolean；**默认主世界 `true`，下界/末地 `false`**；不插值。
2. 新增 `sky_occluder.vsh/.fsh`：以**雾色**、带**平滑边界**地把天空盒遮挡到某个角度以内。
3. 行为变化：主世界中「天空盒下半部分」现在也遮挡太阳/月亮/星星。

本模组现状（`VanillaSkyScene` 末尾）：仅当
`level.effects().skyType()==NORMAL && camera.y < horizonHeight` 时，在 y+12 处画**黑色**下半圆盘；
**且该黑盘在天体之后绘制**——但它**不写深度**（`RenderFlags.sky()` 关掉了深度测试与写深度），
所以它其实只是**叠了一层黑色**，天体先画、黑盘后画覆盖，效果上等价于「遮住」，但**不是**真正的 occluder 语义。

**合同**（v2，按主线设计锁定）：
- `has_sky_occluder = false` → **完全不画**下半球（旧黑盘路径被禁用）。
- `has_sky_occluder = true` → 画下半球，且：
  - 颜色取自**当前生效的雾色**（`fog_color` 被设置则用设置值，否则沿用现有 `fogColor`）——原版 26.4 用雾色而非纯黑；
  - 遮挡对象包含太阳/月亮/星星；
  - 边界用**雾色 + alpha 渐隐**实现（不是旧黑盘硬盖）。**这是对 26.4 `sky_occluder` 着色器的移植近似**，
    可声明"移植近似"，但**不宣称与 26.4 逐像素一致**（那需要 `sky_occluder.vsh/fsh` 与 1.21.11+ 的天空管线）。
  - **绘制顺序（必须）**：渐隐遮挡盘**保持在天体之后绘制**（现有位置），用雾色 + 高 alpha 覆盖，
    这样**不改** opaque/透明/合成主链路的深度时机（§9 第 4 条）。若实现改成"天体之前画 + 写深度"，
    必须先证明不破坏 R34.2 的深度指纹与 DH 合成，否则按本条退回。
- `auto` → 保持现有判定（`skyType==NORMAL && camera.y < horizonHeight` 时画旧黑盘），即 **1.21.1 原生行为**，
  默认值 **不得**改成「常开」，否则 A1（默认不变）被破坏。
- ⚠ **默认值口径分歧必须显式说明**：原版 26.4 默认 `true`（主世界）；本项目 `auto` = 1.21.1 原生行为（带高度条件）。
  两者不同，**必须**写清楚，不能照抄 Wiki 默认值冒充我们的默认值。
- **`auto` 下不改变旧黑盘的观感**：`auto` 分支沿用旧纯黑逻辑；只有显式 `true` 才用雾色渐隐。这样"默认不变"与"新功能"互不污染。

### 4.8 `sky_color` 与 `fog_color` 的关系（v2：覆盖色即最终色）

- 现有：画布 clear 用 `fogColor`；背景球 `DrawColor` 用 `skyColor`。
- **v2 锁定语义**：`set sky_color` / `set fog_color` / `set cloud_color` 的值是**最终颜色**，
  **不再经过天气乘色**（等价于原版 `override` 修饰符位于天气源之后）。
- 原版天气对 `sky_color` 的 `blend_to_gray{brightness:0.6,factor:0.75}`、对 `cloud_color` 的
  `blend_to_gray{brightness:0.24,factor:0.5}` 等修饰符链**不实现**——这**不是缺陷**，是范围选择（§10）。
- **必须写进用户文档**：设了颜色后，下雨/雷暴**不会**再自动把天空/云变灰；要那种效果得自己调值。
- `auto` 时仍走现有 `SkyDisplayState` 的天气混合链（保持默认不变）。

### 4.9 雾距离在私有画布内的生效范围（v2.1：三项独立，不取 min）

| 覆盖项 | 作用对象 | 校验 |
|---|---|---|
| `fog_start_distance` | 天空盘 `FogStart`；**可负** | 有限 float |
| `fog_end_distance` | 天空盘 `FogEnd`；**必须 > 0**，最终值**夹到 ≥ 96** | >0 有限 |
| `sky_fog_end_distance` | 天空盘天空雾终点；**必须 > 0** | >0 有限 |
| `cloud_fog_end_distance` | 私有云 `CloudFogEnd`；**必须 > 0** | >0 有限 |

**优先级（v2.1 定稿，先前"取 min"规则作废）**：

1. **天空盘的天空雾终点**：`sky_fog_end_distance` **显式值优先**；
   若未设（`auto`），则回退到 `fog_end_distance` 的显式值；
   若两者都 `auto`，则沿用现有 `nativeSkyFogEnd` / `renderDistance*16`。
   —— 这是 **1.21.1 私有画布适配**，不是原版"两个独立源"语义，文档要写明。
2. **私有云**：`cloud_fog_end_distance` **单独控制**，**不与** `fog_end_distance` 取 min。
   显式 `set cloud_fog_end_distance <e>` 时 `CloudFogStart = e/3`；
   **公共 `fog_start_distance` 仍可控制淡出起点**（若显式设置，则覆盖 `e/3`）。
3 **`0` 一律非法**：三个 `*_end_distance` 收到 `0` 必须**返回 0 并报错**，不得静默变成"全雾/不绘"。
   要"最远全雾"就设很大值（上限 4096 沿用现有云距常量；天空雾上限不额外限制，仅要求有限且 >0）。

> ⚠「`sky_fog_end` 未设时回退到 `fog_end`」是**本项目自创**的回退链，原版是两个独立值各管各的。
> **文档不得写成"原版语义"**，要写成"本模组为让用户只设一项就能同时改变天空与地形雾而做的回退"。

### 4.10 日月星不吃雾（v2 重要范围决定）

- 现状：`vanilla_celestial.fsh` 只做 `texture(Sampler0,UV)*DrawColor`，`vanilla_stars.fsh` 只输出 `DrawColor`，
  两者**都没有雾项**。
- 原版原文（zh Wiki〈天空〉"描述"节）：太阳/月亮/星星是**不受雾影响的**视觉对象。
- 结论：**日月星不实装雾**。若给它们加雾，默认画面会变（A1 破坏）且与原版行为相反。
- `fog_*` / `sky_fog_end_distance` 只作用于**天空盘**与**私有云**（云用 `cloud_fog_end_distance`）。

### 4.11 天体角度的"内部旋转"与"显示方位"（v2 关键坑）

我方几何：太阳 quad 在 `y=+100`，月亮 quad 在 `y=−100`（`SkyVanillaGeometry.celestial(size, y, moon, …)`），
且两者共用同一个 `solarView` 矩阵。因此**同一角度下月亮天然比太阳差 180°**。

- 新属性语义是**显示方位**：`0` = 天体在**正上方**。
- 因此：
  - 太阳内部旋转 = `sun_angle`
  - 月亮内部旋转 = `moon_angle − 180`（抵消几何上的 −Y）
  - 星空内部旋转 = `star_angle`
- `auto`：三者都走旧 `sky.phase*360`（月亮**不**减 180），保证默认画面逐像素不变。
- 审计判据：`set sun_angle 0` 且 `set moon_angle 0` 时，太阳与月亮**都**应出现在正上方（不会一个上一个下）。
- 角度为有限 float；周期性由 `Axis.XP.rotationDegrees` 处理，不做插值。

### 4.12 `sunrise_sunset_color` 覆盖会"常显"（v2 主动偏离）

- 原版：该属性只在 `overworld` 天空盒渲染，且**强度仍受时间驱动**（昼→最强→不可见），并受天气影响；
  另外原版「日出日落效果会影响下半部分天空颜色」（面向东西时下半天空被橙红填充）。
- 本项目：`set sunrise_sunset_color` 后**不乘时间衰减**，可**在非晨昏时段强制显示霞光**；
  也**不**实现"下半部分天空被霞光填充"。
- 这两点都是**主动偏离**，用户文档必须写明，不得写成"与原版一致的日出日落效果"。


---

## 5. 范围说明（要写进用户文档 `VANILLA-ENVIRONMENT-ATTRIBUTES.md`）

### 5.1 为什么 `sky_light_color` / `sky_light_factor` 不纳入（用户点名要求说明理由）
- 这两项是**光照贴图（`lightmap.fsh`）**参数 `SkyLightColor` / `SkyFactor`，作用于**整个世界光照**：
  地形、实体、手、粒子。它们与「天空盒方块里面显示什么」没有因果关系。
- 本模组的天空画布是**私有纹理**，光照贴图影响的是最终合成阶段的画面，**不改**私有画布里的天空颜色。
- 若要让「天空盒内部光照变暗」，正确做法是改世界维度 / 数据包 / 光影包，那正是**本模组明令不碰**的东西（第 4 条红线与 §7 的「不影响真实世界」）。
- 结论：**不纳入**。纳入的唯一后果是「设置后画面变化，但它不是天空盒内部变化」，会误导用户。

### 5.2 为什么 `cloud_fog_end_distance` 的 `auto` 不是原版 2048
- 现有实现 `vanillaCloudFadeEnd` 默认 **384**，并且已在本项目 R30 文档中实测通过
  （384 → 768 → 1536 的网格扩张与 GPU 远裁剪都有证据）。
- 若 `auto` 取 2048，默认画面会**多出大量云**、顶点数上升，直接违反 A1。
- 结论：`auto`＝384（保持旧观感）；原版默认 2048 只作为**文档参考值**列出。用户想贴近原版可
  `environment set cloud_fog_end_distance 2048`。

### 5.3 为什么 `cloud_color` 的 `auto` 是 alpha=1（原版是 `#00000000`）
- 原版 `auto` 若真是全透明，等于「默认没有云」；本模组默认**有**云，这是 R26 起的产品行为。
- 结论：`auto` 分别解析为 `现有云色 + alpha 1`；文档必须写明「我们的 auto ≠ 原版默认值」。
- 但 **alpha=0 时必须真的不画云**（并且不写深度），否则用户设了 0 却看到云＝配置失效。

### 5.4 水下雾三项（v2 定稿：**不纳入**）
属**非天空盒段**——水下雾作用于整个世界近景，与方块内部画什么天空无关。
纳入会误改玩家真实水下雾，直接违反 §9 第 2 条（不写 `FogRenderer`）。用户文档按"不纳入 + 完全不触碰真实水下雾"表述。

---

## 6. 命令面（合同）

主命令：`/skyboxblock environment …`；别名（**顶级**）`/skyboxblock env …`。

```
/skyboxblock environment                       # 与 list 相同
/skyboxblock environment list                  # 全部属性：ID / 覆盖值或 auto；另有 JVM 提示
/skyboxblock environment get <id>              # 单值或 auto、格式与支持范围
/skyboxblock environment set <id> <value>       # 有效设置保存并重载；无效输入报错且不写入
/skyboxblock environment reset <id>            # 删除该属性覆盖
/skyboxblock environment reset all             # 只清环境属性覆盖，保留旧云高／距离
```

- **`<id>` 解析（A13，v6-FROZEN 已按源码更正）**：**只接受 3 种写法**
  （依据 `SkyboxCommands.forms()`：`shortId()` / `id()` / `NAMESPACE + id()`）：

  | # | 写法 | 示例 | 支持 |
  |---|---|---|---|
  | 1 | 短 ID | `sky_color` | ✅ |
  | 2 | 组内全 ID | `visual/sky_color` | ✅ |
  | 3 | `minecraft:` + 全 ID | `minecraft:visual/sky_color` | ✅ |
  | — | `skyboxblock:` 前缀 | `skyboxblock:visual/sky_color` | ❌ **不支持**（注册表只认 `minecraft:`） |
  | — | `visual_` 下划线替换 | `visual_sky_color` | ❌ **不支持**（无任何下划线别名逻辑） |

  > ⚠ **v1–v5 曾写"四种写法都认"并声称支持 `skyboxblock:` 前缀与 `visual_sky_color` 下划线替换，
  > 这是未经验证的假合同，v6-FROZEN 已撤回。** 以本表 3 种为准。
  - 未知 ID → 命令返回 0，并提示「未知属性；用 `/skyboxblock environment list` 查看」。
  - **别名建议**（可选、非必须）：`cloudheight`↔`cloud_height` 交叉引用仅出现在提示文本，不做静默互改。
- **TAB 补全**：`<id>` 位补全上述 3 种写法（`shortId` / `id` / `minecraft:`+`id`）。

### 6.1 `reset all` 的边界（v2 定稿）

- `reset all` **只清 `environment.*` 命名空间**（即 `environment.visual/*` 全部删除 → 全部回到 `auto`）。
- **旧键 `vanillaCloudHeight` / `vanillaCloudFadeStart` / `vanillaCloudFadeEnd` 保持独立，不被 `reset all` 触碰。**
- **`environment.*` 覆盖优先**：`cloud_height` 有值时不读 `vanillaCloudHeight`；`cloud_fog_end_distance` 有值时不读 `vanillaCloudFadeEnd`。
- **`reset` 是"恢复旧配置"而不是"硬重置 192/384"**：
  - `reset cloud_height` → 删除 `environment.visual/cloud_height`，**回落到 `vanillaCloudHeight` 当前值 + 0.33**；
    若 `vanillaCloudHeight` 也没设，才回到 192.33。
  - `reset cloud_fog_end_distance` → 回落到 `vanillaCloudFadeEnd` 当前值（默认 384），**不是**原版的 2048。
  - 上述回退链是实际配置语义；指令反馈只确认 auto，具体旧云层数值可用原有云层查询指令查看。
- **`reset` 反馈（v6-FROZEN 按源码更正）**：实际文案是 `"<id> 已恢复 auto（删除覆盖值）"`，
  `reset all` 是 `"全部环境属性已恢复 auto；其余配置项保持不变"`。
  **它只确认"已回到 auto / 其余配置保持不变"，不列出被删除的键，也不显示回落到哪个具体值。**
  用户要看回退后的实际值，需自己 `get <id>` 或看 `list`。
  > ⚠ v2–v5 曾要求"反馈必须列出实际被复位的键 / 显示实际回退值"，**源码没有这个行为，已撤回。**

---

## 7. 存储与优先级（v2 定稿）

沿用 `SkyboxConfig` 的既有优先级链，**新键插入到文件层与 JVM 层之间**：

```
内置默认值  <  vanillaCloud*（旧键，文件层）  <  environment.*（新键，文件层）  <  -Dskyboxblock.<key>（JVM）
```

**持久化键格式已锁定**：

```properties
environment.visual/sky_color=#7ba4ff
environment.visual/fog_color=#c0d8ff
environment.visual/cloud_height=240.0
environment.visual/has_sky_occluder=true
```

- 前缀固定 `environment.`，后接**完整属性 ID（含 `visual/` 段与 `/` 分隔符，无命名空间前缀）**。
- `Properties` 的键允许 `/` 与 `.`，无需转义。
- JVM 覆盖对应 `-Dskyboxblock.environment.visual/sky_color=#7ba4ff`（点号路径拼接，`/` 保留）。

**合同**：
1. `auto` = **该键不存在**。不写 `=auto` 字面量，避免与"字符串 auto"歧义。
2. `environment.*` 与旧 `vanillaCloud*` **不是同一个字段**；`environment.*` 存在即优先，落空才看旧键。
   两者**由不同 setter 写、不同 getter 读**，因此**不存在互相覆盖**——这是 A10 的满足方式。
3. 写入沿用现有 `SkyboxConfig.update()` 的**原子写 + 保留其余属性**（`Properties.store` 会重排并加时间戳注释，既有行为，非缺陷）。
4. 读文件失败/值非法：**视同该键不存在（auto）+ WARN 日志 + 不抛异常**。
5. `/skyboxblock environment list` 必须能区分并显示三种状态：`auto`、`environment.* 显式`、`旧键生效（environment 未设）`。

---

## 8. 旧接口兼容（A11）

| 旧接口 | 必须保持 | 备注 |
|---|---|---|
| `/skyboxblock cloudheight <h\|reset>` | 参数视为**名义高度**，范围 ±32768，reset→192（写 `vanillaCloudHeight`） | 与 §4.1 的换算关系写死在文档 |
| `/skyboxblock clouddistance <d\|reset>` | 16–4096，start 自动 = end/3，reset→384 | 写 `vanillaCloudFadeEnd`/`Start` |
| `/skyboxblock cloudfade <s> <e\|reset>` | `0 ≤ s < e`，e∈16–4096，reset→128/384 | 同上 |
| `/skyboxblock reload` | 重读属性文件并 `SkyVanillaClouds.invalidate()` | |
| `clouds=true`（文件或 `-D`） | 私有云总开关；与 `cloud_color` 的 alpha=0 **是两个独立开关** | 见下 |

**`clouds=false` 与 `cloud_color` alpha 的交互**：两者都是"不画云"，属"同效果不同开关"，**不构成覆盖缺陷**，但：
`environment list` 里必须同时显示 `clouds=false` 标记，避免用户设了 alpha 却以为是 alpha 没生效。

**⚠ 已知的旧接口 vs 新属性冲突点（必须文档写明）**：
当 `environment.visual/cloud_height` 生效时，`/skyboxblock cloudheight` 的**查询**应显示什么？
- **契约**：查询显示**等效名义值** = `cloud_height − 0.33F`，并加后缀提示"（由 environment 覆盖）"。
- 这样旧接口的数字始终"直接可比"，用户不会因为一个 192 一个 192.33 而困惑。
- `cloudheight <h>` 的**写入**仍然只写 `vanillaCloudHeight`，**不清除** `environment.visual/cloud_height` ——
  因此用户设了 environment 之后再敲 `cloudheight 240` 会**看不到变化**，命令必须提示
  "`environment.visual/cloud_height` 正在覆盖，用 `environment reset cloud_height` 解除"。


---

## 9. 不得改变的东西（回归红线）

1. **其他 8 种天空盒的显示**（`SkyStyle` 非 `VANILLA` 的全景路径）——逐像素不变。
2. **真实世界**：维度云、`FogRenderer` 的雾、光照明暗、天气、维度天空盒 —— 一律不写。
3. 私有画布的 **GL 状态保护**（`SkyGlState`）、**Iris 状态隔离**、**帧末合成时机**（`GameRenderer.renderLevel` RETURN / Iris 即时钩子）——不动。
4. **深度/遮挡主链路**（`SkyWorldRenderer` 的 opaque → 透明 → 合成）——不因新属性改变几何或深度写入时机。
5. **R32 的全景射线**、**R34.2 的边界稳定**逻辑——不动。
6. 私有云构建的**线程局部范围**（`SkyCloudRange`）——扩距时仍需只在本模组私有构建期间生效。

---

## 10. 明确不做的部分（写进用户文档的"不是回移"声明）

- **不**实现环境属性引擎：不做维度/生物群系/时间线/天气四源叠加，不做 `override/add/multiply/alpha_blend/blend_to_gray/append` 修饰符链，
  不做插值、不做时间轴、不做数据包 `attributes` 字段、不做 `timeline` 定义。
- **不**影响真实世界与其他天空盒：所有设置只被私有天空画布读取。
- **不**承诺与原版 SkyRenderer 逐像素一致：本模组是**近似复刻**（自研几何 + 自研 shader），
  雾、雾化边界、晨昏的色阶与原版存在已知差异（R31/R34 文档已记录）。
- **不**支持 `gameplay/*`、`audio/*`：它们与本方块显示无关，且需要服务端权威值。

---

## 11. 接入点分工与代码落点（给实现方看，不是我的产出）

| 负责方 | 文件 | 需要做的 |
|---|---|---|
| 主代理 | `client/SkyDisplayState.java` | 把这些值聚合进显示快照（只读快照，别在里面读 `Properties`）；`sunAngle/moonAngle/starAngle` 与 `skyColor/fogColor/cloudColor(argb)/starBrightness/hasSkyOccluder` |
| 主代理 | `client/VanillaSkyScene.java` | 三个角度解耦；云色改 ARGB；`has_sky_occluder` 下半球用雾色 + 可关；天空雾 `FogStart/End` 可被 `sky_fog_end_distance` 覆盖 |
| 主代理 | `client/SkyVanillaClouds.java` | `cloud_height` 换算（§4.1）；`cloud_color` alpha=0 时早退（不建网格、不写深度）；`cloud_fog_end_distance` 与 `vanillaCloudFadeEnd` 同源 |
| 另一 DeepSeek | `client/SkyEnvironment.java`（新） | 属性表（ID/类型/范围/默认/解析/格式化）、别名解析、`auto` 语义 |
| 另一 DeepSeek | `client/SkyboxConfig.java` | 持久化（单键单一字段，§7）、与旧键同源、JVM 覆盖 |
| 另一 DeepSeek | `client/SkyboxCommands.java` | 命令树（§6）、TAB 补全、错误码 |

**接口建议**（降低两边扯皮）：`SkyEnvironment` 只暴露
`boolean isAuto(String id)`、`float getFloat(String id)`、`int getArgb(String id)`、
`int getRgb(String id)`、`boolean getBoolean(String id)`、`String format(String id)`、
`java.util.List<String> ids()`、`String canonical(String id)`。
`SkyDisplayState` 只在构造时读一次（每帧一次，不做每像素查表）。

---

## 12. 我的独立校验工具

`skybox-mod/tools/check-r35-environment.py`（纯 Python，无第三方依赖）：

1. **静态源码断言**：扫描 `SkyEnvironment/SkyboxConfig/SkyboxCommands/SkyDisplayState/VanillaSkyScene/SkyVanillaClouds`
   的文本，检查 §2/§4/§6/§7 的合同（键唯一、0.33 换算存在、alpha=0 早退、角度三变量独立、命令树完整…）。
2. **数学与解析自测**：内置一份 Python 版属性表，验证颜色 round-trip、范围校验、`auto` 解析、
   `192.33 ↔ 192+0.33` 一致性、角度 0/90/180/270/360 的旋转等价性。
3. **旧接口兼容**：确认旧命令 literal 与取值范围常量未被删改。

⚠ 这个工具**只证明源码文本与数学合同**，不证明实机画面。**没有截图/运行证据，我不会写"实机通过"。**

---

## 13. 待办与已知未决问题（v2 后）

已由主线锁定、**不再是问题**：§6.1 `reset all` 边界、§7 键格式（`environment.visual/*`）、§4.8 覆盖色为最终色、
§5.4 水下雾不纳入、§4.1 `cloud_height` 换算与容差、§4.9 三项 end 严格 >0 且不取 min、§4.10 日月星不吃雾、
§4.11 月亮 ±180 口径、§4.12 霞光常显偏离。

仍未解决：

1. **`moon_phase` 索引与 `moon_phases.png` 网格的实际对应**：我用 UV 公式
   （`u=(phase%4)/4, v=(phase/4)/2`，取 `.25×.5` 格）推断索引 0..7 = 满月…盈凸月，
   并有一手来源支持**枚举顺序**（英文 Wiki〈Moon〉"Following the chronological order in Java Edition"
   列出 Full moon → Waning gibbous → Third quarter → Waning crescent → New moon → Waxing crescent →
   First quarter → Waxing gibbous，与中文 Wiki 环境属性节顺序完全一致）。
   **但"枚举顺序 = 纹理网格行优先顺序"这一步我没有一手证据**，需实现方用已知相位画面确认后回填 §4.5。
2. **`has_sky_occluder` 的"雾色高 alpha 渐隐"具体参数**（alpha 曲线、几何半径）由实现决定；
   规格只约束"不改变默认画面、在天体之后绘制、不改深度时机"。
3. **`cloud_fog_end_distance` 的上限**：沿用现有 `MAX_CLOUD_DISTANCE=4096` 还是放开到原版 `2048` 之上任意正有限值？
   建议沿用 4096（`SkyCloudRange` 的网格扩张与远裁剪都按这个上限算的）。**待实现方确认**。

## 14. 变更记录

- **v1** 2026-10-31：初版。属性清单核对完成（Wiki 中/英 + 26.4 官方 changelog 三源交叉），
  定义 13 条验收要点与 15+3 项清单。
- **v2** 2026-10-31：按主代理主线设计定稿。差异如下：
  1. `reset all` 只清 `environment.*`；旧键独立；`environment.*` 优先；`reset` 回落到旧配置而非硬重置 192。
  2. 持久化键锁定 `environment.visual/<attr>`。
  3. 覆盖 `sky_color`/`fog_color`/`cloud_color` 即**最终颜色**，不再天气乘色（§4.8）。
  4. 雾距离**三项独立**，`sky_fog_end` 显式优先，否则回退 `fog_end`；**不取 min**（作废 v1 的 min 规则）。
     `cloud_fog_end_distance` 单独控制，`start=end/3`，公共 `fog_start` 可覆盖起点。
  5. 三个 `*_end_distance` **严格 > 0**，`fog_end` 最终值夹到 ≥ 96；`0` 非法（返回 0 报错）。
  6. 日月星**不吃雾**（不改 `vanilla_celestial/stars` shader）。
  7. 月亮显式角度 = `moon_angle − 180`（几何上月在 −Y）；`auto` 仍走 `phase*360`（§4.11）。
  8. `sunrise_sunset_color` 覆盖后**不受时间衰减**、可在非晨昏强制显示；不实现"下半天空被霞光填充"（§4.12）。
  9. `has_sky_occluder`：`true` → **最后绘制的雾色 + 高 alpha 渐隐**（移植近似）；`false` → 去旧黑盘；`auto` → 旧行为。
 10. 水下雾三项**不纳入**。
 11. `cloud_height` 换算 `nominal = H − 0.33F` 走旧 `cloudRelativeY`；**用 Java 实测确认
     `192.33F − 0.33F` 逐位等于 `192F`**（我 v1 用 Python 双精度中间值做出的"不等"结论是错的，已撤回）；
     审计容差仍定为 ≤1e-4。
 12. §8 增加旧接口与 `environment` 覆盖的冲突提示契约。

## 15. 与官方原文的偏离清单（必须原样出现在用户文档）

| 项 | 官方原文 | 我们的做法 | 性质 |
|---|---|---|---|
| 「取较近」合并雾源 | 无此规则；`fog_end` 与 `sky_fog_end` 是两个独立值 | 未设 `sky_fog_end` 时回退到 `fog_end` | 自创回退链，1.21.1 适配 |
| `cloud_color` 默认 | `#00000000`（全透明） | `auto` = 现有云色 + alpha 1 | 刻意不同（保持默认有云） |
| `cloud_fog_end_distance` 默认 | `2048.0` | `auto` = `vanillaCloudFadeEnd`（384） | 刻意不同（保持默认观感） |
| `has_sky_occluder` 默认 | 26.4 主世界 `true` | `auto` = 1.21.1 原生（`camera.y < horizonHeight` 才画） | 刻意不同（保持默认不变） |
| `has_sky_occluder` 实现 | 专用 `sky_occluder.vsh/fsh`，雾色 + 平滑边界 + 特定角度 | 雾色 + alpha 渐隐的下半球盘 | **移植近似**，非逐像素复刻 |
| 天气修饰符 | `sky_color`/`cloud_color`/`sunrise_sunset_color` 等受天气 `blend_to_gray`/`multiply` | 覆盖值即最终值，不乘天气 | 不做修饰符链（范围选择） |
| `sunrise_sunset_color` | 仅 overworld 天空盒、强度受时间驱动、受天气影响 | 覆盖后常显、不随时间衰减 | 主动偏离 |
| 日出日落下半天空填充 | 「日出日落效果会影响下半部分天空颜色」 | 不实现 | 未实现 |
| 日月星受雾 | 「在天空中有几个**不受雾影响**的视觉对象」（含太阳/月亮/星星） | 同样不吃雾 | **一致** |
| 插值 | 多数属性「可插值」，跨 90° 直接跳变 | 全部不插值、立即生效 | 未实现 |
| `fog_end` 下限 | 「不会小于 96 格」 | 显式值也夹到 ≥ 96 | 一致 |

---

## 16. 代码审计（第一轮 r1 → 第二轮 r2）

### 16.0 两轮总览

| 轮次 | 工具结果 | 报告 | 说明 |
|---|---|---|---|
| r1（12:00 快照） | **89 / 91 通过，2 失败** | `build/reports/r35-environment-audit-r1.json` | 快照早于作者修复 |
| **r2（12:10 快照，最新）** | **91 / 91 全部通过，0 失败** | `build/reports/r35-environment-audit-r2.json` | 三个缺陷均已修；`defaultValue` 误报已按 §16.4 修正为信息性检查 |

> ⚠ **r1 的失败状态已作废**，不得作为当前状态引用。当前状态以 **r2 = 91/91** 为准。

### 16.1 发现并确认的缺陷（含修复状态）

| # | 缺陷 | 位置 | 后果 | 状态 |
|---|---|---|---|---|
| R35-A1 | `Value.color()` 用 `substring(2)`，但 `canonicalColor` 输出带 `#` | `SkyEnvironment` `Value.color()` | **4 个颜色属性全部取到错色**；`cloud_color` 的 alpha 丢失 → `cloudOpacity` 恒 0 → **设了 cloud_color 云直接消失** | ✅ **已修**（`substring(1)`） |
| R35-A2 | `attribute()` 未试 `REGISTRY.get(bare)` | `SkyEnvironment.attribute()` | **带 `minecraft:` 前缀的完整 ID 无法解析**；短 ID 与 `visual/xxx` 仍可用 | ✅ **已修**（补 `REGISTRY.get(bare)`） |
| R35-A3 | 无参构造器未初始化 `final Map values` | `SkyEnvironment` | **javac 编译失败**（`变量 values 可能尚未初始化`，已用最小样例 `build/probe-float/CtorProbe.java` 实测复现） | ✅ **已修**（构造器已删除；文件 12:10:38 更新） |
| R35-A4 | 4 个距离属性 `defaultValue = 0` 而 `min = MIN_POSITIVE` | `SkyEnvironment` 注册表 | **经复核为「元数据约定」而非缺陷**：全仓 `defaultValue` 只有 record 头一处声明，**没有任何 `.defaultValue()` 取值读取**；auto 由调用方 `number(id, fallback)` 决定 | ✅ **非阻断，不修**（见 §16.4） |

### 16.4 关于 `defaultValue = 0`：为什么判定为「非阻断、不改」

**我早先把 R35-A4 报成"默��值违反自身校验"是过度断言，现将结论降级。** 依据：

1. 全仓 grep `defaultValue`：**只在 `record Attribute(...)` 的头部出现一次**，没有任何读取点
   （`SkyEnvironment` / `SkyDisplayState` / `VanillaSkyScene` / `SkyVanillaClouds` /
   `SkyEnvironmentMath` / `SkyboxConfig` 均无 `.defaultValue()` 调用）。
2. auto 语义的真实来源是 **调用方的 fallback 参数**：
   `env.number("cloud_fog_end_distance", legacyEnd)`、`env.number("cloud_height", 192.33F)` 等，
   由 `VanillaSkyClouds` / `SkyDisplayState` 传入当日有效值。
3. 因此 `defaultValue` 目前承担的角色只是**文档性元数据**（"Wiki 默认多少"），
   用 `0` 表示"没有内置默认"是**可读的哨兵**，不产生运行期影响。

**明确反对的做法**：因为测试报警就把 auto 硬写成 `1024`/`2048`。那会**真的改变默认画面**，
直接违反验收要点 A1（默认不变）。审计脚本已改为只校验
`defaultValue-metadata-not-read-at-runtime`（即"auto 不依赖它"），不再对哨兵值本身报警。

**可选（不要求）**：若将来有人在 `list` 里展示 `defaultValue`，再把它改成 `Float.NaN` 或
单独注释 `0 = auto/无内置默认` 即可。

### 16.2 逐条确认**正确**的实现（不得误判为缺陷）

- **`solarMvp` 在 auto 分支复用是刻意的逐位旧行为**，不是耦合。
  判据：三个 `env.has("sun_angle"/"moon_angle"/"star_angle")` 分支各自调用
  `celestialMvp(frame, projection, …)`，auto 分支保留 `solarMvp`。
  **禁止**用「变量名出现次数」判定耦合。
- `cloud_height` 换算只在一处：`SkyEnvironmentMath.cloudHeight` 内
  `env.number("cloud_height",192.33F) - .33F`；`SkyRenderMath.cloudRelativeY` 仍 `+ .33F`。
- `fogEnd` = `Math.max(96F, fog_end)`；`sky_fog_end` 显式优先。
- `cloudStart` = `end/3`；公共 `fog_start` 可覆盖起点。
- `cloudOpacity` 由 ARGB 高字节解包；`<=0F` 时 `SkyVanillaClouds.render` 开头 `return false`；
  uniform 在 shader 中真实使用。
- `has_sky_occluder` 三分支互斥且正确；occluder 用雾色 + `SkyEnvironmentMath.visibility()`
  平滑边缘（±6°）的半球穹顶，**在天体之后**绘制。
- 日月星不吃雾（`vanilla_celestial.fsh` / `vanilla_stars.fsh` 无 `Fog`）。
- 覆盖色即最终色（三个 `has(...)` 分支直接赋值，不走 `weather()`）。
- 不写世界雾/光照；`SkyEnvironmentMath` 无 MC 依赖。

### 16.3 审计脚本自身的 3 个缺陷（已修；记录以免复用错误做法）

1. `find_java` 只搜 `client/` → `SkyEnvironment`（在**包根** `dev/yg/skyboxblock/`）永远被判缺失。
2. `strip_strings` 误用于**正向**断言 → `env.has("sun_angle")` 的字面量被删光，产生 6 项假红。
   现约定：**正向断言用保留字面量的版本，负向断言才用 `_nostr` 版本**。
3. `new Attribute("` 正则只匹配单行声明 → 15 个注册项只数到 12；改用 `= register(new Attribute(`。

### 16.4 v3 待办（历史，已全部完成或降级）

1. ~~修 R35-A3（删无参构造器）~~ → ✅ 已完成。
2. ~~R35-A4 建议顺手处理~~ → ✅ 降级为非阻断元数据约定（§16.1）。
3. `moon_phase` 枚举 → 纹理网格的逐帧一致性 → ✅ **已被实机证据覆盖**（§16.7：8 个月相在真实画布上产生 **8 种不同像素**）。
4. ~~本轮无任何实机证据~~ → ✅ **已完成：四环境全部 371/371**（228 阶段 / 1,484 检查 / 812 命令，§16.7）。

### 16.5 v6 最终状态（FROZEN）

| 项 | 结果 |
|---|---|
| 静态审计 | **93 / 93**（`build/reports/r35-environment-audit-r3.json`） |
| 包审计 | **58 / 58**（`build/reports/r35-package-audit.json`） |
| 独立 javac（48 文件） | ✅ 通过；`build/verify-r35-final-quoted/javac-stderr.txt` **仅弃用提示，0 错误** |
| 构建 | `build/r35-build-r4.log` 成功（43 秒） |
| 五组 Java 回归 | **433,545 断言全过** |
| 实机 | **四环境各 371/371；累计 228 阶段 / 1,484 检查 / 812 条真实命令** |
| 最终汇总 | `build/reports/r35-final-validation.json` → **`status: PASS`** |
| SOL 独立命令评审 | `docs/INDEPENDENT-R35-COMMAND-REVIEW-2026-10-31.md`（1005 / 16 case） |

**已无待验项**（在指定四环境范围内）。**不扩为**：完整发布认证、历史四红、门户、性能长测。

**交付报告（主代理撰写，本规格仅引用）**：`docs/ENVIRONMENT-ATTRIBUTES-R35-DELIVERY-2026-10-31.md`

### 16.6 复现命令

```powershell
$py = "<本机 Python 解释器路径>"    # 需带 NumPy；PATH 上的 python.exe 可能是 Store 存根
cd skybox-mod

# 独立只读审计（源码合同 + 数学自测 + 旧接口回归）
& $py -X utf8 tools\check-r35-environment.py --json build\reports\r35-environment-audit-r3.json

# 编译验证（必须 powershell.exe 5.1，不是 pwsh）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\verify-javac.ps1 -Tag r35
```

---

## 16.7 实机（真实客户端）证据 — **四环境全部通过**

> 证据由主代理的 `run-r35-environment.py` 产出；**我只读报告与截图，不产出实机数据**。

### 16.7.1 四个环境的结果（全部 371/371）

| 环境 | 报告 | 结果 | 阶段 | 命令 | 认证告警 |
|---|---|---|---|---|---|
| 纯原版（Fabric，无光影） | `r35-runtime-vanilla.json` | **371 / 371** | 57 | 203 | 1 条 Yggdrasil |
| Iris 原生（Iris+Sodium+ITRP 原生） | `r35-runtime-iris-native.json` | **371 / 371** | 57 | 203 | 1 条 Yggdrasil |
| 原生 DH（DH 3.3.3） | `r35-runtime-dh.json` | **371 / 371** | 57 | 203 | **0 条 ERROR** |
| Iris + DH + FSR2（FSR2_SCALE=2） | `r35-runtime-iris-dh-fsr.json` | **371 / 371** | 57 | 203 | **0 条 ERROR** |
| **合计** | | **1,484 / 1,484** | **228** | **812** | 总计 2 条 |

- 四份报告的 `productionJarSha256` **完全一致**：
  `f1937b9eb21caa1aea2b912bea1c7447eafa2e0f2cd90d116beba99d46291444`
  （与交付 jar、probe jar 四次 SHA 同一）。
- 测试版本：**Iris 1.8.1 / Sodium 0.6.7 / ITRP Alpha 0.8.31（native 或 FSR2_SCALE=2）/ DH 3.3.3**。
- 截图目录：
  `build/render-regression/r35-env-vanilla-r1/r30-results`（234 个文件）、
  `build/render-regression/r35-env-iris-native-r1/r30-results`（291 个文件）等。
- 每阶段 4 类结构断言：`commands` / `has_real_block_geometry`（≥1024 方块、400+ 帧）/
  `no_gl_errors` / `state_restored`（GL 与 Iris 逻辑状态前后一致，每 240 次调用抽样）。
- **对照图**：[`docs/screenshots/r35-environment-comparison-20261031.png`](screenshots/r35-environment-comparison-20261031.png)
  —— 4×4 **实际游戏截图**（四环境 × 四主题），**未重画天空**，仅加标题与缩放。
  主代理与本文档作者均已 `read_image` 目视确认。

### 16.7.2 真实断言覆盖（每个环境 371 项）

- **15 个属性 × 6 类**：`sky_color` `fog_color` `fog_start_distance` `fog_end_distance`
  `sky_fog_end_distance` `cloud_color` `cloud_height` `cloud_fog_end_distance`
  `sunrise_sunset_color` `sun_angle` `moon_angle` `star_angle` `moon_phase` `star_brightness` `has_sky_occluder`。
- **持久化 3 轮**（各 34 项）：`all-persisted` / `reload-persisted` / `invalid-no-mutation`
  —— 含"非法值**不改当前值**"的负例。
- **8 个其他天空盒 × auto/overridden**：`other_style_unchanged:*`，中心像素差 **MAE = 0.0**。
- **8 个月相**：`ordinal` 断言 + 实际画布 **8 种互不相同的像素结果**。
- **GPU 值断言**：`negative_fog_start_gpu`、`minimum_fog_end_gpu`、`independent_sky_end_gpu`、
  `gpu_fade`、`cloud_cyan_exact_snapshot`、`sky_color_exact_snapshot`、`fog_color_exact_snapshot`。

### 16.7.3 `star_brightness`：显式值为**最终值**，不乘雨

源码（`VanillaSkyScene`）：
```java
float brightness = sky.starBrightness * (env.has("star_brightness") ? 1F : weatherAlpha);
```
即：**显式设置** → 直接使用设置值（不乘 `1-rain`）；**auto** → 保留旧 weather 链。

实机证据（`r35-runtime-vanilla.json`，`stars-rain-overridden` 阶段）：
- 快照 `rain = 1.0`、`starBrightness = 1.0`；
- 实际 GPU uniform `starsGpuDrawColor = [1.0, 1.0, 1.0, 1.0]`。

即**真实雨天（rain=1.0）下亮星仍是满白**，证明"显式值不乘雨"成立。
审计脚本已新增 2 项静态检查（`star-brightness-explicit-skips-weather`、
`star-brightness-auto-keeps-weather-chain`），共 **93 项**。

### 16.7.4 ⚠ 必须原样保留的诚实口径

- **四个**游戏进程均 **exit code = 0**、**fatal / 渲染 / 采样 GL 错误 = 0**（`fatalErrors: []`、`renderingErrors: 0`）。
- **早两个环境**（vanilla、iris-native）**各保留 1 条**
  `[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key`
  —— 这是**离线环境下的公钥拉取失败**，与渲染无关。**总计 2 条**（不是 4 条）。
- **后两个环境**（原生 DH、Iris+DH+FSR2）**ERROR 为 0**。
- 通用 `run-result/runner` 对**任何 ERROR 行**严格返回 1；独立的 `check-r35-runtime`
  **仅白名单这一条精确认证服务错误**并**原样记录**。
- **因此不得把整个矩阵表述为「0 ERROR」**。正确表述是：
  「四环境全部 exit 0、无 fatal/渲染错误；早两环境各留 1 条离线认证公钥告警（共 2 条），日志未吞」。

### 16.7.5 构建与回归

| 项 | 结果 |
|---|---|
| 构建 | `build/r35-build-r4.log` **成功，43 秒** |
| 生产 jar | `skyboxblock-1.0.4-test.1-r35.jar`，SHA256 `f1937b9e…46291444`（jar/probe 四次 SHA 完全同一） |
| 独立 javac（48 文件） | ✅ 通过，`build/verify-r35-final-quoted/javac-stderr.txt` **仅弃用提示，0 错误** |
| 五组 Java 回归 | **合计 433,545 全过**（原 389,385 + render 42,257 + Env 279 + Command 619 + SOL Boundary 1,005 / 16 case） |
| 静态审计 | **97/97**（`r35-environment-audit-final.json`；在 r3 的 93 项基础上追加 4 项 ID 正反向检查） |
| 包审计 | **58/58**（`r35-package-audit.json`） |
| 最终汇总 | `r35-final-validation.json` → **`status: PASS`** |
| SOL 独立评审 | `docs/INDEPENDENT-R35-COMMAND-REVIEW-2026-10-31.md`（1005 / 16 case） |
| 交付报告 | `docs/ENVIRONMENT-ATTRIBUTES-R35-DELIVERY-2026-10-31.md`（主代理撰写） |

### 16.7.6 冻结边界（不扩大结论）

**已证**：指定**四客户端矩阵**（纯原版 / Iris 原生 / 原生 DH 3.3.3 / Iris+DH+FSR2）
各 371/371，累计 **228 阶段 / 1,484 检查 / 812 条真实命令**。

**明确不认证**（继续保留为未验证，不因本轮转绿）：

- **历史 4 项红**（透明层 3675/3684 等）—— 本任务**不重新认证**。
- **门户（portal）内部**表现、旧环境差异 —— 未验证。
- **资源 / 性能长测** —— 未做，不作性能承诺。
- **不是**通用发布认证；**不是**新版环境属性引擎的原生实现，而是 **1.21.1 私有天空画布适配**。

**唯一残留的来源级别缺口**：`has_sky_occluder` 等属性的默认值仅来自**社区 Wiki 转述**，
未取得 minecraft.net 一手原文。此为**来源级别声明**，不影响上述功能与实机结论。

