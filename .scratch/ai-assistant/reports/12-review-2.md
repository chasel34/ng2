VERDICT: FAIL

# 票 12 联网搜索与网页读取 — 评审 2

对照 [评审 1](12-review-1.md) 的四条意见与 [实现报告](12-impl.md) 的「评审 1 修复」小节逐条核对，并复查本轮新增的表单 POST、验证页识别与截断标记是否引入新问题。

自行执行的验证：

```
JDK 17，未设置 NGA_INTEGRATION / NGA_WRITE_SMOKE
./gradlew --offline :app:testDebugUnitTest --rerun-tasks    BUILD SUCCESSFUL
./gradlew --offline :app:assembleDebug                      BUILD SUCCESSFUL
```

XML 结果汇总：1281 个用例，0 失败，5 跳过，与实现报告一致。主源码无残留的 `extractWebPageText` / `WebReader::get` 旧引用。

代码本身四条都已真正修复，未发现新的功能或安全问题。唯一需要修改的是实现报告里关于真实样本的描述与提交的 fixture 不符。

## 需要修改的问题

### 1. 低 — 实现报告对真实验证页与零结果页的描述与提交的 fixture 不符

位置：`.scratch/ai-assistant/reports/12-impl.md`，「评审 1 修复 / 2（中）解析单测未用真实 Lite 样本」

报告写「**真实验证页不含原先假设的字样。** 实际页面只有 `anomaly-modal` 与 `/assets/anomaly` 资源路径，没有 `anomaly.html` / `bots use duckduckgo`」。对提交的 `ddg-lite-challenge.html` 实测：

| 标记 | 出现次数 | 出处 |
| --- | --- | --- |
| `anomaly-modal` | 56 | 弹窗类名 |
| `/assets/anomaly` | 9 | 拼图图片路径 |
| `challenge-submit` | 1 | 提交按钮 |
| `bots use duckduckgo` | 1 | `<div class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</div>` |
| `anomaly.js` | 2 | `<form id="challenge-form" action="//duckduckgo.com/anomaly.js?...">` |
| `challenge-form` | 2 | 同上 |
| `anomaly.html` | 0 | 确实不存在 |

即评审 1 时已有的 `bots use duckduckgo`、`anomaly.js`、`challenge-form` 三个标记本来就能命中这张真实验证页，只有 `anomaly.html` 落空。同一小节还写「真实零结果页的措辞是 `No results found for ...`，由 `no results` 命中，不再落到兜底分支」，而 `no results` 在评审 1 的版本里已经在 `EMPTY_MARKERS` 中，零结果页此前也不会落到兜底分支。

结论方向没错（真实样本确实值得保存，新增标记也没有害处），但报告把「补齐标记」说成了修复一处实际错误分类。真实样本真正暴露的只有 GET 被验证页拦截、需要改表单 POST 这一条。按现在的写法，后续维护者会以为旧标记全部失效，可能把仍然有效的标记删掉。

同一小节的 fixture 字节数表也与提交的文件对不上：`ddg-lite-results.html` 实际 23585（表中 21709），`ddg-lite-challenge.html` 实际 14044（表中 14159），只有 `ddg-lite-empty.html` 的 8037 一致。

修复建议：把该段改成「真实样本确认旧标记仍然命中，另补 `anomaly-modal`、`/assets/anomaly`、`challenge-submit` 作为更贴近当前页面结构的判据；`anomaly.html` 在当前页面已不存在」，删掉关于零结果兜底的说法，并按提交文件更正字节数。代码不需要改。

## 逐条复核

### 评审 1 第 1 条（中）通知条在部分搜索成功时仍然出现 — 已修复

`app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt:43` 收紧为三个条件同时成立：存在失败的 `search_web`、没有任何 `search_web` 为 `ok`、没有任何 `read_webpage` 为 `ok`。设计稿 `factSearchDown`（唯一一次搜索失败、无网页读取）仍然出现通知条，`fact`（多次搜索读取、其中一次失败）不再出现。`WebToolSessionTest.部分搜索失败不触发通知条` 覆盖全失败、失败+成功搜索、失败+成功读取、全成功四种组合；原有的 `TopicAiViewModelTest.webSearchFailureKeepsForumAnswerAndRaisesNotice` 仍然通过。注释说明了收紧的原因，docs 与 ADR 同步。

### 评审 1 第 2 条（中）解析单测未用真实 Lite 样本 — 已修复

三份 fixture 确为真实响应：结果页 23585 字节、10 个 `result-link` 与 10 个 `result-snippet`、真实站点链接（stats.gov.cn、data.10jqka.com.cn、sc.macromicro.me、tjj.sh.gov.cn 等）；零结果页 8037 字节，`<div class="no-results__message"><h1>No results found for ...`；验证页 14044 字节，完整的 anomaly 弹窗与拼图表单。

