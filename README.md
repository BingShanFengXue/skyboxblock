# Sky Box Block（天空盒方块）

## R34.2 交界稳定与移动边缘（2026-10-24，已交付）

最终构建 **1.0.3-test.4-r34.2**。ITRP显示端按四采样真实几何深度选择天空材质，消除所测低分辨率抖动材质边界波动；原版按几何覆盖率合成，减轻行走硬边，不整屏模糊。原生DH3.3.3仅在归属／尺寸／格式校验后，用其未污染背景源重算边缘一次合成，解决独立初审发现的重复混合与背景覆盖问题。

- 最终主包 `skyboxblock-1.0.3-test.4-r34.2.jar`（Release 附件）；[交付报告](<docs/EDGES-R34-2-DELIVERY-2026-10-24.md>)；[官方DeepSeek-V4.1-Flash独立终审／复审](<docs/INDEPENDENT-R34-DEEPSEEK-READONLY-2026-10-24.md>)：**声明范围内通过，无阻断**。
- 最终同包612正向场景4191/4191检查，另360控制；全部11组既有回归通过。真实LOD5阶段26/26；真实背景下部分覆盖一次合成RGB MAE0.226，未污染金墙对照MAE<1。数学389385断言、实际包审计94/94。
- ITRP原生／FSR交界时序标准差6.54／11.01降至0；纯原版移动边缘误差下降97.23%，nativeDH下降96.87%，Iris/Sodium/DH关闭光影下降97.05%。这是所测边缘误差，不是FPS或整屏画质提升。
- [最终图像对照](<docs/screenshots/r34-2-final-comparison-20261024.png>)；[实拍采样GIF](<docs/screenshots/r34-2-final-jitter-20261024.gif>)；源码／工具／选定证据 `skybox-r34-2-source-and-evidence.zip`（Release 附件）。
- R33测光、手臂、R32方向、4K晴空与A/B名称保留；手臂白剪切0%。用户安装包、存档、shader ZIP和配置未替换。仍是有范围测试版；历史四红、门户、资源／性能长测不借本轮宣称通过。

以下R33及更早条目均为历史记录；旧R34／R34.1初审反例和源码证据另保留，当前交付以R34.2为准。

## R33 光影暗色密闭空间手臂过曝修复（2026-10-23）

本轮冻结构建 **1.0.3-test.3-r33**。针对末日陆地／银河／纯黑及三者混合暗室，引入仅在曝光compute期间生效的私有测光输入代理，避免虚拟暗背景把物理相机增益推高而漂白手臂。没有修改实际天空显示颜色、没有重画手臂、没有关闭自动曝光／泛光。

- [R33主包](<build/libs/skyboxblock-1.0.3-test.3-r33.jar>)；[执行记录](<docs/DARK-HAND-R33-WORKLOG-2026-10-23.md>)；[交付报告](<docs/DARK-HAND-R33-DELIVERY-2026-10-23.md>)。
- 两种光影分辨率模式下验证三种暗色、混合空间和原版对照；完整进入／驻留30秒／退出／重入30秒时序。同包关闭代理的负对照保留白手臂复现。
- 两张晴空4K PNG、A/B名称、R32双点运动修复和原颜色shader均保留原字节；生成器同步使用4K输入。仍为指定Iris/ITRP组合的有范围测试版，不扩大历史兼容与性能结论。

## R32 历史全景行走／视角摇晃修复（2026-10-23）

本轮冻结构建 **1.0.3-test.2-r32**。针对新增非纯色天空在行走时抖动、变形及显示同材质后排轮廓的问题，将全景方向改为逐屏幕像素重建，不再从具体方块面插值坐标推导。两个晴空显示名统一为「晴空·A」「晴空·B」，内部ID不变。

- [R32主包](<build/libs/skyboxblock-1.0.3-test.2-r32.jar>)；[执行／复现记录](<docs/PANORAMA-MOTION-R32-WORKLOG-2026-10-23.md>)；[实际包审计261/261](<build/reports/r32-package-audit.json>)。
- 修改限定在全景采样分支；原版专用shader／类、原版与纯色颜色分支、几何／深度／HDR主体和全部PNG均保留R31原字节。原版作为对照，不当作本轮故障路径。
- 新回归开启原生视角摇晃并真实行走／转头，验证实际相机移动与完整相机变换的非零偏移；不再只靠关闭bobView的静态图。最终同包四环境416场景2,632/2,632（各82运动+22定向回归）；旧公式8个运动帧全部复现分割。数学回归389,385断言，包审计261/261；[完整交付报告](<docs/PANORAMA-MOTION-R32-DELIVERY-2026-10-23.md>)。

