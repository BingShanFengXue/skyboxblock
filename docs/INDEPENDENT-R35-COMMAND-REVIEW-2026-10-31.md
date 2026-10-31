# R35 原版天空盒环境属性：独立命令与配置审计

- 日期：2026-10-31（Asia/Shanghai）。
- 审计范围：`SkyEnvironment`、`SkyboxConfig`、`SkyboxCommands` 及相关真实 Java 回归；十五项环境覆盖、默认 `auto`、旧云命令兼容。
- 工作区：`E:\DSHworkspace\DSHminecraft`；已读根 `AGENTS.md`、`docs/LESSONS.md`，项目下无附加 `AGENTS.md`。
- 能力选择：已阅读会话 available_skills/catalog；当前任务不匹配 Office 或 ACL 修复技能，使用宿主 read/edit/write、send_message；不运行 Gradle，不操作用户游戏实例。
- 初始授权只新增本报告；主代理随后明确扩展授权，独占新增 `src/test/java/dev/yg/skyboxblock/client/SkyEnvironmentBoundaryRegression.java`。未修改生产代码、作者原有测试或其他人的文档。
- 已知且作者正在修的颜色截位、namespace 查表、final 初始化、MC 初始化依赖、未定义 context、FloatArgumentType 等问题不重复计为本审计新发现。

## 1. 当前结论与证据层次

**本报告记录源码审计与真实 Java/Brigadier 验证；不将静态字符串匹配、Python 重译公式或编译成功当作游戏验收。**

**最终结论：命令、注册表及配置边界审计通过。** 独立读取真实 JavaExec 结果：[边界回归报告](<../build/reports/r35-environment-boundary-regression.txt>) 为 **1005 checks、16 cases、0 failures**；统一构建日志显示 `compileJava`、`compileTestJava`、相关 Java 回归以及 `build` 完成，**BUILD SUCCESSFUL in 43s**。四项新问题及其修复过程中的键格式断链均已解除，无现存 P1/P2 命令边界阻断；仅剩 unknown WARN 重复前缀这一非阻断显示瑕疵（见 §5）。

构建由主代理统一执行；本审计没有运行 Gradle、重复启动 javac 或操作游戏实例。早先 javac 类路径过期不作为最终失败证据，`dumpCp` 已在这次统一构建中执行。**该结论不代表实机或 GPU 渲染验证通过。**

本审计已尽早向主代理回传新发现。尝试直发作者时，宿主返回 `belongs to another parent session`，随后改由主代理转交；没有冒称作者已收到直达消息。

## 2. 新发现与最小修法

### I-01：reset 后配置别名复活；set 可能被旧 alias 遮蔽

- 优先级：P1，配置行为错误。
- 初始代码：`SkyboxConfig.resetEnvironment` 只移除 `environment.visual/<id>`；而 `SkyEnvironment.fromEntries` 接受短 ID、完整 ID、`minecraft:` 完整 ID，注册表还接受 `minecraft:` 短 ID。
- 实际后果：文件中已有 `environment.sky_color` 或转义后的 `environment.minecraft:visual/sky_color` 时，执行 reset 仍会重新加载这个值；set 加入 canonical 后，原先 alias 仍可能生效。
- 最小修法：set/reset 先找出 suffix canonical 到同一属性的所有 `environment.*` 键，统一删除；set 再只写 canonical 键。其他属性及未知键保持原状。
- 最终状态：**已修并实测通过**。已复读 `SkyEnvironment.aliasedKeys` 与 Config set/reset 调用；真实 `aliasReset` / `aliasSet` 用例均 PASS。
- 测试：`aliasReset`、`aliasSet`，使用真实 Properties 文件，逐一从三种指令 ID 形式清除/设置四种可读键形式，验证其他未知键保留。

### I-02：canonical 提前归并会丢失同层优先级；JVM 必须分层处理

