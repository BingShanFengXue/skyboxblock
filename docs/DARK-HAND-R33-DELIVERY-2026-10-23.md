# R33 光影暗色天空手臂泛白修复交付

日期：2026-10-23。版本：**1.0.3-test.3-r33**。

## 修复内容

修复当前Iris/ITRP光影组合下，末日陆地、银河、纯黑及三者混合密闭空间引起的手臂过曝变白。保留R32运动／后排轮廓修复、晴空A/B名称与两张4K PNG。

原因是艺术化暗色显示背景参与了物理相机测光：纯黑房内平均亮度降至0.00274，曝光增益约360.75，真实手臂内部像素全部白剪切。新实现只为原光影曝光compute提供私有测光输入代理，对实际可见的三种暗天空建立物理天空／18%线性中性参考的测光下限。更亮的真实样本不降低，普通非目标材质不启用。

原曝光compute照常运行并自行更新曝光；不关闭自动曝光或泛光、不修改shader ZIP、不画手臂补丁、不直接修改世界或泛光图像。只在这一次dispatch期间替换一个sampler，finally恢复。实际场景RGB与天空显示仍走原有路径；GPU只处理原compute已使用的低分辨率测光图集，正常游戏没有新增CPU颜色读回。

## 同包实机结果

环境：MC1.21.1/Fabric、Iris1.8.1、Sodium0.6.7、DH3.3.3、ITRP Alpha0.8.31。原生和FSR2分别验证。

| 密闭材料 | 原生：场景 / 检查 | FSR2：场景 / 检查 |
|---|---:|---:|
| 纯黑 | 15 / 182/182 | 15 / 182/182 |
| 银河 | 15 / 182/182 | 15 / 182/182 |
| 末日陆地 | 15 / 182/182 | 15 / 182/182 |
| 三者混合 | 15 / 182/182 | 15 / 182/182 |
| 原版天空对照 | 15 / 176/176 | 15 / 176/176 |

每组15场景包含进入、驻留30秒、离开、再次进入驻留30秒、移除恢复。固定白天6000、实际天空光15／方块光0，隔离材质造成的虚假暗处适应，不用锁定曝光。光影定量测试沿用MOTION_BLUR=false；用户配置没有改动。

加上原生／FSR各22个定向前景、混材、重载、缩放、移除回归，正向共 **194场景、2232/2232检查**。另有无Iris四场景冒烟 **71/71**，不是全量无光影验收。

- 全部正向手臂最大白剪切比例：**0.000000%**（真实手部深度掩码内部RGB各通道≥248）。最小平均通道色差：31.352，肤色纹理保留。
- 所有采样的手臂最终回填前后差异为0，证明不是在最终画面重涂手臂。
- 同JAR关闭代理的负对照15场景仍复现白手臂；纯黑30秒曝光 **360.750 → 6.078**。
- 目标暗天空及混合室的显示颜色按R32独立方向oracle核对，最大平均绝对RGB误差 **0.165062/255**；纯黑仍为纯黑。
- 原版对照全程要求代理不启用；所有目标场景确认代理实际启用且输入sampler已恢复。
- 实际包／保留范围审计 **171/171**：所有原颜色shader、方向数学和PNG与R32-4K基线逐字节一致，两张晴空PNG4096×2048。
- 数学回归389,385断言；另有100,000个曝光／覆盖／颜色输入的有限半浮点、零覆盖不变及更亮样本保留属性检查通过。

同包负对照与修复图来自两次独立固定场景运行，不冒称同一帧。外部认证网络日志如有保留在摘要和原始日志，不归类为模组渲染故障；没有删改原始输出。

## 包身份与复现

主包SHA-256：

```text
531669a68c692ca56a578b712de71cf03ca827c854b184a11f8fd66e86628771
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build
python tools/verify-r33-package.py
python tools/render-regression.py --profile iris-dh --suite handexposure --hand-material black --jar build/libs/skyboxblock-1.0.3-test.3-r33.jar --label NEW_LABEL --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --shader-option FSR2_SCALE=2 --shader-option MOTION_BLUR=false --timeout 700
python tools/check-r33-hand.py build/render-regression/NEW_LABEL --expect fixed --output build/reports/NEW_LABEL.json
```

材料可换成galaxy、apocalypse、mixed；同JAR负对照增加--negative-dark-meter，并用--expect negative检查。所有实例实际安装的JAR与交付包相同。

## 范围

仍为有范围测试版；测光适配仅在已校验的ITRP包／pipeline／输入图像归属成立时启用，不扩为所有光影版本通用兼容。资源／性能长测未启动，旧严格恢复四红、门户及其它历史暂缓项保留；没有新增独立第三方终审。

用户安装包、存档和光影ZIP未替换；旧归档只保留不覆盖。源码包不包含提取分析的第三方shader正文／游戏JAR／用户存档。素材来源声明继续适用。

过程见 [执行记录](<DARK-HAND-R33-WORKLOG-2026-10-23.md>)。