## R31 历史材质扩展（2026-10-23）

本轮冻结构建 **1.0.3-test.1-r31**。原版方块名称现为「天空盒方块（原版）」；保留原版ID与R30修复，新增纯白、纯黑及用户 `04-定稿` 全部6套适用素材，共9种方块。全景按连续世界视线投影，不将整张图重复缩到每个方块；经度、两极和末日六面接边已作连续处理。

- 主包：[skyboxblock-1.0.3-test.1-r31.jar](<build/libs/skyboxblock-1.0.3-test.1-r31.jar>)。
- [R31最终交付报告](<docs/MATERIALS-R31-DELIVERY-2026-10-23.md>)：同包四环境340场景、2,808/2,808检查；纯色64张实拍最大偏差0，数学339,384断言，包审计226/226；负对照按预期检出21项混材颜色错误。
- [R31执行与验证协议](<docs/MATERIALS-R31-WORKLOG-2026-10-23.md>)；[素材转换器](<tools/prepare-r31-materials.py>)；[独立CPU像素检查器](<tools/check-r31-materials.py>)；[实际二进制审计](<build/reports/r31-package-audit.json>)。
- 新增素材是用户提供的第三方素材，不随项目代码自动改标MIT；见[来源声明第7节](<THIRD_PARTY_NOTICES.md>)。
- 本轮仍是有范围测试版。用户存档、安装包和光影设置保持不变；性能长测、旧严格恢复四红与门户暂缓项继续保留。本轮素材矩阵与R30动态DH历史验证分开记账。

## R30 历史冻结基线（2026-10-23）

当前冻结构建为 **1.0.2-test.9-r30.8**：动态DH实际LOD前后遮挡、云层距离指令和FSR中央玻璃稳定改进。所测组合为MC1.21.1/Fabric、DH3.3.3，及Iris1.8.1/Sodium0.6.7/ITRP Alpha0.8.31原生与FSR2；不宣称任意版本通用兼容。

- [R30交付状态／证据与已知边界](<docs/DH-CLOUD-GLASS-R30-DELIVERY-2026-10-23.md>)；[官方DeepSeek-V4.1-Flash独立只读终审](<docs/INDEPENDENT-R30-DEEPSEEK-READONLY-2026-10-23.md>)已完成，净结论无阻断；按有范围测试版收口。
- [云层距离指令](<docs/VANILLA-CLOUD-DISTANCE.md>)：`/skyboxblock clouddistance 768`、`/skyboxblock cloudfade 256 768`，各自`reset`；保存到本客户端配置，仅改变方块内部云。
- **资源／性能长测由用户明确跳过**，不是通过；旧严格恢复四红与其他暂缓项保留，传送门继续停止。用户实例、存档、安装包与shader ZIP未替换，未提交或上传。

以下R28／R29和test.4条目保留为历史基线，不冒充R30重新验收。

Minecraft **1.21.1 / Fabric** 模组。R28历史候选 **1.0.2-test.8 / R28**：修复光影白天密闭天空墙内手臂渐白的HDR测光反馈；原生/FSR手部时序360/360，泛光与自动曝光保持开启，见[R28专项报告](<docs/HAND-EXPOSURE-R28-DELIVERY-2026-10-22.md>)。**R28广泛回归保留透明层3675/3684；R29同包充分稳定专项为3680/3684，仍保留4项恢复红项。整体验收未通过，本包仍非正式版**。R29完成9次实机、477场景采集，参照预算与严格报告分列，见[透明层验证交付](<docs/TRANSPARENCY-R29-DELIVERY-2026-10-22.md>)；其余发布边界仍见[发布差距](<docs/RELEASE-READINESS-2026-10-22.md>)。test.7邻接AO修复继续保留，原记录见[R27报告](<docs/VANILLA-AO-R27-DELIVERY-2026-10-20.md>)。R26 的月亮方向、晨昏、夜间蓝晕修正及原版天空盒方块独立云高配置、`/skyboxblock` 命令继续保留。默认云高仍为 **Y=192**；物品/选框对齐、嵌套Iris状态恢复继续保留。详见[R26修正与验证报告](<docs/VANILLA-SKY-R26-DELIVERY-2026-10-20.md>)和[云高命令说明](<docs/VANILLA-CLOUD-HEIGHT.md>)。按用户最新要求，沉浸式传送门兼容工作已停止，R25仅保留为[历史试验](<docs/PRACTICAL-R25-DELIVERY-2026-10-20.md>)。仅保留 `sky_block`；天空、日月星和立体云独立生成，生产渲染不从世界颜色画面抓取天空。以下test.4数字属于历史已验收基线，不冒充test.8完整发布验收。

