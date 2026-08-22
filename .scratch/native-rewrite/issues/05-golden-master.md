# 05 — 金样本管线(M1,Q16=A 的落地)

**What to build:** TS 实现当 oracle,机器保证 Kotlin 直译的语义等价:
- RN 侧一次性导出脚本(node/vitest 环境,放 `scripts/export-goldens.mjs` 或 .ts):把全部 fixtures 语料(3,259 行)喂给 TS 纯函数——BBCode `parseBBCode`、`sanitize`、envelope 解包、decode-body、dice、anonymous、title-style、attachments URL、deep-link、vote 解析——规范化 JSON(键排序、稳定序列化)写入 `native/app/src/test/resources/goldens/<domain>/<case>.json`(input + expected 成对)。幂等可重跑。
- Kotlin 侧表驱动对拍框架:每个 domain 一个参数化测试,逐条 input→Kotlin 实现→与 expected 深度比对,差异输出可读 diff。
- **策略链等控制流不走金样本**,关键回归手工移植(票 06)。

**Blocked by:** 01

**Status:** in-review（05a 完成，05b 待做）

- [x] 导出脚本进版本库,goldens 生成物有 README 说明再生方式
- [ ] Kotlin 框架对至少一个 domain(建议 sanitize)全量跑通
- [x] 约定:后续每张 M1/M2 票的「金样本对拍通过」都指本管线(写进 goldens/README.md 末节)

## Comments

### 2026-08-22 — 05a（RN 侧导出管线）完成，05b（Kotlin 对拍框架）待做

**交付物**

- `scripts/export-goldens.mts` — 导出器（唯一真源，含全部 case 表）
- `vitest.goldens.config.ts` + `package.json` 的 `pnpm goldens:export`
- `native/app/src/test/resources/goldens/**` — **617 条** golden，24 个 domain，3.9MB
- `native/app/src/test/resources/goldens/README.md` — 格式约定 / 再生方式 / 各 domain 语义
  （**05b 与 03/04/09/10 的唯一输入**，逐条写死了 schema、规范化规则、`api/*` 的输入管线）
- `native/app/src/test/resources/goldens/index.json` — 索引，Kotlin 侧靠它枚举，
  不必在 classpath 上遍历目录

**每个 domain 的 case 数与来源**

| domain | 条数 | fn | 语料来源 |
|---|---|---|---|
| `sanitize` | 22 | `sanitizeNgaJson` | `sanitize.test.ts` 八步 + 6 份真实抓包 |
| `envelope` | 18 | `parseNgaJson` | `envelope.test.ts` + 6 份 net fixture + fid=414 坏字节 |
| `errors` | 32 | `isFakeError`/`isAuthLevelServerError`/`extractServerError`/`stripServerHtml` | `envelope.test.ts`、`server-text.test.ts` |
| `decode-body` | 21 | `decodeResponseBody`×16 / `parseCharset`×5 | `decode-body.test.ts` + 6 份真实抓包（含两份 fid=414） |
| `entities` | 20 | `unescapeNgaText`×11 / `escapeForSubmit`×9 | `entities.test.ts` |
| `bbcode` | 126 | `parseBBCode` | `coverage.test.ts`（29 节点全覆盖 + 拼接长文）+ `parse*.test.ts` 全部 + `walk.test.ts` 语料 + 5000 层嵌套 |
| `dice` | 23 | `resolveDice`×20 / `formatDiceTerms`×3 | `dice.test.ts`（含 5 条站上真帖对拍） |
| `anonymous` | 30 | `decodeAnonymousName`/`resolveAuthorName`/`isAnonymousAuthor` 各 10 | `anonymous.test.ts` |
| `title-style` | 35 | `parseTopicMisc`×12 / `signedBoardId`×9 / `decodeTitleStyle`×14 | `title-style.test.ts` |
| `attachments` | 44 | 7 个函数 | `attachments.test.ts` |
| `deep-link` | 56 | `parseNgaLink`×51 / `ngaLinkPath`×5 | `deep-link.test.ts` |
| `vote` | 24 | `parseVote`×17 / `isVoteClosed`×3 / `voteSharePercent`×4 | `vote.test.ts`（含站上真帖 tid=47331456） |
| `query` | 16 | `buildQueryString`×12 / `hasGbkParam`×4 | `query.test.ts` + `block-word.test.ts` 的 GBK 载荷 |
| `api/topic-list` | 22 | `parseTopicList`×9 等 5 个 | 9 份 fixture（fid=-7 / 两种搜索 / 收藏夹 / 我的主题 / 我的回复 / 翻到底 / `__output=11` / fid=650） |
| `api/topic-detail` | 11 | `parseTopicDetail`×5 / `parseAvatarUrl`×6 | 5 份 read.php fixture（匿名+热回 / 贴条+noimg / 附件 / 版头老帖 / lite=js） |
| `api/board-tree` | 6 | `parseBoardTree`×3 / `pickActiveAnnouncement`×3 | `home-category`（7 分类 673 版块） |
| `api/board-favor` | 10 | `parseBoardFavorites`×3 / `parseBoardIdInput`×7 | 3 份 forum_favor2 fixture |
| `api/topic-favor` | 3 | `parseFavoriteFolders` | 3 份 topic_favor_v2 fixture |
| `api/notifications` | 18 | `parseNotificationFeed`×7 / `notificationKind`×11 | 空账号 fixture + 文档口径向量（§9.1） |
| `api/user-profile` | 5 | `parseUserProfile` | ucp 的 user/admin/missing/avatar + net 侧同接口 |
| `api/search` | 8 | `parseBoardSearch`×2 / `parseUserSearchInput`×6 | forum.php 两份 fixture |
| `api/block-word` | 14 | `parseBlockWords`×8 / `serializeBlockWords`×2 / `blockWordError`×4 | `block-word.test.ts` |
| `api/sub-board` | 16 | `subBoardState`×10 / `subBoardOptionParam`×4 / `nextSubBoardState`×2 | 魔法数表全枚举 |
| `api/fields` | 37 | `orderedEntries`/`orderedValues`/`str`/`text`/`int`/`nonZero` | `fields.test.ts` + 补足的字段形态 |

