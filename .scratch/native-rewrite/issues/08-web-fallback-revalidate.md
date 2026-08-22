# 08 — Web 反解移植与线上重验(M1)

**What to build:** 两步,顺序不能反:
① **先对线上重新抓包**:同一主题页并发抓 `read.php` 的 JSON(`__output=8`)与 HTML(无格式参数)响应,验证 `commonui.postArg.proc` 参数位置表(RN 版 `read-html.ts:52-68`,全网无第二份文档,最易被 NGA 改版打掉)是否仍有效;好/坏样本各留、脱敏后进 fixtures(坏字节样本**故意留着**——ADR-0002 第 9 条)。
② 移植 HTML 反解:引号感知括号深度匹配器 + 标签深度追踪;抓 `postArg.proc` / `userInfo.setAll` / `__PAGE`+`setDefault` 交叉校验 / `<!--msgcodestart-->` / `attach.load` / `loadAlertInfo` / `__ATTACH_BASE_VIEW`;输出与 `__output=8` 同构信封、标 `source='web'`;已知不可恢复字段清单照抄(投票内容、嵌套贴条/热回 from_client、第 1 页外匿名楼主标记)。

**Blocked by:** 06

**Status:** resolved

- [x] 抓包重验结论写进本票 Comments(参数表变没变、变了怎么改)
- [x] 金样本 + 新抓样本双份对拍通过
- [x] `only` 模式失败改写为不可重试的语义有用例

## Comments

### 1. 抓包重验结论(2026-08-22)——**参数位置表没变,原样移植**

抓包纪律:`bbs.nga.cn`,`X-User-Agent: Nga_Official` + WP UA(`NGA_WP_JW/(;WINDOWS)`),
凭证取仓库既有的 `.env.local`(与当初抓 fixtures 同一账号)。
**共 4 发请求,分两批并发,两批之间冷却 1 分 51 秒**(T6 限流),没有一发失败、没有触发限流。

| 批次 | 请求 | 用途 |
|---|---|---|
| 1(15:53) | `read.php?tid=45150945&page=1&__output=8` + `read.php?tid=45150945&page=1` | 同刻两份,逐字段对齐参数表 |
| 2(15:56) | `read.php?tid=47328470&page=1` + `read.php?tid=1&page=1` | 富样本结构对拍 + 错误页 |

**结论三条:**

1. **参数位置表逐位相同**,`read-html.ts:52-68` 的 `ARG` 一个位置都不用改。
   实参个数仍是 23(带贴条那种是 24),`key=0 / subject=2 / content=3 / info=6 /
   pid=10 / type=11 / authorId=13 / postedAt=14 / scores=15 / contentLength=16 /
   fromClient=19` 全部对上。
   证据不是「肉眼看着像」——是拿**同一时刻同一主题**的 `__output=8` 当基准,
   20 楼 × 10 个可恢复字段(pid / lou / authorid / postdatetimestamp / type /
   content_length / from_client / score / subject / content)逐条比,**零差异**;
   `__T`(tid / subject / authorid / author)、`__F.name`、`__PAGE`、`__ROWS`、
   `__R__ROWS_PAGE`、`__GLOBAL._ATTACH_BASE_VIEW`、`__U` 的键集合同样全部相等。
   这条对拍已经落成常驻用例(`src/core/net/web/read-html.test.ts`,吃两份新 fixture),
   不是一次性跑完就扔。

2. **模板没有改版**。tid=47328470 在 2026-08-08 有一份样本,今天同一 tid 再抓一份,
   逐项对比:`postArg.proc` 调用数 24=24、实参个数集合 {23,24}={23,24}、
   `setDefault` / `userInfo.setAll` / `attach.load`(3)/ `loadAlertInfo`(1)/
   `__PAGE` / `__CURRENT_TID` / `__CURRENT_PAGE` / `__ATTACH_BASE_VIEW` /
   `currentTopicName` / `currentForumName` / `hightlight_for_` 出现次数全部相同。
   `setDefault` 只有**载荷**变了(推荐/投票那一长串 id 列表长了),末四位与第 4 位
   (楼主 id)结构不变。

3. **错误页字节完全没动**。tid=1 今天重抓,与既有 fixture
   `read-web-not-found.gbk.bin` **逐字节相同**(`cmp` 无输出)。
   所以没有再存一份重复的坏样本——那一份既是 2026-08-08 的,也是 2026-08-22 的,
   继续故意留着(ADR-0002 第 9 条),并把这件事写进了 fixture 的注释。

**要改什么:没有。** 这一票的移植是纯直译,没有因为改版而偏离 RN 版任何一处。

### 2. 完成摘要

**RN 侧(commit 2966fe0):**
- 新 fixture 两份(脱敏:uid → 10000001、用户名 → nga_user;响应体里本来就没有 cookie/cid,
  已断言):`read-web-revalidate-45150945.gbk.bin`(网页 HTML)与
  `read-json-revalidate-45150945.gbk.bin`(同刻 `__output=8`,对拍基准)。
- `read-html.test.ts` 加一组「与同刻 `__output=8` 逐字段相等」的用例。
- `scripts/export-goldens.mts` 加 `web` domain(7 条),`pnpm goldens:export` 重跑并验幂等
  (连跑两次零 diff);goldens README 补 `web` 行 + 细则,总数 762 → 769、28 → 29 个 domain。
- `pnpm test` 1128 passed / 14 skipped(skip 全是既有的),`tsc --noEmit` 干净。

**Kotlin 侧(commit 8db15d1):**
- `core/net/web/HtmlScan.kt`、`core/net/web/ReadHtml.kt`——直译
  `src/core/net/web/{html-scan,read-html}.ts`。
