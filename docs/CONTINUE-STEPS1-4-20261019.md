# 第1—4步持续执行交接（R23门户工程已结束，冻结后独审）

## 最新状态（覆盖以下历史运行中说明）

- normal门户主批次及native/FSR remote-direct均已完成；basic两份无主模组基线也已完成。所有后台输出已收取，无待续GPU批次。
- normal无光影559/559；两组ITRP normal各587/605红，真正无主模组baseline普通条纹失败。两份direct协议通过但两侧几何均红，不支持唯一portal路径归因。
- basic真实IrisCompatibilityPortalRenderer、持久配置、实际Iris/pack/FSR均验证；两组92/96，protocol=true、baselineGeometry=false，四项普通条纹红。进程exit0不是图像验收。
- 最新basic专用报告：[r2](<../build/reports/ip-r23-basic-baseline-assessment-20261019-r2.json>)；[实机结论及边界](<PORTAL-REMOTE-DIRECT-2026-10-19.md>)。r1配置键读取错误原报告保留，r2明确实际字段映射与IP丢弃旧别名，不改原几何判据。
- 按人类范围结束工程：不做basic empty/sky、不改生产、不扩大未知缺绘/恢复问题；下一步冻结checkpoint证据后派**全新GPT6.1SOL独立审计**。这次是允许红项的工程检查点审计，不是伪造release-ready或替代正式release门禁。
- R23 Native core高墙缺绘与full settled三项恢复红继续保留；R24诊断回放未复现，不认定已修。正式负控/完整release矩阵仍待，releaseAccepted=false。
- 自动goal仍paused/disarmed；此前resume已被工具明确拦下，不重复调用。保活、关机仍取消。不操作用户游戏与原配置。

---

以下为历史交接，所有“正在运行”按最新状态覆盖。

## 最新人类指令：恢复执行到IP兼容阶段，工程结束后再独立审计

2026-10-19用户直接要求：“该轮增加执行到‘沉浸式传送门兼容测试与尝试兼容’后结束，在这之后审计。” 新范围是限定门户兼容实测/必要尝试，到此结束本轮工程，再新GPT6.1SOL独立审计；不是无限整改旧高墙/全包恢复缺口，也不伪造release通过。

当前GPU唯一主批次 **pwsh-8d94ab8af7d6400d** 正在串行：`ip-r23-{no-shader,iris-native,iris-fsr}-{baseline,empty,sky}`各9场，固定R23 main9f1903…、harness612f57…、settleTicks300；各组三变体后自动checker保存`build/reports/ip-r23-{ENV}.json`；然后native/fsr baseline的remote-direct各一组+专用checker。旧所有GPU已结束，主线先核无其它owned Java再开此批次。不要重复开游戏。

已派1个GPT6.1SOL工程triage **81432bdc-f188-4c92-a18c-7f990d597bd8**只读旧/新证据，核IP compatibilityRenderMode真实语义和最多1—2项可证伪兼容尝试，不改主线、不GPU/Gradle，不是最终审计。最终新审计须另开fresh child，在门户工程结束、资料冻结后才派。

Goal文本已更新 **revision7**，工具拒绝模型resume界面paused状态（明确返回“the model cannot resume a paused goal; the user must resume it”），已停止尝试，不绕过。当前依直接人类请求在此轮执行；不可把自动goal标成armed。原审计延期/暂停说明以下为历史，以本段新边界为准。保活/关机仍取消。

## 上一人类指令（历史）：审计延后，仅报告状态

用户于2026-10-19明确“审计延后，报告任务状态”。当前goal已编辑为revision6，仍paused/disarmed；此次不自动恢复开发、不开新测试、不启动最终审计。完整当前状态见[TASK-STATUS](<TASK-STATUS-AUDIT-DEFERRED-2026-10-19.md>)。以下旧revision5快照是历史，不按它重新resume。

## 当前暂停状态（goal自动Round1读取）

