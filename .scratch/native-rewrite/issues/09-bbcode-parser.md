# 09 — BBCode 解析器(M2)

**What to build:** `core/bbcode/parse.ts` 直译 Kotlin:单遍扫描、显式帧栈、`MAX_NESTING_DEPTH=64`、**永不抛异常**(未知标签/不匹配闭合原样降级纯文本;EOF 未闭合保留开标签文本、子节点上提——内容永不丢失)。29 种节点(见 research/inventory.md §4)定义为 `@Serializable` 密封类(可 JSON 序列化,直接进 Room 帖子缓存)。HTML 实体**双重解码**(NGA 双重转义)、按 UTF-16 码元解十进制实体自然重组代理对、孤立代理清洗、命名实体仅六个;正文裸 HTML 除 `<br/>` 一律字面文本。不支持标签清单照抄(pre/hide/spoiler/randomblock/email 降级;防剧透=color=white 行为)。投票不进 AST(`Floor.vote` 的 `~` 分隔 kv 独立解析,票 10)。

**Blocked by:** 05

**Status:** resolved

- [x] coverage 覆盖表 29 类型金样本全绿
- [x] 全部 bbcode fixtures 对拍零差异
- [x] 长正文性能抽查:最长 fixture 解析耗时记录进 Comments(为票 13 的后台一次性转换提供预算)

## Comments

**完成摘要(2026-08-22)**

`core/bbcode/` 新增 7 个文件(零 Android 依赖):`Nodes.kt`(29 节点 + 序列化入口)、
`Internal.kt`(解析期中间节点与 `OpenTag`)、`Tags.kt`(标签清单两张表)、`Parse.kt`
(单遍扫描主循环)、`Normalize.kt`、`Walk.kt`、`JsCompat.kt`。`Entities.kt` 是票 03 的,复用未动。

单测 5 个类:`BBCodeGoldenTest`(bbcode domain 126 条全量)、`WalkTest`(手工移植
`walk.test.ts`)、`BBCodeCoverageTest`(手工移植 `coverage.test.ts` 里不属于渲染器的那一半)、
`ParseRobustnessTest`(「永不抛异常」与深度上限的不变量)、`BBCodeParsePerfProbe`(验收项 3)。

`./gradlew :app:testDebugUnitTest --rerun` → **51 例全绿、0 skipped、0 @Ignore**
(其中本票 18 例;`entities` 20 条与 `query`/`decode-body` 等票 03/05 的照旧全绿)。
`:app:assembleDebug` 通过。

**验收项 3:长正文解析耗时(JVM 桌面,MacBook,只做预算参考,不是性能裁决)**

预热 200 次后取 100 次平均:

| 语料 | 输入长度 | 解析 | 解析+序列化成 JSON |
|---|---|---|---|
| `deep-nesting-5000`(goldens 里最长,5000 层 `[b]`) | 35001 码元 | **1.82 ms** | 1.93 ms |
| `coverage-all-joined`(最长的**真实形态**语料) | 714 码元 | **0.043 ms** | 0.092 ms |
| `full-floor`(整段楼层正文) | 287 码元 | **0.018 ms** | 0.023 ms |

给票 13 的读法:真实楼层量级是 **0.02–0.05 ms/条**,一页 20 楼 ≈ 1 ms,
连「解析 + 序列化进 Room」一起算也就 2 ms 上下——**后台一次性预转换整页绰绰有余,
不需要分帧**。真机(小米 17 / ART)按 5–10 倍打折仍在一帧内。畸形超长正文
(35 KB、5000 层)也只有 1.8 ms,**没有指数级退化**。数字由 `BBCodeParsePerfProbe`
在每次单测时重新打印(stdout),断言口径故意放到 200 ms,只防退化、不做性能门禁。

**关键决定 1:序列化形态本身进对拍范围**

`BBCodeGoldenTest` 比的不是 Kotlin 对象,而是 `encodeBBCode(parseBBCode(input))` 出来的
`JsonElement`。理由:同一份 JSON 之后要进 Room 帖子缓存(票 13/14),类鉴别器取值
(`type`)与「可选字段缺席 vs null」是**契约的一部分**,只比对象比不到。
落地口径:`@SerialName` 与 TS 的 `type` 同名同值,`Json` 默认 `encodeDefaults = false`
+ 属性默认值 `null` ⇒ `collapse.title` / `td.width` / `box.punishment` 在没有时**整个缺席**。
`BBCodeCoverageTest` 另有一条 JSON 往返用例(序列化→反序列化→深度相等)。

