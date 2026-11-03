# skyboxblock port.1 历史交付记录（已被 port.2 替代）

> **历史说明，不是当前验收结论。** 当前产物、运行矩阵及准确边界见 [PORT2-VALIDATION.md](<PORT2-VALIDATION.md>)。
> 本文旧“逐像素不可区分”、旧固定帧图片及“Iris 只要不崩”的口径不再用于判断本轮完成；原始记录保留供审计追溯。
> 关于旧证据与前景 TAA 判据的校正，见 [AUDIT-2026-11-02.md](<AUDIT-2026-11-02.md>) 和 [TEMPORAL-ROOT-CAUSE.md](<TEMPORAL-ROOT-CAUSE.md>)。

> MC 1.21.1 的 `skybox-mod` 移植到 **26.2**，并按 API 差集覆盖 **26.1 / 26.3**。
> 目标：与 1.21.1 表现**无差别**。本文只写"结论 + 可核对证据"。

## 一、产物

| 文件 | 适用版本 | 大小 | sha256（前 16 位） |
|---|---|---|---|
| `output/skyboxblock-1.0.6-test.1-port.1-mc26.2.jar` | **26.2** | 16,924,529 | `b776377c9ac5d992` |
| `output/skyboxblock-1.0.6-test.1-port.1-mc26.1.jar` | 26.1 | 16,903,185 | `d1169660816faba1` |
| `output/skyboxblock-1.0.6-test.1-port.1-mc26.3.jar` | 26.3 | 16,895,926 | `84d2c1d526eb7022` |

> 命名规则：`skyboxblock-<mod 版本>-mc<目标 MC 版本>.jar`（构建脚本里 `archiveFileName` 统一加了 `-mc<x.y>`）。

## 二、验收证据

### 1) 与真实天空**逐像素不可区分**（核心判据）

同一机位、同一时刻（夹具每刻 `time set 6000`）拍"有墙 / 无墙"两张图，逐像素比较原版天空风格那一列：

```
pixels            : 33660
mean abs diff (RGB): [0.21 0.18 0.08]
max diff          : 5
share >8          : 0.00%
VERDICT: PASS（方块面与真实天空不可区分）
```

脚本：`tools/verify-sky-parity.py`（可重复执行）。
图：`docs/screenshots/26.2-parity-no-wall.png` / `26.2-parity-with-wall.png`。

### 2) 行为逐项

| 1.21.1 行为 | 26.2 | 证据 |
|---|---|---|
| `shaderMode 0` 真实天空 | ✅ | 上面的逐像素判据 |
| `shaderMode 1` 纯白 | ✅ | `docs/screenshots/26.2-style-parity.png` |
| `shaderMode 2` 纯黑 | ✅ | 同上 |
| `shaderMode 3` 自定义全景 | ✅ | 同上（galaxy / dusk / 自定义 gradient） |
| 自定义天空盒（pack + 每玩家设置） | ✅ | 日志 `client accepted revision 1/2` |
| 日月星 | ✅ | `build/sky-frame.png` 夜间/白天两次实测 |
| 云 | ✅ | `docs/screenshots/26.2-clouds-default.png`；开销 810 µs/帧 |
| 地平线雾 | ✅ | 同 1) 判据 |

### 3) 回归

`gradlew build regAll` → **BUILD SUCCESSFUL**，共 **69,452** 条断言：

| 套件 | 条数 |
|---|---|
| SkyEnvironmentRegression | 283 |
| ServerCommandRegression | 782 |
| ServerBackendRegression | 109 |
| SkyboxServerBackendRegression | 186 |
| SkyTimeRegression | 68,012 |
| SkyUvMathRegression（新，取样数学） | 17 |
| SkyStyleMappingRegression（新，shaderMode 语义） | 63 |
| SkyLayersRegression（新，26.1 降级路径） | 3 |

### 4) 三版本

- 26.2：实机进游戏 + 上述全部验收；
- 26.1：`MCVER=26.1 clean build` 通过（雾/云实现类按版本从源码集排除，调用走 `SkyLayers` 反射入口）；
- 26.3：`MCVER=26.3 clean build` 通过并打包。

## 三、已知差异（只有一项）

**DH（Distant Horizons）深度注入**未实现：1.21.1 靠"自研 sky shader + 抓帧深度"把 DH 远景塞进天空窗口；
26.2 自绘路径两者都没有（实测本机自写 shader + UBO 的 pass 也画不出像素）。
现状是**检测 + 启动日志明确告知**，见 `docs/CLOUDS-AND-DH-PLAN.md`。

另外 Iris 光影下的天空按需求"只要不崩"，未做视觉验收。

## 四、开关

| 环境变量 | 作用 |
|---|---|
| `SKYBOX_CLOUDS=0` | 关掉自绘云层（默认开，224×140，约 0.8 ms/帧） |
| `SKYBOX_HAZE_BLIT=0` | 关掉地平线雾层（默认开） |
| `SKYBOX_HAZE_BLIT=1` + `SKYBOX_CLOUD_VIA_BLIT=1` | 诊断：用 blit 管线直接贴云贴图 |

## 五、文档索引

- [`STATUS-26.2.md`](STATUS-26.2.md)：逐轮进展与每轮证据
- [`PARITY-1.21.1-vs-26.2.md`](PARITY-1.21.1-vs-26.2.md)：行为对照表 + 26.2 渲染管线实测边界
- [`CLOUDS-AND-DH-PLAN.md`](CLOUDS-AND-DH-PLAN.md)：云/DH 的可行性与工作量评估
- [`API-MATRIX-26.1.md`](API-MATRIX-26.1.md) / [`API-MATRIX-26.2.md`](API-MATRIX-26.2.md) / [`API-MATRIX-26.3.md`](API-MATRIX-26.3.md)：API 差集
