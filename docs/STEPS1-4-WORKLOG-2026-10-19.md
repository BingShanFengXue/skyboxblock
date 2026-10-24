# 第 1—4 步实施日志（进行中）

日期：2026-10-19。用户明确指令：依次推进拼接缝、维度/时间规则、Immersive Portals 基础兼容尝试、水/染色玻璃前景遮挡。

## 最新人类指令（覆盖旧计划）

2026-10-19最新范围：**“该轮增加执行到‘沉浸式传送门兼容测试与尝试兼容’后结束，在这之后审计。”** 本次按直接人类指令执行门户三环境三变体、必要remote-direct和针对性兼容尝试；到该阶段结束工程执行，再派全新GPT6.1SOL独立审计。现有高墙偶发缺绘和完整组3项恢复差异保留为已知问题，不把本轮无限扩成全部整改。持久目标文本更新至revision7，但工具对恢复界面暂停返回“the model cannot resume a paused goal; the user must resume it”，故未重复resume；当前操作依新直接请求执行，不声称自动目标已恢复。

当前主线唯一GPU批次`pwsh-8d94ab8af7d6400d`：R23冻结main9f1903…/独立harness612f57…，no-shader/iris-native/iris-fsr三环境各baseline/empty/sky，settleTicks300；随后两份无主模组baseline remote-direct。另派1名GPT6.1SOL工程triage `81432bdc-f188-4c92-a18c-7f990d597bd8`只读核compatibilityRenderMode语义和必要小修位置，不跑GPU/Gradle，不充终审。

2026-10-19用户最新指令：**“审计延后，报告任务状态。”** 已将独立终审延后，不启动最终审计个体；此次仅核对报告、更新状态，开发目标保持paused/disarmed（revision6）。[本次任务状态报告](<TASK-STATUS-AUDIT-DEFERRED-2026-10-19.md>)记录当前R23实际结果、R24未复现诊断和剩余缺口；历史章节中“完成后立即独审”的安排以后续人类指令为准。

用户明确纠正：项目作者身份为 **Bing6512**，原默认称呼来自提示词注入，不作为作者名。current LICENSE、AUTHORS、THIRD_PARTY_NOTICES、main/harness Fabric authors与包检查断言已更正；保留 `dev.yg` 技术namespace，不误改第三方作者／模型贡献名。旧冻结jar/归档不重写，后续包重新构建并核验署名。

用户醒来后明确取消保活和预关机。唯一主提醒 `schedule-8820c2c6-89dc-4ad4-b334-dd77672d8893` 已删除；提醒个体 `6c7dc893-c3ca-4278-be56-686248018a93` 已通知停止。此前从未下发任何OS关机命令；后续不安排/执行自动关机。第1—4步开发与最终独立GPT6.1SOL审计仍保留。目标已更新为 revision3；旧章节中的定时/关机安排仅作历史记录，不得恢复。

## 基线与约束

- 基线版本：1.0.2-test.4。
- 原始 jar SHA-256：`59ccfd220789db8ebbc0b041f1818d747ae4e84ce915a70ca0658a49398f567d`，本轮开始已重新核对。
- 开始前新增完整基线归档：`../../artifacts/snapshots/skybox-test4-before-steps1-4-20261019.zip`，386 文件，85,299,265 字节，完整性 PASS；SHA-256 `47bbbeb86824a3e0ac1704652c4e390dfa7e1c05ebc54b104c1dd8077497871c`。
- 不修改或打开用户存档，不替换参考实例安装包，不改用户光影选项；运行使用隔离新世界。
- 保留已修复的 Iris 逻辑目标状态、FSR 光栅、前景遮挡、物品/选框对齐及 Y=192 云高。
- 第 3 步是分层可行性实验，不把全部门户能力作为交付前提；第 4 步需保留透明材质效果并阻断天空方块之后的世界。
- 已派 2 个只读审查个体，按用户指定使用 `xyjunfun1 / gpt-6.1-sol`，分别审查门户接入与透明前景根因，不跑 Gradle、不改主线。

## 用户新增证据

- [水前景透视](<screenshots/user-water-xray-20261019.png>)：天空仍可见，水区域暴露天空方块之后的白色物体/地形。
- [染色玻璃前景透视](<screenshots/user-glass-xray-20261019.png>)：玻璃色调与纹理仍在，但其后不应可见的内容被暴露。

截图只证明现象，不单凭截图确定根因。第 4 步已从待验证提升为明确问题。

## 进度

