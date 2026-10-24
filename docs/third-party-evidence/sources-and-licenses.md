# 来源与协议（逐条可粘贴署名）

本文件是所有素材的**授权证据与署名模板**。每个来源的协议都在抓取时逐页核对过；
`素材清单.csv` 里每个文件都单独记录了「协议 / 作者 / 来源页面 / 下载直链」。

> 结论先说：**本包内全部素材都可商用**。
> - CC0 1.0 部分（Poly Haven、Screaming Brain Studios、VertexZero 复古包、OpenGameArt 太空/云层/阴天）：无署名义务，商用、改作、再发布都允许。
> - CC BY 4.0 部分（Solar System Scope、ESO）：**必须署名**，文本直接用下面「署名模板」里的即可。

---

## 1. Poly Haven — CC0 1.0

- 站点授权页：<https://polyhaven.com/license>
- 协议：**CC0 1.0 Universal（公有领域贡献）** —— 官方明确「所有资产均为 CC0，可免费用于任何用途，无需署名」。
- 收录：`01-天空` 全部 8 张（源自各资产的 *pure sky* 变体）+ 对应 8 个立方体 zip。
- 原始资产页（逐个）：
  - <https://polyhaven.com/a/kloofendal_48d_partly_cloudy_puresky>
  - <https://polyhaven.com/a/kloppenheim_03_puresky>
  - <https://polyhaven.com/a/kloppenheim_05_puresky>
  - <https://polyhaven.com/a/qwantani_dusk_2_puresky>
  - <https://polyhaven.com/a/belfast_sunset_puresky>
  - <https://polyhaven.com/a/kloppenheim_06_puresky>
  - <https://polyhaven.com/a/kloofendal_overcast_puresky>
  - <https://polyhaven.com/a/kloppenheim_02_puresky>
- 摄影 / 修图：
  - Greg Zaal（多数原始拍摄与处理）
  - Jarod Guest（pure sky 纯天空修图）
  - Dimitrios Savva（belfast_sunset 拍摄）
- 署名模板（**非强制**，建议写）：
  `Sky HDRI panoramas from Poly Haven (polyhaven.com), CC0 1.0. Photographers: Greg Zaal, Dimitrios Savva; sky edits: Jarod Guest.`

> 说明：下载的是 Poly Haven 官方提供的 **Tonemapped JPG**（不是 .hdr），已由官方做过色调映射，
> 因此不需要再自行 tonemap；原始分辨率高于本包内的 4096×2048，本包做了等比缩放到游戏友好的尺寸。

---

## 2. Solar System Scope — CC BY 4.0（需署名）

- 素材页：<https://www.solarsystemscope.com/textures/>
- 协议：**Creative Commons Attribution 4.0 International（CC BY 4.0）**
- 版权文本：<https://creativecommons.org/licenses/by/4.0/>
- 收录：`02-宇宙`
  - `宇宙_01_银河全景_SolarSystemScope_8192x4096.jpg`（原始 8k 星图 `8k_stars_milky_way.jpg`）
  - `宇宙_02_深空星野_SolarSystemScope_8192x4096.jpg`（`8k_stars.jpg`）
  - 以及同名的两张 `_提亮版.jpg`（gamma 0.55 派生，同属 CC BY 4.0）
- 署名模板（**必须**）：
  `Night sky textures by Solar System Scope (solarsystemscope.com), licensed under CC BY 4.0 — https://creativecommons.org/licenses/by/4.0/`

> 注意：这两张是给 HDR 管线做的，8bit 下极暗（全图均值 0.24 / 1.44 每 255）。
> 本包额外提供了 `_提亮版`（gamma 0.55，均值提到可用范围），按需取用；改动已标注，仍受同一协议约束。

---

## 3. ESO（欧洲南天天文台）— CC BY 4.0（需署名）

- 作品页：<https://www.eso.org/public/images/eso0932a/>
- 协议：**CC BY 4.0**（ESO 公开图像自 2018 年起统一为 CC BY 4.0）
- 收录：`宇宙_03_银河全景_ESO_6000x3000.jpg`（原始 `eso0932a.jpg`，6000×3000，7.2 MB）
- 署名模板（**必须**）：
  `ESO/S. Brunier — "The Milky Way panorama", CC BY 4.0, https://www.eso.org/public/images/eso0932a/`

