# 原版天空盒方块：环境属性配置

适用：本模组的**原版天空盒方块**（ID `skyboxblock:sky_block`，显示名「天空盒方块（原版）」）。
> **注意**：这里的「原版」指**本方块内部显示的是原版风格的天空**，不是世界的真实天空。

> 🔧 **R36 起命令表与生效范围都已变化**：配置改为**服务端权威**（一个世界一份，改动同步给所有玩家，
> 变更需权限等级 2），根目录 `cloudheight` / `clouddistance` / `cloudfade` / `reload` 已移除，
> 统一改走 `/skyboxblock environment|env …`，另新增方块私有时间命令。
> **逐条可复制的现行指令请看 [COMMANDS-R36-2026-10-31.md](COMMANDS-R36-2026-10-31.md)**；本文只讲属性语义。

属性名、类型与语义参照 Minecraft 的**环境属性**体系，取值范围与 auto 适配差异见下文，
资料来自**社区 Minecraft Wiki**（[中文](https://zh.minecraft.wiki/w/环境属性?variant=zh-cn) /
[English](https://minecraft.wiki/w/Environment_attribute)）及其**版本条目**
（[中文〈Java版26.4〉](https://zh.minecraft.wiki/w/Java版26.4?variant=zh-cn) /
[English〈Java Edition 26.4〉](https://minecraft.wiki/w/Java_Edition_26.4)），抓取日期 **2026-10-31**。

> ⚠ **出处说明**：以上均为**社区 Wiki（玩家维护）** 的整理与转述，**不是** minecraft.net 的官方发布原文。
> 本文的"原版默认值"取自这些 Wiki 页面，我们**没有**用官方一手原文逐条复核，
> 若与游戏实际表现有出入，以游戏为准。26.4 新增的 `has_sky_occluder` 其"默认主世界 `true`"
> 目前也只有社区 Wiki 转述这一级证据。


---

## 0. 先读这一节：这不是「回移环境属性」

本模组运行在 **Minecraft 1.21.1 / Fabric**，而环境属性体系是 **1.21.11（25w42a）** 才加入的数据包机制。
本功能**不是**把环境属性引擎搬回来，而是：

- **借用**这些属性的 **ID、含义、取值范围和原版默认值**，方便对照 Wiki；
- 在**本模组自己的天空画布上手工实现**，只影响**这一个方块里画出来的天空**；
- **不影响**真实世界的天空、云、雾、光照，也**不影响**另外 8 种天空盒。

**没有实现**：维度/生物群系/时间线/天气四个属性源的叠加；`override` / `add` / `multiply` /
`alpha_blend` / `blend_to_gray` / `append` 修饰符链；区域平滑插值与时间轴；数据包 `attributes` / `timeline`。
你设什么就是什么（等价于原版的 `override` 语义）。

### 0.1 为什么 1.21.1 也能做：天空私有画布

方块里的「原版天空」是本模组在**私有纹理**上按原版风格重画（天空球背景、太阳、月亮、星空、立体云），
再把结果贴到方块面上。它**不是** 1.21.11+ 的新版天空引擎，**不参与**真实世界的雾与光照计算，
因此这些属性只改变"这块私有画布上画什么"。这也决定了 `has_sky_occluder` 等效果只能是**移植近似**。

### 0.2 与原版语义的三处已知偏离

| 项目 | 原版 | 本模组 |
|---|---|---|
| `sky_fog_end_distance` 没设时 | 与 `fog_end_distance` **各自独立** | **回退**使用 `fog_end_distance` 的值 |
| 天气对天空/云颜色 | 用 `blend_to_gray` 等自动变灰 | 你设了颜色就是**最终色**，不再变灰 |
| 各类插值 / 跨 90° 跳变 | 有 | 全部**立即生效**，无插值 |

完整偏离清单见 §7 与规格书 §15。

---

## 1. 命令（语义摘要，完整表格见 R36 手册）

```mcfunction
/skyboxblock environment list                     # 15 项各一行
/skyboxblock environment get <属性>                # 一行
/skyboxblock environment set <属性> <值>
/skyboxblock environment reset <属性> | reset all
/skyboxblock environment reload
/skyboxblock environment time get|set|lock|unlock|reset
```

**属性名三种等价写法**（TAB 只补全与当前前缀匹配的那一种）：

```mcfunction
/skyboxblock environment set sky_color #7ba4ff
/skyboxblock environment set visual/sky_color #7ba4ff
/skyboxblock environment set minecraft:visual/sky_color #7ba4ff
```

属性 ID 沿用 Minecraft 的 `minecraft:` 命名空间，**不使用** `skyboxblock:` 命名空间，也不把 `/` 替换成下划线。

命令是**服务端命令**：设置保存在服务端 world 层，**所有玩家同步看到同一套效果**。
`list` / `get` / 查询类命令人人可用；`set` / `reset` / `reload` / 时间变更需要**权限等级 2（OP）**。
权限不足时命令不执行、也不写入任何状态。

---

## 2. `auto` 是什么意思（最重要的一节）

每个属性默认 `auto`：**用本模组原本的算法算出来的值**。
因此**全部属性保持 `auto` 时，画面与你升级前完全一致**；`reset` 就是把那一项交还给原算法。

⚠ **我们的 `auto` ≠ 原版默认值**：

| 属性 | 原版默认 | 我们的 `auto` | 为什么不同 |
|---|---|---|---|
| `cloud_color` | `#00000000`（无云） | 当前时刻的原版昼夜/天气云色，**不透明** | 本方块默认**有**云（R26 起的产品行为） |
| `cloud_fog_end_distance` | `2048` | **`384`**（跟随旧键 `vanillaCloudFadeEnd`） | 384 是 R30 实测通过的默认观感 |
| `has_sky_occluder` | 主世界 `true` | **按 1.21.1 原生条件**（相机低于地平线才画） | 原生即如此，常开会改变默认画面 |

### 2.1 `reset` 是"交还给旧配置"，不是"硬重置"

`reset <属性>` 删除该覆盖后，`cloud_height` 与 `cloud_fog_end_distance` 回落到**旧配置键**
`vanillaCloudHeight` / `vanillaCloudFadeEnd`（默认 192 / 384，`cloud_height` 的旧路径再加 0.33）；
若旧键也没设，才用内置默认。`reset all` 只清 `environment.*` 与**时间设置**，旧键与其他配置项原样保留。

查询只显示**明确写下的覆盖值**，其余显示 `auto`；它不会把旧键算出的数值当成当前值报出来——
旧键回退的语义就是本节，需要精确数值请直接看配置文件或 §3.2 的换算表。

---

## 3. 属性清单

颜色写法**只有一种**：`#` + 十六进制。**RGB** `#rrggbb`（6 位）；**ARGB** `#aarrggbb`（8 位，保留 alpha 与符号位）。大小写不限，保存时统一大写。`0x` 前缀、不带 `#`、三位缩写与颜色名一律拒绝。
距离类必须 **> 0**；角度类为有限度数，**0 = 正上方**。

### 3.1 天空与雾

| 属性（可省略 `visual/`） | 类型 | 原版默认 | 说明 |
|---|---|---|---|
| `sky_color` | RGB | `#000000` | 天空本体颜色（天顶到地平线渐变） |
| `fog_color` | RGB | `#000000` | 雾色；画布底色与地平线颜色 |
| `fog_start_distance` | float | `0.0` | 雾开始生效的距离，**可负**；**天空与云共用这个起点** |
| `fog_end_distance` | float | `1024.0` | 雾最大浓度距离，**> 0**；实际生效值不低于 **96** |
| `sky_fog_end_distance` | float | `512.0` | 天空雾独立结束距离，**> 0** |

> **雾距离怎么生效**（本模组适配，不是原版规则）：
> - `sky_fog_end_distance` 设了就用它；没设就**回退** `fog_end_distance`；都没设就沿用游戏渲染距离。
> - `0` 非法（原版同样要求 > 0），要"雾很远"就设大数。
> - `fog_*` / `sky_fog_end_distance` **只影响方块里的天空画面**，不影响世界真实雾（那只看渲染距离和光影）。
> - **太阳、月亮、星星不受雾影响**（原版亦然）。

### 3.2 云

| 属性 | 类型 | 原版默认 | 说明 |
|---|---|---|---|
| `cloud_color` | **ARGB** | `#00000000` | 云色与不透明度；alpha=0 时**完全不画云** |
| `cloud_height` | float | `192.33` | 云的**最终世界高度**（见下） |
| `cloud_fog_end_distance` | float | `2048.0` | 云淡出结束距离，**> 0**，上限 **4096** |

> **云的淡出起点**：显式设 `fog_start_distance` 时由它决定（**它是天空与云共用的起点**）；
> 未设时沿用旧键 `vanillaCloudFadeStart`（默认 128）。旧 `clouddistance` 那种"起点 = 结束/3"只在旧键路径里存在。
> 云的距离**单独控制**，不会因为 `fog_end_distance` 变小而跟着变小。

> ⚠ **`cloud_height` 是最终高度；旧 `cloudheight` 是名义高度，两者差 0.33。**
> 原版云几何在名义高度上加 `+0.33`，所以旧 `cloudheight 192` 的云实际在 **y ≈ 192.33**：
>
> | 写法 | 云实际高度 |
> |---|---|
> | 旧 `cloudheight 192`（R36 已移除该命令） | 192.33 |
> | `env set cloud_height 192.33` | **192.33**（与上一行完全相同） |
> | `env set cloud_height 240` | 240.00 |
> | 旧 `cloudheight 240`（已移除） | 240.33 |
>
> 所以 `auto` 时的等效最终高度是 192.33，而旧键记的是 192 —— 同一朵云的两种记法。

### 3.3 太阳、月亮、星星

| 属性 | 类型 | 原版默认 | 说明 |
|---|---|---|---|
| `sunrise_sunset_color` | **ARGB** | `#00000000` | 霞光色与强度；alpha=0 不画霞光 |
| `sun_angle` | float（度） | `0.0` | 太阳高度角：0 = 正上方，90 = 落西 |
| `moon_angle` | float（度） | `0.0` | 月亮高度角，**独立于太阳** |
| `star_angle` | float（度） | `0.0` | 星空高度角，**独立于日月** |
| `moon_phase` | 枚举 | `full_moon` | 见下表 |
| `star_brightness` | float | `0.0`（夜晚实际 `0.5`） | 星星亮度，**0–1** |

- 三个角度互相独立，都以「**0 = 正上方**」为准；全部 auto 时跟随世界时间。
- 不做角度平滑插值（原版跨 90° 会跳变，我们全程直接生效）。
- ⚠ `sunrise_sunset_color` 覆盖后**常显**、不随时间衰减；我们**没有**实现"面向东西时下半天空被霞光填充"。
- `moon_phase` 可用八个名字或 `0`–`7`：

| 数字 | 名称 | 中文 |
|---:|---|---|
| 0 | `full_moon` | 满月 |
| 1 | `waning_gibbous` | 亏凸月 |
| 2 | `third_quarter` | 下弦月 |
| 3 | `waning_crescent` | 残月 |
| 4 | `new_moon` | 新月 |
| 5 | `waxing_crescent` | 峨眉月 |
| 6 | `first_quarter` | 上弦月 |
| 7 | `waxing_gibbous` | 盈凸月 |

- `star_brightness` **显式设置后就是最终亮度，不再被下雨/雷暴压暗**；保持 `auto` 时仍走原昼夜+天气链路。
  > 已实机验证（R35）：`rain = 1.0` 下 `set star_brightness 1`，星星绘制色为 `[1,1,1,1]`。

### 3.4 天空遮挡

| 属性 | 类型 | 原版默认 | 说明 |
|---|---|---|---|
| `has_sky_occluder` | 布尔 | 主世界 `true` | 是否遮挡天空下半部分（地平线以下的天体） |

- `false`：完全不画下半球遮挡。
- `true`：用**雾色**填充、边界 **alpha 渐隐**，遮住地平线以下的天体。
- ⚠ 这是对原版 26.4 `sky_occluder` 着色器的**移植近似**：方向一致（雾色、平滑边界、遮天体），不保证逐像素相同。
- ⚠ `auto` **不是**原版 26.4 的 `true`：沿用 1.21.1 原生行为（相机低于地平线才画），默认画面不变。

### 3.5 方块内部时间（R36 新增）

`/skyboxblock env time set|lock|unlock|reset` 让方块画布**独立走时或停在指定时刻**，
`ticks` 为 `0..23999`，preset：`day` 1000 / `noon` 6000 / `night` 13000 / `midnight` 18000 / `sunrise` 0 / `sunset` 12000。
**不改变世界 `time` 与天气**；设置与锁定持久保存，重进世界从保存的起点开始、不计离线时间。
`time set` **不解除锁定**：已锁定时只更新锁定的时刻，需要恢复走时请用 `time unlock`。
若显式设了 `sun_angle` / `moon_angle` / `star_angle` / `sky_color` 等，固定时间时这些覆盖仍优先，
**时间的视觉变化可能不明显**。完整说明见 [R36 手册 §5](COMMANDS-R36-2026-10-31.md)。

---

## 4. 旧命令与兼容

R36 起根目录 `cloudheight` / `clouddistance` / `cloudfade` / `reload` **已移除**（解析失败、不改配置）。
旧**配置键**仍然读取，作为 `auto` 回退，不迁移也不删除：

| 物理量 | 旧命令（已移除） | 现属性 | 换算 |
|---|---|---|---|
| 云高度 | `cloudheight h`（名义 h） | `cloud_height H`（最终 H） | `H = h + 0.33` |
| 云淡出结束 | `clouddistance d` / `cloudfade s e` 的 `e` | `cloud_fog_end_distance` | 相同 |
| 云淡出起点 | `cloudfade` 的 `s` | `fog_start_distance`（天空/云共用） | 未设时沿用旧键 `vanillaCloudFadeStart` |

`clouds=false`（文件或 `-Dskyboxblock.clouds=false`）是**云的总开关**，与 `cloud_color` 的 alpha=0 独立；
任意一个生效，云就不画。

### 4.1 覆盖与旧键谁听谁的

**`environment.*` 覆盖优先；旧键保持独立、不会被改写。**

| 情况 | 结果 |
|---|---|
| 只用旧键（手改文件） | 完全照旧；`environment` 全部 auto 时不读新键 |
| `environment set cloud_height …` | 以新键为准 |
| `environment reset cloud_height` | 回落到旧键 `vanillaCloudHeight` + 0.33 |

配置文件（**服务端 world 级**，客户端不持有配置副本）：

```properties
# 服务端 skyboxblock 配置
clouds=true
vanillaCloudHeight=192.0
vanillaCloudFadeStart=128.0
vanillaCloudFadeEnd=384.0

# environment 覆盖项（前缀固定 environment.，auto 时不写）
environment.visual/sky_color=#7ba4ff
environment.visual/cloud_height=240.0
environment.visual/has_sky_occluder=true
```

优先级：**内置默认 < `vanillaCloud*`（旧键，auto 回退） < `environment.*`（命令写入，服务端保存并同步）**。
命令设置的值由服务端保存并广播给所有玩家；**客户端不读本地 `-Dskyboxblock.environment.*`**，
因此 `get` / `list` 显示的就是服务端当前的共享设置。
手改服务端文件后用 `/skyboxblock environment reload`（OP2）重新读取。

---

## 5. 不支持的属性及原因

### 5.1 `sky_light_color` 与 `sky_light_factor`
它们控制**光照贴图**（`lightmap.fsh` 的 `SkyLightColor` / `SkyFactor`），作用于**整个世界的光照**：
地形、实体、手、粒子。不会改变我们这块私有画布的天空像素——效果会是"世界变暗/变色，方块里的天空不变"，
与"配置天空盒"的直觉相反，且影响真实世界。因此**不做**。想让方块内更亮/更暗请用
`sky_color` / `fog_color` / `star_brightness` / `sunrise_sunset_color`。

### 5.2 其他 `visual/*`

| 属性 | 不做的原因 |
|---|---|
| `block_light_tint` | 世界 lightmap |
| `night_vision_color` | 世界 lightmap |
| `ambient_light_color` | 维度环境光，属世界 lightmap |
| `default_dripstone_particle` | 与天空显示无关 |
| `ambient_particles` | 世界空间粒子，不属于方块内天空 |

### 5.3 `audio/*`（4 项）
`background_music`、`music_volume`、`ambient_sounds`、`firefly_bush_sounds` 是背景音乐/环境音效，
且属服务器权威环境，不是客户端外观。

### 5.4 `gameplay/*`（23 项）
`sky_light_level`、`bed_rule`、`natural_mob_spawns`、`monsters_burn` 等是**玩法规则**，
必须由服务端权威决定；客户端改只会不同步。本模组不碰玩法。

### 5.5 水下雾（`water_fog_color` / `water_fog_start_distance` / `water_fog_end_distance`）
**不纳入**：它们作用于**整个世界**的水下近景，与"方块里显示什么天空"无因果关系，
纳入等于让本模组的配置去改**玩家真实的水下雾**。
**请放心：本模组完全不触碰你的真实水下雾。**

---

## 6. 常见用法

逐条可复制的常用组合见 [COMMANDS-R36 §4](COMMANDS-R36-2026-10-31.md)，
其中包含：永久正午、紫罗兰黄昏、无云夜空、云高 240 + 共用雾起点 100→320、关掉下半球遮挡、全部还原。

---

## 7. 已知限制

1. **只影响原版天空盒方块**：另外 8 种材质天空盒不受任何影响。
2. **不影响真实世界**：维度的云、世界雾、光照明暗、天气、世界时间一律不动。
3. **覆盖即最终值**：`sky_color` / `fog_color` / `cloud_color` 显式设置后不再被天气变灰；`star_brightness` 显式后不乘雨。
4. **与原版逐像素不一致**：自研几何 + 自研着色器复刻，雾的色阶、霞光过渡、遮挡边界都有已知差异。
5. **没有修饰符链、没有插值、没有时间轴**：设什么就是什么，变化立即切换。
6. **`sunrise_sunset_color` 覆盖后不受时间衰减**，可为非晨昏强制显示；未实现"下半天空被霞光填充"。
7. **日月星不吃雾**（与原版一致）。
8. **`moon_phase` 的纹理排布**沿用既有实现；R35 已实机证实 8 个命名值产生 8 种不同像素，但未逐格目视对照。
9. **水下雾三项未纳入**，且完全不触碰真实水下雾。
10. **实机范围**：R35 在**四个环境**完成验证（纯原版 Fabric / Iris 原生 / 原生 DH 3.3.3 / Iris+DH+FSR2），
    **各 371/371 通过，合计 1,484 项检查**（详见 §9 与 R35 交付报告）。该结论**仅覆盖那四个客户端矩阵**，
    **不覆盖 R36**（旧命令移除、时间命令、补全合并均为本轮新改动，实机验收另记）。

---

## 8. 相关文档

- **R36 指令手册（现行命令）**：[COMMANDS-R36-2026-10-31.md](COMMANDS-R36-2026-10-31.md)
- 规格与审计基线：[ENVIRONMENT-ATTRIBUTES-R35-SPEC-2026-10-31.md](ENVIRONMENT-ATTRIBUTES-R35-SPEC-2026-10-31.md)
  （含 **v4 审计结果 §16**、与官方原文偏离清单 §15、来源口径表）
- 云高历史说明：[VANILLA-CLOUD-HEIGHT.md](VANILLA-CLOUD-HEIGHT.md) · 云距历史说明：[VANILLA-CLOUD-DISTANCE.md](VANILLA-CLOUD-DISTANCE.md)
- 属性来源（**均为社区 Wiki，非官方原文**）：
  [中文 Wiki〈环境属性〉](https://zh.minecraft.wiki/w/环境属性?variant=zh-cn) ·
  [English Wiki〈Environment attribute〉](https://minecraft.wiki/w/Environment_attribute) ·
  版本条目 [中文〈Java版26.4〉](https://zh.minecraft.wiki/w/Java版26.4?variant=zh-cn) /
  [English〈Java Edition 26.4〉](https://minecraft.wiki/w/Java_Edition_26.4)
  （`has_sky_occluder` 为 26.4 Snapshot 2 新属性，Wiki 记主世界 `true`、下界/末地 `false`）—— 抓取日期 2026-10-31。

---

## 9. 文档状态（R35 已冻结，R36 进行中）

**R35 构建**（`skyboxblock-1.0.4-test.1-r35.jar`，SHA256 `f1937b9eb21caa1aea2b912bea1c7447eafa2e0f2cd90d116beba99d46291444`）
的验收结论**原样保留**，本轮不修改其计数：

| 项 | 状态 |
|---|---|
| 属性 ID / 类型 / 范围 / 原版默认值 | ✅ 已对照中英社区 Wiki + 版本条目（2026-10-31）；⚠ **非**官方一手原文 |
| 本模组实现（15 项视觉属性） | ✅ 已落地 |
| 静态合同审计 / 包审计 | ✅ 93 / 93；58 / 58 |
| 五组 Java 回归 | ✅ 433,545 断言全过 |
| 四环境实机 | ✅ 各 371 / 371，合计 **228 阶段 / 1,484 检查 / 812 条真实命令** |
| 最终汇总 | ✅ `build/reports/r35-final-validation.json` → `status: PASS` |
| 其他 8 种天空盒不受影响 / 8 个月相可辨 | ✅ 实机证实 |

**R36 状态**：命令树变更（旧根命令移除、`env reload`、时间命令、补全合并）与文档更新为本轮工作，
**尚未实机验证**；R36 的实机与回归计数由 R36 交付报告单独给出，**不提前引用**。

**实机对照图**：[`r35-environment-comparison-20261031.png`](screenshots/r35-environment-comparison-20261031.png)
—— **4×4 实际游戏截图**（四环境 × 四主题），未重画天空，仅加标题与缩放。

**交付报告（R35）**：[ENVIRONMENT-ATTRIBUTES-R35-DELIVERY-2026-10-31.md](ENVIRONMENT-ATTRIBUTES-R35-DELIVERY-2026-10-31.md)
**独立命令评审（R35）**：[INDEPENDENT-R35-COMMAND-REVIEW-2026-10-31.md](INDEPENDENT-R35-COMMAND-REVIEW-2026-10-31.md)

### 9.1 关于 R35 日志里"ERROR"的诚实说明

四个游戏进程都是 **exit code 0**，**没有 fatal / 渲染 / 采样 GL 错误**。
**早两个环境**（纯原版、Iris 原生）各保留 1 条（共 2 条）离线认证公钥告警：

```
[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key
```

**后两个环境**（原生 DH、Iris+DH+FSR2）ERROR 为 0。正确表述是：
> 四环境全部 exit 0、无 fatal/渲染错误；早两环境各留 1 条离线认证公钥告警（共 2 条），日志未吞。

### 9.2 冻结边界（本任务不扩大的部分）

- **历史 4 项红**（透明层 3675/3684 等）—— 不重新认证。
- **门户（portal）内部**表现、旧环境差异 —— 未验证。
- **资源 / 性能长测** —— 未做，也不作性能承诺。
- 以上结论**仅覆盖指定四客户端矩阵**，**不是**通用发布认证；本模组是 **1.21.1 私有天空画布适配**，
  **不是**新版环境属性引擎的原生实现。
