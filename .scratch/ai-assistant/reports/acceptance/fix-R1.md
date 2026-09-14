# 回归复验 R1 修复说明

验证命令（JDK 17，`--offline`，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE，未使用模拟器或真机）：

```bash
ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，JUnit XML 合计 1305 项、0 failures、0 errors、5 skipped（默认关闭的联网测试）；修复前为 1303 项。另单独执行 `:app:compileDebugAndroidTestKotlin` 确认 UI 用例仍可编译，未在设备上运行。`git diff --check` 通过。未执行 git commit，未读取 `.env.local`。

---

## P1 成因 A：输出上限装不下报告，且被标成「回答生成中断」

**根因**：`AiRunLimits.outputTokens` 既是下发给服务商的 `max_tokens`，又要同时容纳思考 token，长楼/个人档取默认 4096，个人报告 JSON 写不完；`TopicAgentRuntime` 对 `finishReason in ("length","max_tokens")` 抛的是裸 `IllegalStateException`，`classifyAiFailure` 因为已经有文字而归为 `INTERRUPTED`。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`：`AiRunLimits` 拆成 `outputTokens`（回答正文预算）与 `thinkingTokens`（同一次生成的思考预算），新增 `maxTokens = 两者之和`，即实际下发与预留的上限。默认 2048 + 2048、短问答档 768 + 768，合计与改动前的 4096 / 1536 完全相同，非个人入口的线上取值不变。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`：`runLimitsFor(allowance, entry)` 增加入口参数，个人入口取 `PERSONA_REPORT_OUTPUT_TOKENS = 8192` 正文 + `PERSONA_REPORT_THINKING_TOKENS = 4096` 思考（合计 12288）。取值依据：满额 300 条样本的 `processedSourceIds` 约 1800 字，加上各卡正文、时间线与边界声明按约 12000 字估算，中文约 0.7 token/字。`reserve` 增加 `output` 参数，`AiRunBudget` 每次预留都按本次运行真正下发的 `maxTokens` 计算，不再回落到档位默认值。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`：新增 `requireCompleteGeneration`，两处请求节点共用。末帧不是 `End` 仍是「回答生成中断」；`finishReason` 为 `length` / `max_tokens` 时抛 `AiRunLimitReached("回答达到本次输出上限（N token，含思考），尚未写完")`，因此走 `AiFailure.LIMIT` 的「达到限制」状态卡，保留已生成文字与来源并提供「继续」。`DeepSeekParams(maxTokens = limits.maxTokens)`。

个人入口无论档位都用报告体量的上限：短问答档下首个请求的保守预留可能直接触发额度确认卡，这是既有的「追加额度」路径，不会静默截断报告。

**验证方式**：`app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/AgentRunLimitsTest.kt` 新增
`generationCutOffByTheOutputCeilingIsALimitAndPersonaGetsAReportSizedBudget`：
真实 Koog 循环下让流以 `End("length")` 结束，断言异常是 `AiRunLimitReached`、`classifyAiFailure` 为 `LIMIT`、
`aiRunLimitDetail` 写明具体上限、已流式输出的半份 JSON 原样保留，并断言个人档的正文/思考取值、
`maxTokens` 大于普通长楼档、账本里的预留按 12288 而不是档位默认值计算。

## P1 成因 B：补读来源被来源校验判为越界

**根因**：`parsePersonaReport` 要求 `processedSourceIds` 的序号落在 `1..sampleCount`，且证据、相反表述与时间线来源都必须属于 `processedSourceIds`。persona skill 要求对只有标题的样本用 `read_floor` 补读主楼，补读来源的序号必然大于 `sampleCount`，模型一旦引用，整份报告作废。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`：新增 `personaSampleId(id, sampleCount)` 与 `PersonaReport.processedSampleCount(sampleCount)`。校验改为：`processedSourceIds` 只需是本会话已注册来源；证据、相反表述与时间线来源可以引用任意已注册来源，但**属于初始样本的序号仍必须先出现在 `processedSourceIds` 里**，补读来源只要求真实存在。「只对成功处理的样本下结论」这条约束因此保留，补读不再作废报告。`mentions` 上界改用初始样本的已处理数（并至少为代表性发言数）。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt`：报告顶部「已处理 N 条 · 未处理 M 条」改用 `processedSampleCount`，补读来源不再被算成已处理样本。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：样本覆盖判定（本轮结束与历史恢复两处）同样改用 `processedSampleCount`。
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md`：说明 `processedSourceIds` 只列初始样本；证据与时间线可以直接引用补读来源，也可以保留初始标题样本 ID 并在正文附补读来源；补读来源不计入已处理样本数。另补一句「报告要在一次输出内写完，正文只保留必要长度」。
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt`：`BuiltinQuickActions.VERSION` 由 `3` 升到 `4`，让已安装设备重新释放改过的技能目录。

