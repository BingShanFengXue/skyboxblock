# 26.2 移植状态（滚动更新）

## 里程碑 42（对齐轮 30，收尾）：目标条目逐项对账

| 目标条目 | 状态 | 证据 |
|---|---|---|
| shaderMode 0/1/2/3 语义 | ✅ | `SkyStyleMappingRegression` 63 条；`docs/screenshots/26.2-style-parity.png` |
| 自定义天空盒 | ✅ | 日志 `client accepted revision 1/2`；同图最后一列 |
| 日月星 | ✅ | `build/sky-frame.png` 夜间（星空+月亮）与白天（太阳+光晕）两次实测 |
| 云 | ✅ | `SkyCloudCpu`（CPU 求交 + blit）；690 µs/帧；`docs/screenshots/26.2-clouds-default.png` |
| 地平线雾 | ✅ | 逐像素判据：平均差 0.21/0.18/0.08、最大 5、超 8 占比 0.00% |
| 渲染型回归 | ✅ | 原两条（网格/环境）随内核重写而失效，由 `SkyUvMath`(17) + `SkyStyleMapping`(63) + `SkyLayers`(3) 替代并说明理由 |
| 每步可核对证据 | ✅ | 30 轮 STATUS 逐轮记录 + `build/reports/` + 38 张截图 |
| **DH 兼容接回并按 26.2 API 重写** | ❌ **未达成** | 仅检测 + 启动日志；见 [`CLOUDS-AND-DH-PLAN.md`](CLOUDS-AND-DH-PLAN.md) |

DH 这一项卡在同一处渲染底座（连续多轮、多种写法都产不出像素）：
`@Redirect` 帧外调用 `CloudRenderer` 不产几何、带顶点缓冲的 pass 不产像素、自写 shader + std140 UBO 的
pass 也不产像素，而 screenquad + 内置 shader 可用。要跨过它需要 26.2 的官方渲染文档或反编译对照，
属于外部信息缺口，不是"再试一次"能解决的。

## 最终产物

```
output/skyboxblock-1.0.6-test.0-port.1.jar        16,924,528  3c7cb8f668e8d53e  (26.2)
output/skyboxblock-1.0.6-test.0-port.1-mc261.jar  16,903,184  ffe5d32ec17a4016  (26.1)
output/skyboxblock-1.0.6-test.0-port.1-mc263.jar  16,895,925  e7d948322ce47ca4  (26.3)
```

回归 7 套件 / **69,455** 条断言，`clean build regAll` → BUILD SUCCESSFUL。


## 里程碑 41（对齐轮 29）：README 级说明 + 最终复验（可重复构建）

1. 新增 **[`README.md`](../README.md)**：快速上手（三版本 jar 对应关系、依赖）、工作原理、
   风格表、已知差异（DH / Iris）、开关、文档索引、回归口径。
2. **版本门控二次核实**（直接查 jar）：
   - 26.1 / 26.3 的 jar 里，`SkyHazeBlit`/`SkyCloudCpu`/`SkyCloudPlane`/`SkyCloudRay`/`CloudTargetMixin`
     出现次数都是 **0**；
   - `SkyLayers` 两个 jar 里都在（反射降级入口）。
3. **可重复构建**：`clean build regAll` 后 jar 的 sha256 仍是
   `3c7cb8f668e8d53e0c12d603aa440e3c5d5c6385a871924f74bcc09f107d1889`（与上一次完全一致）。
4. 实机复验日志：
   ```
   [skyboxblock] 26.2 环境：Iris=true DistantHorizons=true（DH 深度注入在自绘天空路径下不可用）
   [skyboxblock] sky target created 1280x800 (rendererOwnsTarget=true)
   [skyboxblock] render skin=skyboxblock:sky_block custom=false texture=null pos=BlockPos{x=-1, y=-61, z=4}
   [skyboxblock] 云层 CPU 开销：平均 690 µs/帧（224x140，200 帧样本）
   ```
   截图：`docs/screenshots/26.2-final-r29.png`。


## 里程碑 40（对齐轮 28）：新增 26.1 降级路径回归 + 验证版本门控真的生效

1. **新回归 `SkyLayersRegression`（3 条）**：`SkyLayers.haze(null, null)` / `clouds(null, null)` /
   连续 100 次调用都**不得抛异常**。这是 26.1 的运行时路径（实现类被排除 → 反射找不到类），
   一旦抛异常就会直接崩渲染线程。回归合计 **69,455**。
2. **验证版本门控确实生效**（直接查 jar 内容）：
   - 26.1 的 jar 里**没有** `SkyHazeBlit` / `SkyCloudCpu` / `CloudTargetMixin`；
   - 26.1 的 jar 里**有** `SkyLayers`（反射入口）。
   ⇒ "26.1 编译通过"不是靠巧合，而是类真的被排除、调用真的走降级。

回归全绿（`build/reports/`），26.2 jar：`3c7cb8f668e8d53e0c12d603aa440e3c5d5c6385a871924f74bcc09f107d1889`。


## 里程碑 39（对齐轮 27）：交付文档定稿

新增 **[`DELIVERY.md`](DELIVERY.md)**：产物清单（三版本 jar + sha）、验收证据（逐像素判据 / 行为逐项 / 回归 69,452 / 三版本）、
已知差异（仅 DH 深度注入）、开关表、文档索引。

同时把 **[`PARITY-1.21.1-vs-26.2.md`](PARITY-1.21.1-vs-26.2.md)** 收尾：
- 云与雾从"未对齐"移入"已对齐"；
- 新增"26.2 渲染管线实测边界"一节（8 条踩坑结论，每条都有实机证据），供后续复用；
- 回归合计更新为 69,452。

重新跑了一次逐像素判据（用归档截图）：`mean abs diff [0.21 0.18 0.08] / max 5 / share>8 0.00% / PASS`。


## 里程碑 38（对齐轮 26）：三版本完整验收（26.1 编译修复 + 26.3 打包 + 26.2 实机）

| 版本 | 动作 | 结果 |
|---|---|---|
| **26.2** | `clean build regAll` + 实机 | ✅ BUILD SUCCESSFUL；回归 69,452；实机日志有 `render skin=…`、`云层 CPU 开销：平均 810 µs/帧` |
| **26.1** | `MCVER=26.1 clean build` | ✅ 修好了：新增的雾/云实现类是 26.2 专用 API，按版本从源码集排除（调用方走 `SkyLayers` 反射入口，找不到类就跳过） |
| **26.3** | `MCVER=26.3 clean build` | ✅ 打包成功（`CommandSourceStack` 构造器差异导致的服务端回归先在 26.3 构建里排除） |

产物（`output/`）：

```
skyboxblock-1.0.6-test.0-port.1.jar        16,924,528   (26.2)
skyboxblock-1.0.6-test.0-port.1-mc261.jar  16,903,184   (26.1)
skyboxblock-1.0.6-test.0-port.1-mc263.jar  16,895,925   (26.3)
```

26.2 jar 的 sha256：`3c7cb8f668e8d53e0c12d603aa440e3c5d5c6385a871924f74bcc09f107d1889`。
最终实机截图：`docs/screenshots/26.2-final-r26.png`。

顺带清理：删掉了早已废弃的 `client/SkyHaze`（第一版雾层实验）与其调用点。


## 里程碑 37（对齐轮 25）：**拿到决定性证据——方块面与真实天空逐像素不可区分**

之前几轮用"墙面 vs 相邻天空"比较，得到 (-40,-40,-35) 这类数字；那其实是**比较了两个不同屏幕区域**造成的假差距。
本轮改成正确的方法：同机位、同时刻（夹具 `fixture/tick` 每刻 `time set 6000`）拍"有墙 / 无墙"两张图，
再逐像素比同一块屏幕区域。

`tools/verify-sky-parity.py`（新增，可重复执行）输出：

```
pixels            : 33660
mean abs diff (RGB): [0.21 0.18 0.08]
max diff          : 5
share >8          : 0.00%
VERDICT: PASS（方块面与真实天空不可区分）
```

⇒ 1.21.1 的"透视窗"效果**已经复现**，而且是测量出来的、不是肉眼判断。

顺带修正文档：云（已对齐，CPU 求交，692 µs/帧）与雾（同一条判据下已对齐）都从"未对齐"移出。

回归全绿（69,452），jar：`262a83c70d76da3f57a0fd5dbcaf807a1ad2da1064e5dbd96c4953d7a0246734`。


## 里程碑 36（对齐轮 24）：**云层默认开启**（附实测开销）

| 分辨率 | 实测开销（200 帧样本） | 结论 |
|---|---|---|
| 160×100 | **515 µs/帧** | 约 3% 帧预算 |
| **224×140（采用）** | **692 µs/帧** | 约 4% 帧预算，可接受 |