- `get_goal`实际返回 **revision5 / phase=paused / activation=disarmed / roundsStarted1**。不要根据自动goal_round文本重新resume；恢复要求直接人类指令。
- 主GPU `pwsh-028b73c1311c491b`已完成，全模组core113：world774/774、time149/149、alignment114/114，590.28s，fatal/renderThread0，第三方ERROR原样保留。
- `pwsh-0fd5ca6cc7324937`的R24前50场诊断已经完成：185.42s，ERROR/fatal/renderThread0。**原缺绘本次未复现，不将诊断正常等同修复。** 五个dim-overworld都是110blocks/1572verts，raw高墙Sky/BE均110、backing156、floor169；sameRendererLevel=true；每场220次回调覆盖110唯一pos，strictReason全部strict-eligible；两个section11都是built/flags3，实际entity/geometry列表均在，compiled BE50/60，pending/task均无。没有证据支持直接上线position-hint resolver或替换registry。
- [完整只读汇总](<../build/reports/test5-r24-high-wall-readonly-summary.json>)已新建并绑定输入SHA；原R23 core失败继续保留，根因未解决。暂停后未再启动新游戏、未改生产渲染或阈值。
- 当前所有主线后台任务均已结束并收取输出。诊断目录`build/render-regression/test5-r24-exact-prefix-high-wall`，旧PID35632已非当前操作依据。恢复需直接人类指令，当前goal仍paused，不自动resume。
- 最终独立审计、IP/负控、最终矩阵和归档仍未完成；不标goal完成，不创建passed=true真实验收。

## 此前最重要状态（2026-10-19 21:35前后）

**不要宣布最终完成，Native core发现了真实缺绘，正在精准诊断。**

### 当前两个后台任务（GPU串行，勿重复启动）
- 主批次 **pwsh-028b73c1311c491b** 仍运行，已到`test5-r23-core-full-fsr`（最后一组113）。当前主外层是 **powershell.exe PID6580，UTC启动2026-10-19T11:48:26.6463566Z**，不是pwsh名；不要操作宿主进程。
- 下一组 **pwsh-0fd5ca6cc7324937** 在`Wait-Process`等待该精确外层身份退出，才启动 **test5-r24-exact-prefix-high-wall**：从失败R23 native log提取默认原先首50场（一直到dim-overworld-jump-day），使用R24只读高墙诊断包，完整保留资源重载/旧高墙清空/低位alignment与seam前序；timeout1200。这是诊断，不是重复直到绿。
- 旧排队`pwsh-d17437ed44d24c13`、`pwsh-c7b3fd1f438c4568`只在等待时已取消；后者R24构建已完成，勿恢复或重复建同目录。没有第二个游戏并行。

### 已完成真实R23结果
- vanilla settled53 **1228/1228 PASS**，862.05s，运行错误0；core113 **world817/817 / time149/149 / alignment114/114**，423.31s，运行错误0；[vanilla合成位置负控](<../build/reports/test5-r23-vanilla-position-controls.json>)20px线条8/8、60px图像16/16全部检出，metadata原字节一致。
- iris-native settled53 **[1228/1228 PASS](<../build/reports/test5-r23-settled-iris-native-strict.json>)**，2873.34s，运行错误0。时间长是外部窗口高度800→794导致精确jitter等待，不是pose或渲染停。只读[现场](<../build/reports/test5-r23-native-stall-live-r1.txt>)显示pose/count早已满足、viewport1280×794而target jitter为800时值。
- Native窗口已用独立附加的diagnostic agent调用**现有fixture.window回1280×800并把诊断pose计时/帧数归零**，不改shader/jitter/阈值/世界/gameTime/类字节码；[修复说明](<../build/reports/test5-r23-native-window-restored-r1.txt>)。全部51正式结果仍1280×800，重满15s/300帧后才采样。`build/diagnostics/native-stall-r1/`含只读agent/修复agent/attacher源码、manifest与jar；最终需pin这些干预证据给终审，不隐瞒。未触用户其它MC进程。无需进一步restore/maximize操作，数据已完成。
- **iris-native core113 FAIL**：[world754/764](<../build/reports/test5-r23-core-iris-native-world.json>)、[time139/144](<../build/reports/test5-r23-core-iris-native-time.json>)，alignment114/114；475.53s，runtime0。首次5个dim-overworld皆blocks0/verts0/depth0，server/client BlockState=110Sky/156背墙/169floor；这条不含raw BE数。before图只有底floor，普通砂岩背墙也缺，不能只归entity identity guard。
- **full/FSR settled53 FAIL**：[1225/1228](<../build/reports/test5-r23-settled-full-fsr-strict.json>)仅三个removed-world-restored：none MAE.7597/p998、glass.5569/6、water.5986/7；其它材料/阻后方/物理几何等全部过。929.31s，fatal/renderThread0，47条第三方ERROR保留。尚无本轮两份真正neverSky settled控制，不得直接waive；原bounded caps不改。full core113还在主批次尾段，先收真实结果。

