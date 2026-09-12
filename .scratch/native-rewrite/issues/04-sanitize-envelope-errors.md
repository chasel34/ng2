# 04 — 清洗管线 / 信封 / 错误模型(M1)

**What to build:** 三件套直译(research/inventory.md §3.7):
- **清洗六步**,顺序敏感:剥 `window.script_muti_get_var_store=` 前缀并切到首个 `</script>` → 切 `/*error fill content` 尾 → 去 `/*$js$*/` → 非法数字加引号 → **一趟带字符串状态的扫描**同时做「裸整数键加引号 + 字符串内裸控制字符转义」(防把正文里 `,123:` 形状改坏)→ 去尾分号外层括号。**不删 `alterinfo`**(保「已编辑」标记,与上游参考实现相反,是刻意的)。
- **信封解包** + `orderedEntries`:字符串整数键对象与**真数组**双兼容(`__output=11` 返回真数组,曾被误判成被封——ADR-0002 第 10 条)。
- **错误模型**:kind ∈ network/http/parse(≈被封)/server/unavailable;默认 `retryable = kind != server`;**「未登录」强制可重试**(传输身份失败非语义失败);假错误白名单(完毕/没找到/没有符合条件的结果/今天已经签到/找不到用户)短路成成功;HTTP 非 2xx 先解析 body、解不出才按状态码报错。
kotlinx.serialization 配置:`isLenient + ignoreUnknownKeys + coerceInputValues`,二象性字段用 `JsonTransformingSerializer` 归一。

**Blocked by:** 01, 03

**Status:** resolved

- [x] sanitize/envelope 全部 fixtures 金样本对拍通过
- [x] 错误分类与可重试语义:手工移植 TS 版对应测试逐条绿
- [x] `alterinfo` 保留、真数组兼容各有专门用例

## Comments

### 完成摘要(2026-08-22)

落地 `core/net/{Sanitize,Envelope,Errors,ServerText,ClassifyHttpResponse,JsSemantics}.kt`
与 `core/api/{Fields,NgaJson}.kt`,零 Android 依赖。

金样本四个 domain 全绿(**109 条**):`sanitize` 22 / `envelope` 18 / `errors` 32 /
`api/fields` 37,用既有框架,`GoldenAssert.kt` 里 05b 留的 `throwsDescribedBy` 口子接上了
`NgaErrorThrowDescriber`(kind/message/**retryable**/code?/status?/via?)。
手工移植单测 **68 条**:`SanitizeTest` 9 / `EnvelopeTest` 14 / `ErrorsTest` 13 /
`ClassifyHttpResponseTest` 14 / `FieldsTest` 12 / `NgaJsonTest` 6。
`./gradlew :app:testDebugUnitTest` 全仓 **105 tests / 0 failures**(含前面票的 37 条)。

### 关键决定(票里留白的「实现时定」项)

1. **信封层的 `Json` 必须是严格档,不能用 `isLenient`。** 票面写的
   `Json { isLenient; ignoreUnknownKeys; coerceInputValues }` 落在 `core/api/NgaJson.kt`
   (端点层解 `@Serializable` 用);`parseNgaJson` 走 `Envelope.kt` 里的私有严格档。
   理由:`isLenient` 会把 `<html>你被封了</html>` 当成一个不带引号的原语收下,
   「洗不成 JSON ⇒ 大概率被封 ⇒ 换下一个组合」这条信号当场消失。
2. **`Json.parseToJsonElement` 即使关掉 `isLenient` 也比 `JSON.parse` 松**——顶层的裸 token
   照收。补了 `requireJsonParseCompatibleRoot`:顶层原语必须是字符串 / `true` / `false` /
   `null` / 合法 JSON 数字,否则算解析失败。没有这一下,`envelope/not-json` 会把
   `kind:parse`「响应不是合法 JSON」降级成「响应顶层不是对象」——文案是小事,
   **语义**是「链还走不走下去」。
3. **`classifyHttpResponse(status, bodyText, via, shape, validate, parse)`**:TS 里这段在
   `strategies/attempt.ts` 的下半截(不是 `fetcher.ts`),和发请求/拼 URL/埋点混在一个 async
   函数里。抠成纯函数后 `HttpClassification.Ok/Failed` 两态,票 06 的 attempt 只剩
   「发请求 → 解码 → 调它 → report」。`validate`(ADR-0002 第 1 条的一票否决)也一并收进来了,
   因为它和 HTTP 分类在同一个 try 里,分开就少一条分支。
4. **`NgaError.code` 用 `JsonPrimitive`** 而不是 `String`/`Int`:TS 是 `string | number`,
   金样本要求 `"?"` 与 `403` 可分辨,收成 String 会把两者拍平。
5. **`int()` / `NgaEnvelope.time` 返回 `Long`**:TS 是 double 什么都装得下,`Int` 会让
   `credit`/`bit` 这类越过 32 位的权限位图静默截断。代价是服务端真发小数会被截断——
   现实里没见过,记在这儿备查。