1. 拼接缝：已将共享端点改为“绝对整数网格点先形成，再双精度减相机，最后单次 float 舍入”。五万组不变量中旧算法产生 1,115 组位级差异，新算法全部一致；完整数学回归 165,019/165,019。首轮 Iris/ITRP/FSR2 隔离实机 8 场景、52.58s、错误 0，覆盖/最终色检查 111/111；旧算法真实图像对照仍在核验。该精度缺陷已有证据，截图接缝的唯一根因尚未下结论。
2. 维度/时间：实现统一逐视图显示状态中。默认普通天空读当地实际相位/天气（含固定时间、无天光的普通天空）；非常规天空改用服务器已同步且存档持久化的 gameTime 生成主世界相位/月相，离线不推进，睡眠/时间指令不跳备用钟，不建立每块方块时钟。备用天气默认晴，可配置 simulatedWeather=local；特殊维度可用 localTimeDimensions/simulatedTimeDimensions 显式覆盖。正在新增固定白天、无天光固定夜晚的真实数据包维度与 17 场景专项，不把纯数学自定义相位测试冒充第三方昼夜模组实测。
3. 门户：已交付独立 sourceSet（4 Java+2 JSON）、独立真实无本mod baseline launcher，harness首次编译出现重复资源（改为setSrcDirs）、随后旧AP导致主Mixin映射报错（恢复Loom直接字节码remap，useLegacyMixinAp=false）后主包14s及harness10s构建通过。实际IP文件名6.0.7但内报6.0.6（含IpDimFix），运行Fabric Loader实际0.19.3，与编译依赖0.16.14需区分记录。无shader baseline首次启动卡在开世界前，尚无可声称门户通过的截图。主线已实现GameRenderer WrapMethod try/finally视图scope和Iris真实final后逐view resolve，待重新构建/实机。
4. 透明前景：确认最高嫌疑为新天空深度/首颜色配旧solid GBuffer；Iris先solid hand后copy depthtex1的时序已查证。精确Iris CompositeRenderer before FullScreenQuadRenderer.renderQuad钩子可按Pass.name=composite40识别，读实际colortex6输入（不是当前output FBO），用既有pre-translucent depthtex1保证手/柱；后续40→50保留原生透明合成。仅精确hash匹配ITRP才改MRT1/2与中间背景，未知包保持原路径；尚未完成该生产适配。独立透明oracle与51场景契约已交付，主线21/21合成控制通过，不等于实机通过。

### 第1/2步追加事实

- 旧共享端点负路径的相同8视角也111/111通过，数学缺口未形成同类视觉负例，绝不写成已视觉复现。新增SeamScan（64stage×23实际jitter×16亚像素相位、平墙/L折角、两窗口尺寸）及独立10/10合成检查，已编译、待实机。
- 新增SkyCoverage候选：仅在自己颜色目标标记真实天空命中的深度，世界深度既不清也不写；最终只在当前深度仍匹配该指纹时接受另一个同外观天空面的低分辨率样本，不放宽旧全局tolerance。该候选待完整遮挡/持物回归与扫描。
- 维度首次17场景运行超时并未生成场景：第二次界面诊断确认是实验世界确认界面（class_405，selectWorld.backupJoinSkipButton），不是渲染崩溃。已为仅显式ownedGameDir的新隔离夹具加入继续操作；尚待重跑。所有初次失败日志保留。

## 最新进度：R23 输入隔离与实际时间/帧稳定协议（2026-10-19）

- 用户明确承认柱缺块为自己误点（“缺一块是我不小心点了一下”）。R22原生缺口截图/808报告继续保留，但不将实际破坏的夹具当作天空穿透完整柱体的渲染回归；旧工程只读报告的“历史输入尚未证”是当时边界，已由新增人类事实补足。
- R23只在named/canonical owned fixture的START_CLIENT_TICK清KeyMapping queued click/held及toggle，不改用户options或其它MC进程。[19场输入隔离实机](<../build/render-regression/test5-r23-input-vanilla-bugs/run-result.json>)68.3s、ERROR/fatal/renderThread0；[285/285 world通过](<../build/reports/test5-r23-input-vanilla-bugs-world.json>)，三处缩放场景server/client柱均6块，depthChange恢复0/2741/2987，主线已read_image完整柱遮挡。
- R23 frozen main SHA `9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129`，sources `5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab`；[构建math339030/339030](<../build/steps14-test5-build-r23-settled.log>)、[包58/58](<../build/reports/skybox-test5-package-r23-settled.json>)通过。
- 已核真实ITRP慢路径：pixelData亮度雾w=.75临界阻尼约12.312秒才进1e-3 snap；SkyImage history=.97按真实root render帧，300帧残留约1.08e-4。Iris smooth参数实际乘0.1，不能把7/5说7/5秒；实际drynessHalfLife本版jar为200→20秒，rain0不保证已有wetness0。R22all先前跨雨天/维度并有首baseline仅16渲染帧，故70/90ticks不是相同稳定状态。源码机制不当GPU实际误差唯一归因，也不豁免原严格失败。
- 原全局tick freeze还会使Minecraft跳过TextureManager.tick；仅水流freeze恢复world时，水sprite循环也恢复。新显式settled fixture因此按两个精确sprite名固定**资源声明的首动画帧**，不冒称物理index0或旧随机相位；owned guard外正常delegate，close正常释放，reload/换world重新锁。用户游戏默认flag关闭，正常水动画不受影响。此为测试相位控制，不是生产修复、也不是仅墙volume。
- 新`--settled-transparency`是固定53协议：2个真正无Sky warmup＋完整原51测量，每正式场景实际目标pose稳定后至少**15真实秒＋300root render帧同时满足**；所有像素/相关/RMS/geometry/≥2band/hardcaps原值。实际首正式empty已记录15745ms/1333frames，输入guard1015tick、水sprite观察/锁定2/2，证明不是只声明条件。
- 当前唯一GPU主批次 **pwsh-028b73c1311c491b**：按vanilla（solid marker）settled53→core113、Iris-native settled53→core113、full/FSR（显式DH新生成disabled，renderer DEFAULT）settled53→core113。新最长期限只是给源码依据稳定等待留时间，不按结果挑slot。R22尾部未预热controls已取消，不再继承当前成绩。
- Gate现已要求核心world/time/alignment默认all113；settled透明53须实际2+51、15k/300记录、真实输入/水sprite控制。工程child49仅补该Gate selftest，不改本体/GPU；此前40/40是旧pin，不当当前新逻辑已验收。
- 精确接手状态/当前hash/待办与原失败限制以[持续执行交接](<CONTINUE-STEPS1-4-20261019.md>)最新R23首段为准。最终同包seam正负、位置负例、门户/remote-direct、全新独立GPT6.1SOL终审与正式归档仍待完成；保活/关机继续取消。