### R24诊断包：不是生产修复
- [R24 main](<../build/milestones/steps14-r24-high-diagnostic/skyboxblock-1.0.2-test.5.jar>) SHA `213d0e73b2251e512ad783c7138c4483f0efb2aec284c098cc241a9f085822d5`；同目录sources保留。[build](<../build/steps14-test5-build-r24-high-diagnostic.log>)339030/339030。
- 仅扩展`BlockCollectionDiagnostics`：高墙110位置raw Sky/BE身份、highBacking156/highFloor169、renderer vs Minecraft level identity；collect incoming/raw identity/removed/current state/strictReason/wouldResolve；Sodium增加section11（Sky目标(-1,11,0)=50、(0,11,0)=60）的compiled名单身份/移除状态、实际entity/geometry列表。
- 已实际assert **SkyWorldRenderer/Frame、SkyGlState、VanillaSkyScene、SkyCloudMesh、SkyVanillaClouds六production class与R23字节完全一致**。所以此包只用诊断，不把当前源码扩展后的诊断类错记为R23已包内容。
- 等queued prefix结束后先解析最新JSON的`blockCollection.atEnd.client.highSkyBlocks/highSkyEntities/highBacking/highFloor`、`collectAttempts`以及`sodium.sections`y11；若callbacks旧110/current raw live110/wouldResolve110→可做incoming仅position hint→当前live的最小生产兼容分支。必须保持same frame level、当前stateSky、rawMap SkyBE/!removed/samelevel/samepos，seen后用live.shouldRenderFace；AIR仍不画。**不要在lookup前因incomingRemoved退出**。但无callback/compiled list空或section不在实际列表时，这个resolver不解决根因，更不能解释普通backing缺失；按实际证据选下一步，别立即大改registry/强刷/扩大容差。
- `99e7d6ff-ba82-47b4-95b3-9fe9937873d1`完成只读核对，已闲置。确认R23 frozen strict guard的确先drop removed/identity，5帧447+仍未绘，源码/bytecode未漂移。无生产fix先入。

### 工程工具已绿（不是独审）
- 49的扩展严格selftest先243/243，追加类型后260/274红报告保留，实际发现14项bool/float宽松接受；主线已窄修typed整数+合法OR，**[274/274](<../build/reports/verify-test5-acceptance-strict-integer-r3.json>) PASS**。别再改其严格期望/放宽caps。
- [archive临时契约20/20](<../build/reports/archive-test5-temporary-contract-r2.json>)，真实release zip尚未创建；首次TEMP遗漏Gradle metadata的[setup失败](<../build/reports/archive-test5-temporary-contract-r1-setup-failure.json>)保留，只修fixture scaffold。
- [R23新来源核对](<../build/reports/source-provenance-r23-20261019.json>)：实际参考树Java16/177且git HEAD分别415197…/8bfecd…已重核；exact/pixel/long Java全部0，自有两PNG生成器匹配。仅本地固定提交事实，不证明历史从未派生/联网许可当前状态；原Literal MIT归属与Shadered灵感保留，binary notices不改（否则需重建）。
- 独立harness已冻结在R23目录，仍SHA612f57…；source/meta.Bing6512保持。最终新session GPT6.1SOL独审/矩阵/归档仍未开始。

### 下一立即动作
1. 收主GPU尾部full core113原始结果；随后queued R24 prefix自动启动，勿另开游戏。
2. 读R24目标section11/current rawBE/callback真实状态与before图，先定位Native core缺绘；根据证据窄修，再对最终实际同一jar补完整矩阵。没有修复前别用仅透明1228/1228替代核心失败。
3. full仅3removed噪声仍需最终同jar两份真正neverSky settled53控制+原bounded companion；若有新生产包，不能拿R23不同hash控制放行。
4. 最终seam64正负、oldIris状态与桥负例、其余两环境位置合成负控、IP三环境三变体/remote-direct及全新独审/append-only归档仍待。详见下文旧待办但以本段版本/进程状态为准。

---

## R23此前交接：后文早期状态已被上述进度覆盖

**用户明确承认R22柱缺块是自己误点（“缺一块是我不小心点了一下”），不是新渲染回归。** 原坏图/808报告保留，工程49此前“未证历史点击”的只读报告是当时边界，已被人类新增事实补足。R23已添加只在owned fixture进程的START_CLIENT_TICK输入隔离：KeyMapping.releaseAll清queuedclick和held，resetToggleKeys清toggle；不动其它Minecraft进程/用户options。不要再花时间做攻击注入归因。

