# 实用候选与门户兼容继续范围（2026-10-20）

作者：Bing6512。用户最新要求：继续任务；偶发且未复现的问题可暂缓，优先整体渲染正常，继续尝试沉浸式传送门兼容。疑难由后续实际使用反馈。

执行顺序：定位稳定复现的ITRP+IP普通基线异常；必要修正采集阶段/ROI与真实物理证据验证；依据证据做最小兼容修正；串行验证主视角、物品/选框、透明及基础门户。原生高墙R24未复现的问题暂缓，不称已修。full三项恢复差异和水负例灵敏度边界记录，不无限追测、不改旧正式验收门禁冒充通过。

保留test.4物品/选框、Iris嵌套状态和名义Y192云。作者Bing6512，dev.yg与第三方/AI归属不变。只用新owned fixtures，不改用户实例/存档/原光影配置，不停用户游戏；GPU/Gradle主线串行。保活和关机继续取消。

上一轮冻结和独审保持原样。新测试使用新label。目标更新为revision8，仍界面paused/disarmed，不重复被工具拦下的resume，当前按直接人类请求工作。技能目录仅ACL/Office，无Minecraft匹配项，使用filesystem/Python/JDK工具。

## 已执行进展

- launcher仅改为IP真实字段enableCrossPortalView/enableClippingMechanism/useDepthClampForPortalRendering；用户原配置未动。
- 第一单变量试验：`ip-r25-native-no-motion-baseline`，仍无主模组，精确同ITRP archive、FSR=-1、IP normal，仅MOTION_BLUR=false。9场154.95秒、真实往返完成，fatal/thread=0，4条旧类startup chunk ERROR仍原样保留。
- 主线亲看front/oblique原图，原先混成天空的条纹墙与石柱恢复清晰。4固定视角色块布局观测正常（红准确率0.888—0.980、非红浅色准确率1、接口覆盖1）；复核证明该布局观测会漏掉亮蓝背景孔洞，已明确降级为诊断，不称完整白材质几何通过，strict门禁保留。
- 辅助自测最新32/32：初版30/30未覆盖的白条亮蓝孔洞问题已记录并加控；布局诊断不再替代strict stripe。缺/错实际块证据、mode错标、bool/float计数与错误digest被拦截；child中间阶段仍检测真正投影外串画。自动验收未签通过。
- 门户批次`pwsh-a942fe4800ee4dd3`已全部完成并收输出：两环境六组共54capture、真实往返；随后`pwsh-d5525494a9b14c5f`三环境各28场主视角/手持/选框也结束，世界831/831、位置342/342。全部GPU结束，不重复启动已有label。
- 主渲染Java未修改；原生/FSR实际天空方块、前景柱和真实往返均完成有界人工原图复核，兼容选项改善成立。同一GPT6.1SOL审计员的窄差异复核已完成，不签自动发布通过。
- 实用候选ZIP已创建并完成17项entry的CRC/SHA与主包原字节校验，安装/兼容方法及暂缓问题已写入交付说明；本轮停止追加测试，等待实际使用反馈。

最终汇总：[实用候选交付](<PRACTICAL-R25-DELIVERY-2026-10-20.md>)、[实际证据绑定](<../build/reports/practical-r25-runtime-summary-20261020-r2.json>)、[32项自测](<../build/reports/ip-r25-practical-helper-selftest-r2.json>)。初版基线/30项自测与旧独审、原红报告全部保留，不把早期结构表述当自动发布通过。
