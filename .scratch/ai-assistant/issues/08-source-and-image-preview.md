# 08 — 来源预览与图片预览

**What to build:** 点击回答中的来源标签或页脚来源列表，底部弹出预览面板：论坛原文每次经 READ 流程重新读取，显示「刚刚重新读取」、序号徽标、作者与字数、正文，主按钮「跳到 N 楼」收起面板并高亮楼层；读不到时显示「已删除或不可访问」并说明旧引用保持原样、需要原句时会重新读取，主按钮禁用；当前账号无权限时显示「当前账号无法查看」，主按钮「切换账号」。带图片的楼层在预览中标注「图 1 已带入上下文，另 N 张未读取」，可打开图片查看。返回后对话与聊天位置保持不变。站外来源变体由票 12 接入。

**Blocked by:** 05 论坛读取工具与工具调用行、07 对话持久化、历史页与中断恢复

**Status:** implemented

- [ ] 真机：三种论坛变体各能触发（正常、已删除、无权限）；跳楼层后高亮并保留对话位置
- [x] 预览不读取 Room 中的任何正文，只用引用坐标重新读取（单测断言）
- [x] 历史对话中的引用同样可预览；被清理的帖子显示已删除变体，条目徽标变为「来源已删除」
- [x] 图片预览显示带入 / 未读取状态；个人分析对话不出现图片预览入口

## Comments

### 2026-09-14 实现记录

已实现来源标签及回答页脚来源列表的底部预览，主题面板与历史全屏聊天共用。界面按 Main.dc.html 的论坛预览结构实现来源序号、重新读取状态、作者、字数、BBCode 正文、来源主题页码及返回／跳楼操作，沿用项目明暗主题与阴影。

验收落实：

- 正常、已删除或不可访问、当前账号无权限三种变体均已实现，并在模拟器使用确定性数据触发。正常来源确认跳转后高亮目标楼层，历史和跨主题跳转同样支持；引用点击不直接收起聊天，查看来源后保留聊天位置。网络失败可重新读取，屏蔽来源不显示正文。
- 预览只接收 tid/pid/page 坐标，每次经 NGA READ 重新读取；请求固定当前账号，禁止 TopicCache 回退，不经过内存主题页缓存或 Room 模型工作正文。单测断言请求属性、重复读取次数、缓存拒绝和工作正文访问禁令。
- 历史引用重新读取；来源清理后持久化「来源已删除」条目徽标，旧回答、坐标和内容哈希不变。无权限提供「切换账号」，删除状态禁用跳转，并保留原引用楼层文案。读取期间账号变化的结果不展示。
- 图片按原文顺序标注「已带入上下文／未读取」，实际读图状态随会话元数据保存，工具结果恢复保留读取标记。使用现有图片查看器支持翻图，当前图片的状态随翻页更新；关闭后回到原文预览，查看图片不触发模型调用。个人分析历史不提供图片预览入口。

验证结果：

- JDK 17、现有 Android SDK，`./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：通过。共 1192 项单测，0 failures、0 errors；4 项已有 NGA 联网冒烟按离线条件跳过。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiSourcePreviewSheetTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest`：Pixel_8 API 37 模拟器 5 项通过，覆盖三种预览变体、图片与个人分析入口限制、聊天全屏／收起、来源点击保持面板、停止、输入及滚动跟随。
- 最终文案与截图调整后再次运行 assembleDebug、testDebugUnitTest 和 AiSourcePreviewSheetTest：全部通过，最终 Gradle 日志为 `reports/08-evidence/final-gradle.log`，聊天回归日志为 `reports/08-evidence/chat-regression.log`。
- 已查看 normal.png、normal-dark.png、deleted.png、permission.png，确认明暗主题、正文卡、图片状态、异常说明与按钮布局；截图位于 `reports/08-evidence/`。
- `git diff --check`：通过。票 08 未要求 release 验证，本次未运行 assembleRelease。没有调用付费模型，没有论坛写操作，没有 git commit。
- 设备限制：`adb devices` 仅有 emulator-5554，没有连接真机。因此票中「真机」验收项保留未勾选；模拟器离线验证不能视为真机／真实 NGA 权限与删除场景的实测。实现范围已落地，真机验收仍需连接设备后完成。

改动文件清单（仅本次，不包含进入任务前已有的工作区改动）：

- `app/src/main/kotlin/com/chasel/ng2n/core/api/TopicDetail.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/net/NgaRequest.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/net/strategies/TopicCache.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReader.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiHistoryScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/image/ImageViewerScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/nav/Keys.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicViewModel.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReaderTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheetTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheetTest.kt`
- `docs/ai-assistant.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/issues/08-source-and-image-preview.md`
- `.scratch/ai-assistant/reports/08-impl.md`
- `.scratch/ai-assistant/reports/08-evidence/normal.png`
- `.scratch/ai-assistant/reports/08-evidence/normal-dark.png`
- `.scratch/ai-assistant/reports/08-evidence/deleted.png`
- `.scratch/ai-assistant/reports/08-evidence/permission.png`
- `.scratch/ai-assistant/reports/08-evidence/final-gradle.log`
- `.scratch/ai-assistant/reports/08-evidence/chat-regression.log`
