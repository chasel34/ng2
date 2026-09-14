VERDICT: FAIL

评审范围：05-impl.md 清单中的本票实现、新增测试和文档；检查相关 git diff 与未跟踪文件。其他票的实现仅作为调用契约核对，不列入修改建议。未修改代码。

1. **[P2] 回复链复用热门回复时漏掉引用上游及已读下游。**
   文件：`app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt:164`（及 166 行）。
   `material()` 允许从缓存页的 `floors + hotReplies` 找到目标并返回正文，但链遍历只从 `floors` 查找目标和扫描下游。初始第一页已缓存、目标仅存在于 `hotReplies` 时，工具能返回该热门回复，却不会解析其引用；即使正文明确引用另一个 pid，也会返回 `ok` 且不提供后续节点。已缓存热门回复作为下游时同样被漏掉。这违背工具声明的“引用上游与已加载页中的下游”范围，并非“未扫描整楼”所解释的缺失。
   修复建议：目标查找与下游扫描统一覆盖 `floors + hotReplies`，按 `(tid,pid)` 去重。补充目标仅在热门回复中、热门回复引用其他楼层，以及热门回复作为下游的测试。

2. **[P2] 回复链达到遍历上限时返回不可执行的续读参数。**
   文件：`app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt:178`（与 147、154 行共同触发）。
   对超过 300 个节点的链，以 `offset=280` 读取后，若队列仍有未读节点，返回 `nextOffset=300`；下一次照此调用必定被 `offset > 280` 校验拒绝。结果没有结构化返回剩余队列的 tid/pid，因而文档所述“按具体坐标继续读取”也缺乏可靠入口，尤其在分支链或跨主题引用时。模型遵循工具返回值仍会得到参数错误，无法按约定完成分页。
   修复建议：达到 300 节点上限时不要返回无效 `nextOffset`；明确标记达到范围限制，并返回有界的未读节点坐标或可恢复游标，让模型能够继续读取全部未读分支。补充超过 300 节点及分支链的测试，断言每个返回的续读参数都可被执行。

核实记录：

- 已阅读 CLAUDE.md、票据、实现报告、technical-design.md、koog-best-practices.md、cost-and-recovery.md，并核对公开文档及测试。上述两处为源码路径可直接确认的问题；现有回复链测试只覆盖局部失败与重新过滤，未覆盖热门回复遍历和 300 节点边界。
- 执行 `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:testDebugUnitTest --offline --rerun-tasks`：BUILD SUCCESSFUL，33 个任务实际执行；XML 统计 1164 tests、0 failures、0 errors、4 skipped。普通命令也通过，但为 UP-TO-DATE，故另行强制重跑。首次沙箱执行受 Gradle 缓存锁文件权限限制，获准访问缓存后完成验证。
- 相关路径 `git diff --check` 通过。未跟踪的新文件按全文审阅。本票未新增依赖版本或引入 core 的 Android 依赖；论坛读取经 TopicRepository 到显式 `Operation.READ`，未发现额外外层重试或 WRITE 路径。注释与文档同步未发现另需修改的问题。
- 核对五工具注册、参数错误、缓存重新过滤、匿名处理、共享限速及取消、文本入口禁用读图、图片独立 user 消息、成功轮次协议保留、工具行结算与展开行为。现有离线测试通过不消除上述未覆盖缺陷。
- 真机自主翻页、真实模型读图质量仍为票内未完成验收项。本次未调用付费模型、未执行 NGA 写操作，未重跑设备 UI 测试；实现报告中的模拟器与截图验证不视为本次真机验证。
