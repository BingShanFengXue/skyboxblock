# Modrinth 审核：周期、队列规则与本项目的操作纪律

记录日期：2026-11-26。适用项目：`skyboxblock`（Modrinth 项目 id `iV2hMdsN`）。

## 1. 官方口径

来源：[Project Review Times](https://support.modrinth.com/en/articles/8793355-project-review-times)（2026-10-07 更新）。

| 项目类型 | 目标时长 |
|---|---|
| 普通项目（mod / 插件 / 资源包） | 24–48 小时 |
| 整合包 | 72–96 小时（3–4 天） |

同页顶部的延迟通告原文：

> Please understand that review times **will be delayed or fluctuate drastically** while our team trains new content moderators … Project reviews during and after this period may take upwards of **two weeks, sometimes over a month**, depending on review complexity.
>
> We try to review projects within **24 to 48 hours** of submission (with fluctuations averaging **two to four weeks**, sometimes longer, due to the above delay).

结论：**乐观 1–2 天，常见 2–4 周，个别超过一个月**。Modrinth 在 [2025-07 Creator Updates](https://modrinth.com/news/article/creator-updates-july-2025/) 中也公开承认过数周等待并说明正在改造审核工具。

## 2. 队列规则（官方明确写出的）

1. 不显示排队位置，也不给时间预估；显示 `Under review` 就一定会被审，不会「掉出队列」。
2. 排队期间**修改项目或上传新版本不会改变队列位置**。
3. **删除旧版本再上传新版本，可能被送到队尾** —— 原文：*Deleting old versions then uploading new versions might send the project to the end of the queue. Unless requested by the content moderators, or old versions violate Modrinth's content rules (such as including unlicensed content), deleting old versions during review is not recommended.*
4. 审核员要求修改后重新提交，会明确排到队尾。
5. 催问不加速；技术支持不参与内容审核。
6. 自家恶意代码扫描器若命中，会转人工技术审核，可能显著延长（也可能完全无感）。

## 3. 本项目的实际时间线

| 日期 | 事件 |
|---|---|
| 2026-11-04 | 首次提交审核（`processing`），上架内容：1.21.1 r38 / 26.2 port.2 |
| 2026-11-14 | 1.21.1 手工拼装的包被证实不可运行（`NoClassDefFoundError`）；**删除 `joMZgmPn`、`eUM9hQyY` 两条版本**后重建上传 |
| 2026-11-14 起 | 此后每轮只**新增**版本，未再删除 |
| 2026-11-22 / 11-26 | 继续新增 1.0.8-test.5、test.7 等版本 |

按第 2 节第 3 条，**2026-11-14 的删除 + 重建有把项目送回队尾的风险**，这是当时为了不让玩家下到坏包所做的取舍。它可能解释了 11 月中之后项目仍长期停在 `processing`。

## 4. 今后的操作纪律（对本仓库发布流程生效）

1. **不删版本**。要修东西就**新增**版本（版本号递增），或 `PATCH /v2/version/{id}` 改 changelog。
2. 只有两种情况可以删：审核员明确要求；或该版本含违规/无授权内容。
3. 坏包的正确处理顺序：先修源码 → 构建新包 → 新增版本 → 在新版本 changelog 里写明旧包作废原因 → 让作者/用户去下新版本。**不要**为了替换文件而删除旧版本。
4. 每次上传后记录：版本 id、文件 SHA-256、上传时间，写进对应交付文档，便于日后核对与向审核员说明。
5. 审核期间不催问；只在 Moderation 标签页出现留言时回复。

## 5. 与 GitHub 的分工

Modrinth 审核不阻塞分发：GitHub Release 可立即提供下载。Modrinth 侧维持`新增版本 + 等待审核`的节奏即可，不要在审核期间做破坏性版本操作。