**关键决定（票里留白的「实现时定」项）**

1. **导出器用 vitest 跑**，不是 `node scripts/…`：仓库没装 tsx / vite-node，`src/core/**`
   全是无扩展名 TS 相对导入，Node 24 的原生类型剥离解析不了。导出器本身就是那条用例，
   它跑两遍逐字节对比，**「幂等」是可执行的验收项**而不是口头约定。
   为此加了 `vitest.goldens.config.ts`（刻意不进 `pnpm test` 的 include——导出器写文件，
   `pnpm test` 不该有副作用；实测默认套件仍是 84 文件 1125 通过，一条没多）。
2. **`tsconfig.json` 的 include 补了 `**/*.mts`**：不补的话导出器完全不进 `pnpm typecheck`。
   补上后立刻抓到一处类型错（合成的 `Topic` 少 5 个必填字段），已修。
3. **golden schema 比票里多了 `fn` 与 `note` 两个顶层键**。`fn` 让每条 golden 自描述
   （一个 domain 覆盖多个函数是常态，例如 `attachments` 有 7 个），05b 的参数化测试
   可以直接按 `fn` 分派；`note` 带上出处，Kotlin 侧 diff 报错时能直接看到「这条锁的是什么」。
4. **`api/*` 的 input 是「解码后的文本 + 管线声明」**（`{text, envelope, part, args}`），
   不是原始字节、也不是已解析的信封：这样 05b 的一条 api 用例天然走
   `sanitize → parseNgaJson → parseX` 全链，而字节层解码由 `decode-body` 单独锁。
   合成向量（无 fixture 的）用 `{value, args}`，靠有没有 `text` 字段区分。README 写死了。
5. **`envelope` 的 expected 不含 `root`**：`root` 等于 `JSON.parse(sanitize(text))`，
   已被 `sanitize` domain 锁住，再存一份让每条 golden 体积翻倍。README 里明写了。
6. **抛错的 expected 除 `kind`/`message` 外还带 `retryable`**（以及 `code`/`status`/`via`）：
   `retryable` 决定反封锁链要不要往下走，是语义的一部分，漏掉等于没对拍（ADR-0002）。
