# 任务状态：第1—4步 / 审计延后

日期：2026-10-19。项目作者：Bing6512。

## 结论

**四项功能的主要实现已落地，但工程验收尚未收口，test.5未正式放行。** 已验收基线仍为test.4。当前生产候选是R23；R24只扩充只读诊断，不是新的生产修复版。

用户最新指令为“审计延后，报告任务状态”。已记录：暂不派最终独立审计个体；工程自测不代替独审。持久目标仍为paused/disarmed，此次没有自动恢复开发或启动新测试。当前所有已启动的后台验证均已结束并收取结果。保活与自动关机保持取消。

## 四步状态

| 步骤 | 已完成 | 尚缺 |
|---|---|---|
| 1. 拼接细线 | canonical共享端点、单次float舍入、逐视图深度来源覆盖、invariant位置；历史同包正负扫描1158/1158且已查看原图 | 最终候选同包64-stage正负复验与source review；历史成绩不自动继承 |
| 2. 全维度外观/时钟 | 统一Overworld外观；普通天空读当地相位/月相，其他天空用持久化gameTime备用钟；Y192云；主框架与实际数据包维度测试 | R23精简原生Iris核心回归出现首次五个Overworld高墙缺绘，尚未定位；不把字段正确当图像通过 |
| 3. Immersive Portals基础探索 | 逐视图生命周期/即时Iris resolve；独立baseline/empty/sky harness；真实步行往返、1627块几何探针和remote-direct工具 | 当前最终候选的三环境三变体及remote-direct实机终验未完成；不宣称完整兼容 |
| 4. 水/染色玻璃遮挡 | 精确ITRP桥、合法中性材质输出、正确中间背景、固体手/柱深度保护；无光影和原生Iris稳定专项均严格通过 | 完整组有3项移除恢复差异，需同包同条件无天空对照判定；原失败保留、阈值不改 |

## R23真实验证结果

每个核心组为默认完整113场景；每个稳定透明组为2个无天空预热＋原完整51项正式测量，共53场景。正式测量至少15真实秒和300个稳定目标pose的root渲染帧，同时固定测试用水sprite的资源声明首动画帧。以上仅是显式测试控制，正常游戏的输入/水动画不受影响。

| 环境 | 世界/天空/遮挡 | 维度/时间 | 掉落物/选框位置 | 稳定透明专项 |
|---|---:|---:|---:|---:|
| 无光影Fabric | 817/817，通过 | 149/149，通过 | 114/114，通过 | 1228/1228，通过 |
| Iris＋Sodium＋ITRP，原生分辨率 | 754/764，未通过 | 139/144，未通过 | 114/114，通过 | 1228/1228，通过 |
| 完整模组组＋ITRP，FSR2=2，受控近景 | 774/774，通过 | 149/149，通过 | 114/114，通过 | 1225/1228，未通过 |

完整组仅在新隔离副本禁用新的DH远景生成，DH模组保留、rendererMode仍DEFAULT，源用户配置未改。**这些结果不证明动态DH LOD兼容。**

所有七组相关运行均无fatal和render-thread错误；无光影/精简原生Iris运行ERROR为0。完整组保留已知第三方data fixer、模型、配方、战利品和chunk-loading等ERROR：透明组47行、核心组78行，不将它们改记为“0 ERROR”。

原始报告：
- [无光影world](<../build/reports/test5-r23-core-vanilla-world.json>)、[time](<../build/reports/test5-r23-core-vanilla-time.json>)、[alignment](<../build/reports/test5-r23-core-vanilla-alignment.json>)、[透明](<../build/reports/test5-r23-settled-vanilla-strict.json>)。
- [精简原生Iris world](<../build/reports/test5-r23-core-iris-native-world.json>)、[time](<../build/reports/test5-r23-core-iris-native-time.json>)、[alignment](<../build/reports/test5-r23-core-iris-native-alignment.json>)、[透明](<../build/reports/test5-r23-settled-iris-native-strict.json>)。
- [完整组world](<../build/reports/test5-r23-core-full-fsr-world.json>)、[time](<../build/reports/test5-r23-core-full-fsr-time.json>)、[alignment](<../build/reports/test5-r23-core-full-fsr-alignment.json>)、[透明](<../build/reports/test5-r23-settled-full-fsr-strict.json>)。

