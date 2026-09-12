# 03 — 字符集策略(M1)

**What to build:** 用 JVM `Charset.forName("GB18030")` 替代 RN 版手写状态机,但**策略照抄**(research/inventory.md §3.3):
- 响应侧:有 charset 声明就信;无声明先试 UTF-8,出现 U+FFFD 再试 GB18030,按替换字符计数投票取少者。
- 出站侧:逐参数 `gbk()` 标记(目前只有 `forum.php` 的 `key`、block-word 的 `data`);GBK 表外字符(emoji)按 **UTF-16 码元逐个**写十进制 HTML 实体再 percent 编码;**任一 GBK 参数出现 → 整个请求撤掉 `__inchst=UTF8` 声明**、POST Content-Type 加 `;charset=GBK`。
四个环节漏一处 = 「偶发乱码」级难查 bug(移植风险 TOP5 #3)。

**Blocked by:** 01

**Status:** resolved

- [x] decode-body 全部 fixtures 金样本对拍通过(依赖票 05 的管线,可先手工样本起步)
- [x] emoji/表外字符出站编码与 TS 版逐字节一致
- [x] `__inchst` 撤销与 Content-Type 切换的用例覆盖

## Comments

### 2026-08-22 — 完成

**交付物**(`native/app/src/main/kotlin/com/chasel/ng2n/core/net/`,全部零 Android 依赖)

- `encoding/Gb18030.kt` — `decodeGb18030` / `gbkEncodeUriComponent` / `encodeUriComponent`
- `encoding/DecodeBody.kt` — `decodeResponseBody` / `parseCharset`(直译 `decode-body.ts`)
- `Query.kt` — `QueryValue`(`Text`/`Num`/`Flag`/`Gbk`)、`gbk()`、`queryOf()`、
  `hasGbkParam`、`buildQueryString`(直译 `query.ts`)
- `OutboundCharset.kt` — `inchstParam` / `formContentType` / `outboundQuery` / `outboundUrl`
  (`attempt.ts` 里 `buildUrl` 那两条可纯化的判据)

测试:`DecodeBodyGoldenTest`(30 条 golden)、`QueryGoldenTest`(16 条)、
`OutboundCharsetTest`(10 条手工移植)。`:app:testDebugUnitTest` 全绿,
33 tests / 0 failed / **0 skipped**。

**关键决定 1:没有用一句 `String(bytes, Charset.forName("GB18030"))`**

票里写的是「用 JVM `Charset.forName("GB18030")` 替代手写状态机,但**策略照抄**」。
开工先做了一轮整表对拍(JDK 17.0.20 的 GB18030 vs Node 的 `TextDecoder('gb18030')`,
也就是 RN 版逐序列对拍过的那个 WHATWG 实现):**表几乎一样,取字节的框法不一样**。

| | WHATWG / RN 版 | JDK 的 `CharsetDecoder` |
|---|---|---|
| `A3 A0`(**全角空格**) | U+3000 | U+E5E5(CP936 的老 PUA 映射) |
| 单独的 `0x80` | `€` | 非法字节 → U+FFFD |
| `81 30 41 42`(半截四字节) | U+FFFD + 把 `30 41 42` **退回流里**重解 → `�0AB` | 一口气吞掉 3 字节 → `�B` |
| `D4 7F`(ASCII 尾字节) | U+FFFD + 退回 `7F` | 吞掉 2 字节 |

头一条是**日常内容**(全角空格在中文帖子里到处都是,直接交给 JDK 会满屏 PUA 方框);
后三条决定坏字节处出**几个 U+FFFD**——而 `decodeResponseBody` 未声明 charset 时
正是**按 U+FFFD 个数投票**选编码的,多吞一个字节就可能把整篇翻到错的那一边
(fid=414 那份坏字节抓包正好卡在这条线上)。

所以最终做法是 **框法自己走(照抄 TS 的状态机,逐行对着抄,含两处「把字节退回流里」),
映射表问 JDK 要**——76KB 的 WHATWG 索引表不必再抄一份进仓库。整表对拍下来只需两处补丁,
都写在 `Gb18030.kt` 文件头:

1. 双字节 pointer 6555(`A3 A0`)→ U+3000;
2. 四字节 BMP 段的 18 个 pointer(U+9FB4–U+9FBB、U+FE10–U+FE19)→ 真字符而不是 PUA。

对拍范围:全部 **23940** 个双字节序列 + 全部 **1237576** 个四字节 pointer,
除上面两处外逐条相同。星平面段(pointer ≥ 189000,emoji 走这条)是算术映射,不查表。

**关键决定 2:出站编码不能用 `java.net.URLEncoder`**

它是 `application/x-www-form-urlencoded` 口径——空格变 `+`、`!~*'()` 一律转义,
与 JS 的 `encodeURIComponent` 逐字节对不上。`encodeUriComponent` 自己写了一份
(UTF-8 + JS 那张 unreserved 表)。孤立代理码元**抛错**而不是静默变 `?`,
与 JS 的 `URIError` 同语义。

emoji 出站逐字节一致:`gbk("摸鱼😄")` → `%C3%FE%D3%E3%26%2355357%3B%26%2356836%3B`
(golden `build-query-string-gbk-emoji-not-in-table` + `OutboundCharsetTest` 各锁一遍)。
确认过 TS 原文是**按 UTF-16 码元**遍历(`text.charCodeAt(i)`,不是码点),Kotlin 照抄;
表外字符走双字节表查不到 → 写 `&#<码元>;` 实体 → 实体本身再 percent 编码。
**只查双字节表,不走 GB18030 的四字节编码**——服务端那头是 GBK。

**关键决定 3:`__inchst` / Content-Type 抠成纯函数**

这两条在 RN 版里落在 `attempt.ts` 的**未导出** `buildUrl` / `runAttempt` 里,进不了金样本
(05a 的 Comments 也是这么记的)。票 03 把它们抠进 `OutboundCharset.kt`,用例**手工移植**自
`fetcher.test.ts`(3 处)、`search.test.ts`(2 处)、`block-word.test.ts`(1 处)。
钉住的点:

- **判据各看各的**:`__inchst` 只看 `request.query`,Content-Type 只看 `request.form`
  ——RN 版就是分开判的,一条请求完全可能 query 全 UTF-8 而 form 里带 GBK;
- 空 gbk 值(`gbk("")`)**不算** GBK 参数(它会被整个剔除,服务端根本看不到 GBK 字节);
- `outboundQuery` 用 `LinkedHashMap` 复刻 JS 对象展开的语义(`{__inchst, ...format, ...query}`):
  **后值覆盖,但位置留在第一次出现处**——请求自己写了 `__inchst` 时盖掉默认值、仍排最前。

**对 RN 版的有意偏离**

- `QueryValue.Num` 收 `Long` 而不是 JS 的 `number`。全仓库的 query 参数都是整数
  (fid/tid/pid/page/uid/时间戳),真要小数就传 `Text`。这样避免了「JS 数字转字符串」
  那套(`1e21`、`-0`)的兼容包袱。
- `decodeUtf8` 里显式剥一次 BOM:WHATWG 的 `TextDecoder('utf-8')` **自己会吃掉一个前导 BOM**
  (`ignoreBOM` 默认 false),外层 `stripBom` 再吃一个,所以 RN 的 UTF-8 路径最多剥两个、
  GBK 路径只剥一个。JVM 的 `String(bytes, UTF_8)` 不剥,这里补上,免得「两个 BOM」
  这种脏样本两边不一致。

**改了 RN 侧的什么(只改测试/导出器,不碰 TS 实现)**

1. `scripts/export-goldens.mts` 的 `query` domain:`input` 从「参数对象」改成
   `{ params: [[key, value], …] }`。**这是个真 bug**:导出器的规范化把对象键排字典序,
   而 `buildQueryString` 拼的是插入序,`build-query-string-post-form-same-rules` 的期望
   (`access_uid=123&access_token=abc`)与落盘键序(`access_token, access_uid, extra`)
   矛盾,Kotlin 照文件顺序拼**永远对不上**。详见票 05 的 05b Comments。
2. `decode-body` 补了 9 条 `gbk-*` 合成用例(`gbk-fullwidth-space` / `gbk-standalone-euro` /
   `gbk-lead-then-ascii` / `gbk-lead-then-7f` / `gbk-half-four-byte` /
   `gbk-truncated-four-byte` / `gbk-four-byte-astral` / `gbk-four-byte-unmapped` /
   `gbk-dangling-lead`),把上面那张「框法差异」表逐条钉成金样本。
   emoji 出站用例 05a 已经有了(`build-query-string-gbk-emoji-not-in-table`),没再加。
3. `README.md` 的 `decode-body` / `query` 两节同步改写。

**没做 / 留给别的票**

- `buildUrl` 剩下的一半(格式档参数表 `RESPONSE_FORMATS`、Referer/UA、双通道认证)——
  票 06。`outboundQuery(query, formatParams)` 已经把接口留出来了,票 06 传进来即可,
  **不要再抄一遍 `__inchst` 的判据**。
- `gbk()` 标记目前只有 `forum.php` 的 `key` 与 block-word 的 `data` 两处用到;
  真正给这两处接上是票 07/12 的事,本票只提供编码层。
- 真机验证(GBK 搜索关键词打到线上确实不乱码)要登录态,**待所有者**;
  但 `block-word.test.ts` 那条 `data=1%0D%0A%BC%D3…` 的期望本来就来自站上抓包,
  逐字节对上了,风险不高。

**发现的票外问题**

- `native/app/src/main/kotlin/com/chasel/ng2n/core/net/package.kt` 之类的占位文件
  (骨架票留的)还在,本票新增的实现放在同包下,没冲突;等各票填满后可以清一遍。

**主控验收(2026-08-22)**:合并后主干 `testDebugUnitTest` 33 例全绿、0 skipped。裁决:接受「框法照抄 TS 状态机、映射表取自 JDK」的偏离——票面写的 `Charset.forName("GB18030")` 目的是语义等价于 TS,而 JDK 解码器在 A3A0/孤立 0x80/坏字节吞并三处与 WHATWG 不一致且直接影响 U+FFFD 投票,goldens 对拍才是裁判。
