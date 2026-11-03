# 来源、创作灵感与第三方许可

核查日期：2026-10-19；**2026-10-24 增补第 7 节素材的逐件协议与 CC BY 4.0 署名**。适用对象：当前 `skybox-mod` 源码及后续重新构建的分发包。旧冻结 jar 不因本文增加而自动获得新的包内署名。

## 1. 项目自身

本项目自身代码与原创资源按 [MIT](<LICENSE>) 分发，项目版权声明为 **Copyright (c) 2026 Bing6512**。AI 辅助贡献署名见 [AUTHORS](<AUTHORS.md>)，不额外声称 OpenAI、DeepSeek 或模型路由持有项目版权。

## 2. Literal Sky Block：早期结构参考／派生脉络，保留 MIT 归属

- 作者：**Kristiāns Micītis**。
- 本地实际参考提交：`415197984bbbf941fe40f61755fd25de98c91aad`，分支 `main`。
- [上游仓库](https://github.com/latvian-dev/literal-sky-block)。
- [固定提交的 MIT 许可](https://github.com/latvian-dev/literal-sky-block/blob/415197984bbbf941fe40f61755fd25de98c91aad/LICENSE.txt)。
- 完整上游许可副本：[Literal Sky Block MIT](<LICENSES/Literal-Sky-Block-MIT.txt>)，原版权年份 **2022**。

明确标注的关系：早期“不可见普通方块模型 + 方块实体渲染入口 + 方块面天空投影”的框架参考／派生脉络。现有项目许可证早已保留该上游归属，本轮继续保留，不因后续重写删除它。

当前代码并非整份上游源文件移植：当前 BER 只收集几何，独立生成天空并做世界深度、Iris 状态保护、FSR 和逐视图恢复；上游直接提交 `RenderType` 的实现与当前管线不同。`SkyBlock`、`SkyBlockEntity` 与 BER 的通用 API 结构相似，应区分设计参考、历史派生和当前源文件复制。

本轮自动比对没有发现当前主源码／资源与这份参考树的**完整同字节文件、同像素 PNG，或达到本次检测阈值的长共同 Java 代码块**。这不等于证明历史从未参考或派生；因此没有把已有 MIT 归属撤掉，也没有夸称当前文件逐行来自上游。

## 3. Shadered：标记为创作灵感，不引入其许可到当前原创实现

- 项目作者信息：**Noodlegamer76、Siggystar**（本地构建元数据所列）。
- [上游仓库](https://github.com/Noodlegamer76/Shadered)。
- 本地实际参考：`GLASS-test`，提交 `8bfecd7495ddbabcaee7ce9e9d8bed9b55ffee2a`；该工作树构建元数据为 Minecraft **1.20.1 / Forge**。
- 该参考的 `mod_license=All Rights Reserved`；根许可证却是 Minecraft Forge 的 LGPL 2.1 模板，存在声明口径差异。**本项目不据这份模板宣称 Shadered 整体是 LGPL，更不宣称它是 MIT。**
- 历史已记录的 [1.21.1 分支构建元数据](https://github.com/Noodlegamer76/Shadered/blob/02e330c933f57c857848248f7af0fa2d3a43be45/gradle.properties) 声明 `Attribution-NonCommercial 4.0 International`。本轮该对象未保存在本地、联网核查遇 DNS 错误，因此不把历史记录写成刚重新联网确认的结果。

**当前关系：创作灵感／历史视觉目标对照。** 当前分发代码、shader 和两张 PNG 中没有查到对本地 Shadered 参考文件或 PNG 的直接完整复用；旧多预设已移除。没有将其作者的代码、shader 或贴图改标成本项目原创，也不以本项目 MIT 声明覆盖未来可能引入的 Shadered 内容。

## 4. 当前两张 PNG：本项目生成，非原模组贴图

[贴图生成器](<tools/gen-skybox-textures.py>) 的 `sky_texture()` 可在内存中逐像素复现 [方块贴图](<src/main/resources/assets/skyboxblock/textures/block/sky_block.png>)；其最近邻 128×128 结果逐像素复现 [模组图标](<src/main/resources/assets/skyboxblock/icon.png>)。两者均未与上述两份参考树的 PNG 发生同像素匹配。

这部分标注为**本项目原创生成资源（AI 辅助）**，不是“原模组素材移植”。生成器中的旧虚空函数属于工具历史；当前包没有虚空方块注册／贴图。

## 5. Minecraft、Iris、Immersive Portals、Sodium 与 iterationRP

- Minecraft 的日／月／云纹理和云网格来自**运行时游戏资源及现有方法**；本项目 jar 不再打包分发 Minecraft 的这些原始贴图或游戏 jar。Minecraft 资源不因本项目采用 MIT 就改用 MIT。
- Fabric API、Iris、Sodium、Immersive Portals 等通过依赖/API/运行时适配使用，不把它们的完整实现复制或打包为本项目代码。各项目自身许可继续适用。
- iterationRP Alpha 0.8.31 用于特定版本兼容研究：读取程序／缓冲格式，独立实现互操作代码和标准法线/位打包数学。本项目不打包原光影 zip，也不修改用户原 zip。此关系标注为**兼容性研究参考**，不是把原光影内容改署名。
- 参考树、游戏 jar、光影 zip、测试实例不属于可任意按本项目 MIT 再分发的项目原创内容；正式源码快照只收项目代码／工具／选定证据，不把整个参考目录打进去。

## 6. 核查证据与限度

[来源自动核对报告](<docs/evidence/source-provenance-20261019-r1.json>) 对当前 `src/main` 与两份本地参考树进行了完整文件 SHA-256、PNG RGBA 像素及长共同 Java 行块检测：两上游的精确文件匹配 **0**、同像素 PNG **0**、达到本次阈值的长共同 Java 块 **0**；两张 PNG 均与自有生成器吻合。

自动“未匹配”不是对创作历史的绝对证明，也识别不了所有改写／短通用 API 模式。本轮结合实际源码人工复核后，用“保留 Literal Sky Block 历史归属”“Shadered 创作灵感”“当前自有资源可复现”三类分别标注，避免仅凭相似功能就说复制，或仅凭改名就说完全原创。

DeepSeek 早期贡献依据用户确认，当前源码没有完整逐行模型来源记录；见 [贡献记录](<AUTHORS.md>)。本轮网络取证失败记录不改变本地已核验的 MIT 正文。

## 7. R31 / 1.0.3 新增用户提供全景素材（2026-10-23）

本轮按用户要求引入工作区 `skyboxblock素材/04-定稿/` 的六套素材。它们是**用户提供的第三方视觉素材**，不因代码采用 MIT 而自动改标为项目原创或 MIT。来源目录保持原样；分发包含转换后的显示贴图及其物品缩略图，不包含原始 HDR/JPG 文件。

| 方块 | 用户素材 | 上游与协议 | 转换 |
|---|---|---|---|
| 晴空·克洛彭海姆 | `kloppenheim_06_puresky_1k.hdr` | Poly Haven `kloppenheim_06_puresky` — **CC0 1.0** | Radiance RGBE → 全局 Reinhard/sRGB 显示贴图，1024×512 |
| 晴空·赛弗方丹 | `syferfontein_6d_clear_puresky_1k.hdr` | Poly Haven `syferfontein_6d_clear_puresky` — **CC0 1.0** | 同上，1024×512 |
| 黄昏 | `天空_04_黄昏_qwantani_dusk_2_4096x2048.jpg` | Poly Haven `qwantani_dusk_2_puresky`（Greg Zaal / Jarod Guest）— **CC0 1.0** | 4096×2048 RGB PNG |
| 银河 | `宇宙_01_银河全景_SolarSystemScope_8192x4096_提亮版.jpg` | **Solar System Scope — CC BY 4.0（强制署名，见下）** | Lanczos 缩至4096×2048；沿用用户提亮版本 |
| 葱郁行星 | `行星_Lush_02.png` | Screaming Brain Studios `planet-surface-skyboxes` — **CC0 1.0** | 2048×1024 RGB PNG |
| 末日陆地 | `16_末日陆地_ApocalypseLand/末日陆地_{front,back,left,right,up,down}.png` | VertexZero `retro-skyboxes-pack`（OpenGameArt）— **CC0 1.0** | 核对12条立方体接边、窄带融合后转2048×1024全景 |

### 7.1 CC BY 4.0 强制署名（银河 / galaxy，含其方块缩略图与物品图标）

> Night sky textures by **Solar System Scope** (solarsystemscope.com), licensed under **CC BY 4.0** — <https://creativecommons.org/licenses/by/4.0/>
>
> 改动说明：原图 8192×4096 经用户提亮（gamma 0.55）后，由本项目以 Lanczos 缩至 4096×2048 RGB PNG；对应的 32×32 方块缩略图与物品图标由该图派生，同受 CC BY 4.0 约束。

CC0 素材不要求署名，此处仍按上游记录致谢：**Poly Haven**（Greg Zaal、Jarod Guest、Dimitrios Savva）、**Screaming Brain Studios**、**VertexZero**（vladislavzh.net）。

逐件协议、作者、来源页与下载直链见 [素材清单](<docs/third-party-evidence/asset-manifest.csv>)；协议正文与逐来源署名模板见 [来源与协议](<docs/third-party-evidence/sources-and-licenses.md>)；上游许可原文另存 `docs/third-party-evidence/` 内。

HDR 来源目录附注原文：`标注为httpswww.bilibili.comvideoBV1mLHKekEDH即可`。本包保留该标注；这一目录附注和文件名不是完整上游许可证明。本轮未把第三方素材的作者及完整许可核查冒充为已完成。

全部全景做周期经度与极点连续处理；生成器为 [prepare-r31-materials.py](<tools/prepare-r31-materials.py>)。每个输入及转换输出的 SHA-256、色调参数、12条边误差见 [r31-material-assets.json](<docs/evidence/r31-material-assets.json>)。纯白／纯黑颜色及生成图标为本项目生成，不取自上述第三方素材。
