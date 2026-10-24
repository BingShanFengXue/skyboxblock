# R29：透明层充分稳定协议与接续验证

日期：2026-10-22（Asia/Shanghai）。状态：本轮协议、同包验证及证据审计已完成。**9次实机／477场景采集、374项协议与旧检查器自测、75项证据完整性检查均通过；原严格图像为3680/3684，原生1项＋FSR3项恢复红项继续保留。** 两参照预算86/86、12组水层重复响应通过，与严格未通过分列保存。详见[最终交付报告](<TRANSPARENCY-R29-DELIVERY-2026-10-22.md>)；**这不是严格透明或正式发布通过。**

## 范围与不变项

- 以冻结 `test.8 / R28` 为唯一生产包，SHA-256 `07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c`。
- 本轮只修改验证工具，不修改生产 Java、GLSL、资源、模组元数据或交付 JAR；传送门继续停止。
- 旧 51 场景规则保留，所有原图像阈值不变。R28 的 5 项恢复差异、4 项原版水层参照不足继续保留原报告。
- 不操作用户实例、存档或其他 Java 进程；实机使用项目生成的隔离新世界和 owned-input/water 控制。
- 本轮未派发子个体；沿用现有代码、项目工具与本地归档。当前技能目录没有匹配的 Minecraft 渲染技能。

## 修改前留档

[工具源码快照](<../../artifacts/snapshots/skybox-r29-protocol-before-20261022T1529.zip>)包含 6 个既有工具/自测文件，SHA-256 `725d638b4828a9d47061ec2dfe1de7e9d87b84cb9294a7de220ce37d5fe61e9e`。归档只新增，没有清理历史。

## 已实现的门禁

1. 新增[共享协议验证器](<../tools/settled-transparency-protocol.py>)：完整 53 场景身份、实际日志中的唯一采集顺序、两个固定预热、51 个测量各至少 15000ms/300 root 帧、实际帧数一致性、输入隔离计数、水资源首帧控制及生命周期观测。
2. 明确区分 `legacy-unsettled` 与 `settled53-v1`。未声明 settled 的旧数据不获得新协议通过；候选/参照协议不一致时保留未验证。
3. 检查两个真实水资源的 frozen/static 观测、实际 sprite suppression 和上传日志；不把仅固定 shader 时间冒充固定水动画。不改水贴图像素。
4. 对 warmup 与全部 sky-free 场景核验真实深度图零写入、`before == opaque` 及 `final == pre-resolve`。neverSky 对照检查整个 53 场景生命周期的计数、实际服务端/客户端 sky 状态，而非只选末帧。
5. [独立协议入口](<../tools/check-settled-transparency.py>)绑定实际安装的唯一主 JAR 哈希；它不豁免图像阈值、不替代发布验收。
6. [原透明检查器](<../tools/check-transparency-regression.py>)、[重复参照检查器](<../tools/check-marker-reference-repeat.py>)及[恢复预算检查器](<../tools/check-restoration-repeat.py>)接入共享门禁，保留旧计算链。重复参照检查器另支持实际没有 Iris、明确禁用光影的 vanilla 环境，用于核验原版水层，而非伪造 Iris 时间激活证据。
7. [运行器](<../tools/render-regression.py>)在 settled 协议下默认超时改为 1800 秒，其他协议仍为 360 秒；显式低于测量下界 765 秒的设置在创建实例之前失败。超时值写入 fixture 声明。

## 自测与实机执行记录

