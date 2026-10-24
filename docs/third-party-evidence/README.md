# 素材授权证据（随仓库分发）

这里放的是 **R31 / 1.0.3 六套全景素材的授权证据**。原文件位于工作区的素材库目录（不在本仓库内），为了公开发布时能自证来源，逐字复制到这里。

| 本地文件 | 原始位置 | 改动 |
|---|---|---|
| `sources-and-licenses.md` | 素材库 `来源与协议.md` | 逐字副本，未改一字 |
| `asset-manifest.csv` | 素材库 `素材清单.csv` | 逐字副本，含 2026-10-24 追加的 5 行（kloppenheim_06 / syferfontein_6d 的 1k、4k HDR 与 `行星_Lush_02.png`） |
| `ScreamingBrainStudios-CC0-License.txt` | 素材库 `来源与协议/ScreamingBrainStudios-CC0-License.txt` | 逐字副本（上游自带的 CC0 声明原文） |
| `VertexZero-RetroSkyboxes-README.txt` | 素材库 `来源与协议/VertexZero-RetroSkyboxes-README.txt` | 逐字副本（上游自带的 CC0 声明原文） |
| `hdr-acquisition-note.txt` | 素材库 `04-定稿/##.hdr文件来源.txt` | 逐字副本（53 字节，即 bilibili 获取渠道附注） |

## 与已发行贴图的对应关系

| 方块贴图 | 素材 | 协议 |
|---|---|---|
| `textures/sky/kloppenheim.png` | `kloppenheim_06_puresky_1k.hdr` | CC0 1.0（Poly Haven） |
| `textures/sky/syferfontein.png` | `syferfontein_6d_clear_puresky_1k.hdr` | CC0 1.0（Poly Haven） |
| `textures/sky/dusk.png` | `天空_04_黄昏_qwantani_dusk_2_4096x2048.jpg` | CC0 1.0（Poly Haven） |
| `textures/sky/galaxy.png` | `宇宙_01_银河全景_SolarSystemScope_8192x4096_提亮版.jpg` | **CC BY 4.0（必须署名）** — Solar System Scope |
| `textures/sky/lush.png` | `行星_Lush_02.png` | CC0 1.0（Screaming Brain Studios） |
| `textures/sky/apocalypse.png` | `16_末日陆地_ApocalypseLand/*.png` | CC0 1.0（VertexZero） |

CC BY 4.0 的强制署名文本见仓库根目录 `THIRD_PARTY_NOTICES.md` §7.1 与 `README.md` §8。

> 说明：`asset-manifest.csv` 是抓取时逐件登记的记录（协议 / 作者 / 来源页 / 下载直链）。其中 HDR 两项当时未登记，2026-10-24 依 Poly Haven 资产页与同名 JPG 记录补齐，备注栏已写明获取渠道；`行星_Lush_02.png` 与素材库 `03-老式游戏天空盒/C-行星地表全景/行星_Lush_02.png` 经 SHA-256 比对为同一文件（`c64491db…`），据此归到 Screaming Brain Studios 的 `planet-surface-skyboxes`（CC0）。