- R23 frozen [main](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5.jar>) SHA `9f1903e75e3b66cd4cad147a11c9d74cddd9a2d249eaf6ada103b6419ad64129`， [sources](<../build/milestones/steps14-r23-settled/skyboxblock-1.0.2-test.5-sources.jar>) SHA `5cbf48ed5f910e00f54acdb0034524c41a09a849a42461595d5b9d6d186fe2ab`。
- [R23 build](<../build/steps14-test5-build-r23-settled.log>) math339030/339030，[包](<../build/reports/skybox-test5-package-r23-settled.json>) **58/58**。当前新核心候选是R23，不是R22。
- [R23 vanilla bugs](<../build/render-regression/test5-r23-input-vanilla-bugs/run-result.json>)实际19场68.3s，ERROR/fatal/renderThread0；[world285/285](<../build/reports/test5-r23-input-vanilla-bugs-world.json>)。三处resized-hidden/side/restored，server/client柱均6块，inputGuardTicks736/773/820，depthChange0/2741/2987；主线已看修复后的完整柱真实图，不改renderer/容差来掩盖误点。
- 新[FixtureClientControl](<../src/main/java/dev/yg/skyboxblock/client/FixtureClientControl.java>)还在显式settled fixture下仅包装sprite-name匹配minecraft:block/water_still与water_flow的ticker。实时named-owned-world guard为真首次uploadFirstFrame（**动画声明的首frame，不保证物理index0**），随后skip delegate；guardfalse正常delegate且重置latch，close始终delegate，资源reload新wrapper。**不是生产修复/不是仅水墙volume；整个owned fixture共享这两个sprite固定相位，用户游戏默认无此flag完全不变。**
- 实际MC字节码已证原全局tick freeze也跳过TextureManager.tick，恢复worldtick同时恢复water sprite循环，不能用“等待15秒”让循环动画收敛。water wrapper调用点原atlas已绑定、真实x/y正确；Sodium已查路径无返回ticker强转风险，但仍以实机为准。
- 新`--settled-transparency`固定协议：2个真正无Sky warmup（warmup-empty-front/oblique，各300client ticks），再**完整原51正式测量**；每正式场景实际target eye pose稳定后必须 **15真实秒+300个root rendered frames同时满足**。依据ITRP临界阻尼w=.75、history .97和已核Iris时间单位，预先固定，不按候选转绿调参；frameTimeCounter仍原冻结，frameCounter仍live。所有HF/LP/corr/gain/RMS/geometry/≥2bands/caps仍原值。
- `--settled-transparency`仅完整suite transparency（不ad-hoc --scenes或旧--history-warmup）；manifest suite transparency/requestedScenes=[]、settledTransparency=true、freezeWaterTextures=true、fixedHistoryWarmupScenes两名、waterSpritePhase first-declared-resource-frame、settlementPolicy15k/300；实际run53。旧`--history-warmup`是R22可用的短2alignment+51控制，**不是新的settled协议，当前不使用**。
- R23首个正式NoSky empty已验证metadata：observedMilliseconds15745、observedFrames1333，inputGuardTicks1015、observed/frozen water sprites2/2、waterSpriteTicks2030；实机无类型转换/贴图hook加载错误。warning里的未安装Iris类正常可选mixin，不当fatal。

### 当前已完成的R23无光影环境（真实结果，不是TEMP）

- [settled53 runtime](<../build/render-regression/test5-r23-settled-vanilla/run-result.json>)862.05s，全部runtime错误0；[严格透明1228/1228](<../build/reports/test5-r23-settled-vanilla-strict.json>)，无waiver；solid marker的水HF可弱于3，但实际LP几何正控/evidenceValid=true，参考作为自身leak-positive真实检出，未篡改legacy flag。
- [core113 runtime](<../build/render-regression/test5-r23-core-vanilla/run-result.json>)423.31s，全部runtime错误0；[world817/817](<../build/reports/test5-r23-core-vanilla-world.json>)、[time149/149](<../build/reports/test5-r23-core-vanilla-time.json>)、[alignment114/114](<../build/reports/test5-r23-core-vanilla-alignment.json>)。
- [archive TEMP contracts](<../build/reports/archive-test5-temporary-contract-r2.json>)20/20；首次临时scaffold少必需build.gradle的setup失败[原记录](<../build/reports/archive-test5-temporary-contract-r1-setup-failure.json>)保留，仅修测试scaffold，archiver未放宽。**尚未创建真实release归档。**
- 独立harness已copy到R23同轮新空目标，hash仍612f57b1…，源/旧冻结文件未覆盖。

### 当前唯一GPU主批次（重要）