- `strategies/WebFallback.kt` 的默认反解器换成 `ReadHtmlWebParser`,这一档接实。
- 测试 4 个类:`ReadHtmlGoldenTest`(`web` domain 全量)、`ReadHtmlTest`(14 条语义断言)、
  `HtmlScanTest`(8 条移植 + 5 条直译边界)、`WebFallbackTest` 补 4 条。
- `./gradlew :app:testDebugUnitTest`:58 个类 471 条,**0 失败**
  (1 条 skip 是票 06 的联网冒烟,按 env 开关,不是我加的)。

### 3. 关键决定

- **对拍口径自检**:把 `ArgIndex.SCORES` 从 15 故意改成 14,`web` 金样本当场
  「7 条中 3 条不匹配、22 处差异」且差异全部指在 `score` 上,证明 harness 不是空跑。
  改回后全绿。
- **`only` 档的两条语义各一条用例**(票面验收项 3):
  可重试的失败(网页版也被封 → `kind:parse`)被改写成 `retryable=false`、链当场收手、
  原生接口一次都不打;**本来就不可重试**的服务端错误(msgcode 2048)**原样上交不重新包**
  ——改写那条路会丢掉 `code`,而错误页要拿它显示「2048 找不到主题」。
  另补一条反面:`secondary` 档反解失败后错误照旧可重试,链上后面的帖子缓存档轮得到。
- **`UnavailableWebReadParser` 留着没删**。它原本是票 08 的占位,现在的用处是给
  「反解器缺席时让位、不白打一次请求」这条规则留回归线——NGA 将来改版把反解打掉时,
  这一档要能靠 `available=false` 干净退场。
- **Kotlin 侧的语料取金样本的 `input.text`**:classpath 上只有 goldens,没有 `.gbk.bin`,
  所以 `ReadHtmlTest` 与 `WebFallbackTest` 的真实 HTML 都从 `Goldens.load("web")` 取。

### 4. 对 RN 版的有意偏离(语义不变)

直译时几处 JS 语义在 Kotlin 里不是同名 API 就能替的,单独处理并在注释里标了出处:

1. **数字**:JS 只有一种 number。内部一律 `Double`,落进信封时按 `JSON.stringify` 的口径
   ——整数值写成整数(`43` 而不是 `43.0`)。`String(n)` 同理。
2. **`unquote` 的兜底**:TS 版首字符不是引号时**原样返回**(`length < 2` 时 `slice(1,-1)`
   得到空串而不是越界),照抄了这个形状。
3. **`parseObjectLiterals` 的三选一**:TS 靠 `match[2] ?? match[3] ?? match[4]` 区分
   「匹配到空串」与「整支没匹配」,Kotlin 的 `groupValues` 一律给 `""`,所以改用
   `groups[i] != null`。`aid:''` 必须收成空串,专门补了一条用例钉住。
4. **附件表的键**:用 `forEachIndexed` 的下标,没有 `url` 的那条被跳过但它占掉的序号
   TS 也不补——键上因此可能有洞,**照抄**。
5. **分页那句 `Math.max(1, ceil(rows / perPage))` 用 Double 算**:每页 0 楼时 TS 得到
   `Infinity`、与任何页数都不相等而落到兜底分支,用整数除法会当场 `ArithmeticException`。
6. **用户表解出来是数组时也收下**:TS 的 `typeof parsed === 'object'` 把数组算在内。
   现实里没见过,但不敢替 NGA 做决定。

### 5. 未完成 / 边界

- **`source='web'` 不在本票**。它是端点层(票 07 的 `fetchTopicDetail`)按
  `result.via === 'web-fallback'` 推出来的,RN 版就在 `src/core/api/topic-detail.ts:395-402`。
  本票停在信封层,负责的是**把 `via = "web-fallback"` 正确交出去**——
  `WebFallbackTest` 里那条「真反解器把这一页整个救回来」断言的就是 `result.via`。
  **给票 07 的口径**:`via == WEB_FALLBACK_STRATEGY_NAME` → `source = web`;
  `via == TOPIC_CACHE_STRATEGY_NAME` → `source = cache`;其余 `native`。
- **不可恢复字段仍是三个**,没有试图补(KDoc 里逐条写了为什么补不了):
  投票内容、嵌套贴条与热门回复的 `from_client`、第 2 页起的匿名楼主标记。
- **坏样本没有新增一份**:重抓的错误页与既有 fixture 逐字节相同,存第二份只是重复。
  这一条是对票面「好/坏各一」的有意偏离,理由与证据见上面第 1 节第 3 条。

### 6. 发现的票外问题

- `core/net/strategies/FormatRotation.kt:96` 有个编译告警:
  `Unnecessary safe call on a non-null receiver of type 'ComboCache'`。
  票 06 的地盘,没动。
- `golden/GoldenFrameworkTest.kt:69` 的测试名里有 `"`,Kotlin 警告
  「Name contains character(s) that can cause problems on Windows」。本项目 Android-only,
  暂时无害,记一笔。
- 顺带记一条给后来人的坑:**Kotlin 反引号测试名里不能有 ASCII `:`**
  (JVM 方法名非法字符),编译期报 `Name contains illegal characters`。
  从 TS 用例移植名字时要换成中文逗号或破折号。

### 7. 需要所有者(真人)介入

无。抓包用的是仓库里既有的 `.env.local` 测试账号(与当初抓 fixtures 同一账号),
没有新增凭证需求;模拟器/真机这一票用不上。

**主控验收(2026-08-22)**:合并后主干 567 例全绿,goldens 重导幂等(769 条)。接受「坏样本不重复存」的偏离(逐字节同 fixture)。`source` 映射口径转交票 07。
