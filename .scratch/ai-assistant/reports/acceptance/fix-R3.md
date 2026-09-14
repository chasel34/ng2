# 回归复验 R3 修复说明

验证命令（JDK 17，`--offline`，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE）：

```bash
ANDROID_HOME=/Users/cola/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，JUnit XML 合计 1310 项、0 failures、0 errors、5 skipped（默认关闭的联网测试）；修复前为 1307 项。另单独执行 `:app:compileDebugAndroidTestKotlin` 确认改过的 UI 用例可编译。`git diff --check` 通过。未 git commit，未读取 `.env.local`，未安装或运行 App。

---

## P3 「达到限制」且本轮零文字仍写「已有草稿保留」

**根因**：fix-R2 只按卡片类型排除（`turn.card !in listOf("READ", "EMPTY")`），而「有没有草稿」取决于本轮有没有写出可展示的文字。`LIMIT` 卡片下本轮一个字都没生成时，这句仍然照写「已有草稿保留，可继续」，与同屏状态卡的「本轮没有生成文字」自相矛盾。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`：新增 `personaPendingNote(sampleCount, draftShown, sourceCount)`，按「本轮是否写出可展示文字」分支措辞：有文字才接「已有草稿保留，可继续。」；没有文字但已登记来源时只补「已登记 N 个来源。」；两者都没有时只留处理进度一句。措辞不与状态卡重复，状态卡继续负责说明文字与资料去向。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：这句说明改用该函数，`draftShown` 取 `turn.text.isNotBlank() && !reportDraft`，即报告 JSON 结构无效被占位替换的那轮也不算「可展示文字」。卡片类型的排除条件保留：`READ` / `EMPTY` 两种卡连模型都没请求过，整句仍然不显示（accept-R3 第 3 项已验收通过，不回退）。

**验证方式**：`PersonaContextTest.pendingNoteOnlyClaimsADraftWhenTheRoundActuallyShowedText`（离线单测）断言三种分支的完整文案。`TopicAiSheetTest.limitedRoundWithoutTextDoesNotClaimADraft`（androidTest）在 `LIMIT` 卡片下断言零文字时不出现「已有草稿保留」、出现「本轮没有生成文字」，把文字填上后这句才回来。

## P3 读取失败多的用户要连点 3 次「继续」才拿到报告

**accept-R3 记录的四轮分别停在哪一步**：第 1 轮撞工具执行次数上限（32 次）且零文字；第 2、3 轮撞输出上限（12288 token）且零文字，第 3 轮还出现「报告结构尚未完整」；第 4 轮才完成。累计 ≈US$0.0627，吃掉本次额度的 63%。