- [新协议自测 R1](<../build/reports/r29-settled-selftest-r1.json>)：64/64，退出 0。覆盖真实 parser/图像路径的合成正例、51/53 计数、缺少/矛盾稳定证据、过短时间/帧数、错误预热姿态、水控制未生效、neverSky 私有写入及混合协议等负例；含无光影正负对照。
- [扩展自测 R2](<../build/reports/r29-settled-selftest-r2.json>)为 79/79；[最终门禁与恢复集成自测 R3](<../build/reports/r29-settled-selftest-r3.json>)为 89/89，均退出 0。新增覆盖缺少/多余真实场景、重复 JSON 键、元数据与实际图像尺寸不符、残留 BE、Iris 逻辑状态与光栅外写入，以及原生/无光影恢复预算的真实 reader 路径。
- 旧透明 54/54、恢复预算 5/5、重复参照 226/226 两轮均通过；[最终重复参照自测](<../build/reports/r29-protocol-legacy-marker-r2.json>)退出 0（226/226）。保留所有新日志，不覆盖上一轮 285 项记录；最终一组唯一测试计数为 **374/374**，不把多次复跑叠加成新覆盖数量。
- [源码与工具身份复核](<../build/reports/r29-tools-and-source-identity-r1.json>)：8 个 Python 文件解析通过，当前 `src/` 的 83 个文件仍与冻结源码快照一致，主 JAR 哈希不变。
- 第一组实际运行：`r29-settled-vanilla-candidate-r1`。命令参数为 `--profile vanilla --suite transparency --settled-transparency --capture-jitter-bits 00000000:00000000 --jar build/libs/skyboxblock-1.0.2-test.8.jar --timeout 1800 --heap 6G`。[运行日志](<../build/logs/r29-settled-vanilla-candidate-r1.log>)
- 已读回第一组真实预热与水层采集：固定相机分别为 X=0.5/1.15、实际眼高 141.61999988555908；预热 root 帧数 1318/1285。选定测量的稳定时间 15617–15709ms、root 帧数 1336–1339；两个水资源均已 frozen，实际 sprite suppression 计数持续增加。已查看[无天空水层帧](<../build/render-regression/r29-settled-vanilla-candidate-r1/results/trans-open-water-none-empty.png>)，这只是阶段观测，不作全量通过。
- 首个候选实际完成 53 场景，用时 **864.88 秒**，运行 ERROR、致命异常、渲染线程错误均为 0；[同包新协议门禁](<../build/reports/r29-settled-vanilla-candidate-preflight-r1.json>)通过。
- [候选水层先行诊断](<../build/reports/r29-settled-vanilla-early-water-r1.json>)沿用原检查器的固定 ROI 与阈值：四种保护组合的水层参照均有效、均未检出超过固定阈值的标记匹配；候选的 none/glass/water 三种移除恢复原始 MAE、p99、最大通道差均为 0。该先行观测当时尚无两份独立参照，本身不等同完整验收；两参照齐备后的结果见下节，也不唯一归因于水动画或稳定时间中的某一项。
- **第一独立参照 A 已完成。** 实际运行 53 场景，客户端用时 919.25 秒（运行器父进程阶段为 919.859 秒），运行退出 0；[门禁报告](<../build/reports/r29-settled-vanilla-never-a-r1-protocol-r1.json>)通过。全生命周期累计观测 68,092 个 root 帧，测量场景最低稳定时间 15,615ms、最低稳定 root 帧数 1,139。53 场景保持无天空方块及无私有渲染，实际 client/server sky 与 BE 计数按门禁核验。[第一独立参照诊断](<../build/reports/r29-settled-vanilla-first-reference-r1.json>)显示三种移除恢复及其与候选 NoSky 对应帧的差异均为零；**仍不替代第二次独立参照**。
- [候选视觉索引](<screenshots/r29-water-reference-protocol-20261022.png>)已生成并人工读图，四张来源图及 metadata 的 SHA-256 记录在[图像回执](<../build/reports/r29-candidate-visual-evidence-r1.json>)。缩略图只作说明，不参与像素门禁；不把历史/新协议对照称作唯一变量实验。
- [整体证据审计器](<../tools/verify-r29-settled-objective.py>)已准备，只在全部九个真实 fixture 和分析报告齐备后才考虑收口，不根据矩阵进程退出 0 自动放行。[5 项审计自测](<../build/reports/r29-objective-auditor-selftest-r1.json>)通过：原数值阈值、全部 22 个原图像数学/ROI/载入/检查函数、恢复预算函数均与修改前快照一致；矩阵未完成的负例明确返回未验证，不放行。
- [串行矩阵运行器](<../tools/run-r29-settled-validation.py>)已启动三环境共 9 个完整 fixture（首候选以相同包身份复用，不重复启动）。每个新游戏前后核验冻结 JAR 和验证工具哈希，保存不可覆盖的 stage 日志与 JSONL 进度；不并行运行 GPU 游戏。
- 原生与 FSR 后续沿用 R28 实际测到的 ITRP Alpha 0.8.31 参数（`MOTION_BLUR=false`，分别 `FSR2_SCALE=-1`、`2`）及相应真实 jitter bit key；不改变用户实例设置。
- [首阶段检查点](<../build/reports/r29-protocol-checkpoint-r1.json>)、[第一独立参照检查点](<../build/reports/r29-protocol-checkpoint-r2.json>)、[完整原版结果检查点](<../build/reports/r29-protocol-checkpoint-r3.json>)、[原生首参照检查点](<../build/reports/r29-protocol-checkpoint-r4.json>)、[两环境分析完成检查点](<../build/reports/r29-protocol-checkpoint-r5.json>)及[七个完整 fixture 检查点](<../build/reports/r29-protocol-checkpoint-r6.json>)记录目标、后台矩阵作业、进度、完整命令及未完成项；[验证工具差异](<../build/reports/r29-validation-tools-delta-r1.json>)与[可复核补丁](<../build/reports/r29-validation-tools-implementation-r1.patch>)单独留档。

