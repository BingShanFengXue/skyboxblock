# R36 立体云细白点／细线：独立研究与复现验证设计

日期：2026-10-31。候选版本：`1.0.4-test.2-r36`。研究工作区：`E:\DSHworkspace\DSHminecraft`。

## 0. 交付边界与结论强度

- 本轮只读生产、共享测试、shared compile classpath、参考字节码和本地实际 named Minecraft jar。没有运行 Gradle、javac、游戏、用户实例、存档或光影配置；没有 commit/push。
- 唯一新增执行文件是 [独占 CPU 探针](../tools/check-r36-cloud-mesh.py)，另新增本报告。没有派生子代理，没有和兄弟代理互发消息；关键发现已直接报父代理。
- 可用 skill catalog 只有 Windows ACL 与 Office 工作流；本任务没有匹配项，也没有出现 ACL 问题，因此没有加载不相干 skill。已读取 [工作区规则](../../AGENTS.md) 和 [历史教训](../../docs/LESSONS.md)。
- 用户描述的 `181×150` 图像在本独立任务里只有尺寸和现象描述，没有可读取的像素文件；本报告不声称已从该图确定缺陷，也不声称完成动态 GPU 验收。
- **已证实**：实际原版 mesh 常量、面的位置关系、UV 规则、缓存 Y 分支、纹理二值 alpha、当前两遍状态。**待 GPU 证实**：细白点究竟是重复透明混合、UV 边界采样、还是实际覆盖缺失。
- **当前优先假设**是原版相交面在交线／近交线量化等深后，颜色遍多次 alpha blend；它比“1/256 是几何裂缝 epsilon”“先猜 FSR”更符合白色单像素，但仍只是优先假设。真正的露底应向 sky-only 背景靠拢，白点本身不等于露底。
- 原 R35 四环境 `228` 场景／`1484` 通过只覆盖原环境属性验收，不构成本缺陷的像素证据或修复结论。

## 1. 证据来源与可复现命令

### 1.1 当前实际 jar，而不是网上其他 MC 版本

当前明确选择的 jar 是：

```text
skybox-mod/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-f556725616/
1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/
minecraft-merged-f556725616-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar
SHA-256 d089f57cb10748f0743bd14a1b14b20826da059a75f0a910624b9961409c17ae
```

它由已有 [compile-classpath](../build/compile-classpath.txt) 所在 Loom 命名目录定位，不改该 CP。使用工作区 JDK 的 `javap.exe -classpath <以上jar> -p -c net.minecraft.client.renderer.LevelRenderer` 核对。探针同样直接调用 javap，在内存解释实际方法的 `1317` 条指令；不是把网上／印象中的 buildClouds 手抄成一个替代算法。解释器不支持的指令会立即报错，所有 `float` 运算按 binary32 舍入，构建器/VertexConsumer 仅由 CPU 顶点 sink 接收。

解释后的规范指令记录 SHA-256：`609285f2be2eda8ef7ef3525844c2e4d71540cef186d3a03091edbc74a3ee321`。这不是 class 文件的哈希，而是探针规范化 javap 指令元组的哈希。

只运行本研究探针：

```powershell
python tools/check-r36-cloud-mesh.py        # 需要带 Pillow 的解释器
```

脚本只向 stdout 输出 JSON，没有写截图、缓存、测试结果文件或共享类路径。依赖只有本地 Python 标准库与已有 Pillow。JDK 默认路径明确固定在工作区 `.jdks/jdk-21.0.12.1+1/bin/javap.exe`；允许用 `--javap` 和 `--jar` 指定另一个明确版本，不自行搜用户目录。

旧 `tmp-mcapi/LevelRenderer.c.txt` 的 javap 记录（工作区归档，不随仓库分发）只作旁证；本轮另跑当前 jar 才形成版本证据。旧记录常量／关键分支与当前 BC offset 一致。

### 1.2 实际原版构建规则（BC 为 buildClouds 内字节码偏移）

