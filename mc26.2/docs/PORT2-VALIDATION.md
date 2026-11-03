# port.2 修复与最终验收记录

**状态：最终验收通过。** 完成时间：2026-11-03（Asia/Shanghai）。本页取代旧 port.1 交付结论；工作从 2026-11-02 开始。

## 交付与字节一致性

- [Minecraft 26.2 jar](<../output/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar>)：**17,015,597 字节**。
- SHA-256：`45c256728cdac6a75f3e71b1a1b6af1335de53e753702aa3dee79aa450c5daa8`。
- [哈希文件](<../output/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar.sha256.txt>)、[完整机器汇总](<../output/port2-validation-summary.json>)、[实机截图总览](<screenshots/port2/overview.png>)。
- 汇总工具逐项核验所有最终 case 的 `preparation.modSha256` 与交付 jar 相等；不是另一个未运行的重打包版本。
- 最终 **210 个正常场景通过，另有 7 个同 jar 负控场景**。每张 PNG 都有同帧 JSON；完整实例、日志、取证及旧候选保留在项目 `build/render-regression/`，未清理历史归档。

## 修复内容

| 问题 | 根因与修复 |
|---|---|
| 方块物品名称不本地化 | 26.2 `BlockItem` 不再借用方块描述键；注册时显式 `useBlockDescriptionPrefix()`。九种内置方块名/物品名实机核对，中英语言文件与 1.21.1 基线逐字节一致，无重复 `item.*` 镜像键。 |
| 动态天空拉伸、分割、走动错误 | 删除实体材质上的逐顶点屏幕/全景 UV，改为片元完整逆 VP 双点射线。保留原主线共享端点 double-before-float 精度与 256 格可见距离。 |
| 光影真实天空被污染 | 删除 START_MAIN 私有重入原版 `SkyRenderer` 和旧 haze/cloud RenderPipeline；私有场景不改变 Iris phase、override 或主目标标志。 |
| 深度、前景、手部覆盖 | opaque 写真实反 Z 深度；RG32F 记录 depth/material owner；当前 view 的 Iris final 前保存世界/手部深度，final 后只恢复仍属于本材质的窗口，并保留透明叠加。 |
| 玻璃折射背景 | 对实际 ITRP0.8.31/Iris1.11.4，校验包哈希、程序布局、纹理归属后补合法 solid/normal MRT，并在真实 composite40 draw 前向实际 colortex6 scene input 注入线性 HDR。未在最终屏幕硬涂玻璃，未关闭 TransparentGuard。 |
| 暗天空改变前景曝光 | 恢复 1.21.1 的 meter-only atlas 代理：只在 dark styles 的真实 composite74 compute 期间替换测光 sampler，finally 恢复 texture/sampler/activeUnit；不改场景/泛光 RGB，不写曝光图，不禁自动曝光。 |
| GL 上下文兼容 | Minecraft 实机为 OpenGL3.3，不是显卡最大版本；indexed blend 走 core/ARB/global 分支，隔离并精确恢复 foreign XOR、blend equation、MRT mask、FBO/viewport/program/VAO/纹理/sampler/深度等状态。 |
| 云、月纹、环境与时间 | 接入原生状态与云 TextureData，恢复高位 ARGB、透明裁剪、smoothstep 淡出、月亮 UV 和 nearest 采样；接通服务端/逐玩家设置。锁定当前时间从原生 SUN_ANGLE 度数转换，不再把 ticks 直接当 phase。 |
| 资源与 DH | 生成包声明 26.2 的 88.0 格式；自定义全景、同尺寸彩云重载实测；DH 使用实际当前视图 projection/depth API，不猜近远裁剪面。 |

风格 0 沿用 1.21.1 主线的**独立原版天空场景**定义，不是抓取各 shaderpack 的专有大气/云算法；真实世界天空仍由 shaderpack 自己绘制。

## 环境与光影样本

