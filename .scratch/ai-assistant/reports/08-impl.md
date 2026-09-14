# 08 — 来源预览与图片预览实现报告

Status: implemented

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

### 2026-09-14 评审第一轮修复

评审意见 `08-review-1.md` 的 P2 问题成立，已修复，无不同意项。本节更新上一轮对来源定位与旧引用预览的说明。

- 来源增加可空的 `part` 定位信息。新父楼来源明确标为 `floor`；贴条有 pid 时按 pid 定位，无 pid 时以作者 ID、发表时间、正文和附件地址计算 SHA-256 指纹，避免使用删除后会变化的顺序索引。工具来源去重也比较定位信息，防止不同贴条被合并。
- 定位信息通过现有 Room 会话的 `entryJson.sourceParts` 映射持久化并恢复，不增加正文快照、不读取模型工作正文、不修改数据库表结构。旧记录没有该字段时保留未知状态，保存后不会擅自改成父楼来源。
- 精确贴条引用只展示匹配贴条的作者、正文和图片，跳转仍落在所属父楼。父楼存在但该贴条已删除时返回不可访问并更新历史徽标；贴条被屏蔽时显示被屏蔽，不借用父楼内容。
- 父楼预览保留当前贴条，父楼与每条贴条分别应用当前屏蔽规则。旧引用无法精确定位时显示「当前范围已重读」及明确的范围说明，列出父楼和当前贴条，不能声称已找回原引用；没有当前贴条时也明确说明。
- 无 pid 的贴条没有可用于确认编辑前后身份的服务端标识，因此正文编辑后指纹不匹配、或出现多个相同匹配时，保守显示不可访问，不猜测对应的父楼或邻近贴条。有 pid 的贴条允许重新读取编辑后的正文。

本轮验证：

- JDK 17，`./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：通过；1196 项单测，0 failures、0 errors，4 项 NGA 联网冒烟按离线配置跳过。测试任务实际执行，非 UP-TO-DATE。
- 新增／扩展单测覆盖父楼与贴条正文及图片不同、无 pid 贴条重排、贴条单独删除、贴条单独屏蔽、父楼屏蔽时独立处理贴条、有 pid 贴条编辑、旧引用无贴条、定位信息保存与历史恢复、恢复后贴条删除、旧回答及哈希保持不变，以及禁止读取 Room 工作正文。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiSourcePreviewSheetTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest`：Pixel_8 模拟器 6 项通过。新增界面用例通过实际 `buildTopicContext` 与 `AiSourcePreviewReader` 生成数据，验证贴条作者／正文、旧引用范围说明、父楼和贴条分别展示、被屏蔽正文不泄露；原有来源及聊天回归通过。
- 已查看 `08-evidence/review-1/note.png` 和 `legacy-notes.png`，确认独立贴条和旧引用范围界面；最终合并构建／单测／设备验证日志在 `08-evidence/review-1/gradle.log`。
- `git diff --check`：通过。本轮没有 git commit，没有付费模型调用或论坛写操作。真机及真实 NGA 删除／权限场景仍未验证，沿用上轮报告的设备限制；模拟器测试不替代该验收项。

本轮最新改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReader.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheet.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/TopicContextTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReaderTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheetTest.kt`
- `docs/ai-assistant.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `.scratch/ai-assistant/reports/08-impl.md`
- `.scratch/ai-assistant/reports/08-evidence/review-1/gradle.log`
- `.scratch/ai-assistant/reports/08-evidence/review-1/note.png`
- `.scratch/ai-assistant/reports/08-evidence/review-1/legacy-notes.png`
