# 09 — 开销控制、用量记账与异常状态卡

**What to build:** 一次分析（首次概览或每次追问）内所有模型调用、思考、图片、整理与重试共用单次额度。每次模型请求发送前估算并原子预留输入、输出上限与重试开销，多对话预留不重复花同一份额度；收到 usage 后按带版本与核实日期的价格表记账，未知缓存命中按未命中、时段不确定按峰时价（工作日北京时间 9–12、14–18）；已发出但未收到完整 usage 的请求标「待核实」并保留保守预留，跨重启与跨日不清零。达到单次额度时对话内出现确认卡「已达到本次额度，要继续吗？」，两个单选（追加一段额度并说明金额 / 到此为止），必须点确定；关闭折叠为「已达到本次额度 · 待确认」可重开；确认后显示绿色结果胶囊。达到每日额度显示状态卡并要求修改额度，普通继续不能绕过。其余状态卡：回答生成中断（保留文字与 N 条来源、用量不完整）、请求结果不明（不自动重试）、API Key 无效或余额不足（去设置）。模型层自动重试只配置 Koog 的 RetryingLLMClient，App 不另写重试；每次尝试经 EventHandler 单独记账。同一对话只运行一个任务，其他对话排队显示「等待执行 · 前面还有 N 个对话」，排队不调用模型。页脚显示估算费用，展开为模型请求 / 输入 / 输出 / 网页 / 图片明细；设置页「今日用量」显示金额、每日占比、明细与待核实说明。

**Blocked by:** 03 AI 设置页与 Key 存储、05 论坛读取工具与工具调用行、07 对话持久化、历史页与中断恢复

**Status:** implemented

- [x] 并发单测：两个对话同时预留不会共同超出剩余额度；usage 重放不重复记账
- [x] 重启单测：待核实与进行中预留在重启后保留；删除对话不重置当日用量
- [x] 达到额度：已有结果保存，确认卡出现，追加后运行继续并计入本对话与当日；到此为止后不再发请求
- [x] 错误分类单测：认证 / 余额 / 参数 / 限流 / 服务错误各映射到正确状态卡与是否重试；RetryingLLMClient 配置不对已开始输出的请求重试
- [x] 用户停止后迟到的响应不会重新启动运行或改写状态
- [ ] 用真实 DeepSeek 分别跑普通主题、多图楼、长回复链，记录费用与 token 到 Comments，作为默认额度取值依据
- [x] 开销文档更新价格表版本、默认额度取值与验证结果

## Comments

2026-09-14 实现记录

已实现单次共享额度、每日原子预留、独立持久化用量账本、重复 usage 去重、跨日/重启待核实保留、会话删除不清零、确认追加与到此为止、排队执行、错误状态卡、页脚和今日用量明细。确认卡按 Main/Components 设计稿实现折叠重开、明确单选后确认、查看用量及绿色结果胶囊；设置沿用 Settings 设计稿。暂停额度单独保存，不伪装成进程中断；停止后迟到响应不能重启或覆盖状态。

Koog EventHandler 在发送前预留、在完整 usage 后结算；HTTP 重试通过同一运行预算独立记账。自动重试仅使用 RetryingLLMClient，允许明确 429/500/503 且尚未输出时重试一次，保留 Retry-After；超过 10 秒等待则暂停。认证、余额、参数、未知结果和已输出流不自动重放；额外防止 Koog 对空流异常的无条件重试。HTTP 底层透明重试及重定向关闭，默认会输出服务端正文的日志关闭。固定 Koog JVM 构造 ABI 的用途和升级验证要求已补入 ADR。

价格版本 deepseek-flash-2026-09-14，USD，峰时每百万 token：缓存命中输入 0.006、未命中输入 0.30、输出 1.20；谷时一半。应用采用峰时保守估算，保留价格快照，思考计入输出且不重复相加。暂定额度 0.02 / 0.05 / 0.10 / 0.20 USD，每日默认关闭，单请求输出 4096 token，图片预留 65536 token/张；这些值未经真实样本校准。详情见 cost-and-recovery.md。