| 已证实项 | 当前 jar BC | 含义 |
|---|---:|---|
| `0.00390625F` | 5、27、39 及各 `setUv` 前 | `1/256` 纹理坐标比例，**不是几何 epsilon** |
| `9.765625E-4F` | 17 | 真正几何 epsilon：`1/1024` 云局部单位 |
| `u0=floor(x)/256`，`v0=floor(z)/256` | 22–43 | 输入分数不写入 mesh；由 modelView 单独平移 |
| `Y=floor(y/4)*4` | 153–167 | 整层基准，与缓存 `floor(y/4)` 匹配 |
| FANCY 网格 `i,j=-3..4`（含端点） | 179–213 | 8×8 大块，每块在 X/Z 为 8 个云纹理单元 |
| 底面存在条件 `Y>-5` | 215–221 | 判断的是已量化的 Y，不是连续输入 y |
| 顶面存在条件 `Y<=5` | 516–522 | 同上 |
| 顶面 `Y+4-epsilon` | 537、616、697、772 | 四个顶点全使用相同高度 |
| `-X` 面平面 `x=i*8+k`，`k=0..7` | 833–1180 | 沿法向的 U 固定在该单元 `+0.5` texel 中心 |
| `+X` 面平面 `x=i*8+k+1-epsilon` | 1183–1538 | BC 1209/1294/1381/1464 使用 epsilon；侧面高度仍 `Y..Y+4` |
| `-Z`／`+Z` 同理 | 1541–2246 | `+Z` BC 1927/2012/2097/2180 减 epsilon；V 固定中心 |
| 格式与图元 | 141–151 | `POSITION_TEX_COLOR_NORMAL`，QUADS |

