# 06 — 反封锁链(M1,核心;照抄 + 修 P1)

**What to build:** fetcher 策略链五段照抄(装配序:web-fallback(primary 档)→ format-rotation → switch-account → web-fallback(secondary 默认档)→ topic-cache 槽位(实现挂票 14 之后);链外 /web 兜底归票 17):
- 引擎:按序试、首成功返回、`retryable=false` 立刻抛、全程 attempts 诊断挂载。
- format-rotation:格式 `__output=8`/`__output=11`/`lite=js`;顺序=缓存好组合→调用方指定→域名外层×格式内层笛卡尔积;**上限 8**;组合缓存 key 接口粒度(`path` 或 `path?__lib&__act`)、TTL 10min、**不持久化**、自愈;游客短路(未登录且服务端报未登录→停换域名)。
- switch-account:≥2 账号才触发、循环到当前 uid 下一槽、只试一次。
- **`renewTransport` 真实现**(原生的白捡增益,ADR-0002 第 3 条):`OkHttpClient.newBuilder()` + 独立 ConnectionPool/Dispatcher,换域名真换 TCP 连接。
- 凭证双通道照抄(Cookie 头 + `access_uid`/`access_token` 表单;OkHttp BridgeInterceptor 覆盖 Cookie 头的坑与 RN 相同)+ **自管 CookieJar**,WebView CookieManager 只在登录收割点交互(修 P1-03)。
- **修 P1-01/P1-02**(spec §一.5):`NgaRequest` 加 `operation(read/write)` 与 accountPolicy 元数据——写操作固定 uid、**禁入 format-rotation 与 switch-account**、失败不自动重放;读侧缓存按 uid 隔离的原则从第一天生效。
- 无退避/无超时照抄?**不照抄**:加统一超时预算(修审计 P2-06,connect/read/整体 deadline 各一档),背靠背轮换行为保持。

**Blocked by:** 01, 04

**Status:** in-review

- [x] 手工移植回归全绿:combo 投毒(`rejectNonTopicList` 防假成功)、「未登录=可重试」、游客短路、`__output=11` 真数组
- [x] 新增回归:写操作不轮换/不换号/不重放;读写元数据缺省安全(未标注=按写处理或编译期强制标注,二选一并记录)
- [x] renewTransport 实测换连接(日志验证新 socket)
- [x] 组合缓存 TTL/自愈/接口粒度语义与 TS 版一致(单测)

## Comments

### 完成摘要(2026-08-22)

`core/net`(纯 Kotlin)+ `data/net`(OkHttp 实装)+ `di/NetworkModule` 全部落地,
`./gradlew :app:testDebugUnitTest` **393 tests / 0 failures / 1 skipped**(跳过的是
`NGA_INTEGRATION` 门控的联网冒烟),`:app:assembleDebug` 通过。

新增文件:
- core:`Constants.kt` / `Transport.kt` / `Timeouts.kt` / `Auth.kt` / `NgaRequest.kt` /
  `Combo.kt` / `FetchDiagnostic.kt` / `Chain.kt` / `NetworkSettings.kt` / `NgaClient.kt`
  + `strategies/{Attempt,Direct,FormatRotation,SwitchAccount,WebFallback,TopicCache}.kt`
- data:`net/{NgaCookieJar,OkHttpTransport,SettingsNetworkSource,ChainDiagnostics,TopicCachePayloadReader}.kt`
- 改动既有文件三处:`core/net/Errors.kt`(补 `NgaError.diagnostic`,票 04 留的口)、
  `data/settings/Settings.kt`(`NGA_HOSTS` / `WebFallbackMode` 改为引用 core)、
  `di/NetworkModule.kt`(替换占位 provider,**注入类型与作用域不变**,票 12 一行不动)。

### ADR-0002 十条现状修正 → 代码分支逐条对应(验收清单)

