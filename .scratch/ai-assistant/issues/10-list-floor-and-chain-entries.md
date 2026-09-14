# 10 — 列表概览、列表单条主题、单楼与回复链入口

**What to build:** 补齐 Entries 设计稿中除个人分析外的入口。版块列表顶栏的 AI 按钮发起列表概览：保留当前排序与筛选，取已加载列表靠前的最多 300 个主题，只用标题、作者、时间与回复数，阅读范围卡显示已加载数、计入数、屏蔽规则未计入，并说明正文与楼层不默认读取；快捷操作为深入某个话题、梳理讨论分歧、补充背景。主题行右侧的 AI 按钮发起单条主题分析：第一页、热门回复与最多一张图片，快捷操作同主题详情。回复链视图的 AI 按钮发起回复链分析：主楼正文与当前回复链全部发言，阅读范围显示上游 / 当前 / 下游楼层，不加入第一页其他楼层；快捷操作为梳理分歧、检查各方论证、事实核查。单个楼层入口在票 04 已有，本票补齐其快捷操作与「查找前情」。所有入口的对话都进入历史页并带正确分类。

**Blocked by:** 05 论坛读取工具与工具调用行、06 内置 skills 与快捷操作

**Status:** implemented

- [x] 默认上下文单测：列表快照按当前排序取前 300、不足时按实际数；回复链范围只含主楼与链内发言；被屏蔽主题与楼层不计入并计数
- [ ] 真机：四个入口各发起一次，阅读范围卡与设计稿的行与文案一致，快捷胶囊按入口不同
- [x] 列表概览对话中追问某个主题时，模型能用工具读取该主题正文
- [x] 历史页筛选「列表 / 楼层 / 回复链」各能命中对应对话

## Comments

### 2026-09-14 实现记录

已实现列表概览、列表单条主题、单楼与回复链入口，复用现有 Koog 通用工具循环、会话、预算、来源预览与历史存储；没有新增依赖、数据库迁移或 git commit。保留工作区中此前已有的改动。

- 版块列表、24 小时热帖和精华区顶栏新增 AI 入口。点击时捕获列表顺序和筛选规则，过滤屏蔽、不可访问主题及非主题跳转行，取靠前最多 300 个；不足时使用实际数。默认只传标题、匿名处理后的作者、时间和回复数，不读取正文、楼层或图片。范围卡按 Entries 显示「当前列表 / 主题摘要 / 屏蔽规则」及已加载、计入、排序与筛选明细，并显示按需读取正文的说明。
- 主题行右侧独立 AI 按钮不触发主题行导航。首次读取第一页、热门回复，最多带入一张图片，显示实际楼层、热门回复和图片计数及图片选择原因。
- 回复链入口捕获当前视图的全部节点与已加载页，补读主楼及缺失的链内发言；不混入第一页其他楼层或无关热门回复。范围卡显示主楼、链内计入数、上游 / 当前 / 下游和图片，过滤与缺失单独计数。链内初始图片最多一张。
- 列表快捷操作为「深入某个话题 / 梳理讨论分歧 / 补充背景」，回复链为「梳理分歧 / 检查各方论证 / 事实核查」，单楼保留「事实核查 / 批判性思考 / 查找前情」。均在原对话通过现有 skills 与论坛工具继续执行。
- 历史保存正确入口分类、范围行及摘要来源类型；恢复后仍使用相应快捷操作。列表内部入口 tid=0 不作为论坛主题发起读取，真实来源保留各主题 tid。列表缺 Key 时先保存已准备的快照，设置修正后可手动继续。摘要引用显示「主题」，点击按当前账号重新读取主楼预览，不冒充已读正文。

### 验收与验证

1. 默认上下文：新增单测覆盖降序快照、412→300、过滤后截取、不足与空列表、匿名去假名、回复正文不混入摘要、跨页回复链、无关第一页/热门回复排除、链内图片选择、屏蔽去重及缺失计数，全部通过。
2. 界面：Pixel_8 AVD、Android 17 / API 37 的 4 项新增 Compose 仪器测试通过，覆盖四种入口面板、范围展开/收起、差异化快捷操作，以及真实 TopicRow AI 点击不误触导航。另有 4 项共享聊天面板回归通过，覆盖半屏/全屏/收起、停止、引用、自动滚动、缺 Key、保存失败与暗色。已逐张查看四份截图，范围行与文案、操作胶囊无截断或重叠。
3. 列表追问正文：新增 Koog runtime 离线测试先生成只含摘要的概览，确认零论坛正文读取；追问后由假模型 executor 发出 read_topic_page 工具调用，真实通用循环执行论坛工具、回传正文并生成引用，验证来源由 summary 扩展为 floor，全部通过。这验证实际工具循环接线，不代表真实 DeepSeek 的选择与回答质量已验证。
4. 历史分类：新增 ViewModel + 内存持久化测试实际创建列表、楼层、回复链会话，使用历史页同一筛选函数逐类命中；恢复列表后范围、summary 来源和快捷操作保留，缺 Key 保存后续聊也通过。

