# 渲染距离指令与缺面复查（2026-11-26）

## 本轮范围

1. 两版（1.21.1 主线与 26.2 移植）新增**本机**天空盒方块渲染距离指令。
2. 复查上一轮“遮挡后快速显露时天空盒面短暂缺失”的残留问题，调整 26.2 的补收策略。

## 指令

```mcfunction
/skyboxcustom renderdistance            // 查询当前值与默认/上限
/skyboxcustom renderdistance set 512    // 设置本机渲染距离（16..4096 格）
/skyboxcustom renderdistance reset      // 恢复默认 256 格
```

- 属性文件键 `skyboxRenderDistance`，也接受 `-Dskyboxblock.skyboxRenderDistance=512`。
- 默认 **256**，范围 **16..4096**；4096 实际大于原版可达视距（≤32 chunk ≈ 512 格），因此等于取消方块实体距离门。
- 只影响本机视觉；即使服务器通过 `/skyboxblock env` 统管天空外观，本机距离仍可单独调整。
- 距离值由 `SkyBlockEntityRenderer.getViewDistance()` 每帧读取，改完无需重启。

## 缺面复查与修复

上一轮 26.2 只对“淡入进度低于 0.35 的可见区块”补收天空盒面。复查又确认了三处残留：

1. `visibleSections` 仅在视锥更新时重建，快速转动/移动后的一段时间里，刚进入视野的区块不在列表中。
2. 依赖淡入数值阈值，提取与绘制之间跨过阈值时仍可能整帧漏收。
3. **Sodium 会接管原版方块实体提取**（`LevelExtractorMixin` 取消原版提取、走自己的 `SortedRenderLists`），此时原版可见区块列表可能为空或不维护，扫描它完全无效。

因此本轮改为：两版都在不透明绘制前遍历**本模组自己维护的已加载天空盒实体索引**（`SkyBlockEntity.loaded()`），不依赖任何渲染器的区块列表：

- 索引只在**客户端世界**注册/注销，使用弱引用集合，避免强持有服务端或已卸载世界；
- 先按本帧 `seen` 去重（正常收集的方块零额外开销），再校验 `ClientLevel.getBlockEntity(pos)` 身份，最后才做距离平方比较与状态提取；
- 距离边界与原版一致：严格小于配置值；
- 不新增全屏拷贝、不新增全局实体遍历、不改变 GL 路径。

## 验证

- **1.21.1**：`gradlew build` **BUILD SUCCESSFUL**，`SkyGeometryRegression` **389,404** 断言通过（新增距离默认值/上下界/非法值回落/JVM 覆盖/持久化写入与重置共 19 条），环境渲染 42,257、命令 976、时钟 68,012 等全部通过。
- **26.2**：`gradlew build regAll` **BUILD SUCCESSFUL**，26 个任务全部执行；`SkyWindowCoreRegression` **1,198** 项通过（含新增：可配置距离来源、默认 256、上限 4096、越界回落、索引兜底、身份校验、去重与绘制时序）。
- 期间修复了两个真实编译问题：26.2 不存在的 `SkyVanillaClouds` 引用、以及弱引用索引的并发/跨世界问题。
- 实机矩阵本轮未运行（用户自测）；Sodium、Iris/DH 组合与 4096 上限的实际帧率需实机确认。

## 构建产物

| 版本 | JAR | 大小 | SHA-256 |
|---|---|---:|---|
| 1.21.1 | [skyboxblock-1.0.8-test.7-renderdistance.jar](<../build/libs/skyboxblock-1.0.8-test.7-renderdistance.jar>) | 17,707,190 | `BCE36C1A7E84D386ABA1ECABCFF136985130051C0C0A7A56EA39AD8D960745D7` |
| 26.2 | [skyboxblock-1.0.8-test.7-renderdistance-mc26.2.jar](<../mc26.2/build/libs/skyboxblock-1.0.8-test.7-renderdistance-mc26.2.jar>) | 17,115,974 | `117907025D2A5DAD315EB6338293A65E35C0352BD8BF266887F7F4E2D020D148` |

## 已知限制

- 索引只覆盖本模组已加载的天空盒方块；尚未加载的区块依旧需要客户端先加载。
- 4096 实际等于取消距离门；大型天空墙下每个方块仍要计算 6 个面，性能随墙面积增长。
- 本轮没有实机验证 Sodium 下的缺面是否完全消失，用户可优先用 `/skyboxcustom renderdistance 4096` 与默认 256 做对照。