**`pwsh-028b73c1311c491b` 正在运行**，不要再启动第二个游戏。当前进行Iris-native的settled53/后续core113，前面的vanilla两组已完成。PID11624已是旧进程，绝不凭旧PID操作。按序每环境：
1. vanilla：settled53（**--marker-pattern solid**保持原无shader强几何参考），strict checker；然后`test5-r23-core-vanilla`默认all113→world/time/alignment。
2. iris-native：settled53，FSR2_SCALE=-1，精确jitter来自R19；然后core113。
3. full-fsr：settled53（striped），显式DH新生成disabled/renderer DEFAULT+DH只读观察，FSR2=2匹配jitter；然后core113。
每settled timeout5400s，核心2400s。保留原strict失败，不以runtime0当通过。若三个settled strict真实全部通过，不需要恢复校准来waive历史all-suite跨天气/维度history漂移。

**已取消的旧 `pwsh-b02246b1ee35420f` 不要恢复**：R22三环境主113已收完，尾部未预热controls无效噪声/不再关键。原R22 full/native world774/774 time149/149 align114/114；两组strict1225/1228，仅3removed；R22vanilla world808/817九项是用户误点造成实际柱缺口，time149/149 align114/114、trans1223/1228。原始全部保留。

### 当前工程child与Gate状态

- `49eeb337-77cc-4c37-b63d-55ab57e10c1a`当前已完成并闲置：仅改 **tools/verify-test5-acceptance-selftest.py**，扩至274控；243控首轮通过，加入类型控后260/274红报告真实发现14项Gate宽松接受bool/float，原失败保留。主线已在child停止后将4时间/frames、input/sprite counters窄修为严格整数，保留static/frozen合法OR缺省分支；[同严格测试r3为274/274 PASS](<../build/reports/verify-test5-acceptance-strict-integer-r3.json>)。不要再修改其严格期望为接受bool/float。这是工程自测，不是最终独审。
- Gate本体主线已新增：core world/time/alignment必须默认all113且runtime.scenes113；settled trans实际53+固定2warmup+全51top JSON、15k/300实际记录、inputGuardTicks、实际水sprite控制；所有原caps不改。当前最终新增测试尚在跑；此前40/40只是旧pin，别外推当前已过。
- Empirical reader两文件已冻结：[226/226新自测](<../build/reports/marker-reference-repeat-sol-v3-dh-selftest-r1.json>)，reader SHA00e8fc1f91f9784d202764be8629fa2f627d419ca07e768aec3739abac722f49，selftest83207b22c35ccb3b2de3db667b558a9ca86de4ab774134cd6eff44c2c008ff58；old checker c0ef1e2a4314afe3031c1b6e16b8f548a78ca1974e180e1eaa73ce3a7f0ce8a8 /settings9293…fc21仍未改。当前R23新freezeWaterTextures/settlementPolicy尚未加入reader signature；**若未来实际使用该诊断比较R23，先补实际sprite/settlement语义一致性与自测，不要混R22 live sprites与R23 static。** 最终raw strict不依赖empirical，也不waive rawstrict。
- `99e7d6ff-ba82-47b4-95b3-9fe9937873d1`已闲置，已交准确shader/水ticker字节码证据，无待编辑源码。任何工程child都不是最后全新终审。

### 为什么独立settled透明而不是给all-suite历史噪声绿灯

R22 all曾跨rain/clear及多维度，Iris真实drynessHalfLife bug使干燥20s半衰期，rain=0不等wetness=0；freshNoSky first地下→天光还有pixelData w=.75雾阻尼约12.31s到snap；SkyImage .97按frames，首native只有16帧而removed208，原70/90ticks不保证同状态。R22 controlled full NoSky-a none rawMAE1.620/LPMAE1.254，超过原稳定caps；候选raw.904/LP.272虽更小也不能用坏control放行。原测量[明确passed=false](<../build/reports/test5-r22-controlled-restoration-measurement-only.json>)，不放宽1.4control/1.5candidate/rawp9910/LP1,2,4。R23新53固定静态相位+实际秒/frames是预声明实验修正，不用候选拟合ROI或阈值。

## 后续仍必须完成（以R23同包为准）

1. 收当前GPU 6组输出，检查core三环境及settled3组strict/实际settlement记录；不复跑已完成标签，不破坏证据。
2. 同R23 seam64stage正负+manual source review；旧Iris-state负例、透明bridge负例（相同settled/sprite/marker条件），三环境20px线/60px图合成位移负控。旧R6/R9不继承最终身份。
3. 同R23主+R20相同独立harness跑门户noShader/native/FSR三变体，各9；新baseline remote-direct native/FSR；实际1627block探针。保留真实失败支持边界，不承诺完整IP/DH。
4. 最终文档/README/真实matrix/hash归属仍待更新；旧R18/R22表是历史。source/harness另pin。human authorBing6512，dev.yg不改，许可/AI归属保留。
5. Gate当前新selftests冻结后构真实matrix，--subject-digest ready后**另派全新GPT6.1SOL独立终审**，整改复审；producer session仍81f…，goal revision4 active/armed。所有工程child不算。
6. 真acceptance passed=true后只增归档、entry CRC/hash、present最终产物。**不安排或执行保活/关机。**