真实验证与限制：使用现有 NGA_INTEGRATION 游客只读客户端从版块 7 列表选样，未执行论坛写入。选用普通主题 tid=47549429、多图楼 tid=47344551，受控诊断另选普通主题 tid=47552184。三次逻辑模型调用均未收到可用 usage，输入 token、输出 token、费用均为未知，不记为 0，也不认定免费；最终错误来自模型 HTTP/SSE 适配，属于请求结果不明。长回复链未取得可用于校准的样本。依用户补充“若联网受阻则用离线验证完成”的授权，已停止继续真实调用，以离线验证完成本票，真实校准验收项保留未勾选并说明原因。Key 只在本机读取，未写入代码、报告、票或日志；09-calibration.json 只含样本坐标与用量状态。

改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/BudgetHttpClient.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/KnownResultClient.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/DeepSeekClientFactory.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/db/AiConversationDao.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/db/Ng2nDatabase.kt`
- `app/schemas/com.chasel.ng2n.data.db.Ng2nDatabase/4.json`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSessions.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposer.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/AiSettingsViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/AiSettingsScreen.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/AiBudgetTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/AiExecutionQueueTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/MemoryAiBudgetRepository.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/AiRetryTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/net/NgaIntegrationSmokeTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/net/AiBudgetCalibrationTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/db/AiBudgetPersistenceTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCardsTest.kt`
- `.scratch/ai-assistant/cost-and-recovery.md`
- `docs/ai-assistant.md`
- `docs/testing.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `.scratch/ai-assistant/reports/09-calibration.json`
- `.scratch/ai-assistant/issues/09-budget-usage-and-status-cards.md`
- `.scratch/ai-assistant/reports/09-impl.md`
- `.scratch/ai-assistant/reports/09-ui/budget-dark.png`
- `.scratch/ai-assistant/reports/09-ui/budget-light.png`
- `.scratch/ai-assistant/reports/09-ui/daily-limit.png`
- `.scratch/ai-assistant/reports/09-ui/usage-pending.png`

验证结果：

- JDK 17；`./gradlew --offline :app:assembleDebug`：通过。
- `./gradlew --offline :app:testDebugUnitTest`：通过，1213 项测试，0 失败、5 项联网/真实校准测试按默认开关跳过。覆盖并发预留、usage 重放去重、跨日保留、单次追加后续跑、到此为止不再发请求、迟到响应、保存失败不虚报成功、Koog 空流/已输出响应不重试，以及真实 HTTP 重试各自预留、SSE 独立 usage 结束块的缓存记账。
- `./gradlew --offline :app:assembleRelease`：通过，包括 lintVital 与 R8。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiBudgetPersistenceTest,com.chasel.ng2n.ui.ai.AiBudgetCardsTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：5/5 通过，0 跳过；验证 3→4 迁移、真实 Room 原子预留、重开与删除保留、确认卡、待核实用量明细和原有设置交互。
- 设备与 R8 并行时一次 PixelCopy 截图超时；构建结束后单独完整复测上述设备项，5/5 通过。浅色/深色确认卡、每日额度和待核实明细截图已保存至 `reports/09-ui/`，人工检查无裁切、遮挡，使用项目配色。
- 真实读取/模型校准命令：`NGA_INTEGRATION=1 AI_BUDGET_CALIBRATION=1 ./gradlew --offline --no-configuration-cache :app:testDebugUnitTest --tests 'com.chasel.ng2n.data.net.AiBudgetCalibrationTest'`。测试入口完成，NGA 选样可读；模型调用结果不明、无可用 usage，不算真实费用校准通过。按本轮用户授权采用离线完成，默认值和长回复链真实样本校准保留待验证。
- 已检查生成的测试 XML、日志和校准报告不包含真实 Key；`git diff --check` 通过。未执行 git commit，未回退工作区原有改动。