## 较早进度：R22 窄水更新、受控全模组回归（2026-10-19 18:55前后）

- 冻结R22 main SHA256 `70aceaaa6d1ba2264965868278ab42db0276d1e8b700282ba101c25f3f5b100e`，sources `5894a2d01e598918862312dfdd4ff1a799a29516a01c740834449274181968b4`；[数学构建339030/339030](<../build/steps14-test5-build-r22-fluid.log>)、[包55/55](<../build/reports/skybox-test5-package-r22-fluid.json>)通过。最终独审/交付尚未完成。
- 新[scheduled water guard](<../src/main/java/dev/yg/skyboxblock/RegressionFluidControl.java>)与[common mixin](<../src/main/java/dev/yg/skyboxblock/mixin/RegressionFluidMixin.java>)仅在双flag+严格owned fixture root+overworld+精确水墙体积下抑制匹配的WATER/FLOWING_WATER回调；默认完全关闭，无client依赖，正常不冻结server/world/chunk/network ticks。
- R19full坏参考已通过真实双侧探针识别：server4chunk/32marker/357floor正确，client全部0，原图无柱。IP字节码核对playerId1、冻结gameTime6240%13=0时每server tick purge/generation++却错过phase1刷新；getChunkNow走真实IP map，非旧storage。具体本次卸载packet轨迹未存，不能外推所有丢块唯一归因。R20另一个freeze相位NoSky物理199/199通过不消除该反例。
- R22[真实同包水更新策略因果43/43](<../build/reports/test5-r22-fluid-causal-pair-r2.json>)，actual installed jars/pack已rehash：normal时间6293→6368→6446→6520→6620、始终4client chunk；水40/outside0/黑白16+16/floor357全部双侧一致。legacy negative时间6241恒定（phase1这次client仍4），不强求旧相位相关问题每次复现。原39/39报告保留，增强r2新写。
- 新几何v3共12项加waterOutside0，实际server/client gameTime独立记录；NoSky每root frame断言geometry/material/bridge/resolve全0并累计frame数；原checker只增物理/时钟/水控制硬检查，像素门槛未改，54/54自测。release gate36/36 TEMP通过，包含实际DH配置不符、把renderer关闭冒充受控生成会失败；不是独立审计。
- 原动态DH组的[NoSky深度诊断62/62有效输入](<../build/reports/test5-r20-never-sky-full-fsr-a-drift.json>)：全程无mod私绘但全图noneMAE3.10/p9927，DH depth覆盖22682→150898、128216像素coverage flip；旧observer last-built list数组未识别而明确unavailable，R22已修观察分支。此不是恢复验收，也不waive原hardcaps或唯一归因所有shader history变化。
- 为分离异步新远景生成与核心遮挡，在**新隔离副本**显式`--dh-worldgen disabled`，仅generatorPlan=DISABLED，DH mod保留、rendererMode仍DEFAULT，原用户配置不变；manifest与gate实际pin配置并强制warning **controlled-near-world-not-dynamic-DH-validation**。不称动态LOD兼容，不隐藏原动态失败。
- R22该受控full真正NeverSky51场289.05s，[物理灵敏度343/343](<../build/reports/test5-r22-never-sky-full-controlled-a-sensitivity.json>)通过；保持原阈值、所有水/玻璃/保护两槽位物理正控与客户端状态正确。fatal/render-thread0，第三方ERROR原样保留。
- R22受控full113场592.53s完成；[world774/774](<../build/reports/test5-r22-final-full-fsr-world.json>)、[time149/149](<../build/reports/test5-r22-final-full-fsr-time.json>)、[alignment114/114](<../build/reports/test5-r22-final-full-fsr-alignment.json>)通过，严格透明检查仍在收取，不以runtime0代替通过。
- 当前唯一GPU批次`pwsh-b02246b1ee35420f`串行后续native113→vanilla113→受控full NoSky-b→native NoSky-a/b。完整继续清单与真实hash见[持续执行交接](<CONTINUE-STEPS1-4-20261019.md>)。不并行启动第二游戏，旧R20后续矩阵已取消且只结束核验归属的自有进程。
- 工程child49仅补empirical reader的v2/v3真实物理/时钟/DH配置语义绑定与自测，不改旧checker/gate/GPU；empirical仍仅诊断，不waive rawstrict。最终新GPT6.1SOL独立终审尚未开始。

