# 验收组 D + E 修复说明

验证命令（JDK 17，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE，未使用模拟器）：

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，1298 个离线用例、0 失败（修复前 1290）。另单独执行 `:app:compileDebugAndroidTestKotlin --rerun-tasks` 确认 UI 用例仍可编译，未在设备或模拟器上运行。未执行 git commit，未读取 `.env.local`。

---

## P1 `maxAgentIterations = 8`：多工具分析必以「回答生成中断」结束且不可恢复

**根因**：两层。

1. 循环上限写死为 8。Koog 的迭代计数覆盖策略图上执行过的每个节点（start + 每次 LLM 请求 + 每次工具执行），离线实测为 `2 × LLM 请求数`，所以 8 只够 4 次模型请求。事实核查要先读 3 份技能文件再做 2 次搜索，第一轮就把预算用光。
2. `AIAgentMaxNumberOfIterationsReachedException` 与真正的流中断走同一条失败分支。`classifyAiFailure` 在已经产生输出时一律返回 `INTERRUPTED`，于是一份已经写完并落库的完整回答被扣上红色错误卡；而完全没有文字的那种又被 `budget.requestId` 判据挤进 `READ` 卡（「尚未请求模型」），点继续永远回到同一处。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`：新增 `AiRunLimits(iterations = 40, toolCalls = 24, repeats = 4, outputTokens = 4096)`、`AiRunLimitReached`、`aiRunLimitDetail()` 与 `AiFailure.LIMIT("达到限制")`；`classifyAiFailure` 先沿 cause 链识别 `AiRunLimitReached`，在 `outputStarted` 判据之前返回 `LIMIT`。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`：新增 `runLimitsFor(allowance)`（短问答 24 步 / 12 次工具 / 1536 输出，默认 40 / 24 / 4096，长楼 48 / 32，更高 64 / 48）与 `AiBudgetRepository.limits()`；`AiRunBudget` 增加 `limits`、工具执行计数与连续重复计数。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`：`maxAgentIterations` 与 `DeepSeekParams(maxTokens = …)` 都取自 `budget.limits`；`agent.run` 单独捕获 `AIAgentMaxNumberOfIterationsReachedException`，配合新的 `answerIsComplete(response)` 判据——最后一条 assistant 消息不含工具调用且有文字时按正常完成返回，否则抛 `AiRunLimitReached` 并保留原异常为 cause。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：发起运行时把 `budgets.limits()` 写进 `AiRunBudget`；失败分支区分「达到限制」与失败，run 状态记为 `limit` 而不是 `failed`，状态文案为「达到限制 · <具体上限>」，卡片为 `LIMIT`，`incomplete = true` 让「继续」按钮出现；`READ` 判据排除 LIMIT，避免把已请求过模型的运行说成尚未请求。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiHistoryScreen.kt`：历史徽标新增「达到限制」，排在通用「已中断」之前。

迭代上限不再是唯一闸门：真正收敛的是工具执行次数上限与连续重复读取阈值（`AiRunBudget.beforeTool` 按「工具名 + 参数」签名判定），循环上限只作兜底，取值随本次分析的额度档位变化，符合 cost-and-recovery.md 「分别限制模型尝试次数、工具执行、连续失败」与「区分『已完成』和『达到限制』」。

**验证方式**：新增 `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/AgentRunLimitsTest.kt`。

- `reachingTheIterationLimitKeepsGeneratedTextAndAllowsContinuing`：把上限压到 3 步、模型先吐文字再发工具调用，断言抛出 `AiRunLimitReached`、`classifyAiFailure` 归为 `LIMIT`、明细为「本次分析已达到执行上限（3 步）」、已流出的文字完整保留；随后用默认上限再跑一次同一 `ForumToolSession`，断言拿到完整回答。
- `completedAnswerIsNotReportedAsALimitEvenWhenTheLoopCounterTripped`：直接覆盖 `answerIsComplete` 的四种情况（纯文字、null、空文字、文字带工具调用）。
- `defaultLimitsCoverAFactCheckShapedLoopThatTheOldEightStepCapKilled`：6 轮工具 + 收尾共 7 次模型请求，走真实 Koog 循环跑完，断言默认上限 ≥ `2 × 请求数`——这正是旧的 8 必然打断的形状。
- `toolExecutionCapAndRepeatedReadsEndTheRunAsALimitNotAFailure`：覆盖工具次数上限与连续重复读取阈值。
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt` 新增 `reachingTheRunLimitKeepsTextAndSourcesMarksItAsALimitAndContinuesInTheSameAnalysis`：断言卡片为 `LIMIT`、状态为「达到限制 · 本次分析已达到执行上限（40 步）」、`incomplete`、已生成文字既在界面也已落库，`retry()` 之后同一 `analysisId` 完成且卡片清空。

**未做**：真实模型下的端到端复验需要设备，本轮按约束未执行。

---

## P2 中断卡宣称「已保留文字和 N 条来源」但该轮 text 为空

**根因**：卡片正文是固定字符串，既不看本轮是否真的有文字，也不看来源数的口径。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt`。正文按 `turn.text` 是否为空分支：为空时写「本轮没有生成文字，已读取的资料已保留。」，非空才说「已保留文字和 N 个来源。」。新增 `LIMIT` 卡用同一句加「继续会复用已读资料和检查点，可能产生新的费用。」，配色归入提示色而非危险色。