---

# R22历史交接（以下不是当前运行批次）

## 人类任务与固定约束

继续完成拼缝→全维度Overworld外观/时钟→Immersive Portals基础实验→水/染色玻璃遮挡；保留test.4已验收状态修复/物品选框/Y192云。作者Bing6512，技术namespace保留dev.yg；保留Literal MIT/Shadered灵感及AI协作归属。最终完成后必须另派全新GPT6.1SOL独立终审、整改复审。保活/自动关机已取消，绝不恢复。用户要求实际阶段进度。

- 工作目录：`E:\DSHworkspace\DSHminecraft`；项目`skybox-mod`。无git，不commit/push，不删历史/artifacts，不Gradle clean。
- 不改用户实例/存档/光影配置，不停止用户Minecraft。仅新owned fixture；GPU/Gradle仅主线串行，工程child只CPU/javac。
- Python：随 DSH 运行时自带（位于其安装目录内），路径随安装位置变化，不写死。
- 当前工具仅普通filesystem/pwsh/subagent等；不用Office/Excel工具。已查catalog仅ACL/Office，无匹配技能。
- 文本read/edit/write；glob找路径、grep找内容；PowerShell只执行命令，不读写源码/MD/JSON。审批关闭，不设sandbox_permissions/justification。
- Goal `goal-975c0926-6aa3-42c6-99d4-72388bab1b66` **revision4, active, armed, max48**。已用准确仅action/id/revision的resume成功；早先带不适用可选字段的重复失败已停止，勿重复。
- 真实producer `$env:DSH_SESSION_ID`：`session-81f3568b-50f7-4432-926d-59e3a6bb6c1f`。终审必须独立新session；工程child不算。

## 当前冻结候选 R22-fluid

- [main](<../build/milestones/steps14-r22-fluid/skyboxblock-1.0.2-test.5.jar>) SHA256 `70aceaaa6d1ba2264965868278ab42db0276d1e8b700282ba101c25f3f5b100e`。
- [sources](<../build/milestones/steps14-r22-fluid/skyboxblock-1.0.2-test.5-sources.jar>) SHA256 `5894a2d01e598918862312dfdd4ff1a799a29516a01c740834449274181968b4`。
- [build](<../build/steps14-test5-build-r22-fluid.log>)数学339030/339030；[package](<../build/reports/skybox-test5-package-r22-fluid.json>) **55/55**。
- 独立portal harness仍是R20/R16相同字节：[harness](<../build/milestones/steps14-r20/skybox-portal-fixture-1.0.0.jar>) SHA `612f57b10655cee0ed98ac0bf27941c6a802ebadc6735e7a7af0a228b4965cc7`。可最终copy到R22新空目标，勿改旧文件。

## 当前唯一主GPU长批次（不要重复启动）

**job `pwsh-b02246b1ee35420f` 正在运行**。按序：

1. `test5-r22-never-sky-full-controlled-a`，全模组/ITRP/FSR2=2，51个真正neverSky，**343/343物理参考灵敏度已经通过**，runtime289.05s，fatal/render-thread0，第三方ERROR原样保留。
2. **当前 `test5-r22-final-full-fsr`**，113默认all，PID23560（仅历史辅助信号，勿盲杀PID）。full **`--dh-worldgen disabled`** + `--distant-terrain-diagnostics`；完成后自动world/time/alignment/transparency checker各自独占log/report。
3. `test5-r22-final-iris-native`，113、原生FSR2_SCALE=-1、精确从R19测出的jitter。
4. `test5-r22-final-vanilla`，113。
5. `test5-r22-never-sky-full-controlled-b`，51，受控生成+DH只读观察。
6. `test5-r22-never-sky-native-a/b`，各51。

批次保留checker失败并继续下一环境；runtime失败或第一NoSky物理灵敏度失败会中止。先job_output收取；之后检查真实reports，不把exit0当图像通过。

### 受控DH策略必须明示，不是完整动态LOD支持

`tools/render-regression.py --dh-worldgen instance|disabled` 默认instance，只有显式disabled才在**新隔离复制的配置**中把`common.worldGenerator.generatorPlan`改DISABLED；**rendererMode仍DEFAULT、DH模组仍安装/渲染，不改源用户配置**。manifest记录original/actual plan、两hash与limitation。最终full组使用此受控近世界条件，不能称动态LOD已验收。旧动态组的失败/漂移保留，不能放宽原hardcaps。