## 较早进度：R18 因果通过，R19 增强物理参考（2026-10-19 18:00 前后）

- R18同包实体生命周期[最终因果报告](<../build/reports/test5-r18-entity-lifetime-causal-r2.json>) **53/53通过**，11/11合成witness契约通过。原始110个旧实例的removed=true、cachedSky=true、currentBlock=air、rawMapSameEntity=false均实际记录。正向dim110→NoSky0→SkyOn88→removed0；负向110→110→198→110。首次checker因Iris删除全默认options文件而NOT_VERIFIED的r1保留；r2按精确已核包默认值语义校验，未篡改实际设置。
- R18main SHA256 `3cd77919e55ae9b15fe71bca340d12cd059360fe5d0b17ff07422511b2225554`；数学339030/339030、包52/52。原生Iris113场景471.70s、ERROR/fatal/render-thread均0；world774/774、time149/149、alignment114/114。full113场景608.16s、world774/774、time149/149、alignment114/114；保留89条第三方ERROR，fatal/render-thread0。
- R18严格透明仍[原生967/972](<../build/reports/test5-r18-final-iris-native-transparency.json>)、[full965/972](<../build/reports/test5-r18-final-full-fsr-transparency.json>)失败；27项旧NoSky实体污染已消失，剩水4组reference无效（原生HF约2.88—2.95、full约2.5，门槛3）、removed原生1/full3。不得把reference无效且leakDetected=false解释为无泄漏。R18后续vanilla与neverSky排队已取消，不重跑即当通过；仅本会话自有PID核验并结束，用户游戏未碰。
- R19只增强固定夹具物理柱材料为沿Y黑/白concrete交替，仍四根同一1×8柱、原AABB/相机/所有旧HF/LP/相关/RMS门槛不变；保留--marker-pattern solid旧弱刺激。新增一次actualServerGeometry/actualClientGeometry，使用getChunkNow/raw BE map，不等待目标计数、不强刷。新checker只添加协议v2的物理计数硬检查；[工具自测](<../tools/check-transparency-regression-selftest.py>)实际48/48，旧阈值保持。
- R19冻结main SHA256 `47f2f23e5a7edf3ee9dfa19dd938aee47a24aae276ad7721822956d2a1281968`，sources `78a2fe2959d09fa4623890d804d4ec90d3b0e75c6083ecfb8a6e4ec1cfda47d5`；数学339030/339030、包52/52。原生真正NeverSky51场景238.97s、全部runtime错误0；[固定物理灵敏度](<../build/reports/test5-r19-marker-native-reference-sensitivity-r1.json>) **199/199通过**：所有前景/保护/两槽位真实参考作为自己的leak-positive均在原阈值下检出。水HF现在7.90—9.25，legacyEvidenceValid=true；先建立灵敏度，再评价SkyOn，不从SkyOn拟合ROI或门槛。
- 原工程empirical接管[完整自测87/87](<../build/reports/marker-reference-repeat-takeover-sol-selftest-r2.json>)、[R16full诊断](<../build/reports/marker-reference-repeat-takeover-sol-r16-full-fsr-r2.json>)、[R16native诊断](<../build/reports/marker-reference-repeat-takeover-sol-r16-iris-native-r2.json>)已交付。原生A/B可采信4/4、full0/4，但两candidate均110旧BE污染所以NOT_VERIFIED。此工具只交叉诊断，不waive raw strict；当前checker新增v2后其历史pin不外推，新完整自测另存。
- 只读GPT6.1SOL另核对DH蓝带：ITRP确读DH depth决定远景天空/雾，readonly只阻新更新和worldgen、不保证已有DB→GPU停止；没有唯一归因。现新增默认关闭owned-fixture深度/LOD观察类，用实际DH纹理与渲染列表指纹区分几何变化和history变化，不关闭LOD绘制、不setter、不freeze、不改用户配置。最终独立审计尚未开始。

## 较早进度：R17 定位与 R18 待验证修正（2026-10-19 17:00 前后）