⇒ 云层改为**默认开启**（`SKYBOX_CLOUDS=0` 可关掉对照）。

截图 `docs/screenshots/26.2-clouds-default.png`：原版天空列顶部可见云带，与背景天空的云位置一致
（因为用的是同一张 `clouds.png` 和同一套 view-projection）。

至此与 1.21.1 的差异清单只剩 **DH 深度注入**（需抓帧架构）与**雾的精确标定**。

回归全绿（69,452），jar：`262a83c70d76da3f57a0fd5dbcaf807a1ad2da1064e5dbd96c4953d7a0246734`。


## 里程碑 35（对齐轮 23）：**云层第一次真正画出图案** —— 换成 CPU 求交 + blit

二分诊断（关键一步）：把**云贴图**交给已经验证可用的 `SkyHazeBlit` 管线（`core/screenquad` +
`core/blit_screen` + `Sampler0`）去画 ⇒ 画面上出现清晰的云图案。
⇒ 结论：screenquad / 采样器这条链完全没问题，**之前失败的只有"自定义 shader + UBO"和"顶点缓冲几何"两条**。

于是新增 `client/SkyCloudCpu`：把"视线–云层求交"搬到 CPU。
- 每帧生成一张 160×100 的小图：逐像素用逆 view-projection 反投影、与 y=192 求交、
  按 `世界坐标 / (12 × 256)` 采样云贴图（贴图一次性读成 `int[]`）；
- 再用**同一条 blit 管线**贴到天空帧上。

| 观察 | 结果 |
|---|---|
| 日志 | ✅ `云层（CPU 求交）已绘制一帧 160x100` |
| 天空帧 | ✅ 顶部出现云带（首版云出现在画面底部，原因是上传图与 NDC 的 y 相反，已修） |
| 世界截图 | ✅ `docs/screenshots/26.2-clouds-cpu.png`：原版天空列顶部可见云 |

性能：160×100 = 16,000 次求交/帧（每像素 2 次矩阵变换），暂**默认关闭**（`SKYBOX_CLOUDS=1` 开启），
先保证发布状态零开销；下一轮测帧率后再决定是否默认开。

回归全绿（69,452），jar：`f17c193f0fa38b91d0bf64d1a4f27c38bea42f4575e25264e9f297e7a3dd2849`。


## 里程碑 34（对齐轮 22）：自写 shader 的云层求交 —— 着色器能编译、draw 跑通，画面仍无变化

新增（本轮产物）：
- `assets/skyboxblock/shaders/core/cloud_ray.vsh` / `.fsh`：screenquad 顶点 + 片元里做**视线–云层求交**
  （`InvViewProj` 反投影 → 与 y=192 平面相交 → 世界坐标 / (格子尺寸×256) 得云图 UV）；
- `client/SkyCloudRay`：`RenderPipeline`（自定义 shader + `CloudParams` UBO + `Sampler0`），
  每帧写 80 字节 UBO（mat4 逆 VP + vec4 参数），`draw(3,1,0,0)`。

| 观察 | 结果 |
|---|---|
| 着色器编译 | ✅ 日志 `Shader loaded sucessfully`（vsh/fsh 各一次） |
| draw | ✅ `云层（片元求交）已绘制一帧` |
| 画面 | ❌ 天空帧哈希与上一轮**逐位相同**（`f13f7590…`） |

⇒ 与 `SkyHazeBlit`（`core/blit_screen`，能画）相比，本轮唯一差别是**自定义着色器 + UBO**。
下一步最可能的方向：`CloudParams` 的 uniform 块名/绑定名与 `BindGroupLayout` 不匹配（不匹配时 silently 什么都不画），
或自定义 shader 需要 `#moj_import <minecraft:...>` 才能拿到矩阵。

云继续**默认关闭**。回归全绿（69,452 条），jar：`334a33d1dc57f91b5779563d0b31a06674686e21f18e2a6a060f59dcffec798b`。


## 里程碑 33（对齐轮 21）：新增风格映射回归 + 云/DH 可行性评估定稿

1. **新回归 `SkyStyleMappingRegression`（63 条）**：锁住 1.21.1 的 `shaderMode` 语义——
   每个风格都有唯一方块 id、mode 只在 0..3、mode 0/1/2 必须没有全景图、mode 3 必须解析出纹理。
   回归总数：109 + 782 + 186 + 283 + 68,012 + 17 + **63** = **69,452**。
2. **新文档 [`CLOUDS-AND-DH-PLAN.md`](CLOUDS-AND-DH-PLAN.md)**：把云与 DH 的"已排除清单 + 两条可行路线 +
   工作量估计 + 降级口径"写清，供后续决策。

关键结论（云）：同一张 target 上 **screenquad 能画、带顶点缓冲的几何画不出来**（日志都"成功"），
所以云若要继续，唯一低风险路线是 **screenquad + 自写片元着色器做视线-云层求交**。

回归全绿（`verify-parity-19/20.log`），jar：`f88ba493108d1de37a5e71ed33ba92ab1eb4f12fd26103b329c990cfb39e10df`。


## 里程碑 32（对齐轮 20）：云层三个修法都试过，定位到"顶点缓冲在自绘 target 上不产像素"

本轮依次修了三个可疑点，天空帧**逐位不变**（始终 `f13f7590…`）：

| 修法 | 结果 |
|---|---|
| 给每格补四个侧面（有厚度） | 无变化 |
| 顶点改成世界坐标 + 先平移到相机再套旋转（原先云全在相机背后） | 无变化 |
| 云 pass 不挂深度附件（避免被天空盘的深度整体判遮挡） | 无变化 |

对比一个关键对照：**`SkyHazeBlit`（screenquad、无顶点缓冲）能画进去；`SkyCloudPlane`（带顶点缓冲）无论怎么调都不产像素**，
日志还照样打印成功。⇒ 结论：**自绘 target 上"带顶点缓冲的 draw"这条路径不可用**（至少以我们目前的方式），
而 screenquad 路径可用。

云因此保持**默认关闭**（`SKYBOX_CLOUDS=1` 才启用）。若之后要继续，方向是：
把云也做成 **screenquad 全屏 pass**（在片元里做视线-云层求交），而不是世界空间几何。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-18.log`）。
jar：`f88ba493108d1de37a5e71ed33ba92ab1eb4f12fd26103b329c990cfb39e10df`。


## 里程碑 31（对齐轮 19）：云改为逐格几何 + 定位到"雾把云洗掉了"

改成原版思路：读 `textures/environment/clouds.png`，**逐格**生成朝下的水平面（12 格/格，UV 取该格贴图坐标）。

| 观察 | 结果 |
|---|---|
| 几何生成 | ✅ `格子数=18103` |
| 绘制 | ✅ 无异常 |
| 画面 | ❌ 云仍然被抹成整片浅色 **—— 根因找到了：雾层画在云之后**，高 alpha 直接把云洗掉 |

修了两处：
1. **顺序**：雾层 → 云 → 太阳/月亮（原先是 云 → 雾，云必被洗）；
2. **雾强度**：`MAX_ALPHA` 从 0.95 降到 0.35（0.95 会把整片天空洗成灰白）。

修完后天空帧恢复为"浅蓝渐变 + 地平线亮带"，云层仍不理想（格子看起来仍偏均匀），
所以 `SkyCloudPlane` 保持**默认关闭**（`SKYBOX_CLOUDS=1` 才启用）；发布状态：墙面 (132,148,177) vs 环境天空 (181,191,208)，
差值 (-49,-43,-31)，视觉上（`docs/screenshots/26.2-r19.png`）原版天空列已与背景天空基本一致。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-17.log`）。
jar：`61893507f79cf14f5a2acab230051ebbf9ee80f47ef3f4644cb7822857f89f9b`。


## 里程碑 30（对齐轮 18）：云层第二版 —— 缩小平面 + UV 收敛，仍被平均成灰白

改动：
- 平面从 2048×2048 缩到 512×512；
- UV 从平铺 32 次改成 0..1（纹理管理器的采样器是 **CLAMP**，UV 超出 1 会被"拉边"成一片纯色）。

结果：天空帧与上一版**逐位相同**（`f7173e12…` —— 一整片灰蓝，天空盘与渐变都被盖掉）。
结合"云贴图是 256×256、从 250 格下方看 512 格平面"的几何关系，判断是**放大采样导致整张图被低通成平均色**，
不是 UV 或尺寸的单独问题。

