# 金样本协议与迁移参考

本页保留原 RN 导出格式、TS 函数映射和迁移记录。旧实现及导出器已移除；其中的票号、路径、导出动作和规模属于迁移时点，不是当前维护步骤。当前入口见 [README](README.md)。

## 文件 schema

```jsonc
{
  "expected": <JSON 值>,          // 必填：被测函数的返回值（或 { "throws": … }）
  "fn": "<TS 函数名>",             // 必填：这条对拍的是哪个函数
  "input": <JSON 值>,             // 必填：入参，形状按 fn 定，见下表
  "inputEncoding": "base64",      // 选填：只在 input 含原始字节时出现
  "name": "<kebab-case>",         // 必填：= 文件名
  "note": "<中文说明>"             // 选填：这条锁的是什么 / 出处
}
```

规范化（导出器保证，Kotlin 侧比对时可以依赖）：

1. **对象键按 `Array.prototype.sort()` 的字典序**排列（不是 JS 引擎那套「整数键提前」，
   NGA 的 data 全是整数样式的键，交给引擎排就不是字典序了）。所以顶层永远是
   `expected` → `fn` → `input` → `inputEncoding` → `name` → `note`。
2. **值为 `undefined` 的键整个删掉**（不写成 `null`）。数组元素里的 `undefined` 落成 `null`。
3. **函数返回 `undefined` 时 `expected` 是 `null`**（JSON 装不下 undefined）。
   要区分「返回 undefined」和「返回 null」的场合，本仓库里没有——所有相关函数的
   「没有」都写成 `undefined`。
4. 数字与字符串原样，不做取整/规整；非有限数字（NaN/Infinity）会让导出直接报错。
5. 缩进固定两个空格，文件以单个换行结尾。

### `expected` 是抛错时

```jsonc
{
  "expected": {
    "throws": {
      "kind": "parse",            // NgaErrorKind: network | http | parse | server | unavailable
      "message": "响应为空",
      "retryable": true,          // 反封锁链要不要往下走，**语义的一部分，必须对上**
      "code": 403,                // 选填：服务端错误码（string | number）
      "status": 503,              // 选填：HTTP 状态
      "via": "direct"             // 选填：触发失败的策略名
    }
  }
}
```

非 `NgaError` 的异常写成 `{ "kind": "error", "message": … }`。
**注意**：`expected` 是个恰好只有 `throws` 一个键的对象 ⇒ 这条期望抛错。
本批语料里没有任何函数会正常返回一个带 `throws` 键的对象，不存在歧义。

### `inputEncoding: "base64"`

只有 `decode-body` 用得上。含义：input 里承载**原始响应字节**的那个字段
（固定叫 `bytes`）是 base64 串，其余字段是普通 JSON 值。Kotlin 侧：

```kotlin
val bytes = Base64.getDecoder().decode(input.bytes)
```

## 各 domain

`part`/`envelope`/`args` 的含义见下面「`api/*` 的输入管线」。