- R16 无光影完整113场景的新增独立检查：[世界817/817](<../build/reports/test5-r16-final-vanilla-world.json>)、[维度149/149](<../build/reports/test5-r16-final-vanilla-time.json>)、[位置114/114](<../build/reports/test5-r16-final-vanilla-alignment.json>)、[透明972/972](<../build/reports/test5-r16-final-vanilla-transparency-mixed-r2.json>)均通过。mixed-all loader修正及缺证据控制的整套自测实际33/33；不是预估37。
- 光影透明严格报告仍失败：[full938/972](<../build/reports/test5-r16-final-full-fsr-transparency-mixed-r2.json>)、[native940/972](<../build/reports/test5-r16-final-iris-native-transparency-mixed-r2.json>)。除水参考不足和removed差异外，27个NoSky场景仍收集110旧天空实体。不能将0可见深度写等同无旧实体污染，原始失败全部保留。
- 新默认关闭的[只读收集诊断](<../src/main/java/dev/yg/skyboxblock/client/BlockCollectionDiagnostics.java>)已冻结进R17，jar SHA256 `ff63a9b54fafa35f8ceedc301aa608f4e8dc97cc8a7c6a4c7732070bac625058`。原生首8场56.34s完成、fatal/render-thread/ERROR均0；[逐节摘要](<../build/reports/test5-r17-native-cold-collection-summary.json>)显示35client块/35raw BE、四节compiled BE12/16/3/4、actual entity/geometry列表均在、late collect0。这次未复现R16的7/35，不能称其已修复；未改offscreen或强刷renderer。
- 同R17真实[dim→trans短运行](<../build/render-regression/test5-r17-native-removed-collection-r1/run-result.json>)53.83s：dim110→NoSky110→SkyOn198→removed110；旧110位置实际为y181..190/z2，vertices3960。下一同jar正/负诊断将补每实例removed/cached/current/raw-map身份，避免只靠serverSkyBlockCount（该字段只数新z5墙）断言旧高墙已同步清空。
- 工程只读GPT6.1SOL已核对真实Sodium/MC字节码：dispatcher查BE cachedState而不查removed；setRemoved保留level与cachedState；Sodium编译列表可保留旧引用。生产候选增加seen去重前的isRemoved、getChunkNow当前SKY_BLOCK和raw实体身份校验；未实机验证前不宣布该修正通过。
- 初次generic fixture同时视距6→12会触发Sodium workers重启，R16失败日志有该时序；尚无证据证明具体initial-build/dirty丢失窗口。R18夹具拟在新实例启动前设12，并在首次编辑前有限核验既有四chunk与四目标section已编译连续20client ticks；不强刷、不靠等待目标计数变绿。显式negativeFixtureStartup保留旧准备路径。
- [蓝晕真实BLOOM开关对照](<screenshots/test5-blue-halo-bloom-ab-20261019.png>)已亲自查看：关闭SUNLIGHT_RIMS宽蓝晕仍保留，单关BLOOM宽晕明显消失，修正初判为该组主要由泛光形成。两次实际TAA jitter不同，不冒充逐像素同帧A/B；天空边界额外放大尚未独立证明。
- 四份R16 genuine neverSky各51已完成；[full本底测量](<../build/reports/test5-r16-never-sky-full-fsr-measurement-r1.json>)首次NoSky none前后MAE17.836/p9968、另一份3.050/p9928，远景结构发生变化，且full-a首张marker图肉眼无柱；[native本底](<../build/reports/test5-r16-never-sky-iris-native-measurement-r1.json>)none两份p99均6。不得据此扩大既有绝对低通caps或放行无实际marker物理正控的水参考。
- 新独立empirical marker参考工具仍由原工程个体实施；新增只读工程个体专查收集路径。它们都不是最终全新独立审计。用户取消的保活／关机持续取消。

## 此前进度：R16（2026-10-19 15:50 前后）

