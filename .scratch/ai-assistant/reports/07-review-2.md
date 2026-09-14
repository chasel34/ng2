VERDICT: FAIL

1. **P2：清空事务失败后，内存删除标记没有回滚，导致保留下来的对话无法继续保存。**

   位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/AiConversationStore.kt:75`，关联 `save()` 第 38 行及 `ui/settings/AiSettingsScreen.kt:60–62`。

   `clear()` 在执行 `dao.clear()` 和事务提交之前，就把全部会话 ID 加入单例的 `deleted` 集合。如果 DELETE 或提交因存储异常失败，Room 会回滚数据库，但不会回滚这个内存集合。设置页显示“清空失败”，历史记录也仍然存在；然而之后这些对话的 `save()` 都会因 ID 已在 `deleted` 中直接抛出 `AiStorageException`。即使磁盘问题已经解除，用户点“重试保存”仍然失败，继续聊天也被阻止。当前失败注入测试只覆盖 INSERT 失败，没有覆盖清空回滚后的恢复。

   修复建议：让删除标记与事务成功状态一致，在失败或取消时恢复本次新增的内存标记，同时保留原有删除标记；将相关操作放在一致的并发保护下，避免修复后产生迟到写入重建对话的窗口。补充真实 Room 回归测试：保存会话→注入 DELETE 失败→调用清空并确认失败→移除故障→确认历史、检查点和用量仍在，且同一个 store 能再次保存原会话。

核实记录：

- 已按实现清单检查本票相关 git diff 与未跟踪文件，对照 CLAUDE.md、票据验收项、ADR-0005、Koog 最佳实践和 cost-and-recovery.md；未修改应用或测试代码。
- 上轮两项问题已修复：连续恢复使用最近未完成运行 ID；读图恢复从持久化工具结果取回媒体并按 prompt 去重。新增四项回归测试已在本次完整单测中执行通过。
- `env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:testDebugUnitTest --rerun-tasks`：BUILD SUCCESSFUL，1,181 项，0 失败、0 错误、4 跳过。
- `env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiPersistenceTest,com.chasel.ng2n.data.db.Ng2nMigrationTest,com.chasel.ng2n.ui.ai.AiHistoryScreenTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：Pixel_8 / Android 17 模拟器 12/12 通过。
- 本票范围的 `git diff --check` 通过。核对了 core 纯 Kotlin 边界、论坛读取复用路径、版本 3 schema 与 2→3 迁移、子表级联和独立用量生命周期、历史入口、注释及文档同步；本票未新增独立依赖版本。
- 上述问题依据清空和保存的实际控制流确认，现有测试通过不覆盖该失败路径。本次未重跑杀进程双阶段测试、真机或真实账号切换，票据中未勾选的人工验收项仍未验证。
