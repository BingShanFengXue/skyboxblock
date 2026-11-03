# Temporal 根因：当前证据中的差异来自真实近地形轮廓，不是天空内部接缝

诊断日期：2026-11-02，Asia/Shanghai。只读取证；本轮唯一工作区写入为本报告。

## 1. 可直接交给主线的结论

**本批 8 组异常没有证明“同材质前后天空内部细裂”。它们全部落在尚未清理的真实近地形顶面/轮廓。当前 `backdrop_mask` 只证明射线与有限背景平面相交，没有证明沿途不存在更近地形，因此把真实地形像素算成了 `ownedPixels`。**

这不是泛泛怀疑“草/云会动”：新 GPU 快照在唯一的 960 个坏点处提供了直接反证：背景帧是更近的真实地形深度、`coverage=(0,0)`；前景帧是天空深度、`coverage.g=7`。两帧透明快照差为零。两个独立事实闭环：

1. GPU 地形深度反投影到 `y≈-60, z≈-0.006`，恰在旧清场范围 `z>=0` 外面。
2. 从实际相机矩阵投影 `y=-60,z=0` 地形唇，预测了原 8 组全部坏点的区域，并解释了 pose2 全零。

**建议主线现在先保持渲染决策不变**，用已经加入的负 z 清场隔离真实地形，再执行原严格颜色判据；不提高误差阈值、不删除透明保护、不增加深度 tolerance、不恢复已删的 `owner.x==gl_FragCoord.z` 限制。

当前证据排除了本次裂点由 foreground 帧 `TransparentGuard` 或 `WorldDepth-ownerDepth` 失败产生；也未证明存在纯天空内部 coverage 缺口。若清场后仍有坏点，必须重新双向分类，而不是沿用本报告地形结论。

## 2. 边界与方法