- 已冻结R16 main/source/独立remote-direct harness；实际署名Bing6512，最低Loader声明保守收紧到编译版本0.16.14（实测0.19.3）。数学339030/339030、包52/52通过。main SHA256 `7f29ec9e3d09f033796f1b167a7f75e90bfc3cfae70e1183a12037ec3f6be184`；sources `2fa23ae8f1f14b1b69d394bd82d13c63d3570f361744d7b394341eeb93223a01`；harness `612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7`。
- R15 full113退出0但独立world754/764、time122/127；五个初始overworld维度场景Frame.blocks=0、source图缺失，服务端fill110/156成功。原生Iris与vanilla同R15 world分别774/774、817/817，time132/132、位置114/114，说明非所有环境必现。原始失败都保留。
- 7d只读核对：seam-clean gameTime8355→dim-day6078发生真回拨且IP stable timer重置；但watch generation/nanoTime与其它环境反例不支持直接断言“IP定时重置必然丢chunk”。R16夹具改为仅向前对齐目标相位，拍前独立采样服务端/客户端110sky、156背墙、169地板，有限等待实际同步再留20client ticks，不强刷renderer。新增20001条夹具时钟属性；不改生产SkyDisplayState计时策略。新增--scenes仅显式短诊断，默认all仍113；负控制都明确入manifest。
- R16整包FSR实际113场景617.23s完成，fatal/render-thread均0，既有第三方ERROR原日志保留；独立[world](<../build/reports/test5-r16-final-full-fsr-world.json>)774/774、[time+双侧几何](<../build/reports/test5-r16-final-full-fsr-time.json>)149/149、[alignment](<../build/reports/test5-r16-final-full-fsr-alignment.json>)114/114通过。第一day服务端/客户端块数均正确、gameTime30100、Frame110/1572。此为消除无效夹具变量后的实测，不等同唯一归因某IP缓存缺陷。
- GPU job `pwsh-196112907e254e3e`继续R16 Iris原生与vanilla各113；后续接透明同包native repeat、位置/旧逻辑反例、最终接缝扫描、门户三组/remote-direct、蓝光开关控制。最终新GPT6.1SOL独立审计尚未开始。
- C16已交付严格acceptance门禁与TEMP自测；主线碰到其接口更新中的selftest race（portal实验标签iris-fsr），保留失败并等待其停止编辑后重跑，不改别人文件或为现实raw失败绿灯。透明mixed all目录错误与水组弱几何证据仍在窄修/分析，不降门槛。
- 门户新launcher将实际renderThreadViolations显式入新run-result；不补写旧报告。旧/historical与最终候选身份分开，当前仍非最终交付。

## 较早进度：R15（2026-10-19 15:00 前后）

- 作者更正已进入R15实际binary/source包，内容核验51/51；冻结候选166786B，SHA256 `c8b867a6c74e0c6e3d62ddd1fc8a9b34b58b96725faeb7d6746396aa348413b1`。正式交付仍待全量与新审计。
- R13正/负clip与R14逐canvas诊断均130/132，证明clip不是固定白天缺云根因；R14实际program、sampler、blend equation、depth/cull/logic均正确。
- R15新增自有[云网格GL缓冲](<../src/main/java/dev/yg/skyboxblock/client/SkyCloudMesh.java>)，接收同一游戏native MeshData及纹理/坐标；保留fixture-only `negativeCloudBuffer`旧路径。[独立因果检查](<../build/reports/test5-r15-cloud-buffer-causal-pair.json>)29/29：正向132/132，负向130/132仅同两项云图零变化。旧云VAO Position/UV/Color属性实际指向仅120B的日月quad VBO（首坐标[-20,-100,20]），而5376×28B云mesh应150528B；新路径实际VBO字节及VAO/EBO归属全部正确。不是扩大云图阈值或改变Y192。
- [云正负图](<screenshots/test5-cloud-buffer-before-after-20261019.png>)由同jar、同相机/固定白天/FSR2=2的两次独立实机私有源生成；原像素未缩放。GPU job `pwsh-c65dcfda30304c57`完成上述正负后，正在同R15三环境各113场景：`test5-r15-final-full-fsr`→`test5-r15-final-iris-native`→`test5-r15-final-vanilla`，尚未宣称全通过。
- 用户新增[蓝紫边缘截图](<screenshots/user-blue-halo-20261019.png>) SHA256 `1cfe779d4d9c8008d68c9954206a1878e23d3829b8a17eafe9415e88a16b46d1`。已核ITRP默认SUNLIGHT_RIMS/BLOOM开启、当前sky emissiveness=0且方块未设置lightLevel；初判轮廓光+泛光，但天空边界额外放大与否仍待独立开关对照，不把初判写成精确实测归因。
- 原两个工程GPT6.1SOL个体分别补remote-direct无门同姿态控制/归档与严格acceptance聚合工具。最终独立审计仍须另派全新session；保活/关机继续取消。

## 较早进度：R11—R13 / Q8（2026-10-19 14:30 前后）

用户新增要求：每阶段主动报告真实进度。后续按完成项、实际验证、剩余问题汇报；不以构建退出0替代图像验收。

