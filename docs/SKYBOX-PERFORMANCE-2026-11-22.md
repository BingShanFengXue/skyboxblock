# 天空盒方块：渲染性能与可见性开关

## 指令

```mcfunction
/skyboxblock env render_skybox false
/skyboxblock env render_skybox true
/skyboxblock env render_skybox
/skyboxblock env reset render_skybox
/skyboxblock player <目标玩家> render_skybox false
/skyboxblock player <目标玩家> render_skybox true
/skyboxblock player <目标玩家> reset render_skybox
```

渲染开关使用原版 Brigadier 布尔参数 `brigadier:bool`，只使用 `true` / `false`，不接受 `t` / `f`。`environment` 与 `env` 等价，两层也支持显式的 `set render_skybox <true|false>`。默认显示。`env` 设服务器全局值，玩家值可独立覆盖全局；玩家 `reset` 恢复继承。全局 `reset` 恢复默认值，保留已设置的玩家覆盖。查询显示 `auto` 表示该属性没有有效覆盖，默认显示；`auto` 不是布尔输入。写入需要权限等级 2，已连接的同版本客户端无需重进游戏。多人服应同时更新客户端和服务端；在新增渲染开关之前的客户端不识别该配置属性。

关闭时，天空盒方块不提交颜色、深度、光影材质或最终合成，后方景物和原版天空照常可见。方块不变成空气：位置、碰撞、选取和掉落行为均保留；准星对准时依然可出现选框。此开关不改写光照、正常的游戏天空或背包物品模型。

## 不降低默认画质的优化

- **两版**：空视图或关闭的视图跳过私有渲染；仅含全景或纯色材质、且没有 Iris/DH 等兼容消费者的视图，不再生成不会被采样的原版天空画布。全景分辨率、材质遮挡和采样方式未变。
- **1.21.1**：没有天空盒几何时不再进入 GL 状态保存／恢复。原版不透明阶段不再为同一个目标重复保存和恢复 GL 状态；Iris 仍保留原来的目标切换和状态保护。可见面仍按原规则收集，消除逐面分配三角索引数组。
- **26.2**：只在后续合成或捕获明确需要时生成覆盖纹理、分配深度/透明缓冲并进行透明前后帧复制。只有同一个根视图、目标尺寸和投影完全一致时复用私有天空画布；不同尺寸、有抖动／缩放的投影、裁剪和 Portal 路径保持重绘。未降低抗锯齿或跳过光影所需的透明处理。

### 大量方块路径审查与本轮优化

“视野卡进天空盒看到完整全景图不卡，但大量天空盒方块会掉帧”对应两条不同路径：全景图主要是一次私有画布生成与屏幕采样；大量方块还会让每个方块实体逐帧进入收集、做可见面/归属校验、生成六边顶点并上传材质批次。

- 两版都不是每个天空盒方块单独发起 GPU draw：1.21.1 的 `SkyWorldRenderer.Frame`、26.2 的 `SkyWindowRenderer.Frame` 都按 `SkySkin` 聚合，后续每个材质批次一次绘制；覆盖、最终合成等阶段会按设计产生额外遍，但仍按批次处理。
- 1.21.1 的同材质相邻接触面由 `SkyBlockEntity.shouldRenderFace` 剔除，其余面交给原版遮挡规则；不透明阶段启用 GL 背面剔除和深度测试，所以玩家背向面不会产生可见片元。26.2 的相邻天空盒接触面统一剔除，渲染状态同样启用深度测试/背面剔除。
- 本轮 1.21.1 直接把面顶点写入材质批次，普通视图不再为每个面先写临时整帧数组再 `System.arraycopy`；Iris、DH 和回归诊断需要的合并副本仍保留。严格实体归属检查复用了视图内最近区块和当前 `BlockState`，减少大墙体中重复 `getChunkNow` 与状态读取。
- 没有加入按相机方向的 CPU 面剔除：这样保留了相机贴入/穿入天空盒时的既有可见性语义，避免把用户反馈中的“卡进方块仍能看到全景”变成空面；背面由 GPU 状态处理。

## 隔离实机对照

Windows，i5-12600KF／RTX 4060 Ti，1280×800。每个场景先等服务端命令生效和渲染稳定，再串行采样；原版两轮按旧→新→新→旧运行，每场景每轮 600 帧；光影主对照每场景 360 帧，26.2 iterationRP 另按同样次序重复 600 帧。对照在同一个 JAR 上用 `-Dskyboxblock.perf.baseline=true` 恢复被优化的主要渲染工作，不是拿不同功能的两款版本比较。CPU 值是渲染线程区段时间，GPU 值是延迟读取的 OpenGL 时间戳；内、外阶段有重叠，不相加。整帧指标为相邻主视图开始时间差，含引擎其他工作及调度，不能拿来承诺低配帧率。

| 原版场景 | 1.21.1 P95 帧时间，旧 → 新（ms） | 26.2 P95 帧时间，旧 → 新（ms） |
|---|---:|---:|
| 无天空盒 | 0.77 → 0.89 / 0.78 → 0.82 | 2.96 → 2.51 / 4.22 → 2.97 |
| 小窗 | 0.97 → 1.05 / 1.14 → 1.25 | 5.29 → 3.42 / 5.08 → 3.75 |
| 大面积原版天空盒 | 2.59 → 2.21 / 2.75 → 2.16 | 3.92 → 3.44 / 5.85 → 6.46 |
| 大面积全景 | 2.30 → 1.84 / 2.05 → 1.68 | 4.64 → 3.07 / 6.32 → 5.30 |

