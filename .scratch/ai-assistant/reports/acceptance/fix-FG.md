# 验收组 F + G 修复说明

验证命令（JDK 17，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE，未使用模拟器或真机）：

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，1303 个离线用例、0 失败（修复前 1298）。另单独执行 `:app:compileDebugAndroidTestKotlin` 确认 UI 用例仍可编译，未在设备上运行。未执行 git commit，未读取 `.env.local`。

---

## P1 个人分析因迭代上限中断（fix-DE 已修，本轮确认入口与补测）

**确认**：个人入口没有独立的运行参数。`TopicAiViewModel.openPersona` 走 `openEntry` → `send`，与主题、列表、回复链共用同一条运行路径；该路径在发起前执行 `budgets.limits()`（`AiBudgetStore.limits()` → `runLimitsFor(当前额度档位)`），把 `AiRunLimits` 写进 `AiRunBudget.limits`，`TopicAgentRuntime` 的 `maxAgentIterations` 与 `DeepSeekParams.maxTokens` 都从这里取值。验收时的档位是「长楼与个人分析」，对应 48 步循环、32 次工具、4096 输出；默认档为 40 / 24 / 4096。达到上限时 `AiRunLimitReached` → `AiFailure.LIMIT`，保留已生成文字与来源并出现「继续」，与其他入口一致。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt`：`PERSONA_HISTORY_PAGE` 上方的注释仍写着「Koog 循环上限只容得下两轮工具调用」，那是旧的 8 步前提，已改为说明续读一次取回剩余样本、首轮在运行上限内完成。仅注释，未改行为。

**验证方式**：`app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/AgentRunLimitsTest.kt` 新增
`personaFirstRoundFinishesSkillHistoryAndFloorReadsWithinTheAllowanceLimits`：
用真实释放的内置技能目录与真实 Koog 循环，按个人首轮的实际形状跑一遍——
`__read_file__ persona-evidence/SKILL.md` → `read_user_history` → 三次 `read_floor`（补读主楼）→ 收尾报告，
共 6 次模型请求、5 次工具执行。断言拿到完整报告文本、工具行顺序正确、
`2 × 请求数 > 8`（旧上限必然打断）且不超过个人档与默认档的循环上限。

---

## P2 样本为 0 条仍请求模型并标「已完成」

**根因**：个人入口准备完历史后没有检查样本数。`buildPersonaContext` 在两路分页全失败时照样返回一份 0 条样本的 `TopicContext`，运行继续走到模型请求，于是花钱换回一句「证据不足」，最后按正常轮次标「已完成」。分页失败信息只写进了 `note` 的自然语言里，界面没有可判定的字段。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`：`TopicContext` 增加 `missing: List<String>`，记录准备阶段确实失败的读取。
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt`：`buildPersonaContext` 把 `missing` 参数同时写进该字段，不再只拼进 `note`。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：入口范围恢复检查之后、`modelContext` 赋值之前新增判据——个人对话且 `sampleCount == 0` 时直接结束本轮：`missing` 非空为「历史读取失败」+ `READ` 卡，`missing` 为空为「没有可分析的发言」+ 新的 `EMPTY` 卡，两者都标 `incomplete`，不写 `modelContext`（所以重试会重新读一遍历史），不请求模型、不预留额度。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt`：新增 `EMPTY` 卡文案「本次范围内没有可分析的发言，没有请求模型，也没有产生费用。」，配色用普通表面色而非危险色。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：底部那颗按钮在 `READ` / `EMPTY` 卡下显示为「重试」而不是「继续」——这两种情况没有可继续的草稿，点下去就是重新准备范围。

**验证方式**：`app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt` 新增
`personalEntryWithoutAnySampleNeverRequestsTheModelAndStaysRetryable`：模型实现直接 `error()`，
两种客户端各跑一次——空 `__T` 得到 `EMPTY` / 「没有可分析的发言」，错误信封得到 `READ` / 「历史读取失败」；
两次都断言轮次 `incomplete`、`sampleCount == 0`，且预算账本里一条请求记录都没有（没有计费）。

---

## P3 回复链范围卡图片计数把主楼图算进总数

