# 测试版说明（skyboxblock 1.0.6-test.1-port.1，MC 26.2）

产物（文件名里带目标 MC 版本，三个版本不会混）：

| 文件 | 适用版本 | sha256（前 16 位） |
|---|---|---|
| `output/skyboxblock-1.0.6-test.1-port.1-mc26.2.jar` | **26.2 ← 这份是给你的测试版** | `b776377c9ac5d992` |
| `output/skyboxblock-1.0.6-test.1-port.1-mc26.1.jar` | 26.1 | `d1169660816faba1` |
| `output/skyboxblock-1.0.6-test.1-port.1-mc26.3.jar` | 26.3 | `84d2c1d526eb7022` |

已装进测试实例：`26.2-Fabric 0.20.1\mods\skyboxblock-1.0.6-test.1-port.1-mc26.2.jar`（旧包已移除）。

## 这一版改了什么

1. **修复：方块面上的天空上下颠倒**（实测发现）。`SkyUvMath.project` 的 v 轴翻转方向搞反了，
   导致地平线跑到方块顶端、天顶跑到下方。回归 `SkyUvMathRegression` 已同步改成"v 越小越靠上"。
2. 版本号从 `1.0.6-test.0-port.1` 提到 `1.0.6-test.1-port.1`，便于和你手里的旧包区分。

## 本会话的验收数据

### 1) 十种风格逐个实机截图

`docs/screenshots/styles/<风格>.png`（另有 `_overview.png` 九宫格式总览）。全部十种各不相同、无黑块/无缺失：

| 风格 | 表现 |
|---|---|
| vanilla | 真实天空（含云、地平线亮带） |
| white / black | 纯白 / 纯黑 |
| kloppenheim | 灰色多云全景 |
| syferfontein | 浅色天空全景 |
| dusk | 粉色黄昏 |
| galaxy | 星空 |
| lush | 绿色山峦 |
| apocalypse | 暗红末日 |
| gradient | 自定义橙色渐变（自定义天空盒链路） |

### 2) DH 是否影响天空盒显示 —— **不影响**

同一机位、同一时刻，DH 启用 vs 停用（把 `DistantHorizons-3.3.3-26.2-fabric-neoforge.jar` 移出 `mods/`）：

- **方块那一列**：平均绝对差 `0.93 / 0.80 / 0.90`，最大 `1`，差异>8 的像素占比 **0.00%**
- 全屏：差异>8 占比 18.72%（DH 确实改变了背景远景地形，但没进到方块面里）

图：`docs/screenshots/conditions/dh-on.png` / `dh-off.png`。

### 3) 光影兼容（Iris 1.11.4 + 26.2）

| 光影 | 结果 |
|---|---|
| **iterationRP Alpha 0.8.31（ITRP，主要目标）** | ✅ 能进游戏、不崩；模组自己的天空纹理正常（见 `build/sky-frame.png`）；**但方块面显示的是自绘的"原版天空"，不跟随光影的天空**（那片天在光影里是黑的，方块里却是蓝的） |
| **ComplementaryReimagined r5.9.3** | ✅ 能进游戏、不崩；同样**不跟随光影天空**（光影天空有卷云，方块里是纯蓝渐变） |

光影下的画面（窗口截图，因为 Iris 会把画面合成到自己的帧缓冲，模组从渲染目标取不到图）：
`docs/screenshots/conditions/itrp-window2.png` / `complementary-window2.png`。

> 结论：**兼容性 = "能跑不崩"，不是"视觉跟随"**。1.21.1 那套是靠抓帧，所以在光影下也能一致；
> 26.2 自绘方案天然做不到，除非改回抓帧架构（见 `docs/CLOUDS-AND-DH-PLAN.md`）。

## 你可以这样测

1. 进游戏看十种方块（创造模式物品栏搜 `skybox`）；
2. 装/卸 DH，看方块面是否变化（预期：不变）；
3. 在 Iris 里切 iterationRP / Complementary，看是否崩、方块面是否还显示天空（预期：不崩、显示自绘天空但不跟随光影）；
4. 自定义天空盒：`config/skyboxblock/skyboxes/*.conf` + 全景图，`/skyboxblock` 命令按玩家设置。

## 已知问题

- 光影下"方块里的天空"与"光影天空"不一致（架构限制）。
- DH 远景地形不会出现在天空窗口里（同）。
- 测试用的世界夹具里可能有少量残留方块（datapack 的 `/fill` 有 32768 方块上限，清场没清干净），只影响截图美观。
