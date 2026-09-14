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

## 评审修复（09-review-1，2026-09-14）

本轮接受并修复全部四条评审意见，无不同意项。

1. **P1：确认追加期间停止或删除会话。** 确认协程纳入当前 job 管理，启动前登记任务；捕获会话 ID 和 generation，在额度提交、会话保存返回后检查取消及有效性。保存期间保持可停止状态，但持久化完成态不保存 busy。即使额度已经提交，停止或删除后也不会自动续跑或恢复被删除的会话。新增 decide/save 两个挂起点分别停止、删除的四项竞态测试，断言无新增模型调用。
2. **P2：设置修正后继续原分析。** daily、AUTH、BALANCE 卡片保留明确的“继续”入口，用户手动触发后沿用原 analysisId 与检查点，创建新的 run；每日额度仍在发送前重查。修改设置、返回对话均不自动调用模型。新增三项 ViewModel 恢复测试及覆盖三类卡片“进入设置 → 保存返回 → 手动继续”的设备界面测试。
3. **P2：释放明确未发送的预留。** 账本区分 reserved、in_flight、settled、not_sent、pending_verification；HTTP 开始前登记发送状态，当前进程能确认未进入传输的失败或取消将预留原子释放为 not_sent、费用为 0。已发送但 usage 不完整以及重启后无法确认的记录仍保守保留。重试建立新预留前清理旧请求标识，避免误释放上一已发送尝试。用量界面不把预留或明确未发送记录计为模型请求、图片或重试。新增 onStart 保存失败及发送前取消测试，确认执行器调用数为 0、额度释放且序列化恢复后不重新占用；真实 Room 设备测试补充释放后重开验证。
4. **P3：同步长期文档。** 原位修正用户文档中预算尚未接入的旧说明，以及 ADR 中默认 HTTP 工厂、默认传输行为、未包装重试的旧描述；同步说明 BudgetHttpFactory、Koog 单层重试、未发送释放和手动恢复路径。

本轮最新改动文件清单（仅列本轮修复涉及文件，前轮完整清单保留在上文）：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AiBudget.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/BudgetHttpClient.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCardsTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/db/AiBudgetPersistenceTest.kt`
- `docs/ai-assistant.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `.scratch/ai-assistant/cost-and-recovery.md`
- `.scratch/ai-assistant/reports/09-impl.md`

本轮最新验证结果：

- JDK 17，Android SDK 使用本机安装；全部 Gradle 验证使用 `--offline`。
- `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：通过，BUILD SUCCESSFUL；debug、release（含 R8/lintVital）均成功。核对 JUnit XML：1222 项测试，0 failures、0 errors、5 skipped（默认关闭的联网/真实校准测试）。本轮新增 9 项 JVM 回归测试全部通过。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiBudgetPersistenceTest,com.chasel.ng2n.ui.ai.AiBudgetCardsTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：Pixel_8 API 37 设备上 6/6 通过，0 失败、0 跳过。设备验证在构建结束后独立运行。
- `git diff --check`：通过。
- 本轮未发起联网或真实付费模型调用。上文披露的真实样本校准限制不变：额度档位、每日默认关闭、单请求输出上限及图片 token 预留仍未经真实样本校准，长回复链样本仍待验证。
- 未执行 git commit，未回退工作区原有改动。

## 第二轮评审修复（09-review-2，2026-09-14）

接受并修复本轮唯一的 P2 问题，无不同意项。`TopicAiViewModel` 保存独立的用量观察 Job，在 `discard()` 及 `onCleared()` 时取消。`AiSessions.forget()` 和 `discardAll()` 现有的 discard 路径因此会释放观察订阅，后续账本更新不再写入已删除会话。取消仅针对该订阅，不取消共享的应用级 taskScope；收起面板仍保留有效会话的观察和运行。独立用量账本的持久化、保留和收费规则不变。

新增两项生命周期回归测试，均显式注入共享长生命周期 taskScope：分别验证 discard 与 ViewModelStore 清理。每项反复创建、销毁三次，核对订阅数量增加后回落；收起不减少订阅；销毁后更新账本，已销毁 ViewModel 的 state 保持不变，同作用域中的其他会话继续收到更新，作用域仍有效；最后全部销毁后订阅数为 0。

本轮最新改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `.scratch/ai-assistant/reports/09-impl.md`

本轮最新验证结果：

- JDK 17；`./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：BUILD SUCCESSFUL。debug、完整 JVM 测试及 release（含 R8/lintVital）均通过。
- 核对 JUnit XML：1224 项测试，0 failures、0 errors、5 skipped；两项新增生命周期测试均通过。跳过项仍为默认关闭的联网/真实校准测试。
- `git diff --check`：通过。
- 本轮修改为订阅生命周期管理，使用可确定调度的 JVM 测试验证取消及共享作用域边界，未复跑设备测试；上一轮设备结果不作为本轮复测结果。
- 本轮未调用真实付费模型，未读取 Key；此前披露的真实样本及默认预算未经校准的限制不变。未执行 git commit，未回退工作区原有改动。