- 优先级：P1，读取不确定性 / 覆盖优先级错误。
- 初始实现：file 原始键和 JVM 原始键混在一个 Map 中，仅原字符串相同才覆盖。JVM alias 未必压过文件 canonical；同一属性多 alias 受 Properties 枚举顺序影响。
- 审计中间修复：Config 遍历 `file.stringPropertyNames()` 时立即 `entries.put(canonicalKey(name), value)`。这仍有问题：原键身份已经丢失，随后 `SkyEnvironment.canonicalEntries` 即使有 alias rank，也恢复不了谁原本是 canonical。
- 具体例子：同一文件同时有 `environment.sky_color=#112233` 与 `environment.visual/sky_color=#ABCDEF`；canonical 应确定性胜出，不能看枚举顺序。canonical 值为 `auto` 时也必须屏蔽 alias，不能“复活”。
- 最小修法：文件原始键先单独 canonicalize，JVM 原始键也先单独 canonicalize；两个层内各自确定 canonical > namespaced-full > short 的一致规则，再以 JVM 层覆盖 file 层。交给 `fromEntries` 时保持它要求的键格式。
- 测试：`canonicalCollisions` 同时覆盖 LinkedHashMap 两种插入顺序和真实 Properties 加载；`jvmAliases` 覆盖三种 JVM 形式压过文件 canonical；`invalidJvm` 覆盖多 JVM alias 同层冲突、canonical auto 屏蔽以及非法 JVM 值落回 auto。
- 非法值口径：按当前合同，JVM 的显式非法值/auto 应视为该属性 auto，不能偷偷启用较低层文件中的显式覆盖；本测试据此断言。
- 第二次修复复审又发现键格式断链：Config 分层 canonicalize 后得到 bare `visual/<id>` Map，直接交给 `fromEntries`；后者再次调用只接受 `environment.` 前缀的 `canonicalEntries`，将这些已知键全过滤掉，使配置始终 EMPTY。已即时回报主代理。独立 `allRoutes` 通过真实 Config/live snapshot 可直接发现该错误，纯注册表 raw-prefix 测试发现不了。
- 最终状态：**已修并实测通过**。已独立复读 Config 的 `rawMerged` 交接：已知 bare id 补回 `environment.`，unknown 原带前缀不重复添加；真实 `allRoutes`、`canonicalCollisions`、`jvmAliases`、`invalidJvm`、`reload` 全部 PASS。没有以“作者 ready”代替运行证据。

### I-03：值 TAB 给出非法值、缺月相、未按前缀过滤

- 优先级：P2，命令可发现性与交互质量。
- 初始实现：NUMBER 统一建议 `0`、`-64`；这使 `star_brightness` 获得非法的 `-64`。PHASE 只建议 `full_moon`、`new_moon`；`auto` 只给 NUMBER；建议提供器直接 suggest 所有例子，未过滤 remaining prefix。
- 最小修法：按属性范围生成可解析的示例，八个月相全部列出；所有类型均可建议 `auto`；按 `builder.getRemaining()` 的前缀过滤。
- 测试：`completions` 获取真实 dispatcher suggestions，再将建议值逐个交给同一真实 dispatcher 执行，验证每项都合法；额外验证八个月相、`has_sky_occluder t` 只返回 `t...`、全部 ID 三形式补全。
- 测试不以“建议列表非空”代替有效性验证。
- 最终状态：**已修并实测通过**。NUMBER 示例已按范围分流，完整 `MOON_PHASES`、所有 kind 的 `auto` 与 remaining 前缀过滤已落地；真实 `completions` 用例获取建议并逐个 execute 全部 PASS。

### I-04：损坏 Properties 的 Unicode 转义异常逃出 reload/get

- 优先级：P1，持久化健壮性。
- 代码根因：`SkyboxConfig.get()` 只捕获 `IOException`；`Properties.load` 碰到如 `\\u12GZ` 的畸形转义会抛 `IllegalArgumentException`。
- 后果：读取/重载损坏文件会逃出异常，而不是按合同 WARN + auto。因为 load 可以在异常前已加载部分条目，单加 catch 还可能留下半份配置。
- 最小修法：读文件同时捕获 `IOException | IllegalArgumentException`，日志后清空本次加载的 Properties 或丢弃整个局部对象，再构造默认快照；写路径读取失败应返回失败，不覆盖损坏原文件。
- 测试：`malformedFile` 在隔离目录写入畸形 Properties 转义，直接调用真实 Config 加载，要求无环境覆盖；`malformedWrites` 直接执行 set/reset/reset-all/旧云写命令，要求返回0、有错误反馈、原文件逐字节不变、原实例及 snapshot 不变。
- 最终状态：**已修并实测通过**。已复读 get 同时捕获 IOException / IllegalArgumentException 并 clear 部分加载的 Properties；update 将解析失败包装 IOException，保留原文件；两个真实损坏文件 case 全部 PASS。
- 修复过程还发现两处 Java 注释中的裸反斜杠-u 会触发 Unicode 词法预处理错误；已由主线改成普通 `Unicode escape` 描述，统一 `compileJava` / `compileTestJava` 实编译通过。此为已解除的修复回归，不列为现存阻断。

