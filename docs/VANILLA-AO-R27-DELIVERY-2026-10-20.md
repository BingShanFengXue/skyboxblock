# test.7 / R27：无光影天空盒方块周边 AO 暗影

日期：2026-10-20（Asia/Shanghai）。项目作者：Bing6512。Minecraft 1.21.1 / Fabric / Java 21。

**结论：test.7 / R27 的邻接 AO 暗影修复及专项验证完成。纯原版、Sodium 关闭光影均通过；原版天空及三环境主视角/物品/选框回归通过。首轮旧 Sodium 基准红项原样保留，同包完整复验通过；本轮不是旧完整 release 矩阵的整体放行。**

## 1. 修复了什么

关闭光影时，天空盒方块相邻的地面、墙面和顶面曾出现额外接触暗边。根因是普通完整碰撞方块继承的 AO 亮度：原版 `BlockBehaviour.getShadeBrightness` 对完整碰撞体返回 `0.2F`。已有 `noOcclusion`、天空光透过和 `isViewBlocking=false` 没有覆写这个独立的邻接 AO 系数。

本轮仅在 [`SkyBlock`](<../src/main/java/dev/yg/skyboxblock/SkyBlock.java>) 明确覆写 `getShadeBrightness(...)=1.0F`，使邻接 AO 采样按玻璃/空气的中性亮度处理。保留完整碰撞和选框、原有透光、零发光；不关闭全局平滑光照、不修改其他方块或世界光照，不改天空颜色、云、Iris 桥接、光影 ZIP 或用户配置。

同版本、同诊断工具的修复前/后 JAR 唯一变化条目为 `dev/yg/skyboxblock/SkyBlock.class`。修复前该类与 test.6 的对应类逐字节相同。[实现差异](<../build/reports/r27-implementation.patch>)及[源码哈希记录](<../build/reports/r27-source-delta.json>)保留。

## 2. 受控专项

- 两种布局：地面，以及地面＋左右墙＋顶面。
- 两个真实时间：正午、午夜；平滑光照分别开启/关闭。
- 同位置依次测试空气、玻璃、石头、天空盒方块、移除后空气，共 40 场景/次。
- 只使用自然天空光；实际采样确认方块光为 0。相机、投影、亮度、物理表面及光照采样保持对应一致。
- 图像检查通过独立射线/AABB 选取实际可见地面/墙面/顶面；排除解析得到的天空平面遮挡范围，轮廓固定内缩 3 像素。接触带固定为世界 Z=2.05–2.95，不按 RGB、颜色或变化像素选择样本。
- 预先固定阈值：平均绝对差≤0.75、p99≤2、最大差≤3（均为 8 位 RGB）；修复后实际最大差为 **0**，不是放宽阈值得到通过。

| 环境与对象 | 场景 | 检查 | 状态 |
|---|---:|---:|---|
| 纯原版旧实现 R2 | 40 | 445/445 | 已复现旧 AO 暗影，控制组通过 |
| 纯原版修复候选 R3 | 40 | 457/457 | 真实邻接表面与空气对照逐像素相同 |
| Iris＋Sodium，关闭光影，修复候选 R3 | 40 | 457/457 | 同上；完整碰撞与实际光照保持 |
| Sodium 旧实现同包完整重测 R4 | 40 | 445/445 | 同一 JAR、完整声明逐项相同；初次异常基准帧及红项仍保留 |
| 实际原版天空对照 | 23 | 23/23 | 最大单通道差1/255；日月星/晨昏及云高命令通过 |
| 原版/ITRP 原生/ITRP FSR 主视角 | 84 | 831/831 | 天空几何、遮挡及持物检查通过 |
| 三环境物品/选框 | 含于上组 | 342/342 | 三环境均114/114，独立投影与线条位置通过 |

六个修复候选隔离实例合计187次截图，实际安装的主 JAR 均与冻结候选哈希一致；运行 ERROR、致命异常、渲染线程异常、GL 错误均为0，全部帧的 Iris 逻辑状态恢复一致，目标光栅外颜色/深度无变化。[运行身份核验](<../build/reports/r27-runtime-bindings.json>)、[逐帧状态检查](<../build/reports/r27-frame-state-check.json>)。

修复前纯原版在开启平滑光照时，10 组接触带合计 133,248 个像素，平均额外暗化 4.13–14.68/255；加权平均 9.1544/255。修复后对应表面的额外暗化为 0，实际光级不变。这里的“逐像素相同”只指独立选定的真实邻接表面，不宣称空气和天空方块的整幅截图相同。