| # | 修正 | 落点 | 钉住它的测试 |
|---|---|---|---|
| 1 | 「成功」不能只是「洗得成 JSON」,调用方可一票否决,按 `kind=parse` 继续轮换 | `NgaRequest.validate` → `classifyHttpResponse`(票 04) | `ComboPoisonTest` 前 3 条 |
| 2 | 成功组合缓存必须能自愈:10min TTL + 当场失手立刻摘 | `InMemoryComboCache` 的 `live()`;`FormatRotationStrategy` 里 `combo == preferred → forget` | `ComboTest·条目有保质期`、`ComboPoisonTest·缓存过期后重新试探`、`FormatRotationTest·缓存里那个组合当场失手就先摘掉` |
| 3 | `renewTransport` 在 RN 上是空操作,真换连接得走原生 | `OkHttpTransportFactory.renew()`:`newBuilder()` + 新 `ConnectionPool` + 新 `Dispatcher` | `OkHttpTransportTest·renew 之后是一条新连接`(mockwebserver3 的 `connectionIndex`) |
| 4 | 凭证不能只放 `Cookie` 头(BridgeInterceptor 无条件覆盖) | 双通道:form 字段照抄 + **自管 `NgaCookieJar` 成为 Cookie 头唯一来源**(链不再手写该头) | `AuthTest`(5 条)、`OkHttpTransportTest` 前 4 条 |
| 5 | 可观测性是链的一部分:成功也要留记录(组合 / `data` 顶层键 / 条数),不含 cid 与正文 | `runStrategyChain.succeed()` → `FetchEvent.ChainSuccess`;`summarizeEnvelopeData` | `StrategyChainTest·成功也留一条记录…` |
| 6 | 「未登录」是传输层错误 → 可重试;避开「语义错误=组合通」的缓存规则;游客态也标可重试 | `isAuthLevelServerError`(票 04 的 `classifyHttpResponse`)+ `FormatRotationStrategy` 的游客短路 | `FormatRotationTest` 后 5 条 |
| 7 | 判据按「这条错误在说什么」分类,不按 `kind` 一刀切 | 白名单 `AUTH_LEVEL_SERVER_MESSAGES` / `FAKE_ERROR_MESSAGES` 集中在 `Errors.kt`,`Constants.kt` **不再抄一份** | `ErrorsTest`(票 04)+ 上一行 |
| 8 | 轮换档位的价值在于不共用同一段服务端代码;`__output=11` 必须在耗完一轮域名前试到 | `DEFAULT_ROTATION_FORMATS` 顺序 + `DEFAULT_MAX_ATTEMPTS = 8` | `ComboTest·jsonVerbose 在表里…`、`…默认上限够第一个域名试满所有格式` |
| 9 | 没验证过的档位不进默认轮换,出口是去抓样本 | 轮换表只放 JSON 家族三档;XML 两档在 `RESPONSE_FORMATS` 里但 `isRotatableFormat` 挡住 | `ComboTest·不可解析的格式不进轮换` |
| 10 | 列表要同时接受对象形态与数组形态(`__output=11` 的 `__T` 是真数组) | `summarizeEnvelopeData` 同时认 `JsonObject` 与 `JsonArray` | `StrategyChainTest·__output=11 的 __T 是真数组…` |

### 关键决定(票里留白的「实现时定」项)

1. **读写元数据:选「编译期强制标注」**(票面二选一)。`NgaRequest.operation` 没有默认值,
   任何构造点漏标**编译不过**。理由:两种默认值都会静默走错——「未标注按写处理」会让漏标的
   读接口悄悄失去整条反封锁链(表现和被封一模一样,极难归因),「未标注按读处理」就是
   P1-01 本身。代价只是 25 个端点一次性标完。`WriteOperationTest·NgaRequest 的 operation
   没有默认值` 用反射钉住它不被后人加上默认值(为此给 `testImplementation` 加了 `kotlin-reflect`)。
2. **写操作走独立的链**:`NgaClient` 有 `readChain`(五档)与 `writeChain`(只有 `direct`)。
   另外 `FormatRotationStrategy` 与 `SwitchAccountStrategy` **各自还有一道自查闸**
   (`request.isWrite` / `accountPolicy == PINNED` 就报 `unavailable` 且一次请求都不发)——
   装配失守时的第二层保险,有专门用例(`format-rotation 自己也拦写操作`)。
3. **自管 CookieJar 不保存服务端下发的任何 cookie**(票面要求「按 TS 语义决定,Comments 记」)。
   判据:RN 版之所以会保存,只是因为它复用了 WebView 的 `CookieManager`(`ExpoFetchModule`
   挂的 `JavaNetCookieJar`),不是有意设计,而那份保存正是 ADR-0002 第 4 条那个 P0 的直接成因;
   `transport.ts` 自己一个 jar 都没有,`auth.ts` 明说凭证通道只有两条 —— 会话 cookie 对协议层
   不是必需品。所以 `saveFromResponse` 是故意的空实现,并有单测钉住
   (`Set-Cookie 不会被保存,下一发只带我们自己的两枚`)。将来真抓到「NGA 要求带回某个会话
   cookie」的样本,再加一个**按 client 隔离的内存 jar**,仍然不碰 WebView 那份。
