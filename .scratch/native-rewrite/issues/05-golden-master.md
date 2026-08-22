# 05 — 金样本管线(M1,Q16=A 的落地)

**What to build:** TS 实现当 oracle,机器保证 Kotlin 直译的语义等价:
- RN 侧一次性导出脚本(node/vitest 环境,放 `scripts/export-goldens.mjs` 或 .ts):把全部 fixtures 语料(3,259 行)喂给 TS 纯函数——BBCode `parseBBCode`、`sanitize`、envelope 解包、decode-body、dice、anonymous、title-style、attachments URL、deep-link、vote 解析——规范化 JSON(键排序、稳定序列化)写入 `native/app/src/test/resources/goldens/<domain>/<case>.json`(input + expected 成对)。幂等可重跑。
- Kotlin 侧表驱动对拍框架:每个 domain 一个参数化测试,逐条 input→Kotlin 实现→与 expected 深度比对,差异输出可读 diff。
- **策略链等控制流不走金样本**,关键回归手工移植(票 06)。

**Blocked by:** 01

**Status:** resolved

- [x] 导出脚本进版本库,goldens 生成物有 README 说明再生方式
- [x] Kotlin 框架对至少一个 domain 全量跑通（`entities` 20 条 + `decode-body` 30 条 + `query` 16 条，**sanitize 改由票 04 用本框架跑**，见下）
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

### 2026-08-22 — 05b(Kotlin 表驱动对拍框架)完成

**交付物**(`native/app/src/test/kotlin/com/chasel/ng2n/golden/`)

- `GoldenCase.kt` — 按 README schema 的数据模型(`expected`/`fn`/`input`/`inputEncoding`/
  `name`/`note`)+ input 取值助手(`stringField` / `stringFieldOrNull` / `bytesField` 的
  base64 解码)。取错字段抛 `AssertionError`,**不静默降级**。
- `Goldens.kt` — `Goldens.load(domain)`:先读 `goldens/index.json` 枚举,再逐文件读
  (不遍历 classpath 目录,README 交代过打包形态下不可靠)。索引里有、文件没有 = 报错。
- `JsonDiff.kt` — 深度比较 + 可读 diff。口径:**JSON 数字按数值比**(`1` == `1.0`)、
  **字符串逐 UTF-16 码元比**(不做 Unicode 规范化)、**`null` 与缺键不等价**
  (README 规范 2 说 undefined 键已删,所以缺键就是缺键)、对象键序无所谓、数组顺序有所谓。
  差异输出「路径 + 期望 vs 实际」,一条用例的多处差异**全列**(>200 处才截断并注明还有几处);
  长字符串额外给出**首处不同的码元下标 + 两侧上下文窗口 + 两边的 U+XXXX**——
  `entities` 第一轮就是靠这行三秒定位到 `&nbsp;` 我写成了普通空格而不是 U+00A0。
- `GoldenAssert.kt` — `checkGolden` / `assertGolden(case) { … }` 单条断言,
  `runGoldenDomain(domain) { fn("…") { … } }` 表驱动跑法(**按 `fn` 分派**)。
  `throws` 形态:被测函数抛出的异常经 `GoldenThrowDescriber` 折成 `{throws:{…}}` 后
  走同一套深度比较,于是 `kind`/`message`/`retryable`/`code`/`status`/`via` 天然逐字段比。
  默认描述器只给 README 里「非 NgaError」那一档(`{kind:"error", message}`);
  **票 04 落 `NgaError` 后用 `throwsDescribedBy(…)` 换一个进去即可**,框架不动。

**跑法与「不许放水」的落地**

选了「一个 domain 一个 test class 内循环 + `AssertionError` 汇总」而不是 JUnit4
`Parameterized`:后者在一条 case 语料几十 KB 时,失败信息被 runner 的名字截断得没法看,
而且要给每个 domain 写一遍 `@Parameters` 样板。汇总版的失败信息形如

```
金样本 domain `entities`:20 条中 19 条通过,1 条不匹配
  ✗ unescape-nbsp(fn=unescapeNgaText)
    note: &nbsp; 解成不间断空格,保住 NGA 排版
    1 处差异
    $
      期望 "a b"   实际 "a b"
      首处不同在码元 #1(期望长 4,实际长 4)
      期望 U+00A0 vs 实际 U+0020
```

**逐条失败全列出来,不是首错即停**。另外框架自己钉死了三条「不算通过」:

1. 索引里出现**没注册实现的 `fn`** ⇒ 失败(而不是跳过);
2. 注册了却**一条 case 都没命中**的 `fn` ⇒ 失败(fn 名打错了);
3. `@Ignore` / 放宽比较一律不用——`GoldenFrameworkTest`(18 条)把上面这些失败面
   逐条钉住:数值 `1`/`1.0` 相等、组合字符不做规范化、`null` ≠ 缺键、期望抛错而正常返回、
   期望正常返回而抛错、`retryable` 对不上、以及**框架自身的 `AssertionError` 不会被
   当成「期望抛错」吞掉**(否则取错 input 会假绿)。
   还有一条 `index.json 里登记的每一条都读得出来`:762 条全部 load 一遍并与 `total` 对账。