## 原版环境完整结果

三次独立隔离进程的 PID 分别为 22764、30896、10180，客户端用时分别为 864.88、919.25、857.56 秒；每次 53 场景、退出 0，运行 ERROR/致命/渲染线程错误均为 0。三份新协议门禁均通过。neverSky-B 实际观测 71,146 个 root 帧，最低稳定时间 15,609ms、最低稳定 root 帧数 1,327。

- [严格透明图像检查](<../build/reports/r29-settled-vanilla-r1-transparency.json>)：保留全部 **1228/1228 原检查**，另计 **1/1 新协议检查**，没有失败项。
- [独立恢复预算](<../build/reports/r29-settled-vanilla-r1-restoration.json>)：**30/30**；候选与两次独立参照的 none/glass/water 三种移除恢复 MAE、p99、最大通道差均为 0，不依赖放宽阈值过关。
- [水层重复响应](<../build/reports/r29-settled-vanilla-r1-water-repeat.json>)：none/pillar/hand/both 四组均有有效 A/B 与 NoSky holdout，均未检出超过固定阈值的 SkyOn 标记匹配；16/16 真实裸标记几何正例、4/4 holdout 自正例通过，48 个同状态负例均未误匹配。报告绑定 1003 个实际输入。
- [完整原版阶段回执](<../build/reports/r29-settled-vanilla-complete-milestone-r1.json>)保留三次进程/日志身份、逐组原标志及正负计数。原 `legacyEvidenceValid=false` 仍原样保留，`multiScaleEvidenceValid=true` 使既有 OR 判据成立；不是调低旧高频 RMS 门槛。
- 本结论限于本包、本环境、充分稳定＋固定水资源帧的联合协议。它不把两项协议干预中的某一项单独认定为唯一根因，也不证明快速移除期间绝无瞬态差异；R28 原始 5 项原版红项报告不覆盖。

辅助审计初次按 UTF-8 读取首候选的 PowerShell `Tee-Object` 日志时遇到 UTF-16 BOM。原始日志未修改；新增只读 BOM 识别，重新核验实际 PID 成功，并用[11/11 编码与收口审计自测](<../build/reports/r29-objective-auditor-selftest-r2.json>)覆盖 UTF-8、UTF-8 BOM、UTF-16 LE/BE 及真实旧自测日志。未据此改变生产代码或渲染通过判据。

## Iris 原生组阶段结果：保留恢复红项

候选、neverSky-A、neverSky-B 分别完成 53 场景，客户端用时 878.14、870.94、869.27 秒；三者运行 ERROR/致命/渲染线程错误均为 0，新协议门禁均通过，实际 PID 为 15916、30744、26780。参照 A 累计观测 71,426 个 root 帧，最低稳定时间 15,782ms、最低稳定 root 帧数 1,339。以下分别记录早期诊断及两参照齐备后的正式数值；原严格失败保留。