| domain | TS 源 | fn（条数） | input | expected |
|---|---|---|---|---|
| `sanitize` | `core/net/sanitize.ts` | `sanitizeNgaJson`×22 | 响应原文（字符串） | 洗完的 JSON 文本（字符串，**不解析**） |
| `envelope` | `core/net/envelope.ts` | `parseNgaJson`×18 | `{ text, via?, shape? }` | `{ data, time?, fakeError? }` 或 `{ throws }` |
| `errors` | `core/net/errors.ts`、`server-text.ts` | `isFakeError`×10 / `isAuthLevelServerError`×3 / `extractServerError`×11 / `stripServerHtml`×8 | 字符串（前三个是 message，`extractServerError` 是**响应顶层对象**） | 布尔 / `{ code, message }`\|`null` / 纯文本 |
| `decode-body` | `core/net/encoding/decode-body.ts` | `decodeResponseBody`×25 / `parseCharset`×5 | `{ bytes(base64), contentType }` / `{ contentType }` | 解码后的文本 / 小写 charset 或 `null` |
| `web` | `core/net/web/read-html.ts` | `parseReadPageHtml`×7 | `{ text, via }`（整页 HTML 文本） | 信封的 `data`（与 `__output=8` 同构）或 `{ throws }` |
| `entities` | `core/bbcode/entities.ts` | `unescapeNgaText`×11 / `escapeForSubmit`×9 | 字符串 | 字符串 |
| `bbcode` | `core/bbcode/parse.ts` | `parseBBCode`×126 | BBCode 原文（字符串） | AST（`BBCodeNode[]`，可直接 JSON 往返） |
| `dice` | `core/local/dice.ts` | `resolveDice`×20 / `formatDiceTerms`×3 | `{ text, authorId, tid, pid }` / `{ terms }` | 见下 |
| `anonymous` | `core/local/anonymous.ts` | `decodeAnonymousName`×10 / `resolveAuthorName`×10 / `isAnonymousAuthor`×10 | 作者名（字符串） | `{ name, colors:[c1,c2] }`\|`null` / 字符串 / 布尔 |
| `title-style` | `core/local/title-style.ts` | `parseTopicMisc`×12 / `signedBoardId`×9 / `decodeTitleStyle`×14 | `{ raw }` / `{ value }` / `{ titlefont?, topicMisc? }` | `TopicMisc` / 数字\|`null` / `TitleStyle` |
| `attachments` | `core/api/attachments.ts` | 7 个函数×44 | 见下 | 字符串 |
| `deep-link` | `core/local/deep-link.ts` | `parseNgaLink`×51 / `ngaLinkPath`×5 | 链接原文 / `NgaLink` 对象 | `{ ok:true, link }`\|`{ ok:false, reason }` / 路由路径 |
| `vote` | `core/local/vote.ts` | `parseVote`×17 / `isVoteClosed`×3 / `voteSharePercent`×4 | `{ raw, tid }` / `{ raw, tid, now }` / `{ votes, total }` | `Vote`\|`null` / 布尔 / 数字 |
| `query` | `core/net/query.ts` | `buildQueryString`×12 / `hasGbkParam`×4 | `{ params: [[key, value], …] }`（见下） | query 串 / 布尔 |
| `filters` | `core/local/filters.ts` | `normalizeRuleValue`×3 / `filterRuleId`×3 / `topicCategories`×5 / `validateFilterRule`×6 / `createFilterRule`×4 / `upsertFilterRule`×2 / `removeFilterRule`×2 / `matchFilterRules`×21 / `filterMatchText`×3 | 见下 | 见下 |
| `reply-chain` | `core/local/reply-chain.ts` | `extractQuoteRefs`×7 / `quoteRefOf`×3 / `isReplyHeaderNode`×3 / `replyHeaderRefOf`×3 / `buildQuoteIndex`×10 / `buildReplyChain`×11 / `chainDepthOf`×11 / `stripQuoteMarkup`×4 | 见下（先过 `parseBBCode`） | `QuoteRef[]` / 索引 / `ChainNode[]` / AST |
| `money` | `core/local/money.ts` | `splitMoney`×7 / `formatMoney`×7 / `toReputation`×5 / `formatReputation`×5 | `{ copperTotal }` / `{ raw }` / `{ reputation }` | `Money` / 字符串 / 数字 |
| `hot-topics` | `core/local/hot-topics.ts` | `aggregateHotTopics`×11 | `{ pages, options: { now, windowHours? } }` | 排好序的候选主题数组 |
| `api/topic-list` | `core/api/topic-list.ts` | `parseTopicList`×9 / `hasTopicListStructure`×7 / `rejectNonTopicList`×4 / `mergeTopicPages`×1 / `serverEmptyTopicList`×1 | 管线 / 值 | `TopicList` 等 |
| `api/topic-detail` | `core/api/topic-detail.ts` | `parseTopicDetail`×5 / `parseAvatarUrl`×6 | 管线（`args.context`）/ `{ raw }` | `TopicDetail` / 字符串\|`null` |
| `api/board-tree` | `core/api/board-tree.ts` | `parseBoardTree`×3 / `pickActiveAnnouncement`×3 | 管线（`part: "root"`, `envelope: "bare"`）/ `{ announcements, now }` | `BoardTree` 或 `{ throws }` |
| `api/board-favor` | `core/api/board-favor.ts` | `parseBoardFavorites`×3 / `parseBoardIdInput`×7 | 管线 / 字符串 | `Board[]` / 数字\|`null` |
| `api/topic-favor` | `core/api/topic-favor.ts` | `parseFavoriteFolders`×3 | 管线 | `FavoriteFolder[]` |
| `api/notifications` | `core/api/notifications.ts` | `parseNotificationFeed`×7 / `notificationKind`×11 | 管线或值 / `{ type }` | `NotificationFeed` / 分类名 |
| `api/user-profile` | `core/api/user-profile.ts` | `parseUserProfile`×5 | 管线（`args.nowSeconds`） | `UserProfile`\|`null` |
| `api/search` | `core/api/search.ts` | `parseBoardSearch`×2 / `parseUserSearchInput`×6 | 管线 / 字符串 | `BoardSearchItem[]` / `UserSearchQuery`\|`null` |
| `api/block-word` | `core/api/block-word.ts` | `parseBlockWords`×8 / `serializeBlockWords`×2 / `blockWordError`×4 | 值 / `BlockWordList` / `{ text, label? }` | `BlockWordList` / 字符串 / 字符串\|`null` |
| `api/sub-board` | `core/api/sub-board.ts` | `subBoardState`×10 / `subBoardOptionParam`×4 / `nextSubBoardState`×2 | `{ attributes }` / `{ action, filterType }` / `{ state, action }` | `SubBoardState` / `"add"\|"del"` |
| `api/fields` | `core/api/fields.ts` | `orderedEntries`×8 / `orderedValues`×8 / `str`×6 / `text`×4 / `int`×7 / `nonZero`×4 | `{ value }` / `{ record, key }` | 见下 |