- 项目作者：**Bing6512**；早期 AI 协作和本轮贡献见[贡献记录](<AUTHORS.md>)。
- 模组 ID：`skyboxblock`；创造标签页：「天空盒方块」。
- 当前构建：[skyboxblock-1.0.2-test.9-r30.8.jar](<build/libs/skyboxblock-1.0.2-test.9-r30.8.jar>)；[test.8历史包](<build/libs/skyboxblock-1.0.2-test.8.jar>)；[历史test.4基线](<build/libs/skyboxblock-1.0.2-test.4.jar>)原样保留。
- 最新验证：[R29透明层同包验证](<docs/TRANSPARENCY-R29-DELIVERY-2026-10-22.md>)、[执行记录](<docs/TRANSPARENCY-R29-WORKLOG-2026-10-22.md>)；既有[手臂渐白修复](<docs/HAND-EXPOSURE-R28-DELIVERY-2026-10-22.md>)与[整体发布差距](<docs/RELEASE-READINESS-2026-10-22.md>)保留。
- 最新证据：[R29整体审计（75/75完整性，严格4红保留）](<build/reports/r29-whole-objective-audit-r1.json>)；[R28历史汇总（原9红保留）](<build/reports/r28-final-validation.json>)和[test.4历史验收数据](<build/reports/state-test4-final-acceptance.json>)原样保留。
- 开发约束：[核心单方块、后续附属扩展与门户兼容路线](<docs/CORE-ADDON-ROADMAP-2026-10-19.md>)；规划与现有实现分开标注。
- 历史背景：[test.3 最终颜色修复](<docs/FSR2-TEST3-ACCEPTANCE-2026-10-19.md>)、[独立天空重构](<docs/VANILLA-REWRITE-2026-10-19.md>)。旧检查没有证明全局选择框的位置正确，本轮已补专项。

> test.4主要验收环境为 **Iris 1.8.1、iterationRP Alpha 0.8.31、FSR2_SCALE=2、第一人称根视角**。R25另做原生/FSR基础同维纯平移门户实测并提供运动模糊关闭配置；跨维/旋转/缩放/嵌套门户、完整动态DH仍未验证。实用候选不等同原完整release矩阵通过；历史失败与[独立审计](<build/reports/independent-final-audit-r23-ip-checkpoint-20261019.md>)保留。

## 1. 使用

当前共9种方块，均按连续世界视线投影；原版动态天空独立于光影包外部天空，其他6种素材天空为静态全景。纯白和纯黑是无纹理阴影／雾乘色的显示颜色，不是普通羊毛或混凝土材质。

| ID（均以 `skyboxblock:` 开头） | 中文显示名称 | 转换染料 |
|---|---|---|
| `sky_block` | 天空盒方块（原版） | 基础合成 |
| `white_sky_block` | 天空盒方块（纯白） | 白色 |
| `black_sky_block` | 天空盒方块（纯黑） | 黑色 |
| `kloppenheim_sky_block` | 天空盒方块（晴空·A） | 淡蓝色 |
| `syferfontein_sky_block` | 天空盒方块（晴空·B） | 蓝色 |
| `dusk_sky_block` | 天空盒方块（黄昏） | 橙色 |
| `galaxy_sky_block` | 天空盒方块（银河） | 紫色 |
| `lush_sky_block` | 天空盒方块（葱郁行星） | 绿色 |
| `apocalypse_sky_block` | 天空盒方块（末日陆地） | 红色 |

基础合成：**8个玻璃围绕1个末影之眼**得到1个原版天空盒。新增8种为1个原版天空盒+表中对应染料的无序合成；创造标签页「天空盒方块」列出全部9种。

