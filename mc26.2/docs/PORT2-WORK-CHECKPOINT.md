# port.2 工作检查点（已完成）

> **最终状态：2026-11-03 02:28 后全套验收完成。** `verified-port2` 全部 suite PASS；210 正常场景、7 同 jar 负控，最终 SHA256=`45c256728cdac6a75f3e71b1a1b6af1335de53e753702aa3dee79aa450c5daa8`。产物与最终报告均已封装，overview已read_image检查，文档链接与字节哈希再次核验通过，owned Minecraft JVM剩余0。下文均为执行阶段历史，不能按旧候选状态重复施工。


## 最新进度（2026-11-03 02:06 之后）

- ITRP scene桥已实机三段日志验证并通过白/黑玻璃背景A/B，Sky响应0.63805；其它三包generic矩阵87场景通过。
- 暗天空proxy已移植并实机激活。首轮未激活根因是Iris `ImageBinding.update` 对2D也以 layered=true绑定；本机javap已证，validator改成真实flag并额外校验底层TextureType=TEXTURE_2D。
- `complete2-port2-meter` on实测black ratio=0.94450、galaxy=0.94561；proxy active和finally restore日志均已确认。原无proxy基线约1.247/1.244。same-jar negative后半仍由job `pwsh-365f8ad0ba344248`收尾，必须收集。
- 所有子代理已完成并停止写源。当前source含scene桥+meter，唯一Gradle最后日志`build/audit-r2/meter-image-owner-build.log`。Native回归当前11+19+16+64=110（其中13结构），非全为GPU项。
- 最终全量逐case、自动关闭客户端驱动已启动：job **`pwsh-5a58e0397b264615`**，命令`tools/final-acceptance.py --prefix verified-port2 --jar build/libs/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar --disable-meter-flag skyboxblock.fixture.disableItrpDarkMeter --suite all`。
- 最终case：verified-port2-core(145)、plain(14+rejoin1)、dh-off(4)、dh-itrp(4)、glass(各4pack报告)、glass-dh、meter、meter-negative。同一jar hash必须一致；`tools/summarize-validation.py --prefix verified-port2`已改为严格检查全部case、负控差异并重新封装output/overview。
- 只有最终全量完成后运行summarizer、read_image overview、更新PORT2-VALIDATION候选状态/新hash/新数值/新增桥与meter说明。旧output仍是14fa候选，不能直接present。
- 源码冻结，除非实机发现明确问题，不再额外扩展功能。收集所有相关jobs，确认owned JVM关闭，校验文档链接与jar包，present并完成goal。

---

以下为上一阶段快照（历史）：

时间：2026-11-03 01:36 之后（Asia/Shanghai）。**任务仍在执行，不是最终交付。**

## 当前状态

- 本地化、独立 GL 场景、完整逆 VP、网格精度、256 格距离、原生月纹/nearest、彩云 ARGB/透明/淡出、服务器当前时间单位均已修。
- 第一份核心候选 jar SHA256 `14fadc54cf177f1b91ab9dede9cdf7bd557de22c6656cf44545adaedfe69adee` 已通过 145 个五环境场景、14 个 plain 高级场景、1 个重连、8 个 DH smoke，共 168。输出目录保留该候选，但汇总已标记 awaiting ITRP bridge，不得当最终版本发布。
- 原生 GL 回归原有 11+19=30 项；新 ITRP scene-input GL3.3 回归再有 16 项。
- 玻璃补测发现显式 HDR scene-input 及 MRT 路径未复用原主线，现已新增 `ItrpSceneBridge`、`SkyIrisCompositeMixin`、`itrp_background` 与 window 的辅助输出。严格锁 ITRP0.8.31 zip hash `334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8` / Iris1.11.4。
- 桥实机已激活三段校验日志：material MRT/plan、actual colortex6 input、before-composite40 injection。新桥 no-DH 玻璃响应 0.63805，旧 jar 0.26176；普通 concrete 响应约 0.15572 不变。旧 DH+Iris 量化响应 0.25154，不能以“有方差”或“完全无传输”概括；新桥在真实 HDR 背景质量上对照白/黑。
- `ITRP0.8.31` 无 deferred，solid 在 composite25；water composite40 读写 colortex6，glass composite50 采 colortex6。新桥在实际 indexed draw 前注入，不照搬旧 renderQuad hook。

