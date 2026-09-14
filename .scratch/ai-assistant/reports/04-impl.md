# 04 — 主题面板与流式概览实现报告

### 2026-09-13 实现记录

已实现主题顶栏与楼层 AI 入口、默认上下文准备、半屏/全屏/胶囊面板、阅读范围进度、思考计时、流式 Markdown 与来源标签、来源跳楼高亮、停止保留草稿、重新生成及同会话追问。临时收起不会停止请求；新内容入口新建对话，停止后的请求不复用，迟到更新不能覆盖当前状态。会话仅保存在主题 ViewModel 内存中；本票未注册论坛工具，未加入持久化与快捷操作菜单。

上下文复用 TopicRepository：第一页与当前页去重、热门回复带入、单楼只选主楼和目标发言；应用本地/官方屏蔽规则并计数，匿名用户不携带假名，贴条没有独立坐标时引用所属楼层。首次最多一张图片，按主楼/当前页或选中发言规则选择，独立于 Wi-Fi 图片设置；有界下载、解码后使用 Koog 二进制 Image API。读取失败不会静默移除图片或跳过屏蔽规则继续请求模型。

流式图以 Koog chat 图的通用工具循环为基线，使用 writeSession 流式节点与 EventHandler；修正预置策略强制首轮工具调用的约束，允许无工具的主题概览直接回答。成功轮次保留完整 assistant/reasoning 协议消息；残缺输出只作为未完成草稿。引用契约固定为 `[[sN]]`，未知 ID 丢弃，半截标记不显示。选用自研 Compose Markdown 与 InlineTextContent 来源标签，增加 green-c/accent-c/danger-c 和五级阴影；契约和选型已同步技术方案、Koog 最佳实践与 ADR-0006。

#### 改动文件清单

- `README.md`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/AlbumImages.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/Citations.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiImageReader.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiMarkdown.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/Album.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/theme/AiShadows.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/theme/Tokens.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/FloorCard.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicDeps.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicViewModel.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/CitationsTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/TopicContextTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheetTest.kt`
- `docs/ai-assistant.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/issues/04-topic-sheet-streaming-overview.md`
- `.scratch/ai-assistant/reports/04-impl.md`
- `.scratch/ai-assistant/reports/04-build.log`
- `.scratch/ai-assistant/reports/04-ui.log`
- `.scratch/ai-assistant/reports/04-screenshots/full-light.png`
- `.scratch/ai-assistant/reports/04-screenshots/full-dark.png`
- `.scratch/ai-assistant/reports/04-screenshots/missing-key.png`

#### 验证结果

使用 JDK 17 与本机 Android SDK；显式关闭 NGA_INTEGRATION/NGA_WRITE_SMOKE，Gradle 使用 `--offline`。

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.TopicAiSheetTest
```

- `:app:assembleDebug`：通过。
- `:app:testDebugUnitTest`：1151 项，0 失败，0 错误，4 项按既有配置跳过。
- `:app:assembleRelease`：通过，包含 R8 与 lintVital；无新增 keep/dontwarn 或依赖。
- `:app:connectedDebugAndroidTest`：Pixel 8 / Android 17 模拟器，2 项通过；覆盖全屏/半屏/收起恢复、拖动收起、来源点击、停止状态、追问输入、设置入口、深浅主题和截图。
- 上下文单测覆盖页码和热门回复去重、单图优先级、单楼范围、屏蔽计数、匿名去假名、折叠内容/附件与贴条。
- 引用单测覆盖合法标签、未知标记丢弃、每一个流式半截前缀隐藏及三态精确边界。
- Koog 假执行器与本机 MockWebServer 覆盖 EventHandler 流式增量、真实 DeepSeek SSE 解码、单张二进制图片序列化、reasoning 续聊、无论坛 Cookie、取消、不自动重启、截断工具参数和长度上限。
- ViewModel 单测覆盖无 Key 时零论坛/模型请求、收起继续、停止取消、迟到响应丢弃、重试新请求、同会话追问去重；主题 ViewModel 测试覆盖目标页加载后高亮和目标已删除时不高亮邻楼。
- `git diff --check`：通过。截图已逐张检查。未执行 git commit，保留原有未提交改动。

#### 验证边界

当前只连接到模拟器，没有真机；票第一项中的真机人工验收未执行，因此该复选框保留未勾选。对应功能已实现，并由上述上下文、模型协议、ViewModel 与 Compose 测试分别覆盖。没有调用真实付费 DeepSeek、真实 NGA 读取或任何论坛写操作；离线通过不代表真实模型回答质量、实际费用或真机性能已验证。

### 2026-09-13 评审第一轮修复

接受 `04-review-1.md` 的全部三项意见，均已修复，无不同意项。

