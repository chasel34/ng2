# 12 — 联网搜索与网页读取

**What to build:** 模型可以调用 DuckDuckGo Lite 搜索工具（返回标题、链接、摘要）与网页正文读取工具（有界正文，标记「已读正文」）。URL 策略拒绝私网地址与 localhost，任何域名不携带 NGA Cookie；限制并发、短期缓存、少量重试。搜索区分无结果、网络失败与人机验证页，验证页不当作零结果，不自动换其他引擎或收费服务。搜索失败时回答顶部出现通知条「外部搜索暂时不可用。本次回答仅依据论坛中已读取的内容，外部事实核查尚未完成。」，工具行显示失败。工具全部结束后显示已读网页标签；站外来源标签用地球图标，预览面板为站外变体（域名、已读正文、主按钮「打开网页」在浏览器打开）。事实核查 skill 更新为使用搜索与网页读取，回答中区分论坛发言与外部证据，说法卡片标注支持 / 部分支持 / 无法核实。

**Blocked by:** 05 论坛读取工具与工具调用行、08 来源预览与图片预览

**Status:** implemented

- [x] 解析单测：用保存的 Lite 页面样本解析出结果；验证页与空页各归到正确类别
- [x] URL 策略单测：私网、localhost、非 http(s) 被拒；请求不含论坛 Cookie
- [ ] 真机移动网络：事实核查一次，看到搜索与读取工具行、已读网页标签、站外来源预览与打开网页
- [x] 模拟搜索失败：通知条出现，回答继续基于论坛资料并说明外部核查缺失
- [x] 搜索摘要与已读正文在来源中分别标识，未读正文的结果不能被引用为已核实

## Comments

- 2026-09-14：实现完成。改动文件清单与验证结果见 [实现报告](../reports/12-impl.md)。
- 2026-09-14：评审 1 的四条意见已全部修复（通知条触发条件、真实 Lite 样本与随之发现的 GET 被验证页拦截问题、正文截断标记、结果条数统计），本轮改动清单与验证见同一实现报告的「评审 1 修复」一节。
- 2026-09-14：评审 2 只提出实现报告与 fixture 描述不符一条，已按 fixture 实际内容更正 fixture 字节数，并改正「验证页标记全部落空」「零结果页此前会落到兜底分支」两处错误说法；代码未改动。

### 改动文件

新增

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/WebSearch.kt`：DuckDuckGo Lite 解析（有结果／零结果／`challenge`）、网页标题与有界正文抽取、URL 策略（协议、账号信息、localhost 与保留域名、私网与保留网段字面地址、IPv6 只放行 2000::/3）、按地址字节判定私网、域名提取。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/WebToolSession.kt`：独立 OkHttp 客户端（无 CookieJar、关闭自动重定向、`PublicOnlyDns` 过滤解析结果、2 MiB 正文上限）、逐跳 URL 策略的 `WebReader`，以及 `search_web` / `read_webpage` 两个 Koog 工具（并发 2、失败重试 1 次、15 分钟会话内缓存、工作上下文重放）。
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/WebSearchTest.kt`：Lite 解析、URL 策略、说法块解析三组单测。
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/WebToolSessionTest.kt`：工具行为与 `WebReader` 的 MockWebServer 测试。
- `app/src/test/resources/fixtures/web/ddg-lite-{results,challenge,empty}.html`：保存的 Lite 页面样本。

修改