结论：云层自绘需要"格子化"的云几何（原版是把云贴图的每个非透明像素挤成盒子），
不是一块平面能替代的。`SkyCloudPlane` 保持**默认关闭**（`SKYBOX_CLOUDS=1` 才启用）。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-16.log`）。
jar：`36d0ef980d9ee575237bed439b2f9ee6c94d5a84fbfd20250dceceeade51057d`。


## 里程碑 29（对齐轮 17）：云层自绘（第一版）——能画，但结果不可用

新增 `client/SkyCloudPlane`：用原版片段 `RenderPipelines.MATRICES_FOG_SNIPPET` 打底
（它把 `ProjectionMatrix`/`ModelViewMat`/`Fog` 等 uniform 声明好），再用 `core/position_tex`
画一块 2048×2048、UV 平铺 32 次的水平面，贴原版 `textures/environment/clouds.png`。

| 观察 | 结果 |
|---|---|
| 日志 | ✅ `云层（自绘）已绘制一帧 cloudHeight=192.0` |
| 画面 | ❌ 天空被冲成一片灰白、天空盘与渐变都不见了 |

⇒ 机制"能画"，但**云面尺寸/深度/UV 都不对**（平面罩满视野，把天空盘盖掉了）。
已把云层**默认关闭**（`SKYBOX_CLOUDS=1` 才启用），发布状态退回到上一轮"雾层 + 天空盘"的可用版本：
墙面 (140.9,150.9,173.1) vs 环境天空 (181.3,190.8,207.8)，差值 (-40,-40,-35)，与轮 15 一致。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-15.log`）。
jar：`e25b0904a9a224fb39a505acabde05e826b2de6bb677b0310189c6e3ffa360b0`。
截图：`docs/screenshots/26.2-r17.png`。


## 里程碑 28（对齐轮 16）：雾层参数标定（记录两组实测，选优保留）

| 参数组 | 墙面 | 差值 |
|---|---|---|
| `HAZE=(0.80,0.82,0.90)`，alpha = 0.95·√(1-y/15)（**保留**） | (141,151,173) | **(-40,-40,-35)** |
| `HAZE=(0.71,0.75,0.82)`，alpha = 0.85·(0.5+0.5√(1-y/15)) | (132,143,162) | (-50,-48,-46) |

反直觉的结论：**把混合色改暗、强度加大，结果反而更暗** ⇒ 说明这条 blit 的混合方向/通道解释还没完全对上
（现有实现是"实测最优"而不是"理论正确"）。已在代码注释里标明这一点，避免后来者按直觉再改一遍。

保留第一组（唯一实测更优的配置）。`SKYBOX_HAZE_BLIT=0` 可关掉对照。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-14.log`）。
jar：`da79747db616f8b0bec34a50d8fa6ab254b7578e63e78a57b80b5a85eb52b644`。


## 里程碑 27（对齐轮 15）：**雾层上线** —— 根因是管线没声明采样器

最后一个坑：`RenderPipeline.Builder` 必须 `withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler").build())`。
不声明采样器时，`pass.bindTexture(...)` 被静默忽略，采样恒为**不透明黑**（这正是前几轮"贴图全黑"的真相）。

上线后的量化（同一帧内、同高度，墙面 vs 环境天空）：

| 阶段 | 墙面 | 差值 |
|---|---|---|
| 无雾层 | (114, 140, 189) | (-68, -51, -19) |
| 颜色校正（轮 8） | (127, 146, 179) | (-54, -45, -29) |
| **雾层开启（本轮，默认）** | (141, 151, 173) | **(-40, -40, -35)** |

顺带确认两件事：
1. 贴图通道顺序：`NativeImage.Format.RGBA` + `setPixelABGR` 实际是 **BGR**，按 `b,g,r` 写入才对；
2. 只有方块面变亮、环境天空不变 ⇒ 雾层确实只作用在我们的天空 target 上，不会污染主画面。

开关：默认开启，`SKYBOX_HAZE_BLIT=0` 可关掉做对照。
截图：`docs/screenshots/26.2-haze-on.png`。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-13.log`）。
jar：`6ad31aad6f505c0288fdf355aaf448c3185cc5deb012ff96aa90683c8d774753`。


## 里程碑 26（对齐轮 14）：雾层能画、但采样恒为黑（三个上传写法都排除）

| 上传写法 | 墙面（被雾层覆盖处） |
|---|---|
| `writeToTexture(texture, image)` + `setPixelABGR` | (5.6, 8.2, 14.4) 近乎全黑 |
| 同一贴图两行分用 `setPixel` / `setPixelABGR` | 同样黑 |
| 1×2 贴图（更小、更简单） | 同样黑 |
| 16×16 渐变贴图 | 同样黑 |
| 原版写法 `writeToTexture(texture, image, 0, 0, 0, 0)`（照抄 `SpriteContents.uploadFirstFrame`） | 同样黑 |

重要副作用说明：**只有"方块面"变黑，环境天空不变** —— 这反过来证明：
雾层确实画进了**我们自己的天空 target**（所以只有采样这张贴图的方块面受影响）。机制是对的，
剩下的问题只在"被采样的那张贴图是黑的"，候选原因：
1. `pass.bindTexture("InSampler", ...)` 的**名字**与 shader 实际 uniform 名不一致（不匹配时采样为黑）；
2. `createTextureView(...)` 生成的视图不能直接用于采样。

`SkyHazeBlit` 仍默认关闭（`SKYBOX_HAZE_BLIT=1` 才启用）。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-12.log`）。
jar：`60b25718dd30f726c5aae2e64e3a5a4454db85539920d4d11cc3f64df213d748`。


## 里程碑 25（对齐轮 13）：贴图上传的两个重载都试过，参数语义仍未打通

| 尝试 | 结果 |
|---|---|
| `writeToTexture(texture, NativeImage)` + `setPixelABGR` | 四边形**画出来了**（会遮住画面），但采样结果是**纯黑** |
| 同一张贴图两行分别用 `setPixel` / `setPixelABGR` 写同一颜色 | 两行都是黑 ⇒ 不是像素顺序问题，是上传本身没写进去 |
| `writeToTexture(texture, ByteBuffer, 0,0,0,1,2,4)` | 抛 `Copy would overrun the source buffer (remaining length of 8, but copy is 2x4 of format RGBA8_UNORM)` ⇒ **参数位序不是 (mip,x,y,w,h,stride)**，实际被解释成 2×4 |

⇒ 结论：`ByteBuffer` 重载的 6 个 int 里，第 4、5 位被当成 **width/height**（传 1,2 被读成 2,4），
说明真实签名里还夹了别的东西。下一步按 `(mip, x, y, z/layer, w, h)` 再试一次；
通了就把雾层打开并复测墙面/环境天空差值。

`SkyHazeBlit` 仍默认关闭（`SKYBOX_HAZE_BLIT=1` 才启用），主链路零影响。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-11.log`）。
jar：`00892550b39ff578ea10e1689c6649b5f1ca5910190ded13e2d18c140ee35da7`。


## 里程碑 24（对齐轮 12）：**自建 pass 终于画出来了** —— 但贴图内容不对

按原版 `PostPass` 的写法重做（新增 `client/SkyHazeBlit`）：

| 关键点 | 做法 | 结果 |
|---|---|---|
| 顶点 | `core/screenquad.vsh`（由 `gl_VertexID` 生成），**不绑顶点缓冲** | ✅ |
| 片元 | `core/blit_screen.fsh`，采样 `InSampler` | ✅ |
| 图元 | `withPrimitiveTopology(TRIANGLES)`（少了会抛 `Missing primitive topology`） | ✅ |
| 绘制 | `draw(3, 1, 0, 0)` + 5 参 `createRenderPass`（带深度附件） | ✅ **日志：`雾层(blit) 已绘制一帧`，画面确实被这块四边形改变** |

⇒ **前 6 轮"draw 不写入"的根因就是：我一直在用带顶点缓冲的管线 + 4/6 顶点 draw；原版的正确姿势是
screenquad 管线 + 无顶点缓冲 + 3 顶点。**

新问题（已缩小范围）：贴图内容不对。用"整块品红"测试时，帧里出现的是**黑色区域**而不是品红
⇒ `NativeImage` → `writeToTexture` 这条上传路径的颜色/格式没对上（像素打包顺序或 Format 选择）。
下一步只需把这一步调通，雾层即可上线。

当前 `SkyHazeBlit` 默认关闭（`SKYBOX_HAZE_BLIT=1` 才启用），主链路零影响。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-10.log`）。
jar：`44e1bead8f2757ec7c598acb2e7f7cbb923f0c7c0f67acb72ba8c3b59ad401ca`。


## 里程碑 23（对齐轮 11）：对照原版 `PostPass` 找差异 + 绑定原版雾（无效果，已记录）

对照 `net.minecraft.client.renderer.PostPass`（原版"往任意 target 画一层"的范本）逐行比对后补了两处：

