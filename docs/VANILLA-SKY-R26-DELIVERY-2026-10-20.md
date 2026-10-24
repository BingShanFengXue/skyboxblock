# test.6 / R26：原版天空还原、泛蓝修正与独立云高

作者：Bing6512。Minecraft 1.21.1 / Fabric。

## 范围

按用户最新要求，停止追加沉浸式传送门兼容。本轮验证均不加载该模组。历史 R23/R25 产物和失败报告保留，不用新范围覆盖旧门禁。

本轮修正原版天空盒方块的月亮纹理方向、日出日落与原版天空的差异，并尝试消除用户截图中的夜间蓝晕。新增仅作用于此方块类型的云层高度命令及配置。

## 实现

1. 月亮：原先仅翻转下半球四边形的Z方向，UV仍沿用太阳顺序，导致月面纹理旋转180度。现在严格匹配1.21.1原版每个顶点的UV，八个月相分别验证。
2. 晨昏：移除自拟的射线渐变和指数晨昏染色，使用原版八三角天空圆盘、原版雾距插值、16段晨昏扇形及原版混合顺序。普通天空读取原版已经采样的生物群系/天气/方向性晨昏雾色，不读取世界画面RGB。
3. 星星：恢复原版绘制顺序及RGB、alpha同时受亮度控制。
4. 夜间蓝晕：原生ITRP测试成功复现。单将早期材质置黑没有改善，试验已撤回，失败报告保留。有效修正位于精确哈希限定的ITRP背景适配：按照该包 `VanillaComposite.glsl` 的曝光相对HDR单位注入颜色，读取当前Iris管线所拥有的 `pixelData2D` 标量曝光数据，校验唯一归属、512×513及RG16F存储；不改光影ZIP、不关闭BLOOM、不读取目标世界颜色。
5. 云高：默认仍为192，增加独立 `vanillaCloudHeight`，不改维度云高或其他天空盒类型的设置。见[命令及配置说明](VANILLA-CLOUD-HEIGHT.md)。

## 最终验证

[构建R5日志](<../build/logs/r26-test6-build-r5.log>)成功；数学/几何回归 **339,289** 项断言通过。[运行身份及全帧状态核验](<../build/reports/r26-runtime-bindings.json>)覆盖 **170次候选截图**，七个隔离实例全部绑定同一主JAR，ERROR、致命异常、渲染线程异常和GL错误均为0。所有有天空几何的光影帧均实际启用当前管线的曝光桥接，状态恢复前后相等。

| 专项 | 实际结果 |
|---|---|
| 纯原版+Fabric基础天空 | 23/23；20场RGB逐像素完全一致，其余3场仅1–3像素相差1/255 |
| 三环境主视角/遮挡冒烟 | 原版、ITRP原生、ITRP FSR共84场，831/831 |
| 物品/选框独立位置 | 三环境342/342 |
| 云高命令 | 实际分派96→240→reload仍240→reset192；原版维度云高每次仍192 |
| 检查器负对照 | 月亮旋转180度、额外蓝色、错误晨昏颜色全部检出；原图未修改 |
| ITRP透明遮挡诊断 | 51场，1227/1228；保留1项移除后整帧时序恢复差异 |

原版23场包含八个月相、六个晨昏时间、四个夜间方向、白天及四次实际云高命令。[全画面像素报告](<../build/reports/r26-test6-reference-final-r5.json>)不使用颜色遮罩或排除区域。比较的是无云基础天空，不把独立云层或所有光影最终画面也宣称为逐像素一致。

[原生ITRP蓝晕对照](<../build/reports/r26-blue-iris-r5.json>)与[FSR蓝晕对照](<../build/reports/r26-blue-fsr-r5.json>)均确认相同光影ZIP、支持模组、选项和真实几何。独立射线选取72,464个实体地面像素，原生平均蓝色偏差降低 **84.95%–85.34%**，FSR降低 **86.01%–86.24%**。BLOOM保持开启；仍保留光影自身夜色，不宣称普遍彻底消除所有光影泛光。

[透明层原始报告](<../build/reports/r26-test6-transparency-iris-r5.json>)的唯一红项是 `trans-removed-none:removed-world-restored`：983,291个像素的整帧比较MAE=1.018、p99=6/255、最大51/255。它不是本轮已证明修复的项目；旧阈值原样保留。该轮采用51场常规等待协议，不冒充15秒+300帧的settled协议。[批次运行汇总](<../build/reports/r26-validation-summary-r5.json>)因此保留退出1及 `allStageExitsZero=false`，没有用整体绿标隐藏此红项。

![原版与私有天空对照](<../build/reports/r26-vanilla-reference-comparison.png>)

![蓝晕修正前后](<../build/reports/r26-blue-halo-before-after.png>)

蓝晕图来自两次受控实机运行，不是同一帧；拼图为显示缩放，数字测量使用原始1280×800图片。

## 操作

```text
/skyboxblock cloudheight 240
/skyboxblock cloudheight
/skyboxblock cloudheight reset
/skyboxblock reload
```

命令仅调整当前客户端的原版天空盒方块，并写入本实例的 `config/skyboxblock.properties`，不要求服务器OP。手动配置示例：

```properties
vanillaCloudHeight=240.0
```

替换模组时只安装主JAR，匹配sources包仅供查看源码；保留同一mod ID的一个版本。旧版本文件和存档建议自行保留回滚备份。本轮没有自动改动用户实例、世界或光影配置。

## 交付身份

- [test.6主包](<../build/libs/skyboxblock-1.0.2-test.6.jar>)：223,652字节，SHA-256 `c1ceb286dbf106ca86cec9ca5b44f4f2acd7138bf3467a9574b178c957bec0fb`。
- [匹配源码JAR](<../build/libs/skyboxblock-1.0.2-test.6-sources.jar>)：128,611字节，SHA-256 `5ba7b431e8f82b7a9aff30bb3c9036f801edcdf1bd5dea3a70773199b3d8d4d6`。
- 打包工具固定这两个哈希，逐项校验复制字节、ZIP CRC及归档内容；打包不会重新编译或替换已验证主包。
- 历史test.4基线SHA-256仍为 `59ccfd220789db8ebbc0b041f1818d747ae4e84ce915a70ca0658a49398f567d`。

## 边界

- 不新增传送门测试、兼容声明或FSR门户支持承诺。
- 仅受支持的Iris 1.8.1和精确ITRP Alpha 0.8.31归档启用曝光适配，其他包不套用其HDR约定。
- 旧test.5偶发高墙缺绘、完整DH及settled透明层恢复的历史红项未在本轮宣称修复。
- 本轮是有明确范围的test.6测试版交付，不等同旧完整发布矩阵重新通过。