1. **筛选页面与选中楼层读取范围**：TopicScreen 改为捕获 TopicViewModel 的完整 `paramsFor(page)`，保留 authorId、pid 与 fav，并将 onlyUser 纳入楼层动作的重建条件。单楼分析按选中 pid 单独定位，主楼始终读取未筛选第一页；只有完整请求参数一致时才复用该页。上下文按发言去重，不再因普通页和筛选页页码相同而省略当前页，界面与模型资料明确注明筛选范围。目标发言缺失时显示“选中楼层 · 未读取”，保留失败状态并阻止模型请求，不再将主楼单独送出并谎报目标已读。
   - 新增实际 TopicViewModel → AI ViewModel 回归：只看用户第一页显示原主题第 74 楼，单楼请求使用 pid=74、保留 fav、不携带 authorId；模型上下文恰为主楼和第 74 楼。顶栏概览同时保留普通第一页与该用户筛选第一页的发言。另测目标不存在时阅读项不完成且零模型调用，以及同页码不同内容的纯上下文合并。
2. **当前页首图顺序**：保留通过屏蔽规则的楼层来源映射，主楼无图时依照 `current.floors` 原始顺序查找首张图片，不再使用第一页/热门回复的来源插入顺序。新增第 25 楼为热门回复、当前页第 21 楼为首个有图发言的复现用例，确认选择 A 图；屏蔽第 21 楼时才选择后续 B 图，并保持正确屏蔽计数。
3. **Markdown 代码文字保真**：围栏内直接保留原始行和缩进，禁用标题、引用、列表及表格转换。行内代码进入字面量模式，不再递归转换强调和链接。来源标记仍统一校验，合法标签可点击，未知标签丢弃。新增 Compose 回归，核对 `# comment`、`> quoted`、`- item`、`* item`、缩进、强调符号、链接和管道表格的原始文字，以及关闭围栏后普通标题/列表恢复渲染；验证代码内来源标签仍能定位来源。用户说明与 ADR 已同步这些行为。

#### 本轮最新改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiMarkdown.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicScreen.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/TopicContextTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiMarkdownTest.kt`
- `docs/ai-assistant.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `.scratch/ai-assistant/reports/04-impl.md`
- `.scratch/ai-assistant/reports/04-review1-build.log`
- `.scratch/ai-assistant/reports/04-review1-ui.log`

#### 本轮验证结果

使用 JDK 17、Android SDK，显式关闭 NGA_INTEGRATION 与 NGA_WRITE_SMOKE，执行：

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiMarkdownTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest
```

- Debug 构建、Release 构建（含 R8/lintVital）与全量离线单测：通过。XML 汇总 **1156 项，0 失败，0 错误，4 跳过**，新增 5 项 JVM 回归全部执行通过。
- Pixel 8 / Android 17 模拟器 Compose 测试：**4 项全部通过**，包含新增的 2 项 Markdown 保真测试和原有 2 项面板交互测试。
- `git diff --check`：通过。未新增依赖或 R8 规则，未执行 git commit。
- 日志保存于 `04-review1-build.log` 与 `04-review1-ui.log`。本轮未访问真实 NGA、未调用付费模型，未进行真机验证；沿用原报告的真机验收边界。


### 2026-09-13 评审第二轮修复

接受 `04-review-2.md` 的唯一问题，已修复，无不同意项。

- **流式回答恢复跟随**：移除仅在滚动开始时判断位置的逻辑。聊天区域通过嵌套滚动回调，在手势及惯性滚动实际消费位移后按距底部距离更新跟随状态；用户上翻时暂停跟随，滚回底部时恢复。内容布局增长及直接 `scrollTo` 不触发此回调，避免程序滚动或新增文字误改跟随状态。自动跟随同时观察跟随状态和滚动是否结束，在用户操作期间暂缓，结束后补齐到底部的滚动。
- **回归覆盖**：新增 `streamingFollowsAgainAfterUserReturnsToBottom`，渲染 50 段回答，通过真实触摸滑动及滚动语义值验证：初始位于底部、追加 12 段继续跟随、上翻后追加文字保持原位置、手动回到底部后再次追加 12 段仍距底部 0 px。没有直接修改 ScrollState，也没有调用模型。
- 用户说明同步自动跟随、上翻暂停及回到底部恢复的行为。

#### 本轮最新改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheetTest.kt`
- `docs/ai-assistant.md`
- `.scratch/ai-assistant/reports/04-impl.md`
- `.scratch/ai-assistant/reports/04-review2-build.log`
- `.scratch/ai-assistant/reports/04-review2-ui.log`

#### 本轮验证结果

使用 JDK 17、Android SDK，显式关闭 NGA_INTEGRATION 与 NGA_WRITE_SMOKE，执行：

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiMarkdownTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest
```

- `:app:assembleDebug`、`:app:assembleRelease`（含 R8/lintVital）：通过。
- `:app:testDebugUnitTest`：实际重新执行，XML 汇总 **1156 项，0 失败，0 错误，4 跳过**。
- Pixel 8 / Android 17 模拟器 Compose 测试：**5 项全部通过**，包含新增滚动恢复回归、原有面板交互和 Markdown 保真测试。
- `git diff --check`：通过；本轮源码、测试和文档亦单独检查无行尾空白。未新增依赖或 R8 规则，未执行 git commit。
- 构建与设备测试日志保存于 `04-review2-build.log`、`04-review2-ui.log`。本轮未访问真实 NGA、未调用付费模型，未进行真机验证；原报告的真机验收边界不变。
