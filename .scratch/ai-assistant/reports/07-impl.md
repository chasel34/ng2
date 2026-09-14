# 07 — 对话持久化、历史页与中断恢复实现报告

2026-09-13：实现完成，Status 改为 implemented；未执行 git commit，保留任务开始前工作区已有改动。

### 实现内容

- Room 2→3 自动迁移新增会话、消息、阅读范围、引用坐标、运行、模型工作上下文和独立用量记录。来源表仅保存坐标、作者、时间、读取时间及 SHA-256，没有论坛正文快照；schema JSON 已生成到仓库目录。
- Koog Persistence 与 ChatMemory 经 Room provider 接入，框架 runId 映射到独立 App 会话/运行 ID。每次继续新建 run，沿恢复关系找到最近检查点，保留完整协议、成功工具结果、图片工作资料与回复链游标；其他对话不混入。续聊系统规则要求引用原句前重新读取可能已被清理的内容。
- 应用级会话管理支持收起后继续运行、历史直接全屏、同主题多条对话并存。历史按六类入口筛选并显示计数，提供本地日期分组、两字起搜索与高亮、空态、来源/时间/范围/状态、长按及更多菜单、删除 Snackbar 撤销。面板历史按钮、主题数量条和设置历史入口均已连接。
- 删除标记支持撤销，确认后级联清除消息、引用、运行、检查点及 ChatMemory，拒绝迟到写入重建对话，同时清除对应内存会话；用量表保留。设置提供历史条数和确认清空全部。模型请求先落盘，未知费用不显示为零消费。
- 首次访问恢复旧运行的中断状态，打开历史不发送模型请求；状态卡显示中断位置，用户继续才恢复。存储失败停止后续请求，保留内存草稿，显示「对话未能保存」与「重试保存」，保存成功也不会自动请求模型。
- 按 `design/ai-assistant/History.dc.html`、`Main.dc.html`、`Settings.dc.html` 落实对应界面，并核对浅色、深色、搜索空态及全屏中断状态截图。

### 验证结果

- JDK 17，Android SDK 已配置，全部 Gradle 验证使用 `--offline`。
- `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：最终 **BUILD SUCCESSFUL**。1,177 项单测，0 失败、0 错误、4 项按既有条件跳过。debug 与 R8 release APK 均生成。
- `./gradlew --offline :app:connectedDebugAndroidTest`，限定 `AiPersistenceTest`、`Ng2nMigrationTest`、`AiHistoryScreenTest`、`TopicAiSheetTest`、`AiSettingsScreenTest`：Pixel_8 / Android 17 / arm64 模拟器 **12/12 通过**。包括旧版本迁移、书签和新表重开保留、删除撤销及用量保留、SQLite 写入失败注入、未保存状态卡、历史搜索菜单与全屏中断提示、面板收起和设置交互。
- 独立 `AiProcessRecoveryTest`：工具和部分回答提交后，主机执行 `am force-stop com.chasel.ng2.dev`；seed 阶段按预期报告进程终止。新进程 verify 阶段 **OK (1 test)**：只打开记录仍为原有 2 条请求，手动恢复后共 3 条请求；成功工具不重读，原问题不重复，草稿和中断位置保留。使用专用数据库和离线模型/论坛夹具，没有调用真实付费服务。
- `AiSchemaLifecycleTest` 验证对话子表级联、用量无对话外键、历史无账号分区、引用表无原文列；`AgentPersistenceTest` 验证检查点续跑、跨运行 ChatMemory、会话隔离、连续中断追溯及写入失败后阻止下一请求。
- `git diff --check`：通过。最初截图接口在模拟器上超时，已改为等待绘制后系统截图，最终界面测试和截图检查通过。

### 逐项验收与环境边界

| 票内验收项 | 实现与验证 |
| --- | --- |
| 迁移保留书签与新表、schema JSON | 已实现，模拟器迁移与重开测试通过，schema JSON 已生成 |
| 真机杀进程、显示中断位置、手动检查点继续、不重读成功工具 | 已实现；模拟器实际进程终止、Room 重启和 UI 测试通过；没有连接真机，真机项未勾选 |
| 真机收起/展开同一会话、同主题重新发起两条并存 | 已实现；ViewModel 单测和模拟器面板测试通过；真机项未勾选 |
| 切换账号历史一致 | 历史表与查询无账号分区、会话管理不按账号隔离，schema 单测通过；未执行真实账号切换，人工验收项未勾选 |
| 删除撤销、检查点和 ChatMemory 清除、用量保留 | 已实现；schema 生命周期单测、真实 Room 删除/撤销/级联及 UI 撤销测试通过 |
| 写入失败状态卡、禁止后续请求、保留内存草稿 | 已实现；SQLite 触发器注入、运行层/VM 单测和 Compose 状态卡测试通过 |
| 存储文档与生命周期 | 已同步 `docs/storage.md`、功能说明、测试说明与技术方案 |

真实账号和真机验收未冒充为已通过。金额计价、额度执行和真实模型质量联调属于后续对应能力，本次独立请求记录以待核实状态保留。

### 改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/topic/TopicRepository.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/db/Ng2nDatabase.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/db/AiConversationDao.kt`
- `app/schemas/com.chasel.ng2n.data.db.Ng2nDatabase/3.json`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiConversationStore.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/RoomAgentPersistence.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSessions.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiHistoryScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicDeps.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/AiSettingsScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/SettingsEntries.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/AgentPersistenceTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/MemoryAiConversationStore.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/AiSchemaLifecycleTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/AiHistoryTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/db/AiPersistenceTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/db/AiProcessRecoveryTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiHistoryScreenTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheetTest.kt`
- `docs/storage.md`
- `docs/ai-assistant.md`
- `docs/testing.md`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/issues/07-persistence-history-and-interruption.md`
- `.scratch/ai-assistant/reports/07-impl.md`

