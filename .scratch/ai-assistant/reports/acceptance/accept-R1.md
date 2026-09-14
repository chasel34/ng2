# 回归复验 R1（fix-DE 构建 + 票 11 个人分析）

环境：emulator-5554 / com.chasel.ng2.dev（含 fix-DE 的构建，未重装、未改代码、未 git 操作）。NGA 已登录 lemon43。
本轮共 16 次分析，今日用量由 ≈US$0.3548 增至 ≈US$0.4530。
**结束时环境已复原**：单次分析额度＝长楼与个人分析、每日上限＝US$3.00、DeepSeek Key 已恢复并用一次真实计费请求验证可用（备份 `ai-keys-R1.bak` 与上一轮 `ai-keys.bak` 逐字节相同；改 Key 前备份，验完 `am force-stop` 后 `run-as` 原样写回并 `cmp` 校验，未打印明文）。
`logcat -b crash` 全程无 `com.chasel` 记录。（crash buffer 里 08:11 那条 `UiAutomationService … already registered` 是我自己两个 `uiautomator dump` 撞车，不是 App。）

## 复验结论速览

| # | 项 | 结果 |
|---|---|---|
| 1 | 迭代上限（事实核查 / 批判性思考） | 通过 |
| 2 | 个人分析报告（票 11） | **未通过，仍拿不到报告，换了新的根因** |
| 3 | 短问答档首个请求 | 通过 |
| 4 | 快捷菜单 ↓↓ / Esc | 通过 |
| 5 | 设置页「网页」次数 | 通过 |
| 6 | API Key 无效文案 | 通过 |
| 7 | 生成阶段点停止 | 通过 |
| 8 | 崩溃 / 卡住 | 无 |

## 新问题

- [P1] 票 11 的个人报告在真实模型下仍然 100% 拿不到，报告全屏一次都没渲染出来。与上一轮不同，这次**不是迭代上限**（`logcat` 全程无 `Max iterations`），而是两条独立成因，任一条都足以让报告作废。

  **成因 A：长楼档输出上限仍是 4096（含思考 token），个人报告 JSON 写不完，而且被标成「回答生成中断」而不是「达到限制」。**
  复现步骤：用户 `tidebringer123`（36 条样本）资料页 →「AI 分析发言」；点「继续」再跑一次。2/2 复现。
  证据（`adb logcat`，本轮共 14 行）：
  ```
  W System.err: ERROR ai.koog.agents.core.agent.entity.SimpleAIAgentNodeImpl - Error executing node (name: sendResults): 回答达到长度上限，尚未完成
  W System.err: java.lang.IllegalStateException: 回答达到长度上限，尚未完成
  W System.err: 	at com.chasel.ng2n.data.ai.TopicAgentRuntime$runWith$graph$1$sendResults$2$1.invokeSuspend(TopicAgentRuntime.kt:193)
  ```
  `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt:193` 对 `finishReason in ("length","max_tokens")` 抛的是裸 `IllegalStateException`，不是 `AiRunLimitReached`，所以 `classifyAiFailure` 走 `outputStarted` 分支返回 `INTERRUPTED`，界面是红色「回答生成中断 / 已保留文字和 2 个来源」。而 `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt:31` 的 `"long" -> AiRunLimits(iterations = 48, toolCalls = 32)` 没有覆盖 `outputTokens`，仍取默认 4096；页脚「输出（含思考）」说明思考 token 也占这 4096。
  期望：`docs/ai-assistant.md:55`「达到工具执行次数上限、连续重复读取同一资料达到阈值或达到循环上限时，本轮标为『达到限制』并说明具体上限」——输出长度上限属于同一类执行上限，应归 `LIMIT` 并写明是输出上限；票 11 的报告是长结构化 JSON，长楼/个人档的 `outputTokens` 需要按报告体量重新取值。
  证据截图 `r1-persona-interrupt.png`。

  **成因 B：`parsePersonaReport` 的来源校验与个人 skill 的「补读主楼」要求互相排斥，报告必然被拒。**
  复现步骤：用户 `多捞哦小伙子`（23 条样本，全部为只有标题的主题）资料页 →「AI 分析发言」→ 点「继续」→ 再用英文显式要求「只引用 s1..s23、processedSourceIds 列全 s1..s23」。三轮全部以「报告结构尚未完整或来源校验未通过，请继续。已有草稿已保留。」结束。
  证据（落库 `ai_message.payload.text`，3043 / 3121 字，JSON 结构完整、`overview` / `interests` / `positions` / `judgment` / `timeline` / `boundary` 齐全）：
  ```
  "processedSourceIds":["s1",…,"s23"]
  evidence/counter 中出现 "s25" "s27" "s29" "s30"
  ```
  `ai_source` 表显示 s24–s30 就是模型按 skill 要求用 `read_floor` 补读的同一批主楼（例如 s24 tid 47547041 与样本 s1 同 tid）。而 `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt:25` 要求 `processedSourceIds` 的序号落在 `1..sampleCount`，`:27-29` 又要求 `(evidence + counter)` 全部 ∈ `processedSourceIds`。补读来源的序号天然大于 `sampleCount`，于是**只要模型照 skill 说的去补读并引用，报告就一定作废**；样本是只有标题的主题时，不补读又给不出依据。
  界面表现：报告全屏不出现，聊天里只有灰色占位「报告结构尚未完整或来源校验未通过，请继续。已有草稿已保留。」+「已读取 23 条；本轮尚无已确认处理完成的样本，23 条待确认处理。」，状态行「个人报告尚未覆盖全部样本，可继续」。用户看不到任何报告内容，钱照付（三轮合计 ≈US$0.041）。
  期望：票 11「报告全屏展示：概述段、倾向卡（数量胶囊、信号条、查看依据抽屉、相反表述 N 抽屉）、判断侧重、稳定性与变化时间线、末尾边界声明」。修法方向二选一：补读同一 tid/pid 的资料复用原样本的 sourceId 而不是新开一个；或者把补读来源并入「已处理样本」集合并放开 `1..sampleCount` 这条序号限制。
  证据截图 `r1-persona-rawjson.png`，落库文本见 `r1b.db` / `r1c.db` 的 `ai_message` 表（rowid 69–71）。

  **因此以下票 11 项目本轮仍然未验证**：倾向卡本体、数量胶囊、信号条三档、「查看依据」抽屉、「相反表述 N」抽屉、代表性发言整行点击重读原楼层、「稳定性与变化」时间线、报告顶部「已处理 N 条 · 未处理 M 条」。末尾边界声明只在零样本那种骨架回答里见到过。

