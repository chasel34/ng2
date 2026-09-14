# 票 12 联网搜索与网页读取 — 实现报告

## 改动文件

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

## 评审 1 修复（2026-09-14）

四条意见全部修复，无不同意项。

### 1（中）通知条在部分搜索成功时仍然出现

`webSearchUnavailable` 收紧为「本轮存在失败的 `search_web`，且没有任何 `search_web` 成功、也没有任何 `read_webpage` 成功」。部分失败只在对应工具行保留失败标记，已读网页标签与站外来源照常展示，不再与通知条文案冲突。新增 `WebToolSessionTest.部分搜索失败不触发通知条` 覆盖全失败、失败+成功搜索、失败+成功读取、全成功四种组合。

### 2（中）解析单测未用真实 Lite 样本

已抓取并保存三份真实响应替换原手写片段：

| fixture | 来源 | 字节 |
| --- | --- | --- |
| `ddg-lite-results.html` | POST `q=社会消费品零售 月度数据`，10 条结果 | 23585 |
| `ddg-lite-empty.html` | POST 引号包裹的无意义查询，零结果 | 8037 |
| `ddg-lite-challenge.html` | GET 同一查询，HTTP 202 验证页 | 14044 |

脱敏：`vqd` 会话令牌与验证页里全部十个 32 位十六进制一次性令牌（challenge 提交令牌与拼图图片哈希）都替换为固定占位串；复查确认三份样本不含任何 32 位十六进制串、IP、Cookie 或账号信息。

真实样本暴露的实现问题只有一个，已修复：

- **GET 稳定被验证页拦截，表单 POST 才返回结果。** `search_web` 改为向 `https://lite.duckduckgo.com/lite/` POST 表单字段 `q`；`WebReader.get` 改名为 `fetch(url, form)`，重定向后不重发表单。

分类判据经真实样本核对后确认原本就正确，本轮只是补强，不是修错：

- 验证页里 `bots use duckduckgo`、`anomaly.js`、`challenge-form` 三个原有标记都实际出现，旧实现已能正确归类；只有 `anomaly.html` 在当前页面不存在。本轮另补 `anomaly-modal`（56 次）、`/assets/anomaly`（9 次）、`challenge-submit`（1 次）作为更贴近当前页面结构的判据，原有标记一律保留，后续维护不要删。
- 零结果页的措辞是 `No results found for ...`，由原有的 `no results` 命中，此前也不会落到「结构未知按验证页处理」的兜底分支。`EMPTY_MARKERS` 只去掉被 `no results` 覆盖的冗余项 `no results found`，并新增 `not many great matches`。

评审 2 指出本节原先把「补强标记」写成了「修复错误分类」，并列错了 fixture 字节数；以上为更正后的描述，ADR-0006 中同一处说法一并改为保留原有字样并补充新判据，代码未改动。

原 fixture 中依赖的私网链接与 `uddg` 重定向两个场景改为 `WebSearchTest` 里的内联片段单测，仍然覆盖。

### 3（低）正文截断无信号

`extractWebPageText` 改为 `extractWebPage` 返回 `WebPageText(text, truncated)`；`WebFetchResult` 增加 `truncated`，字节读取触达 2 MiB 时置位。两者任一为真时：工具结果返回 `truncated: true`，`scope` 写明「只取得前 N 字，未读完，不能声称已读全文」，`detail` 显示「超过读取上限，未读完」；`AiWebSource` 增加 `truncated`，`webReadState` / `webReadLabel` 让资料正文、来源列表、已读网页标签与站外预览统一显示「正文未读完」。fact-check 技能补充 `truncated` 的处理要求。

### 4（低）`count` 与 `detail` 夸大可用结果数

`count` 改为实际注册条数；超出单次上限的条数放在新字段 `omitted` 与 `scope`、`detail` 中单独说明，并写明这些结果没有来源编号、不能引用。

### 本轮改动文件

- `core/ai/WebSearch.kt`：补强验证页标记与整理零结果标记、`extractWebPage` 截断标记、`AiWebSource.truncated`、`webReadState` / `webReadLabel`。
- `core/ai/TopicContext.kt`：站外来源的读取状态改用 `webReadState`。
- `data/ai/WebToolSession.kt`：表单 POST、`WebFetchResult.truncated`、结果条数统计、截断上报、`load` 透传表单。
- `data/ai/ForumToolSession.kt`：`webSearchUnavailable` 收紧；`registerWeb` 透传 `truncated`。
- `ui/ai/{ToolCallRows,AiSourcePreviewSheet,TopicAiSheet}.kt`：读取状态统一走 `webReadLabel` / `webReadState`。
- `app/src/main/assets/ai-skills/fact-check/SKILL.md`：补充未读完正文的使用限制。
- `app/src/test/resources/fixtures/web/ddg-lite-{results,empty,challenge}.html`：替换为真实脱敏样本。
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/WebSearchTest.kt`、`app/src/test/kotlin/com/chasel/ng2n/data/ai/WebToolSessionTest.kt`：按真实样本与新行为更新，新增截断、条数、POST、部分失败等用例。
- `docs/ai-assistant.md`、`docs/adr/0006-koog-agent-runtime.md`：同步 POST、验证页识别、结果条数上限、截断标记与通知条触发条件。

### 本轮验证

```
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest --rerun-tasks    BUILD SUCCESSFUL
```

1281 个用例，0 失败，5 跳过。JDK 17，未设置 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`。

票的第三项验收「真机移动网络：事实核查一次」仍未执行，设备与真实模型联调不在本次授权范围。