### 逐 domain 的细则

**`sanitize`** — 纯字符串变换，**不做 JSON 解析**。`expected` 就是洗完的文本，
Kotlin 侧逐字符比。真实抓包那几条（`capture-*`）的 `input` 是响应字节按
`decodeResponseBody` 解出来的文本，不是原始字节。

**`envelope`** — `expected.data` 是信封的 `data` 字段（`null` 表示 `undefined`，
即「只有 error 的响应」）。**`root` 故意不进 `expected`**：它等于
`JSON.parse(sanitize(text))`，已经被 `sanitize` domain 锁住了，再存一份让每条 golden 体积翻倍。
`shape` 缺省是 `"wrapped"`——顶层既没 `data` 也没 `error` 一律抛 `kind:"parse"`
（2026-08-13「版块全空」排查：以前把顶层当 data，任何陌生 JSON 都变成「合法的空数据」）。

**`decode-body`** — 03 票的验收依据，分支必须逐条对上：
- 声明了 GBK 家族（`gbk/gb18030/gb2312/x-gbk/csgb2312/gb_2312-80`）→ 按 GB18030 解；
- 声明了 UTF-8 家族 → 按 UTF-8 解；
- **不认识的 charset 不硬用**，退回投票（`unknown-charset-falls-back-to-vote`）；
- 未声明 → 先试 UTF-8，**一个 U+FFFD 都没有就直接采纳**（`undeclared-utf8-body`）；
- 未声明且 UTF-8 出了 U+FFFD → 再解一遍 GB18030，**替换字符少的那边胜出，平手留 UTF-8**
  （`undeclared-gbk-body`、`undeclared-both-lossy`）；
- 最后一律剥 BOM（`strip-bom`、`strip-bom-gbk`）。
真机样本：`capture-thread-list-undeclared-gbk`（thread.php 是全仓唯一不声明 charset 的）、
`capture-read-thread-declared-gbk`、`capture-read-web-not-found-gb18030`、
`capture-thread-list-414-broken-bytes`（**服务端下发的字节本身就坏**，期望里就该有 U+FFFD——
这是「fid=414 打不开」的根因，不是解码器的 bug）。
`gbk-*` 那 9 条锁的是 GB18030 的**框法**（票 03 补，非 fixture 的合成字节）：
`A3 A0` = 全角空格 U+3000、单独的 `0x80` = `€`、坏字节处「把尾字节退回流里重新解析」。
**JDK 的 `CharsetDecoder` 在这三处与 WHATWG 不一样**，而未声明 charset 时正是按
U+FFFD 个数投票选编码的，多吞一个字节就可能翻盘——所以 Kotlin 侧的做法是
**表问 `Charset.forName("GB18030")` 要、框法照抄 WHATWG 状态机**，两处补丁写在
`core/net/encoding/Gb18030.kt` 的文件头。

**`web`** — Web 反解档（票 08，ADR-0002 / API 文档 §0.8）。`input.text` 是
`read.php` **不带格式参数**拿回来的整页 HTML（已过 `decodeResponseBody`，不是原始字节），
`expected` 是信封的 `data`——与 `__output=8` 同构，下游 `parseTopicDetail` 直接吃。
**`root` 故意不进 `expected`**：`parseReadPageHtml` 的 `root` 就是 `{ data }`（理由同 `envelope`）。