**根因**：`buildChainContext` 选图时用 `sources.filter { it.pid in chainIds }`（已排除主楼），而 `imageReadingRow` 的 `total` 统计全部 sources，主楼图因此进了分母，出现「0 / 1 张 · 已带入 无」且没有任何解释。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/core/ai/EntryContext.kt`。选图与计数改用同一份 `chainSources`；`imageReadingRow` 增加 `extra` 明细参数，回复链在主楼确有图时补一行「主楼图片 N 张，不参与首轮选取」。总数与「已带入」从此同一口径，范围外的图片单独说明。

**验证方式**：`EntryContextTest.chainIncludesOnlyRootAndCapturedNodesAcrossPagesAndChoosesOneChainImage` 补断言「1 / 2 张」与主楼图明细；新增 `chainImageTotalStaysWithinTheChainWhenOnlyTheRootHasImages` 覆盖只有主楼有图时总数为 0 张。

---

## P3 范围卡底部「已读 N 条」是会话累计来源数

**根因**：卡片用 `context.sources.size`，而 `sources` 会随 agent 续读增长（`AiStreamUpdate.Sources` 每次覆盖），于是同一张卡里上半部分是首轮范围、底部是会话累计，追问两轮后从 76 涨到 137。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`：新增 `prepared: Int = sources.size`，在构造时固定为本次准备范围的条数；`copy(sources = …)` 不会重算，续读不影响它。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`：底部改用 `context.prepared`。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：入口元数据保存 `preparedSources`，历史恢复时读回（旧记录回落到来源数，与原行为一致）。

**验证方式**：`TopicContextTest.preparedRangeCountIsFixedAtPreparationAndDoesNotGrowWithLaterReads`，断言追加来源后 `prepared` 不变。

---

## P3 回复链范围卡「主楼正文 1 楼」与「上游 1 楼」同字不同义

**根因**：主楼行的「1 楼」是计数，相邻角色行的「1 楼」是楼层号，同一张卡上冲突；本例主楼真实楼层是 0 楼。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/core/ai/EntryContext.kt`，主楼行改为状态词「已计入 / 未计入」，展开明细里给出真实楼层号「楼层：0 楼」。

**验证方式**：`EntryContextTest` 同一用例断言 `amount == "已计入"`、明细为 `楼层 to "0 楼"`。

---

## P3 历史页分类标签顺序与文档不一致

**根因**：标签顺序写死为「全部 / 主题 / 楼层 / 回复链 / 个人 / 列表」，「列表」排最后且默认在屏幕外。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiHistoryScreen.kt` 改为「全部 / 列表 / 主题 / 楼层 / 回复链 / 个人」，与 docs 的「列表、主题、楼层、回复链」同序并把「个人」补在末尾；`docs/ai-assistant.md` 同步写成完整顺序。

**验证方式**：仅顺序常量，无断言可加；由既有历史页 androidTest 保证渲染（只编译，未在设备运行）。

---

## P3 停止后只剩裸 Markdown 标记按字面渲染

**根因**：停止发生在「生成回答」刚开始时，保留下来的文字可能只有一行 `##`。`AiMarkdown` 的标题判据要求 `#` 后有空格，`##` 于是按普通文本原样显示，观感像渲染出错。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/Citations.kt`：新增 `trimIncompleteMarkdown`，裁掉末尾只有标记、没有正文的行（`#`、列表符号、`>`、`|`、`` ` ``、`*`、`_` 及空行）；围栏未闭合时整段原样返回，因为那些标记是代码内容。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：用户停止与运行结束的收尾分支各调用一次，落库文字与界面文字保持一致。副作用是状态卡的「本轮没有生成文字」判据也随之准确——只产出 `##` 的那一轮不再宣称已保留文字。

**验证方式**：`CitationsTest.stoppedAnswersDropDanglingMarkupButKeepEveryLineThatHasText`，覆盖尾部 `##`、混合残缺标记、全是标记、正常标题与列表、未闭合围栏五种输入。

---

## 文档

`docs/ai-assistant.md`：范围卡条数为本次准备范围；回复链主楼行用状态词与真实楼层号、图片总数只统计链内且主楼图单独标注；历史分类顺序补全；个人分析零样本按读取失败或无样本处理且不请求模型；停止保留文字去掉末尾残缺标记。

## 未做的事

- 未执行 git commit，未使用模拟器或真机：状态卡文案、范围卡显示、历史标签首屏可见性、停止后的实际渲染都未在设备上复验。
- 个人报告全屏的卡片级验收（倾向卡、数量胶囊、信号条、两个抽屉、时间线）仍需真机重跑一次个人分析，本轮只保证首轮形状能在运行上限内跑完。
- 验收里提到的 P3「历史条目标题与副标题重复主题名」属于 fix-DE 的范围，本轮未再改动。
