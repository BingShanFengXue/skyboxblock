# 来源核对证据（脱敏副本）

这里的两份 JSON 原本生成在 `build/reports/`（该目录不入仓库），为了让 `THIRD_PARTY_NOTICES.md` §6/§7 的引用在公开仓库里仍然可点、可复核，做了**仅脱敏、不改结论**的副本。

| 文件 | 原始生成位置 | 改动 |
|---|---|---|
| `source-provenance-20261019-r1.json` | `build/reports/source-provenance-20261019-r1.json` | `references.*.localRoot` 由本机绝对路径改为 `reference/literal-sky-block`、`reference/shadered`；`note` 末尾补一句脱敏说明。命中结论（`exactByteMatches` / `pixelMatches` / `longCommonCodeBlocks` 全空）与两张图的像素哈希未改。 |
| `r31-material-assets.json` | `build/reports/r31-material-assets.json` | `sourceDirectory` 由本机素材库绝对路径改为 `skyboxblock-materials/04-final`；其余字段（每件的 `source`、`sourceSha256`、`exposure`、接边误差、`outputSha256`）保持原样。 |

两份文件都仍是合法 JSON，且不含任何盘符绝对路径、本机账号名或 DSH 运行时路径。

## 读这两份文件时要注意的两件事

1. **`r31-material-assets.json` 记录的实际输入是 1k HDR**（`width: 1024`、`height: 512`、`source: …_1k.hdr`）。
   `tools/prepare-r31-materials.py` 当前写的却是 `…_4k.hdr`，`THIRD_PARTY_NOTICES.md` §7 表格写的是 `…_1k.hdr`。
   三处口径尚未统一（2026-10-24 审核时标出，未改，待确认哪一份是当前事实）。
2. **`source-provenance` 只能证明"没有整份同字节复制"**，不能证明创作历史；`THIRD_PARTY_NOTICES.md` §2/§6 已按这个限度表述，不要把它读成"完全独立".
