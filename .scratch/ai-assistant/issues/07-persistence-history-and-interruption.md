# 07 — 对话持久化、历史页与中断恢复

**What to build:** 对话在本机永久保存并全账号共享。Room 新增对话、消息、阅读范围、引用坐标（tid/pid、作者、时间、读取时间、内容哈希）与运行状态表，按 ADR-0005 提高版本并写迁移；不保存论坛原文快照。Koog Persistence 检查点与 ChatMemory 通过 provider 写入 Room，随对话删除而删除。新增「AI 对话」历史页：分类筛选（全部 / 主题 / 楼层 / 回复链 / 个人 / 列表）带计数，今天 / 昨天 / 更早分组，搜索标题、主题或用户并高亮命中，≥2 字无结果时显示空态；条目显示来源、时间、阅读范围摘要与状态徽标（生成中 / 已达额度 / 已中断 / 来源已删除）；长按或更多菜单提供继续聊天、打开来源主题、删除对话，删除后 Snackbar 可撤销并说明用量记录保留。从历史进入直接全屏；面板标题栏的历史按钮打开此页。主题详情顶部出现「本主题有 N 条 AI 对话」入口条。临时收起再展开继续当前对话；从内容入口重新发起则新建对话，不自动恢复旧对话。应用进程在后台被结束后，下次打开该对话显示「上次运行已中断」状态卡说明停在哪一步，用户点继续才从最近检查点恢复，不自动发起付费请求；续聊时系统规则告知模型早先读到的内容可能已被论坛清理。存储写入失败时显示「对话未能保存」状态卡并暂停后续付费请求。设置页「对话历史」段显示条数与「清空全部对话」。

**Blocked by:** 04 主题详情入口 → 半屏面板 → 流式概览

**Status:** implemented

- [x] Room 迁移设备测试：从当前版本升级后书签与新表数据保留；schema JSON 进版本库
- [ ] 真机：发起对话 → 杀进程 → 重开，历史页显示「已中断」，打开后状态卡说明中断位置，点继续从检查点接着运行且不重复已成功的工具读取
- [ ] 真机：收起再展开继续同一对话；从同一主题重新发起得到新对话，历史页两条并存
- [ ] 切换账号后历史页内容一致
- [x] 删除后撤销恢复对话；确认删除后检查点与 ChatMemory 记录一并清除，用量记录保留（单测）
- [x] 写入失败注入测试：状态卡出现、后续模型请求被阻止、内存草稿仍可见
- [x] 存储说明文档同步新表与生命周期

## Comments

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