4. **凭证跟着 `HttpRequest.credential` 走,而不是由链写 `Cookie` 头**。okhttp 的
   `CookieJar.loadForRequest` 只收得到 URL,拿不到 request tag;而全局共享一个可变字段在并发
   请求下会串号(首页同时拉几个版块 / 换账号那一档与主请求并行)。所以 `OkHttpTransport`
   每发一次派生一个 `client.newBuilder().cookieJar(…)`——`newBuilder()` **共享连接池与
   dispatcher**,不是新建 HTTP 栈。`buildAuthAttachment` 仍原样产出两个通道的东西(移植过来的
   `AuthTest` 逐条对拍的就是它),`runAttempt` 只是改用它的 `usesCookieChannel` 做判据。
5. **协程取消原样抛 `CancellationException`,不折成 `NgaError(network, retryable=false)`**。
   TS 那两条用例(`fetcher.test.ts` / `format-rotation.test.ts` 的「取消」)改成断言
   「抛 CancellationException + 链上后面一档都不跑 + 只发了一次」。吞掉 CancellationException
   会让结构化并发失效,这是 Kotlin 侧不能照抄的一处。
6. **统一超时预算取值**(修 P2-06):connect 8s / read 12s / write 12s / **整体 call 15s**,
   常量与理由在 `core/net/Timeouts.kt`。**背靠背轮换行为不变**(组合之间无退避),
   所以最坏是 8 × 15s = 120s —— 有上限,而 RN 版没有上限。没有加「整条链的 deadline」:
   那会改变轮换行为(轮到一半被掐),票面明确要求行为不变。
   另加 `retryOnConnectionFailure = false`:反封锁链自己就是重试机制,okhttp 再偷偷重试
   会让「试了几个组合」对不上账。
7. **组合缓存粒度不变(不含 uid)**,与 TS 一致:它记的是「服务端在哪个组合上没封这个接口」,
   与谁在用无关。会串账号的是数据层的业务缓存,为此在 `NgaRequest` 上暴露了
   `cacheScope(uid)`(游客固定前缀 `guest`,`__` 开头的框架参数不进 key),供票 07 / 16 用。
   两条性质各有用例。
8. **链的诊断类型在 core 再定义一份**(`FetchDiagnostic` 等),映射到票 14 的
   `data/diagnostics` 收在 `data/net/ChainDiagnostics.kt` 一处(约 20 行)。
   原因是分层单向:core 不能 import data。脱敏仍然只在 data 侧做(P1-04 的收口点没有被绕开);
   `userAgentValue`(完整 UA 串)故意不搬进落盘记录。
9. **`WebSettings.getDefaultUserAgent()` 走 `lazy`**,第一发请求时才求值(那时已在 IO 协程里),
   冷启动路径碰不到它(P2-04 的纪律)。
10. **`NGA_HOSTS` / `WebFallbackMode` 的唯一真相源迁进 core**,`data/settings/Settings.kt`
    改成再导出(两行 + 一个 typealias),全仓引用点与票 14 的 `SettingsTest` 一行没动。

### 对 RN 版的有意偏离

| 偏离 | RN 版原行为 | 这一版 | 为什么 |
|---|---|---|---|
| 取消 | `AbortError` → `NgaError(network, retryable=false)` 返回 | `CancellationException` 原样抛 | 结构化并发,见决定 5 |
| `Cookie` 头 | 链手写进 headers,被 cookie jar 顶掉 | jar 唯一来源 | 修 P1-03 / ADR-0002 第 4 条 |
| 超时 | 无 deadline(okhttp 默认值) | 四档预算 | 修 P2-06 |
| 写操作 | 与读共用一条链,会重放 / 换号 | 独立单档链 + 两道自查闸 | 修 P1-01 |
| `renewTransport` | 空操作 | 真换连接池 | ADR-0002 第 3 条 |
| 组合缓存并发 | 单线程 JS,无锁 | `synchronized` | JVM 上链会被多协程并发调用 |

### TS 用例 → Kotlin 测试映射表

**六个必移植文件共 78 条,移植 78 条(100%),未移植 0 条。**

