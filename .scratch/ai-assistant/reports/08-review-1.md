VERDICT: FAIL

评审范围：以 08-impl.md 的文件清单限定本票 git diff 与未跟踪文件；未修改代码。已对照 CLAUDE.md、票 08 验收项、koog-best-practices.md、cost-and-recovery.md 及本票同步文档。

1. **P2 — 贴条来源预览显示父楼正文，丢失被引用的实际内容。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/AiSourcePreviewReader.kt:25-30`，尤其第 27 行 `floor.copy(notes = emptyList())`。
   - 问题：现有来源构造会给每条贴条分配独立 sourceId，但使用父楼 tid/pid/floor 坐标。点击贴条引用时，预览仅按 pid 找到父楼，再主动清空 notes，最终以该贴条的来源序号显示父楼作者、正文和图片，并标为“刚刚重新读取”。即使贴条仍存在，也看不到回答引用的原句；贴条已删除而父楼仍存在时，仍显示成功，不能识别引用来源缺失。历史引用同样受影响。这不是假设的输入：现有 `TopicContextTest.notesWithoutFloorCoordinatesRemainAttachedToTheirParent` 已断言两条贴条共享父楼坐标，该测试在本次重跑中通过。
   - 修复建议：预览必须保留重新读取的贴条并逐条应用屏蔽规则、显示各自作者与内容；为独立贴条来源补充并持久化可重新定位的身份信息，使贴条缺失时返回不可访问，而不是用父楼替代。对无法精确定位的旧引用，应明确展示父楼及其当前贴条范围，不能冒称已找到对应贴条。补充父楼与贴条内容不同、贴条单独删除、贴条被屏蔽及历史恢复的预览测试。

验证记录：

- JDK 17；`./gradlew --offline :app:testDebugUnitTest :app:assembleDebug` 通过，首次命中增量结果。
- 随后执行 `./gradlew --offline :app:testDebugUnitTest --rerun-tasks`，实际重跑 1192 项单测，0 failures、0 errors，4 项联网冒烟跳过。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiSourcePreviewSheetTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest`：Pixel_8 模拟器 5 项通过。现有界面测试使用直接构造的预览数据，未覆盖上述贴条来源映射。
- 对实现清单中的文件运行 `git diff --check -- <清单文件>`，通过。
- 本票 core 改动未引入 Android 类型；原文请求显式 READ、固定账号并禁用 TopicCache；预览不读取 Room 工作正文，未新增模型调用或外层自动重试；未发现本票新增依赖版本散落、注释约定或文档同步方面的其他实际问题。
- 真机与真实 NGA 删除／权限场景未验证；票中真机验收项仍未完成。上述模拟器结果不能替代真机验收。本次未调用付费模型或论坛写接口。