## 正在进行

- 主线 pwsh job `pwsh-9e170e529e0d4ffa`：在 `itrp-bridge-on-r2` 同一实例从已激活 ITRP 切到 Complementary/Photon/BSL，跑 generic 矩阵，检查附件/路由清理。
- 主线 pwsh job `pwsh-fa61fdec1f7c4a32`：`dark-meter-before-proxy-r2`，现有桥但尚无 meter-only proxy，测自然天空→黑→银河→自然的固定砂岩/手部曝光。
- GPT6.1-sol 子代理 `97415288-0bff-4c96-8efe-74b1732414ef` 正在移植真实 1.21.1 `SkyExposureMeter`，只改 meter sampler 的曝光 atlas tile proxy，不改场景/bloom RGB，不写曝光图，不禁自动曝光。允许新增类/mixin/shader、WindowRenderer只读 accessor/释放、ItrpSceneBridge meter 输入校验。禁止触碰 SkyGlScope、SkySceneState/VanillaScene、现有MRT/HDR场景路径及depth/透明判据。主线不要与这些接口写入并发。
- 子代理不能运行 Gradle/游戏；唯一编译、真实客户端与截图由主线执行。

## 必须保持的已验证修复

- `SkyGlScope`：真实 MC 是 OpenGL3.3（core40=false），blend indexed 用 core/ARB/global 分支；foreign XOR/非ADD equation 被 clean 中和并精确恢复。不要再无 guard 用 core GL40+。
- Frame geometry 用 `SkyRenderMath.relativeGridVertex(block,corner,double camera)`，不是 per-block float pose；BER viewDistance=256。
- final 只以 material owner + 当前 world depth 判定，**不要恢复 triangle-depth==owner-depth 限制**。
- TransparentGuard 不全局关；真正折射背景应在 pack scene input 修复，不在 final 硬涂玻璃。
- 测试矩阵必须采真实 native render 参数/Iris Gbuffer uniforms，bob 可在 P 或 V；仅 Camera getter 会误判 Iris。`Fixture262LevelMixin` 在真实 opaque stage 独立采样。
- 连续性夹具清负 z 草唇。旧 8 组差异全属真实近地形/TAA silhouette；纯天空场景 15 组 8,420,790 像素 max0。不要调宽 tolerance“修”它。
- 全局时间命令为 `skyboxblock environment time ...`；检查 command outcome，不只看截图方差。

## 最终剩余工作

1. 收集 generic 矩阵与 meter baseline 输出；等待 meter 子代理交接。
2. 唯一 Gradle build/regAll + 原生 GL/compute 回归；实际触发 meter-proxy 日志与 enabled/fixture-disabled 同 jar A/B，检查固定前景曝光。
3. 最终二进制重跑必要矩阵/玻璃/HDR/DH/高级，所有被宣称最终通过的 case preparation.modSha256 必须一致。
4. 更新 `tools/summarize-validation.py` 最终 case 名及新测试数；不要把旧失败/无效夹具记录覆盖成通过。
5. 更新 `docs/PORT2-VALIDATION.md` 的候选状态、哈希、数值及范围；历史 DELIVERY 已标 superseded。
6. 重新封装 output、读取最终 overview PNG、停止所有 owned test JVM、检查相关 background jobs、present jar/验证记录/截图，然后完成持久 goal。

## 工具约束

- CWD `<工作区根>`；项目 `skybox-mod-26.2`，基线 `skybox-mod`。不改原 PCL 实例。
- Java25：`<JDK 25 安装目录>`；GRADLE_USER_HOME 指向本项目 `.gradle-home`。
- Python：`<DSH 运行时自带的 python.exe>`。系统 python 是 Store 占位符。
- 源码/JSON/Markdown 只用 read/edit/write，避免 PowerShell 编码损坏。新 ps1 需 BOM。
- 测试窗口必须隐藏、无焦点、不抓鼠标；夹具已用 GLFW hints + MouseHandler guard 实现，每张 JSON 有检查。
- 不能用 UUID `send_message` 联系 ephemeral subagent（runtime 返回 active teammate not found）；子代理能 `send_message target=lead`。通过完整初始 brief 与共享证据文件协调，勿为传话改对方源码。
- 不建 git commit、不删 artifacts 历史。所有旧候选/日志/截图保留。
