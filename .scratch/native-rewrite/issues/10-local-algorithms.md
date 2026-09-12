# 10 — 逆向本地算法包(M2;怪癖勿修)

**What to build:** 全部直译,**NGA 就这样,别修**(research/inventory.md §4):
- **骰子**:种子=authorId+tid+pid;LCG `state=(state*9301+49297)%233280`;宽松正则文法照抄;每项 ≤10 骰、≤100000 面;**一楼内所有 [dice] 共用一条随机流按文档顺序推进**;collapse 内 seedOffset 仅 tid>10246184。
- **匿名还原**:`#anony_<32hex>`;22 字天干地支 + 255 字百家姓;6 段固定偏移、**hex[5] 静默跳过**;255 表整字节索引越界掉字符照抄;颜色 hex[11:17]/[17:23]。
- **彩色标题**:掩码 1红2蓝4绿8橙16银32粗64斜128下划线;颜色互斥按优先级 if/else 链,样式叠加;`topic_misc` 无 padding base64 TLV(type+大端 u32;0 结束/1 掩码/2 stid/3 sfid)**优先于 titlefont**;sfid 有符号修正只对 fid/sfid、绝不对 stid/tid。
- **附件 URL**:base 从 `__GLOBAL._ATTACH_BASE_VIEW` 动态取(兜底 img.nga.cn/attachments);noimg 缺日期目录按 postedAt **固定 UTC+8** 合成;死域名替换;四种缩略后缀。
- **深链解析**:read.php/thread.php 映射、`&amp;`、percent、`#pid123Anchor`、foreign-host 拒绝;同一映射表服务系统深链与「由 URL 读取」。
- **投票解析**:`Floor.vote` 的 `~` 分隔 kv → 只读渲染模型。

**Blocked by:** 05

**Status:** resolved

- [x] 各算法全部 fixtures 金样本对拍零差异(尤其骰子共享随机流、匿名越界 case、TLV 边界)
      —— 10 个 domain 348 条全绿;`./gradlew :app:testDebugUnitTest` 179 tests / 0 failures

## Comments

### 完成摘要(2026-08-22)

10 个 domain 的金样本 **348 条全绿零差异**,外加 `*.test.ts` 里不在金样本的用例手工移植
(共 44 个 Kotlin 测试方法)。`./gradlew :app:testDebugUnitTest` 全仓 179 tests / 0 failures。

| domain | 条数 | 落地文件 |
|---|---|---|
| dice | 23 | `core/local/Dice.kt` |
| anonymous | 30 | `core/local/Anonymous.kt` |
| title-style | 35 | `core/local/TitleStyle.kt` |
| attachments | 44 | `core/api/Attachments.kt` |
| deep-link | 56 | `core/local/DeepLink.kt` |
| vote | 24 | `core/local/Vote.kt` |
| filters | 49 | `core/local/Filters.kt` |
| reply-chain | 52 | `core/local/ReplyChain.kt` |
| money | 24 | `core/local/Money.kt` |
| hot-topics | 11 | `core/local/HotTopics.kt` |

另加两个支撑件:`core/local/JsText.kt`(JS 口径的 trim / `\s` / `Number(string)`)、
`core/net/encoding/PercentDecode.kt`(`decodeURIComponent` 等价物)。

### 关键决定

1. **票 09 未合并的接缝(主控合并后由票 11/13 收口)。**
   - `resolveDice` 的输入抠成 `DiceScope`(本作用域的 dice 表达式序列 + 折叠块子作用域),
     不吃 AST:Kotlin 的 data class 是结构相等,当不了「按节点身份查点数」的 Map 键。
     `DiceScope.flatten()` 与 `resolveDice()` 同序,票 11/13 拿它把结果贴回节点(有单测锁)。
   - `reply-chain` 的函数对节点类型泛型,AST 表面抠成 `BBCodeShape<N>` 适配器(5 个方法:
     `typeOf` / `childNodeLists` / `textValue` / `floorRefArgs` / `floorRefPid`)。
     票 09 落地后写一个 `object : BBCodeShape<BBCodeNode>` 即可,**`core/local` 的函数本身不动**
     (`stripQuoteMarkup` 返回的仍是原节点)。
   - 对拍测试里临时用 `src/test/.../core/local/MiniBBCode.kt`:只认
     `[dice]`/`[collapse]`/`[quote]`/`[b]`/`[pid]`/`[uid]` 与 `<br/>` 的极简抽取器,
     节点 JSON 形状照 `goldens/bbcode/coverage-*.json` 抄。**文件头标了 TODO(票 11/13):
     票 09 合并后删掉它,换正式 `parseBBCode`。**
