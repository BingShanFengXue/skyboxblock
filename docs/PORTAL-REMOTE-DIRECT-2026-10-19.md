# 门户远端同姿态直接控制（2026-10-19，实机结果已更新）

## 最新实机结论：控制协议可信，普通几何仍异常

本节覆盖后文保留的“待主线运行”历史表述。冻结R23主包与独立harness完成三环境各baseline/empty/sky，以及原生/FSR两份真正无门remote-direct控制。结果：

- 无光影：[三变体报告](<../build/reports/ip-r23-no-shader.json>) **559/559 PASS**，范围仅同维度纯平移、真实穿越与返回的九场景。
- ITRP原生：[三变体报告](<../build/reports/ip-r23-iris-native.json>) **587/605 FAIL**；ITRP FSR2=2：[三变体报告](<../build/reports/ip-r23-iris-fsr.json>) **587/605 FAIL**。两者均在真正未加载主模组的baseline普通条纹几何上失败。
- [原生direct](<../build/reports/ip-r23-iris-native-direct.json>)与[FSR direct](<../build/reports/ip-r23-iris-fsr-direct.json>)均协议通过，但directGeometry与portalGeometry均失败，结论为 **DIRECT CONTROL NOT VERIFIED**。这不支持把问题唯一归因门户路径或本天空模组。
- 有界兼容尝试：launcher新增显式basic模式；[原生basic无主模组基线](<../build/portal-fixture/ip-r23-basic-iris-native-baseline/fixture.json>)和[FSR basic无主模组基线](<../build/portal-fixture/ip-r23-basic-iris-fsr-baseline/fixture.json>)均实跑九场。持久配置compatibilityRenderMode=true、实际IrisCompatibilityPortalRenderer、IrisRenderingPipeline、irisActive=true及实际FSR设置均已核对，未关闭光影或用RendererDummy冒充兼容。
- [basic基线专用核验r2](<../build/reports/ip-r23-basic-baseline-assessment-20261019-r2.json>)两组均 **protocolPassed=true、92/96**，仅四项固定视角的普通红白条纹失败，原始stripeAccuracy=0.85等阈值未动。主线亲自查看两组front/oblique原图，画面与红项相符。
- 核验r1的配置键异常[原报告](<../build/reports/ip-r23-basic-baseline-assessment-20261019-r1.json>)保留：IP序列化会丢弃launcher的crossPortalView/clipping/depthClamp旧别名；r2按enableCrossPortalView/enableClippingMechanism/useDepthClampForPortalRendering实际字段逐项核对，实际均true，不声称旧别名被采纳。兼容模式键本身为真实字段且已生效。
- 两个basic运行各4条原样保留的chunkloading ERROR、fatal/thread=0；进程成功与几何通过分开报告。

按[本轮边界](<IP-STAGE-SCOPE-2026-10-19.md>)，基础普通基线仍红，本轮到此停止工程扩展：不继续basic empty/sky，不以同目录冒充三组，不再改生产渲染、不放宽oracle、不追加无边界调试。随后冻结证据并派全新GPT6.1SOL独立审计。**ITRP+IP兼容未获验收；旋转、缩放、嵌套、跨维度及完整动态DH均未验证。正式release仍未接受。**

---

以下为实现阶段保留的设计与历史状态。

项目作者：Bing6512。技术命名空间 `dev.yg` 保持不变；第三方原作者 / 版权不改。本项不是最终独立工程审计。

> **本次仅实现与纯 javac / 离线证据检查；没有启动游戏或 Gradle。**
> 当前六组 ITRP 门户结果不直接给出唯一根因；下述 direct 控制需要主线用新独立 harness 串行实跑后才产生新可视证据。

## 为什么现有九场景还缺这项控制

[原 harness](<../src/portalFixture/java/dev/yg/skyboxfixture/PortalFixtureClient.java>)的默认 `portal` 模式九场景和真实往返要求保留：

- `00-no-portal` / `08-clean` 的玩家在源侧，feet=(0.5,140,-3)，不是固定门户内的远端相机。
- `05-through-after` 是真实过门后的不同 Z / 俯仰，且 reverse portal 仍在；它不是门户完全缺席的同姿态控制。
- 参考固定场景 `01-single-front` 的已观测子相机约为 (32.5,141.62,-3)，需用其真实 view / projection / camera 与实际记录的玩家 yaw / pitch建立控制，不能只平移一张图或复制一个覆盖 mask。