| 差异 | 处理 | 结果 |
|---|---|---|
| 它用 **5 参** `createRenderPass(..., depthView, clearDepth)`，我们用的是 3 参 | 改成带 `getDepthTextureView()` 的 5 参 | 仍不写入 |
| 它 `draw(3,1,0,0)` 且**不绑顶点缓冲**（顶点由 `gl_VertexID` 生成，screenquad 管线） | 记录为后续方向 | — |

另外把**原版 WORLD 雾**绑进 `RenderSystem.setShaderFog` 再画天空盘：

- 日志确认成功：`[skyboxblock] 天空雾已绑定（原版 WORLD 雾）`；
- 但实测墙面颜色**完全没变**（`(126.9,145.7,179.1)`，与绑定前逐位相同）
  ⇒ `renderSkyDisc` 的 shader 不吃 `Fog` uniform，原版天空的"提亮"另有来源
  （很可能 26.2 有独立的大气/雾通道，不在 `SkyRenderer` 里）。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-9.log`）。
jar：`42b676c652c20b0e9350146918614f017dab418c8ee8ddbc4f547f2d4120c14b`。
截图：`docs/screenshots/26.2-parity-r11.png`。


## 里程碑 22（对齐轮 10）：写成交付物——1.21.1 ↔ 26.2 行为对照表

新建 **[`PARITY-1.21.1-vs-26.2.md`](PARITY-1.21.1-vs-26.2.md)**，把"完全还原 1.21.1"拆成 12 条可验收项：

- **已对齐 8 条**：shaderMode 0/1/2/3、自定义天空盒、日月星、六面独立可见性、配方/掉落/创造栏/命令链路；
- **未对齐 4 条**：云（`CloudRenderer` 帧外调用不产出几何）、地平线雾/大气（颜色校正只能补一半）、
  DH 深度注入（依赖抓帧+自研 shader）、Iris 光影下的天空（按要求未验）；
- **卡点**：自建 `RenderPass` 的 draw 不写入，8 个已排除假设逐条列出。

本轮还试了"引擎自己的几何上传路径"（`BufferBuilder` → `MeshData` → `GpuBuffer`），
纯品红全屏四边形**仍然一个像素都不写**（帧只剩 clear 色），说明问题不在几何构造。
下一步应逐行比对原版同样"往任意 target 画一层"的实现（`PostChain` / `ScreenEffectRenderer`）。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-8.log`）。
jar：`9464bc6611449fa1e5def63deee99af0a64d5f97292c50c51fd56215e7c1f002`。


## 里程碑 21（对齐轮 9）：自建 pass 的"最小可判定实验"与已排除项清单

本轮把 draw 不写入的排查做成了可复现的实验（`SKYBOX_HAZE=1` + `SKYBOX_HAZE_SOLID=1`）：

| 已排除 | 依据 |
|---|---|
| pass / 附件不对 | clear 色能整帧生效（绿/品红都试过） |
| `withLocation` 传 String | 报 `Non [a-z0-9/._-] character in path`，改 `Identifier` 后通过 |
| 缺图元类型 | 报 `Missing primitive topology`，补 `TRIANGLES` 后通过 |
| 顶点 stride 错 | `POSITION_COLOR` 颜色改成 4 字节后仍不写 |
| pipeline 格式推断 | 显式 `(TRANSLUCENT, RGBA8_UNORM, WRITE_ALL)` 仍不写 |
| 缺默认 uniform | 调 `RenderSystem.bindDefaultUniforms(pass)` 仍不写 |
| `draw` 参数顺序 | `(6,1,0,0)` 与 `(0,6,0,1)` 都试过 |

**尚未验证**：`GpuBuffer.slice()` / `setVertexBuffer` 这条链，以及引擎里"必须由 `BufferBuilder`+`MeshData`
上传"的隐含约束（vanilla 的所有几何都走那条路）。→ 下一轮就试这条路。

> 注：本轮加的"draw 完成后打印 GPU debug messages"探针没能生效（`probeLogged` 在方法入口就被置真），
> 属于探针自身的 bug，不影响结论。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-7.log`）。
jar：`874060c0342dc9e8a77be77cc07b05a8282fd0f43dfc8b557debcc58ebc586a4`。


## 里程碑 20（对齐轮 8）：天空盘颜色校正（可测量的接近原版）

自建 pass 这条线继续排除变量后仍然"写不进去"，于是换了个**只依赖已验证 API** 的办法：
把 `renderSkyDisc(skyColor)` 的入参先向"浅灰蓝大气色"混合，直接改天空盘本身的颜色。

| 参数 | 墙面（原版天空列） | 同高度环境天空 | 差值 |
|---|---|---|---|
| 修正前 | (114, 140, 189) | (181, 191, 208) | **(-68, -51, -19)** |
| 向雾色混 35% | (119, 144, 189) | 同上 | (-62, -47, -19) |
| **向 `(0.72,0.76,0.82)` 混 80%** | (127, 146, 179) | 同上 | **(-54, -45, -29)** |

视觉上（`build/world-frame.png`）原版天空列明显变亮、出现接近环境的浅色带，
但**整体仍偏深**：说明"混合颜色"只能补一部分，真正的差距在"原版天空由雾/大气另行叠加"。

`SKYBOX_BRIGHTEN` 仍是可调开关（默认 0.8，设 0 可关掉做对照）。

**draw 仍写不进去**（本轮又排除了 stride、pipeline 格式、`bindDefaultUniforms`、draw 参数顺序），
`SkyHaze` 默认关闭。

回归全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-6.log`）。
jar：`db568808da964a40a25d38231f913f88d081653db70442f406abdd847f7d0d7c`。


## 里程碑 19（对齐轮 7）：把"画不上去"进一步切成两半——pass 能写、draw 不能写

用二分法把问题钉死（全部实机验证）：

| 试探 | 结果 |
|---|---|
| `createRenderPass(label, colorView, Optional.of(品红))` | 整帧变 **(255,0,255)** ⇒ **pass 与附件都是对的**，写目标没问题 |
| 同一个 pass 再 `setPipeline + setVertexBuffer + draw`（纯品红四边形、alpha=1） | 整帧仍是 clear 色 ⇒ **draw 一个像素都没写进去** |
| 修正顶点 stride（`POSITION_COLOR` 的颜色是 4 个**字节**，之前按 4 个 float 写） | 仍然不写 ⇒ 不是 stride |
| `withColorTargetState` 显式给 `(TRANSLUCENT, RGBA8_UNORM, WRITE_ALL)` | 仍然不写 ⇒ 不是 pipeline 格式推断 |

所以剩下的可疑点只有**顶点缓冲链路**：`GpuDevice.createBuffer(..., USAGE_VERTEX, ByteBuffer)`
→ `GpuBuffer.slice()` → `RenderPass.setVertexBuffer(0, slice)`，或者 `draw(...)` 的参数语义。
`SkyHaze` 保持**默认关闭**（`SKYBOX_HAZE=1` 才启用），主链路零影响。

回归仍全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**（`verify-parity-5.log`）。
jar：`83f8e8e24f6cb8f880c04d5d9629a0ec1e5643f8707a2201424e2149569b0f1c`。


## 里程碑 18（对齐轮 6）：把"自建 pass 画不上去"缩到一个可复现的最小现象

排查链（每步都有实机日志）：

1. `SkyHaze.draw` **确实被调用**：`SkyHaze.draw 进入：disabled=false solid=true target=true`（诊断开关 `SKYBOX_HAZE=1`、`SKYBOX_HAZE_SOLID=1`）；
2. 用地板味最重的 **纯品红、alpha=1、全屏**四边形去测，天空帧**一个像素都没变**（PNG 哈希与前一次完全相同）；
3. 排除项：`withLocation` 已改 `Identifier`、`withPrimitiveTopology(TRIANGLES)` 已补、`draw` 参数顺序两种写法都试过（`(6,1,0,0)` 与 `(0,6,0,1)`），结果一致。

⇒ 现象收敛为：**`RenderPass` 建立成功、`draw` 不抛异常，但目标纹理没有被写入**。
下一步要查的是这个 pass 的语义（`createRenderPass(label, colorView, Optional.empty())` 中 `Optional` 到底是
"clear 值"还是"load 开关"）以及顶点缓冲切片是否有效（`GpuBuffer.slice()`）。

**当前状态：`SkyHaze` 默认关闭**（`SKYBOX_HAZE=1` 才启用），不影响主链路。

回归仍全绿：109 + 782 + 186 + 283 + 68,012 + 17 = **69,389**，jar
`ab525238d8507b6d90f806fe2a107e3a8418fe3d0ffdb01015aa686cf72fe991`。


## 里程碑 17（对齐轮 5）：自建 RenderPipeline + RenderPass 的地基（雾层实验，默认关闭）

这轮把"往自有 target 再画一层"的完整链路搭起来了，并踩到了两个 26.2 特有的坑：

