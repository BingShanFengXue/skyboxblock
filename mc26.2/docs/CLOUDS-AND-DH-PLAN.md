# 云 / DH 的可行性与工作量评估（26.2）

> 这两项是「完全还原 1.21.1」剩下的硬骨头。本文只写**已经过实测验证**的结论与可执行步骤，
> 不写猜测。更新于对齐轮 21。

## 一、云

### 已排除（每条都有实机证据）

| 方案 | 结果 |
|---|---|
| 帧图之外调 `CloudRenderer.render(...)`（`@Redirect` 换 target，日志确认命中） | 调用成功、**不产出几何** |
| 自绘云平面贴 `clouds.png`（512 或 2048 格） | 被低通成一整片灰白 |
| 逐格几何（18103 格，12 格/格，UV 取格坐标） | 生成与绘制都正常，**画面无变化**（帧字节逐位相同） |
| 逐格几何 + 四个侧面 | 同上 |
| 世界坐标 + 平移到相机（修掉"云在相机背后"） | 同上 |
| 云 pass 不挂深度附件（避开天空盘已写的深度） | 同上 |

### 关键对照

同一张 target 上：

- `SkyHazeBlit`：**screenquad 管线**（`core/screenquad` + `core/blit_screen`，`draw(3,1,0,0)`，**无顶点缓冲**）→ **能画**；
- `SkyCloudPlane`：`core/position_tex` + `BufferBuilder` 顶点缓冲 → **怎么调都不产像素**（日志照样打印成功）。

⇒ 结论：**26.2 上"往自绘 target 画带顶点缓冲的几何"这条路径目前打不通**，screenquad 路径可用。

### 两条可行路线

1. **screenquad + 自写片元着色器**（推荐）
   - 在片元里用屏幕坐标反投影出视线，与 y=192 的云层求交，再采样 `clouds.png`；
   - 需要新增 `assets/skyboxblock/shaders/core/*.vsh/fsh` 两个文件与一个 `RenderPipeline`；
   - 工作量估计：着色器 100 行 + Java 80 行；风险：26.2 的 shader 载入约定（`#moj_import`、uniform 名）需要实测校准。
2. **mid-frame 钩子（mixin）**
   - 在云 pass 内部把主目标的云层复制到我们的 target；
   - 需要一个能稳定命中的注入点（`LevelRenderer.addCloudsPass` 的 lambda），且要处理分辨率/时序；
   - 工作量估计：mixin 60 行 + 校验；风险：与 Sodium/Iris 的 pass 顺序冲突。

## 二、DH（Distant Horizons）

### 现状

`client/ModCompat` 只做检测与日志：

```
[skyboxblock] 26.2 环境：Iris=true DistantHorizons=true（DH 深度注入在自绘天空路径下不可用）
```

### 为什么接不回原能力

1.21.1 的 `DistantSkyDepth` 依赖两件事：
1. 自研 sky shader（能把 DH 深度当采样源、并按 DH 投影做遮挡）；
2. 抓帧得到的当前帧深度。

26.2 的自绘路径两者都没有（`SkyRenderer` 用原版 pipeline 往我们的 target 画）。

### 建议的降级口径（可在不改架构的前提下落地）

| 场景 | 建议行为 |
|---|---|
| 未装 DH | 完全不变 |
| 装了 DH | 天空窗口内**不显示** DH 远景地形（与"只显示天空"的语义一致），并在启动日志里明说 |
| 想做完整对齐 | 需按 §一 的路线 2（mid-frame 钩子）先拿到 DH 深度，再自写 shader 消费它 —— 属于架构改造 |

## 三、当前发布状态的验收口径

- 风格语义（0/1/2/3）与自定义天空盒：**已对齐**，由 `SkyStyleMappingRegression`（63 条）与实机截图守护；
- 天空内容：天空盘 + 日月星**已对齐**；
- 地平线雾：**近似**（颜色校正 + 0.35 alpha 的渐变叠层），差值 (-49,-43,-31)；
- 云：**未对齐**（默认关闭）；
- DH：**仅检测**。
