# R30：动态DH、云层距离与玻璃稳定收口

开始日期2026-10-22，收口日期2026-10-23（Asia/Shanghai）。**已完成用户指定范围的动态DH实现、同包复跑和官方DeepSeek-V4.1-Flash只读终审，按有范围测试版交付。资源长测明确跳过；旧全局发布门禁不改绿。未上传、未替换用户安装包。**

## 1. 用户最终范围

- 完成动态Distant Horizons有范围兼容；新增方块内部云层距离指令；修复当前FSR玻璃中央抖动。
- **性能／资源长测按用户2026-10-23指令跳过。** 现有短时GL对象记录只用于验证状态／释放与诊断，不写成长期无泄漏或性能承诺。
- 旧严格恢复四红、历史偶发缺绘、全面光影／版本／分辨率覆盖、自然动态全面验收、部署／存档生命周期继续暂缓；传送门继续停止。

## 2. 当前候选身份

- [主JAR](<../build/libs/skyboxblock-1.0.2-test.9-r30.8.jar>)：`1.0.2-test.9-r30.8`。
- SHA-256：`50dd054fded07bd8721916cdfe8d97263eb100148dd33f5dee343bbc3a06619d`。
- [当前包核对](<../build/reports/r30-frozen-candidate8-package-r1.json>)：CRC正常，无r30验证探针类，无DH第三方实现类；DH API为compileOnly，不打入主包。
- 独立探针r30.6 SHA-256：`e710a159bdc9418a2a8bd7e0af0673c05c7595f194b591f98f3ea7609d94aa0f`，不是用户安装依赖。
- 用户参考实例、安装JAR、存档及shader ZIP未替换；test.8原包和所有中间候选、原始失败保留。未Git提交、未上传。

## 3. 动态DH实现与证据

实际输入为MC1.21.1/Fabric、DH3.3.3（API7.2.0）；光影路径为Iris1.8.1、Sodium0.6.7、ITRP Alpha0.8.31，原生及FSR2_SCALE=2（实际752×470，输出1280×800）。这是所测版本／配置，不是任意DH、任意光影的通用承诺。

关键实现：

- 不关闭DH、不冻结远景更新；真实生成、活动LOD列表、上传顶点及深度均有采集。
- 仅天空块加入DH renderer ignore集合，避免其隐形模型变成灰色实体LOD，不改变普通地形或世界方块交互。
- 世界不透明、HDR背景输入、最终颜色恢复三处都尊重更近DH地形；按实际投影转换eye-space距离，不直接比较不同投影的raw depth。
- native DH合成后恢复应可见的天空；复用同帧全分辨率私有天空颜色，避免再绘一次云。Iris使用当前pipeline的拥有者纹理，native API投影要求同root事务。
- GL真实纹理绑定最后恢复；断开清空对level的引用；不把世界RGB抓取作为独立天空来源。

当前候选真实前后遮挡：

| 环境 | 证据 | 结果 |
|---|---|---|
| Iris原生，物理金墙G208 | [当前候选26项](<../build/reports/r30-candidate8-lod-order-g208-native-r1-check.json>) | 26/26 |
| Iris FSR2，物理金墙G208 | [当前候选26项](<../build/reports/r30-candidate8-final-r1-validation/lod-fsr2-ordering.json>) | 26/26 |
| FSR输入／状态／深度 | [当前候选证据](<../build/reports/r30-candidate8-final-r1-validation/lod-fsr2-evidence.json>) | 146/146 |

Iris原生更近金墙4409个有效像素全部保持物理金色且不是私有天空；前方天空8245个有效像素与同帧私有参考MAE0／p990。native无光影前后遮挡曾在候选3完成26/26；其版本归属保留，不冒充当前JAR重跑。

较早候选1完成三环境39段、962/962；[原记录](<../build/reports/r30-candidate1-dynamic-r1-matrix/summary.json>)保留。当前候选同样13段×三环境的最终动态复跑已完成，覆盖生成、512/1024移动和返回、远方方块更新／移除、玻璃、资源重载、尺寸变化：DH-only 278/278、Iris原生342/342、FSR2 342/342，共39段962/962，均绑定当前主包／探针，运行ERROR／致命0。[最终范围回执](<../build/reports/r30-user-scope-completion-r1.json>)单列，未用旧成绩填充缺项。

受控LOD-only因果场景会明确省略owned实例的原版chunk绘制、让真实DH绘制金墙。它有两次真正NoSky物理正例和姿态/投影校准，与正常动态矩阵分开，不冒充默认配置。