**验证方式**：`AgentRunLimitsTest` 与上面的 ViewModel 用例覆盖了「文字为空」与「文字非空」两条数据路径；卡片文案本身由既有 androidTest `AiBudgetCardsTest` 渲染，未在设备上重跑。

---

## P2 设置页「今日用量」明细里「网页」恒为 0

**根因**：对话页脚的网页次数来自 `turn.tools`（内存中的工具行），设置页只有预算账本，账本里根本没有这个字段，于是恒为 0。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`：`AiBudgetRequest` 增加 `web`，`AiBudgetBook` 增加 `webRead(id)`；新增 `AI_WEB_TOOLS`。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`：`AiRunBudget.beforeTool` 在工具名属于联网工具时，把次数记到发起这批工具调用的请求上。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt`：`AiUsageDetails` 去掉 `webpages` 参数，改为 `requests.sumOf { it.web }`，两处入口从此同一口径。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：去掉页脚自己传入的 `webpages`。

**验证方式**：`AgentRunLimitsTest.webToolExecutionsAreRecordedOnTheRequestSoBothUsageViewsAgree` 断言两次联网工具、一次论坛工具后请求上的 `web` 为 2。

---

## P2 快捷菜单键盘 ↓/Esc 被浮层背后的内容抢走、双高亮

**根因**：菜单是非获焦的独立弹窗窗口，按键只会落到当前获焦控件上。按键处理挂在输入框的 `onPreviewKeyEvent` 上，而点 `/` 按钮会把焦点移到按钮本身，之后的 ↓ 就按普通焦点搜索跳到浮层背后的聊天列表，于是系统焦点框与菜单高亮同屏出现，Esc 也进不来。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposer.kt`。给输入框加 `FocusRequester`，`open` 变为 true 时把焦点放回输入框；按键处理提成 `handleKey`，同时挂在输入框和整个 composer 根节点上，焦点落在 `/` 按钮或发送按钮时也能接住。筛选、Enter/Tab 选择、输入法组合期不发送等逻辑不变。

**验证方式**：离线只能覆盖到编译与既有 androidTest 的编译（`compileDebugAndroidTestKotlin` 通过）。焦点行为需要设备/模拟器，本轮按约束未验证，留给下一轮。

---

## P2 短问答档首个请求就超额

**根因**：预留把输入 token 按 UTF-8 字节数估算。中文一个字符 3 字节，而 DeepSeek 官方口径约 0.6 token，高估约 5 倍。一页普通中文主题（约 1.2 万字）加工具定义就估到 5 万多 token，仅输入就超过 US$0.017，再叠加 4096 输出上限的 US$0.005，第一次请求必然撞上 US$0.02。这一条与「跳过预留检查」无关，纯粹是估算失真。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`：新增 `estimateTokens(text)`，按字符估算（宽字符 0.7、其余 0.4，均略高于官方系数保持保守）；`AI_PROTOCOL_TOKENS = 4096` 协议余量保留；每张图片的预估从 65536 收到 `AI_IMAGE_TOKENS = 16384`，并在注释里写清推导——读图前已采样到长边 ≤2048 像素，按每 16×16 像素一个视觉 token 得到该上界，仍高于常见视觉模型实测值。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`：新增 `estimateRequestTokens(messages, registry)` 取代原来的字节求和，两处 `prepare` 调用共用。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`：`reserve` 的输出上限不再写死 4096，改取当前档位的 `outputTokens`（短问答 1536）。

修复后短问答档首个请求的实测预留为 US$0.007097（1.2 万字中文主题、无图），带一张图约 US$0.012，都在 US$0.02 之内。预留检查本身没有放宽。

**验证方式**：`AgentRunLimitsTest.shortAllowanceStartsAFirstOverviewInsteadOfExceedingItsOwnReservation` 用 20 层、每层 600 字中文的真实主题上下文，把额度设为短问答档跑完一次概览，断言不抛 `AiBudgetExceeded` 且单次预留小于 20000 微美元。

---

## P3 同一屏「N 个来源」与「N 条来源」口径不同

**根因**：页脚用本轮引用数，状态卡用 `state.context.sources.size`（整个会话累计）。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt` 把 `cited.size` 传给 `AiBudgetCard`；`AiBudgetCards.kt` 的量词统一为「个来源」。同一屏两处从此是同一个数。

