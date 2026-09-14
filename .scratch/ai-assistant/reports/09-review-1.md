VERDICT: FAIL

评审范围：09 票与 09-impl.md 清单中的 git diff、未跟踪新文件；对照 CLAUDE.md、票内验收项、koog-best-practices.md 和 cost-and-recovery.md。未修改项目代码。

1. **[P1] 确认追加额度期间点击停止，仍会恢复付费运行。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt:186–194`（关联 `stop()` 第 176–180 行）。
   - 问题：`decideBudget()` 把 busy 设为 true，因此输入栏显示可点击的“停止”，但确认协程通过独立的 `scope.launch` 启动，没有保存到 `job`，也没有校验 generation。在 `budgets.decide()` 或 `save()` 挂起期间点击停止，只会取消旧运行的 job。确认协程随后仍覆盖停止状态并调用 `retry()`，发起新的模型运行。这违反停止后不再启动请求的验收项；同样可能在删除会话后发生迟到状态更新。
   - 修复建议：将确认操作纳入可取消的任务管理，捕获并检查会话 ID 与运行代次；在每次挂起返回、更新状态以及调用 retry 前检查是否仍有效。追加金额即使已提交，也不能在用户停止后自动续跑。补充用延迟的 decide/save 触发“确认追加 → 停止 → 完成存储”的测试，断言没有新增模型调用且状态仍为停止。

2. **[P2] 修正每日额度、Key 或余额后，原分析没有继续入口。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt:165`；`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiBudgetCards.kt:64`。
   - 问题：`daily`、`AUTH`、`BALANCE` 被无条件排除在“继续”按钮之外，卡片只允许前往设置。设置返回不会清除这些 card，也没有监听设置修正来恢复操作，因此错误修正后仍只能再次进入设置。输入框发送会走 `continuing=false`，创建新 analysis，不能替代按原检查点继续。卡片承诺“修正后回到本对话继续”，但该路径不可用。
   - 修复建议：提供明确的修正后继续入口，复用现有 retry/检查点和 analysisId；每日额度在发送前重新检查，不能绕过。设置返回不得自动付费运行。补充完整的“触发状态卡 → 修改设置 → 返回 → 用户继续”界面测试。

3. **[P2] 明确尚未发送的请求也永久占用待核实额度。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/AiBudgetStore.kt:131`；`app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt:201`。
   - 问题：事件先执行 reservePrepared，再调用包含数据库保存的 onStart，最后才进入 HTTP。若 onStart 保存失败或此时用户取消，HTTP 尚未开始，但 finish 无条件把预留标成 pending_verification，完全不检查 transportStarted。该记录 cost 为 null，之后跨日、重启一直占用单次及每日额度，并在界面计为模型请求；目前没有释放或核销入口。这里是已知未发送，不能作为“已发送但 usage 不完整”长期扣住额度。
   - 修复建议：区分预留、已进入发送、已结算与明确未发送取消；在能够确认未发送的失败/取消路径原子释放预留。进程重启或发送结果无法确认时继续保守保留，不能一概清空。补充 onStart 失败及发送前取消的测试，确认 HTTP 请求数为 0 且预留不再占用额度。

4. **[P3] 长期文档仍保留与本票实现冲突的预算和重试说明。**
   - 位置：`docs/ai-assistant.md:17`；`docs/adr/0006-koog-agent-runtime.md:30–32,46`。
   - 问题：用户文档仍称“金额额度仍由相应能力接入”；ADR 仍称工厂传入默认 `OkHttpKoogHttpClient.Factory()`、“保留框架默认传输行为”以及“没有额外自动重试包装”。实际已使用 BudgetHttpFactory、关闭透明重试/重定向，并包装 RetryingLLMClient。这些段落与同文档新增的预算章节互相矛盾，不满足 CLAUDE.md 的文档同步约定。
   - 修复建议：原位更新上述当前行为描述，使其与本票预算、HTTP 和重试实现一致；不要仅在文末追加新段落而保留失效说明。

验证记录：

- JDK：Zulu OpenJDK 17.0.20.1。
- `./gradlew --offline :app:testDebugUnitTest --rerun-tasks`：通过，33 项 Gradle task 实际执行。核对生成的 JUnit XML：1213 项测试，0 failures、0 errors、5 skipped。
- `./gradlew --offline :app:assembleDebug`：通过。
- `git diff --check`：通过。
- 已核对本票核心预算文件无 android.* 依赖、Room 3→4 schema 与迁移声明、READ 复用路径、Koog 单层重试与独立尝试记账、依赖版本目录和新增注释。上述四项来自代码控制流与文档核对；现有通过测试未覆盖这些恢复与取消边界。
- 本轮未运行设备 instrumentation 或真实付费模型调用，不把实现报告中的设备结果视作本轮复测。真实费用校准仍未完成，票和报告已明确披露其限制，本次不将已披露的离线替代本身列为新增缺陷。
