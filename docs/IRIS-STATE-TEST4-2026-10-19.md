# test.4：光影掉落物 / 全局选择框错位与固定云高

## 交付

[skyboxblock-1.0.2-test.4.jar](<../build/libs/skyboxblock-1.0.2-test.4.jar>)，**81,880 字节**。

```text
SHA-256: 59ccfd220789db8ebbc0b041f1818d747ae4e84ce915a70ca0658a49398f567d
```

已修复光影开启、可见天空方块时的掉落物与全局选择框错位；独立云层固定为原版名义高度 **Y=192**，保留水平漂移与昼夜 / 天气颜色。三环境共 **111 个最终场景、1,468/1,468 项图像 / 深度 / 位置检查通过**。[最终包与证据身份复核](<../build/reports/state-test4-final-acceptance.json>) **38/38**。

![同一 jar 的旧状态负对照与修复后画面](<screenshots/state-test4-before-after-20261019.png>)

两侧为相同相机、相同 FSR2 设置的独立实机运行，不是同一帧；掉落物旋转 / 浮动相位可不同。左侧选择框偏到右上方、物品上移且错色；右侧恢复世界坐标位置。

## 用户现象

- 可见天空方块时，掉落物严重错位；任意普通方块与天空方块的选择框也严重错位。
- 用户确认仅在开启光影时出现。天空本身的显示效果已达到预期，应保持现有独立天空架构。
- 云层要求固定为原版高度。

原始截图：[掉落物](<screenshots/user-items-offset-20261019.png>)、[选择框](<screenshots/user-outline-offset-20261019.png>)。

## 根因：恢复了 GL，却漏了 Iris 的逻辑目标状态

Iris 1.8.1 在 `RenderTarget.bindWrite` 的状态跟踪注入中，把“绑定对象是否为 Minecraft 主目标”写入 `WorldRenderingPipeline.setIsMainBound(boolean)`。

本模组生成独立天空时绑定私有 `TextureTarget`，将该标志设成 `false`。旧版的 GL 状态保护恢复了 draw/read FBO、viewport、程序、纹理和缓存，却没有恢复这个 Iris 逻辑标志。

Iris 的着色器选择条件为：

```text
shouldOverrideShaders = isRenderingWorld && isMainBound
```

因此，私有天空 pass 结束后，随后提交的掉落物与线条可能使用原版回退着色器，而不是 ITRP 的 `ExtendedShader`。ITRP 实体 / 线条顶点着色器中的 FSR 缩放没有执行；随后整体上采样，使这些对象严重位移。无光影没有这条逻辑路径，与用户补充的触发条件一致。