`verify-test5-acceptance.py`会实际pin/解析新fixture的DH TOML，确认真实DISABLED+DEFAULT并加入`controlled-near-world-not-dynamic-DH-validation` warning；最后新独审必须review。对应TEMP门禁36/36通过，含关闭renderer冒充受控生成会失败。此自测不是终审。

## 已完成的关键定位与修复

### 1. 旧BE真正生命周期缺陷：R18同包53/53

[因果报告](<../build/reports/test5-r18-entity-lifetime-causal-r2.json>)：旧110个高墙BE已removed=true、cached state仍sky、当前world为空气、raw map身份不符，Sodium旧渲染列表仍回调它们。seen前检查isRemoved、getChunkNow当前SKY_BLOCK和raw BE identity。正向110→0→88→0；旧负分支110→110→198→110，桥接flag也随旧引用误启。

R16首次7/35坏态本轮R17只读未复现（35/35，BE四节12/16/3/4，late0），不称唯一根因已证。R18夹具启动前设视距12并等初始chunk/目标空节条件，避免首fill同时6→12重启；不allChanged，不改shouldRenderOffScreen，不强刷目标。

### 2. 物理参考增强，不降阈值

旧黑柱水HF只有2.5—2.95<3，evidenceValid=false不能说零泄漏。固定四根同一1×8柱，改沿Y黑白concrete交替；AABB/相机/全部旧HF/LP/corr/gain/RMS/≥2band门槛不变，保留`--marker-pattern solid`旧刺激。

R19原生NeverSky 199/199灵敏度；[原生SkyOn严格](<../build/reports/test5-r19-marker-native-candidate-transparency.json>) **1074/1074 PASS、无waiver**。水HF现在7.90—9.25。主线已read_image实际水/玻璃图，柱消失、透明效果在。不同构件不混为R22最终成绩。

### 3. 全局tick freeze破坏IP watcher夹具

R19full trueNeverSky51实际server几何正确而client existingChunks=0，物理100/199失败。IP字节码：END_SERVER_TICK每次调用tracking，但玩家刷新按playerId%13==gameTime%13，purge/generation++按gameTime%13==0。该坏run playerId1、冻结gameTime6240=13*480，持续purge不刷新，符合startup4后client0。getChunkNow已证虚分派到IP真实map，不是读旧vanilla storage。尚未直接capture每个卸载packet，别过度归因。

R22新增默认关闭`RegressionFluidControl`+common `RegressionFluidMixin.tickFluid`：仅regression+freezeFluidTicks+严格canonical owned world root+fixture名+overworld+精确水墙x-2..2/y140..147/z2的WATER/FLOWING_WATER scheduled回调取消；只在当前fluid仍匹配才计数。其它world/server/chunk/network tick保持live。坏路径/权限guard失败返回false；无client依赖。旧`--negative-global-fluid-freeze`独立JVM保留。

[真实同包增强因果报告](<../build/reports/test5-r22-fluid-causal-pair-r2.json>) **43/43 PASS**：normal服务端时间6293→6368→6446→6520→6620，client始终4chunk；water40、outside0、地板357、黑白16+16均双侧一致；negative时间6241恒定，phase1这轮client仍4（不能强求相位相关失效每次复现）。实际installed jars/pack已rehash。normal/negative各5场103.0/112.44s，fatal/render-thread0。

### 4. 新物理协议v3

每透明场景记录actualServer/ClientGeometry（v3共12项，含waterOutside0、旧高位sky0及raw BE0）、实际两侧gameTime、fluidFreezeMode、suppressedFixtureWaterTicks。一次读现存chunk/raw BE，不等目标count、不强刷。

NeverSky控制每个root frame额外assert accepted geometry/material/bridge/resolve全0，metadata记录neverSkyObservedRootFrames，非仅截图检查。

原checker新v3硬规则核验only-water freeze、时间字段、真实water counter、整套server clock有推进；像素阈值原值。[自测脚本](<../tools/check-transparency-regression-selftest.py>) **54/54 PASS**；release gate **36/36 TEMP PASS**。

## DH只读诊断与边界

[FixtureDistantTerrain](<../src/main/java/dev/yg/skyboxblock/client/FixtureDistantTerrain.java>)默认先returnnull；显式regression+distantTerrainDiagnostics+owned/path/world/root同level才读当前Iris DH depth/all/opaque；只读float32LE+sha/row curves/mask，SkyGlState+PBO/pack布局恢复；不setter，不freeze，不取消draw，不读world color。

