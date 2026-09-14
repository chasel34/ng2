VERDICT: FAIL

评审范围：07 票实现报告列出的相关 git diff 和未跟踪文件；对照 CLAUDE.md、ADR-0005、票据验收项、koog-best-practices.md 与 cost-and-recovery.md。未修改应用或测试代码。

1. **P1：从历史恢复后连续中断，会跳过最近一次运行的检查点。**

   位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt:157`（关联第 100、158、257 行）。

   `load()` 将 `resumeFrom` 固定为历史运行 A；第一次继续创建 B 后，只有成功完成才清空该字段。如果 B 已产生新的检查点、成功工具结果，随后再次断线或被停止，同一页面再次点击继续时，第 157 行仍优先选 A，创建 C→A，而不是 C→B→A。这样会丢弃 B 的恢复进度，重走旧模型步骤，并且无法通过 `priorWork()` 查到 B 的成功工具缓存，可能重复读取和产生额外模型费用。这违反“从最近检查点继续、不重复成功工具”的验收要求。现有连续中断测试手工构造正确的 run 关系，没有经过 ViewModel 的这段选择逻辑。

   修复建议：创建新运行前以当前最近的未完成 `run.id` 为恢复来源；初次加载的旧运行也已在 `run` 中，不应让独立的旧 `resumeFrom` 长期覆盖它。补充 ViewModel 回归验证：加载 A→继续 B→B 保存新检查点后中断→继续 C，断言 C 指向 B，并恢复 B 的最新检查点与工具结果。

2. **P1：读图完成后的检查点恢复会丢失实际图片输入。**

   位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt:87`（关联第 68–69、82–92 行，以及 `ForumToolSession.kt:117–131`）。

   对非初始图片执行 `read_image` 成功后，Koog 在 `execute` 节点完成时保存检查点；图片字节另存在 `ToolReplay.images` 和会话的内存 `pending` 中，节点结果只有文本状态。若后续 `sendResults` 的模型流中断或此时进程结束，继续会从该检查点直接进入 `sendResults`，不再执行成功工具。但这里仅从 `drainImages()` 取图片：新进程的 `pending` 为空，同进程重试时它也已被前一次发送消耗，且运行入口还会清空一次。`ToolReplay.images` 只在再次执行工具的缓存命中分支中恢复，因此这条正常检查点恢复路径不会读取它。恢复请求将带着“图片已读取”的工具成功结果，却没有对应图像；模型无法继续原来的读图分析。

   修复建议：把本批待发送媒体及其来源标识纳入可恢复状态，在恢复 `sendResults` 时按检查点重建图文输入，并按已持久化的 prompt 去重；不能依赖重新执行成功工具来填充内存队列。补充非初始图片的中断恢复测试，覆盖同进程重试与新建会话对象，断言继续请求包含对应图片且图片读取次数不增加。

验证记录：

- `./gradlew --offline :app:testDebugUnitTest --rerun-tasks`：BUILD SUCCESSFUL；重新执行 1,177 项，0 失败、0 错误、4 跳过。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.db.AiPersistenceTest,com.chasel.ng2n.data.db.Ng2nMigrationTest,com.chasel.ng2n.ui.ai.AiHistoryScreenTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest`：Pixel_8 / Android 17 模拟器 11/11 通过，包括迁移保留、删除撤销与级联、用量保留、存储失败和历史界面。
- 本票已跟踪改动的 `git diff --check` 通过；核对版本 3 schema、2→3 自动迁移、core 的纯 Kotlin 边界、论坛读取复用路径、注释与存储文档。本票未新增独立依赖版本。
- 上述两项来自实际控制流与持久化数据流核对；现有通过的测试没有覆盖其触发路径。本次未另行执行杀进程双阶段测试、真机测试或真实账号切换，不将实现报告中的既有证据计作本次重跑结果。