> 下载时本机 schannel 无法完成证书吊销检查（`CRYPT_E_REVOCATION_OFFLINE`），
> 用 `curl --ssl-no-revoke` 取回，文件本身是 ESO 官方 CDN 原件。

---

## 4. Screaming Brain Studios（SBS）— CC0 1.0

- 作品页：
  - 多云：<https://opengameart.org/content/cloudy-skyboxes-0>
  - 行星地表：<https://opengameart.org/content/planet-surface-skyboxes>
- 协议：**CC0 1.0**，包内自带 `License.txt` 原文（已存为 `ScreamingBrainStudios-CC0-License.txt`）：
  > "All Screaming Brain Studios assets have been released under the CC0/Public Domain License.
  > You are free to use these assets in any and all projects, commercial or non-commercial,
  > with no restrictions, and can be released with or without credit."
- 收录：`03-老式游戏天空盒/B-多云全景`（25 张）、`C-行星地表全景`（32 张），均为 2048×1024 等距圆柱。
- 署名模板（**非强制**）：`Skybox panoramas by Screaming Brain Studios, CC0 1.0.`

---

## 5. VertexZero / vladislavzh — CC0 1.0（复古立方体）

- 作品页：<https://opengameart.org/content/retro-skyboxes-pack>
- 作者站点：<https://vladislavzh.net>（包内 `README.txt` 原文已存为 `VertexZero-RetroSkyboxes-README.txt`）
- 协议：**CC0 1.0**。包内原文：
  > "License CC0: https://creativecommons.org/publicdomain/zero/1.0/
  > You are free to use these skyboxes in personal and commercial projects."
- 收录：`03-老式游戏天空盒/A-复古立方体` 共 **20 套**（原包 19 套 + 附赠 Techno），每套 6 面 512×512 PNG。
- 署名模板（**非强制**）：`Retro skyboxes by VertexZero (vladislavzh.net), CC0 1.0.`

---

## 6. OpenGameArt 其余 CC0 作品

| 收录目录 | 作品页 | 协议 | 备注 |
|---|---|---|---|
| `03-.../D-太空立方体`（蓝1/绿1/橙0/青1） | <https://opengameart.org/content/space-skyboxes> | CC0 1.0 | 1024×1024×6 面，低分辨率复古太空 |
| `03-.../E-云层立方体` | <https://opengameart.org/content/clouds-skybox-1> | CC0 1.0 | 原为 BMP，已无损转 PNG |
| `03-.../F-阴天全景` | <https://opengameart.org/content/overcast-skybox> | CC0 1.0 | 512×256 等距圆柱，原包另有 EXR |

> 这三页的作者字段在 OpenGameArt 现行页面结构里抓不到稳定值（页面上的用户名同时包含评论区用户），
> 因此按「作品页链接」署名。**CC0 本身不要求署名**，若你仍想写，直接引用上面表格里的作品页链接即可。

---

## 7. 未收录 / 已剔除（附原因，方便你复核）

| 候选 | 原因 |
|---|---|
| OpenGameArt `space-skyboxes` 里的 `blue0` 组 | 只有 5 个面（缺 `up`），且多出的 `back.png` 与其它 5 面边缘都对不上（差值 13~15/255），无法作为立方体贴图使用，整组剔除 |
| ambientCG `NightSkyHDRI001/008`（CC0） | `ambientcg.com/get?file=NightSkyHDRI001_8K_HDR.zip`、`_4K_HDR.zip`、`NightSkyHDRI001_4K.zip` 等端点全部 404，API 查不到该 ID，放弃 |
| NASA SVS 4851 «Deep Star Maps 2020» | 高分辨率只提供 EXR/TIF（`starmap_2020_4k.exr` 34 MB、8k 124 MB），本机 Pillow 不能解码 EXR；它自带的 `_print.jpg` 只有 1024×512，做天空盒太糊。属于公有领域，需要的话可以另行转换（`oiiotool` / `imageio` 读 EXR 后转 PNG） |
| OpenGameArt `night-skyboxes`（Humus） | 标注 CC BY 3.0 且要求署名 Humus，画质与 CC0 方案重复，未收录 |
| OpenGameArt `skiingpenguins' skybox pack` | CC BY-SA 3.0（传染性协议），为避免给商用带来额外约束，未收录 |