把旧实现按新验收标准送检，得到 **427/455**，保留 **28 个预期失败**：20 个图像差异、8 个旧 AO 系数。这证明检查器确实检出了原始缺陷。[负对照报告](<../build/reports/r27-ao-legacy-negative-control-r2.json>)。

![昼夜修复前后及空气参考](<../build/reports/r27-ao-before-after-r3-display2.png>)

![独立选定真实表面的差异放大图](<../build/reports/r27-ao-terrain-difference-r3-display2.png>)

拼图仅为展示缩放；数值均使用未修改的 1280×800 原始图。已查看原始图和拼图。

## 3. 未隐藏的诊断与边界

1. 首轮 R1 测试放置了人工光源，复现 AO 暗影，但夜间空白控制也出现随机亮度差异，392/405。原版 `LightTexture` 字节码表明其随机 `blockLightRedFlicker` 参与方块光照。因此 R2 移除测试人工光源，保留真实日夜与相同阈值；未冻结或修改生产光照。R1 原报告保留，不把它改写为通过。
2. Sodium 旧实现首轮 R3 为429/445：`ao-corner-day-on-air` 未画出三面测试墙，导致玻璃和移除对照16项差异。该场景没有天空方块；服务器已反馈填充成功。R4 使用同一 JAR、相同的完整 fixture 声明和全部40场景重新运行，**445/445**；两次的 `fixture.json` 逐项相同。原图、日志、红项及首轮批次退出1、`allStageExitsZero=false` 原样保留。此重测不证明历史高墙问题修复，也没有添加全局刷新、排除场景或阈值豁免。[首轮批次](<../build/reports/r27-validation-r3.json>)、[同包复验与最终专项汇总](<../build/reports/r27-final-validation.json>)。
3. 传送门兼容工作保持停止；本轮没有加载传送门或 Distant Horizons。
4. test.6 的透明层移除后全帧恢复差异、历史偶发高墙缺绘、完整动态 DH 与旧完整 release 矩阵均不宣称在本轮修复或重新通过；独立审计仍延后。
5. 全部实机验证为本项目新建隔离世界，未打开用户存档、替换用户实例 JAR、修改用户光影设置或停止用户游戏。未派发子个体。

## 4. 冻结候选身份

- 主 JAR：`skyboxblock-1.0.2-test.7.jar`，SHA-256 `0d82c0e5a1bed8be6e1f13bb2c008a38027e4be2425a7203c836628d63a1e013`。
- 匹配源码 JAR：`skyboxblock-1.0.2-test.7-sources.jar`，SHA-256 `0099b15b8276cbf4396705c48296e3ccb77e2bb71cd5e0d15713dee1117522e0`。
- 最终构建：[R3 日志](<../build/logs/r27-ao-fixed-build-r3.log>)；339,289 项数学断言通过。
- test.6 主 JAR 仍保持 `c1ceb286dbf106ca86cec9ca5b44f4f2acd7138bf3467a9574b178c957bec0fb`，旧交付与归档保留。

## 5. 安装与复现

仅安装[主 JAR](<../build/libs/skyboxblock-1.0.2-test.7.jar>)，保留同 ID 的一个版本；[源码 JAR](<../build/libs/skyboxblock-1.0.2-test.7-sources.jar>)与测试工具不作为模组安装。[完整交付 ZIP](<../build/deliverables/skyboxblock-test7-r27-20261020.zip>)包含主包、源码 JAR、完整源码/工具 ZIP、原始截图、运行日志、逐项报告、保留红项及文件哈希清单。包内容与 ZIP CRC/逐项 SHA-256 由[打包工具](<../tools/package-r27-test7.py>)核验，详见[包 QA](<../build/reports/r27-package-qa.json>)。云高默认仍192，原有命令及配置保持。

```text
/skyboxblock cloudheight 240
/skyboxblock cloudheight reset
/skyboxblock reload
```

```powershell
python tools/check-ambient-occlusion-r27.py --selftest
python tools/render-regression.py --profile vanilla --label UNIQUE_LABEL --jar build/libs/skyboxblock-1.0.2-test.7.jar --suite ambient --timeout 600 --no-probe-clouds
python tools/check-ambient-occlusion-r27.py build/render-regression/UNIQUE_LABEL --expect fixed --output build/reports/UNIQUE_LABEL.json
```

每次使用唯一 label 和报告路径；已有实机证据不覆盖。原生天空及位置回归继续使用现有 R26 检查器，完整编排见 [`run-r27-validation.py`](<../tools/run-r27-validation.py>)。
