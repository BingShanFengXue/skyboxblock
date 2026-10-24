# 天空盒渲染接手记录（2026-10-18）

## 原始状态与备份

- 工作区与项目均无 Git 元数据；保留现有实现，不重置其他人的进度。
- 接手前源码、资源、构建设置与工具快照：[备份 zip](../../artifacts/snapshots/skybox-before-render-takeover-20261018-230911.zip)。
- 基线命令：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/verify-javac.ps1 -Tag takeover-baseline`。
- 基线结果：15 个 Java 源文件编译通过，退出码 0。
- 参考实例已确认：MC 1.21.1，Fabric，Iris 1.8.1，Sodium 0.6.7，Immersive Portals 6.0.7，Distant Horizons 3.3.3。
- 检查时没有游戏客户端运行。实机回归使用工作区内独立实例与生成的测试世界，避开用户存档。

## 已发现的证据与待验证项

1. `style_probe` 在顶点计算等距柱状 UV，再按屏幕空间线性插值；跨经度接缝与近距离大面时，这不等价于逐像素的视线方向采样，会产生接缝和三角形畸变。
2. 实时天空路径默认在非光影状态下只烘焙一次全景，随后一直优先使用缓存；这与实时昼夜、天气语义矛盾。Iris 仅安装但关闭光影包时也会触发该烘焙分支。
3. GL 抓帧快照只记录进入时激活单元的绑定，实际修改的是单元 0；激活单元非 0 时会留下纹理绑定污染。延迟绘制也遗留 shader、纹理单元与部分 GL 缓存状态。
4. 源码在 BER 阶段组合全局 ModelView 与传入 PoseStack；当前渲染模组组合下 PoseStack 是否已含相机变换，要用独立场景对照选框与实际屏幕覆盖验证，避免再次依靠假设修矩阵。
5. 抓帧只使用 viewport 的宽高、忽略原点；世界切换与捕获开关的缓存失效也需要覆盖。
6. 配置文件当前覆盖 JVM 参数，和 README 的“JVM 参数覆盖”承诺相反。
7. 目前只对正 w 做裁剪；补充完整齐次视锥、有限值、近距离和大视角的数学回归。

## 验证约定

- 源码正确性：项目 `verify-javac.ps1`（Windows PowerShell 5.1）。
- 数学回归：固定种子、独立参考实现，覆盖投影、裁剪、方向连续性与 pass 嵌套。
- 实机回归：原版 Fabric、Iris 无包、Iris 光影、Iris + IP + DH；截图必须来自本次运行并人工读图。
- 结论区分“源码确认”“自动测试通过”“实机截图通过”；不以 `glError=0` 代替像素证据。

## 最新验收标准（用户澄清）

光影开启时允许天空盒显示原版天空，以几何、遮挡、稳定性和观感为准；希望天空盒同时显示原版云。不把“与光影包最终天空不同”当成缺陷，也不把整个 Shadered 的所有动态系统逐项移植当作默认要求。当前正在增加独立原版云网格、纹理与 shader 的离屏合成，避开 Iris/Sodium 的云渲染接管。

## 修复与实测结果

### 首轮实现与验证

- 已修复：预设经度接缝与三角形畸变、真实齐次视锥裁剪、默认一次性全景导致的昼夜冻结、根视图矩阵来源、捕获 viewport 原点、纹理单元/着色器/深度等 GL 状态恢复、世界/资源代际缓存与配置覆盖顺序。
- 基线和首轮修复分别在原版 Fabric、Iris 无光影包环境各运行 12 个场景；像素检查从每组 5/10 提升到 10/10。
- 三组 IP + DH 组合（Iris 无包、Complementary、iterationRP）均运行 12 个场景；每组仅 8/10 像素检查通过，实时天空仍不匹配组合环境最终天空。
- 组合日志还发现断线回调可能在网络线程执行；已改为客户端线程调度并完成构建，组合退出复测待做。
- 当前构建：17 个主源码文件，`tools/build.ps1 -Task build` 退出码 0，`BUILD SUCCESSFUL in 12s`；Gradle 的单元测试仍为 NO-SOURCE，不据此称有数学单测。
- 用户补充了原始提示词后，已按原目标核查：门户内部缺失、光影天空来源缺口、Shadered 静态预设非等价移植、DH LOD 遮挡证据不足、参考分支和许可声明不一致。
- IP 文件名为 6.0.7，但运行时 Core/加载器/握手报告 6.0.6；后续以运行时证据记录。

完整检查、数值、历史差异与用户澄清见 [原始需求验收报告](ORIGINAL-REQUIREMENTS-AUDIT-2026-10-18.md)。

### 2026-10-19 原版云与 ITRP 交付

- 新增 `CloudMeshAccess` 与 `SkyVanillaClouds`：复用游戏云网格生成器/纹理/昼夜颜色，自己的 shader、VBO 和带深度探针；不重入 Iris/Sodium 的云管线。
- 主源码 19 个文件；新增独立数学回归并接入 Gradle `check`：252,116 断言、30,000 个裁剪面、1,000 条逆 VP 射线等通过。
- 原版、Complementary、ITRP 各 17 场景，29/29 像素断言；两套光影分别带 IP+DH，云专项各 6 场景、23/23；云关闭对照 18/18。
- 组合首次超时停在 DH 联网更新检查、尚未进世界；后续仅在隔离夹具关闭其更新检查后完成，未关闭 LOD 渲染。组合登录阶段 4 条区块加载 ERROR 保留待归因。
- 交付 [1.0.1 jar](../build/libs/skyboxblock-1.0.1.jar) 已完整构建，并用实际产物再跑 ITRP 6 场景、23/23、ERROR/渲染线程异常均 0。详见 [ITRP 报告](ITRP-TEST-2026-10-19.md)。
- 用户原参考实例的配置和存档未改动；门户内部、透明前景与 LOD 遮挡仍在后续待办，未标为完成。

### 门户后续定位索引（未实施改写）

- 安装的 IP `PortalRendering` 有 `isRendering()`、`getRenderingCameraPos()`、`getRenderingPortal()` 与 `isRenderingOddNumberOfMirrors()`。
- `getRenderingPortal().getInnerClipping()` 返回 `qouteall.q_misc_util.my_util.Plane`，记录访问器 `pos()` / `normal()`；该类在 IP 主 jar，不在 DimLib 嵌套 jar。
- Iris 的 `iris$endLevelRender` 在 `LevelRenderer.renderLevel` RETURN 调用 `finalizeLevelRendering()`；IP 的 `IrisPortalRenderer` 另有 `onBeforeHandRendering` 与分层 deferred framebuffer 合成。
- 所以后续不应只删掉根 pass 过滤；需要每视角几何/天空上下文、正确的目标与模板/裁剪恢复，再以真实门户场景验证。现有有效云实现已额外归档快照。