- R11三环境113场景各完成：整包FSR / Iris原生 / vanilla。世界覆盖分别774/774、774/774、817/817；维度时间原生与vanilla132/132，整包130/132，仅固定白天两场的实际私有云图无变化，尚未收口。
- P0位置检测器修正了灰侧／edge-on漏检，未改renderer/fixture/位置容差。三环境原R11原图重算均114/114；原失败95/102、97/102、102/102报告保留，新报告另存-r2。55/55工具自测；三个环境20px线移位各8/8、60px画面移位各16/16预期位置失败全部检出，JSON字节不变。最大物品误差3.200、4.234、1.613px，原18.432/23.04px容差未放宽。
- R12匹配jitter透明检查[原严格报告](<../build/reports/steps4-transparency-itrp-fsr-r4.json>)969/972，恰为三项removed-world-restored失败。两次真正从未放sky的同jar native timeline控制证明自身p99=7—8；[校准伴随报告](<../build/reports/steps4-restoration-calibrated-r1.json>)27/27，通过硬上限、低通形状、零残留块／私有写、实际包／phase一致性。原严格失败保持，不静默改阈值。
- Q8新增[AUTHORS](<../AUTHORS.md>)、[第三方声明](<../THIRD_PARTY_NOTICES.md>)、[Literal完整MIT副本](<../LICENSES/Literal-Sky-Block-MIT.txt>)；README、Fabric contributors、binary/source Jar均纳入署名。Literal保留历史MIT归属；Shadered标创作灵感且记录许可元数据矛盾；两PNG可由自有生成器逐像素复现。早期DeepSeek贡献依据用户确认，无逐行模型历史。
- [R13构建](<../build/steps14-test5-build-r13.log>)24s、数学319029/319029；[包审查](<../build/reports/skybox-test5-package-r13.json>)50/50，实际binary/source归属全文一致。冻结jar SHA256 `a029510d01e4eaa0e11cd4fbdd3ea55965448fef11b94128f530ca6968ef6fbf`，仅为候选，不是最终交付。
- R13加入私有canvas内clip0隔离，恢复外层原clip bit，不影响world faces裁剪；新增实际cloud program/texture/clip诊断及同jarnegativePrivateSkyClip开关。GPU job `pwsh-188584590a29485c` 正串行跑 `test5-r13-full-dimensions-fixed` 与 `test5-r13-full-dimensions-negative-clip`，原因假设尚待真图证明。
- 继续一名已有GPT6.1SOL工程个体7d6f，限定补门户同姿态无门direct控制及归档LICENSES；不碰main renderer、不跑Gradle/游戏。无光影门户实际独立检查559/559；Iris原生589/607、FSR588/607，基线画面仍异常，未宣布兼容。该工程工作不充当最后全新独立审计。
- 最终同包三环境、门户支持边界、接缝复验、透明校准、包锁定、文档／新快照与全新GPT6.1SOL独立审计仍待完成。保活／关机持续取消，禁止恢复旧计划。

## 较早里程碑（历史记录；后文旧定时／关机安排均已取消）

### 冻结候选 R6

- [冻结 jar](<../build/milestones/steps13-r6/skyboxblock-1.0.2-test.5.jar>) SHA-256：`96f0f0aa23f294aaa3fee40cc5ee7e54eb58ef7effef60a8415c80d8537681f1`；同目录 sources jar 保留，不随后续构建覆盖。
- [普通接缝实机](<../build/render-regression/steps13-seams-itrp-fsr-r2/run-result.json>) 8 场景、50.62s、0 ERROR；[独立检查](<../build/reports/steps13-seams-itrp-fsr-r2.json>) **118/118**。
- [完整扫描配对报告](<../build/reports/steps1-seamscan-pair-r1.json>) **1158/1158，PASS_WITH_SOURCE_REVIEW**；正/负各64 stage，每stage实际覆盖23种jitter、16个亚像素phase、flat/fold、1280×800与1786×1053。正组2427实际root render帧、144.92s、0 ERROR、raw holes=0、final mismatch=0；旧判据仍在折角拒绘，来源标记使最终颜色正确。负组同时启旧端点与禁用来源标记，产生真实微斜1px断续暗线；不是人工平移图。
- 主线已亲自查看 [独立天空源](<../build/render-regression/steps1-seamscan-fixed-r1/results/seamscan/s048-f001985-first-anomaly-source.png>)、[修后最终图](<../build/render-regression/steps1-seamscan-fixed-r1/results/seamscan/s048-f001985-first-anomaly-final.png>)、[旧路径暗线图](<../build/render-regression/steps1-seamscan-negative-r1/results/seamscan/s048-f001977-first-anomaly-final.png>)和[误差聚合图](<../build/render-regression/steps1-seamscan-negative-r1/results/seamscan/stage-048-final-error-max.png>)。私有源的候选暗点来自云几何边缘/纹理等像素特征，没有旧路径那条横贯画面的微斜虚线；修后天空区域与私有源逐像素一致。此为人工source review记录，仍保留原自动review标志。
- 第1步可以收口为：端点精度加固 + 真正低/高分辨率折角深度来源修复。未靠扩大faces或全局depth tolerance；全包最终三环境P0回归仍须在最后候选重跑。

### 已查明并修正的夹具/构建问题