---

## P3 楼层预览的图片计数与用户看到的图不一致

**根因**：论坛阅读用 `collectFloorImages`，AI 资料用 `buildTopicContext` 里另写的一套遍历，两份实现各自去重、各自处理相册与附件，口径没有结构性保证；界面上又把同一张图分别渲染在正文和附件区，用户看到两处、统计只有一张，无从判断附件是否计入。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AlbumImages.kt`：新增 `ATTACHMENT_IMAGE_KIND` 与 `floorImageUrls(nodes, attachments, options, urls)`，正文内联图（含相册）按出现顺序在前、图片附件在后，按最终地址去重。
- `app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/FloorImages.kt`：`collectFloorImages` 改为在这份实现之上只补缩略图来源，URL 列表与顺序完全一致。
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`：`AiSource.images` 改用同一函数，正文展开只负责 `[图片]` 标记。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheet.kt`：图片计数行补一句「正文内联图与图片附件一并计入，同一地址只算一张。」

结论：验收里那个楼层的「点击显示附件(1)」与正文内联图是同一个文件，统计为 1 张是对的，缺的是口径说明与单一实现来源。

**验证方式**：`TopicContextTest.floorImagesCountAttachmentsAndMatchTheForumReaderListExactly`，构造「内联图 + 同一张图的附件 + 另一张只在附件里的图 + 一个非图片附件」，断言 AI 资料的图片列表为两张、并与 `collectFloorImages` 的结果逐项相等。

---

## P3 主题入口历史条目标题与副标题重复主题名

**根因**：条目标题就是主题名，副标题又从 `context.title` 再拼一次，只排除了「主题 tid」占位。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt` 的 `save()`：主题名与条目标题相同时不写入副标题。

**验证方式**：ViewModel 新增用例断言落库副标题为「主题 42 · 用户1」，不再重复标题。

---

## P3 回答里出现未闭合的 `**`

**结论**：渲染器行为正确，不是缺陷。`AiMarkdown` 的行内解析在找不到配对定界符时（`value.indexOf(delimiter, i + len)` 返回 -1）不进入强调分支，逐字符原样输出，所以 `**` 按字面显示、后文不会被吞掉。验收看到的残留是模型输出本身少了后半个 `**`。

**改动文件**：`app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiMarkdownTest.kt` 补 `unclosedEmphasisIsShownLiterallyInsteadOfSwallowingTheRestOfTheLine` 固化这一行为（只编译，未在设备运行）。渲染逻辑未改。

---

## P3 「API Key 无效」文案

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`，`AiFailure.AUTH` 标题改为票面的「API Key 无效或余额不足」。401 仍归 AUTH，402 仍单独归 `BALANCE("余额不足")`，两者都指向设置。

---

## 文档

- `docs/ai-assistant.md`：输出上限改为按档位取值；补「执行上限与金额额度分开」「达到限制」的定义与恢复行为；额度段补预留按字符估算与各档的输出/工具/循环上限；用量段说明两处明细同口径、网页次数记在请求上；图片段说明楼层图片与论坛阅读同口径。
- `docs/adr/0006-koog-agent-runtime.md`：记录 `maxAgentIterations` 与输出上限取自额度档位、Koog 迭代计数覆盖每个已执行节点、`AIAgentMaxNumberOfIterationsReachedException` 与 `AiRunLimitReached` 的转换判据。

## 未做的事

- 未执行 git commit。
- 未使用模拟器或真机：焦点/按键、卡片文案、图片预览的实际显示均未在设备上复验。
- 每张图片 16384 token 与各档位的工具次数、循环上限仍是保守估计，尚未用真实样本校准，文档保留了这条说明。