对应旧记录：[常量与量化](../../tmp-mcapi/LevelRenderer.c.txt#L6186-L6306)、[顶面](../../tmp-mcapi/LevelRenderer.c.txt#L6437-L6602)、[正 X 面](../../tmp-mcapi/LevelRenderer.c.txt#L6774-L6950)、[正 Z 面](../../tmp-mcapi/LevelRenderer.c.txt#L7145-L7326)。

### 1.3 CPU 提取结果

输入 `x=.375,y=0,z=.625,cloudColor=(1,1,1)`，未应用扩距 Mixin：

| 项目 | 实测值 |
|---|---:|
| 顶点／quad／标准三角索引数 | `5632 / 1408 / 8448` |
| 顶／底 quad | 各 `64` |
| `±X`、`±Z` quad | 每个方向各 `320` |
| 顶面 Y | `3.9990234375` |
| 侧面 Y 端点 | `0`、`4` |
| 每个完整水平面的内部共享边 | `112` 条，端点精确相等，重数为 2 |
| 每个水平面外边界 | `32` 条，重数为 1（有限域边界，非缺陷） |
| 单元尺度 | 水平 `12` 世界单位，垂直厚度 `4` 世界单位 |
| epsilon 对应世界单位 | 水平 `.01171875`，垂直 `.0009765625` |

分数 x/z 不影响原版生成的任何顶点属性；跨 x 整数单元只使 U 精确增加 `1/256`，位置／颜色／法向不变（探针有断言）。CPU 浮点颜色是打包前输入；探针另列预期 UNORM8 字节，避免把 `.7F` 错称最终字节。原版的顶、X 侧、Z 侧、底 RGB 系数分别为 `1/.9/.8/.7`，顶点 alpha 为 `.8`（打包为 204）。

**拓扑解释**：顶面不是每一个实心 texel 各建一个闭合盒子，而是 8×8 大水平面加纹理裁剪，配合逐单元的侧面薄片。把整张 mesh 简单要求“每条边都恰好两次”会把有限域边界、透明纹理表示和内部薄片都误判。上表只能排除“所有大块水平面端点自己就对不上”的说法，不证明 GPU 没有接缝。

**重要几何区别**：`topY=4-eps`，而侧面上端仍为 4；正侧面又位于整数单元边界内侧 `1-eps`。因此顶面实际穿过侧面内部，形成微小相交／overhang，**不是从两个面各缩一截留下的一条简单开放缝**。在相交线上，两个面本来就是同一深度；删掉 epsilon 会改变反重合策略并增加精确重合，绝不是已证实修复。

## 2. 当前模组绘制链的事实与差异

以下是研究读取时的行号；主线后续编辑会漂移。对照 [R36 基线快照](../../artifacts/snapshots/skybox-r36-baseline-20261031/src/main/java/dev/yg/skyboxblock/client/SkyVanillaClouds.java) 可固定研究时点。

- [SkyVanillaClouds:180–223](../src/main/java/dev/yg/skyboxblock/client/SkyVanillaClouds.java#L180-L223)：漂移 `.03` 世界单位/tick；水平 `/12`；x/z 归一化至 2048 周期；缓存为 `floor(x),floor(y/4),floor(z)`；真实调用 `LevelRenderer.buildClouds`；只临时设 FANCY 并恢复。
- [SkyVanillaClouds:224–256](../src/main/java/dev/yg/skyboxblock/client/SkyVanillaClouds.java#L224-L256)：同一 modelView 为 `view.scale(12,1,12).translate(-fract(x),y-floor(y/4)*4,-fract(z))`；先清深度，colorMask=false 画预遍，再 blend 画颜色遍。颜色遍显式 `depthMask(false)`。
- [SkyGlState:111–129](../src/main/java/dev/yg/skyboxblock/client/SkyGlState.java#L111-L129)：基线深度函数 `GL_LEQUAL`；当前两个云 pass 的顶点变换和 fragment discard 完全相同。不能把“画过两遍”当作像素保证。
- [SkyCloudMesh:24–75](../src/main/java/dev/yg/skyboxblock/client/SkyCloudMesh.java#L24-L75)：原始顶点字节上传；QUADS→`0,1,2;2,3,0` 标准索引；UV 与 Color 使用真实 format offset，Color 是 normalized UNORM8。当前证据不支持先重写上传或屏幕几何。`Normal` 在当前 shader 没参与计算，编译器将它优化掉是正常现象。
- [SkyCloudRange](../src/main/java/dev/yg/skyboxblock/client/SkyCloudRange.java#L9-L33) 与 [CloudBuildRangeMixin](../src/main/java/dev/yg/skyboxblock/mixin/CloudBuildRangeMixin.java#L9-L17)：仅扩原版构建的两组范围常量、局部远裁剪，不改 UV 比例、局部面顶点、相机 jitter、世界 projection。范围扩展可以放大远处量化问题，但不是已证实局部裂缝来源。
- [云 vsh](../src/main/resources/assets/skyboxblock/shaders/core/vanilla_cloud.vsh#L15-L20)：原始 UV 直通，位置统一乘 ModelView 和 Proj；[云 fsh](../src/main/resources/assets/skyboxblock/shaders/core/vanilla_cloud.fsh#L13-L20)：采样纹理×Color，先 alpha `<.1` discard，再乘 CloudOpacity 与局部雾 alpha，最后 `<.001` discard。
- [VanillaSkyScene:42–56](../src/main/java/dev/yg/skyboxblock/client/VanillaSkyScene.java#L42-L56) 已在云调用上游 `clearSamplers()`，并拥有私有 TextureTarget；[GL 状态保存恢复](../src/main/java/dev/yg/skyboxblock/client/SkyGlState.java#L70-L109) 保存 sampler binding。因此“普通上游 sampler 泄漏”优先级降低，仍应在 `shader.apply()` 后量测有效状态，不能只看调用前的缓存。
- 当前 jar 的 `RenderType.createClouds` BC 22–31 明确 `TextureStateShard(CLOUDS_LOCATION,false,false)`；TextureStateShard setup BC 9–14 每次调用 `AbstractTexture.setFilter(false,false)`。当前自定义云只 bind texture，未显式重设过滤。因此必须量测实际 MIN/MAG/WRAP，而不是假定从未被资源／其他绘制改变。
- 原版 `createClouds(true)` 预遍为 `DEPTH_WRITE`；颜色遍 `createClouds(false)` BC 59 是 `COLOR_DEPTH_WRITE`。当前颜色遍不写深度是实际差异，但恢复写深度本身并不会剔除所有量化等深多片元。
- 当前 jar 的 `RenderTarget.createBuffers` BC 155–178 使用 **unsized** `GL_DEPTH_COMPONENT` + `GL_FLOAT` 数据类型分配深度纹理；没有指定 D24 或 D32F。实际存储位数、类型和 sample count 必须 GPU 查询，不能把 CPU 精度表的 D24 假定写成运行事实。

### 2.1 实际云纹理与 UV 边界

实际 jar 内 `clouds.png`：`256×256`，SHA-256 `18b72300292efa39f15f0f0e9b77f339b9c9237d054cf8141681ad25f047374f`。无对应 mcmeta。

- alpha 0：`52180` texel，RGB `(0,0,0)`；alpha 255：`13356` texel，RGB `(255,255,255)`。没有半透明原图 texel；模糊边来自过滤／覆盖／混合／后处理，而非原图的中间 alpha。
- 循环纹理中 X/Z 相邻占用变化边分别 `8904/9324` 条。顶／底 UV 在单元整数边界上；侧面法向 UV 使用单元中心 `+0.5`。因此 GPU 插值在二值 alpha transition 附近的最后一位，可让邻接面采到不同占用。
- binary32 的 `u=.5` 前一数为 `.4999999701976776`；nearest 对 256 宽纹理分别选择 texel 127 与 128。这只证明边界敏感性，**没有证明实际 GPU 已发生错误取样**。
- 当过滤为 linear，原图透明 texel RGB 为黑，通常会产生灰／暗的软边，与“异常更白”的重复混合特征不同；仍需对照，不用肉眼颜色单独排除。

## 3. 根因候选分级及决定性实验

| 候选 | 目前证据 | 区分实验与判定 |
|---|---|---|
| **H1：顶／侧／侧内部相交 + 深度量化等深，颜色遍重复 blend** | 两平面相交已证实；LEQUAL 颜色遍可接受多个等深片元。白云蓝底叠画通常更白，与描述相符；尚缺实际 fragment 计数／alpha 证据 | 同一冻结云帧在私有线性黑／白底双渲染，反演 effective alpha；配合同深度／同 discard 的 additive overdraw witness。若异常处从 `.8p` 升至 `1-(1-.8p)^2` 附近，且强制一次 blend 消失，则支持 H1 |
| **H2：nearest 二值 UV 边界采样差异／非 nearest 过滤** | 顶面 UV 和侧面法向中心采样不同；二值 alpha transition 多。正常 sampler 清除已有保障，但 texture 参数没有云局部重设 | 真实纹理与全白 opaque 诊断纹理 A/B；记录 fragment 的单元 ID／normal ID 及 MIN/MAG/WRAP。缺陷仅真实纹理存在且落在占用变化边，才支持采样因果；不要把自然纹理孔当缺陷 |
| **H3：光栅化覆盖裂缝／T-junction／不同 clip 顶点舍入** | 水平块共享边精确；面交会不总是共享同一顶点，不能援引共享边覆盖保证。大顶面和细分侧片存在非匹配交线 | 全白 opaque 诊断 mask 仍有预期实心区域漏采样，且 overdraw=0／alpha 无异常；高精度射线或原版 mesh CPU coverage 对照确认应覆盖，才归为真实覆盖裂缝 |
| **H4：缓存重建或近层面集切换** | 原版 build 与缓存的 y floor 逻辑匹配；已确认真实切换点。它可能产生瞬时 pop，但不解释任意静止高度下长期随机白点 | 记录 cell key、buildGeneration、mesh hash；在跨 cell 与不跨 cell 的运动帧分别对照；若异常总与 generation 同帧相关，再追重建数据和失效条件 |
| **H5：MSAA／sample mask／Iris/FSR 后处理** | 未测实际 sample buffers。当前私有目标用普通 TextureTarget，不应预设等于宿主 MSAA；用户也未指定 shader | 优先看 private clouds raw 图，再看 display/final；raw 已坏不归咎最终 FSR。四环境分别验，量测样本数、coverage、jitter/history，只有后续阶段新出现才能定后处理链 |

### 3.1 为什么 GL_EQUAL、去掉 epsilon 或提高精度不是现成结论

- shared-edge／top-left 规则解决的是匹配边界的覆盖分配，不替两个在内部相交的面选择“唯一透明层”。同一深度两个片元都满足 EQUAL，所以仅把颜色遍 LEQUAL 改成 EQUAL 仍可能多 blend。
- 颜色遍改 LESS 会挡住预遍已写入的表面，不是正确去重。恢复颜色 depth write 或增加 epsilon，也不能保证所有距离／角度下只混一次。
- 例示投影 `near=.05,far=2048,D24`（**不是运行时格式**），垂直 epsilon 在 eye-depth 对齐距离 64/128/512 时只有约 `.200/.050/.00313` 个深度 LSB；水平 epsilon 约 `2.40/.600/.0375` LSB。即使顶点坐标足够准确，远处微小分离仍会量化等深；交线本身的分离量更是 0。
- 顶点位置在相机相对、云局部的有限网格，原始 UV 是 dyadic 倍数；没有证据支持先把全云改 double、重写 R35 overlay、改整套屏幕投影或替换世界相机矩阵。

## 4. 最小、可回滚修复建议（由主线实现）

**先保存能重复出现的原始坏帧和负对照，再选一个已被实验支持的分支；本研究没有生产修改。**

### A. 若 effective sampler/texture 参数偏离原版

只在私有云 draw 范围保证 `nearest/no mip/repeat`，并恢复原状态。支持 sampler object 时，可用项目独占 sampler 绑定 unit 0，由已有 scope 恢复原 binding；不支持时，逐项保存／恢复该纹理真实参数。不要把全局云纹理对象永久改过滤作为“隔离”方案，也不要动最终屏幕天空采样或全局 shaderpack。对照需证明修复前有效状态异常，修复后像素缺陷消失且其他阶段未变。

### B. 若证实 H1 等深多 blend

优先做一个**仅私有云颜色遍、默认可撤销**的 one-blend witness：

1. 私有 FBO 用匹配尺寸／样本数的 stencil 附件，确认 complete；不能在不带 stencil 的 target 上调用几条 stencil 状态就宣称生效。
2. 保留现有 mesh、UV、矩阵、alpha discard 和 depth prepass。
3. 颜色遍 stencil 初值 0，只有 stencil bit 0 为 0 且深度通过的首个非 discard 片元混合一次，然后把该位变为 1；后续等深片元跳过。
4. 一个正确的消费模式是 `stencilMask(1); stencilFunc(EQUAL,0,1); stencilOp(KEEP,KEEP,INCR)`。这里直接用 `REPLACE` 会写 ref=0，消耗失败。每次云颜色遍前单独清 stencil，不能沿用前一帧的标记。
5. 保存／恢复 front/back stencil func、ref、value mask、write mask、三种 op、clear value、enable 及附件生命周期；现有 SkyGlState 只保存 stencil enable，单独照抄它并不够。
6. 首个等深面决定一次颜色，可能仍有面方向色阶选择差异；必须同时验证“亮点减少”与正常顶／侧颜色，而不只看 alpha。若 tie 面选择仍不稳定，再评估 winner-ID witness，不马上扩大改动。

这是定位兼 proof-patch，不是当前已验收方案。通过后由主线决定最小生产落点；保留旧路径 regression-only 负对照，能一键退回。局部 stencil 比改整个云生成／缩放／overlay 更符合限制，但附加附件兼容性必须实测。

### C. 若证实真实 coverage／UV 缺陷而不是 H1

只修云私有 mesh 或云 shader 中被证实的具体边界。几何 endpoint canonicalization 必须同时检查顶／侧相交与 overdraw；大顶面细分到单元属于更高影响方案，不是先手。`texelFetch(floor(uv*size))` 与 nearest 在整数边界仍有同类选择问题，不能因为函数换名就宣称修复。随机 UV nudge、通用 epsilon 变大、把所有边界侵蚀掉，也不构成根因修复。

## 5. 动态复现矩阵：跨什么边界、记录什么

### 5.1 坐标与真实高度分支

[cloudRelativeY](../src/main/java/dev/yg/skyboxblock/client/SkyRenderMath.java#L13-L18) 实际是 `height-(float)cameraY+.33F`，经 float 运算后才提升为 double。测试要记录算出的 y 与 floor key，不能只看命令里的十进制相机坐标。

| 连续输入 y | 量化 Y | bottom | top | 标准顶点数 |
|---|---:|---|---|---:|
| `-5` 或略低于 `-4` | `-8` | 无 | 有 | 5376 |
| `-4` | `-4` | 有 | 有 | 5632 |
| `0` | `0` | 有 | 有 | 5632 |
| `5` 或略高于 `5` | `4` | 有 | 有 | 5632 |
| 略低于 `8` | `4` | 有 | 有 | 5632 |
| `8` | `8` | 有 | 无 | 5376 |

**实际面集切换是输入 y=-4 和 y=8，不是 ±5。** 相机对应约 `H+4.33` 与 `H-7.67`，但须以实际 float y 为准。另跨所有 `y=4k`，即使面集不变也触发 mesh 重建和 modelView fractional translation 回绕。

### 5.2 必须覆盖的运动与阶段

| 维度 | 必测路径／样本 | 目的 |
|---|---|---|
| 纯云漂移，相机固定 | 连续至少 3 个云单元；每单元 400 tick（20 秒），约 60 秒；每实际渲染帧度量 ROI，保留失败前后帧 | 既涵盖 subpixel 相位，也跨至少三次 floor(x) 重建；不能 freezeClouds 贯穿验收 |
| 冻云，相机平移 | X、Z、XZ 对角正反向；小步与连续走动两种；记录真实相机 delta 非零 | 把云时间与相机视差独立开；Z 运动不能由仅 X 时间漂移代替 |
| 旋转／视角摇晃 | 各象限 yaw，缓慢 pitch 穿过顶／侧交会；平移+旋转+bob；近水平掠射角 | 不同 normal、面交线、投影 subpixel 与完整矩阵位置都要覆盖 |
| X/Z 重建边界 | `x,z=k±δ`、精确 k，k 至少含 `-1,0,1,7,8,2047,2048`；归一化前后都记 | 负 floor、8 单元 mesh 大块关系、2048 wrap、整数 UV 迁移；Z 要包含 `.33000001311302185` 偏置 |
| Y 重建与面集切换 | `y=4k±δ`，重点 `-8,-4,0,4,8`，并单独测 `±5±δ` 证明不是那里切换 | 排除缓存失效误归因；δ 取可分辨 float ULP，再用 `.001/.01/.1` 扩大采样 |
| 云高与距离 | H=64/192/384，加高 H 但相机随 H 保持同一相对 y；相对云层内/近顶/近底、y 的正负方向分别取 32/64/128/256；扩距再测 512–4096 视距范围 | 把绝对云高、相对面位置与远处深度量化拆开。远距需落在已启用 fadeEnd/投影范围，不能用全雾区掩盖缺陷 |
| alpha／雾 | p=`1/.5/.25`，p=0 负对照；清晰区、雾起点附近、雾末端附近 | 双 blend 分别预期 `.96/.64/.36` effective alpha；远雾／透明自然变化单独建模 |
| 环境 | native；Iris 无 pack；Iris+选定 pack 且 FSR off；同 pack FSR on（若原包支持） | 不改用户 pack，主线在隔离实例执行；每个环境各有同路径坏版／修复版对照 |
| 分辨率／jitter | 原生 viewport + 另一正常分辨率；保留 1:1 的 181×150 ROI 而非缩放当证据；FSR 每个关键点覆盖完整 jitter cycle 和 history warm-up | 奇偶 viewport、缩放、history 阶段可能改变样本位置。记录实际 size/jitter，不假定固定 cycle 长度 |

无需四环境做无意义全笛卡尔积，但先在 native 找到稳定坏路径，再把同一坏路径迁移四环境，并至少分别覆盖 X/Z/Y/rotate/near/far/alpha 关键边界。报告实际轨迹、帧数、坏帧数，而不是只报场景标签数量。

### 5.3 每帧／每坏帧必要元数据

- 原始／归一化 x/y/z、fract、cell key、mesh buildGeneration、mesh vertex/index counts/hash、cloudColor、CloudOpacity、fadeStart/End。
- camera position/yaw/pitch、实际 partialTick/gameTime、完整 ModelView/Proj（包括 bob/jitter）、projection near/far 或原矩阵、相机运动 delta。
- drawFramebuffer、viewport、color/depth attachment ID、实际 depth component bits/type/internal format、samples、sample buffers、GL_SUBPIXEL_BITS。
- `shader.apply()` 后 sampler unit/object/texture ID、纹理内容 hash、MIN/MAG、WRAP_S/T、BASE/MAX_LEVEL、有效 sampler 过滤；program、VAO/EBO、格式／属性。
- depth func/mask/range，cull，blend func/equation，color mask，GL_MULTISAMPLE、SAMPLE_ALPHA_TO_COVERAGE、SAMPLE_COVERAGE、SAMPLE_MASK、FRAMEBUFFER_SRGB。
- raw base、private clouds、display clouds、最终屏幕同帧阶段图及 pixel metrics；错误帧前后至少若干帧保留。阶段图要有 frame ID 对齐，避免把不同时刻天空相减。

## 6. 独立像素 oracle：不把自然孔洞或叠画当成裂缝

### 6.1 输入与独立参考

1. 原版纹理实际像素和 texture hash，不硬编码“白云应该填满某个屏幕矩形”。CPU 用实际二值占用生成理想 voxel union／高精度 ray-box 参考；另保留**实际提取原版薄片 mesh**的高精度 coverage 参考。两者分开：理想闭合云和原版 epsilon 薄片并不完全相同。
2. 使用捕获帧的真实矩阵、viewport、cloud drift 与 cache key 反投影射线；至少 8×/16× 子样本积分作为边沿 coverage 范围。不能直接用上一帧同一像素当本帧 truth，移动时必须按几何重投影。
3. oracle 与修复实现不能共用“修复后生成的 mask”作唯一参考。提供已知 1 px 亮点、1 px 露底、自然纹理孔、正常 silhouette、双 blend 的独立合成负样本；坏版真实路径应 fail，修复版才有通过意义。

### 6.2 分类而非单一亮度阈值

| 类别 | 规则 |
|---|---|
| 真正露底 coverage 裂缝 | oracle 射线应命中实心可见云、预期采样 coverage 足够高，但实际颜色回到同帧 sky-only 背景／实际 coverage=0；单独记录孔的连通长度、宽度、位置和帧寿命 |
| 纹理孔洞 | ray/纹理占用本来为空，或被前景真实透明格穿透；不计裂缝。孔边界要按本帧投影，而非从图像上简单 fill holes |
| 正常云外边沿 | 子样本参考预期部分覆盖，可自然跳过 0/1；单独统计 alias／轮廓误差，不把整条边一律忽略，也不把每次轮廓变化都报错 |
| UV 交界孔 | 预期实心 voxel，实际 opaque 诊断 mesh 无孔、真实纹理才有孔，normal/单元 ID 显示邻面取到不同占用；归采样，不误称网格顶点没焊上 |
| 透明重复混合亮线 | coverage 存在，但 effective alpha 或该**具体可见面**颜色偏离单次混合；generic 全云最亮值包络会漏掉侧面双 blend，需 normal-ID／独立几何面判断 |
| 雾／透明混合正常变化 | 预期 alpha `.8*CloudOpacity*(1-fog)` 随距离变；接近 discard 阈值的区域单列，不以缺少白色像素为失败 |
| 后处理／重投影拖影 | private clouds raw 正确、display 或 final 新增白点／拖线；与 jitter/history 阶段、运动矢量相联系，不能倒推原 mesh 出错 |

### 6.3 黑／白底双 capture 与 overdraw witness

在两个**新鲜、独立、相同格式**私有诊断 target 中，冻结同一 frame 的 mesh、云色、雾、矩阵，只替换已知背景 B。在线性颜色域、背景黑/白时：

```text
C_black = P
C_white = P + (1 - a_effective)
a_effective = 1 - (C_white - C_black)
```

三个通道的反演结果应一致，超出量化／色域容差的像素标为无效样本；先查询 sRGB 状态，做正确 transfer conversion。已有天空 target 的背景 alpha=1，当前 blend 会让最终 alpha 继续等于1，**直接读旧 target alpha 不足以检测 overdraw**。

无雾且原图实心 texel，p=1/.5/.25 时单次 alpha=`.8/.4/.2`，两次为 `.96/.64/.36`。不同法向 RGB 叠画仍满足 effective alpha 的重复公式。为防多个低 alpha 层恰好混成相似颜色，再加一个同 depth attachment、同变换、同 discard、独立 additive count target 的 replay；严格保持 depth 不改，计数 surviving color fragments，而不只是“有一片通过”。该 witness 必须是隔离回归工具，不能把 debug blend 状态留进生产。

### 6.4 阈值、负对照与验收口径

- 最低 pixel 阈值从同一坏/好 target 的量化及重复捕获误差标定，不随意设一个会吞掉 1px 缺陷的 2–3px erosion。稳定内域与面交会 ROI 都测；交会带恰好是本缺陷位置，不应整体屏蔽。
- 对轮廓／孔洞边界，用子样本 expected coverage 与不确定带分类；对边界外仍应充分覆盖的像素坚持报错。保留 full-res fail crop 和元数据可重放。
- 合成的 1px 缺口与 1px double-blend 点都必须 fail；自然孔、正常轮廓、p=0、雾全退隐必须通过相应的“预期空”类别。
- 真坏版应在同路径至少重复复现，修复版在相同 frame path 无异常，回滚坏版再次失败。给出异常像素数、最长连通线、最亮／alpha 偏差、frame recurrence 和边界关联，不只报全图 PSNR 或平均差。
- 独立 CPU 提取检查 PASS、状态完整、glError=0、“drawn=true”或任意单张截图，都**不替代以上动态像素验收**。

## 7. 官方规范核查范围

只为通用 GL 语义核查官方原始规范，不用外部旧 MC 片段覆盖当前本地 jar。参考 OpenGL 3.3 core spec 的 polygon rasterization／fragment depth 与 blending 章节，以及 Khronos 官方 glBindSampler 参考：

```text
https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf
https://raw.githubusercontent.com/KhronosGroup/OpenGL-Refpages/main/gl4/glBindSampler.xml
https://raw.githubusercontent.com/KhronosGroup/OpenGL-Refpages/main/gl4/glDepthFunc.xml
```

这些资料支持“匹配边的栅格一致性不等于相交透明面唯一归属”“depth test 决定通过与否，不替透明混合去重”“绑定 sampler object 的参数优先于 texture sampling 参数”。具体 MC 常量／mesh／纹理／target 分配事实均来自本地 jar 和已列源码，不来自规范猜测。

## 8. 研究完成状态与交接

- 已完成只读源码／当前 jar 核对、独占 CPU 探针运行和研究设计；本报告不包含生产修复、游戏动态通过或发布批准。
- 探针完整执行两次；第二次在补充分数 cell 不变／整数 X 仅变 UV 断言、UNORM8 输出并去除 Pillow 弃用警告后，命令退出成功且 stderr 为空，末尾为 `PASS exact mesh counts/attributes, shared horizontal edges, epsilon planes, fail-closed VM`。最终工具 SHA-256：`ef28ec3ee12fd8d760b6cbabd3f8397da13bb671cdd15a270001f057ec267bd8`。
- 主线下一步：先在 native 同帧 private clouds raw 捕捉重复亮点；用 alpha／overdraw witness 将 H1 与 H2/H3 分开；在相同坏路径做一个局部、可回滚候选，再迁移四环境动态验证。
- 待命复核主线实际 diff、坏/好/回滚三方证据和动态 oracle 输出。主线统一构建与隔离游戏，本研究保持既定只读范围。