2. **`buildQuoteIndex` 收「已抽好的引用」而不是正文**(TS 版在函数里调 `parseBBCode`)——
   同一个原因,避免把解析器拖进 `core/local`。调用方写
   `extractQuoteRefs(parseBBCode(content), shape)`。
3. **`attachmentUrl` 收 `src` + `needsAttachBase` 两个字段而不是 `AttachmentRef` 类型**:
   那个类型是票 09 `core/bbcode` 的东西,这一层不该抢先定义它。
4. **日期目录固定 UTC+8**:`java.time.Instant.atOffset(ZoneOffset.ofHours(8))`,不碰系统时区。
5. **JS 语义的三处显式复刻**(JVM 默认口径与 RN 版对不上,不复刻就是偶发差异):
   - 空白:Kotlin 的 `trim()` 走 `Character.isWhitespace`(不算 NBSP/BOM、却算 U+001C–1F),
     Java 正则 `\s` 只有 ASCII 六个 → `JsText.kt` 按 ECMA-262 表逐字符照抄;
   - `Number(string)`:JDK 认 `1d`/`0x1p3` 而 JS 不认,JS 认 `0x10`/`Infinity` 而 JDK 不认;
   - 正则大小写不敏感:`CASE_INSENSITIVE` **必须叠 `UNICODE_CASE`** 才等价于 JS 的 `i`。
6. **base64 手写不用 `java.util.Base64`**:NGA 发的是无 padding 变体,末尾余位直接丢,
   JDK 解码器对残缺尾组的口径不保证与 TS 那段手写循环一致。
7. **`decodeURIComponent` 手写不用 `URLDecoder`**:后者是 form-urlencoded 口径(裸 `+` → 空格)。
8. 掩码位运算前按 JS 的 `ToInt32` 折一次(`mask.toInt()`):`topic_misc` 的掩码按无符号读出来
   可以是 `4278190113`,JS 侧 `&` 之前会折成负数,`and` 的结果一样——照抄,别改成无符号比较。

### 已知缺陷修复:P3-05(用户正则资源上限)

**RN 版原行为**:`filters.ts` 把用户手输的串直接 `new RegExp(pattern, 'i')` 拿去 `test()`,
没有长度限制、没有复杂度检查、没有匹配预算,编译缓存是一张无界 `Map`。
`(a+)+$` 这类嵌套量词碰上长正文就是指数级回溯,在 JS 上表现为整个 UI 线程卡死。

**修了什么**(`core/local/Filters.kt`,四道闸 + 有界缓存):

1. `MAX_RULE_VALUE_LENGTH = 256` —— 规则文本长度上限,`validateFilterRule` 存之前就挡。
2. `MAX_REGEX_PATTERN_LENGTH = 256` —— pattern 长度上限,`compileFilterRegex` 直接不编译。
3. **嵌套量词粗检**(`NESTED_QUANTIFIER`):一个带量词的分组、组内还有量词
   (`(a+)+`、`(\d*|x)+`、`(a{1,9})*` 这一族)一律拒。只做粗检,漏判的由第 4 条兜底。
4. **匹配步数预算 `MAX_REGEX_STEPS = 200_000`**。**Kotlin/JVM 没有原生正则超时**,
   我选的策略是:`Matcher` 读输入**只经过** `CharSequence.charAt`,所以给它喂一条会计数的
   `CharSequence`,超预算就抛内部信号、按「不命中」处理。比墙钟超时更可复现——
   同一条规则 + 同一段正文,在快机器和慢机器上是同一个结果。