### 验证证据

证据目录：`.scratch/ai-assistant/reports/07-artifacts/`。

- `builds-and-unit.log`：最终 debug、release 与完整单测结果。
- `unit-summary.json`：单测数量与失败/跳过汇总。
- `connected-and-release.log`、`connected-results.xml`：12 项设备测试与 release 构建结果。
- `process-test-build.log`、`process-seed.log`、`process-verify.log`：独立进程终止与恢复验证。
- `ai-history-light.png`、`ai-history-dark.png`、`ai-history-empty.png`、`ai-history-interrupted.png`：最终界面截图。


## 评审修复：07-review-1

本轮接受两条 P1 评审意见，均已修复，无不同意项。

### 修复说明

1. 删除 ViewModel 重复维护的历史恢复来源。每次继续从当前最近的未完成 `run.id` 创建新运行，保留 C→B→A 的恢复关系。新增经过实际 ViewModel 加载历史、继续、再次断线或手动停止、再继续的两项测试，断言 C 指向 B、读取 B 的检查点和成功工具结果，并保留 B 的草稿。
2. 恢复工具结果发送节点时，根据检查点 prompt 中的 `read_image` 调用参数和本批成功结果的图片标识，从运行链上的持久化 `ToolReplay.images` 取回图片。复用现有工作资料格式，无需重新执行成功工具或新增数据库迁移；依据恢复 prompt 中实际存在的图片附件去重。必要图片资料缺失时停止请求，不向模型发送缺图的成功状态。新增同一工具会话重试、新建工具会话恢复两项测试，验证非初始图片在恢复请求及下一次工具循环中恰好出现一次、图片读取仅一次，且恢复请求前不重读论坛页面。
3. 同步存储文档，说明连续中断的运行链、图片重建与缺失处理。

### 本轮最新改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/AgentPersistenceTest.kt`
- `docs/storage.md`
- `.scratch/ai-assistant/reports/07-impl.md`
- `.scratch/ai-assistant/reports/07-artifacts/review-1/`：本轮回归前后日志、完整构建与单测日志、单测汇总及相关测试 XML、设备验证证据。

### 本轮验证

- 新增四项回归测试在修复前全部失败；修复后 `AgentPersistenceTest`、`TopicAiViewModelTest`、`TopicAgentRuntimeTest` 合计 21/21 通过。
- `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：**BUILD SUCCESSFUL**；1,181 项单测，0 失败、0 错误、4 跳过，Debug 与 Release 构建通过。
- 首轮设备命令误将设置测试写为 `ui.ai.AiSettingsScreenTest`，导致该类加载失败，其余 11 项通过；已改为实际包名 `ui.settings.AiSettingsScreenTest` 后重跑全部所选测试，初始错误日志另存为 `connected-invalid-selector.log`。
- 最终 `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiPersistenceTest,com.chasel.ng2n.data.db.Ng2nMigrationTest,com.chasel.ng2n.ui.ai.AiHistoryScreenTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：**BUILD SUCCESSFUL**，Pixel_8 / Android 17 模拟器 **12/12 通过**，0 失败、0 错误、0 跳过。
- `git diff --check`：通过。未执行 git commit。
- 本轮媒体恢复通过真实 Koog 图与持久化 provider 的离线回归测试验证，同会话和新会话均覆盖；未另行重跑主机杀进程双阶段测试、真机或真实账号切换，不将前轮证据计作本轮结果。
- 本轮证据目录：`.scratch/ai-assistant/reports/07-artifacts/review-1/`。


## 评审修复：07-review-2

接受本轮 P2 意见，已修复，无不同意项。

### 修复说明

- `clear()` 记录本次新增的内存删除标记；事务异常（包括取消）时撤回这些标记，保留此前已经存在的删除标记，继续抛出原异常。
- 保存、单条删除、撤销、确认删除与清空统一使用 store 的互斥锁。清空执行和失败回滚期间，保存无法穿插检查删除标记，成功清空后仍阻止迟到保存重建对话。
- 新增真实 Room 故障注入测试：保存草稿、运行、检查点、ChatMemory 和用量，注入 DELETE 失败，确认清空失败后数据仍在；移除故障后同一个 store 能保存原对话。同时验证此前已删除的对话仍不能重建，以及随后清空成功会清除工作资料、保留用量并阻止迟到保存。
- 同步存储文档中的互斥与失败回滚行为；无数据库结构、依赖或界面布局变更。

### 本轮最新改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiConversationStore.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/db/AiPersistenceTest.kt`
- `docs/storage.md`
- `.scratch/ai-assistant/reports/07-impl.md`
- `.scratch/ai-assistant/reports/07-artifacts/review-2/`：构建、单测和设备测试证据。

### 本轮验证

- `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：**BUILD SUCCESSFUL**。1,181 项单测，0 失败、0 错误、4 跳过；Debug 和 Release 构建通过。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiPersistenceTest,com.chasel.ng2n.data.db.Ng2nMigrationTest,com.chasel.ng2n.ui.ai.AiHistoryScreenTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：**BUILD SUCCESSFUL**；Pixel_8 / Android 17 模拟器 **13/13 通过**，包括新增清空失败恢复测试，0 失败、0 错误、0 跳过。
- `git diff --check`：通过。未执行 git commit。
- 本轮未另行重跑杀进程双阶段、真机或真实账号切换测试；此前人工验收边界不变。取消路径由同一异常回滚分支处理，本轮故障注入直接验证的是 DELETE 失败路径。
- 本轮证据：`.scratch/ai-assistant/reports/07-artifacts/review-2/` 中的 `builds-and-unit.log`、`unit-summary.json`、`connected.log`、`connected-results.xml`。