```mcfunction
/give @s skyboxblock:sky_block
/give @s skyboxblock:white_sky_block
/give @s skyboxblock:black_sky_block
/give @s skyboxblock:galaxy_sky_block
```

R31使用上表新ID，不恢复早先已删除的旧太空／海洋／森林／风暴／末地／极光／虚空注册项。含那些旧ID的存档仍先备份；现有 `sky_block` ID不变。

## 2. 渲染架构与本轮修复

1. [独立天空生成器](<src/main/java/dev/yg/skyboxblock/client/VanillaSkyScene.java>)读取时间、天气、维度与生物群系颜色，在私有目标中生成背景、日月星和立体云，不抓取地形或光影天空。
2. BER 收集世界相机相对坐标的真实方块面。[世界阶段渲染器](<src/main/java/dev/yg/skyboxblock/client/SkyWorldRenderer.java>)在不透明地形之后、Iris 手部与透明层之前绘制颜色和深度，由 GPU 完成遮挡。
3. [Iris 适配](<src/main/java/dev/yg/skyboxblock/client/ModCompat.java>)读取实际 FSR 比例和 TAA jitter，分别维护完整 GL viewport 与左下角有效光栅，避免重复缩放或错读深度。
4. Iris 合成结束后，使用同一组世界空间面和手部绘制后的深度恢复独立天空的最终颜色，避免灰白化；正常模式只复制深度，不读取世界颜色，没有 CPU/NDC 覆盖几何。
5. **test.4 修复逻辑状态泄漏**：[GL 状态保护](<src/main/java/dev/yg/skyboxblock/client/SkyGlState.java>)现在同时保存 / 恢复 Iris 的 `isMainBound`。旧版只恢复真实 GL FBO，却把 Iris 的世界着色器替换标志留为关闭，导致后续实体和线条漏掉光影的 FSR 变换。私有绘制期间隔离 Iris 世界替换，结束后按嵌套层级恢复原值。
6. [独立云层](<src/main/java/dev/yg/skyboxblock/client/SkyVanillaClouds.java>)默认原版名义高度 **Y=192**，test.6起可独立配置 `vanillaCloudHeight`；保留原版 `+0.33F` 几何偏移、水平漂移及昼夜 / 天气颜色，不改变维度或其他天空盒的云层。
7. 低分辨率世界天空与全分辨率显示天空分别缓存；资源重载、缩放和退出时处理自有资源与状态。颜色读回只在显式回归模式启用。

## 3. 历史test.4最终验证

全部测试均使用隔离新世界，未打开用户存档、未替换参考实例已安装 jar、未修改用户光影选项。

| 环境 | 场景 | 天空 / 遮挡 / 持物检查 | 独立位置检查 |
|---|---:|---:|---:|
| 完整参考模组组 + ITRP，保留用户 `FSR2_SCALE=2` | 37 | **397/397** | **102/102** |
| Iris + Sodium + ITRP，隔离配置关闭 FSR2 | 37 | **399/399** | **102/102** |
| 无光影 Fabric 对照 | 37 | **366/366** | **102/102** |

- 合计 **111 场景、1,468/1,468** 图像 / 深度 / 位置检查；三组实际 jar 哈希与交付包相同。
- 数学回归 **65,018/65,018**，含 6,000 组 FSR/jitter 和 10,000 组固定云高不变量；[最终构建](<build/state-test4-final-build.log>)成功。
- [包审计](<build/reports/skybox-test4-package-audit.json>) **23/23**；[身份 / 负对照复核](<build/reports/state-test4-final-acceptance.json>) **38/38**。
- 独立投影检查实际线条写入位置与真实掉落实体中心；三个环境的最大物品中心误差分别 **6.533 / 4.485 / 3.286 px**。主可见面边覆盖率为 **100%**，所有实际线条像素均在投影边的 3 个有效光栅像素范围内。
- 同 jar 的旧状态负对照正常完成，但位置检查 **54/102**，产生 **48 个预期失败**；无天空方块基线仍通过。另有元数据完全不变的合成位移自测，证实检查器不是仅凭状态字段给出通过。
- 完整第三方模组组保留 **75 条 ERROR 日志行**，两组精简对照 ERROR 为 0；三组致命与渲染线程错误均为 0，详见[完整报告](<docs/IRIS-STATE-TEST4-2026-10-19.md>)。