已读 native / FSR2 各 baseline、empty、sky 六组的 manifest / 实际包哈希，并查看 baseline / empty 等真实图；现象支持继续做直接控制，不据此宣告 IP、Iris 或 ITRP 任一单组件已定因。

## 新控制做什么

新增可选 `--mode remote-direct`，默认旧 `--mode portal` 行为不变。直接模式只有 `00-remote-direct` 一张正式 capture：

1. [plan 生成 / runner](<../tools/portal-remote-direct.py>)从指定旧夹具固定场景的**唯一真实 child observation**读取 camera / raw P / V，验证 root→child 是已声明的纯 (32,0,0) 平移，再用实际 first-person eye-height offset推导远端 feet；不注入或替换任何矩阵。
2. 仅支持四个已有静止姿态：front、oblique、near、through-before。移动中的过门 / 返回不被硬凑成静止控制。
3. 从所选参考夹具复制已核对 hash 的 supporting mods、实际配置 / 资源包 / 同一 shader ZIP；只替换为新构建的独立 harness。empty / sky 的主包也从参考夹具复制锁定版本，不默默使用最新主包。
4. Shader options按现有检查器的语义规则校验：忽略 timestamp 注释，缺省文件只采用该已核对 ITRP 的已知默认值。直接控制继承实际语义设置，不允许偷偷改 FSR。
5. 新世界 seed / ordinary layout与原 fixture一致：白红条纹后墙、真实 stone柱、同一 bedrock地板；sky变体还保留同一目标天空墙。**不创建 forward / reverse / global portal，也不全局关闭 IP。**
6. [服务端控制](<../src/portalFixture/java/dev/yg/skyboxfixture/RemoteDirectControl.java>)实际采样 **1627** 个 block ID（不是只填 AABB 声明），并检查普通 portal entity与global portal都为0；截图前再复核。
7. owned客户端设置真实远端位置与原 yaw / pitch；禁 hand以匹配已审查 IP child 的 `doRenderHand=false`。原 portal 模式不改 hand策略。
8. 捕获真实 root camera / raw P / V / Iris material P、实际 FSR vectors、当前 frameTimeCounter / frameCounter（如可用）。不冻结这些时钟，不采生产世界 RGB。

新 harness 的 metadata / summary显式带 `mode=remote-direct`、参考输入 SHA-256、实际几何 / portal inventory及 `remoteDirectVerified`。direct模式不虚构 `actualThroughCompleted` / `actualReturnCompleted`；旧portal模式仍要求真实往返成功。

## 主线先构建新独立 harness

由主线在其独占构建阶段串行执行既有独立 harness构建 / remap任务；本项不修改主构建。
必须等待当前 root LICENSE / AUTHORS / notices的 Bing6512 修正后再打包。旧冻结 jar / 历史归档不重写。

[launcher](<../tools/portal-regression.py>)会预检新 jar中确有 `RemoteDirectControl.class` 与客户端的 remote-direct能力；旧 jar会在启动游戏前失败，不会误跑旧九场景冒充直接控制。

## 可复现 CLI

在项目根使用现有带 numpy / Pillow 的 Python；下面 `<PYTHON>` 指该解释器。

### 先做最小 native baseline 直接控制

```powershell
<PYTHON> tools/portal-regression.py --mode remote-direct --variant baseline `
  --direct-from build/portal-fixture/steps3-baseline-itrp-native-r1 `
  --direct-scene 01-single-front --label steps3-direct-baseline-itrp-native-r1 `
  --harness-jar build/libs/skybox-portal-fixture-1.0.0.jar --settle-ticks 90
```

### 相同 FSR2 baseline 直接控制

```powershell
<PYTHON> tools/portal-regression.py --mode remote-direct --variant baseline `
  --direct-from build/portal-fixture/steps3-baseline-itrp-fsr-r1 `
  --direct-scene 01-single-front --label steps3-direct-baseline-itrp-fsr-r1 `
  --harness-jar build/libs/skybox-portal-fixture-1.0.0.jar --settle-ticks 90
```