7. **`undefined` 归一成 `null`**（函数返回 undefined 时），对象里值为 undefined 的键整个删掉。
8. **键序自己拼而不是靠 `JSON.stringify`**：JS 引擎会把整数样式的键提到最前并按数值排，
   而 NGA 的 data 全是整数键——交给引擎排就不是字典序，跨实现比对会踩坑。
9. **domain 命名照票执行**，额外加了两个：`errors`（票 04 的第三块，`isFakeError` /
   `extractServerError` / `stripServerHtml`）与 `api/fields`（`orderedEntries` 一族）。
   票里列的固定名一个没改。

**哪些 TS 函数没导，为什么**

- `core/net/strategies/**`、`combo.ts`、`fetcher.ts`、`diagnostics.ts`、`transport.ts`、
  `auth.ts` —— **有 IO / 有时序 / 有状态**（组合缓存 TTL、attempts 诊断、AbortSignal），
  走不了「输入→输出」对拍。票 05 原文就把策略链控制流排除在外，关键回归手工移植（票 06）。
- `attempt.ts` 里的 `buildUrl`（`__inchst=UTF8` 的撤销、格式档参数、Referer/UA/双通道认证）
  —— **未导出**且掺着 context/凭证，不是纯函数。它可对拍的那一半（逐参数 GBK 编码、
  空值剔除、`hasGbkParam` 判据）已经在 `query` domain 里；剩下的装配顺序靠票 03/06 手工移植。
  所以票里提的「出站 GBK 编码」落成了 `query` domain，没有单独的 `gbk-encode`。
- `core/net/web/read-html.ts` —— 票 08 要求**先对线上重新抓包验证参数位置表**再动，
  现在导 golden 等于把一份未复核的口径钉死。net fixture 里那 4 份网页 HTML 已就位，
  票 08 重验后可以直接补一个 `web` domain（导出器加 20 行）。
- `core/net/encoding/gb18030.ts` 的 `decodeGb18030` —— Kotlin 用
  `Charset.forName("GB18030")`，逐序列对拍没有意义（RN 侧那 23940 条是拿 Node
  TextDecoder 对拍手写状态机用的）。**策略层面**的分支已经全在 `decode-body` 里。
- `core/local/{filters,reply-chain,money,hot-topics,check-in,history,settings,topic-cache,
  topic-favor-index,notifications}.ts` —— 本票交办范围只列了 dice/anonymous/title-style/
  deep-link/vote。其中 **`filters.ts`、`reply-chain.ts`、`money.ts`、`hot-topics.ts` 是纯函数
  且有现成 `*.test.ts` 语料**，票 10（本地算法）开工前建议先让本管线补出来——
  导出器里加一个 `exportLocalXxx()` 函数即可，成本很低。其余几个是 MMKV/SQLite 存储层。
- `core/api/{board-tree-cache,topic-recommend,user-topics,hot-topics,check-in,set-sign}.ts`
  的纯函数（`isBoardTreeStale`/`mergeBoardTree`/`nextRecommendState`/`mergeUserPostPages` 等）
  —— 属 M3 票的范围且无真实语料（全是合成对象），本轮没导，同样一行一条可补。
- `core/smilies` —— 表情映射是**生成物**（`scripts/fetch-smilies.mjs` 从官方 JS 抽的表），
  Kotlin 侧应当复用同一张表而不是对拍解析结果；`resolveSmiley` 的接缝已经通过
  `bbcode` 的 `smiley-unresolved-*` 用例锁住（解析器只负责原样捞出 code）。

**发现的票外问题**

- `native/app/src/test/resources/goldens/` 里 617 个文件共 3.9MB，最大单文件 300KB
  （`api/board-tree/home-category.json`，673 个版块的树）。骨架票的 `native/.gitignore`
  **不要**把 `**/build/` 之外的东西一起挡掉，尤其别写 `resources/` 相关规则。
- `pnpm test` 跑出来是 84 文件 / 1125 通过 / 14 skipped（`*.smoke.test.ts` 那几个要网络）。
  inventory.md §10 记的是「1114 用例」，现在是 1139（含 skip）——只是统计口径/时间差，无异常。

**待所有者 / 待其他票**

- 05b（Kotlin 表驱动对拍框架）等票 01 工程骨架落地后开工，验收项第 2 条留 `[ ]`。
- 票 08 重验 `commonui.postArg.proc` 参数位置表后，回来补 `web` domain。
