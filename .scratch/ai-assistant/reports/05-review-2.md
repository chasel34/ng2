VERDICT: FAIL

1. **[P2] 回复链分页在缓存增加后重排，造成重复和正文漏返。**

   文件：`app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt:156`、`:171`、`:176`。

   段内每次续读都从根节点（或段初游标）重新遍历，再用 `visited > offset` 跳过前面的节点；下游却根据当前可变的 `pages` 重新计算。因此两次调用之间，其他论坛工具加载的新下游会插入已经跳过的前缀，旧 offset 不再对应此前返回的结果。300 节点处保存游标没有解决段内这一问题。

   已用仓库外临时 Kotlin/JUnit 测试直接调用当前 `ForumToolSession` 复现：主题 10 中，pid 1→2→…→25 构成引用链；首次读取根 pid=1 返回 1–20 和 nextOffset=20；随后用 read_floor 读取 pid=100，其引用 pid=1 和 pid=101；再以原根和 offset=20 续读，实际返回 `[19,20,21,22,23,24,25]` 且没有 nextOffset。19、20 重复，101 在重遍历中被读取却因落入跳过区间而没有返回正文，模型也拿不到该分支的后续分页入口。这违反有界分页和准确报告读取覆盖的约定。

   修复建议：每个分页边界都保存稳定的遍历状态（未读队列、已返回/已访问坐标及范围版本），或固定段内节点顺序；不能在动态重建的遍历上直接应用旧 offset。新发现分支应明确追加为未读或归入新的范围，不能被当作已返回的前缀跳过。补充“回复链两页之间调用 read_floor/read_topic_page 加载新下游”的回归测试，验证无重复、无正文遗漏，且游标重用结果稳定；同步维护工具与公开文档的续读协议。

核实记录：

- 按实现报告的原始及修复清单审阅本票 git diff、未跟踪源码、测试和文档；阅读 CLAUDE.md、票据、实现报告、Koog 最佳实践与 cost-and-recovery.md。未修改项目源码、测试文件或构建配置。
- 上轮热门回复遍历遗漏与无效 nextOffset=300 均已修复；新增热门回复、630 节点跨主题分支和游标校验测试通过。它们没有覆盖本轮发现的缓存变化场景。
- 强制执行 `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:testDebugUnitTest --offline --rerun-tasks`：BUILD SUCCESSFUL，33 个任务实际执行；1167 tests、0 failures、0 errors、4 skipped。
- 临时复现文件与初始化脚本位于 `/tmp/ng2-review-2/`，通过 `--no-configuration-cache -I /tmp/ng2-review-2/review.init.gradle --tests com.chasel.ng2n.data.ai.ReviewChainTest` 执行；1 项测试在“不重复返回已输出楼层”断言失败，实际输出与上述复现一致。结果保存在 `/tmp/ng2-review-2/result.xml`。随后不带初始化脚本运行正常 `:app:testDebugUnitTest --offline` 成功，恢复原测试构建产物。
- 本票相关路径 `git diff --check` 通过。核对 core 纯 Kotlin 边界、TopicRepository 至显式 READ 的调用链、无外层重试、共享限速与取消、屏蔽/匿名处理、图片与工具结果分离、成功轮次协议保留、文本入口读图限制、工具行状态及文档同步，未确认其他需要修改的问题；本票没有新增依赖版本。
- 本次未重新执行设备 UI 测试、真实模型或 NGA 写操作。票内真机自主翻页与真实模型读图质量仍未实测，不以离线通过代替该验收；费用记账与持久化按后续票范围处理。
