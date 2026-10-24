# R25 实用候选：整体渲染复核与门户兼容配置

项目作者：**Bing6512**。日期：2026-10-20，Asia/Shanghai。

## 结论

按用户新范围，本轮已找到有实际改善的ITRP+沉浸式传送门配置途径，并完成必要的主视角复核：**ITRP中关闭运动模糊，保留光影与原FSR选择**。未复现的偶发问题暂缓，不再无限穷举。

```properties
MOTION_BLUR=false
```

这是实用测试候选，供后续真实使用反馈；**不是原完整release矩阵已通过**。严格门户报告及旧独审NOT_RELEASE_READY均保留，不签自动发布PASS。

## 1. 真正改变了什么

- 主模组仍使用冻结R23的**同字节**[主包](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5.jar>)和[源码包](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5-sources.jar>)；本轮**未修改生产Java、未重新编译或伪称新渲染修复**。R25是这次配置/工具/复核与交付批次，不是jar内新版本号。
- 兼容方案是ITRP选项关闭Motion Blur。代价就是该光影的运动模糊关闭，不是关闭光影、关闭FSR或移除IP。原生与FSR2_SCALE=2均实跑。
- 仅新隔离夹具使用此设置；用户实例、存档、原始光影选项未修改，也未自动安装候选。
- 测试工具修正真实IP配置字段、basic实际mode/物理证据消费，以及child中间采集的独立完整投影范围。历史严格检查器和原报告未被替换成绿。

配置方法及片段见[兼容说明](<ITRP-IP-COMPATIBILITY-2026-10-20.md>)、[单项选项片段](<ITRP-IP-options-snippet.properties>)。通过菜单设置或合并该单项，保留其余选项与原始shader ZIP；别覆盖整份现有配置或重打包ZIP。

## 2. 主视角、手持与位置：本轮84场全部通过

每环境19个bugs场景+9个alignment场景，包含手持/副手/双持、柱体遮挡、缩放、重载、选择框与真实掉落物投影。本轮没有用28场冒充默认113场。

| 环境 | 实际场景 | 世界/遮挡 | 物品/选框 | runtime ERROR / fatal / thread |
|---|---:|---:|---:|---:|
| 无光影 | 28 | [285/285](<../build/reports/test5-r25-practical-smoke-vanilla-world.json>) | [114/114](<../build/reports/test5-r25-practical-smoke-vanilla-alignment.json>) | 0 / 0 / 0 |
| Iris / ITRP原生，Motion Blur关 | 28 | [273/273](<../build/reports/test5-r25-practical-smoke-native-world.json>) | [114/114](<../build/reports/test5-r25-practical-smoke-native-alignment.json>) | 0 / 0 / 0 |
| 完整模组组 / ITRP FSR2=2，Motion Blur关 | 28 | [273/273](<../build/reports/test5-r25-practical-smoke-full-fsr-world.json>) | [114/114](<../build/reports/test5-r25-practical-smoke-full-fsr-alignment.json>) | 59 / 0 / 0 |

合计世界**831/831**、位置**342/342**。完整组59条数据修复器/模型/配方/loot与IP启动chunk日志原样保留，不把fatal0写成clean run。full仍为隔离实例DH新生成disabled、renderer DEFAULT的近景条件，不证明动态LOD。

主线已看[完整FSR选框/掉落物原图](<../build/render-regression/test5-r25-practical-smoke-full-fsr/results/align-sky-selected-position-oracle.png>)、[原生手持](<../build/render-regression/test5-r25-practical-smoke-native/results/bug-panel-sky-hand.png>)、[无光影缩放柱体](<../build/render-regression/test5-r25-practical-smoke-vanilla/results/bug-pillar-resized-side.png>)。

## 3. 门户：六组新实跑，54次capture与真实往返

原生与FSR各baseline（真的未加载主模组）/empty/sky三变体，各9场；实际IP normal renderer、Iris pipeline/active、原始精确archive与实际FSR均核对。全部真实步行穿越/返回、服务器到达确认。每组仍有4条startup ChunkResult ERROR，fatal/thread均0。

主线亲看两组baseline及sky的front/oblique、sky的到达/返回原图：旧普通墙面混成天空的严重现象在MOTION_BLUR=false后明显改善，私有天空正常显示，前景石柱保留。独立坐标公式核对24份固定姿态capture、**39,048个实际block ID**。

![原生/FSR中运动模糊开关与天空方块的实际原图对照](<../build/reports/ip-r25-motion-blur-compat-comparison-20261020.png>)

拼板仅展示缩放，来自独立运行，不是同帧逐像素A/B。实际配置的语义差异经复核为Motion Blur关闭；并未改光影archive或主模组字节。