字节码证据：[目标绑定 hook](<../build/iris-target-hooks-bytecode.txt#L9-L31>)、[Iris 管线状态](<../build/iris-framebuffer-tracking-bytecode.txt>)、[ITRP 线条顶点着色器](<../build/itrp-inspect/shaders/Lib/Programs/Gbuffers/Line_VS.glsl>)、[ITRP 实体顶点着色器](<../build/itrp-inspect/shaders/Lib/Programs/Gbuffers/Entities_VS.glsl>)。

## 修复

- [Iris 适配](<../src/main/java/dev/yg/skyboxblock/client/ModCompat.java>)增加逻辑目标作用域：保存进入前的 `isMainBound`，私有绘制期间设为 `false`，退出时恢复原值。
- [GL 状态保护](<../src/main/java/dev/yg/skyboxblock/client/SkyGlState.java>)持有该作用域，在真实 GL 状态恢复后通过 `finally` 收束；多层嵌套恢复各自进入前的值，不粗暴地统一设为 `true`。
- 私有着色器绘制期间保持 Iris 的世界替换 / 写锁路径关闭；返回世界阶段后，实体与线条重新使用原光影管线。
- 继续保留独立原版天空、世界空间几何、早期深度与最终颜色恢复；没有回退到抓取世界颜色或 CPU/NDC 覆盖。

## 云层

MC 1.21.1 的 `OverworldEffects` 构造函数使用名义高度 **192.0F**，见[原版字节码](<../build/vanilla-cloud-height-bytecode.txt>)。

旧版读取 `level.effects().getCloudHeight()`，会随维度效果覆盖而变化。当前[云层渲染器](<../src/main/java/dev/yg/skyboxblock/client/SkyVanillaClouds.java>)在普通天空维度固定使用 **Y=192**；继续保留原版的 `+0.33F` 几何偏移、立体云厚度、水平漂移和昼夜 / 天气颜色。无普通天空的维度不额外生成云层。

[纯数学回归](<../src/test/java/dev/yg/skyboxblock/client/SkyGeometryRegression.java>)新增 10,000 组不同相机高度的云层世界高度不变量，合计 **65,018** 项断言。

## 新的位置验收，避免旧回归的盲区

旧 test.3 的天空覆盖、颜色和前景保留检查没有独立证明“选择框位于正确位置”。错误位置的线条也可能被当作真实前景保留，因此旧检查通过不代表本问题已被覆盖。

新增加 9 个场景：无天空方块基线、可见天空时选普通方块、选择天空方块、替换为另一普通方块、斜视、窗口缩放、资源重载、移除天空方块后的恢复，以及清理。

- 两个不受重力 / 拾取影响的真实绿宝石掉落实体，记录其世界坐标、年龄、原版摆动相位与帧插值。
- 固定已知被选方块 `[0,141,2]`，记录实际命中方块和形状，独立从世界坐标投影十二条边。
- [实际线条观测](<../src/main/java/dev/yg/skyboxblock/mixin/BufferSourceMixin.java>)围绕 `RenderType` 的真实状态 / 绘制范围采集像素差分，同时覆盖原版与 Iris 自有分段批处理。Iris 会绕过原版 `BufferSource` 提交入口；仅观测原版批次会漏掉光影线条。
- [位置检查器](<../tools/check-alignment-regression.py>)要求至少 99% 的真实线条像素距离独立投影边不超过 3 个有效光栅像素，并检查独立投影中最大可见面的四条主边覆盖率；不把“线条还在”当成正确位置。斜视时次可见薄面的远边可能被正常深度测试裁掉，故不把每条薄面边都作为必绘条件，但任何实际写入的线条像素仍接受同一严格位置检查。
- 绿宝石按原版 bob、GROUND 缩放与 `2/16` 平移计算显示中心，利用实际绿色像素连通区域核对中心位置；不使用本模组的几何帮助函数。
- 单独核对私有绘制前后的 Iris 标志与实际线条着色器类。

正常运行关闭上述颜色读回。诊断负对照开关也只有显式启用回归时才生效。

## 当前实测与最终验收

首轮修复位置专项：[9 场景、102/102](<../build/reports/state-test4-fixed-align-r3-alignment.json>)。相同设置下，修复前后标志分别为 `true → false` 和 `true → true`；修复后实际线条使用 `net.irisshaders.iris.pipeline.programs.ExtendedShader`。

| 最终环境 | 实机场景 / 用时 | 天空、遮挡、持物检查 | 独立位置检查 | 最大物品中心误差 |
|---|---|---|---|---|
| 完整参考模组组 + ITRP，保留用户 `FSR2_SCALE=2` | **37 / 216.55 秒** | **397/397** | **102/102** | **6.533 px** |
| Iris + Sodium + ITRP，隔离配置关闭 FSR2 | **37 / 126.78 秒** | **399/399** | **102/102** | **4.485 px** |
| 无光影 Fabric 对照 | **37 / 110.83 秒** | **366/366** | **102/102** | **3.286 px** |

三组实际选框像素全部落在独立投影边的 3 个有效光栅像素范围内，主可见面四条边覆盖率均为 **100%**；最终天空采样 RGB 最大误差均为 **0**。三组资源重载均已完成，所有运行退出码为 0，致命错误和渲染线程错误均为 0。

- 完整组：[运行](<../build/render-regression/state-test4-final-itrp-full/run-result.json>)、[天空检查](<../build/reports/state-test4-final-itrp-full.json>)、[位置检查](<../build/reports/state-test4-final-itrp-full-alignment.json>)。
- 关闭 FSR2：[运行](<../build/render-regression/state-test4-final-itrp-no-fsr/run-result.json>)、[天空检查](<../build/reports/state-test4-final-itrp-no-fsr.json>)、[位置检查](<../build/reports/state-test4-final-itrp-no-fsr-alignment.json>)。
- 无光影：[运行](<../build/render-regression/state-test4-final-vanilla/run-result.json>)、[天空检查](<../build/reports/state-test4-final-vanilla.json>)、[位置检查](<../build/reports/state-test4-final-vanilla-alignment.json>)。
- [完整构建日志](<../build/state-test4-final-build.log>)：`BUILD SUCCESSFUL`；数学回归 **65,018/65,018**。最后复核构建未改变已经实测的 jar 哈希。
- [交付包审计](<../build/reports/skybox-test4-package-audit.json>)：**23/23**，仅 `sky_block`、shader 完整、无生产世界颜色读回、Iris 作用域和固定云高常量存在。
- [最终身份 / 负对照复核](<../build/reports/state-test4-final-acceptance.json>)：**38/38**；三组实际复制的 jar、记录的哈希与交付包完全一致。

完整参考模组组保留 **75 条第三方 ERROR 日志行**，包括 Yuushya 模型 / 配方、数据修复器和 IP/C2ME 区块加载，详见运行记录；另外两组 ERROR 为 0。这里的“全部通过”指上述验收判据，不把完整第三方整包写成零 ERROR。

### 负对照与检查器自测

1. 同一个交付 jar，只在隔离回归中关闭 Iris 状态恢复：[9 个场景正常完成](<../build/render-regression/state-test4-negative-r3/run-result.json>)，位置报告 **54/102**，出现 **48 个预期失败**，且无天空方块基线仍通过。实际线条回退成 `net.minecraft.class_5944`，即 remap 后的原版 `ShaderInstance`。错误路径在预期世界目标中没有正确线条写入，最终帧仍能看到右上方错位线框；错色 / 丢失的绿宝石候选记为 `null`，不编造中心坐标。见[负对照位置报告](<../build/reports/state-test4-negative-r3-alignment.json>)。
2. 为证明位置检查独立于“状态已恢复”元数据，[合成图像偏移自测](<../tools/test-position-oracle.py>)保持所有元数据不变：线条掩码横移 20 个有效光栅像素，**8/8** 位置失败被检出；最终图横移 60 个显示像素，**16/16** 掉落物位置失败被检出；全部逻辑状态检查仍通过。它们是明确标注的合成检查器自测，不算新增实机场景。见[自测报告](<../build/reports/state-test4-position-oracle-controls.json>)。
3. 初次完整组位置检查为 [101/102](<../build/reports/state-test4-final-itrp-full-alignment-initial-edge-visibility.json>)：斜视时一条极薄次可见面的远边被正常深度测试裁掉。最终将“必须覆盖”的几何判据独立定义为最大投影可见面的四条主边，保留原来的 3 px / 99% 全像素位置要求，未移动或改写实机图片。
4. 初次原版位置检查为 [97/102](<../build/reports/state-test4-final-vanilla-alignment-initial-dark-items.json>)：阴影中侧向绿宝石比初始亮度阈值更暗。最终保持绿色色相判据、纳入暗绿像素，并采用全局一一匹配；位置容差未扩大。最终最大原版中心误差仅 **3.286 px**。

## 安装、复现与归档

用[交付 jar](<../build/libs/skyboxblock-1.0.2-test.4.jar>)替换旧同 ID 版本，只保留一个 `skyboxblock` 包。参考实例目前仍是用户安装的 test.3，原哈希已核对保持不变；本轮未自动替换它。

在项目根运行，`python` 使用带 NumPy / Pillow 的环境：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build
python tools/render-regression.py --profile full --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --suite all --heap 8G --label my-test4-full --timeout 600
python tools/check-world-regression.py build/render-regression/my-test4-full/results --output build/reports/my-test4-full.json
python tools/check-alignment-regression.py build/render-regression/my-test4-full/results --output build/reports/my-test4-full-alignment.json --annotate
# 两个对照分别使用 --profile iris --shader-option FSR2_SCALE=-1 与 --profile vanilla
# 真实负对照：--suite alignment --negative-iris-state
python tools/verify-skybox-package.py
python tools/verify-render-acceptance.py build/render-regression/state-test4-final-itrp-full build/render-regression/state-test4-final-itrp-no-fsr build/render-regression/state-test4-final-vanilla --negative build/render-regression/state-test4-negative-r3 --output build/reports/state-test4-final-acceptance.json
```

每次实机 label 保持唯一。归档只新增、不覆盖旧快照：[test.4 源码、交付包与选定证据快照](<../../artifacts/snapshots/skybox-test4-state-alignment-20261019-final.zip>)；包含源码、工具、文档、实际 jar、所有本轮选定 fixture 的机器可读元数据和关键截图。完整截图集合仍保留在工作区各隔离 fixture 中。

本轮未派发子个体。技能目录没有 Minecraft 渲染专项；使用项目构建 / 隔离实机工具与 NumPy / Pillow 完成验证。

## 范围

测试只使用隔离新世界；参考实例的用户存档、已安装 jar 与光影选项保持原样。本轮结论以实际测试的 Iris 1.8.1 / ITRP Alpha 0.8.31 为准。门户内部最终颜色、透明前景合成与真实 DH LOD 遮挡仍保留原有待验证状态。