- Minecraft 26.2，Fabric Loader 0.20.1，Fabric API 0.161.0+26.2，Java 25。
- Windows 11，RTX 4060 Ti，NVIDIA 621.01；实机 OpenGL3.3，另有隐藏 OpenGL4.5 对照。
- Iris 1.11.4+mc26.2、Sodium 0.9.2+mc26.2；DH 3.3.3。
- 先运行无本模组的对照，确认已有样本本身在 26.2 正常，再加入最终 jar。未把样本自身异常归咎于模组。

| 环境 | 九种风格/全景 oracle | 前后深度连续性 | 真实走动/旋转/bob | 昼夜/雨场景 | 玻璃背景响应 |
|---|---|---|---|---|---|
| Iris 无 pack | 通过 | 通过 | 通过 | 通过 | 原生高级场景检查 |
| iterationRP Alpha 0.8.31 | 通过 | 通过 | 通过 | 通过 | 通过，专用 HDR 桥已验证激活 |
| Complementary Reimagined r5.9.3 | 通过 | 通过 | 通过 | 通过 | 通过，generic 路径 |
| Photon 1.4 | 通过 | 通过 | 通过 | 通过 | 通过，generic 路径 |
| BSL 10.1.1 | 通过 | 通过 | 通过 | 通过 | 通过，generic 路径 |

[样本哈希](<../build/render-regression/reference-final-r2/shader-samples-sha256.json>)与[无模组对照图](<../build/render-regression/reference-final-r2/reference-montage.png>)保留。专用桥只锁审计过的 ITRP zip SHA256 `334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8`。

## 最终量化结果

[核心矩阵](<../build/render-regression/verified-port2-core/matrix-report.json>)：**145 场景通过**。

- 15 组不同前后面深度/机位，共 **8,420,790 个天空内区像素**；前后图最大逐像素差 **0**。
- 60 帧独立 CPU PNG 双线性/逆 VP oracle：最差平均绝对误差 **0.179804/255**，最差 P99 **1.103340/255**。
- 30 帧真实走动图像，记录实际位置、旋转和非零原点偏移，最大观察偏移 **0.099989**，并非仅检查 `bobView=true`。
- 原生参照矩阵来自真实 render 参数；Iris 参照独立采自真实不透明阶段 Gbuffer uniforms，不从被测 Renderer 复制矩阵。

[原生 Fabric 高级回归](<../build/render-regression/verified-port2-plain/advanced-report.json>)：**14 场景通过**。

- 自定义注册全景通过独立图像 oracle；资源重载后再次通过。
- 原生月亮同机位 ROI：平均差 **0.001583/255**、P99 **0**、最大差 **63**；报告保留极少量局部差，不宣称全 ROI 完全相同。
- 彩云红色贴图与同尺寸蓝色半透明贴图（A=179）重载，两次主色占比均 **100%**，平均图像变化 **82.042859/255**；ARGB/alpha/裁剪另有生产字节码回归。
- 锁定当前夜晚后把真实世界改为白天，窗口仍保持夜晚；切换 RUNNING 白天后恢复明亮。命令解析及执行结果均记录。
- 砂岩、染色玻璃、第一人称手持物和 HUD 已检查。

[重连](<../build/render-regression/verified-port2-plain/rejoin-report.json>)：**首次权威快照通过**，未重新执行设置命令即恢复持久化逐玩家绿色天空。

DH： [原生](<../build/render-regression/verified-port2-dh-off/dh-smoke-report.json>)与[Iris＋ITRP](<../build/render-regression/verified-port2-dh-itrp/dh-smoke-report.json>)各 4 场景通过；以 yaw37°/pitch−25° 非对称旋转确认 `observed DH 3.3.3 view projection` 和 `current-view DH depth configured`，不是仅检查已安装。

### 折射与测光专项

