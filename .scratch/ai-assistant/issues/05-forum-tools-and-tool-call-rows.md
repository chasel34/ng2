# 05 — 论坛读取工具与工具调用行

**What to build:** 模型在回答过程中可以自行调用五个论坛工具：读取主题某页、按 pid 定位楼层、读取回复链、列出某楼层或某页的图片、读取指定图片。面板里出现「N 次工具调用」组件：每行显示工具名与参数标签，进行中闪光，失败行红色并可展开明细，全部结束后标题补「，N 次失败」。工具经票 02 的读取层与现有 READ 链读取，共用一个 NGA 请求速率限制器，与 READ 链自身的重试分开。工具返回前套用屏蔽规则并报告被过滤数量；匿名作者返回「匿名用户」；结果区分「已删除或不可访问」与「请求失败」；分页与有界正文，不一次返回整楼。读图结果的图片按 Koog 最佳实践追加为带来源说明的 user 图文消息，而不是塞进工具结果。工具结果只作资料，不作为指令。个人分析入口尚未存在，但工具注册表要能按入口禁用读图。

**Blocked by:** 04 主题详情入口 → 半屏面板 → 流式概览

**Status:** implemented

- [ ] 真机：提出需要翻页或回读引用楼层的问题，看到模型调用工具、工具行逐条出现并结算，回答引用新读到的楼层
- [x] 限速单测：连续几十次读取按上限排队；用户停止时排队与进行中的读取一并取消
- [x] 工具单测：屏蔽楼层被过滤并计数、匿名作者无假名、已删除 / 无权限 / 网络失败三种状态可区分、无效参数在执行前被拒绝并返回结构化错误
- [ ] 读图后模型能描述图片内容；工具结果消息本身不含图片二进制
- [x] 同一资料在同一对话内重复请求时复用已读结果，不重复发起论坛请求

## Comments

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

- 评审修复第 1 轮：接受并修复热门回复上游/下游漏读、超过 300 节点返回无效续读参数两项问题。增加 630 节点跨主题分支与游标回归测试；强制重跑 debug、release 和单测通过，1167 tests、0 failures、0 errors、4 skipped，107 个任务实际执行。完整修复说明与本轮文件清单已追加至 [实现报告](../reports/05-impl.md#评审修复第-1-轮)。Status 保持 implemented，未 git commit。

- 评审修复第 2 轮：修复缓存增加后回复链段内分页重排；每页使用固定范围版本和稳定游标，新增下游明确归入新范围。两种工具插入缓存、旧游标重用及屏蔽变化回归通过。强制重跑 debug、release 与单测成功，1168 tests、0 failures、0 errors、4 skipped，107 个任务实际执行。完整说明与本轮文件清单见 [实现报告](../reports/05-impl.md#评审修复第-2-轮)。Status 保持 implemented，未 git commit。