- [P3] 同一屏出现两处「回答生成中断」：来源列表下方的粉色状态卡（带「已保留文字和 N 个来源…」正文）与估算费用卡下方的红色状态行，文案重复。证据 `r1-persona-interrupt.png`。

- [P3] 报告校验失败时状态卡仍写「已保留文字和 N 个来源」，但界面上一个字的回答都看不到——落库的 `text` 是那份被拒的 JSON，界面用灰色占位替换掉了。上一轮修的是「text 为空时改口」，这里是「text 非空但不展示」，用户读到的仍是不实的说明。证据 `r1-persona-interrupt.png`（36 条样本那轮）。

- [P3] 个人分析里普通 Markdown 追问的回答下方也会挂一句「已读取 23 条；本轮尚无已确认处理完成的样本，23 条待确认处理。已有草稿保留，可继续。」。复现：个人对话里问一个与报告无关的问题（本轮问的是「列出 23 条样本的标题与日期」）。这句只对报告轮有意义，挂在普通追问下面容易被当成这条回答失败了。证据 `r1-stopped.png`。

## 未修复项（accept-FG 已记，本轮再次复现，不重复计入问题数）

- 个人分析样本为 0 条时仍发起模型请求并标「已完成」：`scugenda`（发帖 16）、`斩苍穹`（发帖 500）各一次，两次的 TOPICS / REPLIES 第 1 页都读取失败，样本 0 条，仍然请求模型并产出一份只说「证据不足」的回答，费用 US$0.0017 / US$0.0014，状态「已完成」。

## 逐项结论（通过项）

