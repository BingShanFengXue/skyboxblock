# skyboxblock — Minecraft 26.2 / port.2

本项目是[1.21.1 主线](<../README.md>)的 **26.2 Fabric 移植**，位于同一仓库的 `mc26.2/` 目录。`port.2` 重做了首版移植的天空渲染路径，并修复物品名称的描述键来源。

- 发布产物：[`skyboxblock-1.0.6-test.1-port.2-mc26.2.jar`](<https://github.com/BingShanFengXue/skyboxblock/releases/download/v1.0.6-test.1-port.2-mc26.2/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar>)（Release 附件），SHA-256 `45c256728cdac6a75f3e71b1a1b6af1335de53e753702aa3dee79aa450c5daa8`。
- 本目录许可与 1.21.1 主线相同：代码 MIT（`LICENSE`），第三方归属见 `NOTICE` 与 `THIRD_PARTY_NOTICES.md`。

## 安装

- 产物：`output/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar`；替换旧包，不要同时保留两个同 id 的模组。
- Minecraft **26.2**、Java **25**、Fabric Loader **0.20.1+**、对应的 Fabric API。
- 当前渲染实现针对 **OpenGL**；本轮不把 Vulkan、26.1 或 26.3 标记为已适配。
- Iris/Distant Horizons 为可选依赖；具体版本、实测项目和边界见[验证记录](<docs/PORT2-VALIDATION.md>)。
- 原 PCL 实例的模组、选项及光影配置不由回归工具覆盖；测试使用项目 `build/render-regression/` 下的隔离实例。

## 本轮修复

- 注册 `BlockItem` 时显式使用 `useBlockDescriptionPrefix()`，沿用与 1.21.1 一致的中英 `block.*` 语言键，不以重复 `item.*` 翻译掩盖注册问题。
- 撤掉 `START_MAIN` 中重入原版 `SkyRenderer` 的做法，不再让私有天空触发 Iris 的天空 phase 或未注册管线覆盖。
- 方块实体仅收集几何；在所属视图的不透明阶段写入天空颜色与真实深度，在 Iris final 后按 **材质归属 + 当前世界深度**恢复窗口颜色，保留前景及透明叠加。
- 全景在片元中通过完整逆 VP 的两个有限深度点重建无限远方向，避免逐顶点 UV 的透视拉伸、分块和走动摇晃错误。
- 使用原生天空状态/资源及独立场景绘制日月星、晨昏、雾与体积云；恢复云贴图 ARGB、透明裁剪、smoothstep 淡出及原生月纹 UV。
- 恢复 256 格可见距离与共享网格端点的 double-before-float 精度；恢复服务端/逐玩家环境与私有时钟消费，修正“锁定当前时间”的 ticks/相位单位混用。
- 对已审计的 ITRP 0.8.31/Iris 1.11.4，按包哈希、程序和纹理归属校验补齐真实 HDR 折射 scene input；暗风格只替换测光采样 atlas，原曝光 compute 和场景/泛光 RGB 保持不变。
- 新增可选的 DH 当前视图深度观察与门户视图隔离；不以“检测到模组”替代实机兼容性结论。

## 构建与回归

在项目目录中，使用已安装的 JDK 25：

```powershell
$env:JAVA_HOME = '<JDK 25 安装目录>'          # 本机需自备 JDK 25；无需改仓库文件
$env:GRADLE_USER_HOME = "$PWD\.gradle-home"
.\gradlew.bat build regAll --offline --console=plain
```

`--offline` 适用于已有完整依赖缓存的情况；首次构建去掉它让 Gradle 拉依赖。原生 GPU 验证另走[隐藏窗口 GL 回归](<tools/run-native-gl-regression.py>)；本机 GPU/运行库前置和结果在验证记录中列出。

### 实机验收工具

- [隔离实例与文件控制入口](<tools/runtime-regression.py>)：不操作原实例，记录正在使用的光影、真实机位、名称、窗口焦点和鼠标抓取状态。
- [五环境视觉矩阵](<tools/run-live-matrix.py>)：独立 CPU 全景 oracle、前后深度连续性、真实移动/旋转及非零 bob 变换。
- [原生高级回归](<tools/advanced-runtime-regression.py>)：自定义全景、日月、彩色/透明云重载、服务器时钟及首次登录快照。
- [GPU 输入取证](<tools/temporal-debug.py>)：仅显式测试开关启用时读取本模组的归属/深度/透明快照。

测试进程通过 `-Dskyboxblock.fixture.control=...` 明确启用夹具；普通游戏不启动文件控制、不隐藏窗口，也不阻止正常鼠标抓取。夹具进程在创建窗口时取消焦点请求、持续保持隐藏，并禁止鼠标抓取。

## 文档

- **当前结论**：[PORT2-VALIDATION.md](<docs/PORT2-VALIDATION.md>)。
- [本地化根因及负向回归](<docs/LOCALIZATION-26.2.md>)。
- [1.21.1 与 26.2 语义复核及误判纠正](<docs/CORE-SEMANTICS-REVIEW.md>)。
- [前景 TAA 轮廓与天空内部连续性的判据区分](<docs/TEMPORAL-ROOT-CAUSE.md>)。
- [旧 port.1 交付历史](<docs/DELIVERY.md>)保留供追溯，不作为 port.2 的验收证据；旧固定帧 `sky-frame.png`/`world-frame.png` 不参与本轮判定。

`src/legacy-1.21.1/` 和旧多版本分支保留为历史参照。本轮的准确目标版本由模组元数据限定为 26.2。
