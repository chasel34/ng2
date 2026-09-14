# 05 — 论坛读取工具与工具调用行实现报告

2026-09-13 — 实现完成，Status: implemented。未执行 git commit，保留工作区已有改动。

实现内容：

- 注册读取主题页、按 pid 定位、回复链、图片索引和指定图片五个 Koog 工具。通过 TopicRepository 与既有 NGA READ 链读取，不叠加外层重试。所有论坛工具共享串行限速器，最小请求间隔 800 ms，排队与进行中的工作随用户停止取消。
- 会话内复用初始页、热门回复、后续页面和图片；缓存每次返回前重新应用屏蔽规则。匿名作者不发送本地假名。工具明确返回屏蔽数量、页码、来源、分页偏移及权限不足、已删除或不可访问、请求失败、无效参数等状态。
- 正文每次 16000 字符、图片索引每次 100 项、回复链每批最多 20 个节点，返回续读坐标。回复链读取上游与已加载页下游，明确未扫描整楼；单次遍历保护最多 300 个节点，可用具体坐标继续读取，不声称完整覆盖。
- 图片工具结果只含文本状态与来源标识；图片在整批工具结果之后作为带来源说明的 user 图文资料追加，复用已经提供的图片，不执行资料中的指令。文本入口在注册与执行两处禁用读图。
- 工具来源使用会话稳定 sourceId，面板回答可引用新楼层。成功轮次保留工具调用、结果、思考字段与图文协议消息；原始初始上下文独立保留，避免将新增全文反复注入续聊。
- 按 Components.dc.html 实现工具行：32dp 行、16dp 图标位、13sp 名称、参数标签、1.4 秒闪光、300ms 出现与收起动画、150ms 标题箭头旋转、失败红色明细与失败计数。回答开始自动收起，用户已操作时保留选择；停止和异常结算未完成行。

逐项验收：

| 票内项目 | 实现与验证 |
| --- | --- |
| 真机自主翻页、引用楼层与逐条工具行 | 功能已接入。假模型连续工具调用验证来源编号、逐条开始/完成事件与续聊协议；模拟器验证工具行。仅连接 emulator-5554，没有真机，未调用真实 DeepSeek；此项真机质量验收未实测。 |
| 几十次读取限速与停止取消 | 40 次读取按 800ms 间隔排队；取消同时终止进行中任务及 30 个排队任务，离线测试通过。 |
| 屏蔽、匿名、删除/权限/网络及无效参数 | 本地与官方规则、缓存重新过滤、匿名去假名、三类错误、缺少参数及参数类型错误均有测试，读取前拒绝无效参数。 |
| 读图与工具结果无二进制 | 假模型验证实际 user 图片块、来源说明、工具结果无 base64、后续轮次保留协议；真实模型对图片内容的描述质量未实测。 |
| 同一对话重复资料复用 | 重复楼层、已读页面中的楼层、图片下载复用和分页测试通过；请求与下载计数验证不重复读取。 |

验证结果：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease --offline`：BUILD SUCCESSFUL，含 release R8；1164 项测试，0 failures、0 errors、4 skipped。论坛工具测试 6 项、TopicAgentRuntime 测试 7 项均通过。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest --offline -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.ToolCallRowsTest`：Pixel_8 Android 17 模拟器，2 项通过、0 失败。
- 已检查深浅色截图，失败色、标签、明细竖线和文字布局正常。截图与完整构建日志位于 `.scratch/ai-assistant/reports/05-assets/`。
- `git diff --check` 通过。本次未使用真实模型、未执行 NGA 写操作，也未将模拟器协议验证当作真机读图或性能验证。
- 初次执行因沙箱不能写 Gradle 缓存、随后缺少 ANDROID_HOME 而失败；使用获准的 Gradle 缓存访问与本机 SDK 环境后完成全部离线构建和测试。没有改动项目 SDK 配置或依赖版本。

改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/ToolCallRows.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/ForumToolSessionTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/ToolCallRowsTest.kt`
- `docs/ai-assistant.md`
- `docs/testing.md`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/issues/05-forum-tools-and-tool-call-rows.md`
- `.scratch/ai-assistant/reports/05-impl.md`
- `.scratch/ai-assistant/reports/05-assets/build.log`
- `.scratch/ai-assistant/reports/05-assets/ui-tests.log`
- `.scratch/ai-assistant/reports/05-assets/ai-tools-light.png`
- `.scratch/ai-assistant/reports/05-assets/ai-tools-dark.png`

## 评审修复第 1 轮

接受 `05-review-1.md` 中两条问题，无不同意项。未执行 git commit。

1. 修复热门回复遍历缺失：目标查找和已读下游扫描统一使用 `floors + hotReplies`；下游先限定 tid 再按 pid 去重，整个遍历按 `(tid,pid)` 去重。新增测试覆盖目标只存在于热门回复、热门回复引用其他楼层、热门回复作为下游，以及同一发言出现在多个缓存页或普通楼层列表中时只返回一次。
2. 修复范围上限后的无效续读参数：不再返回无法执行的 `nextOffset=300`。达到本段范围限制且仍有未读节点时返回 `rangeLimitReached=true` 和完整的 `continuation` 参数对象（tid、pid、cursor、offset）；会话内游标保存全部未读队列和已访问坐标，续读从保留的分支继续。游标不因使用而消耗；未知游标、主题或起点不匹配、非回复链工具使用游标均在读取前拒绝。段内 `nextOffset` 始终不超过 280；非 20 整倍数的合法偏移接近边界时也返回可执行的 continuation。