![同一 jar、相同 FSR2 设置的状态泄漏负对照与修复后画面](<docs/screenshots/state-test4-before-after-20261019.png>)

左右来自两次独立实机运行，不是同一帧；物品旋转 / 浮动相位可不同。最终完整参考组的[天空方块选择框与掉落物](<build/render-regression/state-test4-final-itrp-full/results/align-sky-selected-position-oracle.png>)、[斜视普通方块](<build/render-regression/state-test4-final-itrp-full/results/align-oblique-position-oracle.png>)也保留独立投影标记。

## 4. 配置

实例的 `config/skyboxblock.properties` 可设置，test.6执行 `/skyboxblock reload` 或重启生效：

```properties
# 仅原版天空盒方块内部的立体云；不改变世界/光影云层
clouds=true
vanillaCloudHeight=192.0
debug=false
```

test.6云高可直接用 `/skyboxblock cloudheight 240` 调整并保存；`/skyboxblock cloudheight reset` 恢复192。命令属于客户端外观控制，详细见[独立云高说明](<docs/VANILLA-CLOUD-HEIGHT.md>)。

优先级：默认值 < properties 文件 < 显式 JVM 参数，如 `-Dskyboxblock.clouds=false`。旧 `capture`、`alwaysCapture`、`panorama` 等开关已移除。

## 5. 安装与包身份

客户端与服务端均需 Fabric Loader、Fabric API 及本模组来注册方块；视觉代码仅在客户端执行。把[test.8主包](<build/libs/skyboxblock-1.0.2-test.8.jar>)放入目标实例的 `mods/`，替换旧同 ID 包，只保留一个版本。

R28没有自动替换用户实例中的已安装包；test.8仅作手部专项测试，身份及未通过的透明检查见[R28报告](<docs/HAND-EXPOSURE-R28-DELIVERY-2026-10-22.md>)。test.7与test.6产物原样保留，身份仍见[R27](<docs/VANILLA-AO-R27-DELIVERY-2026-10-20.md>)、[R26](<docs/VANILLA-SKY-R26-DELIVERY-2026-10-20.md>)。以下为历史test.4的SHA-256，留作回滚基线：

```text
59ccfd220789db8ebbc0b041f1818d747ae4e84ce915a70ca0658a49398f567d
```

本轮源码 / 工具 / 交付包 / 选定证据快照（归档 zip，未随仓库分发）只新增，不覆盖历史归档。

## 6. 构建与复现