**根因**：执行上限的处理方式是「抛异常中止整个运行」。`AiRunBudget.beforeTool` 在工具次数或连续重复读取超阈值时抛 `AiRunLimitReached`，这个异常从 Koog 事件处理器一路穿出，本次运行当场结束，模型没有机会用已读资料写任何东西。用户点「继续」等于重开一次运行：工具结果能从检查点复用，但完整的提示词要重新发一遍，于是每轮都在「读一点 → 撞上限 → 零文字」之间打转。s4/s6/s17 的主楼补读失败还会被模型反复重试，同一个坐标每次都真的再请求一次论坛，把有限的工具次数耗在必然失败的读取上。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`：`AiRunBudget` 新增 `exhausted: String?`。工具次数上限与连续重复读取阈值不再抛异常，改为记下原因；金额与每日额度仍按原样抛 `AiBudgetExceeded`。标记之后不再计数，也不再把网页次数记到请求上（那次调用不会执行）。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`：运行图新增 `finalize` 节点。`request` 与 `sendResults` 的工具边加上互斥条件：未达上限走 `execute`，已达上限走 `finalize`。`finalize` 把这一批未执行的调用按 `limit_reached` 结果回传（保持工具调用与结果成对，协议不残缺），再追加一句「不能再调用任何工具，请立即用已读取的资料写出最终结果」，然后在同一次运行内发最后一次请求并直接结束。只有收尾之后仍然没有完整回答时才抛 `AiRunLimitReached`，界面才出现「达到限制」。三个节点共用的「补技能提示 → 预留 → 流式请求 → 校验生成完整」被抽成一个 `ask` 扩展 lambda，不再三份重复。
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`：
  - 所有论坛工具执行前检查 `AiRunBudget.exhausted`，已达上限直接返回 `limit_reached` 并说明该调用未执行，不发起任何读取（同一批里超出上限的调用因此也不会真的去读）。
  - 新增本次运行的失败记忆：某个坐标读取抛错，或读回来既没有发言也没有屏蔽计数（已删除/不可访问）时记下结果；同一坐标再被读取时直接返回上次的原因并注明「本次分析已经失败过，未重复请求，请改用已有资料，例如该样本的主题标题」，不再经过限速器和网络。失败记忆按运行清空（`beginRun()`，在 `runWith` 开头调用），用户点继续时仍可重试一次。
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md`：补读主楼失败时不要重复调用同一次读取，直接用该样本的主题标题作证据并说明正文未读取；工具返回 `limit_reached` 表示工具次数已用完，不要再调用任何工具，立即用已读资料写完报告。
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt`：`BuiltinQuickActions.VERSION` 由 `5` 升到 `6`，让已安装设备重新释放改过的技能目录。

**效果**：accept-R3 的第 1 轮（工具次数用尽、零文字）现在会在同一次运行内直接写出报告，不再要求用户点继续；失败的补读每个坐标在一次运行内只真正请求一次，省下的工具次数留给还能读到的资料。

**验证方式**（均为离线单测，`app/src/koogTest/.../AgentRunLimitsTest.kt`）：

- `toolCapReachedMidRunStillWritesTheAnswerInTheSameRun`：工具上限设为 3，模型连续要求 5 次读取。断言前 3 次为 `ok`、第 4 次为 `limit_reached`、第 5 次根本没有进入工具节点，收尾请求在同一次运行内返回完整回答，最后一次提示词里带「不能再调用任何工具」，整个运行没有抛出限制异常。
- `personaReportArrivesInOneRunWhenSeveralFloorReadsFail`：6 条只有标题的样本，补读主楼全部失败，模型每条都重试一次（共 12 次工具调用）。断言论坛读取只真正发生 6 次、6 条重复调用的明细里带「未重复请求」，并且这一次运行就产出通过 `parsePersonaReport` 校验、已处理样本数为 6 的报告。
- `toolExecutionCapAndRepeatedReadsStopFurtherToolsWithoutEndingTheRun`：原来断言抛异常的用例改为断言记下 `exhausted`，并验证标记之后论坛工具直接返回 `limit_reached`、不再调用读取回调。

## 文档

- `docs/ai-assistant.md`：达到工具次数或重复读取阈值时本次运行不再执行工具、未执行调用按上限结果回传并在同一次运行内收尾，收尾后仍无完整回答才标「达到限制」；同一坐标在本次运行内失败后不重复请求；达到执行上限后工具返回 `limit_reached`；个人分析补读失败改用主题标题作证据；本轮没有可展示文字时待确认处理说明不提草稿。
- `.scratch/ai-assistant/cost-and-recovery.md`：预算实现一节补记同一行为。

## 未做的事

- 未 git commit，未读取 `.env.local`，未重装 debug 包（按约定由团队统一安装），因此未在设备上复验这两条修复的实际表现。
- 未改输出上限：accept-R3 第 2、3 轮撞的是 12288 token（正文 8192 + 思考 4096）。这次通过减少无效补读与收尾轮次降低了撞上限的机会，技能也已要求报告一次写完，但没有提高档位上限——提高上限会同时抬高每次请求的保守预留。如果真机复验仍出现「零文字 + 输出上限」，下一步应当拿真实样本校准 `PERSONA_REPORT_OUTPUT_TOKENS` 与思考预算，而不是继续加收尾轮次。
- `finalize` 节点仍使用带工具定义的流式请求（Koog 1.2.0 的流式接口没有 without-tools 变体）。模型若在收尾轮仍然只输出工具调用，本次运行就以「达到限制」结束，不会再追加第二次收尾，因此最多只多花一次请求。