工程child初版javac30源0错、CPU11/11；主线R22已修实际DH.getColumnRenderBuffers返回数组而非Iterable的观察分支，后续actual状态应sampled。R20旧版记录了enabledSections但last-built unavailable，不能填0。

[R20原动态NoSky诊断](<../build/reports/test5-r20-never-sky-full-fsr-a-drift.json>) **62/62有效诊断**，不是restore验收；所有root从未私绘，原始全图noneMAE3.10/p9927仍变，DH depth覆盖从22682→150898、128216像素coverage flip；glass/water深度bit也变。支持几何动态候选，但旧last-built不可用、history变化仍未排除。不以此扩大LP最大4或waive rawstrict。

## 蓝边问题已回答

[已呈现且亲自查看的BLOOM对照](<screenshots/test5-blue-halo-bloom-ab-20261019.png>)：单关SUNLIGHT_RIMS宽晕保留，单关BLOOM宽晕明显消失。主要为该组泛光，不是方块lightLevel/emission；边界额外放大未独立证明，实际TAA jitter不同不作同帧像素A/B。

## 工程child与当前CPU工作

- `99e7d6ff-ba82-47b4-95b3-9fe9937873d1` GPT6.1SOL工程，已闲置；只读定位/新增诊断，不是最终独审。
- **`49eeb337-77cc-4c37-b63d-55ab57e10c1a` 当前工程在做**：仅拥有`tools/check-marker-reference-repeat.py`及其selftest。加v2/v3真实双侧geometry/water clock策略验证与失败自测，保持所有阈值/old flags；最后冻结hash并新独占报告。不得碰主src/oldchecker/gate/GPU/Gradle。
- 原empirical工具87/87已两轮，最新旧pin报告[87/87](<../build/reports/marker-reference-repeat-current-oracle-selftest-r3.json>)。R16native参考4/4可采、full0/4，但candidate旧110BE污染都NOT_VERIFIED。工具只诊断，**不用于waive raw strict**。
- 当前无其它主线CPU job未收取；所有老job输出已收。唯一GPU长批次b022仍需持续收里程碑。

## 仍需执行，不能宣称任务完成

1. 收R22当前三环境113+真实controls的全部原始world/time/alignment/transparency。full受控条件必须明示。若removed有strict噪声，只用原bounded native-repeat companion且caps原样；任何物理/泄漏失败不能waive。
2. 同最终R22 jar跑64stage seam正负（23jitter×16phase，两尺寸）+人工源图review；旧Iris-state负例、完整透明bridge负例（striped），三环境20px线条/60px图像位置负控。保留原failed/raw。
3. R22主包+R20相同harness跑门户noShader/native/FSR，各baseline/empty/sky9场，再baseline remote-direct native/FSR，实际block1627双侧probe。原旧无shader559/559、Iris有失败不能继承新包；支持按真实report写limited/unsupported，不预设。
4. 主harness CLI：`tools/portal-regression.py --variant baseline|empty|sky --jar ... --harness-jar ... --label ...`；shader原生需`--shader ... --shader-option FSR2_SCALE=-1`，FSR=2保留instance。checker：`tools/check-portal-regression.py --baseline ROOT --empty ROOT --sky ROOT --output NEW.json`。
5. remote-direct：`portal-regression.py --mode remote-direct --variant baseline --direct-from 新portalbaselineROOT --direct-scene 01-single-front --harness-jar ... --label ...`；同姿态真实camera不强制matrix；专用checker见tools/check-portal-remote-direct.py。
6. 完整source/package/hash、所有实际第三方ERROR归因、当前docs/README仍有历史R18表需更新成最终真实身份/边界；license来源固定commit/本地证据与核查限度保留。
7. 建真实matrix（`verify-test5-acceptance.py --example`只模板，不当通过），pin jar/source/harness、actual fixtures和report、position派生JSON重hash、selected images、sourceReview。`--subject-digest`全部工程就绪才ready。
8. **另派全新GPT6.1SOL**（xyjunfun1/gpt-6.1-sol，用户已指定无需重复问；先listmodels核对），独立终审不是已有工程child；report绑定实际subject+3包hash+不同reviewerSession，修问题后复审。
9. 真acceptance passed=true后`archive-test5.py`只增不删创建源码/工具/证据/许可归档、逐entryCRC/hash，再present最终jar/source/report/图。未完成前不假称通过。

## 下一立即动作

收`pwsh-b02246b1ee35420f`，先看`test5-r22-final-full-fsr`进度；让其串行后续native/vanilla/controls，不重启重复GPU。并等49工程child冻结工具。趁GPU可独立准备最终seam/portal/position调用，但不要并行游戏或Gradle。
