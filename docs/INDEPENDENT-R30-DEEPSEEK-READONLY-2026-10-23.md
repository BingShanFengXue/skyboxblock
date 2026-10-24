# R30 官方DeepSeek-V4.1-Flash独立只读终审

日期：2026-10-23（Asia/Shanghai）。审计模型由用户明确指定；实际路由经工具核对为 **deepseek-official/deepseek-flash**，显示名 **DeepSeek-V4.1-Flash**，仅1个独立审计个体。

审计个体未写任何文件、未构建、未运行游戏、未启动资源测试、未修改配置、未派发个体。本文与收到的文字报告由主执行者存档，不把“只读审计”写成审计者修改源码。

## 净结论

**0个阻断项；追加复核后0个中等已证实缺陷。可按所测范围的测试版交付，不等于通用／正式全门禁通过。**

- 主JAR SHA-256 `50dd054fded07bd8721916cdfe8d97263eb100148dd33f5dee343bbc3a06619d`，探针 `e710a159bdc9418a2a8bd7e0af0673c05c7595f194b591f98f3ea7609d94aa0f`。
- 审计者直接检查生产包134条目：无验证探针、无DH/Iris第三方实现类；源码JAR中80个Java/JSON/GLSL文件与工作树 **80/80字节一致**。
- 明确资源／性能长测 **SKIPPED_BY_USER**，现有短时对象账本不推导长期无泄漏或性能承诺。

## 原始报告与追加更正

[首次最终文字](<../build/reports/r30-official-deepseek-audit-original-r1.md>)和[同一审计者追加更正](<../build/reports/r30-official-deepseek-audit-amendment-r1.md>)分开保留。首次提出的F1没有被静默删除，也没有直接照错误建议改变生产坐标。

F1最初误把 `frame.viewport` 的752×470当成真实GL viewport，认为opaque的 `/TextureExtent` 采错区域。经核查：

- GL viewport与DH深度物理纹理仍是1280×800；world_sky.vsh和DH producer都在vertex阶段执行相同FSR scale/jitter，把几何压入左下active子矩形。
- opaque的gl_FragCoord已经是缩放／抖动后的物理纹素索引；只有无jitter全分辨率resolve需要另加DepthUvTransform。
- 两公式代数差约5.55e-17；三阶段实际有效DH深度在active块内120825/144496/147942，active外均0。[可复现反证](<../build/reports/r30-audit-F1-fsr-uv-proof-r1.json>)。

审计者独立复核后**撤回F1**，将F4跨level wrapper缓存和F5透视常数比较降为防御性建议，不再称已证明缺陷。生产源码／shader未因此修改，冻结包哈希保持。

## 保留的非阻断限制

- **Iris深度内容时序**：Iris当前pipeline／纹理／投影按拥有者和尺寸验证，但未独立证明每一帧depth内容与root事务绝对同步；运动边界可能滞后一帧的风险未被固定姿态oracle排除。
- **DH-only像素oracle归属**：无Iris的前后像素遮挡26/26属于候选r30.3；当前r30.8具有DH-only 13段真实动态启用／深度／状态恢复证据，不把旧像素oracle直接改名为当前包通过。
- **固定姿态玻璃**：90帧×4物理对照、23种jitter支持当前场景修复，不扩展为所有运动透明体／所有光影的完整验收。
- **测试代码成本**：生产包既有回归类均有显式开关门控，正常游玩不触发；诊断shader注册仍有常驻成本，未做资源专项，不宣称零成本。
- 旧严格恢复四红及用户其他暂缓项保留。README历史口径已在收口时同步到R30，旧记录仍留存。

## 审计时点之后的同包完成记录

审计首次结束时，最终FSR动态复跑仍是pending，原文如此保留。之后主执行者核验同一主包／探针哈希，三环境13段均完成：DH-only **278/278**、Iris原生 **342/342**、FSR2 **342/342**，共39段 **962/962**；四个实际运行回执均exit0、ERROR／致命0、输入字节未变。没有让审计者替未结束数据背书。

[用户范围收口回执](<../build/reports/r30-user-scope-completion-r1.json>)明确区分 **SCOPED_DH_COMPLETE_RESOURCES_SKIPPED** 与仍未通过的旧全局release门禁。