| TS 文件 | TS 条数 | Kotlin 测试类 | 移植 | 新增 |
|---|---|---|---|---|
| `auth.test.ts` | 5 | `AuthTest` | 5 | 0 |
| `combo.test.ts` | 15 | `ComboTest` | 15 | 0 |
| `fetcher.test.ts` | 29 | `NgaClientRequestTest`(12)+ `NgaClientResponseTest`(10)+ `StrategyChainTest`(7) | 29 | `StrategyChainTest` +4 |
| `combo-poison.test.ts` | 6 | `ComboPoisonTest` | 6 | 0 |
| `format-rotation.test.ts` | 15 | `FormatRotationTest` | 15 | +1 |
| `switch-account.test.ts` | 8 | `SwitchAccountTest` | 8 | 0 |
| **小计** | **78** | | **78** | **+5** |

逐条对应(TS 用例名 → Kotlin 测试名;同名的不再重复列出,只列**改了名或换了断言方式**的):

*`fetcher.test.ts · 请求拼装` → `NgaClientRequestTest`*
- `getHost 每次请求现取，且盖过建 fetcher 时定的 host` → `域名每次请求现取,设置页改完下一个请求就发到新域名`(改由 `NetworkSettingsSource` 现读)
- `GBK 参数按 GBK 编码进 query，并撤掉 __inchst=UTF8` → `GBK 参数按 GBK 编码进 query,并撤掉 inchst 声明`(反引号名不能带 `=`)
- `cookie 认证方式把凭证放头里，body 不带凭证` → `cookie 认证方式把凭证放 cookie 通道,body 不带凭证`(断言 `HttpRequest.credential` 而不是 `headers.Cookie`,见决定 4;真的有没有变成 `Cookie:` 头由 `OkHttpTransportTest` 钉)
- `表单里有 GBK 值时声明 charset=GBK` → `表单里有 GBK 值时声明 charset 为 GBK`(断言 `HttpRequest.contentType`)
- 其余 8 条同名直译。

*`fetcher.test.ts · 响应处理` → `NgaClientResponseTest`*
- 5 条依赖真实抓包的用例(`notiEmpty` / `threadList` / `ucpUser` / `ucpNotFound` /
  `readThreadNotFound`)**把 fixture 原始字节搬进了 `app/src/test/resources/fixtures/net/`**,
  断言与 TS 一致(含「未声明 charset 的 GBK 照样解出中文」)。
- `调用方取消不算被封，不触发后面的兜底` → 同名,断言改为 `CancellationException` + 后档 0 次调用(决定 5)。
- 其余 4 条同名直译。

*`fetcher.test.ts · runStrategyChain` → `StrategyChainTest`* —— 7 条同名直译。
新增 4 条:`整条链失败时把 attempts 诊断挂到错误上`、`成功也留一条记录…`(第 5 条)、
`__output=11 的 __T 是真数组…`(第 10 条)、`unavailable 不盖掉更实质的错误`。

*`format-rotation.test.ts` → `FormatRotationTest`* —— 15 条同名直译(全角冒号改成 `·`)。
新增 1 条:`缓存里那个组合当场失手就先摘掉(自愈)`(第 2 条的另一半出口,TS 只在
combo-poison 里间接覆盖)。

*`combo-poison.test.ts` → `ComboPoisonTest`* —— 6 条同名直译。TS 那边走 `fetchTopicList`
(端点层,归票 07),这里把它的一票否决器 `rejectNonTopicList` 的判据(`__T`/`__F`/`__ROWS`
至少有一个)**原样写在测试里**;被测的是链怎么对待「调用方说这不是我要的东西」。

*`switch-account.test.ts` → `SwitchAccountTest`* —— 8 条同名直译。

**票面六个文件之外,额外移植的两份**(策略壳的性质,本票也落地了):

| TS 文件 | TS 条数 | Kotlin 类 | 移植 | 未移植 | 未移植原因 |
|---|---|---|---|---|---|
| `web-fallback.test.ts` | 9 | `WebFallbackTest`(9) | 8 | 1 | `网页版返回 msgcode 错误时按服务端语义错误抛` 要真实 HTML 抓包 + 反解器 → **归票 08** |
| `topic-cache.test.ts` | 12 | `TopicCacheStrategyTest`(11) | 10 | 2 | `信封往返` 两条测的是 `serializeEnvelope`(payload 序列化)→ **归票 07 / 13** |

这两份里的反解器换成了假实现(`FakeWebReadParser`),被测的是**四档档位、域名沿用、
`only` 档终点、反解器没接上时让位**这些链的性质,与 HTML 怎么解无关。
各新增 1 条:`反解器没接上时这一档直接让位,不白打一次网络请求`、`本地库抛异常也只当这一档不适用`。

