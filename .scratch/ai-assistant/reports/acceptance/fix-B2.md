# 验收组 B 第二轮 + 组 C 修复说明

验证命令（JDK 17，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE）：

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，1290 个离线用例、0 失败。UI 回归另跑了 `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.chasel.ng2n.ui.ai`，24 个用例全部通过。另在 emulator-5554 上用已保存的 Key 做了 4 次真实短调用复验（今日用量 ≈US$0.22 → ≈US$0.24），Key 未写入任何文件或日志。

两条 P1 同源于 Koog 1.2.0 `OkHttpKoogHttpClient` 的 SSE 实现，与 okhttp 5.5.0 无关，不需要降级 okhttp。

---

## P1 回答尾部被打乱成碎片并截断，界面仍标「已完成」

**根因（字节码级确认 + 离线复现）**：Koog 1.2.0 `OkHttpKoogHttpClient.sse` 是 `callbackFlow`，`EventSourceListener.onEvent` 用 **`trySend`** 把每个 SSE 事件推进缓冲，并丢弃返回值。`callbackFlow` 默认缓冲为 64、溢出策略是丢弃：只要消费端慢于服务端（本项目每个文字帧都写一次 Room），缓冲一满就**静默丢帧**。表现正是「开头完整、中段起变成词语碎片、末尾恒定截断」，`]]`/`[[`/`**` 这些残渣是被拆散的标记片段。丢帧同时命中结束帧，于是 `frames.toMessageResponse()` 拼出的 assistant 消息本身就是坏的（所以落库文本坏、从历史重开仍坏），而 `TopicAgentRuntime` 的 `check(frames.lastOrNull() is StreamFrame.End)` 偶尔抛出，就是第 6 条 P2「回答生成中断且文字为 0」。渲染层（`AiMarkdown.kt`）无关，落库文本与原始 SSE 对拍一致地坏。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/BudgetHttpClient.kt`：`sse` 与 `lines` 不再委托上游，改为用同一个 OkHttpClient 直接发请求并自行按 SSE 规范解析（按行累积 `data`、空行分发事件）。事件以 `emit` 挂起下发，背压回到 socket 读取，永不丢帧。非流式 `post` 仍委托上游并沿用 `Content-Type: application/json` 补充。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：文字增量帧的落库改为最多每 400 ms 一次（结束帧、工具帧、其它帧仍立即保存，`finally` 仍有最终保存）。逐 token 写 Room 现在会真的把压力压回 socket，节流避免长回答期间读取被拖慢。
- `docs/adr/0006-koog-agent-runtime.md`：记录该上游缺陷与本机实现，注明上游修正后改回委托。

**验证方式**：新增 `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/ModelStreamingTest.kt`。`chunkedStreamReachesASlowConsumerWithoutLosingFramesOrTheEndFrame` 用 MockWebServer 以 7 字节与 4096 字节两种块长发 400 个文字帧（每段含多字节中文，按字节切分必然把 UTF-8 序列切开），消费端每帧 `Thread.sleep(1)`，断言拼接文本与 `toMessageResponse()` 完全等于原文且最后一帧是 `End`；`agentRuntimeStoresTheWholeAnswerWhenEveryFrameIsPersisted` 走 BudgetHttpFactory → DeepSeek 客户端 → `TopicAgentRuntime.runWith`，断言界面收到的增量与落库消息都等于原文。修复前这两条分别在第 151 段、第 114 段附近开始丢失。设备复验：新发起的主题概览落库文本 1122 字，结尾为完整的「证据局限」段落，无碎片、无截断。

## P1 点「停止」必定崩溃退出

**根因**：同一个上游实现的 `onFailure` 在 OkHttp Dispatcher 线程上调用 `response.body.string()`。okhttp 5 对 `EventSourceListener` 的响应体做了 strip，读取即抛 `IllegalStateException: Unreadable ResponseBody!`，抛在 OkHttp 线程上无人捕获，进程被杀。取消流时必然走这条路径，所以「停止」必崩。第一轮没暴露是因为请求在 415 阶段就失败了，没有活的 SSE 可取消。

**改动文件**：同上的 `BudgetHttpClient.kt`。本机实现失败时只按状态码分类，不读取响应体（也避免把服务端错误正文带进日志）；取消通过 `Job.invokeOnCompletion` 调 `Call.cancel()` 结束读取，随后的 `IOException` 先 `ensureActive()` 转成取消，不再抛到 OkHttp 线程。

**验证方式**：`ModelStreamingTest.cancellingMidStreamRaisesNoUncaughtFailure` 装 `Thread.setDefaultUncaughtExceptionHandler`，在限速的流中途取消，断言没有未捕获异常；修复前该测试捕获到上述 `Unreadable ResponseBody!`。设备复验：生成中点「停止」，进程存活（pid 不变）、`logcat -b crash` 为空，界面显示「已停止 · 未完成」，已生成文字保留并给出「继续」。

## P2 快捷操作菜单打不开（`/` 按钮与输入 `/` 都无浮层）

**根因（设备上定位）**：`open` 状态本身是对的（临时调试读数为 `o=true f=true s=false e=true`），弹窗窗口也已创建（`dumpsys window windows` 里有 `Pop-Up Window Requested w=1016 h=644`），但浮层内容停在入场动画的起点。浮层内的 `AnimatedVisibility` 由 `LaunchedEffect` 从 `false` 起步，需要弹窗自己的下一帧把动画推进；点 `/` 按钮时页面其余部分完全静止，这一帧不会到来，菜单就永远停在不可见状态。输入 `/` 时之所以「正常」，是输入法弹出带来的持续布局与动画顺带把帧推了起来——所以这条问题表现为时有时无，第一轮抓到的是碰巧有动画的时刻。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposer.kt`，浮层内容直接显示，去掉入场动画；其余逻辑（`forced`/`suppressed`、前缀筛选、键盘导航、Esc）不变。

