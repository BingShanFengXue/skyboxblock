# ITRP + 沉浸式传送门：实用兼容设置

作者：Bing6512。状态：原生与FSR六组baseline/empty/sky、54次capture和真实往返已完成；主线亲看普通墙、天空方块、前景柱与穿越前后原图，改善成立。三环境84场主视角/手持/选框复核全部通过。本页提供实用候选配置，不替代旧独立审计或完整release验收。

## 当前明确有效的设置

在 **iterationRP Alpha 0.8.31** 的光影设置中，把 **Motion Blur / 运动模糊关闭**：

```properties
MOTION_BLUR=false
```

保留Iris和光影开启；FSR倍率维持自己的选择。试验中原生为FSR2_SCALE=-1，FSR组为2。不需要关闭整个光影或移除沉浸式传送门。

这是ITRP选项兼容配置，**不是主模组已经自动修改用户光影设置**。代价是该光影的运动模糊关闭；其余选项没有因本实验被关闭。本轮仅改新隔离实例，未改用户原配置。

## 证据与机制边界

- 已确认实际ITRP的MotionBlur_FS使用当前/上一帧视图与相机位置推导速度，再沿速度积分；门户子视图会切换虚拟相机。无主模组的旧normal/basic均出现普通墙面混成天空的现象。
- 新原生真正无主模组基线只增加MOTION_BLUR=false；精确相同archive、IP normal真实renderer和实际Iris仍在。front/oblique原图中条纹墙和石柱恢复清晰，9场真实穿越/返回完成。
- 这支持“关闭运动模糊可规避该具体组合的已观测异常”，不把一次配置A/B夸成所有运动历史问题唯一根因，也不外推任意光影包/版本。
- 严格中性白色/柱体高频指标与跨run整帧RGB仍有红项，全部保留；未用色度问题反向豁免实际缺绘。新红/浅色布局检查经复核可漏掉露出亮蓝天空的白条孔洞，因此已降为诊断，geometryEvidenceComplete=false，不作自动通过门禁。
- 两环境最终诊断均646/662、原strict588/605，practicalPassed=null、automaticAcceptanceReady=false；当前是有实际原图人工复核的实用候选，不是自动发布PASS。实际模式、物理探针、包/设置身份均另行核对。

[必要判据自测32/32](<../build/reports/ip-r25-practical-helper-selftest-r2.json>)、[原生完整诊断](<../build/reports/ip-r25-native-no-motion-practical-r2.json>)、[FSR完整诊断](<../build/reports/ip-r25-fsr-no-motion-practical-r1.json>)、[实际身份/统计汇总](<../build/reports/practical-r25-runtime-summary-20261020-r2.json>)。

[兼容选项片段](<ITRP-IP-options-snippet.properties>)只含MOTION_BLUR=false，应合并单项或通过光影菜单设置，别覆盖整份现有选项。**保留原shader ZIP，不改源码或重新打包ZIP**，以维持专用透明桥的精确archive身份。

## 当前范围

同主世界纯平移、基础单层门户、实际步行穿越与返回。旋转、缩放、嵌套、镜像、跨维度及其它光影组合暂不承诺。本轮用户允许偶发未复现问题先记录，疑难以实际使用反馈为后续输入。

没有自动安装或修改用户实例。完整交付与暂缓事项见[实用候选报告](<PRACTICAL-R25-DELIVERY-2026-10-20.md>)。