1. **迭代上限 —— 通过**。主题 `英国也开始热闹了？苏格兰威尔士北爱都想独立`（4 页 61 回复）：顶栏概览 → `事实核查` 快捷操作 → `批判性思考` 快捷操作，三轮全部「已完成」，没有「回答生成中断」，没有空文字。事实核查该轮「5 次工具调用，1 次失败」、14 个来源、US$0.0105；批判性思考该轮 4 次模型请求、19 个来源、US$0.0088，并自行做了联网核对（页脚出现 `yougov.com` 已读网页标签）。`logcat` 全程无 `Max iterations limit`。上一轮那条「事实核查 2/2 必中断且不可恢复」的 P1 已修复。

2. **快捷菜单键盘 —— 通过**。回答完成后点输入框左侧 `/` 打开菜单，按 ↓ 高亮移到「批判性思考」，再按 ↓ 移到「梳理分歧」，全程**只有一处高亮且在菜单内**，浮层背后的聊天列表不再出现系统焦点框；按 Esc 菜单关闭。证据 `r1-slash.png`、`r1-slash-d1.png`、`r1-slash-d2.png`、`r1-slash-esc.png`。（截图左侧那条深色竖条是模拟器的浮动输入法，不是 App 元素。）

3. **短问答档 —— 通过**。设置改「短问答」后对主题 `预算1k5，打游戏看动画看电影听asmr，头戴有无推荐` 发起顶栏概览：首个请求直接发出，没有弹额度确认卡，卡片上「估算费用 ≈ US$0.0066 / 本次剩余额度 US$0.0135」，整轮跑完实际 US$0.0020、6 个来源、「已完成」。上一轮「第一次请求就超 0.02」已修复。验完已改回「长楼与个人分析」。

4. **设置页「网页」次数 —— 通过**。设置页「今日用量 · 明细」显示「网页 14 次」（同屏：模型请求 150 次 / 输入 1221483 tokens / 缓存命中输入 1038080 tokens / 输出 76076 tokens / 图片输入 41 张次）。与落库预算账本对拍一致：`ai_budget` 的 187 条 request 里 `web` 字段求和正好是 14（非零项为 1,2,2,2,2,1,2,2）。对话页脚同口径按轮统计，事实核查那轮 7 次、批判性思考那轮 4 次，与账本逐请求数值吻合。恒为 0 的问题已修复。

5. **API Key 无效文案 —— 通过**。把 Key 改成 `sk-invalid` 后发起概览：状态卡标题为票面文案「API Key 无效或余额不足」，按钮「前往 AI 设置」，「估算费用 ≈ US$0.0000」，未产生费用。证据 `r1-invalidkey.png`。随后按 accept-DE 第 7 行的做法恢复（`am force-stop` → `run-as` 写回备份 → `cmp` 校验一致 → 重启 App），并用一次真实概览确认可用（US$0.0019、10 个来源、「已完成」）。

6. **停止 —— 通过**。在个人分析对话里发起一个长输出追问，等「✓ 已思考 5 秒」出现、回答正在流式输出时点「停止」：状态立刻变为「已停止 · 未完成」，已生成的四条列表项完整保留（第 4 条在句中断开，无裸 Markdown 标记、无碎片），来源标签正常渲染，出现「继续」按钮，`logcat -b crash` 为空。残留文字观感正常。证据 `r1-stopped.png`。

7. **崩溃与卡住 —— 无**。16 次分析全程无 App 崩溃、无 ANR、无超过 2 分钟无变化的卡住。Koog 的 INFO/ERROR 仍全量打到 `System.err`（含技能路径、tid/pid、完整工具参数），未见 Key 或响应正文外泄。

## 阻塞说明

- 票 11 报告全屏的全部卡片级验收仍被挡住，本轮 6 次个人分析（4 个用户）一次都没拿到有效报告。成因 B 是结构性的：只要样本里有「只有标题的主题」，模型照 skill 补读就必然触发来源校验失败。建议先修 `PersonaReport.kt` 的来源集合与补读 sourceId 复用，再把长楼/个人档的 `outputTokens` 调到能装下整份报告，然后重跑个人入口。
- 「另 N 张未读取」的图片预览变体、「已删除或不可访问」的来源预览变体、「外部搜索暂时不可用」通知条、快捷菜单前缀筛选（无法输入中文）四项本轮同样没有可触发的样本，仍未验证。
