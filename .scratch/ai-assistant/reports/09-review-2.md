VERDICT: FAIL

评审范围为 09 票与 09-impl.md 清单中的 git diff、未跟踪新文件；对照 CLAUDE.md、验收项、koog-best-practices.md 和 cost-and-recovery.md。上一轮四项问题已修复，本轮不重复列出。

1. **[P2] 删除会话后，用量订阅未取消，持续保留已删除的 ViewModel。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt:53–56`（关联第 165–168 行）；`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSessions.kt:21,28–29`。
   - 问题：新增的 `budgets.books.collect` 启动在生产环境传入的应用级 taskScope 下，其 Job 未保存。删除和清空会话时，`forget()` 移除 sessions 条目后只调用 `discard()`，后者仅取消模型/确认任务的 job，无法取消这个无限订阅。订阅闭包继续引用 ViewModel，并在后续账本更新时重新向已清空的 state 写入 usage，因此会话删除后对象和订阅仍存活到进程结束。每次创建再删除会话都会增加一个残留订阅；`AiBudgetStore.books` 又是包含完整账本 JSON 解码的冷 Flow，每个残留订阅都会继续执行解码与状态更新。这是本票新增订阅的资源泄漏，不是账本需要独立保留的行为。
   - 修复建议：为用量观察保存独立 Job，在会话销毁/forget/discard 时取消；或者给每个会话创建独立的子作用域，在销毁时整体取消。不要直接取消共享的 deps.scope，也不要因收起面板而取消仍有效的会话任务。补充使用应用级 taskScope 的生命周期测试：创建会话后订阅数增加，discard/forget 后订阅数回落；再更新账本，已删除会话的 state 不应再变化。
   - 核实方式：沿 `AiSessions.create → TopicAiViewModel.init → forget → discard` 检查任务所有权和取消路径，确认用量观察 Job 没有退出或取消入口。现有停止/删除竞态测试验证模型不重启，未验证该长期订阅被释放。本轮未做真机性能测量，不据此量化内存或耗时影响。

验证记录：

- JDK：Zulu OpenJDK 17.0.20.1。
- `./gradlew --offline :app:testDebugUnitTest --rerun-tasks`：通过，33 项 Gradle task 实际执行。JUnit XML：1222 项测试，0 failures、0 errors、5 skipped。
- `./gradlew --offline :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiBudgetPersistenceTest,com.chasel.ng2n.ui.ai.AiBudgetCardsTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：通过；Pixel_8 模拟器完成 6/6 项设备测试，0 失败、0 跳过。覆盖 Room 3→4 迁移、真实事务并发、重开与删除保留用量、未发送释放、确认卡、设置修正后手动继续及用量明细。
- `git diff --check`：通过。
- 已核对本票 core 预算规则无 android.* 依赖、NGA READ 复用未增加写请求、依赖版本目录与 Koog ABI 例外说明、注释及预算/恢复文档同步。
- 已核对同一分析的额度追加、每日拦截、usage 去重与缓存记账、HTTP 重试独立预留、空流和已输出流不自动重放、停止期间确认协程取消、明确未发送预留释放及重启后保守保留。
- 本轮未执行真实付费模型校准或 release 构建。真实样本与默认额度尚未校准的限制已在票和实现报告中明确披露，未将其视为本轮新增缺陷。

未修改项目代码，仅写入本评审报告。