- 三条 2026-08-08 抓包（`anonymous-hot-reply` / `comment` / `attachments`）分别覆盖
  匿名主楼 + 热门回复、贴条、附件 + 编辑记录。
- `revalidate-45150945` 是 **2026-08-22 票 08 重验**时抓的：同一时刻同一主题**并发**
  抓了网页 HTML 与 `__output=8` 两份，逐字段对齐确认
  `commonui.postArg.proc` 参数位置表**没变**（对拍用例在 `src/core/net/web/read-html.test.ts`，
  JSON 那一份是 fixture `readJsonRevalidate`）。
- `not-found` 是**坏样本，故意留着**（ADR-0002 第 9 条）：服务端语义错误夹在
  `<!--msgcodestart-->` 注释标记里 → `kind:"server"`、**不可重试**。
  2026-08-22 重抓同一请求，字节与 2026-08-08 那份**完全相同**。
- `no-floors` / `empty-body` 是合成向量：一楼都没反解出来 = 大概率被封 →
  `kind:"parse"`、**可重试**，链要接着往下走而不是当场收手。

**已知不可恢复的三个字段**（网页版本来就不给，Kotlin 侧照抄，不要试图补）：
投票（`vote`）、贴条与热门回复的发帖设备（`from_client`）、
第 2 页及以后的匿名楼主标记。详见 `core/net/web/ReadHtml.kt` 的 KDoc。

**`bbcode`** — `input` 是楼层 `content` 原文，`expected` 是 29 种节点的 AST。
`coverage-*` 是渲染器覆盖清单（`src/ui/bbcode/coverage.test.ts`）的样例，**一种节点一条**；
`coverage-all-joined` 把它们拼成一段长正文。特别注意：
- `deep-nesting-5000`：5000 层 `[b]`。`MAX_NESTING_DEPTH = 64`，超出的开标签退化成文本。
  **Kotlin 直译最容易在这条上爆栈**，归一化（`normalize`）也是递归的。
- 解析器**永不抛异常**：未知标签降级文本、EOF 未闭合上提子节点。
- `[code]`/`[url]`/`[img]` 这类 raw 标签内不解析标签但要做实体解码，且不许越过外层闭标签
  （`unclosed-code-does-not-swallow`）。

**`dice`** — `input` 就是种子的三个来源加正文：`{ text, authorId, tid, pid }`。
Kotlin 侧要先 `parseBBCode(text)` 再 `resolveDice(ast, seed)`。
`expected` 是 **outcomes 按文档顺序**（= Map 的插入顺序）的数组，每项
`{ expression, terms, sum? }`；`sum` 缺席 = OUT OF LIMIT（>10 颗或面数 >100000）。
一楼内所有 `[dice]` **共用一条随机流**，折叠块另起一条——`shared-stream-*` /
`collapse-*` 就是锁这个的。`real-post-*` 的期望值来自站上真帖楼主自己报的点数。

**`anonymous`** — `hex[5] 官方就是跳过的`、百家姓表只有 255 字所以 `0xff` 掉字符
（`decode-hex-all-f` 的期望名只有两个字），**照抄，勿修**。

**`attachments`** — 各 fn 的 input：
`normalizeAttachBase` `{ raw }`、`stripThumbnailSuffix` 字符串、
`rehostLegacyAttachment` `{ src, base }`、`thumbnailUrl` `{ url, base }`、
`attachmentUrl` `{ ref: { src, needsAttachBase }, options: { base, postedAt? } }`、
`imageFileName` / `imageMimeType` 字符串。
`dated-directory-is-utc-plus-8` 锁的是「日期目录按论坛时区算，不跟设备时区」——
Kotlin 侧别用系统默认时区。

**`query`** — `input` 是 `{ "params": [[key, value], …] }`，**有序的键值对列表而不是对象**：
规范化会把对象键排成字典序，而 `buildQueryString` 拼出来的串是**插入序**
（`build-query-string-post-form-same-rules` 那条就靠这个区分）。数组顺序规范化不动，所以用列表。
`gbk()` 标记的值在 JSON 里就是它的运行时形态 `{ "charset": "gbk", "value": "原神" }`；
`null` = 该参数被显式删掉（TS 的 `null` 与 `undefined` 在这里同档）。
规则：空值参数（`null`/`undefined`/空串/`false`/空 gbk 值）**必须从 query 中删除**，
`true` → `1`，数字 `0` 保留。`hasGbkParam` 命中 ⇒ 声明 `charset=GBK` 且**撤掉 `__inchst=UTF8`**。
后两条判据落在 `attempt.ts` 的未导出函数里，进不了金样本；Kotlin 侧抠成了
`core/net/OutboundCharset.kt` 的纯函数，用例手工移植（票 03）。