## 两个工程缺口

### A. 原生Iris重复布置高墙缺绘

R23核心回归首次五个Overworld场景没有收集天空几何；服务端/客户端BlockState统计均有110天空块、156背墙和169地板，但普通砂岩背墙也未出现在before图，因此不只锁定旧BE identity guard。

随后使用仅增加诊断、主要生产绘制类与R23逐字节相同的R24包，精确重放相同前50场：185.42秒、运行错误0；五场高墙均110blocks/1572verts，raw BE110，Sodium两个section实际名单50/60且均在entity/geometry列表。**本次未复现，不能认定已修复，也没有据此贸然修改生产路径。**

[只读诊断汇总](<../build/reports/test5-r24-high-wall-readonly-summary.json>)。

### B. 完整组移除恢复差异

完整组1228项检查中，仅以下3项未通过，其余水/玻璃后方标记、材质保留、前景保护和物理计数检查通过：

| 移除场景 | MAE | p99 |
|---|---:|---:|
| 无透明前景 | 0.760 | 8 |
| 染色玻璃 | 0.557 | 6 |
| 水 | 0.599 | 7 |

尚未用本轮同包、同稳定条件的两份真正neverSky控制完成解释/校准，**不先定性为纯噪声，也不直接豁免**。原raw/低通hardcaps保持不变。

## 已澄清的非生产问题

- R22砂岩柱缺一块已由用户确认是误点；R23新增owned测试进程输入隔离后，19场285/285通过，三处柱体服务端/客户端都保持6块；不是靠改深度容差遮住误点。
- 原生Iris稳定专项曾因窗口高度800→794而等待旧分辨率的精确jitter值。只恢复该测试窗口到1280×800并把诊断等待计时/帧数归零，再满足15秒/300帧；全部51正式结果保持原尺寸。运行干预与源代码留档，不隐瞒。
- 蓝色宽光晕的实机开关对照显示主要随BLOOM消失；单关SUNLIGHT_RIMS仍保留。方块本身未设置发光等级，材质emission为0；不把宽晕初判当唯一边界根因。

## 构件与工具

- [R23候选jar](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5.jar>)：210928字节，SHA256 `9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129`。
- [R23源码jar](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5-sources.jar>)：121482字节，SHA256 `5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab`。
- 数学339030/339030；包内容/署名58/58；[验收门禁TEMP自测274/274](<../build/reports/verify-test5-acceptance-strict-integer-r3.json>)；[归档TEMP契约20/20](<../build/reports/archive-test5-temporary-contract-r2.json>)。这些不是独立终审或真实release验收。
- Literal历史MIT归属、Shadered创作灵感、自有两PNG生成证据以及早期DeepSeek V4.1 Flash/当前ChatGPT与GPT6.1SOL工程协作署名已记录。新来源核对仍为exact/pixel/long-Java匹配0，但不据此证明历史从未派生。

## 待完成

1. 定位并收口高墙偶发缺绘；解释完整组3个移除恢复差异。
2. 最终同包拼缝正负、旧Iris逻辑状态/透明桥负例、剩余环境位置合成负控。
3. 当前候选IP基础兼容与remote-direct实机终验、明确支持边界。
4. 锁定真实工程验收矩阵，更新最终交付文档和只增不删归档。
5. **独立审计按最新人类指令延后，后续另行安排，不作为当前已完成事项。**

本次仅报告状态并记录审计延后，没有启动新测试、恢复自动执行或修改生产渲染代码。
