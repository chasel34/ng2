# 验收组 B 修复说明

验证命令（JDK 17，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE）：

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，1283 个用例、0 失败。

## P1 所有模型请求被 DeepSeek 以 HTTP 415 拒绝

**根因（字节码级确认，不是猜测）**：Koog 1.2.0 `OkHttpKoogHttpClient.prepareRequestBody` 按请求体类型选择媒体类型——请求体是 `String` 时默认 `text/plain`，其它类型才默认 `application/json`；只有调用方在 headers 里显式给出 `Content-Type` 才会覆盖这个默认值。而 `AbstractOpenAILLMClient.executeStreaming`（DeepSeek 客户端继承它）先把 chat 请求序列化成 JSON **字符串**，再以 `requestBodyType = String::class` 调用 `sse(...)`，且 `parameters` 与 `headers` 两个参数都走默认值 `emptyMap()`。两者相接的结果是请求以 `Content-Type: text/plain; charset=utf-8` 发出，DeepSeek 在鉴权之后按媒体类型不受支持返回 415。

与两个既有怀疑点无关：okhttp 5.5.0 的 `MediaType$Companion.get(String)` 与 `RequestBody$Companion.create(String, MediaType)` 都还在，不存在 ABI 断裂；`deepseek-flash` 这个模型 id 服务端接受。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/BudgetHttpClient.kt`：新增 `jsonBodyHeaders`，在 `sse` 以及新增覆写的 `post`、`lines` 上补 `Content-Type: application/json`；调用方已声明（不区分大小写）时不覆盖。委托对象其余行为不变。
- `docs/adr/0006-koog-agent-runtime.md`：记录该上游默认值与本机补充，注明上游修正后应移除。

**验证方式**：

- 新增离线回归测试 `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/ModelRequestHeadersTest.kt`，经 `DeepSeekClientFactory` → `BudgetHttpFactory` → Koog DeepSeek 客户端向 MockWebServer 真实发起一次流式请求，断言 `Content-Type: application/json`、`Accept: text/event-stream`，以及请求体是带 `messages` 的 JSON。修复前该测试以 `expected:<application/json> but was:<text/plain>` 失败，修复后通过。
- 用 `.env.local` 的 Key 做了一次 `max_tokens = 16` 的真实调用，经同一条装配链拿到完整流：16 个思考帧 + 文本帧 + 结束帧，usage 为 input 34 / output 16 / total 50。Key 未写入任何文件或日志，探测用的临时测试已删除。

## P2 415 等确定性客户端错误被记为结果不明、不释放预留

**根因**：`classifyAiFailure` 只枚举了 400/401/402/422/429/500/503，415 落到 `UNKNOWN`；`AiRunBudget.finish()` 只按 `transportStarted` 二分——已发出即记 `pending_verification` 并保留保守预留，没有「服务端明确拒绝、无 usage」这一类。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`：新增 `rejectedHttpStatus`（429 以外的 4xx）；`classifyAiFailure` 把这类状态归入 `PARAMETERS`，401/402/429/500/503 的原有分类与顺序保持不变；`AiBudgetBook` 新增 `rejected(id)`，置 `status = "rejected"`、`cost = 0`，释放预留但保留请求记录。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`：`AiRunBudget` 新增 `serverRejected`，在 `reserve` 与 `retryAttempt` 重置，`finish()` 按 rejected / pending / not_sent 三分。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/BudgetHttpClient.kt`：HTTP 层拿到确定状态码时标记 `serverRejected`；429/500/503 的重试分支在此之前，不受影响。
- 文案沿用已有状态卡：AUTH / BALANCE 显示「去设置」，PARAMETERS 显示「请求格式或模型能力不兼容，没有原样重试。已有输入与资料已保留。」不再落到「请求结果不明」。
- `docs/ai-assistant.md`、`.scratch/ai-assistant/cost-and-recovery.md` 同步补充该分类。

**验证方式**：`AiRetryTest.deterministicClientErrorsReleaseTheReservation` 让 MockWebServer 返回 415，断言只发一次请求、账本记录为 `rejected`、`charged == 0`、分类为 `PARAMETERS`；`AiBudgetTest` 增加 415 的分类断言与 `rejectedHttpStatus` 的正反用例（429、503 不算拒绝）。设置页的「N 次请求用量待核实」按 `cost == null` 统计，cost 置 0 后该行不再出现。

## P2 楼层入口范围卡「选中楼层」恒为 0 楼

**根因**：该行取的是 `built.sources.firstOrNull { it.pid == selectedPid }.floor`，即楼层**序号**；而同一张卡的其它行（第 1 页 20 楼、当前页 19 楼）用的是**条数**语义，并与「已读 N 条」相加对应。选中楼层是用 `pid` 过滤重新读取的单楼，返回的 `lou` 为 0，于是显示成「0 楼」，与「已读 2 条」自相矛盾。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`，改为条数语义：读到选中发言显示「1 楼」，否则「未计入」。主楼正文行本来就是「1 楼」，两行合计与「已读 2 条」一致；具体是第几楼由面板副标题「单个楼层 · N 楼」给出。

**验证方式**：`./gradlew --offline :app:testDebugUnitTest` 全量通过（含 `TopicAiViewModelTest`）。

## P3 思考结束态应为「已思考 N 秒」

**根因**：`thoughtSeconds` 只在收到第一个文本帧时写入。失败路径没有文本帧，该字段保持 null，界面落到「思考已结束」兜底文案，丢掉耗时。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：运行结束的 `finally` 里，若该轮已开始思考而尚未记录耗时，按 `thinkingStarted` 补记秒数。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：删除「思考已结束」分支。有耗时显示「✓ 已思考 N 秒」，生成中且是最后一轮显示「正在思考 · N 秒」，两者都不满足则不显示该行，不猜一个 0 秒。

**验证方式**：全量单元测试通过；文案与票 04、`design/ai-assistant/Main.dc.html` 一致。成功路径的「已思考 N 秒」原本就走 `thoughtSeconds` 分支，P1 修复后可在设备上复验。

## P3 面板副标题长标题硬截断

**根因**：`Text(state.title, maxLines = 1)` 没有设置 `overflow`，Compose 默认 `Clip`。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`，加 `overflow = TextOverflow.Ellipsis` 与对应 import，与主题详情顶栏一致。

**验证方式**：全量单元测试通过；该行为是纯渲染属性，设备上打开长标题主题即可看到尾部 `…`。

## P3 快捷操作菜单出现两处高亮

**根因**：菜单项用无参 `clickable`，按压涟漪由默认 indication 绘制。打开浮层时第 1 项残留了一次没有配对释放的按压态，键盘 ↑↓ 只改变自绘的 `primaryContainer` 选中背景，不影响这层残留，于是两处同时可见。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposer.kt`，菜单项改用显式 `interactionSource`（`navigated` 变化时重建，丢弃残留按压）并在键盘导航期间把 indication 置空，使当前项的选中背景成为唯一高亮；未使用键盘时按压反馈不变。

**验证方式**：全量单元测试通过；`b-down.png` 对应的场景需在设备上复验「↓ 之后只有一处高亮」。

## 未做的事

未执行 git commit。设备侧复验（P1 修复后重跑被阻断的第 2、6、8 项，以及流式渲染、「保留已生成文字／重新生成」、「继续问」建议）不在本次范围内。
