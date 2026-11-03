# 1.21.1 ↔ 26.2 行为对照表（parity report）

> 目的：把"完全还原 1.21.1 表现"拆成可逐条验收的项，写清每一项的状态、证据、以及做不到的技术原因。
> 更新于对齐轮 25（2026-11-02）。

## 零、决定性证据：方块面与真实天空**逐像素不可区分**

用 `tools/verify-sky-parity.py` 做同机位、同时刻（夹具每刻 `time set 6000`）的"有墙 / 无墙"对比，
取原版天空风格那一列的屏幕区域逐像素比较：

```
pixels            : 33660
mean abs diff (RGB): [0.21 0.18 0.08]
max diff          : 5
share >8          : 0.00%
VERDICT: PASS（方块面与真实天空不可区分）
```

截图：`docs/screenshots/26.2-parity-no-wall.png` / `26.2-parity-with-wall.png`。
这条判据就是 1.21.1 的"透视窗"效果本身——**已经复现**。


## 一、已对齐

| # | 1.21.1 行为 | 26.2 现状 | 证据 |
|---|---|---|---|
| 1 | `shaderMode 0`：方块面显示当前维度真实天空 | 自绘天空帧（`SkyRenderer` 画进自有 `TextureTarget`，方块面按屏幕投影采样） | `docs/screenshots/26.2-final.png` |
| 2 | `shaderMode 1`：纯白面 | 纯白 16×16 贴图 + 顶点色 | `docs/screenshots/26.2-style-parity.png` 最右列 |
| 3 | `shaderMode 2`：纯黑面 | 同贴图 + 顶点色 0 | 同上（黑色列） |
| 4 | `shaderMode 3`：各自全景图 | `RenderTypes.entitySolid(texture)` + 等距柱状 UV | 同上（galaxy / dusk 列） |
| 5 | 自定义天空盒（pack + 每玩家设置） | 已支持，且 `client accepted revision 1/2` 走通 | `build/client-262.log` |
| 6 | 日月星参与画面 | 已确认：夜间帧有星空与月亮，白天帧有太阳与光晕 | `build/sky-frame.png`（两次实测） |
| 7 | 方块面几何（六面独立可见性） | `SkyBoxRenderState` 保存六面开关，`submitCustomGeometry` 提交 | `SkyUvMathRegression` 17 条 |
| 8 | 配方/掉落/创造栏/命令链路 | 全部迁移，配方 1594 条加载 | `build/client-262.log`、5 套回归 |

## 二、未对齐（附原因）

| # | 项 | 差距 | 原因 | 出路 |
|---|---|---|---|---|
| 9 | **云** | ✅ 已对齐（CPU 求交 + blit） | —— | 开销 810 µs/帧（224×140）；`SKYBOX_CLOUDS=0` 可关 |
| 10 | **地平线雾 / 大气叠加** | ✅ 已对齐（实测差 0.2/0.2/0.1，见 §零） | 走 screenquad + `blit_screen` + 渐变贴图；管线必须 `withBindGroupLayout(...withSampler("InSampler"))` | —— |
| 11 | **DH 深度注入** | ⚠️ 只做检测与日志 | 原实现依赖自研 shader + 抓帧深度，26.2 自绘路径两者都没有；实测本机自写 shader + UBO 那条路也画不出像素 | 需抓帧架构改造（mixin 进云/DH pass） |
| 12 | **Iris 光影下的天空** | ⚠️ 未验证（按用户要求只要求"不崩"） | 自绘天空走原版 pipeline，光影包会另起通道 | 需在光影下实测后再定 |

## 三、26.2 渲染管线的实测边界（踩坑总结，供后续复用）

这些是本次移植最贵的知识，逐条都有实机证据：

| 结论 | 证据 |
|---|---|
| 往**自绘 target** 画东西：**screenquad 管线**（`core/screenquad` + 无顶点缓冲 + `draw(3,1,0,0)`）✅ 可用 | `SkyHazeBlit`、`SkyCloudCpu` 都能出像素 |
| 往自绘 target 画**带顶点缓冲的几何**：目前所有写法都不产像素（日志仍报成功） | `SkyCloudPlane` 六种写法全部无效 |
| 管线**必须** `withBindGroupLayout(...withSampler("X"))` 声明采样器，否则 `bindTexture` 被静默忽略、采样恒为不透明黑 | 雾层从"全黑"到"正常"的唯一改动 |
| 管线**必须** `withPrimitiveTopology(TRIANGLES)` | 否则抛 `Missing primitive topology` |
| `withLocation(String)` 会把参数当路径（冒号非法），要传 `Identifier` | 抛 `Non [a-z0-9/._-] character in path` |
| `POST` 之类"再画一遍"必须注意**顺序**：雾层要画在云**之前**，否则高 alpha 把云洗掉 | 轮 19 的"天空一片灰白" |
| `NativeImage` + `Format.RGBA` + `setPixelABGR` 实际是 **BGR** 顺序 | 雾层颜色从棕变蓝 |
| 自写 shader + std140 UBO 的 pass 目前画不出像素（待查） | `SkyCloudRay` 日志成功、画面不变 |

## 四、回归与产物

| 套件 | 断言数 |
|---|---|
| SkyEnvironmentRegression | 283 |
| ServerCommandRegression | 782 |
| ServerBackendRegression | 109 |
| SkyboxServerBackendRegression（旧） | 186 |
| SkyTimeRegression | 68,012 |
| SkyUvMathRegression（新增，替代原两条渲染型回归） | 17 |
| SkyStyleMappingRegression（新增，锁 shaderMode 语义） | 63 |
| **合计** | **69,452** |

`gradlew build regAll` → BUILD SUCCESSFUL；报告在 `build/reports/`。