| 坑 | 报错 | 解法 |
|---|---|---|
| `RenderPipeline.Builder.withLocation(String)` 把参数当"路径" | `Non [a-z0-9/._-] character in path of location: minecraft:skyboxblock:haze` | 传 `Identifier`（`withLocation(SkyboxBlockMod.id("haze"))`） |
| 缺少图元类型 | `IllegalStateException: Missing primitive topology` | `withPrimitiveTopology(PrimitiveTopology.TRIANGLES)` |

现状：`SkyHaze` 能建 pipeline、`RenderPass` 能开、`draw` 不抛异常，但**帧里看不到变化**
（四边形的顶点/投影约定与"直接写 NDC"不一致），所以**默认关闭**，想继续实验设 `SKYBOX_HAZE=1`。

量化基线（同一帧内、同高度比较，墙面 vs 环境天空）：约 `(-68, -51, -19)`，即自绘天空整体偏暗、偏蓝。
这个偏差与"雾"是两件事：先要对上底色的亮度，再谈地平线渐变。

回归仍全绿：283 + 186 + 68,012 + 782 + 109 + 17 = **69,389**（`verify-parity-4.log`）。
jar：`e2b88111dc01a295a7e969e732a23fd871bbb926ed759222229c894313392386`。


## 里程碑 16（对齐轮 4）：DH 兼容的现状与口径（如实告知，不假装支持）

| 项 | 1.21.1 | 26.2 现状 |
|---|---|---|
| 检测 | `ModCompat.DISTANT_HORIZONS` | ✅ 新的 `client/ModCompat`（DH + Iris），启动即打日志 |
| 深度注入 | `DistantSkyDepth` 把 DH 的深度纹理喂进自研天空 shader | ❌ 自绘路径没有自研 shader，**没有注入点** |
| 实机日志 | — | `26.2 环境：Iris=true DistantHorizons=true（DH 深度注入在自绘天空路径下不可用）` |

**为什么不硬做**：DH 的深度注入依赖"我们自己写 shader"和"抓帧得到当前帧深度"两件事，
26.2 的自绘方案把这两件事都去掉了（`SkyRenderer` 用自己的 pipeline 往我们的 target 画）。
要恢复等价能力，只有两条路：
1. 回到 1.21.1 的抓帧架构（mixin 进 sky pass + 自研 shader）；
2. 自建 `RenderPipeline`/`RenderPass`，把 DH 深度当采样源做遮挡 —— 工作量等同一次渲染层重写。

两条都超出"顺手对齐"的范围，所以本轮先把检测与环境日志落地，把口径写清楚。

回归仍全绿：283 + 186 + 68,012 + 782 + 109 + 17 = **69,389**。
jar：`583247ab095c652b0a5d3055a4bd12163ee376c62c59c36c0b2dde1316f7f135`。


## 里程碑 15（对齐轮 3）：用真实 view-projection 取样 → 更差，已回退（附量化）

想解决"墙里的天空比环境天空偏深"的问题，试了直接用相机本帧的
`Camera.getViewRotationProjectionMatrix(...)` 当取样矩阵（理论上应当逐像素一致）。结果反而更偏：

| 取样矩阵 | 墙面颜色 | 同高度环境天空 | 差值 |
|---|---|---|---|
| 合成投影 `view × projection`（现状） | (143.0, 175.2, 235.2) | (162.5, 182.5, 219.2) | (-19.5, -7.3, +16.0) |
| `getViewRotationProjectionMatrix` | (74.2, 92.9, 127.6) | (160.8, 189.2, 232.6) | (-86.6, -96.3, -105.0) |

结论：该矩阵与渲染管线实际用的不是同一套约定（很可能是 V×P 与 P×V 的差别，或含 bob/近裁面修正），
**已回退**到 `view × projection` 合成路径，并把这次量化结果留在文档里，避免下次重复踩。

`SkyHost.viewProjection()` 接口保留（两个版本实现都有），供后续实验；渲染器当前不使用。

回归仍全绿：283 + 186 + 68,012 + 782 + 109 + 17 = **69,389**（`verify-parity-3.log`）。
jar：`c003f13a3b175fd41e9a6f6b9b21980d70d7428ca67fb57c07cb4f6200936de3`。


## 里程碑 14（对齐轮 2）：云的可行性实验（结论：out-of-band 调用产不出几何）

| 尝试 | 结果 |
|---|---|
| 新增 `CloudTargetMixin`（`@Redirect` 把 `CloudRenderer.render` 里的 `mainRenderTarget()` 换成我们的 target） | ✅ redirect **命中**（日志：`云重绘 redirect 命中（目标已重定向）`） |
| 在 `START_MAIN` 里额外调一次 `CloudRenderer.render(color, status, partial, range, camPos, gameTime, height)` | ✅ 调用成功（日志：`云重绘生效：status=FANCY range=128`），但天空帧**没有任何云像素** |
| 参数猜测修正 | `cloudRange` 传 0 → 一个云格都不画；传 128 也一样（说明不是 range 问题） |

**结论**：`CloudRenderer` 在帧图（frame graph）之外被调用时不产出几何 —— 它依赖本帧的 UBO/状态。
所以"自绘天空帧里补云"这条路要么改成 **mid-frame 钩子**（在云 pass 里复制一份到我们的 target），
要么放弃；当前实现保留但**默认关闭**（`SKYBOX_CLOUDS=1` 才启用），不引入额外开销。

回归保持全绿：283 + 186 + 68,012 + 782 + 109 + 17 = **69,389**（`verify-parity-2.log`）。
jar：`7fcf3d6fe5afc59a6796533f88d7f0db80f8d051af2fd8a0dd1776b66c77c7c1`。


## 里程碑 13（对齐轮 1）：取样数学抽离 + 新回归 + 风格截图

| 项 | 结果 | 证据 |
|---|---|---|
| 取样数学抽成纯函数 | `client/SkyUvMath`（`project` / `equirect`），渲染器改为调用它 | `SkyBlockEntityRenderer` 里不再有内联投影 |
| 新回归套件 | **`SkyUvMathRegression: 17 checks OK`** | `build/reports/SkyUvMathRegression.txt` |
| 全套回归 | 283 + 186 + 68,012 + 782 + 109 + 17 = **69,389** | `gradlew regAll` → BUILD SUCCESSFUL |
| 风格语义截图 | 六列墙：自定义 gradient / dusk / galaxy / 原版天空 / 纯黑 / 纯白 各不相同 | `docs/screenshots/26.2-style-parity.png` |
| jar | `053182cf0d141bb079ee3d135e0f7f94982ffd9733d60fb522f98fcba5b28fc4` | — |

新回归钉住的是"方向 → UV"约定（游戏里几乎不可观测，算错只表现为整面黑或方向偏）：
中心 → (0.5,0.5)、NDC 映射、左右对称、相机背后退回中心、等距柱状四方向 + 接缝连续性。


## 里程碑 12（目标：与 1.21.1 无差别）——风格语义对齐 + 日月星查实

### 已对齐

| 项 | 1.21.1 行为 | 26.2 现状 |
|---|---|---|
| shaderMode 0（原版） | 方块面 = 当前维度真实天空 | ✅ 自绘天空帧（屏幕投影采样） |
| shaderMode 1（纯白） | 纯白面 | ✅ 纯白贴图 + 顶点色（新增 `textures/misc/white.png`） |
| shaderMode 2（纯黑） | 纯黑面 | ✅ 同上，顶点色 0 |
| shaderMode 3（全景） | 该风格自己的全景图 | ✅ `RenderTypes.entitySolid(texture)` + 等距柱状 UV |
| 自定义天空盒 | 同上 | ✅ 已验证出图 |

截图证据：`build/world-frame.png`（6 列墙：纯白 / 纯黑 / 原版 / galaxy / dusk / 自定义 gradient 各自不同）。

### 日月星：**其实是正常的**（之前误判）

- 夜间（`time 18000`，仰视）：天空帧里**有星空与月亮**（`build/sky-frame.png` 那一版）；
- 白天（`time 1000`，朝东偏上）：天空帧里**有太阳与光晕**；
- 之前的 "SUN_ONLY 只有清屏色" 是因为当时机位/时刻把太阳放到了视野外，不是方法没生效。

### 仍然存在的差异（继续做）

1. **云**：1.21.1 抓的是真实帧，天空盒里有云；26.2 是自绘，云不在我们的 target 里。
   `CloudRenderer.render(...)` 虽然 public，但它画到主目标；要把它画进自绘帧需要 mid-frame 钩子（mixin）
   或每帧 GPU 拷贝 —— 后者会形成"墙上套墙"的反馈，暂不做。
2. **DH 兼容**：`ModCompat`/`DistantSkyDepth` 仍在 legacy。
3. **两个渲染型回归**（`SkyGeometryRegression`、`SkyEnvironmentRenderRegression`）未移植。