无需再次传 `--shader` / `--shader-settings`：remote-direct锁定参考夹具自身环境。显式 shader / shader-option / jar 覆盖若与参考不同会中止；不会改用户参考实例。
要测 empty，选择对应 empty参考并传 `--variant empty`；它保留该参考主包的实际 hash。可改 `--direct-scene` 选择另一个已记录固定姿态，每次使用新 label。

可加 `--prepare-only` 只建立 plan / 新隔离实例而不启动客户端。**准备用 label 和正式运行 label应不同**：既有目录不覆盖，也不把只有 plan 的准备运行当实机结果。

### 检查真实 direct结果

```powershell
<PYTHON> tools/check-portal-remote-direct.py `
  --portal build/portal-fixture/steps3-baseline-itrp-fsr-r1 `
  --direct build/portal-fixture/steps3-direct-baseline-itrp-fsr-r1 `
  --scene 01-single-front --output build/reports/steps3-direct-baseline-itrp-fsr-r1.json
```

[专用分析器](<../tools/check-portal-remote-direct.py>)先验证真实 mod集 / 包身份、语义设置、摄像机 / 两套 P-V、独立 block probes与无 portal证据；协议不全或不一致时不给视觉判决。不会为了凑同姿态而 resize / warp图像。

然后使用[原门户 oracle](<../tools/check-portal-regression.py>)的未放宽 pillar / stripe几何判据，在公共投影 ROI内分别观察门户图和direct图。跨run RGB差异只作 observation，**不是整个画面应逐像素等同的判据**。

## 如何解释，不如何过度解释

- 报告 `passed` 表示**新直接控制本身可信且普通几何正常**，不是完整门户兼容通过。
- 只有配置 / pose / geometry协议通过、直接控制正常、门户baseline异常，且两者真实未加载主模组时，才支持“该具体组合的 portal路径存在边界，不是由本主模组存在这一因素引起”的较窄观察。
- 这仍不唯一归因于 IP / Iris / ITRP某一组件，也不外推其它版本、选项、旋转 / 嵌套或 DH。
- 两图都异常、两图都正常、或者协议未通过，应分别记录，不能预设需要哪一个结论。
- 子视图与direct的 shadow / temporal histories本来可能不同；云 / wave / exposure / jitter也未强制相同。旧捕获可能没有时钟字段，明确记缺失，而不是编造同步。
- 历史参考只有几何声明 / 源码与既有图像核查，没有拍摄瞬间的1627块探针；分析器将明确标记 `referenceActualGeometryVerified=false`，不把新direct的实际probe倒灌成旧参考已有证明。
- 为更强归因，可在后续由主线用同一新 harness重跑对应portal场景；新harness会为原四个固定portal场景额外保存只读 `actualWorldGeometry`，九场景 / walk契约和绘制不变。有双侧probe时，分析器严格检查实际block证据一致；旧参考和新 harness hash不同也会显式记录。
- 保留原九场景 / 三组检查器及旧证据，不改其阈值或既有报告来迁就新控制。

## 归档附带修正

[归档器](<../tools/archive-test5.py>)现除原 source / docs / selected evidence外，还自动纳入当前root `AUTHORS*`、`NOTICE*` / `*NOTICES*` 与整个 [LICENSES](<../LICENSES/Literal-Sky-Block-MIT.txt>)树。
它读取当前更正后的字节并写入entry哈希；不改作者字符串、不改第三方版权、不重写任何旧冻结jar或历史zip。仅在最终真实 acceptance布尔 `passed=true` 后运行正式归档。

## 本项验证状态

- 五个独立 fixture Java源纯 javac：**通过**；不依赖主 renderer类编译。
- 六组现有 native / FSR reference plan、hash / 语义选项 / child pose推导：**通过离线校验**。
- 实际 block-probe完整性 / 重复 / 缺块 / 有 portal负例：**通过纯数据自测**。
- 新 direct实际截图、实际 control矩阵匹配、pillar / stripes最终结果：**待主线运行 / 待填**。
- 本项没有 Gradle、游戏启动或最终独立审计；默认用户游戏、zip与配置未改。