## 3. 初始新增测试的缺口与本轮补齐

原来的 `SkyCommandRegression.execute` 在 `parse.getExceptions()` 非空或 reader 有剩余输入时直接返回，没有真正调用 `dispatcher.execute`。这可以验证“测试 helper 拒绝了 extra token”，但不等价于验证“真实 dispatcher 未先执行再报错”。

原来的 `SkyEnvironmentRegression.configurationRegression` 仅以 Map 模拟往返，完全未触达 `SkyboxConfig` 的文件写入、JVM 优先、失败事务边界。科学计数只测试了溢出值，不覆盖合法 E 记数或最小正 float。

新增独立 `SkyEnvironmentBoundaryRegression`：

| 分组 | 真实执行的检查 |
|---|---|
| 注册表 | 精确十五项；所有 ID 三形式；ARGB `#FFFFFFFF`、`0x80ABCDEF`、透明零保留位模式 |
| 命令路由 | 15 属性 × 3 ID 形式 × `environment`/`env`，直接 dispatcher set/get/reset，核对 live snapshot |
| 数字 | `1e-3`、`+1.28E+2`、`Float.MIN_VALUE` 直接执行并 reload 后比 float bits；numeric auto |
| 非法值 | 三个 end 的 0、负零、负数、NaN、±Infinity、溢出/下溢；负 brightness/大于1；超4096云距 |
| extra token | set、auto、reset-one、reset-all、reload、三种旧云指令末尾多 token；要求 byte-identical 文件、同 Config 实例、同 snapshot、无成功反馈 |
| 持久化 | 未知键和 Unicode 值、旧云高/淡出键保留；reset all 只清环境；无 `.properties.tmp` 残留 |
| alias / JVM | alias reset/set、canonical 冲突、file/JVM 分层优先、invalid/auto 回退、JVM同层冲突 |
| reload | 外部修改隔离配置后直接执行 reload，新 snapshot 立即可见 |
| 写失败 | 先加载有效配置，再把其自建父目录换成普通文件迫使 IOException；set/reset/旧云写都返回0且实例与值保持 |
| 文件损坏 | 畸形 Unicode Properties 转义不逃逸，整体按 auto |
| TAB | 所有类型 auto、建议可执行、八月相、前缀过滤、全部 ID 形式 |

测试自身调用 Minecraft Bootstrap，仅初始化类/注册表；使用 dynamic Proxy 捕获 FabricClientCommandSource，不创建游戏窗口/GL 上下文。配置仅位于本测试新建的 `build/r35-environment-boundary-*` 目录，finally 恢复 JVM 环境属性、清除 testFile、清理自己的目录。

## 4. 已由源码确认的正确设计

- 注册表覆盖十五项；`environment().has/number/color/flag/moonPhase` 是只读 snapshot 查询。EMPTY 为所有查询返回调用者 fallback，避免把 auto 当零。
- RGB 颜色读取时补 alpha=FF；ARGB 使用 `Long.parseLong` 后 cast int，保留高位与负 int 位模式，canonical 文本用 `#`。
- 自定义 EnvironmentValueArgumentType 按单 token 读取并先调用真实 `SkyEnvironment.parse`，科学计数不再被 Brigadier 的 float reader 截断；extra token 留给 dispatcher 检查。
- 类型校验集中于注册表：有限 float、三个 end 严格 >0、brightness 0..1、cloud_end ≤4096、flag 仅 true/false、八月相。
- `SkyEnvironmentMath.fogEnd` 对公共 fog_end 夹到96，显式 sky_end 直接优先而非 min；cloud_end 独立读取，不与 fog_end 取 min。
- 主线后续明确 `star_brightness` 显式值也是最终覆盖：已复读 `VanillaSkyScene`，仅 auto 分支乘 `weatherAlpha=1-rain`；显式分支不乘雨系数。太阳/月亮的 `weatherAlpha` 保持不变。此处采用主线新合同，不沿用早期规格里“显式星亮度仍乘天气”的旧描述；GPU/实机验证由主线提供。
- `cloudHeight` 用 float `H-.33F` 后喂旧 cloudRelativeY；192.33F→名义192F 的逐位等价有独立 Java 渲染数学回归。
- 旧 cloudheight/clouddistance/cloudfade 的 literal 和范围仍在；旧键与新键分别写入，reset all 不清旧键。
- `update` 先操作局部 Properties、写同目录 temp、move 成功后才 reload；在此前发生的 IO 失败不应触碰既有内存 snapshot。边界测试对此做直接执行断言。