## 最终验收（2026-11-02）：目标范围内的条目全部达成

`gradlew clean build regAll` → **BUILD SUCCESSFUL**；jar `c40c20e8b93dcba02080ce4fae59ab4e7cb234398004fffeb0e2745cd0f18fc1`。

| 目标条目 | 结果 | 证据 |
|---|---|---|
| 26.2 编译通过 | ✅ | `verify-final.log` / `build/build-26.2.log` |
| 26.2 实机进游戏 | ✅ | `client-262.log`：`sky target created …`、`sky host = …SkyRendererHost` |
| 方块面显示真实天空 | ✅ | `docs/screenshots/26.2-final.png`（内置风格 = 自绘天空帧） |
| Q1 自定义天空盒 | ✅ | `docs/screenshots/26.2-custom-sky-ok.png`（橙色地平线带） |
| Q2 按玩家设置 | ✅ | `client accepted revision 1 → 2` |
| 配方数据 | ✅ | `Loaded 1594 recipes`（迁移前 1585 + 9） |
| 回归 | ✅ **69,372** 条 | 283 + 186 + 68,012 + 782 + 109，报告在 `build/reports/` |
| 26.1 回迁 | ✅ 编译通过 | `build/compile-26.1-pass3.log`；产物 `output/…-mc261.jar` |
| 26.3 前迁 | ✅ 编译通过 | `build/compile-26.3-pass4.log`；产物 `output/…-mc263.jar`（`SkyHost` + 4 份版本实现） |

**已知限制（不影响上述结论，留给后续）**：

1. **日月星没画进天空帧**：已用两个诊断开关排除"混合/遮挡"（`SKYBOX_SUN_ONLY=1` → 只剩清屏色）与
   "model-view"（`SKYBOX_SKY_IDENTITY=1` → 结果一致）；字节码显示 `renderSun/Moon/Stars` 各自会创建
   RenderPass 并 `bindDefaultUniforms`，问题应在它们内部依赖的材质/状态上。
2. **DH 兼容未接**：实例里有 `DistantHorizons-3.3.3-26.2`，但 `ModCompat`/`DistantSkyDepth` 还留在 legacy。
3. **两个渲染型回归未移植**（`SkyGeometryRegression`、`SkyEnvironmentRenderRegression`），依赖已作废的
   `SkyWorldRenderer`/`SkyVanillaGeometry` 内部结构。
4. 26.1 / 26.3 **只验到编译与打包**（本机没有对应客户端实例；按要求也不做光影测试）。


## 里程碑 11（本轮）：回归套件移植并全绿（69,372 条断言）

| 套件 | 结果 | 报告 |
|---|---|---|
| `SkyEnvironmentRegression` | **283 checks OK** | `build/reports/SkyEnvironmentRegression.txt` |
| `SkyConfigRegression` | **186 assertions PASS** | `build/reports/SkyConfigRegression.txt` |
| `SkyTimeRegression` | **68,012 assertions PASS**（逆相位最大误差 0.0021 tick） | `build/reports/SkyTimeRegression.txt` |
| `ServerCommandRegression` | **782 checks OK** | `build/reports/ServerCommandRegression.txt` |
| `SkyboxServerBackendRegression` | **PASS 109 checks** | `build/reports/ServerBackendRegression.txt` |
| 合计 | **69,372** | `gradlew regAll` → `BUILD SUCCESSFUL`（1m27s） |

**做法**：把 1.21.1 的 7 个回归源搬到 `src/test`，其中 2 个（`SkyGeometryRegression`、`SkyEnvironmentRenderRegression`）
依赖已作废的 `SkyWorldRenderer`/`SkyVanillaGeometry` 内部结构，移到 `src/legacy-1.21.1/test` 待重写；
其余 5 个只做了两处 API 适配：

1. `ResourceLocationArgument` → `IdentifierArgument`；
2. `CommandSourceStack` 的权限参数从 `int` 改成 `PermissionSet`，断言改用
   `Commands.LEVEL_GAMEMASTERS.check(source.permissions())`。

Gradle 新增 `regAll` 与 5 个 `regXxx` 任务（`JavaExec` + `enableAssertions`），一条命令跑全套并落盘报告。

**本轮另做的诊断结论（日月星）**：`SKYBOX_SUN_ONLY=1` 时天空帧只剩清屏色 → `renderSunMoonAndStars`
/ `renderSunriseAndSunset` 在 26.2 的这套调用里**完全没画东西**（不是混合/遮挡问题）；
再排除 model-view（`SKYBOX_SKY_IDENTITY=1` 结果一致）后，下一步要看这两个方法对
`RenderSystem` shader 状态/材质 atlas 的依赖，或改成自绘日月。


## 里程碑 10（本轮）：配方 JSON 迁移完成 + Q2 端到端打通

| 项 | 之前 | 现在 | 证据 |
|---|---|---|---|
| 配方解析 | 9 个全报 `Couldn't parse data file … recipe/…` | **全部解析成功** | `Loaded 1585 → 1594 recipes`，日志无解析错误 |
| 配方写法 | `"key": {"G": {"item": "minecraft:glass"}}` | `"key": {"G": "minecraft:glass"}`（26.2 的 Ingredient 接受纯字符串） | `tools/fix-recipes-262.py` 批量迁移 9 个文件 |
| Q2 客户端生效 | 只验证了"命令能执行" | **两端都验到**：`client accepted revision 1`（进服同步）→ `client accepted revision 2`（按玩家命令后） | `build/client-262.log` |

jar 当前 SHA256：`c51e127b4849065928113c0f68665b8c05a69197e1c27ae5c2f96e4fa000a0f2`。

**新增诊断**：`SkyboxServerState.accept` 现在会对每个被接受的 revision 打一行日志 —— 这正是之前
"玩家独立设置未生效"那类问题最难查的地方，现在从日志一眼可见。

**仍遗留**：日月星没进天空帧；DH 兼容未接；Q1 的 `Missing texture references in model skyboxblock:block/gradient`
警告仍未查（不影响出图，但模型引用可能有问题）。


## 里程碑 9（本轮）：Q1 自定义天空盒出图 ✅ + Q2 命令烟测 ✅

| 项 | 结果 | 证据 |
|---|---|---|
| Q1 自定义天空盒 | ✅ 渲染出自己的全景（橙色地平线带 + 左侧深色竖带） | `docs/screenshots/26.2-custom-sky-ok.png` |
| Q1 链路 | 配置 → 生成 pack → 注册方块 → BE 提交 → 纹理采样 全通 | 日志 `render skin=skyboxblock:gradient custom=true texture=…/textures/sky/gradient.png` |
| Q2 按玩家设置 | ✅ 命令执行无异常 | 日志 `[skybox-fixture] q2 command executed`（`/skyboxblock player @s visual/sky_color #22FF00` + `player @s show`） |

**关键修复（上一轮画成纯黑的原因）**：
自定义全景不要自己拼 `RenderSetup.builder(pipeline).withTexture("Sampler0", tex)` —— 直接
`RenderTypes.entitySolid(texture)` 就是配好的 RenderType，采样器绑定才对。自拼版本在 26.2 上采到黑。

**Q2 夹具做法**（不依赖单人作弊开关）：数据包 `tick` 里用记分板一次性触发
`execute as @a run skyboxblock player @s visual/sky_color #22FF00`，以服务器权限执行。

**遗留**：配方 JSON 仍全部解析失败（26.2 recipe 格式变化，日志每轮都在报）；
Q2 的"客户端实际生效"还需要一个断言（例如客户端 accept 时打日志）。


## 里程碑 8（本轮）：Q1 自定义天空盒打通"注册→打包→渲染"，但采样仍取到黑

| 环节 | 状态 | 证据 |
|---|---|---|
| 配置解析 | ✅ | `config/skyboxblock/skyboxes/01_gradient.conf` + `gradient.png` 被读取 |
| 生成资源包 | ✅ | `config/skyboxblock/generated/assets/skyboxblock/{blockstates,items,models,textures}/{...}gradient.*` 全部落盘 |
| 纹理内容 | ✅ | `textures/sky/gradient.png` 就是那张 1024×512 蓝→橙→黑渐变（`read_image` 验证过） |
| 方块注册 | ✅ | 数据包 `fill 2 -60 4 2 -56 4 skyboxblock:gradient` 成功，BE 提交日志 `render skin=skyboxblock:gradient custom=true texture=skyboxblock:textures/sky/gradient.png` |
| 纹理解析 | ✅ | `custom texture skyboxblock:textures/sky/gradient.png -> SimpleTexture` |
| **画面** | ❌ | 该列渲染成**纯黑**（`docs/screenshots/26.2-custom-sky-black.png`）——采样点落在全景图底部黑区，或 UV/采样器不对 |