- [候选先行诊断](<../build/reports/r29-settled-native-early-water-r1.json>)：四种保护组合的水层参照均有效且未检出超阈值标记匹配。但 `trans-removed-none` 的原始 MAE **1.037096**、p99 **6**，越过原 MAE≤1 / p99≤5 恢复线，**继续保留为未通过**。玻璃/水的 MAE 为 0.137126/0.215825，p99 均为 3。
- [频率与有符号差诊断](<../build/reports/r29-native-restoration-frequency-r1.json>)：无前景比较低通 MAE 为 **0.851385**、p99 为 2、最大值为 3；全帧有符号 RGB 均值约 +0.857/+0.859/+0.836。这包含小幅全帧亮度偏移，不直接归类成纯随机噪声。[残差可视索引](<screenshots/r29-native-removal-residual-20261022.png>)已人工查看，128 为中性、差值仅为展示放大24倍，不改变原图及计算。
- [完整 neverSky-A 原始测量](<../build/reports/r29-native-reference-a-measurement-r1.json>)：从未放置天空方块的参照也有无前景 MAE **1.005472**、p99 **5**，低通 MAE **0.852380**、p99 2、最大值 3。它自身的这一 raw pair 也越过原 MAE≤1 线。说明该偏移不是只在放置/移除天空方块时出现，但一份参照仍不足以成立既定两参照校准预算。
- [同状态跨进程比较](<../build/reports/r29-native-cross-state-first-control-r1.json>)：候选与参照 A 的早期 NoSky MAE 0.248174，晚期 MAE 0.376472，RGB 有符号均值接近 0；这与各自进程首末比较中的共同偏移不同，供后续判因，不拿它合成通过。
- 已只读检查实际 ITRP ZIP 的[帧相位相关源码](<../build/reports/r29-itrp-frame-phase-source-r1.json>)与[原始计数对照](<../build/reports/r29-native-frame-phase-correlation-r1.json>)。相同 jitter 不等于全部着色器历史相同；但“仅 mod64 相位不同导致偏移”的简单解释已有反例：参照 A 首末 mod64 均为 2，仍出现该偏移，候选末帧 mod64=34 与参照末帧=2 的跨进程有符号均值却近零。此线索没有被写成唯一根因或噪声结案。
- [原生组严格报告](<../build/reports/r29-settled-native-r1-transparency.json>)现已完成：原图像检查 **1227/1228**，另计新协议 **1/1**；唯一失败仍为 `trans-removed-none:removed-world-restored`（MAE 1.037096、p99 6），没有改绿。
- [两参照预算报告](<../build/reports/r29-settled-native-r1-restoration.json>)为 **28/28**。A/B 的无前景恢复 MAE 分别为 1.005472/0.981303，p99 均为 5；按修改前已存在的预算函数得 MAE 上限 1.105472、p99 上限 6，并继续满足低通 1/2/4 及绝对 1.5/10 硬上限。候选在此独立预算内。**预算通过与原严格未通过分列保存**，不是修改原阈值或给候选拟合容差。
- [水层重复响应](<../build/reports/r29-settled-native-r1-water-repeat.json>)四组均支持未检出超阈值匹配：16/16 真实裸标记正例、4/4 holdout 自正例通过，48 个同状态负例均无误匹配。原 `legacyEvidenceValid=true`、`multiScaleEvidenceValid=false` 仍保持各自原值，独立重复证据没有把旧标志改写为绿色。
- [原生组完整回执](<../build/reports/r29-settled-native-complete-milestone-r1.json>)记录严格红项、预算、A/B 原始比较、四组响应及 1015 个实际输入。生产 JAR、光影 ZIP、固定矩阵工具及原数值阈值均未修改。

## FSR 阶段记录与最终两参照结果