- [四包玻璃量化](<../build/render-regression/verified-port2-glass/glass-itrp-report.json>)与[DH＋ITRP玻璃](<../build/render-regression/verified-port2-glass-dh/glass-itrp-report.json>)通过；用天空白/黑与普通混凝土白/黑背景响应，而非只看画面方差。
- 玻璃归一化响应：ITRP **0.718920**、Complementary **0.747028**、Photon **1.450926**、BSL **0.899872**；各报告保留普通实体背景对照。
- [代理开启](<../build/render-regression/verified-port2-meter/dark-meter-report.json>)：黑/银河固定前景亮度比自然天空约 **0.944217 / 0.945302**。
- [同 jar 关闭代理负控](<../build/render-regression/verified-port2-meter-negative/dark-meter-report.json>)：约 **1.246951 / 1.243540**，复现约 24.5% 增亮。开启、原 atlas/sampler 恢复、负控保留原 compute 的日志分别确认。

## 构建与协议验证

[最终构建](<../build/audit-r2/meter-image-owner-build.log>)：`gradlew build regAll --offline --console=plain`，**BUILD SUCCESSFUL**；12 套入口（含本地化），合计 **293,693 项逻辑/结构断言**。

原生 GL/契约回归共 **110 项**（其中 dark-meter 含 13 项结构检查，不冒称全部是 GPU 检查）：

- [GL3.3](<../build/reports/native-gl/SkyGl33Regression.txt>) 11 项：真实 core40=false 的 ARB 路径、XOR/subtract 中和与精确恢复。
- [GL4.5](<../build/reports/native-gl/SkyIsolatedGlRegression.txt>) 19 项：生产 GLSL、反 Z、材质/前景、跨深度恢复和透明保护。
- [ITRP scene input](<../build/reports/native-gl/SkyItrpSceneInputRegression.txt>) 16 项：真实 GL3.3 MRT/HDR 及偏移采样 negative control。
- [meter proxy](<../build/reports/native-gl/SkyItrpDarkMeterRegression.txt>) 64 项：生产 substitute/render、ARB compute/image 真入口、unit11/entry13恢复、原 compute 写曝光、scene/bloom不变等。
- [安装包/当前字节码契约](<../build/audit-r2/final-itrp-scene-contract.log>)另有 **18 项**：实际程序顺序、采样目标、indexed draw、MC GpuTexture API，以及 Iris 对 2D image 也设置 layered=true 的真实绑定契约。

## 判据与范围

- 所有正常/负控图像均检查 windowVisible=false、windowFocused=false、mouseGrabbed=false；驱动逐例自动关闭 JVM，原 PCL 实例配置/旧 jar 不覆盖。
- 旧固定帧 `world-frame.png` 可能是合成前中间目标；本轮在 `GameRenderer.render` RETURN 截最终目标。
- 背景平面相交不等于真实可见天空。初版夹具漏清负 z 草唇，被 TAA 轮廓污染；双端 GPU 深度/owner 取证后修夹具，未放宽色差、未关闭透明保护。见[根因记录](<TEMPORAL-ROOT-CAUSE.md>)。
- bob 可位于 P 或 V；仅用 Camera getter 会误判 Iris。全局时间命令为 `/skyboxblock environment time ...`。
- 关键 render/Mixin/framebuffer/原生函数异常检索为 0；离线账号认证网络错误与刻意注入坏 properties 的回归警告不冒称“全部日志零错误”。
- 准确目标 **26.2 / OpenGL**；26.1、26.3、Vulkan不标已验证。光影结论限列出的版本/实际默认配置，不泛化所有 FSR/PBR/私有选项。
- DH smoke不等同所有复杂LOD/跨维度/多门户压力场景穷举。门户隔离/裁剪代码保留，但未安装26.2 Immersive Portals实机验收，未列为通过组合。

复现入口见[README](<../README.md>)和[全套驱动](<../tools/final-acceptance.py>)。源码已在 `artifacts/snapshots/` 只进不出的快照中归档；最终源在回归期间保持冻结。普通游戏不要添加 `skyboxblock.fixture.control` 测试参数。