自动报告明确保留边界：[原生](<../build/reports/ip-r25-native-no-motion-practical-r2.json>)、[FSR](<../build/reports/ip-r25-fsr-no-motion-practical-r1.json>)均646/662，原strict588/605，**practicalPassed=null、automaticAcceptanceReady=false、releaseAccepted=false**。

剩余16项主要为严格白色中性/柱材质与高频指标、未同步clock/history的跨run整帧RGB；这些红项不简单解释成缺绘，也不删除来签通过。逐视图私有天空精确匹配、投影外保护和真实协议使用相应原判据核验。此处给的是有原图人工复核的实用范围，不是全画面等价或完整兼容保证。

## 4. 判据修正与窄差异复核

- 必要辅助自测[32/32](<../build/reports/ip-r25-practical-helper-selftest-r2.json>)：缺/错实际块证据、mode错标、错误计数/digest硬失败；child阶段投影外串画仍被检出。
- 初版红/浅色布局判据经GPT6.1SOL复核发现：缺失白条若露出亮蓝天空也可能过。该问题未掩盖：新增孔洞负例，helper降级为颜色布局诊断，显式geometryEvidenceComplete=false/completeGeometryPassed=null，**不再替换strict stripe gate**。
- 本轮诊断保留完整originalStrictReport，柱与跨run红项未删除；未签自动实用/正式release通过。CPU绿色仅证明工具的有限合同，不替代真实白材质深度或水灵敏度证明。
- 同一位既有GPT6.1SOL审计员已完成[窄差异复核](<../build/reports/ip-r25-practical-delta-review-20261020.md>)及[机器可复核JSON](<../build/reports/ip-r25-practical-delta-review-20261020.json>)，不冒充第二次全量独立终审。结论：最终诊断降级与保留strict gate的方案可用于实用候选证据，不签自动兼容/发布通过，也未发现需要重写生产渲染的新证据。独立核验限原生三组，FSR报告字段/计数已复读；主线的FSR看图与84场smoke不冒称全部经过再次独立审计。

## 5. 按新范围暂缓的事项

1. R23首次高墙偶发缺绘：R24前50场未复现；记“已知偶发、暂缓”，不认定已修，不继续反复追测。
2. 旧full settled三项移除恢复严格红、水负例实际灵敏度缺口继续保留；本轮未重跑完整settled、未改cap/噪声扣除或强行放行。
3. 旋转、缩放、镜像、嵌套、跨维度门户及其它shader版本未验证；动态DH未验收。
4. 本轮整体渲染与有限门户实用范围到此收口，疑难以用户后续真实使用报告补充，不为这些未验证项扩大工程。

## 6. 身份与交付

```text
R23 main     9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129
R23 sources  5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab
harness      612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7
ITRP archive 334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8
```

[本轮实际身份与统计](<../build/reports/practical-r25-runtime-summary-20261020-r2.json>)绑定138次capture（54门户+84主视角）、实际主包/支持mods/pack/设置以及物理与数值往返证据，evidenceBindingChecksPassed=true不等同releaseAccepted。

主线汇总器首轮误按dict读取单目录list报告，第二次误从supporting mods列表找主包，均属于汇总脚本schema问题，原失败记录/红报告保留；修正后直接校验实际mods中的主包、独立manifest.jarSha256和冻结SHA相符。没有通过改产品/图像修汇总。

作者Bing6512、dev.yg技术namespace、Literal MIT/第三方及AI贡献归属保留。test.4已验收基线保留，原独审和历史快照不重写；继续工程的当前tools/docs增量不声称仍与旧subject实时逐项同字节。保活与关机继续取消。

## 7. 实际交付包

[实用候选ZIP](<../build/deliverables/skybox-test5-practical-r25-20261020.zip>)已创建，2,837,452字节、17个entry，包含主模组、匹配源码、单项兼容配置、实际统计/诊断与窄复核、选定原图及许可署名；不包含第三方模组、光影archive或用户存档。解压后仅安装mods中的主包，source中的sources.jar不装入mods。兼容片段只合并MOTION_BLUR这一项，不覆盖其它选项。

ZIP SHA256：`8c470e90602012b0469ffc3bbaf809cdb31c8f4225ce1a63f3a91af7c27a8990`。逐项CRC与SHA校验及候选原字节核验见[包QA](<../build/reports/practical-r25-bundle-qa-20261020.json>)；QA通过不等同releaseAccepted。主包内版本仍为1.0.2-test.5，R25只标识本轮实用配置/复核批次。

本轮按新范围完成收口；没有追加疑难GPU测试，也没有自动修改或安装到用户实例。