FSR 候选已完成 53 场景，客户端用时 872.44 秒、运行 ERROR/致命/渲染线程错误均为 0；[新协议门禁](<../build/reports/r29-settled-fsr-candidate-r1-protocol-r1.json>)通过。实际显示为 1280×800，世界有效光栅为 752×470（横纵均为 0.5875），不是按选项名臆测的半分辨率；测量 jitter bits 为 `39f8c100:3a2fcbc3`，原生 Iris 逻辑状态、GL 与光栅外零写入已由门禁核验。

[先行诊断](<../build/reports/r29-settled-fsr-early-water-r1.json>)的四组水层参照均有效，尚未检出超阈值标记匹配；但三个移除恢复 raw pair 均越过原恢复线，继续保留：

| 前景 | raw MAE | raw p99 | 低通 MAE | 低通 p99 / 最大值 |
|---|---:|---:|---:|---:|
| none | 1.033044 | 9 | 0.521032 | 1 / 2 |
| glass | 0.578996 | 6 | 0.074433 | 1 / 2 |
| water | 0.604954 | 7 | 0.101089 | 1 / 2 |

FSR A/B 现已实际完成，各53场景，客户端分别用时867.83/869.23秒，运行错误均0、协议门禁均通过。[严格报告](<../build/reports/r29-settled-fsr-r1-transparency.json>)保留 **1225/1228 原检查＋1/1新协议**及三个恢复红项；[两参照预算](<../build/reports/r29-settled-fsr-r1-restoration.json>) **28/28**，预算仅由A/B按原函数计算，none/glass/water的MAE上限为1.132745/1/1、p99上限为10/7/8，硬上限保持原样。[水层重复证据](<../build/reports/r29-settled-fsr-r1-water-repeat.json>)四组有效，16/16裸标记正例、4/4自正例通过、48负例无误匹配。[完整FSR回执](<../build/reports/r29-settled-fsr-complete-milestone-r1.json>)将严格未通过与预算内状态分列。

## 本轮收口与下一步

1. 协议实现、374项自测、每环境两份独立neverSky及三环境全部9次真实运行已完成；原始失败、新图像、输入字节与阶段日志均保留。
2. [整体证据审计](<../build/reports/r29-whole-objective-audit-r1.json>)75/75完整性通过，原图像3680/3684、新协议3/3、参照预算86/86、12组水层响应支持；`EVIDENCE_COMPLETE_WITH_OPEN_ITEMS`仍表示保留4项严格失败，不是验收通过。CLI因红项退出1，执行回执明确记录。
3. 下一阶段分离NoSky自身的时序/曝光/GI背景变化与天空方块相关影响，处理严格恢复四红；若拟将参照校准纳入正式门禁，应明确规则并做独立终审，不自动放宽标准。
4. 高墙/地形缺绘、动态DH、部署、长时性能、非门户正式矩阵及发布收尾继续单列；传送门工作保持停止。
5. 本轮归档只新增，生产JAR不重建，源码与工具/原图/结果按最终交付报告的证据入口复核。

## 全矩阵结束后的收口命令

矩阵实际完成后已执行以下入口；原报告保留，复跑应使用新的输出文件名：

```powershell
python -B tools/verify-r29-settled-objective.py --matrix build/reports/r29-settled-matrix-vanilla-native-fsr-r1.json --output build/reports/r29-whole-objective-audit-r1.json
```

该审计重新核验九个不同真实 fixture、实际包哈希、每份协议/水层报告的输入字节、三环境各 1228 个原图像检查、原阈值及原预算函数、全部重复参照状态。矩阵退出 0 只表示执行收集完毕；出现任何失败仍需逐项保留和解释。运行中的矩阵冻结了工具哈希，等待期间保持这些工具原样；若确有工具缺陷需要更改，先处理当前作业并用新报告 revision 明确区分，不静默覆盖已收集证据。

## 判读边界

固定包和哈希绑定不等于证明采集过程独立；记录的计数也不是加密认证。重复标记响应可能包含阴影/GI/反射；没有检出超过固定阈值的匹配不等于数学意义上的零影响。原版参照不足的旧红项不会因新自测通过被重写成绿色。