脱敏已核实：三份文件都不含任何 32 位十六进制串，`vqd` 已替换为 `VQD-TOKEN`，一次性令牌与图片哈希替换为 `redactedtoken...` / `REDACTEDIMAGEHASH`，无 IP、Cookie 或账号信息。

分类顺序安全：`CHALLENGE_MARKERS` 先于 `EMPTY_MARKERS` 判断，而结果页与零结果页都不含任何 challenge 标记（结果页里 `anomaly` / `captcha` / `challenge` 均为 0 次），不会把零结果误判成验证页。

解析正确性另行核实：真实结果页里 `result-link` 锚点与 `result-snippet` 单元格严格交替（ASASAS…），按实现的配对算法 10 条全部配到摘要，没有错位。单测里 `count { it.snippet.isNotBlank() } >= 9` 的下限比实际情况松一条，属于可接受的容差，但收紧到 10 能更早发现配对回归。

原 fixture 覆盖的私网链接丢弃与 `uddg` 重定向解码两个场景改为内联片段单测，覆盖未丢失。

### 评审 1 第 3 条（低）正文截断无信号 — 已修复

`extractWebPage` 返回 `WebPageText(text, truncated)`，`text.length > limit` 即置位；`WebFetchResult.truncated` 在字节读取触达 2 MiB 时置位，两者取或。工具结果返回 `truncated` 字段，`scope` 与 `detail` 都写明未读完，`AiWebSource.truncated` 参与 `webReadState` / `webReadLabel`，资料正文（`TopicContext.kt:24`）、来源列表（`TopicAiSheet.kt:170`）、已读网页标签（`ToolCallRows.kt:108`）与站外预览（`AiSourcePreviewSheet.kt:125,136`）四处显示一致。fact-check 技能补充了 `truncated` 的使用限制。字数上限与字节上限各有一个单测。

`AiWebSource.truncated` 带默认值，旧 `sourceWeb` 元数据仍可解码。`registerWeb` 只在 `bodyRead` 为真时写入新的 `truncated`，否则沿用既有值，后续的搜索注册不会清掉已有的未读完标记。

### 评审 1 第 4 条（低）`count` 与 `detail` 夸大可用结果数 — 已修复

`count` 改为实际注册条数，超出 `RESULT_LIMIT = 10` 的部分放进 `omitted`，并在 `scope` 与 `detail` 中写明这些结果没有来源编号、不能引用。`WebToolSessionTest.超出单次上限的结果单独说明且不分配来源` 用 14 条结果验证 count=10、results=10、omitted=4。

## 本轮新增行为的核查

- **表单 POST。** `WebToolSession` 向 `https://lite.duckduckgo.com/lite/` POST 字段 `q`，`WebReader.fetch(url, form)` 用 `FormBody` 发送；`WebToolSessionTest.搜索用表单 POST 发起` 断言了 URL 与表单内容。重定向后 `body = null`，跳转不会重发表单，符合 HTTP 语义也避免把查询词发到第三方。URL 策略仍在每一跳请求前执行，`PublicOnlyDns`、`followRedirects(false)`、`CookieJar.NO_COOKIES` 与 3 跳上限都未改动，POST 没有削弱任何一条防护。`load` 对 5xx 的一次重试会重发同一表单，搜索是幂等操作，没有副作用。GET 稳定被验证页拦截这一结论来自实现者的实测，离线环境无法复核，但 POST 行为本身已被单测固定。
- **验证页与零结果识别。** `CHALLENGE_MARKERS` 只增不减，`EMPTY_MARKERS` 去掉冗余的 `no results found`（被 `no results` 覆盖）、新增 `not many great matches`。新增项在三份 fixture 中都不出现，属于未被测试覆盖的冗余判据，但只在解析不出任何结果时才生效，不会把有结果的页面误判。结构未知按验证页兜底的行为保留。
- **字节上限标记。** `overflow = output.size() >= MAX_BYTES` 在正文恰好等于 2 MiB 时也会置位，属于偏保守的一侧（宁可报未读完），不影响正确性。

## 未变更部分的复核

SSRF 与私网防护、任何域名不携带 NGA Cookie、并发与缓存与重试上限、站外来源预览与「打开网页」、说法卡片渲染、个人入口不注册联网工具、`core` 纯 Kotlin、注释约定、无新增依赖，均与评审 1 的核实结论一致，本轮改动未触及这些路径。`docs/ai-assistant.md` 与 ADR-0006 已同步表单 POST、验证页识别依据、单次 10 条上限、截断标记与通知条触发条件。

票的第三项验收「真机移动网络：事实核查一次」仍未执行，实现报告如实标注为授权范围外。