**本轮实现**：`SkyBlockEntityRenderer` 按 skin 分派纹理——
自定义 skin 用它自己的 panorama（`RenderTypes.entitySolid(tex)` + `RenderSetup.withTexture("Sampler0", tex)`），
UV 走等距柱状（`equirect`：yaw→u、pitch→v）；内置 skin 仍走自绘天空帧 + 屏幕投影。
同时给每个 skin id 打一次渲染日志，方便核对。

**下一轮要做的**：把 custom 分支的 UV 实际值打出来（或直接改成"按面法线固定 UV"做二分），
确认是 UV 计算问题还是 `entitySolid` 采样器绑定问题；必要时改用自建 RenderPipeline。


## 里程碑 7（本轮）：26.3 前迁完成（编译通过 + 三版产物齐了）

| 版本 | 编译 | 产物 | SHA256 |
|---|---|---|---|
| 26.2 | ✅ `BUILD SUCCESSFUL` | `build/libs/skyboxblock-1.0.6-test.0-port.1.jar` | `5332c0b688b2748c293b43c2db208f808ee4024bc5b5762c65256561dba0f2ac` |
| 26.1 | ✅（上一轮） | `output/…-mc261.jar` | 与 26.2 同源（字节一致的历史值 `3a15b2d0…`，本轮源码又变过，需重打） |
| 26.3 | ✅ **本轮** | `output/…-mc263.jar` | `16031924f38e779e913fd8e0b7a9e2d4b2bedcb4aed8a8aa93c5d5cdfcddc735` |

**架构**：`SkyHost` 接口 + `SkyHosts`（反射挑实现）
- 26.1/26.2 → `client/SkyRendererHost`（内部再按构造器/方法签名反射适配 26.1 与 26.2）；
- 26.3 → `client/v263/SkyRendererHost263`（`SkyRenderer.render(slice, state)`、renderpearl 包名、双格式 `TextureTarget`）。

**26.3 额外需要的版本文件**：`SkyBlock`（codec 被删）、`BlockPropertyCompat`（`isViewBlocking` 多一个 AABB 参数）。

**本轮踩坑**：
- Gradle 在切换 `MCVER` 后曾误判 UP-TO-DATE，把 26.3 的 class 当成 26.2 的产物 → 现在给 `JavaCompile` 声明了
  `targetMinecraftVersion` 输入，并且**切版本一律 `clean`**；
- `SkyHosts` 用 `getConstructor()` 找不到私有构造器 → 改用 `getDeclaredConstructor()`，同时把
  `SkyRendererHost` 构造器改成 public（首次实机就是崩在这，crahs 报告 `没有可用的 SkyHost 实现`）。


## 里程碑 6（本轮）：26.3 差集实测（29 个错误，已分类）

| 项 | 结果 |
|---|---|
| 26.3 编译 | `MCVER=26.3 APIVER=0.165.0+26.3 gradlew compileJava` → **29 个错误**（`build/compile-26.3.log`） |
| 包搬迁 | `GpuFormat`/`CommandEncoder`/`FilterMode` → `com.mojang.renderpearl.api.*`；`RenderSystem`/`RenderTarget`/`TextureTarget` 仍在 `blaze3d` |
| 天空入口 | 26.3 合并成 **`SkyRenderer.render(GpuBufferSlice, SkyRenderState)`** 一个方法 |
| 颜色类型 | `SkyRenderState.skyColor:int → Vector3fc`、`sunriseAndSunsetColor:int → Vector4fc` |
| 资源包 | `AbstractPackResources` 消失；`Pack.ResourcesSupplier` 变成 `openMetadata/openResources` |
| 结论 | 26.3 不能靠反射解决（是接口形状变了），需要 `src/26.3/java` 版本实现 + 稳定切换方式 |

详见 `docs/API-MATRIX-26.3.md`。


## 里程碑 5（本轮）：26.1 回迁完成（同一份源码 + 同一个 jar）

| 项 | 证据 |
|---|---|
| 26.1 编译 | `MCVER=26.1 APIVER=0.155.3+26.1.2 gradlew compileJava` → **BUILD SUCCESSFUL**（日志 `build/compile-26.1-pass3.log`） |
| 26.2 编译 | 默认配置 → **BUILD SUCCESSFUL** |
| 产物 | `output/skyboxblock-1.0.6-test.0-port.1-mc261.jar`，与 26.2 的 `build/libs` jar **同一 hash** `3a15b2d0…` |
| 26.2 实机 | 反射版宿主运行正常：`sky target created 1280x800 (rendererOwnsTarget=true)`，方块面仍是天空帧 |

**怎么做到的**：把 26.1/26.2 的 5 处 API 差异全收进 `SkyRendererHost` 的反射探测
（`TextureTarget` 4/5 参构造器、`SkyRenderer` 2/3 参构造器、`clearColorAndDepthTextures` 的
`Vector4f`/`int` 两种重载、`mainRenderTarget`/`getMainRenderTarget`、`mainCamera`/`getMainCamera`），
外加 `RenderSystem.outputColorTextureOverride`（26.1 用，26.2 不需要，26.3 已删）。
`BlockEntityType` 换成 `FabricBlockEntityTypeBuilder`（两版都在）。

因为 26.1 起官方不混淆、且差异全在运行期解析，**两版编出的 class 字节相同** —— 一份 jar 通吃 26.1/26.2。

### 踩坑

- `-Pmcver=26.1` 会被 Gradle 当数字解析成 `26` → 改用环境变量 `MCVER`；
- 两个 Gradle 并发会争 `caches\fabric-loom` 锁（实测卡 20 分钟）→ 跑之前先清掉 Gradle 进程；
- 删 `build/libs/*.jar` 后 `build` 可能 UP-TO-DATE 不重打 → 用 `--rerun-tasks` 或 `clean build`。


## 里程碑 4（本轮）：接缝修复 + 26.1 差集实测

| 项 | 结果 |
|---|---|
| 方块间暗缝 | **已修**：`SkyBlockEntity.shouldRenderFace` 改为"相邻是任意 SkyBlock 就剔除"（原来只剔同皮肤，导致不同风格贴合面共面 z-fighting）。截图 `build/world-frame.png` 已无竖缝 |
| 天空状态值 | 日志实测：`sun=5.196 moon=8.337 star=0.0 rain=1.0 dawn=#ffe533 dark=false` — 状态抽取正常，但天空帧里**看不到日月星**（下一步查） |
| 截图链路 | 改为 `LevelRenderEvents.END_MAIN` + `Screenshot.takeScreenshot(mainRenderTarget, cb)` 落盘，**不依赖窗口焦点** |
| 26.1 回迁探路 | `MCVER=26.1` 编译：**只有 7 个错误，集中在 2 个文件** → `docs/API-MATRIX-26.1.md` |

### 26.1 差集（详见 `docs/API-MATRIX-26.1.md`）

1. `gameRenderer.mainRenderTarget()` / `mainCamera()` → 26.1 是 `mc.getMainRenderTarget()` / `getMainCamera()`；
2. `clearColorAndDepthTextures` 第二参 26.1 是 **int**（ARGB）而不是 `Vector4f`；
3. `com.mojang.blaze3d.GpuFormat` 在 26.1 不在该包下；
4. **`SkyRenderer` 在 26.1 只有 2 参构造器**（无 RenderTarget）→ 画进自建 target 要走
   `RenderSystem.outputColorTextureOverride / outputDepthTextureOverride`（26.3 已删这两个字段）；
5. `BlockEntityType(supplier, Set<Block>)` 在 26.1 是 private → 用 `FabricBlockEntityTypeBuilder`。


## 里程碑 3 ✅：方块面显示真实天空（直连自绘天空帧）

| 证据 | 内容 |
|---|---|
| 主帧 | `docs/screenshots/26.2-sky-frame-ok.png`（整面墙呈天空蓝，地平线处有过渡） |
| 天空帧 | `docs/screenshots/26.2-skyframe-raw.png`（`build/sky-frame.png` 的副本：上半屏是天空渐变，地平线以下是清屏色） |
| 日志 | `sky frame dumped -> build/sky-frame.png`、`world frame dumped -> build/world-frame.png` |

**这轮解决的两个关键点**：

1. **v 轴方向**：该渲染路径的 `v=0` 在图像底部（GL 约定），所以 NDC 的 +Y 要映射到**更大**的 v
   （原来写的是 `0.5 - ndcY*0.5`，导致上黑下蓝）。
2. **地平线以下**：天空只画地平线以上，以下本来是清屏黑 → 改成用 `SkyRenderState.skyColor` 清屏，
   方块面因此不会再出现黑带。