**本轮跑通的 domain**

| domain | 条数 | 测试类 |
|---|---|---|
| `entities` | 20 | `core/bbcode/EntitiesGoldenTest.kt`(实现:`core/bbcode/Entities.kt`) |
| `decode-body` | 30 | `core/net/encoding/DecodeBodyGoldenTest.kt`(票 03) |
| `query` | 16 | `core/net/QueryGoldenTest.kt`(票 03) |

`sanitize` **不在本票范围**:实现归票 04,那张票用本框架跑
(`runGoldenDomain("sanitize") { fn("sanitizeNgaJson") { … } }`,三行)。

**回改了 05a 的导出器(两处,都影响 goldens 生成物)**

1. **`query` domain 的 `input` 由「参数对象」改成 `{ params: [[key, value], …] }`。**
   这是个真 bug:`stringifyStable` 把对象键排成字典序,而 `buildQueryString` 拼出来的串是
   **插入序**——`build-query-string-post-form-same-rules` 的期望是
   `access_uid=123&access_token=abc`,而落盘后的 input 键序是
   `access_token, access_uid, extra`,Kotlin 照文件顺序拼**永远对不上**。
   数组顺序规范化不动,所以改成有序的键值对列表。README 的 `query` 行与细则已同步。
2. **`decode-body` 补了 9 条 `gbk-*` 合成用例**(框法边界),理由见票 03 Comments。

**顺手补的四个 domain(05a 在「哪些没导」里点名建议的)**

| domain | 条数 | 覆盖 |
|---|---|---|
| `filters` | 49 | `normalizeRuleValue`/`filterRuleId`/`topicCategories`/`validateFilterRule`/`createFilterRule`/`upsertFilterRule`/`removeFilterRule`/`matchFilterRules`(21 条)/`filterMatchText` |
| `reply-chain` | 52 | `extractQuoteRefs`/`quoteRefOf`/`isReplyHeaderNode`/`replyHeaderRefOf`/`buildQuoteIndex`/`buildReplyChain`/`chainDepthOf`/`stripQuoteMarkup` |
| `money` | 24 | `splitMoney`/`formatMoney`/`toReputation`/`formatReputation` |
| `hot-topics` | 11 | `aggregateHotTopics` |

语料全部取自各自的 `*.test.ts`。三处**没导并写进 README**:

- `validateFilterRule` 的**非法正则**分支——返回文案里嵌着 JS 引擎的 `SyntaxError.message`
  (V8/Hermes 都不保证一致,JVM 的 `PatternSyntaxException` 更是另一套措辞),
  拿它对拍等于把引擎实现钉死。票 10 用手写单测锁「前缀是『正则表达式不合法:』」。
- `compileFilterRegex` —— 返回 `RegExp`,不是 JSON 值。
- `splitMoney(NaN)` —— README 规范 4 不允许非有限数字进金样本。

`buildQuoteIndex` 的 expected 把 `Map`/`Set` 拍平成 `{quotes:[[pid,refs]…],
quotedBy:[[pid,[pid…]]…], loaded:[pid…]}`(键升序),README 写死了。
`reply-chain` 的 input 是楼层 BBCode 原文,Kotlin 侧要先 `parseBBCode` 再喂——
和 `dice` 同一个管线,所以它**依赖票 09**,票 10 开工时两张票的顺序要注意。

金样本从 617 条 / 24 domain 涨到 **762 条 / 28 domain**;`pnpm goldens:export` 幂等仍绿,
`pnpm typecheck` 绿。

**发现的票外问题**

- **`api/fields` 与 `api/*` 管线里,非数字键的相对顺序在落盘时可能被改掉。**
  `stringifyStable` 排字典序,而 `orderedEntries` 对**非数字键**是「保持原有顺序」
  (`a.index - b.index`)。数字键不受影响(它自己按数值排),真数组也不受影响,
  所以本轮 617 条里大概率没踩到;但票 09/10/11 往 `api/*` 加合成向量时,
  只要 expected 依赖非数字键的先后,就会踩。建议:要么给这类 case 也改成键值对列表,
  要么在 README 里明确「非数字键的相对顺序不保证」。**本票没动,留给主控定。**
- `pnpm lint` 在 `main` 上就有 79 个 error(`react-hooks/purity`、`react-hooks/immutability`
  等,全在 `src/ui/**`),与本轮改动无关;`scripts/**` 根本不在 eslint 的 config 覆盖里
  (`File ignored because no matching configuration was supplied`),所以导出器只有
  `pnpm typecheck` 管得着。

**主控验收(2026-08-22)**:05a+05b 合并,762 条 / 28 domain。口径裁定(针对 05b 发现的键序问题):goldens 里 JSON **对象按无序 map 比较**;凡顺序有语义的输出(orderedEntries、query 参数表、列表)一律导成**数组**,不得依赖对象键序。后续加合成向量按此执行。