**新增回归(TS 侧没有对应物,因为 RN 版就是这些缺陷本身)**:`WriteOperationTest`(10 条)
—— 写操作只发一次 / 不换号、读操作对照组、两道闸各自生效、`accountPolicy=PINNED`、
默认账号策略、写操作照样带凭证、`operation` 无默认值、`cacheScope` 按 uid 隔离、
组合缓存反过来不含 uid。
`OkHttpTransportTest`(10 条)—— jar / `renew()` / GBK Content-Type / 超时预算。

### `renewTransport` 的验证方法(票面要求写清楚)

1. **单测(已跑通)**:`OkHttpTransportTest·renew 之后是一条新连接`。判据是
   `mockwebserver3.RecordedRequest.connectionIndex` —— **服务端视角**的第几条 TCP 连接,
   不是我们自己数的。同一个 transport 连发两次 index 相同(复用),`renew()` 后 index 变,
   连续两次 `renew()` 两次都变。
2. **真机 / 模拟器**:把 `data/net/OkHttpTransport.kt` 里的 `DEBUG_LOG_CONNECTIONS`
   打开,每发一次请求往 logcat 打一行
   `ng2n-net: conn=<connection.hashCode()> local=<socket 本地端口> → <url>`
   (`EventListener.connectionAcquired` 取 `Connection`,`connection.socket().localPort` 取端口)。
   两次 `renew()` 之后这两个值都必须变。**这条钩子本身还没接**——票面要求「Comments 写验证
   方法」,方法与常量已就位,接 `EventListener` 是票 19 真机排障时按需打开的事,
   平时它每请求一行是纯噪音。

### 未完成项 / 交给后续票

- **网页反解本体**:`WebReadParser` 接口 + `UnavailableWebReadParser` 占位已就位,
  策略壳与四档档位全部有测试。票 08 只需换掉实现(并按 spec §四先重新抓包核对
  `commonui.postArg.proc` 参数位置表)。
- **`topic_cache` payload 的序列化/反序列化**:策略只做 `parseNgaJson`(与在线那条路同一段代码),
  `serializeEnvelope` 归票 07 / 13。
- **端点层**(票 07)标 `operation` 时会撞上编译错误 —— 这是设计如此,25 个端点一次性标完。
- **`X-User-Agent` / UA / Referer 不在 okhttp 拦截器里**:它们逐请求不同,由 `runAttempt` 拼。
  图片管线(票 12)若也要这些头,自己加。

### 需要所有者(真人)介入

- **联网冒烟没跑过**:`NgaIntegrationSmokeTest` 默认 `Assume` 跳过。要验就
  `cd native && NGA_INTEGRATION=1 NGA_TEST_PROXY=127.0.0.1:7897 ./gradlew :app:testDebugUnitTest
  --tests '*NgaIntegrationSmokeTest' --rerun-tasks`(`--rerun-tasks` 必须,否则 gradle 判
  UP-TO-DATE 直接跳过)。**不要连续打**,两次之间静置 ≥60s(NGA 限流,T6)。
- **登录态的真机验证**(自管 jar 在真实 NGA 上是否被 `Set-Cookie` 干扰、
  `read.php` 的 WP UA 开关)需要真人登录账号,归票 15 / 18。

### 发现的票外问题

1. **`data/diagnostics/DiagnosticLogStore.record` 是 suspend,而链的 `onDiagnostic` 是同步回调。**
   本票在 `NetworkModule` 里用 `@IoScope` 的 `scope.launch { … }` 桥接。这意味着**进程被杀时
   最后几条诊断可能没落盘** —— 排障旁路,可接受,但记在这里备查。
2. **`DiagnosticLogStore` 的 `runLog`(本次运行的落点表)本票没有接**:
   实验室页(票 17)消费它时需要有人把 `NgaClient.successfulCombos()` 与它对齐,
   现在是两份各记各的。
3. **票 12 的图片管线拿到的是「最近一次协议请求用过的凭证」**(`CurrentCredentialCache`),
   冷启动第一发协议请求之前它是 null,那时按游客取图。与 RN 版行为一致,但如果票 12 需要
   更强的保证(比如从账号 Flow 直接订阅),要在那边加。
4. **`benchmark` 模块与 `androidTest` 本票没碰**,`:app:assembleDebug` 与
   `:app:testDebugUnitTest` 均通过。