**验证方式**：新增 androidTest `QuickActionComposerTest.slashMenuOpensInAFinishedConversationInsideTheSheet`，在 `TopicAiSheetContent` 的半屏与全屏两种形态下分别验证点 `/` 按钮开、再点关、输入 `/` 再开，并用 `assertIsDisplayed()` 确认真的可见。设备复验：连点 `/` 四次，菜单稳定开/关/开/关。

附注给下一轮验收：该浮层是非获焦的独立弹窗窗口，`uiautomator dump` **看不到**它，只能用截图确认。

## P2 来源预览把楼层和页码显示成「0 楼 / 第 1 页」，按钮退化成「跳到主楼」

**根因**：`AiSourcePreviewReader` 用重读结果里的 `floor.lou` 与 `detail.page` 覆盖展示坐标。按 pid 取单楼时 NGA 不返回楼层序号（`lou = 0`），页码也固定是请求页，于是覆盖掉会话里已保存的正确坐标。重读进行中或失败时用的是保存值，所以只有成功后才错。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReader.kt`：`AiSourceCoordinate` 增加 `floor`；按 pid 读取时楼层取 `floor.lou.takeIf { it > 0 } ?: coordinate.floor`、页码取保存的 `coordinate.page`，非 pid 路径保持原样。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：`preview()` 传入 `source.floor`。

**验证方式**：`AiSourcePreviewReaderTest.singleFloorReadKeepsTheSavedFloorNumberAndPage` 覆盖 `lou = 0` 与返回真实楼层两种情况。设备复验：点行内来源标签，预览显示「3 楼 · 樱花花下」「… · 第 1 页」，按钮为「跳到 3 楼」。

## P2 内置技能子目录列举失败

**根因**：不是文件系统适配的问题。数据库里该行的工具名是 `__list_directory`（少了结尾的两个下划线），与注册名 `__list_directory__` 不同，框架按「工具不存在」失败——模型把框架工具名写错了。同一轮根目录那次用的是正确名字，所以成功。这同时解释了下面 P3「失败行显示框架原始名」：名称映射表里没有 `__list_directory` 这个键。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`，新增 `canonicalToolName` / `canonicalToolCalls`：assistant 消息进入提示词与工具执行之前，把只在首尾下划线上有差异、去掉下划线后与注册名完全相同的调用名改写为注册名，不做任何模糊匹配，也不改变对模型公布的工具清单。仍然不存在的工具按失败标注，明细写「模型请求了不存在的工具 X，未读取资料」。