6. **`JsSemantics.kt`:三处 JS 语义补丁**(`\s`/`trim` 的空白集合含 BOM、`Number(str)` 的
   转换规则、`Object.entries` 的属性枚举序)。不补的话金样本会在几处假红,更糟的是线上
   静默取错值。`jsOwnEntries` 那条尤其要紧:JS 把数组下标样式的键提到最前,kotlinx 的
   `JsonObject` 保的是文档序。
7. **`JsonTransformingSerializer` 样板一份**:`NgaListSerializer`(`core/api/NgaJson.kt`),
   走 `orderedValues` 归一,对象/真数组两副面孔解成同一个 `List<T>`。只给样板,
   逐个端点怎么用是票 07 的事——票 04 不替它挑哪些字段是二象性的。

### 对 RN 版的有意偏离

- `NgaError` 暂时**没有** `diagnostic` 可写字段:`FetchDiagnostic` 是票 06/02 的类型。
  票 06 加的时候记得是 `var`,重新包一个 NgaError 会丢掉调用方用 `is` 建立的判断。
- `NgaEnvelope.data` 是 `JsonElement?`,Kotlin 的 `null` = TS 的 `undefined`,
  服务端真下发 `"data": null` 时是 `JsonNull`——比 TS 那边分得更清。
- `FAKE_ERROR_MESSAGES` / `AUTH_LEVEL_SERVER_MESSAGES` 放在 `Errors.kt`(TS 侧住
  `constants.ts`),跟着用它们的判据走。**票 06 建 `Constants.kt` 时别再抄一份。**
- 清洗管线第 2 步的 `indexOf(...) > 0`(不是 `>= 0`)照抄了 RN 版,并在注释里写清了为什么:
  整条响应就是那段垃圾时截出来是空串,不如把原文留给信封层报错。

### 回改了 05a 的导出器(需要主控知晓)

`api/fields` 的 `ordered-entries-mixed-keys` / `ordered-values-mixed-keys` 两条
**按原样不可能对拍通过**,与票 03/05b 那次 `query` domain 的问题同源:

- 导出器的 `stringifyStable` 把对象键排成字典序,而这两条的 expected 来自 JS 对象字面量
  `{ b: 2, '1': 'x', a: 1, '0': 'y' }` 的**插入序**(`Object.entries` 给 `0,1,b,a`)。
  写进文件后 `input.value` 变成 `{"0":"y","1":"x","a":1,"b":2}`,任何从这份 JSON 读回入参的
  实现只能得到 `0,1,a,b` —— 靠顺序的信息在文件里存不下来。
- 处理:`scripts/export-goldens.mts` 里把该 case 的字面量改成
  `{ a: 1, '1': 'x', b: 2, '0': 'y' }`(非数字键取字典序),重跑 `pnpm goldens:export`,
  diff 只有那两个文件的 `expected` 与 `note`,`input` 一字未动;**重跑幂等(零 diff)已复验两次**。
- 丢掉的那半条语义(「插入序 ≠ 字典序时仍保持插入序」)跨不过 JSON,改由 Kotlin 手写单测锁:
  `core/api/FieldsTest.kt` 的 `非数字键排在数字键后面并保持原有顺序`,显式构造键序为
  `b, 1, a, 0` 的 `JsonObject`,期望 `0, 1, b, a`。
- `goldens/README.md` 的 `api/fields` 细则里加了一段警示,免得下一位再踩。
- **没有动**对拍框架(`golden/` 下五个文件)的任何口径。

### 未完成 / 待所有者

无。不需要登录、不需要真机。

### 发现的票外问题

1. `golden/GoldenAssert.kt` 的 `DefaultGoldenThrowDescriber` 现在只有非 `NgaError` 那一档;
   票 04 的描述器写在 `core/net/NgaErrorThrows.kt`(测试源码里)。票 06/07 要对拍抛错时
   直接 `throwsDescribedBy(NgaErrorThrowDescriber)` 即可,别再各写一份。
2. `docs/API文档.md` §0.6 的清洗清单里没有「去尾分号外层括号」这一步(RN 版注释已注明是
   §0.6 之外补的),文档可以补一句;本票只照抄行为,没动文档。
3. 票 06 落地 `attempt` 时需要的 `renewTransport`(ADR-0002 第 3 条:RN 上是空操作,
   Kotlin 要真建新 client)不在本票范围,提醒一句别漏。

**主控验收(2026-08-22)**:合并后主干 269 例全绿,goldens 重导幂等零 diff。接受:信封层严格 Json + 端点层 lenient 的分档;`mixed-keys` 两条 golden 的字面量改写(顺序语义改由手写单测锁,符合票 05 裁定的「顺序进数组」口径)。票外 ②(API 文档 §0.6 缺「去尾分号外层括号」)记 backlog。