## 5. 保证边界与待验收项

1. “原子写”必须限定：当前 `ATOMIC_MOVE` 不支持时降级为普通 `REPLACE_EXISTING`；此 fallback 并非跨崩溃的原子承诺。本轮测试验证正常持久化及失败前的内存不污染，不模拟断电。
2. `Files.move` 成功后的 reload/缓存失效属于提交后阶段；不要把提交后异常误报为“文件没有改动”。恶意外部并发改文件、进程崩溃不是本轮单线程命令回归的证明范围。
3. 雾距、月角0=正上方、alpha=0不画云、遮挡体渐隐、其他八种天空盒逐像素不变，均还需要主线的真实渲染/实机证据。本审计只引用已经读到的数学实现，不冒领游戏验收。
4. 主线规格文档含若干历史措辞（18项、额外 namespace、颜色多种输入等）；本轮以主代理明确锁定的十五项/三形式合同为准，不擅自扩大生产需求或覆盖其他审计者文档。
5. **非阻断 P3 显示瑕疵**：unknown 环境键的 WARN 可能显示 `environment.environment.future/unknown`，因为 unknown 键原已带前缀而消息又补一次。只影响日志标签，不影响 unknown 键保存、忽略或 reset-all 边界，真实 `preservation` case 已通过。可按“已有前缀则不再补”作小修；不作为命令/config 通过的阻断。

## 6. 源码与测试证据索引

- 注册表与 immutable snapshot：[SkyEnvironment.java](<../src/main/java/dev/yg/skyboxblock/SkyEnvironment.java>)。
- 配置读取、分层优先、原子保存：[SkyboxConfig.java](<../src/main/java/dev/yg/skyboxblock/client/SkyboxConfig.java>)。
- 真实命令树和 TAB：[SkyboxCommands.java](<../src/main/java/dev/yg/skyboxblock/client/SkyboxCommands.java>)。
- 本次独立边界测试：[SkyEnvironmentBoundaryRegression.java](<../src/test/java/dev/yg/skyboxblock/client/SkyEnvironmentBoundaryRegression.java>)。
- 作者原有解析/命令测试：[SkyEnvironmentRegression.java](<../src/test/java/dev/yg/skyboxblock/client/SkyEnvironmentRegression.java>)、[SkyCommandRegression.java](<../src/test/java/dev/yg/skyboxblock/client/SkyCommandRegression.java>)。
- 数学合同：[SkyEnvironmentMath.java](<../src/main/java/dev/yg/skyboxblock/client/SkyEnvironmentMath.java>)、[SkyEnvironmentRenderRegression.java](<../src/test/java/dev/yg/skyboxblock/client/SkyEnvironmentRenderRegression.java>)。

## 7. 真实执行证据与复现

- 独立 main：`dev.yg.skyboxblock.client.SkyEnvironmentBoundaryRegression [report.txt]`，已由主线 JavaExec `environmentBoundaryTest` 运行。
- [独立边界回归报告](<../build/reports/r35-environment-boundary-regression.txt#L1-L17>) 已用 read 工具逐行读取：**1005 checks、16 cases、0 failures**，十六组全部 PASS。
- [主线统一构建日志](<../build/r35-build-r4.log>)：该日志被 read 工具识别为 binary，任务和计数改由只读 grep 工具提取，不对日志做转码或改写。

| 任务 / 输出 | 实际结果 | 日志行 |
|---|---|---|
| `compileJava` / `compileTestJava` | 统一构建完成，无编译阻断 | 7、22、171 |
| `environmentBoundaryTest` | 1005 checks，16 cases，0 failures | 28、82 |
| `environmentCommandTest` | SkyCommandRegression: 619 checks OK | 102–103 |
| `environmentRenderTest` | 42257 render-environment assertions PASS | 105–106 |
| `environmentTest` | SkyEnvironmentRegression: 279 checks OK | 108–109 |
| `renderMathTest` | 389385 assertions PASS | 111、135 |
| `build` / `dumpCp` | BUILD SUCCESSFUL in 43s；19 actionable，17 executed，2 up-to-date | 158、160、171–172 |

本审计自身没有运行 Gradle，也没有与作者并发启动编译；以上是主线实际执行、由本审计独立读取的证据。编译、真实 Brigadier、真实配置文件与 JVM 边界的结论已闭环；没有游戏实例 / GPU 像素验证结论。