TS 的 `StyleNode` 一个接口装 `bold/italic/underline/strike` 四种 `type`,Kotlin 密封层级里
拆成四个类;JSON 形态完全不变。

**关键决定 2:与 TS oracle 做了一轮「金样本之外」的差分对拍**

金样本只覆盖已知输入。开发中临时挂了一条差分管线(vitest 跑 TS `parseBBCode` 导出
JSON → Kotlin 侧逐条深比),喂了 **100 条金样本里没有的畸形/边界输入**:空标签、
半截标签、大小写混排的 raw 标签闭合、`[dice 2d6]` 的属性位、脏 `colspan`、标题/分割线
的所有边界(`===a===b===`、`======x======`、`=====`)、NBSP 与全角空格当分隔符、
孤立代理、`[b]`×70 等。**结果:只有下面「有意偏离」列的 3 处不同,其余逐字节一致。**
差分脚手架是临时的,已删,不进版本库(它要 `node_modules`,而 `native/` 那侧跑不了 vitest)。

**关键决定 3:`JsCompat.kt` —— 三处 JS 语义不许用 Kotlin 同名 API 顶替**

直译时最容易悄悄错的三处,单独成文件并写清出处:

1. **正则 `\s`**。JS 的 `\s` 含 NBSP(U+00A0)、全角空格、BOM 等一大票;
   **Java 正则的 `\s` 只有 `[ \t\n\x0B\f\r]` 六个 ASCII**。而 NGA 正文里
   `&nbsp;` 解出来的 NBSP 遍地都是,`[td width=1 ]`、`[dice 2d6]`、`[/b ]`
   全靠 `\s` 断句。所有正则里的 `\s` 一律换成显式字符类 `JS_SPACE_CLASS`。
2. **`String.trim()`**。同上含 NBSP;而 Kotlin 的 `trim()` 走 `Character.isWhitespace`,
   **恰恰把 NBSP 排除在外**。改用 `jsTrim()`(落点:`[img]`/`[album]`/`[dice]`/
   `[url]` 等的取值、`trimEdges` 的空白判定、标题内容判空)。
3. **`Number.parseInt(s, 10)`** 是「跳空白 → 可选符号 → 吃前缀数字」,`"2abc"` → `2`;
   Kotlin 的 `toIntOrNull()` 要求整串是数字。`[td colspan=2px]` 站上是有的,改用 `jsParseInt()`。

**对 RN 版的有意偏离(3 处,全部在差分对拍里逐条确认过)**

1. **归一化递归摊平中间节点**(`Normalize.kt`)。TS 的 `normalize` 只摊平**一层**,
   所以 `[tr][td]a[/td][/tr]`(外面没有 `[table]`)会把 `__td` 这个**解析期中间节点漏进
   AST**,`[stripbr][stripbr]x[/stripbr][/stripbr]` 会漏出 `__fragment`。它们不在 29 种
   节点里,序列化进帖子缓存就是脏数据(Kotlin 侧甚至根本无法表示)。改成递归摊平,
   上面两例分别落成 `[{text a}]` / `[{text x}]`。**有 `[table]`/`[list]` 正常包着的形态
   两边完全一致**(全部金样本 + 差分对拍为证)。
2. **`colspan`/`rowspan` 溢出饱和到 `Int.MAX_VALUE`**。JS 的 `parseInt` 对
   `colspan=99999999999999999999` 得到一个巨大但**有限**的浮点数(`1e20`),照样 `> 0`
   于是原样落进 AST;Kotlin 侧饱和成 `2147483647`。两边都只是「一个大得没意义的正数」,
   渲染结果一致;而 `Int` 让节点能进 Room。差分对拍里这是唯一的数值差异。
3. **全文小写副本只小写 ASCII**(`asciiLowercase`)。解析器要拿「全文小写副本」按下标
   找 raw 标签的闭标签(`lowerSource.indexOf("[/img]", from)`),而 `toLowerCase()` 在
   **JS 与 Java 上都会让个别字符变长**(`İ` U+0130 → `i` + U+0307,两个码元)——
   一旦变长,小写副本的下标就和原文对不上,`[code]` 截出来的内容整体偏移。
   标签名按正则只可能是 `[a-zA-Z0-9_]`,只小写 ASCII 既够用又不会错位。
   **这是 RN 版同样存在、只是没被触发过的隐患,顺手修掉。**

**照抄没动的怪癖(想「优化」的人先看这里)**