两版原版对照的所有 A/B 截图逐像素一致。1.21.1 空场景不透明阶段 CPU P95 从约 0.027 ms 降到 0.0005–0.0006 ms，整帧波动未呈现同向改善。26.2 有窗口的原版场景，从每帧复制两张 1280×800 全屏图减到零（合计 2,048,000 像素／帧）；大面积原版天空盒的整帧数据有波动。另修正测试墙脚避免替换地表，[26.2 修正地形对照](<../build/perf-baseline/summary-262-floor.json>)的关闭画面与无天空盒对照像素完全一致，1.21.1 原版也完全一致。

### Iris 光影

1.21.1 Iris 1.8.1、26.2 Iris 1.11.4，分别运行 **Complementary Reimagined r5.9.3** 与 **iterationRP Alpha 0.8.31**，均通过八场景（空场景、小窗、大窗、全景、全局关闭、玩家覆盖开启、玩家重置、全局恢复）。1.21.1 的光影关闭样本颜色／深度改动均为 0，ITRP 材质桥接仅在可见时工作。26.2 的关闭样本在两套光影中均为 0 个天空盒面、0 拷贝，并确认方块仍在存档。另测了 26.2 iterationRP 的真实玻璃透射，确认材质桥接激活且玻璃可见天空材质变化，归一化差值 0.724（普通背板对照 0.154）。

26.2 Complementary 匹配投影时，原路径每个可见帧都绘制第二张私有天空画布，优化路径 360 帧中均无需重绘；大窗 P95 为 11.21 → 10.41 ms、P99 为 12.33 → 11.19 ms。iterationRP 有抖动，仍保留重绘，重复测量未见稳定的整帧提速。光影 pack 的时相、TAA 和曝光会让跨运行截图产生像素差异，不能当作像素等价证明；用渲染计数、深度、材质桥接和截图一起核对。本机未复现低配环境，未宣称所有光影／场景会提速；DH 与 Immersive Portals 的完整实机组合不在这次矩阵内。

**碰撞实测**：26.2 生存模式从 `z=0.5` 向方块墙走，显示和隐藏时均停在 `z≈4.70`，移除墙的对照走到 `z≈15.35`。见[碰撞记录](<../build/perf-baseline/collision-262.json>)。游戏实例与用户存档未修改。

## 样本、日志和复跑

- [实机截图对照](<../build/perf-baseline/render-toggle-preview.png>)。
- 原版汇总：[1.21.1](<../build/perf-baseline/summary-1211.json>) · [26.2](<../build/perf-baseline/summary-262.json>)。光影汇总：[主线 Complementary](<../build/perf-baseline/summary-1211-complementary.json>) · [主线 iterationRP](<../build/perf-baseline/summary-1211-itrp.json>) · [26.2 Complementary](<../build/perf-baseline/summary-262-complementary.json>) · [26.2 iterationRP](<../build/perf-baseline/summary-262-itrp.json>) · [26.2 iterationRP 重复](<../build/perf-baseline/summary-262-itrp-repeat.json>)。
- 上述性能采样来自 `test.4`；历史 `test.5` 只修正开关的命令参数及其回归。当前工作树在此基础上新增了可配置快捷键、1.21.1 大量方块收集路径的直接批次写入/区块缓存，以及巨构废案停用；本轮没有把这些源码变化冒充为新的 FPS 长测数据。[历史 1.21.1 构建日志](<../build/perf-baseline/build-bool-1211.log>) · [历史 26.2 构建日志](<../build/perf-baseline/build-bool-262.log>) · [26.2 玻璃透射报告](<../mc26.2/build/render-regression/perf4-262-itrp-glass/glass-itrp-report.json>)。历史 `test.5` 两版各通过 976 项命令断言、297 项环境属性断言和 115 项存储／同步断言，HDR/PIZ 原样本注册均无告警。
- 使用 [1.21.1 运行器](<../tools/render-regression.py>) 的 `--suite perf`，用 `--perf-baseline` 跑旧路径，去掉参数跑新路径。光影额外传 `--profile iris --shader <光影包文件名>`。使用 [26.2 运行器](<../mc26.2/tools/run-skybox-perf.py>)，传 `--jar`、`--label`，可加 `--shader`；本机测试设置 `SKYBOX_JAVA25_EXE` 指向 Java 25。用 [汇总脚本](<../tools/summarize-skybox-perf.py>)计算 P50/P95/P99、缓冲复制和 RGB 差异。

## 构建

| 版本 | JAR | SHA-256 |
|---|---|---|
| 1.21.1 | [Fabric 发布包](<../build/libs/skyboxblock-1.0.8-test.5-perf-bool-1.21.1.jar>) | `CCEDAA5E564C15CAE3B95C307CF01DBCB062D1F3E2EDEE5A58505517F183F53E` |
| 26.2 | [Fabric 发布包](<../mc26.2/build/libs/skyboxblock-1.0.8-test.5-perf-bool-mc26.2.jar>) | `05F351AF90EA936EC5CB452118359907FF01B4581A6FED25CF964CA46D842D72` |

1.21.1 使用 Loom `remapJar`，主入口 0 个 Mojang 命名引用、29 个 intermediary 引用，refmap 通过；26.2 使用原生官方类名。