**验证方式**：`PersonaContextTest.reportAcceptsEvidenceFromToolReadSourcesAndCountsOnlyInitialSamples`：
两条初始样本 + 一条 `read_floor` 补读来源 `s3`，证据与时间线都引用 `s3` 的报告通过校验，
`processedSampleCount` 仍为 2；把 `s3` 换成不存在的 `s9` 仍被拒；引用未列入已处理的初始样本 `s2` 仍被拒。
既有的「证据未处理」「证据与反例重合」「时间线越界」「命中条数越界」用例全部保持原结论。

## P3 同屏两处「回答生成中断」

**根因**：状态卡的标题就是 `turn.status`，卡片下方又渲染了一次同样的 `turn.status`。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`，红色状态行改为只在 `turn.card == null`（没有状态卡）时渲染。折叠的额度卡仍显示「已达到本次额度 · 待确认」按钮，信息不丢失。

## P3 报告校验失败时状态卡仍写「已保留文字」

**根因**：`AiBudgetCard` 只判断 `turn.text` 是否为空，而报告结构无效时界面用灰色占位替换掉了整段 JSON，用户一个字都看不到。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt`：`AiBudgetCard` 增加 `answerShown` 参数；为 false 时文案改为「本轮输出的报告结构不完整或未通过来源校验，界面没有展示这段内容；已读取的资料与来源已保留。」，后接原有的「继续」说明。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：把「是否用占位替换了报告草稿」这一判断抽成 `reportDraft`，同时驱动占位文案与 `answerShown`，两处不会再各说各话。底部按钮在这类卡片下仍是「继续」。

## P3 普通追问下挂着报告轮的「待确认处理」说明

**根因**：条件是「个人入口 + 本轮未完成 + 没解析出报告」，普通追问被停止或中断时同样命中。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：`AiTurn` 增加 `reportExpected`，在创建该轮时按「个人入口 + 非快捷操作 + 尚未有覆盖全部样本的报告」写入；字段随消息 JSON 持久化与恢复，旧记录默认 false。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：那句「已读取 N 条；本轮尚无已确认处理完成的样本…」改为只在 `turn.reportExpected` 的轮次显示。

**验证方式**：`TopicAiViewModelTest.personalReportCoversOnlyProcessedSamplesAndKeepsDraftForContinuation`
扩展为三轮：两轮报告轮 `reportExpected` 为 true，报告覆盖全部样本后再问一个普通问题，
该轮 `reportExpected` 为 false 且状态为「已完成」。

## 文档

`docs/ai-assistant.md`：输出上限按正文与思考分别估算后相加、个人分析的报告体量取值；生成因输出长度上限结束归入「达到限制」且不算中断；状态卡与状态行不重复同一句说明，文字未展示时不写「已保留文字」；报告证据可引用补读来源而已处理数只算初始样本；待确认处理说明只出现在报告轮。

## 未做的事

- 未 git commit，未使用模拟器或真机：状态卡文案去重、报告全屏渲染、待确认说明的实际显示都没有在设备上复验。
- 票 11 的卡片级验收（倾向卡、数量胶囊、信号条、两个抽屉、时间线、代表性发言跳楼层）仍需真机用真实账号重跑一次个人分析。本轮只保证：补读来源不再作废报告、输出上限按报告体量取值、被截断时按「达到限制」保留草稿。
- 个人报告的 12288 token 上限是按 300 条样本的体量估算，没有真实长报告样本校准；真机复验时应留意个人分析是否更早触发单次额度确认卡。
- accept-FG 记过、R1 再次复现的「零样本仍请求模型」本轮未再改动（fix-FG 已修，属于该轮范围）。
