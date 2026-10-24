# 本轮工程边界：门户兼容测试 → 兼容尝试 → 独立审计

日期：2026-10-19。项目作者：Bing6512。

用户最新指令：“该轮增加执行到‘沉浸式传送门兼容测试与尝试兼容’后结束，在这之后审计。”

## 执行范围

1. 使用冻结R23主包及独立harness，三环境各测baseline/empty/sky：无光影、ITRP原生、ITRP FSR2=2。
2. baseline必须完全不加载主模组，不用功能关闭冒充无模组。保留真实client keyUp穿越/返回、服务器确认与1627块几何探针。
3. 按需进行无门同姿态remote-direct；不改矩阵、warp图像或放宽oracle阈值。
4. 依据实际差分仅做有限、可证伪的基础兼容尝试；未知/上游组合限制明确记录，不承诺旋转/缩放/嵌套/跨维度或完整动态DH。
5. 门户阶段结束后冻结工程资料，停止继续扩展开发，再派全新GPT6.1SOL独立审计。此前工程子任务及工具自测都不是此次独审。

## 基线身份

- [R23主包](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5.jar>) SHA256 `9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129`。
- [R23源码](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5-sources.jar>) SHA256 `5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab`。
- [独立harness](<../build/milestones/steps14-r23-settled/skybox-portal-fixture-1.0.0.jar>) SHA256 `612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7`。

测试条件：MC1.21.1/Fabric、实际IP内报6.0.6（文件名6.0.7，含IpDimFix）、Sodium0.6.7、Iris1.8.1、ITRP Alpha0.8.31精确原archive；主线GPU串行，新隔离世界。stationary settleTicks=300，不把tick数本身称所有temporal history已严格收敛。

## 保留的工程缺口

- 原生Iris核心重复布置高墙缺绘：R23失败，R24仅增加只读诊断的50场回放未复现；不认定已修复。
- 完整组3项移除恢复差异：原始严格失败保留，尚缺最终同包/同条件neverSky解释。
- 当前最终候选的拼缝/旧Iris状态/透明桥等负控及完整release矩阵仍有待办。

这些问题随审计资料明确列出，不通过改字段或工程自测将release标成accepted。独立审计可以给出不具备正式发布条件的结论；“完成审计”不等于“发布验收通过”。

## 安排与限制

当前按直接人类请求执行上述门户阶段。自动目标仍处于界面暂停；模型的resume调用已被工具明确拒绝，因此不重复尝试、不声称自动续跑已恢复。保活与关机仍取消，不修改用户实例、存档或原光影配置。
