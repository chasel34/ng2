VERDICT: FAIL

# 票 12 联网搜索与网页读取 — 评审 1

评审范围：票 12 改动清单涉及的新增与修改文件（`core/ai/WebSearch.kt`、`core/ai/Citations.kt`、`core/ai/TopicContext.kt`、`data/ai/WebToolSession.kt`、`data/ai/ForumToolSession.kt`、`data/ai/TopicAgentRuntime.kt`、`ui/ai/{ToolCallRows,AiSourcePreviewSheet,AiMarkdown,TopicAiSheet,TopicAiViewModel}.kt`、内置技能、`docs/ai-assistant.md`、`docs/adr/0006-koog-agent-runtime.md`、新增测试与 fixtures）。票 01–11 的改动未纳入。

自行执行的验证：

```
JDK 17，未设置 NGA_INTEGRATION / NGA_WRITE_SMOKE
./gradlew --offline :app:testDebugUnitTest --rerun-tasks    BUILD SUCCESSFUL
```

XML 结果汇总：1274 个用例，0 失败，5 跳过，与实现报告一致。`WebSearchTest`、`WebUrlPolicyTest`、`AnswerClaimTest`、`WebToolSessionTest`、`WebReaderTest` 均实际执行。

## 需要修改的问题

### 1. 中 — 部分搜索成功时通知条仍然出现，文案与同屏的已读网页标签互相矛盾

位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt:43`，`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt:146`

`webSearchUnavailable` 只要本轮存在任意一次 `search_web` 落到 `challenge` 或 `request_failed` 就返回 true。事实核查经常发起多次搜索，设计稿 `Main.dc.html` 的 `fact` 场景就是两次搜索、两次网页读取。一旦其中一次失败，界面会同时出现：工具行下方的已读网页标签、来源列表里的站外来源，以及断言「本次回答仅依据论坛中已读取的内容，外部事实核查尚未完成」的通知条。用户看到的两处信息直接冲突，通知条陈述的事实为假。

设计稿只画了 `factSearchDown`（唯一一次搜索失败、没有任何网页读取）这一种场景，实现把它推广成了「任意一次失败」。

修复建议：把条件收紧为本轮没有取得任何外部证据，例如没有任何 `search_web` 成功、且没有任何 `read_webpage` 状态为 `ok`；部分失败改为在工具行保留失败标记即可，或另给一条措辞不同的提示。同时补一个部分失败的单测。

### 2. 中 — 解析单测用的不是「保存的 Lite 页面样本」，是手写的最小 HTML

位置：`app/src/test/resources/fixtures/web/ddg-lite-{results,challenge,empty}.html`

三个 fixture 分别只有 1528 / 384 / 264 字节，是按 `parseDuckDuckGoLite` 的实现反向写出来的简化片段，不是真实抓取保存的 DuckDuckGo Lite 响应。票的验收项写的是「用保存的 Lite 页面样本解析出结果；验证页与空页各归到正确类别」，该项已勾选，但实际并没有对真实页面结构做过验证。

这对本实现尤其要紧，因为分类完全依赖对真实页面文本的字符串匹配：`CHALLENGE_MARKERS`（`anomaly.html`、`bots use duckduckgo` 等）与 `EMPTY_MARKERS`（`no results`、`没有找到`）。请求带的是 `Accept-Language: zh-CN`，真实零结果页的措辞若与这两组常量不符，会落到「结构未知按验证页处理」的兜底分支，被当成人机验证并触发通知条。兜底方向是安全的（不会把失败当零结果），但会把正常的零结果误报成搜索不可用。

修复建议：抓取并保存三种真实响应作为 fixture 后复测，或者把该验收项改回未完成并在实现报告中写明离线环境下无法取得真实样本。

### 3. 低 — `read_webpage` 在 60,000 字与 2 MiB 处静默截断，模型无法区分「读完」与「被截断」

位置：`app/src/main/kotlin/com/chasel/ng2n/core/ai/WebSearch.kt:18,66`，`app/src/main/kotlin/com/chasel/ng2n/data/ai/WebToolSession.kt:87,209,212`

正文先按 2 MiB 字节上限截断 HTML，再由 `extractWebPageText` 截断到 `WEB_PAGE_LIMIT = 60000` 字。`totalCharacters` 返回的是截断之后的长度，`nextOffset` 也在截断后的长度处停止。模型读到最后一页时看不到任何截断信号，却把该来源标记为「已读正文」，可以据此给出「支持」的说法卡片。票要求区分搜索摘要与已读正文、未读正文不能作为已核实证据，同一条理由也适用于只读到一部分的正文。

修复建议：在 `extractWebPageText` 触达上限或字节读取触达 `MAX_BYTES` 时，在工具结果中加一个截断标记与说明（例如 `truncated: true` 与「正文超过读取上限，未读完」），并在 `detail` 中体现，便于工具行展示。

### 4. 低 — `search_web` 报告全部命中数，但只有前 10 条拿到 sourceId

位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/WebToolSession.kt:173,175,184`