新增 630 节点回归场景包含相同 pid 分布于不同主题的两个分支，按工具返回参数完整遍历并跨过两次范围限制，验证：每批不超过 20 节点、630 个坐标没有遗漏或重复、每个续读参数都可执行、论坛读取恰好 630 次、游标重用不额外读取；另覆盖 offset=279 边界、未知游标和不匹配起点。

同步更新公开工具说明及测试文档。最新续读行为以上述游标协议为准，取代本报告上一轮“按具体坐标继续读取”的说明。

本轮验证：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:testDebugUnitTest --offline --tests com.chasel.ng2n.data.ai.ForumToolSessionTest`：BUILD SUCCESSFUL；论坛工具测试 9 项全部通过。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease --offline --rerun-tasks`：BUILD SUCCESSFUL，107 个任务全部实际执行；1167 tests、0 failures、0 errors、4 skipped；debug 与 release（含 R8）均通过。
- `git diff --check` 通过；本轮涉及的文本文件另检查了行尾空白。日志保存于 `05-assets/review-1-tests.log` 和 `05-assets/review-1-build.log`。
- 本轮未改 UI，未重跑设备 UI 测试；未执行付费模型或 NGA 写操作。真机自主翻页、真实模型读图质量仍沿用前轮未实测边界，未以离线测试替代这些验收。

本轮最新改动文件清单（此前清单仍记录上一轮范围）：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/ForumToolSessionTest.kt`
- `docs/ai-assistant.md`
- `docs/testing.md`
- `.scratch/ai-assistant/issues/05-forum-tools-and-tool-call-rows.md`
- `.scratch/ai-assistant/reports/05-impl.md`
- `.scratch/ai-assistant/reports/05-assets/review-1-tests.log`
- `.scratch/ai-assistant/reports/05-assets/review-1-build.log`

## 评审修复第 2 轮

接受 `05-review-2.md` 的分页重排问题，无不同意项。未执行 git commit。

修复说明：

- 回复链改为每页保存稳定游标：包含读取范围版本、未读队列、已访问坐标和累计访问数量；本页执行完成后固定本页节点顺序及下一游标。续读不再从根重遍历，也不再按旧 offset 跳过动态前缀。
- 每页返回 `rangeVersion`、本页 `cursor`，还有未读节点时返回完整 `continuation` 参数。回复链不再返回 nextOffset，非零 offset 在读取前拒绝；正文与图片索引的偏移分页不变。每累计 300 个节点仍提示范围限制，并保留可执行的下一页游标。
- 按评审允许的“新分支归入新范围”方式处理缓存增加：下游关系固定为本范围创建时已加载页（含热门回复）的关系索引。后来由 read_floor/read_topic_page 加载的下游不插入旧范围；工具描述及每次结果的 scope 均明确提示，不带 cursor 从起点创建新范围即可纳入新增分支。新范围分配新的 rangeVersion。
- 游标不因重用而消耗；重用已执行游标时只读取该页固定坐标，下一游标保持不变。正文返回前仍套用当前屏蔽规则，不能因结果稳定而绕过新屏蔽规则。
- 已同步工具描述、公开使用文档及测试文档。本轮协议取代上轮报告中的段内 nextOffset 续读说明。

回归证据：

- 将评审的 1→2→…→25、100 引用 1 和 101 场景纳入测试，分别在两页之间用 read_floor 和 read_topic_page 加载新下游。旧范围先返回 1–20，再返回 21–25，没有重复；101 不会在跳过区间中被偷偷读取。
- 创建新范围后，1–25、100、101 共 27 个节点全部返回且不重复；重新使用旧第二页游标得到完全相同的结果，不增加论坛请求。
- 修改屏蔽规则后重用同一游标，正确过滤 21 楼并报告屏蔽数量，其余顺序及分页边界保持不变。
- 继续保留并通过热门回复遍历、630 节点跨主题分支、两次 300 节点边界及游标校验等已有回归测试。

本轮验证：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:testDebugUnitTest --offline --tests com.chasel.ng2n.data.ai.ForumToolSessionTest`：BUILD SUCCESSFUL；论坛工具测试 10 项全部通过。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease --offline --rerun-tasks`：BUILD SUCCESSFUL；107 个任务全部实际执行，1168 tests、0 failures、0 errors、4 skipped；debug、release（含 R8）均通过。
- `git diff --check` 通过；本轮文本文件行尾空白检查通过。日志保存于 `05-assets/review-2-tests.log` 和 `05-assets/review-2-build.log`。
- 本轮未改 UI，未重跑设备 UI 测试，未调用真实付费模型或执行 NGA 写操作；真机自主翻页与真实模型读图质量仍为此前未实测边界。

本轮最新改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/ForumToolSessionTest.kt`
- `docs/ai-assistant.md`
- `docs/testing.md`
- `.scratch/ai-assistant/issues/05-forum-tools-and-tool-call-rows.md`
- `.scratch/ai-assistant/reports/05-impl.md`
- `.scratch/ai-assistant/reports/05-assets/review-2-tests.log`
- `.scratch/ai-assistant/reports/05-assets/review-2-build.log`