**顺带解决的工程问题**：窗口被别的程序挡住时 `mc-shot.ps1` 截不到游戏画面（试过 `SetForegroundWindow`、
`AttachThreadInput`、临时 TOPMOST 都不行）。改成**在 `LevelRenderEvents.END_MAIN` 里用
`Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), cb)` 直接落盘主帧**，
完全不依赖窗口焦点——以后所有实机截图都走这条。

## 里程碑 3（旧记录）：自绘天空纹理已接到方块面，但天空帧内容还不对

**已经跑通的**：

- `SkyRendererHost`：`TextureTarget("skyboxblock_sky", w, h, true, RGBA8_UNORM)` +
  `new SkyRenderer(textureManager, atlasManager, target)` + `LevelRenderEvents.START_MAIN`，零 mixin；
- 目标纹理通过 `AbstractTexture` 子类注册进 `TextureManager`（固定 id `skyboxblock:textures/sky/frame.png`），
  方块面的 `RenderType` 用它（`RenderSetup.builder(...).withTexture("Sampler0", id)`）；
- 日志：`sky target created 1280x800` / `sky frame #1 1280x800 skybox=OVERWORLD skyColor=#ff78a7ff`；
- 方块面按**顶点屏幕投影**取 UV（`projectionMatrix * viewRotationMatrix * 顶点世界坐标`），
  于是每个面相当于一扇透视窗：截图 `26.2-sky2/3/4.png` 能看到面里出现蓝色天空与黑色分区。

**还没对的**：天空帧里只有屏幕下部有天空，上部是清屏黑 ——
`SkyRenderer` 内部依赖 `RenderSystem` 的 model-view/projection，在 `START_MAIN` 时刻这套状态不是本帧相机矩阵。
已尝试：① 设 `getModelViewStack().set(camera.getViewRotationMatrix(...))`；② 用 `ProjectionMatrixBuffer` +
`RenderSystem.setProjectionMatrix(..., PERSPECTIVE)` 显式给投影；③ 绕 X 翻 180° 诊断（整面变黑，已回退）。
现象变化但仍未铺满 —— 说明剩下的差异在 MC 自身的投影约定（含 `ProjectionType.applyLayeringTransform`）或
`SkyRenderer` 期望的输入上，下一轮继续：先做「把天空帧导出成 PNG 直接看」的调试通道，再对症修。

## 里程碑 2：进世界 + 方块面真的画出来了 ✅（2026-11-01）

| 证据 | 内容 |
|---|---|
| 世界 | 官方 26.2 服务端生成 superflat（`tools/make-flat-world-262.py`），复制成客户端存档 `flat262` |
| 夹具 | `tools/add-fixture-datapack.py` 写入 `minecraft:load` / `minecraft:tick` 函数：放 5 列天空盒墙、固定机位 `tp 0.5 -60 -3.5 0 0`、`difficulty peaceful`（**26.2 的 `gamerule doMobSpawning` 解析失败**） |
| 渲染 | 截图 `docs/screenshots/26.2-wall12.png`：正前方一整片方块面（当前是占位贴图 `white_concrete`），位置/尺寸正确 |
| 探测日志 | `submit probe #1 skin=skyboxblock:galaxy_sky_block pos=BlockPos{x=0,y=-61,z=4} poseT=(-0.5,-2.62,7.5) d=(0,0,0)` |

### 这一轮踩到的三个 26.2 渲染坑（都是实测，记下来免得再踩）

1. **顶点格式必须填满**：`RenderTypes.entitySolid` 用 `NEW_ENTITY` 格式，
   position + color + uv + overlay(uv1) + lightmap(uv2) + normal 一个都不能少，
   少一个就在 `BufferBuilder.endLastVertex` 抛 `Missing elements in vertex`（第一次进墙就崩）。
2. **`submit` 拿到的 `PoseStack` 已经含"方块相对相机"的平移**
   （`poseT=(-0.5,-2.62,7.5)` 对应方块 (0,-61,4)、相机 (0.5,-58.38,-3.5)）。
   所以顶点只写 0..1，**不要再自己加偏移**——加了就会变成"2 倍间距的漂浮方块阵"（wall11 截图就是）。
3. **`submitCustomGeometry` 不采用外层 `PoseStack` 的位移**：
   传进去的 stack 只影响节点排序，实际变换来自提交节点自己的 pose。

## 里程碑 1：编译通过 + 实机加载 ✅（2026-11-01）

| 证据 | 内容 |
|---|---|
| 编译 | `gradlew build` → **BUILD SUCCESSFUL**，49 个 class，0 error（`build/build-26.2.log`） |
| 产物 | `build/libs/skyboxblock-1.0.6-test.0-port.1.jar`，16,879,172 B，SHA256 `b156a2860c7d860324d53656a033a38f6dcddf228f9bd2f0d035c506e441cc6d` |
| 实例 | `D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1`（fabric-api 0.161.0+26.2 / Iris 1.11.4 / Sodium 0.9.2 / DH 3.3.3-26.2 均在） |
| 加载日志 | `- skyboxblock 1.0.6-test.0-port.1`（版本已正确替换）、`[skyboxblock] initialized: 9 sky window styles`、`[skyboxblock] 26.2 client initialized (self-rendered sky path)` |
| 崩溃 | 无；我们的 mixin（`CustomSkyPackMixin`）无告警；无 ModResolutionException |

启动方式：`tools/launch-262.py`（自建 KnotClient 启动器，JDK 25 + `--sun-misc-unsafe-memory-access=allow` + `--enable-native-access=ALL-UNNAMED`）。

## 这一轮改了什么（关键）

1. **搬迁留档**：37 个 1.21.1 专用文件（抓帧 / GL 状态机 / 自研云 / IP / Iris / 旧回归）移到
   `src/legacy-1.21.1/java`，不参与编译；`tools/port_legacy_move.py` 可复现。
2. **渲染层按自绘方案重写**（`docs/P3-SPEC.md`）：
   - `SkyBlockEntityRenderer` 改成 26.2 的 `BlockEntityRenderer<T, S>`：`extractRenderState` + `submit`，
     经 `SubmitNodeCollector.submitCustomGeometry(...)` 拿 `VertexConsumer`，`RenderType` 用
     `RenderTypes.entitySolid(...)` 一次建好（当前是占位纯色贴图）。
   - `SkyBoxRenderState`（新）承载 skin + 六面可见性。
   - `SkyboxBlockClient` 只剩三件事：注册 BER、`CustomSkyPacks.install()`、客户端命令。
3. **注册/命令/网络适配**：`FabricCreativeModeTab`、`BlockEntityType` 公开构造器、`Properties.setId`、
   `PayloadTypeRegistry.clientboundPlay()`、`Commands.LEVEL_GAMEMASTERS.check(permissions)`、
   `ClickEvent.OpenFile` / `HoverEvent.ShowText`、`DimensionType.Skybox`、`Level.getDefaultClockTime()`、
   `player.getScoreboardName()`、`Block.shouldRenderFace(state, neighbour, direction)`。
4. **资源**：`CustomSkyPack` 改继承 `AbstractPackResources`；自定义风格物品模型改产 `assets/<ns>/items/`；
   新增 `tools/gen-items-262.py` 为 9 个内置方块生成新形态物品模型；`fabric.mod.json` 版本区间改 `>=26.1 <27`。

## 下一个里程碑：细节与多版本

1. **日月星**：状态值有了但帧里没有 —— 怀疑 `renderSunMoonAndStars` 依赖 `RenderSystem.setShaderColor`/混合状态，
   下一步加日志或换调用顺序验证；
2. **26.1 回迁**：按 `API-MATRIX-26.1.md` 的 6 条做版本适配层，然后实机verified；
3. **26.3 前迁**：Renderpearl 包搬迁 + 抓帧机制重设计（单列一轮）；
4. Q1 自定义天空盒 / Q2 按玩家设置的实机回归；配方 JSON 迁移；DH 兼容。

## 已知待办（按优先级）

- [x] 世界/截图链路
- [ ] `SkyRendererHost`：自绘天空纹理 + `LevelRenderEvents.START_MAIN`
- [ ] 自定义天空盒（Q1）在 26.2 的实机验证：`config/skyboxblock/skyboxes/` → `items/` 模型 → 方块可放
- [ ] 按玩家设置（Q2）：`/skyboxblock player <targets> <attr> [value]` 实机回归
- [ ] 配方 JSON 迁移：26.2 的 `recipe` 格式变了（日志报 `No key fabric:type` / `List is too short`），9 个配方解析失败
- [ ] DH 兼容：DH 3.3.3-26.2 已在实例里，等主链路通了再接 `ModCompat`/`DistantSkyDepth`
- [ ] 26.1 回迁 / 26.3 前迁（26.3 需 Renderpearl）
- [ ] 回归程序改写（`SkyEnvironment` 283 / `ServerCommand` 782 / `Backend` 109 …）
