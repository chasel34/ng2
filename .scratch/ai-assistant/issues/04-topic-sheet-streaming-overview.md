# 04 — 主题详情入口 → 半屏面板 → 流式概览

**What to build:** 用户在主题详情点顶栏或楼层下方的 AI 按钮，半屏面板升起，阅读范围卡逐项显示「第 1 页 / 热门回复 / 当前页 / 图片 1 张」的读取进度（当前页为第一页时不重复计入；主楼无图时取当前页第一张），随后出现「正在思考」到「已思考 N 秒」，回答以流式 Markdown 逐步显示，行内引用渲染为来源标签，点击标签把面板收起并高亮对应楼层。面板支持拖动吸附半屏 / 全屏 / 收起胶囊，收起后请求继续，胶囊显示当前状态。用户可停止（保留已生成文字并标「已停止」、提供重新生成），可在输入框继续追问，追问在同一对话内进行。本票不接入论坛读取工具、不持久化、不做快捷操作菜单；对话只存活在内存与当前面板。

实现基线是 Koog 的 `chatAgentStrategy()` 图，流式经 Koog 的 writeSession 流式请求组成节点；进度经 EventHandler 事件映射到界面状态。默认上下文由 App 规则组装：套用本地屏蔽规则与官方屏蔽词并计数，匿名作者以「匿名用户」送入，不带假名；图片经 Koog 多模态 API 送入，不受「仅 Wi-Fi 加载图片」限制。楼层入口的默认范围为主楼正文与选中楼层，最多一张图。

本票同时定稿两项契约并写入技术方案：模型输出中的引用标记语法（指向 sourceId 的短标记，未知 id 丢弃）；回答的 Markdown 渲染方案（选库或自研，记入票 01 的 ADR）。新增 token green-c / accent-c / danger-c 与五级阴影进入主题。

**Blocked by:** 01 Koog 接入与 ADR、02 抽取主题原始读取层、03 AI 设置页与 Key 存储

**Status:** implemented

- [ ] 真机：从主题详情与单个楼层发起，看到阅读范围逐项完成、思考态、流式文字与来源标签；点标签跳到楼层并高亮
- [x] 半屏 / 全屏 / 收起三态吸附阈值与设计稿一致（松手位置决定），收起后请求继续并在胶囊显示状态，点胶囊回半屏
- [x] 停止后取消当前网络请求，已生成文字保留并标未完成；再次发起或追问不复用被停止的请求
- [x] 默认上下文单测：第一页与当前页去重、热门回复带入、单张图片选取规则、屏蔽楼层不进入上下文并计数、匿名作者不带假名
- [x] 引用标记解析单测：合法标记渲染为标签、未知 id 丢弃、流式中途的半截标记不闪现
- [x] 未配置 Key 时面板显示状态卡并指向设置页，不发出模型请求
- [x] 技术方案与 Koog 最佳实践文档记录引用契约与渲染选型

## Comments

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