- 上文“关闭旧AP即可”的临时判断已被运行证据推翻：R5启动 @WrapMethod 缺refmap而失败。读取已验收test.4实际jar证实它使用自动 [refmap](<../tools/inspect-sky-hooks.py>)。最终恢复 `useLegacyMixinAp=true` 并显式注册 main 与 portalFixture 两个源集，正式生成各自refmap；R6已真实启动/完成上述GPU回归，无硬编码method alias。
- 维度 R4 17 场景正常退出但[世界覆盖检查](<../build/reports/steps2-dimensions-vanilla-r4-world.json>) **187/209**、[时间/内容检查](<../build/reports/steps2-dimensions-vanilla-r4-time.json>) **108/121**：新维度 /fill 在区块尚未加载时失败，导致11张空几何截图；时间策略本身的字段断言通过，但绝不把这次退出0说成维度验收完成。已改为服务器线程加载四个目标区块、完成后明确 `execute in` 布置，再执行场景专用时间回调、最后开始等待。
- 真无主模组、无光影门户 [R2 基线](<../build/portal-fixture/steps3-baseline-no-shader-r2/run-result.json>) 已9场景/66.42s完成，实际keyUp穿越与返回均服务器确认；保留4条IP chunk loading ERROR，无致命错误。已查看[正视图](<../build/portal-fixture/steps3-baseline-no-shader-r2/results/01-single-front.png>)。这组早期harness没有新石柱/双投影字段，不冒充最终三组验收。
- 首次IP启动阻塞来自 `initialScreenShown=false` 的4页初始介绍，QuickPlay还未进入WorldOpenFlows；仅新夹具预设true，用户配置未动。实验世界界面仅在显式owned新夹具选择继续。

### R7/R8 集成与当前后台工作

- [R7主构建](<../build/steps14-test5-build-r7.log>)15s通过，数学 **315,029/315,029**；[独立harness构建](<../build/steps3-portal-harness-build-r6.log>)10s通过。这不等于新透明/门户图像已通过。
- 已集成精确ITRP桥：主世界面写合法neutral MRT（LAND1、oct法线），composite40前把自生成线性候选背景写到真实flip input colortex6，仍保留旧最终opaque精确色恢复，透明前景由post-translucent depth挡住恢复而保留包合成。不能直接跳过整视图最终恢复，否则会回归opaque颜色一致性。
- 桥HDR仍是 `pow(2.2) × Gain(默认1)`，需按真实图像校准；未知pack不启aux。生产无world RGB capture。逐视图pre/post世界图只在独立harness的owned + captureScene 事务中启用。
- 透明夹具已51场景：24 sky-on +24 sky-off（white 3D posts / 无后景 × none/glass/water × none/pillar/hand/both）+3真实移除；`tick freeze`冻结水的scheduled flow，保持已知AABB，客户端tick/截图继续。每组强制保存pre图、真实手depth mask，原有first-person持物/选择框验收另保留。
- 新门户harness统一加入远端stone柱、fixtureGeometry、同帧skyViews与私有世界/显示天空参考；在destroyProgress独立读取Iris captured矩阵与custom缓存，区分IP raw gate projection与ITRP实际world projection。standalone无主类编译依赖。
- 两个工程子个体均用用户指定 `xyjunfun1 / gpt-6.1-sol`，已交付独立检查器：透明 **21/21** 合成控制、门户 **30/30**、扫描 **10/10**。它们是工具自测，不是实机结果。最终审计需另派全新GPT6.1SOL，尚未开始。
- 当前唯一仍相关后台job：`pwsh-35a70b85717b4660`。它串行构建R8、harness，冻结到 `build/milestones/steps14-r8/`，再运行：`steps2-dimensions-vanilla-r5` → `steps4-transparency-itrp-fsr-r1` → `steps4-transparency-vanilla-r1` → `steps3-fresh-{baseline,empty,sky}-no-shader-r1`。先收job输出/各run-result，再跑独立oracles；任一失败保留报告并查原因，不凭退出0放行。
- 当前目标：`goal-975c0926-6aa3-42c6-99d4-72388bab1b66` revision2，phase=active、最新自动提醒回合读取activation=disarmed，48轮上限；勿在自动提醒回合反复调用需要直接人类请求的resume。原始完成前持续执行指令仍有效，主会话定时提醒负责衔接。最终需完成三环境完整回归、同jar负对照、包审计/身份复核、文档/截图/新归档/交付、独立审计与整改复审，最后才执行关机。

## 工具记录

- `git status` 确认该项目不是 Git 工作树；历史恢复依赖只增不删的快照。
- 当前技能目录无 Minecraft 开发匹配项；使用已有源码/CLI/实机回归工具。
- 当前另有用户 Minecraft 窗口运行；实施/测试期间不停止其进程。
- 用户追加指令：一名 `xyjunfun2 / Deepseek-V4.1-Flash` 仅负责提醒；完成后另派 GPT6.1SOL 独立审计，整改/复审确认通过、交付落盘后关闭计算机。持久目标已更新（48轮上限）。
- 主提醒 ID：`schedule-8820c2c6-89dc-4ad4-b334-dd77672d8893`，每600秒。提醒个体 ID：`6c7dc893-c3ca-4278-be56-686248018a93`。子会话没有定时器权限，改由主会话定时唤醒该个体，使其只发送简短提醒；不让其开发/测试/审计。关机前删除主提醒并通知该个体停止。