## 4. 云层距离指令

```mcfunction
/skyboxblock clouddistance 768
/skyboxblock cloudfade 256 768
/skyboxblock clouddistance reset
/skyboxblock reload
```

- 结束距离16–4096格；`clouddistance`自动设开始=end/3，`cloudfade`可分别设置；默认128开始、384结束。
- 立即生效并持久化；不改变外部原版／光影／DH云。私有云网格与远裁剪一起扩展，保持云单元尺寸、UV与速度。
- 候选2原版路径8场景 **70/70**：命令、配置、GPU值、文件保存一致；reset/reload及反向区间负例通过。[说明与明确证据版本](<VANILLA-CLOUD-DISTANCE.md>)。

## 5. 中央玻璃抖动

同一固定相机、同一FSR2配置，实际保留四物理场景各90帧：NoSky玻璃、Sky玻璃、Sky无玻璃、NoSky无玻璃，均覆盖23种真实jitter。未靠关闭FSR、TAA、泛光、曝光换通过。

| 冻结候选／中央玻璃 | 边线位移std中位数 | 像素时间std中位数 |
|---|---:|---:|
| 候选1原问题 | 0.301121px | 4.633228 |
| 候选4只加空间guard，仍未放行 | 0.355398px | 1.737358 |
| 候选5前景所有权历史 | **0.005040px** | **0.325739** |

[原问题](<../build/reports/r30-candidate1-glass-fsr2-temporal-diagnostic-r1.json>)、[未充分修正的C4](<../build/reports/r30-candidate4-glass-fsr2-temporal-diagnostic-r1.json>)、[C5物理对照](<../build/reports/r30-candidate5-glass-fsr2-temporal-diagnostic-r1.json>)全部保留；C5同场景外侧玻璃位移std约0.01817px。

历史只记录保守前景深度所有权，不累计世界颜色。相机／投影／天空几何／尺寸变化即清空，NoSky失效；按实际jitter周期控制有限历史，stamp循环及资源释放有明确路径。移动前景可能存在有限历史保护窗口，固定姿态结论不扩成所有动态透明场景的完整验收。

## 6. 跳过、旧失败与审计

- 用户明确跳过资源专项，未启动长时资源／性能测试。串行调度器在首资源阶段前设[未执行停点](<../build/render-regression/r30-candidate8-final-r1-performance-native/SCOPE-SKIPPED-BY-USER.md>)，不计作真实fixture或通过。
- R29严格透明结果3680/3684及四项恢复红项仍保留；本轮不改旧数值阈值，不把参照预算当严格通过。
- 官方审计路由 `deepseek-official/deepseek-flash`，显示名DeepSeek-V4.1-Flash；仅1个独立个体，实际只读、未改文件或构建。最终净结论0阻断、0中等已证实缺陷；FSR坐标疑点经源代码及真实深度反证被同一审计者撤回。Iris深度内容逐帧时序和native旧oracle归属仍列为限制。[审计原文、更正与范围](<INDEPENDENT-R30-DEEPSEEK-READONLY-2026-10-23.md>)。
- 包内旧建议项不等于全部版本兼容声明。发布与支持范围以本报告实际版本／模式为准；正式分发元数据与审计意见在收口时统一。

## 7. 交付文件

- [已验证主JAR的不可变交付副本](<../../artifacts/snapshots/skyboxblock-1.0.2-test.9-r30.8-20261023.jar>)：与实测冻结包完全同字节，不自动安装。
- [源码／工具／声明快照](<../../artifacts/snapshots/skyboxblock-r30-test9-20261023-final-source.zip>)。
- [当前同包与精选连续帧证据](<../../artifacts/snapshots/skyboxblock-r30-test9-20261023-final-evidence.zip>)：不是所有历史数据的全集，完整原始失败仍在工作区。
- [归档CRC／逐文件哈希与包身份回执](<../build/reports/r30-closeout-archives-r1.json>)。

构建源代码时，默认版本已同步到当前候选；DH API仅编译依赖，可用 `-Pdh_api_jar=<本机DH3.3.3 JAR>` 指定路径。生产包不含DH／Iris／验证探针实现类。冻结test.8不覆盖，归档只新增。

[全过程与原始失败](<DH-PERFORMANCE-R30-WORKLOG-2026-10-22.md>) · [只读审计输入](<R30-READONLY-AUDIT-BRIEF-2026-10-23.md>)
