VERDICT: PASS

未发现本票范围内需要修改的实际问题。上轮贴条来源被父楼替代的问题已修复。

评审范围为 08-impl.md 原始清单及第一轮修复清单中的当前 git diff、未跟踪新文件；未修改代码。已对照 CLAUDE.md、票 08 验收项、koog-best-practices.md、cost-and-recovery.md 和本票同步文档。

已核实：

- 来源预览显式走 Operation.READ，固定当前账号并禁用 TopicCache 回退；不读取 Room 模型工作正文，取消继续传播，读取期间账号变化时拒绝展示结果。预览和查看图片不发起模型调用，也不增加外层自动重试。
- 贴条使用独立 part 定位，按 pid 或指纹重新匹配；删除时不替代为父楼或相邻贴条，父楼与贴条分别过滤。定位信息经 sourceParts 保存、恢复；旧引用保留未知定位并明确展示当前范围，不冒称已精确找回原句。历史删除徽标、旧回答和原内容哈希的保留已有单测验证。
- 来源入口、异常按钮、跳楼高亮、图片读取状态与个人分析图片入口限制，以及聊天收起、恢复和滚动跟随的相关实现与测试。查看了 note.png、legacy-notes.png，贴条作者正文和旧引用范围说明与实现一致。
- 本票 core 改动没有 Android 依赖；未新增散落的依赖版本，未发现需要修改的注释约定问题。docs/ai-assistant.md 与 ADR 已同步本轮定位和兼容行为。

本轮实际运行：

- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`：通过，51 项 Gradle 任务实际执行；1196 项单测，0 failures、0 errors，4 项联网冒烟跳过。
- `./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiSourcePreviewSheetTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest`：Pixel_8 模拟器 6 项通过，包含通过实际来源构造和预览读取器生成数据的贴条界面用例。
- 对本票两份改动清单的并集执行 `git diff --check -- <文件清单>`：通过。

验证边界：真机及真实 NGA 删除／权限场景未验证，票中的真机验收项仍未完成；本次 PASS 表示代码评审未发现需修改问题，不表示该真机验收项已完成。未调用付费模型或论坛写接口。
