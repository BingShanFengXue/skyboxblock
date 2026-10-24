# R30：独立只读终审输入

状态：动态DH最后同包复跑正在完成；达到必要门禁后提交用户指定的官方 `deepseek-official/deepseek-flash`（显示名 DeepSeek-V4.1-Flash）只读终审。审计个体不得改文件、构建、启动游戏、派发个体或修改配置，结果以文本返回，由主执行者存档。

## 用户最终范围

- 完成动态Distant Horizons兼容的实现与有限真实GPU验证；修复当前截图中的中央玻璃抖动，增加方块内部云层距离指令。
- **2026-10-23用户明确跳过性能／资源长测。** 本轮未执行该专项，不允许把现有短时对象账本当长期无泄漏或性能承诺。
- 其他P0严格恢复四红／历史缺绘、全面光影版本分辨率覆盖、自然动态全面验收、部署存档生命周期均暂缓；传送门继续停止。
- 审计后做包、支持声明、已知限制和归档收口，不替换用户已安装JAR、不创建Git提交、不上传。

## 当前冻结候选

- `build/libs/skyboxblock-1.0.2-test.9-r30.8.jar`，SHA-256 `50dd054fded07bd8721916cdfe8d97263eb100148dd33f5dee343bbc3a06619d`。
- 独立探针 `build/libs/skybox-validation-probe-r30.6.jar`，SHA-256 `e710a159bdc9418a2a8bd7e0af0673c05c7595f194b591f98f3ea7609d94aa0f`。探针不打入生产主包。
- 旧test.8冻结哈希 `07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c`，所有旧失败和各候选均保留。
- 实际环境：MC1.21.1、Fabric API0.116.15、Iris1.8.1、Sodium0.6.7、DH3.3.3/API7.2.0、ITRP Alpha0.8.31；已核验光影ZIP SHA-256 `334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8`。

## 重点源码

1. `src/main/java/dev/yg/skyboxblock/client/DistantSkyDepth.java`：当前root、原生reverse-Z／Iris拥有者纹理、投影线性距离；断开清level引用。
2. `SkyWorldRenderer.java`：世界不透明阶段、HDR输入和最终恢复均保留更近DH；native复用同帧全分辨率私有天空；无原版天空RGB抓取。
3. `SkyGlState.java`：真实纹理恢复移到program／Iris逻辑状态之后；`SkyCoverage.java`：真实早期深度指纹。
4. `ItrpSkyBridge.java`：精确包哈希与Iris版本、pipeline/target/storage证明；HDR背景写入也尊重DH深度。
5. `SkyTemporalEdgeMask.java` 与 `sky_edge_history.*`：只积累保守前景深度所有权，不积累世界RGB；相机／投影／几何变化清空，frame stamp循环、jitter周期、显存分配／释放值得审查。已知这是有范围的静态玻璃稳定证据，不宣称所有移动透明体均覆盖。
6. `SkyboxConfig.java`、`SkyboxCommands.java`、`SkyCloudRange.java`、`SkyVanillaClouds.java`、`mixin/CloudBuildRangeMixin.java`：原子保存、范围与JVM优先级、私有网格／裁剪、线程局部恢复。
7. `mixin/DistantBlockIgnoreMixin.java`：仅天空块加入DH渲染忽略集合，不禁DH、不开世界只读、不冻结生成。
8. `src/main/resources/assets/skyboxblock/shaders/core/{world_sky,sky_material_background,sky_resolve,sky_edge_history}.*` 与 mixins JSON；`build.gradle`：DH API compileOnly不打包第三方类，独立r30Probe sourceSet。

以上Java路径在同一个 `client/` 目录下，除另标明的mixin。请按实际源码审查，不以本说明覆盖观察事实。

## 证据入口

- `build/reports/r30-candidate8-lod-order-g208-native-r1-check.json`：当前候选Iris原生受控真实LOD前后遮挡26/26；近金墙非天空比与物理金色比均1，前方天空同帧参考MAE/p990。
- `build/reports/r30-candidate8-final-r1-validation/lod-fsr2-ordering.json`：当前候选FSR真实LOD前后遮挡26/26。
- 同目录 `lod-fsr2-evidence.json`：当前候选146/146输入／深度／状态证据。
- 同目录 `dynamic-{dh,native,fsr2}-evidence.json`：当前候选13段×三环境，包含生成、移动512/1024、返回、更新/移除、玻璃、重载和尺寸变化；未产生的报告应明确标pending，不能推定通过。
- `build/reports/r30-candidate1-dynamic-r1-matrix/summary.json`：较早候选39段、962/962，不能冒充当前候选重跑。
- `build/reports/r30-candidate2-cloud-commands-vanilla-r1-check.json`：云指令8实机场景70/70，配置／GPU／保存一致，负例与reset/reload。
- `build/reports/r30-candidate{1,4,5}-glass-fsr2-temporal-diagnostic-r1.json`：90帧×四物理对照，23种真实jitter；C1中央位移std0.3011px，C4仍0.3554px未放行；C5降到0.00504px、颜色std4.633→0.326。图像、每帧姿态/jitter、NoSky/无玻璃对照保留；不把编码/读回开销当性能结果。
- `build/reports/r30-render-math-candidate8-r1.txt`：339384断言通过；`r30-candidate8-build-r1.log`。
- `build/render-regression/r30-candidate8-final-r1-performance-native/SCOPE-SKIPPED-BY-USER.md` 是明确未执行停点，不是真实fixture。

## 审计输出

请以严重性排序给出实际问题、证据文件/行号、影响和最小修正建议。区分已证明、合理风险、范围外未测；用户明确跳过项不能报告成通过，也不要仅因跳过项存在就伪称当前代码有缺陷。检查声明是否超过实测、是否存在已知严重问题阻碍当前测试版交付。尤其检查只读审计边界、生产包不含探针/第三方类、错误保留与旧P0状态。即使认为通过，也说明范围和未证明的事项。