**`filters`** — 各 fn 的 input：
`normalizeRuleValue` `{ value, regex }`、`filterRuleId` `{ origin, kind, value }`、
`topicCategories` 标题字符串、`validateFilterRule` / `createFilterRule` 的 `input` 是
`FilterRuleInput`（后者外面再包一层 `{ input, nowSeconds }`）、
`upsertFilterRule` `{ rules, rule }`、`removeFilterRule` `{ rules, id }`、
`matchFilterRules` `{ rules, subject }`（expected 是命中的那条规则或 `null`）、
`filterMatchText` `{ rule }`。
两条铁律：**匹配一律大小写不敏感**；**非法正则永不命中、也永不抛**
（`match-filter-rules-invalid-regex-*`）。
⚠ `validateFilterRule` 的**非法正则**那一档没导：返回文案里嵌着 JS 引擎的
`SyntaxError.message`，JVM 的措辞是另一套，拿它对拍等于把引擎实现钉死。
Kotlin 侧只需保证前缀是「正则表达式不合法：」，票 10 用手写单测锁。
`compileFilterRegex` 同样没导——它返回 `RegExp`，不是 JSON 值。

**`reply-chain`** — `input` 里的 `text`/`floors[].content` 是**楼层正文 BBCode 原文**，
Kotlin 侧要先 `parseBBCode` 再喂给被测函数（同 `dice`）。
`extractQuoteRefs` / `stripQuoteMarkup` 收整段节点；`quoteRefOf` / `isReplyHeaderNode` /
`replyHeaderRefOf` 收 `parseBBCode(text)[0]`（第一个节点）。
`buildQuoteIndex` / `buildReplyChain` / `chainDepthOf` 的 input 是
`{ floors, tid?, startPid? }`，Kotlin 侧先 `buildQuoteIndex(floors, tid)` 再往下走。
`buildQuoteIndex` 的 expected **把 Map/Set 拍平**成 `{ quotes: [[pid, refs], …],
quotedBy: [[pid, [pid, …]], …], loaded: [pid, …] }`，键都按升序（JSON 装不下 Map）。
锁住的怪癖：嵌套引用只认外层、引用自己与跨帖引用不进索引、下游按楼号排、环引用不死循环。

**`money`** — 纯显示换算（API 文档 §11.1）。`splitMoney` 负余额按**绝对值**拆再标
`negative`（直接对负数取模会拆出 `-1.-2.-3` 这种读不出来的东西）；小数先截断成整数铜币。
⚠ `splitMoney(NaN)` 没导（README 规范 4：金样本里不允许非有限数字），
Kotlin 侧「非有限值 → 0」由票 10 手写单测锁。

**`hot-topics`** — 本地聚合，**不是服务端 API**。`options.now` 固定传进来，函数里不看表。
锁住的判据：窗口过滤看**发帖时间**而不是最后回复（被顶起来的老坟不进榜）、
跨页按 tid 去重、排序是「回复数降序 → 最后回复时间降序 → tid 升序」（最后一档保证结果确定）、
合集镜像行（`shortcut`）与外链活动主题（`jumpUrl`）不进榜。

**`api/fields`** — `orderedEntries`/`orderedValues` 的 input 是 `{ value }`；
`str`/`text`/`int` 是 `{ record, key }`；`nonZero` 是 `{ value }`。
`orderedEntries` 的 expected 是 `[[key, value], …]`。**真数组也要当列表遍历**
（`__output=11` 的 `__T` 是货真价实的 JSON 数组，不认它整页主题会静默变 0 条）。
⚠ `mixed-keys` 那两条里非数字键的相对顺序**只能是字典序**：规范化会把 `input` 的对象键
排成字典序，「JS 对象字面量的插入序」这个信息在文件里存不下来，写成 `b` 在 `a` 前面的话
任何从 JSON 读回入参的实现都对不上（票 04 发现，与 `query` 那次同源）。
「插入序 ≠ 字典序时仍保持插入序」那一半跨不过 JSON，由 Kotlin 侧手写单测锁
（`core/api/FieldsTest.kt`）。