- `core/ai/TopicContext.kt`：`AiSource` 增加 `web`；`material()` 增加站外来源分支，未读正文明确标为不能作为已核实证据。
- `core/ai/Citations.kt`：`splitAnswerBlocks` 解析 `:::claim 状态` 说法块，状态限支持／部分支持／无法核实，围栏内与未知状态按字面保留。
- `core/ai/QuickActions.kt`：技能版本 2 → 3。
- `data/ai/ForumToolSession.kt`：`ToolCallRow` 增加 `sourceId`；新增 `registerWeb` / `restoreWeb` / `webTools`；新增 `WEB_SEARCH_NOTICE` 与 `webSearchUnavailable`。
- `data/ai/TopicAgentRuntime.kt`：注册联网工具（个人入口除外）；系统提示改为说明搜索与网页读取、区分论坛发言与外部证据、`challenge` 不是零结果；工具行回填 `sourceId`。
- `ui/ai/AiMarkdown.kt`：站外来源标签用地球图标显示域名；说法卡片渲染（状态胶囊、说法序号、说法与证据）。
- `ui/ai/ToolCallRows.kt`：新增「搜索网页」「读取网页」标签；工具全部结算后显示已读网页标签，点击打开站外预览。
- `ui/ai/AiSourcePreviewSheet.kt`：新增 `AiWebSourcePreviewSheet`（域名、标题、已读正文或仅搜索摘要、主按钮「打开网页」）。
- `ui/ai/TopicAiSheet.kt`：站外来源走站外预览并用浏览器打开；搜索失败时在回答顶部显示通知条；来源列表区分站外与论坛。
- `ui/ai/TopicAiViewModel.kt`：站外来源的网址、标题与已读正文状态随会话入口元数据 `sourceWeb` 保存与恢复。
- `app/src/main/assets/ai-skills/fact-check/{SKILL.md,references/example.md}`：改为使用搜索与网页读取，区分论坛发言与外部证据，说法卡片写法与三种状态。
- `app/src/main/assets/ai-skills/background/SKILL.md`：补充联网工具与搜索不可用时的说明要求。
- `docs/ai-assistant.md`、`docs/adr/0006-koog-agent-runtime.md`：同步工具清单、搜索状态分类、HTTP 与 URL 策略、站外来源与预览、说法卡片渲染约定。
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/BuiltinSkillsTest.kt`：跟随 fact-check 技能描述更新断言。
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`：新增搜索失败通知条与站外来源跨历史恢复两个用例。

未新增依赖，`gradle/libs.versions.toml` 未改动。

## 验证

```
./gradlew --offline :app:assembleDebug        BUILD SUCCESSFUL
./gradlew --offline :app:testDebugUnitTest    BUILD SUCCESSFUL（1274 个用例，0 失败，5 跳过）
```

JDK 17，未开启 `NGA_INTEGRATION` 与 `NGA_WRITE_SMOKE`。

验收项对照

- 解析单测：`WebSearchTest` 用保存的 Lite 样本解析出标题、链接与摘要；验证页归 `challenge`，空页归零结果，结构未知的页面也不当作零结果。
- URL 策略单测：`WebUrlPolicyTest` 与 `WebToolSessionTest` 覆盖非 http(s)、localhost、私网与链路本地地址、带账号信息的地址；被拒绝的地址不发起请求。`WebReaderTest` 用 MockWebServer 验证服务端下发 `Set-Cookie` 后的下一次请求仍无 Cookie 头，且共享客户端为 `CookieJar.NO_COOKIES`；`PublicOnlyDns.lookup("localhost")` 拒绝解析。
- 模拟搜索失败：`TopicAiViewModelTest.webSearchFailureKeepsForumAnswerAndRaisesNotice` 断言验证页工具行标记失败、通知条条件成立、回答保留论坛结论与「外部核查未做」。
- 搜索摘要与已读正文分别标识：`WebToolSessionTest` 断言搜索注册的来源 `bodyRead=false`、读取正文后同一 sourceId 升级为 `true`；`TopicContext.material()` 对未读正文的来源写明不能作为已核实证据；界面在来源列表、已读网页标签与站外预览三处分别标注。
- 真机移动网络一次事实核查：未执行。本次为离线实现，设备与真实模型联调不在本次授权范围。

## 决策与偏差

- 个人分析入口不注册联网工具。个人报告的产品规则要求只整理本人明确表达、不推断未公开属性，对用户本人做站外检索与该规则冲突；已写入文档与 ADR。
- 说法卡片用 `:::claim 状态` 块承载，而不是新增结构化协议。围栏内同名文本与未知状态按字面保留，不影响既有 Markdown 与 `[[sN]]` 契约。
- 站外来源与论坛来源共用 `[[sN]]` 与同一套 ID 分配；网址、标题与已读正文状态存在会话入口元数据的 `sourceWeb` 中，未改 Room 结构，本机不保存网页原文。