- `pending += literal`(未知标签透传)与标签分支**都不重置 `atLineStart`**,只有普通
  字符才重置——`[quote]===标题===` 能认出标题就靠这个,`[x]===y===` 同理。
- 未闭合的 `[url]`(bare 形态)会**降级成容器帧**而不是文本:`isRawTag` 为真但
  `readRawTag` 返回 null 后,`CONTAINER_BUILDERS['url']` 还在,于是压栈、EOF 时再降级。
  `[code]`/`[img]` 没有容器 builder,所以直接透传成文本(`unclosed-code-does-not-swallow`)。
- `readRawTag` 用的是 `[/name]` 的**精确串匹配**(不吃闭标签里的空格),而
  `CLOSE_TAG` 正则允许 `[/b ]`。两边不一致是 TS 原文如此,金样本没覆盖,保留。
- `matchHeadingOrDivider` 递归调 `parseBBCode`。看着危险,其实**深度恒为 1**:
  `={3,}` 贪婪吃光行首那串等号,捕获组必然从非 `=` 字符起步 ⇒ 标题不会自嵌套。
  加上 `MAX_NESTING_DEPTH=64`,AST 最深 64+1+64 ≈ 129 层,`normalize` 的递归安全
  (`deep-nesting-5000` 那条在这里只会看到 64 层)。推导写在 `Normalize.kt` 的 KDoc 里。

**性能上的一处主动优化**

主循环里普通文本改成**成段扫描**:吃掉当前字符后一次性并进 `pending` 直到遇到
`\n` `\r` `<` `[`,而不是逐字符 `append`。语义等价(吃掉第一个字符后 `atLineStart`
已经为 false,行首 `=` 那一档不可能再命中)。TS 侧靠 JS 引擎的字符串 `+=` 优化蒙混过去,
JVM 上不做这一步,几万字的正文会明显退化。

**没做 / 留给别的票**

- **表情映射**(`[s:ac:blink]` → 哪个 PNG)。解析器只按 TS 原样捞出 `[s:` 与 `]`
  之间的 `code`(不做实体解码),分类/名称怎么切、查不到怎么兜底全归票 12。
  金样本里的 `smiley-unresolved-*` 三条锁的就是这个接缝,本票只保证 `code` 切得对。
- **投票**不进 AST(票面已写),`Floor.vote` 的 `~` 分隔 kv 归票 10。
- **`[dice]` 只给表达式不复算**,复算(共享随机流、OUT OF LIMIT)归票 10;
  `BBCodeCoverageTest` 有一条用例钉住「dice 节点只有 `type` + `expression` 两个键」。
- **行内/块级归属**(`isBlockNode` / `splitIntoSegments`)是渲染器的事,归票 11;
  `coverage.test.ts` 里那两条移过去,本票只移了「29 种类型一个不少一个不多」。
- **集合类型**用的是 `List` 而不是 kotlinx-collections-immutable 的 `PersistentList`:
  `@Serializable` 对后者没有内建序列化器,而序列化形态是本票的硬约束。
  票 11 若要 Compose 稳定性,建议加 `@Immutable` 注解(节点本来就是不可变数据类)
  而不是换容器类型 —— **换容器要连 Room 缓存的编解码一起改,别顺手动**。

**发现的票外问题**

1. **金样本导出器可以再吃 100 条边界向量**。本票差分对拍用的那 100 条畸形输入
   (半截标签、大小写混排 raw 闭合、脏 `colspan`、标题分割线边界、NBSP 当分隔符、
   孤立代理…)现在只以「不变量断言」的形式活在 `ParseRobustnessTest` 里,
   **不是逐值对拍**。goldens 是生成物不许手改,建议主控让 05a 把这批向量加进
   `scripts/export-goldens.mts` 的 `bbcode` domain,以后重写解析器就有逐值网了。
   向量清单可从本票 commit 的 `ParseRobustnessTest.pathological` 里取。
2. `native/app/src/main/kotlin/com/chasel/ng2n/core/bbcode/package.kt` 这类骨架占位
   文件还在(票 03 的 Comments 也提过),本票新增实现同包共存无冲突,等各票填满后清一遍。

**需要真人介入的事项**

无。本票是纯函数,不需要登录、不需要真机;真机性能在票 19 统一裁决。

**主控验收(2026-08-22)**:合并后主干 197 例全绿。接受三处偏离(normalize 全深度摊平 / colspan 饱和 / ASCII-only 小写),均为 goldens 未覆盖的边界且修的是 RN 潜在缺陷。票外建议 1(100 条 pathological 向量进 goldens)记入 backlog,不阻塞。