### `api/*` 的输入管线

`api/*` 里凡是解析响应的 fn，`input` 有两种形态，**看有没有 `text` 字段**：

```jsonc
// 形态 A：走管线。Kotlin 侧：parseNgaJson(text, "golden", envelope) → 取 part → 喂 fn
{
  "text": "<响应解码后的文本>",   // 已经过 decodeResponseBody，不是原始字节
  "envelope": "wrapped",         // parseNgaJson 的 shape
  "part": "data",                // "data" 或 "root"（board-tree 读顶层）
  "args": { "context": "golden" } // 选填：fn 的第二个参数
}

// 形态 B：直接喂值（合成向量，不经网络层）
{
  "value": <解析器的入参>,
  "args": { … }                  // 选填
}
```

固定的 `args`：
- `parseTopicDetail`：`{ "context": "golden" }`（请求级 context，给匿名用户 id 加前缀，
  同一次请求内必须一致、不同请求之间必须不同）。
- `parseUserProfile`：`{ "nowSeconds": 1786100000 }`（判禁言是否在有效期内的基准时刻；
  不固定它期望值就不可复现）。

其余 fn 的 input 直接就是它的入参（字符串或对象），表里已逐一列出。

## 语料来源

- `src/core/api/__fixtures__/*.gbk.bin`、`src/core/net/__fixtures__/*.gbk.bin` ——
  2026-08-07 / 08-08 从 bbs.nga.cn 抓的**原始响应字节**（多数是 GBK）。已脱敏：
  抓包账号 uid → `10000001`，用户名 → `nga_user`；cookie / cid 不在响应体里。
  每条 fixture 的 `note` 原样带进了 golden 的 `note` 字段。
- 各 `*.test.ts` 里内联的输入（BBCode、投票串、链接、屏蔽词表…）——
  `src/ui/bbcode/coverage.test.ts` 与 `src/core/bbcode/parse*.test.ts` 的用例**全部**在内。
- 逆向算法（骰子、匿名、彩色标题、投票）的期望值来自 NGA 官方前端脚本跑出来的输出，
  或站上真帖楼主自己报的结果——**不是本实现自证**。

## 不在金样本里的东西（票 05 明确排除）

- **策略链等控制流**（`core/net/strategies/**`、`combo.ts`、`fetcher.ts`、`diagnostics.ts`）——
  有 IO、有时序、有状态，走不了「输入→输出」的对拍。关键回归**手工移植**（票 06/04）。
- 出站请求的 URL 装配（`attempt.ts` 里未导出的 `buildUrl`：`__inchst` 撤销 + 格式档参数 +
  Referer/UA/双通道认证）——同上，靠 `query` domain 锁住编码那一半，其余手工移植。
  票 03 已把可纯化的那两条（`__inchst` 撤销、POST `Content-Type` 切 GBK）抠成
  `core/net/OutboundCharset.kt`，用例手工移植自 `fetcher.test.ts` / `search.test.ts` /
  `block-word.test.ts`（见 `OutboundCharsetTest.kt`）。
- `validateFilterRule` 的**非法正则**分支与 `compileFilterRegex`——前者的文案里嵌着
  JS 引擎的 `SyntaxError.message`，后者返回 `RegExp`；都不是跨实现可比的值。
- `splitMoney(NaN)`——金样本里不允许非有限数字（规范 4）。
- 存储层（`core/local/{topic-cache,history,settings,notifications,…}`）。
- Web 反解的**扫描器**（`core/net/web/html-scan.ts`：引号感知括号匹配、标签深度追踪、
  JS 对象字面量）——它的边界用例全出自用户内容，`input`/`expected` 都是短字符串，
  写成金样本不如直接手工移植（`app/src/test/kotlin/com/chasel/ng2n/core/net/web/HtmlScanTest.kt`）。
  反解本体 `parseReadPageHtml` **已经在 `web` domain 里**（票 08 重验后加入）。

## 约定

> 后续每张 M1/M2 票验收项里写的「**金样本对拍通过**」，指的就是这套管线：
> 该 domain 的全部 case 由 Kotlin 实现逐条跑出与 `expected` 深度相等的结果，
> 差异输出可读 diff。跳过、`@Ignore`、放宽比较口径都不算通过。
