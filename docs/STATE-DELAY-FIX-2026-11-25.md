# 天空盒状态更新延迟：录屏分析与修复记录

日期：2026-11-25（Asia/Shanghai）

## 状态

26.2 已实施按需补漏修复；1.21.1 保持原样。构建产物为独立 **1.0.8-test.6-fadefix**，不覆盖 test.5 基线。修复不扩大任何版本的 256 格块实体可见距离。

## 用户现象与证据

用户反馈为“被遮挡后快速移动至可见视角时更新不及时”，并补充蓝色破口/整片平面短时缺失。对话提供的 MOV 时长约 62 秒、分辨率 1280×720；已抽取代表帧并生成[时间序列总览](<../build/state-delay-video-contact.png>)。录屏可见天空盒大面出现蓝色缺口，之后相机快速移动；该形态与“服务端开关等待确认”不符，故本轮不修改开关/网络状态链。

## 根因

26.2 原版 `LevelExtractor.extractVisibleBlockEntities` 遍历 `LevelRenderer.visibleSections()` 时，若可见 section 的 `getVisibility(now) < 0.3`，会跳过该 section 内**全部** block entity 的 render-state 提取。默认区块淡入时长为 0.75 秒，因而新建/重置 section 前 0.75×0.3=**225 ms** 不提交其中的天空盒方块实体。天空盒方块没有原版模型兜底，所以这段时间窗口直接露出原版蓝天；快速移动持续引入新 section，会让缺口反复出现。时长是 wall-clock 门限，FPS 越高，缺口越连贯可见。

1.21.1 主线没有同等的 section-fade BE 提取门控，因此没有对它套用无关的回退扫描。

另一个独立限制是两版 `SkyBlockEntityRenderer.getViewDistance()` 都保持 256。子个体只读审计确认 26.2 是严格中心距离 `<256`（等于 256 也剔除），参考点为相机位置；该限制产生静止时仍持续存在的远距裁切，并非 225 ms 淡入洞。本轮按用户选择保留此值。

## 修复

在 26.2 的 `SkyWindowRenderer.drawOpaque()`、锁定收集状态前，增加根视图补漏：

- 仅当天空盒开启、区块淡入选项大于 0、且处于根视图时扫描当前 `visibleSections`；选项设为 0、隐藏天空盒或非根视图时直接跳过。
- 仅对仍低于 0.35 的 section 检查已编译 mesh 的 BE 列表。0.35 在原版 0.3 门限上增加 0.05（约 37.5 ms）交接余量，降低“提取刚越过门限、opaque 阶段也刚越过”的单帧空洞。
- 使用原有 `SkyBlockEntityRenderer.shouldRender()` 保留 256 格距离策略；使用线程本地懒初始化的单个临时 render state，并让实体进入现有 collector、`seen` 去重和正常批次绘制链。没有逐帧创建 render-state 对象；懒初始化也保持纯结构回归可在无游戏 registry bootstrap 环境运行。
- 不修改原版渲染器、区块淡入设置、区块可见性列表或 GL/GPU 合成路径；无新增全屏拷贝或全局 block entity 扫描。
- 补漏扫描纳入现有 `OPAQUE` CPU 计时；只在根视图逐帧遍历可见 section 以读取淡入值，仅低于交接阈值的 section 扫其 BE 列表。该额外成本需以实机 `OPAQUE_CPU_ns` 与高频帧场景复测量化；本轮静态回归不冒充 FPS 实测。

## 变更文件

- [26.2 渲染器：淡入补漏](<../mc26.2/src/main/java/dev/yg/skyboxblock/client/SkyWindowRenderer.java>)
- [26.2 核心回归：阈值与 bytecode hook](<../mc26.2/src/test/java/dev/yg/skyboxblock/client/SkyWindowCoreRegression.java>)
- 1.21.1 源码未变；两版 256 格距离均未变。

## 验证

- 在一次中间尝试中，静态初始化 render state 触发无游戏 registry bootstrap 的纯逻辑测试异常；已改为首次真实补漏时才初始化的线程本地复用对象，随后最终完整构建与 `regAll` 通过。
- 最终命令：`JAVA_HOME=D:\\Program Files\\Eclipse Adoptium\\jdk-25`、项目内 `GRADLE_USER_HOME=mc26.2/.gradle-home`，`gradlew.bat build regAll --offline --no-daemon --console=plain -Pmod_version=1.0.8-test.6-fadefix`。
- 最终结果：**BUILD SUCCESSFUL**，26 actionable tasks（22 executed、4 up-to-date）；`build` 与 `regAll` 全部通过。
- `SkyWindowCoreRegression`：**1199 项通过**，其中新增4项淡入阈值边界断言、6项实际 class-file hook 结构断言（可见列表/visibility/已编译 mesh/当前世界实体身份/原实体距离筛选/补漏早于 drawn）。这确认补漏挂在原版提取时序之后、opaque 收集锁定之前，且没有换掉256格距离门。
- 其他核心统计：server commands 976、server backend 115、环境297、时钟24,003、天空时间68,012、场景语义199,046；build另通过自定义配置194、HDR解码3734、材料身份545、HTML panorama 11、EXR/TX 44等回归。server backend 的格式故障 WARN 和注册器合成 EXR 的 1 条预期形状 WARN 均来自有意负例/测试素材，不是构建失败。
- 成品 [1.0.8-test.6-fadefix-mc26.2 JAR](<../mc26.2/build/libs/skyboxblock-1.0.8-test.6-fadefix-mc26.2.jar>)：17,114,003 字节，SHA-256 `FD95D75639209EC7240BC75A57DFBE6FA777354A80ED20DC8D0CD05B9C9D6D16`；JAR 内容已核对含 `SkyWindowRenderer.class`、`fabric.mod.json`、线程本地状态复用及当前实体身份校验字节码。
- 代码 diff check 通过；工作树改动仅为 26.2 渲染器、核心回归与本报告三项。
- 实机矩阵：本轮未运行游戏实机，最终仍需在新 section 加载场景验证区块淡入0与0.75秒两组；该证据与本地编译/逻辑回归分开记录。
- 256格远距裁切：保留为独立限制；建议之后用 RD≥16 的静止远看/半径边界实验确认，不与淡入修复合并验收。