5. **`MAX_REGEX_INPUT_LENGTH = 20_000`**(实测补上的第四道)。`java.util.regex` 的 `Loop`
   是**递归**实现,栈深度随重复次数走,JVM 没有「最大回溯深度」旋钮:4000 字的正文 +
   `(?:a|aa)+b` 会先炸 `StackOverflowError`,**根本轮不到步数预算**(第一次跑 P3-05 用例时
   实测到的)。所以先砍输入,再在 `matchesWithinBudget` 里把 `StackOverflowError` 也接住
   ——爆栈发生在纯正则递归里,栈干净展开,没有持锁或改到一半的状态。
6. 编译缓存改成容量 64 的 LRU(`LinkedHashMap` accessOrder + `removeEldestEntry`),
   并加锁:RN 版那张无界 `Map` 本身就是个泄漏面。

**对 RN 版的有意偏离**:超长正文的**正则规则**只看得到前 20000 个字符(普通子串规则不受影响,
它没有这个风险);超长/病态规则一律不命中而不是卡死。两条都有手写单测锁
(`FiltersGoldenTest` 的 5 个 `P3-05 …` 用例)。goldens 里
`validateFilterRule` 的非法正则分支与 `compileFilterRegex` 按 README 本就没导出,其余 49 条全绿。

### 未完成 / 有意不做

- `attachments.test.ts` 的「版头 0 楼那张图」端到端那条:TS 侧是
  `fixture → decodeResponseBody → parseNgaJson → parseTopicDetail → parseBBCode → attachmentUrl`,
  前四段是票 07/09 的地盘。这里只把**末段**(老域名绝对地址 → 响应给的基址)钉住,
  同一组真实地址与期望值。票 07/09 落地后可以把整条接起来。
- `validateFilterRule` 非法正则的**文案全文**没对拍(README 明确排除:里面嵌着 JS 引擎的
  `SyntaxError.message`)。只锁前缀「正则表达式不合法:」,与 README 的要求一致。

### 发现的票外问题

1. **`core/net` 还没有官方域名常量表**。`DeepLink.kt` 里的 `NGA_HOSTNAMES` 是从
   `src/core/net/constants.ts` 的 `NGA_HOSTS` 抄的一份**本地副本**,文件里标了注释:
   票 06/07 落 `core/net` 常量后应改成引用它,别留两份。
2. `core/net/encoding/PercentDecode.kt` 是本票新加的文件(`decodeURIComponent` 等价物),
   放在票 03 的目录下但**是新文件、没动 `Gb18030.kt`**,合并不该冲突。
   它与 `Gb18030.kt` 的 `encodeUriComponent` 是一对,将来若票 06/07 也需要,直接用。
3. 测试侧新加了 `golden/GoldenNumbers.kt`(数字/`unknown` 型入参的取值助手)——
   `GoldenCase` 自带的只有字符串/布尔那几个。属于票 05 框架的自然延伸,新文件,不冲突。
4. 票 12 可能同时写了一个临时的 `AttachmentUrls`:本票的正式版是 `core/api/Attachments.kt`
   (7 个 fn 全量对拍),合并冲突请以本文件为准。

### 需要所有者(真人)介入的事项

无。本票零 Android 依赖、纯 JVM 单测,不需要登录、不需要真机。

**主控验收(2026-08-22)**:合并时与票 12 的 `AttachmentUrls.kt` 顶层函数重名,主控改为接口委托到本票 `Attachments.kt`(票 12 最小实现删除,`AttachmentUrlsTest` 仍跑)。合并后主干 313 例全绿。接受 P3-05 四道闸(含输入 20000 字上限的有意偏离)。跟进:`DeepLink.kt` 的 `NGA_HOSTNAMES` 待票 06 常量落地后改引用;`MiniBBCode.kt` 待票 11/13 换正式解析器。