- 已核对本轮可用 skill catalog：只有 ACL/Office 类，无渲染诊断匹配项；未加载无关 skill。
- 已读工作区 [AGENTS](../../AGENTS.md)、空的用户级 AGENTS、[LESSONS 渲染历史](../../docs/LESSONS.md#L191-L269)、现有核心语义复核及当前源码。
- 文本只用 `read`/`grep`；路径发现用 `glob`。二进制 `.png/.f32` 计算用打包 Python + NumPy/Pillow，从 PowerShell 启动；PowerShell 未读取/写入 java/json/md/mjs。
- 未改源码、shader、fixture、构建/启动工具；未跑 Gradle、javac、游戏或 fixture 命令；没有启动子代理。
- 未重跑 `tools/temporal-debug.py`：其 `main()` 会主动向实例发请求。本轮只读它的实现以及主线已生成的文件，并独立离线复算。
- 未改原回归证据；未给任何图像写入/替换输出。图像目视已查看 debug 的 background、foreground、bad。
- 首次查看 temporal-debug 目录时尚无 GPU 输入；先完成源码/旧版比较，随后输入已到，才读取并计算。

## 3. 最强证据：同帧 GPU 输入的双向分类

数据源：[gpu-diagnosis](../build/render-regression/temporal-debug-r2/gpu-diagnosis.json)、[background metadata](../build/render-regression/temporal-debug-r2/control/gpu-background/gpu-inputs.json)、[foreground metadata](../build/render-regression/temporal-debug-r2/control/gpu-foreground/gpu-inputs.json)。

输入尺寸 960×600，little-endian float32，行 0 为 GL 底部；覆盖 RG32F，深度 R32F，before/after 是从底层 RGBA16F 快照以 RGBA float32 读回。相机 `(.5,-58.38000011444092,-8)`；投影和 view 两帧一致；仅实际 jitter/场景与 token 不同。两帧 `resolved/depthCopied/beforeTransparency/afterTransparency` 均为 true，scale 均为 (1,1)，唯一材质编号 6，owner 标记为 7。

| 输入 | background | foreground |
|---|---:|---:|
| token | 1400 | 1526 |
| jitterX | -0.00042937696 | 0.0005919665 |
| jitterY | +0.00042854308 | -0.000037059785 |
| jitter 的 Y 像素偏移（jitterY×600/2） | +0.128562924 | -0.0111179355 |
| 960 坏点中 owner 不等于 7 | **960** | **0** |
| 960 坏点中 worldDepth 不等于 ownerDepth | 960（owner 本来为空） | **0** |
| 960 坏点中 transparency delta 超生产 limit | **0** | **0** |
| 坏点的 `max(abs(after-before))` | 全部 0 | 全部 0 |

PNG 的 960 个坏点精确为：**x=0..959，y=578，整条一像素横线**。不是柱体的内部前后深度交界。该次原 `ownedPixels=573050`，`maxDifference=242`。

具体点（PNG x=480,y=578，经各自 jitter 映射仍取 GL texel x480,y21）：

| 量 | background | foreground |
|---|---|---|
| owner | `[0,0]` | `[0.0037830809596925974,7]` |
| worldDepth | `0.007597300224006176` | `0.0037830809596925974` |
| before=after | `[.352783203125,.458740234375,.215576171875,1]` | `[.08233642578125,.0941162109375,.14111328125,1]` |
| depth 反投影世界坐标 | `[.4877177304,-59.99998443,-.006028365743]` | `[.4924839544,-61.61842151,8.000004332]` |

同一坏行的 x720，foreground 深度反投影为 `[-4.727654994,-60.40401399,2.000005192]`，即近天空柱；其它样点为后墙。同材质前后天空确实都能正常取得 owner；background 的对照却在显示真实近地形。

相邻行进一步佐证：

- PNG y576、577：两帧 owner 全 7，depth==ownerDepth，透明差为 0。
- PNG y578：背景 owner 全 0，前景全 7，形成整行差。
- PNG y579、580：两帧 owner 全 0，均属地形；该次 RGB 最大差仅 6。
- y575..577 两张最终 PNG 逐像素差为 0。

因此，单独检查 foreground 的 CPU 分类会得出“所有 guard 均通过但仍差 960 点”；**必须同时检查 background，否则把正确保留下来的近地形误诊为前景天空漏绘。**

可见颜色示例：PNG (0,578) 背景 `[211,226,166]`（地形），前景 `[29,33,45]`（星空）；(480,578) 分别 `[238,248,200]` 与 `[22,24,37]`。对应 [bad mask](../build/render-regression/temporal-debug-r2/control/debug-bad.png)、[background](../build/render-regression/temporal-debug-r2/control/debug-background.png)、[foreground](../build/render-regression/temporal-debug-r2/control/debug-foreground.png)。

## 4. 几何闭环：为何背景平面 mask 会包含草地

原始清场请求可在 [itrp pose0 background](../build/render-regression/port-final-r2/control/itrp-depth-0-background.json#L5-L37) 核对：只清 z0..3 与 z4..7、y-63..0，然后在 z8 放背景。**z<0 的真实地形仍在。**

生产 [backdrop_mask](../tools/run-live-matrix.py#L175-L191) 做的是：

```text
ray = inverse(cameraVP) 双点差分
t = (8-eye.z)/ray.z
hit = eye + ray*t
mask = t>0 && -31.5<hit.x<32.5 && -62.5<hit.y<.5
```

这是有限背景几何的投影内区，不是遮挡/最近所有权检测。它不查询世界深度，也不排除射线在 z8 之前命中 `y=-60,z<0` 的地形。

用 debug 的无 jitter display ray，在 PNG (480,578) 上得到 z8 交点 `[.4825903288,-61.61873837,8]`：当然通过 mask；但沿途接近 `z=0` 的地形唇处已存在真实地形。GPU 背景帧的直接深度把该表面钉在 `y=-59.99998443,z=-.00602837`。这不是云动画、bob 原点偏移，也不是 raw cameraVP 与 worldVP 不一致。

取所有 pose 的**原始 metadata cameraVP**，把真实地形边界 `y=-60,z=0` 投影到 PNG 坐标，得到：

| pose | 真实地形边界（x,y 是像素连续坐标） | 屏内位置 |
|---|---|---|
| 0 | `y=578.544138439837` | 一条水平线，恰穿过第578行采样中心578.5附近 |
| 1 | `y=-0.03641048132594771*x+590.4794355786597` | 横跨 y555.525..590.479，和斜坏点带重合 |
| 2 | `y=0.048245186579162824*x+607.4901746319609` | y607.490..653.806，整条在600高视野外 |

pose0 采样中心离真实边界仅 -0.04413844px，两个实际 jitter 阶段足以让 nearest coverage owner 翻转。pose2 真实地形出画，因此五环境全部 max=0；不需要假设某 pack 在这个机位突然修好内部天空。

注意：这里证实的是**不同 jitter 阶段下真实前景轮廓/nearest ownership 的变化混入测试**。它不是放宽深度容差的理由；更不是让天空覆盖真实地形的理由。

## 5. 原始 15 组 PNG 独立复算

数据源：[port-final matrix report](../build/render-regression/port-final-r2/matrix-report.json#L16376-L16383) 与其 control 下 15 对 depth PNG；mask 完全复现原公式，保留原 `>8` 分类，未调整颜色阈值。

| 环境 | pose0 bad/max | pose1 bad/max | pose2 bad/max |
|---|---:|---:|---:|
| native/vanilla | 0 / 0 | 0 / 0 | 0 / 0 |
| ITRP | 970 / 233 | 872 / 243 | 0 / 0 |
| Complementary | 5 / 11 | 57 / 90 | 0 / 0 |
| Photon | 3458 / 179 | 1972 / 197 | 0 / 0 |
| BSL | 1 / 9 | 270 / 144 | 0 / 0 |

原 mask 像素数：pose0=573050；pose1=557694；pose2=553414。

异常分布：

| 环境/pose | bad 包围范围（含端点） | 直接可见的分类 |
|---|---|---|
| ITRP/0 | x0..959,y578..596 | 970中960恰为y578全行；余10在地形内部 |
| Complementary/0 | x26..376,y580..597 | 全在地形内部 |
| Photon/0 | x0..959,y578..599 | 960在y578；其余在地形内部 |
| BSL/0 | x923,y599 | 地形内部 |
| ITRP/1 | x0..959,y555..590 | 沿斜地形唇，附少量地形内部差 |
| Complementary/1 | x26..932,y556..589 | 沿斜地形唇/地形 |
| Photon/1 | x0..959,y555..599 | 沿斜地形唇并深入地形 |
| BSL/1 | x1..941,y556..599 | 沿斜地形唇并深入地形 |

令 `signedDistance = (PNG_y+.5) - projectedTerrainEdgeY(PNG_x+.5)`，正数表示地形轮廓下方。8组异常的最小值为 **-0.41507312px**，即所有坏点都只在地形上或其不足半像素的 AA 跨界内。各组极小/极大值：

| 环境/pose | signedDistance min..max（px） |
|---|---:|
| ITRP/0 | -0.04413844 .. 17.95586156 |
| Complementary/0 | 1.95586156 .. 18.95586156 |
| Photon/0 | -0.04413844 .. 20.95586156 |
| BSL/0 | 20.95586156 |
| ITRP/1 | -0.41507312 .. 13.37385355 |
| Complementary/1 | -0.03808594 .. 7.20759984 |
| Photon/1 | -0.11348338 .. 18.83576561 |
| BSL/1 | 0.04761739 .. 18.10497951 |

**诊断性分区，不是替换验收判据**：仅把真实地形边界上方超过1px的区域单独统计，仍覆盖全部内部天空柱边：

| pose | 几何背景 mask ∩ 地形唇上方1px 的像素数 | 五环境逐像素前后最大差 |
|---|---:|---:|
| 0 | 551930 | **全部0** |
| 1 | 540905 | **全部0** |
| 2 | 553414 | **全部0** |

这比“把max233容忍过去”更强：天空内部本来就是逐像素0；异常被精确限制在真实地形轮廓/地形内。下一步应清场重跑原完整 mask，而非把本诊断分区偷换成通过结果。

## 6. 当前26.2源码时序核对

### 6.1 TransparentGuard 没有本次被猜测的“跨 deferred 快照”证据

[SkyIrisTransparentMixin](../src/main/java/dev/yg/skyboxblock/mixin/SkyIrisTransparentMixin.java#L14-L17) 在 `beginTranslucents RETURN` 取 before，`finalizeLevelRendering HEAD` 取 after。

本机 [Iris javap](../build/audit-r2/iris-bytecode.txt#L3071-L3106) 清楚显示：

```text
beginTranslucents:
  removePhaseIfNeeded
  isBeforeTranslucent=false
  copyPreTranslucentDepth
  deferredRenderer.renderAll
  RETURN                         <- before 快照
...
finalizeLevelRendering HEAD      <- after 快照
  isRenderingWorld=false
  removePhaseIfNeeded
  compositeRenderer.renderAll
  finalPassRenderer.renderFinalPass
```

所以当前 hook 已经把 deferred 放在 before 之前、composite 放在 after 之后。仅凭“before/after相差”去怪 deferred 时机并无依据；而本次960点两端 delta本来全0。

[transparencyBoundary](../src/main/java/dev/yg/skyboxblock/client/SkyWindowRenderer.java#L169-L186) 两端选同一 `BLOCK_ENTITY.writingToAfterTranslucent` FBO，读其 DRAW_BUFFER0，并恢复 foreign FBO 的 READ_BUFFER。当前 readback 没有记录两边实际 texture id/generation，所以将来若出现非零透明 delta，仍应记录采样 attachment 的身份/尺寸/阶段；但本批没有这个症状。

### 6.2 coverage/depth 的实际行为符合该轮数据

- [opaque/coverage](../src/main/java/dev/yg/skyboxblock/client/SkyWindowRenderer.java#L127-L145)：同一 `rasterProjection`；先真实反转深度 GEQUAL 写天空，随后借用世界深度 GL_EQUAL 写 RG32F provenance；仅清 coverage 颜色。
- [final draw uniforms](../src/main/java/dev/yg/skyboxblock/client/SkyWindowRenderer.java#L218-L238)：display几何使用无jitter投影；coverage/depth UV仍映射到 `uv*scale+jitter*.5`；两个纹理都 nearest。
- [Pass2](../src/main/resources/assets/skyboxblock/shaders/isolated/window.fsh#L41-L60)：先核对材质，再核对世界深度与 ownerDepth，最后透明保护。多余的“ownerDepth等于当前三角形深度”条件已经不存在。
- 本批 foreground 所有960点 material=7、depth==owner，透明delta=0；这些生产 guard 没有失败。background则被material guard正确挡住，因为那里确实是地形。
- 不能从这个读回反推 foreground 未生成片元；最终PNG在该行已是正确星空颜色，与该材质实际写入相符。

### 6.3 已排除项和保留项

- 不改逆VP双点全景射线；其已有oracle/原生/动态验证受尊重，且本次天空内区逐像素相等。
- 不改 GL30 indexed masks：与这批有明确地形深度的单行变化无关，既有真实3.3/4.5验证不被推翻。
- 不加更宽 tolerance：background是真实近地形，不是owner深度量化误差；加宽只可能扩大覆盖地形风险。
- 不禁用 TransparentGuard：本批delta全0，关闭对根因没有解释力，还会丢玻璃/雨/粒子保护。
- 当前未采集所有包的同帧 `.f32`，因此不把ITRP的透明delta=0泛化成所有包每个像素都为0；其它包的结论来自原PNG的几何空间分布与天空内区max0。

## 7. 对照真实1.21.1的五个组件

对照路径是 `../skybox-mod/src/main/...` 的真实实现，不是26.2项目内 `legacy` 副本。下面提取语义，不移植旧 API。

| 组件 | 真实旧版做法 | 对这次诊断的意义 |
|---|---|---|
| [SkyWorldRenderer](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/SkyWorldRenderer.java#L349-L399) | 实际Iris final后按view立即消费深度；display无jitter，depth UV应用raster scale/jitter；真正有TAA时启用ForegroundEdgeGuard/history | 旧版把真实前景重建边缘与天空内部折面分开处理；并非靠对比两张不同jitter截图判内部裂缝 |
| [SkyCoverage](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/SkyCoverage.java#L21-L51) | RG32F材质+深度，借世界depth，GL_EQUAL，不清/写借来的world depth | 当前26.2同样保留核心provenance；本次readback的0/7切换正表明世界归属真的不同 |
| [SkyTemporalEdgeMask](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/SkyTemporalEdgeMask.java#L24-L77) 与 [edge shader](../../skybox-mod/src/main/resources/assets/skyboxblock/shaders/core/sky_edge_history.fsh#L12-L32) | 3×3邻域，只有“更近非owned天空深度”才记真实foreground；按jitter周期保留；相机/投影/level/几何签名变更清历史 | 它处理前景TAA轮廓，不应当泛化为模糊天空、膨胀所有材质或压掉内部天空细节 |
| [SkySurfaceLayer](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/SkySurfaceLayer.java#L55-L115) | 私有4×MSAA近天空层、每sample最近材质、premultiplied覆盖率合成；Iris路径有适用条件 | 低分辨率ID是provenance，不等于显示分辨率的精确边界；当前新核心并未证明缺这一层而在内部裂 |
| [ItrpSkyBridge](../../skybox-mod/src/main/java/dev/yg/skyboxblock/client/ItrpSkyBridge.java#L84-L239) | 只对精确验证的pack SHA/Iris schema/owner生效；合法天空material/normal MRT写入，composite40前注入真实背景输入，含depth格式位数、texture alias、exposure校验 | 旧版对透明背景的照明/重建有专用语义桥，不是全局关透明保护。不要把旧colortex编号/反射字段/API直接搬到1.11.4 |

旧 [sky_resolve](../../skybox-mod/src/main/resources/assets/skyboxblock/shaders/core/sky_resolve.fsh#L41-L73) 接受另一同材质天空面的精确深度指纹来跨折面，并用history/邻域保护真正foreground；旧4×surfaceLayer也不会把低分辨率ID直接当所有显示边界。这支持“如果以后证实真实前景边缘需要稳定，应做明确的foreground ownership修复”，不支持“此时重建整套旧版渲染”。

## 8. 主线精准动作与可证伪判据

### 8.1 现在就应做的最小动作：仅修验收场景隔离

本轮读到 [run-live-matrix](../tools/run-live-matrix.py#L154-L158) 已含负z清场：z-16..-11、-10..-5、-4..1、2..7，y-63..0。这恰好去掉GPU定位出的 `y=-60,z≈-.006` 地形唇。它是主线已有改动，本轮没有写该文件。

执行仍由主线负责：

1. 用这份清场重跑三pose、五环境的完整原背景有限平面mask；不改变 `metrics.max<=2`，也不把over8当通过阈值。
2. 确认新PNG底部旧地形唇消失；不是只看请求success，实际GPU/最终图才是证据。
3. 预期：本报告解释的y578横线、pose1斜线和地形内部的pack差应消失。若仍留天空内部点，本报告地形归因只覆盖已取证的旧像素，不自动覆盖新点。
4. 原白/黑遮挡、玻璃、雨、粒子、真实前景深度writer/手部/不同材质owner保护全部继续保留；清场只用于“同材质内部连续性”这一独立用例。

### 8.2 若清场后仍差：对每张输入作双向、互斥分类

对背景和前景各自用准确token/P/V/raster/scale/jitter映射 `.f32`，不要借一帧jitter去读另一帧：

```text
texel = floor((displayUV*scale + jitter*.5)*textureSize)
PNG y向下；f32行0向上
ownStyle = abs(coverage.g-(material+1)) <= .1
sameDepth = abs(depth-coverage.r) <= 该片元真实生产tolerance
transparent = max(abs(after-before)) > max(.001, max(abs(before.rgb))*.0001)
```

分类顺序：

- 一端owner为空，且depth反投影落在更近真实物体：**真实foreground visibility/ownership边缘**，不是天空内部裂。
- 两端owned、worldDepth==ownerDepth、透明delta=0，却存在最终差：再看同帧逐像素CPU天空oracle；随后用frame末display写入的观测计数/片元mask区分“未生成display片元”与“之后被覆盖”。目前readback未提供final片元通过mask，别凭猜测二选一。
- owner相同但worldDepth与owner确实分离：真实后续depth writer，先标出物体/hand阶段；不要把差默认当浮点噪音。
- owner/depth都正确且透明delta非零：先观察before/after的texture identity、generation、语义与真正透明物体空间对应；若确为玻璃/雨/粒子，就是应保留的保护。
- 若要准确判断`slope`相关tolerance，需观测该片元生产导数或最终通过mask；`>1e-7`/`>1e-5`是诊断统计，不等于所有三角形真实容差。当前foreground差精确为0，所以不受此限制。

### 8.3 真实前景边缘若另立修复项

只有在“固定场景、已知真实foreground边缘、同jitter相位或完整jitter周期”的数据证明final resolve引入轮廓不稳后，才按旧版语义设计26.2本地修复：区分真实foreground与同材质天空折面，采用view-owned显示空间foreground边缘保护；历史必须对token/视图/相机/投影/几何变更正确失效。不要全局关闭透明guard，不要跨材质扩张，不要把世界RGB快照当可任意回填的天空。

这项在当前证据下属于**明确后续验证条件**，不是已经需要实施的生产补丁。当前最小、可验证路径仍是清掉已精确定位的近地形唇，原标准重跑。

## 9. 最终交付状态

- 本报告完成了旧8组异常、GPU960点、真实地形反投影、三pose轮廓投影、真实1.21.1五组件的证据对照。
- 已通过消息把GPU反证、960整行坐标、真实近地形世界坐标和全部天空内区max0分阶段发给主线。
- 没有源码修改或生产渲染阈值修改；清场新回归结果仍由主线生成与判定。