`registered` 取 `page.hits.take(10)`，`results` 数组因此最多 10 条；但 `count` 写的是 `page.hits.size`，`detail` 写的是「另有 ${page.hits.size - 2} 条结果」。真实的 Lite 单页通常返回二三十条，模型会被告知存在它既拿不到链接、也无法引用的结果，工具行细节同样夸大。

修复建议：`count` 与 `detail` 按实际注册条数统计，或另加一个字段说明被截断的条数。

## 已核实、未发现问题的部分

- **SSRF 与私网防护。** `checkExternalUrl` 拒绝非 http(s)、带账号信息（同时检查 `URI.userInfo` 与 authority 中的 `@`）、`localhost` 与保留域名后缀、IPv4 私网与保留网段字面量，IPv6 只放行 2000::/3（`::1`、`fd00::/8`、`fe80::/10`、`::ffff:` 映射回环均被拒）。十进制、八进制等非点分写法绕过字面量判断后，由 `PublicOnlyDns` 在解析层拦下：它过滤 `Dns.SYSTEM.lookup` 的结果并在全部为私网时抛 `UnknownHostException`，OkHttp 对所有非 SOCKS 路由都会走这个 `Dns`，过滤与连接使用同一次解析结果，不存在 DNS 重绑定的时间窗。重定向由 `followRedirects(false)` + 手工循环处理，每一跳请求前重新执行 URL 策略，上限 3 跳，`WebReaderTest.重定向逐跳走 URL 策略` 覆盖了重定向到 127.0.0.1 的情况。
- **任何域名不携带 NGA Cookie。** `WebReader.shared` 是独立的 `OkHttpClient`，默认 `CookieJar.NO_COOKIES`，不复用论坛的连接池与拦截器。`WebReaderTest.站外请求不携带任何 Cookie` 用 MockWebServer 验证服务端下发 `Set-Cookie` 后下一次请求仍无 `Cookie` 头。
- **搜索验证页不被当成零结果。** 有结果 / 零结果 / `challenge` 三分，HTTP 非 2xx 与结构未知都归 `challenge`，网络异常单独归 `request_failed`；工具描述、系统提示与 fact-check 技能都写明 `challenge` 不是零结果且不改用其他引擎或收费服务。
- **并发、缓存、重试上限。** `Semaphore(2)` 限制站外并发，`load` 最多两次尝试（仅 5xx 与异常重试，`WebFetchRejected` 与取消直接抛出），搜索与正文在会话内缓存 15 分钟，`WebReader.shared` 关闭 `retryOnConnectionFailure`，没有出现多层重试相乘。
- **搜索摘要与已读正文分别标识。** `registerWeb` 以 URL 归并同一来源并只升不降 `bodyRead`；`TopicContext.material()` 对未读正文写明「不能作为已核实证据」；工具结果的 `scope` 字段、来源列表、已读网页标签与站外预览四处一致标注。
- **站外来源预览与「打开网页」。** `AiWebSourcePreviewSheet` 显示地球图标、域名、标题、已读正文或摘要、网址，主按钮通过 `LocalUriHandler.openUri` 在浏览器打开且不重新读取；站外来源走站外预览而不是论坛 READ 流程。已读网页标签在 `settled`（无 running 行）后随工具行展开区出现，与设计稿把标签放在折叠区内、开始回答后自动收起的说明一致。
- **说法卡片。** `splitAnswerBlocks` 只接受支持 / 部分支持 / 无法核实，围栏内与未知状态按字面保留，不影响既有 Markdown 与 `[[sN]]` 契约，单测覆盖。
- **个人入口。** `webEnabled` 排除个人入口，未注册联网工具，并改发「当前对话没有外部搜索工具」的系统提示。
- **项目约定。** `core/ai/WebSearch.kt` 只用 `java.*`，无 `android.*`；站外请求不经过 `NgaRequest`，不涉及 READ/WRITE 声明；未新增依赖，`gradle/libs.versions.toml` 未改动；新增注释只说明非显然的原因与约束，无票号与开发日记；`docs/ai-assistant.md` 与 ADR-0006 已同步工具清单、搜索状态分类、HTTP 与 URL 策略、站外来源与预览、说法卡片渲染约定。
- **持久化。** `ToolCallRow.sourceId` 为带默认值的新字段，旧 `AiTurn` payload 可正常解码；`AiSourceEntity` 主键是 `(conversationId, sourceId)`，站外来源 `tid`/`pid` 同为 0 不会冲突；`sourceWeb` 跨历史恢复有单测覆盖。

## 说明

票的第三项验收「真机移动网络：事实核查一次」未执行，实现报告已如实标注为本次授权范围外，本次评审同样未做设备验证。