构建环境为 JDK 17，使用本机已有 Android SDK 与 Gradle 缓存，全部命令带 --offline：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：BUILD SUCCESSFUL。debug、release（含 R8 / lintVital）通过；JUnit XML 合计 1232 项，0 failures、0 errors、5 skipped（默认关闭的联网测试）。本次新增 8 项 JVM 测试通过。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiEntryPanelsTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest`：BUILD SUCCESSFUL，8/8 通过，0 失败、0 跳过。
- `git diff --check`：通过。
- 修复验证期间发现的测试构造缺少 authorKey，以及新增计数改写旧「只看用户」范围标签的问题；最终完整构建和测试均已复跑通过。

验证限制：`adb devices` 仅发现 emulator-5554，没有物理 Android 设备，因此票中的「真机四个入口各发起一次」尚未验证，该复选框保留未勾选。设备测试使用离线资料与面板组件，不将其表述为真机实网端到端验收。本次没有调用真实 DeepSeek、没有发送付费模型请求，也没有执行 NGA 写操作。

证据位于 `.scratch/ai-assistant/reports/10-evidence/`：`offline-build.log`、`unit-test-summary.txt`、`android-tests.log`、`android-results.xml`，以及 `list.png`、`list-topic.png`、`floor.png`、`chain.png`。

### 改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/EntryContext.kt`（新增）
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReader.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiMarkdown.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposer.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/EntryAiSheet.kt`（新增）
- `app/src/main/kotlin/com/chasel/ng2n/ui/board/BoardScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/board/SimpleListScreens.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/board/TopicRow.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/ChainScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/ChainViewModel.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/EntryContextTest.kt`（新增）
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiEntryPanelsTest.kt`（新增）
- `docs/ai-assistant.md`
- `.scratch/ai-assistant/issues/10-list-floor-and-chain-entries.md`
- `.scratch/ai-assistant/reports/10-impl.md`（新增）
- `.scratch/ai-assistant/reports/10-evidence/`（新增验证日志、结果与截图）

### 2026-09-14 第一轮评审修复

接受 `10-review-1.md` 中的两条 P2 意见，均已修复，无异议项。没有 git commit。

1. **历史展示数据不再作为模型工作上下文。** `TopicAiViewModel` 新增独立的内存 `modelContext`，只接受完成准备的上下文或持久化的 `context/current`；`load()` 构造的来源坐标与范围卡仍用于显示，不填入该字段。切换、新建和删除会话会清理相应内存状态，工具返回的新来源同步更新工作上下文。列表／回复链恢复时若没有已保存上下文，也没有可重建入口的内存快照，显示「入口范围不可恢复，请从原内容重新发起分析」，不调用模型，也不把空的展示资料写成工作上下文。

   新增列表、回复链各一项恢复测试：在执行队列中已保存会话但尚未准备资料时停止，使用新 ViewModel 实例加载历史，连续两次点击继续。断言提示不可恢复、回答标记未完成、论坛读取与模型调用均为零、没有请求预留，也没有新增 `context/current`。原有“已保存列表快照恢复”“缺 Key 后恢复”及历史分类测试继续通过。

2. **新入口来源预览接入账号管理。** `EntryAiSheet` 显式传入 `onAccounts = { nav.push(Accounts) }`，覆盖版块列表、热帖、精华区和回复链共用包装器。新增仪器测试使用真实 `EntryAiSheet → TopicAiSheet → ViewModel.preview → AiSourcePreviewReader` 调用路径，论坛 READ 策略返回无权限；点击回答引用、确认「当前账号无法查看」、点击「切换账号」，验证导航目标为 `Accounts`、预览关闭且已有回答仍显示。测试禁止实际网络、模型调用与 Key 访问，没有改写用户账号设置。

最终验证：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`：最终代码复跑 BUILD SUCCESSFUL，debug、release（含 R8 / lintVital）通过；JUnit XML 合计 1234 项，0 failures、0 errors、5 skipped。本轮新增的 2 项 JVM 恢复测试通过。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiEntryPanelsTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest,com.chasel.ng2n.ui.ai.AiSourcePreviewSheetTest`：Pixel_8 AVD / Android 17，11/11 通过，0 失败、0 跳过；包括新增的包装器账号导航测试。
- `git diff --check`：通过。
- 仍为离线与模拟器验证，没有调用付费模型或执行 NGA 写操作；上一轮所述真机四入口端到端验收限制仍然成立。

证据目录：`.scratch/ai-assistant/reports/10-evidence/review-1/`，包含 `offline-build.log`、`unit-test-summary.txt`、`viewmodel-results.xml`、`android-tests.log`、`android-results.xml`。

本轮最新改动文件清单（在上一轮清单基础上的修复）：

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/EntryAiSheet.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiEntryPanelsTest.kt`
- `docs/ai-assistant.md`
- `.scratch/ai-assistant/reports/10-impl.md`
- `.scratch/ai-assistant/issues/10-list-floor-and-chain-entries.md`
- `.scratch/ai-assistant/reports/10-evidence/review-1/`（本轮验证证据）