使用微软 / Mojang JDK **21.0.3**；由[构建脚本](<tools/build.ps1>)设置 JDK 和 Gradle 目录。项目 PowerShell 脚本用 Windows PowerShell 5.1 调用。Python 需要 NumPy / Pillow。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build
python tools/render-regression.py --profile full --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --suite all --heap 8G --label my-test4-full --timeout 600
python tools/check-world-regression.py build/render-regression/my-test4-full/results --output build/reports/my-test4-full.json
python tools/check-alignment-regression.py build/render-regression/my-test4-full/results --output build/reports/my-test4-full-alignment.json --annotate
python tools/verify-skybox-package.py
```

[隔离实机运行器](<tools/render-regression.py>)目前默认使用 test.5 候选；重现本文 test.4 历史数据时应显式传入 `--jar build/libs/skyboxblock-1.0.2-test.4.jar`。`--profile full` 复制参考实例模组与启用资源包，`--shader-settings instance` 复制光影选项。每次 label 唯一。旧 test.4 的 `all` 是37场景。当前 test.5 运行器的 `bugs` 为19、`sky` 为9、`alignment` 为9、`seams` 为8、`dimensions` 为17、`transparency` 为51，默认 `all` 共 **113** 场景；`--scenes` 仅用于显式短诊断，不作完整交付验收。`--no-probe-clouds` 为关闭独立云的对照。

[天空检查器](<tools/check-world-regression.py>)与[位置检查器](<tools/check-alignment-regression.py>)分别核对覆盖 / 颜色 / 前景以及实体 / 选框投影。[最终汇总工具](<tools/verify-render-acceptance.py>)核对三环境、负对照和实际交付 jar 身份。完整命令及检查器初次失败的处理过程见[本轮报告](<docs/IRIS-STATE-TEST4-2026-10-19.md>)。

## 7. 待验证边界

- 门户内部与嵌套视角的最终颜色恢复，目前根视角之外单独跳过。
- 玻璃、水等透明前景的最终合成。
- 实际 DH LOD 遮挡已有[R30指定版本／模式证据](<docs/DH-CLOUD-GLASS-R30-DELIVERY-2026-10-23.md>)；其他DH版本、运动遮挡时序和未测组合仍不扩为通用兼容。
- 其他光影包和全部动态分辨率方案；本版结论以实际测过的 ITRP 配置为准。
- 方块天空独立于光影包，不承诺与外部天空颜色、后处理或云形一致。

## 8. 来源与许可

本项目自身代码与原创资源采用 **MIT**，见[许可证](<LICENSE>)。完整分类、固定上游提交与核查限度见[来源与第三方声明](<THIRD_PARTY_NOTICES.md>)；人类／AI协作署名见[贡献记录](<AUTHORS.md>)。

- [Literal Sky Block](https://github.com/latvian-dev/literal-sky-block)：早期不可见方块／BE／BER结构与投影设计参考；保守保留历史派生脉络的 **2022 Kristiāns Micītis / MIT** 归属及[完整许可副本](<LICENSES/Literal-Sky-Block-MIT.txt>)。当前渲染管线已重写，不宣称是原文件的完整移植。
- [Shadered](https://github.com/Noodlegamer76/Shadered)：明确标记为**创作灵感／视觉目标对照**；当前未查到引入其源码或PNG。旧参考的许可声明存在分支／模板差异，本项目不把 Shadered 整体标成 MIT 或 LGPL。见[历史检查](<docs/ORIGINAL-REQUIREMENTS-AUDIT-2026-10-18.md>)与[本轮来源报告](<docs/evidence/source-provenance-20261019-r1.json>)。
- **R31 六套全景素材（用户提供，不随代码改标 MIT）**：
  - **CC0 1.0**：Poly Haven `kloppenheim_06_puresky`、`syferfontein_6d_clear_puresky`、`qwantani_dusk_2_puresky`（Greg Zaal / Jarod Guest）；Screaming Brain Studios `planet-surface-skyboxes`；VertexZero `retro-skyboxes-pack`（OpenGameArt）。
  - **CC BY 4.0（必须署名）**：银河（galaxy）来自 **Solar System Scope**。
    > Night sky textures by Solar System Scope (solarsystemscope.com), licensed under CC BY 4.0 — <https://creativecommons.org/licenses/by/4.0/>
    > 改动说明：原图经用户提亮（gamma 0.55）后由本项目以 Lanczos 缩至 4096×2048 RGB PNG；对应的方块缩略图与物品图标由该图派生，同受 CC BY 4.0 约束。
  - 逐件协议、作者与来源页见[素材清单](<docs/third-party-evidence/asset-manifest.csv>)，逐来源署名模板见[来源与协议](<docs/third-party-evidence/sources-and-licenses.md>)，完整分类见[来源与第三方声明第 7 节](<THIRD_PARTY_NOTICES.md>)。
- 当前[方块贴图](<src/main/resources/assets/skyboxblock/textures/block/sky_block.png>)与[图标](<src/main/resources/assets/skyboxblock/icon.png>)均逐像素吻合[自有生成器](<tools/gen-skybox-textures.py>)；标记为本项目原创生成资源（AI辅助）。日月／云采用运行时游戏资源，不重新分发 Minecraft 原始日月云贴图。
- **AI署名**：早期部分实现由 DeepSeek V4.1 Flash 辅助完成（用户确认）；本轮接手、修复、验证及文档由 ChatGPT（OpenAI）与用户选择的 GPT6.1SOL 工程子个体辅助完成。提醒专用 DeepSeek 个体没有参与本轮开发，已取消。

binary/source 构建规则均携带署名与 NOTICE；作者已按本人确认更正为 **Bing6512**，新候选包实际内容会逐包核验。旧冻结包保持原始历史字节，不静默覆盖。

作者公开账号：项目署名 **Bing6512**，GitHub 仓库账号为 **BingShanFengXue**（同一作者）；`LICENSE` 与 `AUTHORS.md` 保持既有版权表述不变。