**验证方式**：`BuiltinSkillsTest.frameworkToolNameWithoutTrailingUnderscoresStillListsTheSkillSubdirectory` 让模型发出 `__list_directory` 调用技能子目录，断言真的读到 `references/example.md`、工具行名为 `__list_directory__`、状态 ok；`TopicAgentRuntimeTest.toolRowsGetReadableNamesArgumentsAndStatusText` 覆盖改写规则的正反用例。

## P2 部分运行以「回答生成中断」结束且丢弃已生成文字

与第一条 P1 同源：结束帧被丢掉后 `check(frames.lastOrNull() is StreamFrame.End)` 抛出。流式修复后不再发生；该轮文字为 0 是因为丢帧发生在该次请求的全部文字帧上，失败分支本身一直保留已生成文字。`agentRuntimeStoresTheWholeAnswerWhenEveryFrameIsPersisted` 同时覆盖这条。

## P3 同一轮里不同工具显示成完全相同的一行

**根因**：`__read_file__` / `__list_directory__` 的参数标签只取技能名，读 `SKILL.md` 和读 `references/example.md` 因此完全相同。

**改动文件**：`TopicAgentRuntime.kt` 新增 `toolArgumentLabel`，技能路径显示为「技能名 · 文件名」，根目录仍为「内置技能」。设备复验：同一轮两行分别显示「补充背景 · SKILL.md」与「补充背景 · example.md」。

## P3 失败或进行中的工具行显示框架原始名

由上面的工具名改写解决；另外 `ToolCallRows.kt` 对未收录的名称也不再原样输出，去掉首尾下划线并把下划线换成空格。

## P3 工具行展开重复显示参数、状态写成 `"NATIVE"`

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/ToolCallRows.kt`：参数只在收起时作一行摘要，展开后给出完整参数与明细，不再重复，也不再把框架原始名单独列一行。
- `TopicAgentRuntime.kt`：明细里的状态与来源改用中文（`toolStatusLabel` / `toolOriginLabel`），`blocked`、`page`、`nextOffset` 取 `jsonPrimitive.content`，不再输出带引号的原始枚举。

## P3 预览重读失败时跳转按钮无反馈

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheet.kt`，跳转按钮显式声明禁用态配色，并在按钮下方按状态说明原因：「该来源已删除或不可访问，无法跳转。」「该来源已被屏蔽，无法跳转。」「重新读取成功后才能跳转。」

## P3 历史条目标题回退成「主题 47552646」，副标题重复且尾部多一个分隔符

**根因**：副标题由字符串拼接而成，`joinToString(prefix = " · ")` 在没有作者时留下尾部分隔符；标题缺失时 `state.context?.title ?: state.title` 与后面的「主题 tid」重复。入口标题在主题读取失败时也没有沿用列表已知的标题。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`：副标题改为按段收集后 `filterNot { isNullOrBlank() }.joinToString(" · ")`，标题等于 tid 占位时不重复输出。
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicScreen.kt`：AI 入口标题回退顺序与顶栏一致，`vm.currentModel?.subject ?: key.title ?: "主题 tid"`。

设备复验：新对话副标题为「法环这支线不看攻略感觉完全做不下去啊 · 主题 47548469 · Bearvite、樱花花下、…」。

## P3（组 C）读取失败的对话徽标写成「已中断」

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiHistoryScreen.kt`，状态含「读取失败」时徽标为「读取失败」，排在通用「已中断」之前，与状态卡「内容读取失败 / 尚未请求模型」一致。

## 顺带修正

`classifyAiFailure` 原先只检查最外层异常是否为 `UnknownHostException` / `ConnectException`；本机 HTTP 实现现在把 `IOException` 作为 cause 传出，改为沿 cause 链判断，断网时显示「网络连接失败」而不是「请求结果不明」，状态卡文案补「请求没有发出，本次没有产生费用」。

## 文档

`docs/ai-assistant.md` 同步：工具行名称与参数展示、模型写错工具名的处理、来源预览沿用保存坐标、重读失败时跳转不可用并说明原因、读取失败的历史徽标。`docs/adr/0006-koog-agent-runtime.md` 记录流式路径自实现的原因、边界与回退条件。

## 未做的事

未执行 git commit。`__list_directory` 名称改写只在离线测试中覆盖（无法让真实模型按需写错名字）。匿名楼层仍无样本。
