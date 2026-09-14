# 06 — 内置 skills 与快捷操作实现报告

日期：2026-09-13。Status：implemented。未执行 git commit，保留工作区原有改动。

## 实现与验收对应

- 内置十份 `SKILL.md` 和各自的 `references/example.md`，覆盖讨论概览、事实核查、批判性思考、梳理分歧、补充背景、查找前情、检查各方论证，以及倾向依据、相反表述、观点变化。个人分析三项保留纯文本、时间、反例与敏感属性边界，其入口与进一步完善仍属于原票明确指定的后续范围。
- 启动和使用时释放资源到 `<filesDir>/ai-skills/<versionCode>-<skillsVersion>/`。先完整写入临时目录和完成标记，再替换目标目录并清理旧版；失败保留已完成的旧版。技能版本在每轮消息 JSON 与会话工作记录中保存，旧消息缺少新增字段时使用默认值，无须改变 Room schema。ChatMemory 或检查点恢复后，请求前刷新目录提示，避免引用已清理的旧路径。
- 使用 Koog `discoverSkills`、`generateSkillsPrompt(XML)`、`ListDirectoryTool` 与 `ReadFileTool`。目录只含名称、用途与定位路径，首个真实 HTTP 请求体不含技能正文或示例；工具读取后正文才进入后续模型上下文。Koog Android artifact 不提供 `JVMFileSystemProvider`，通过其 `FileSystemProvider.ReadOnly` 接口提供 Android 文件适配，未自建技能读取工具。文件访问经过规范路径过滤，目录越界与外部符号链接均拒绝。
- 没有注册脚本、命令执行或写文件工具，无须占位执行工具。注册表单测及 Android 测试断言只有框架的两项只读文件工具。模型沿用通用工具循环，自主选择技能和读取顺序；首次概览使用讨论概览，快捷操作携带指定 skill 任务。无搜索工具时，系统规则和事实核查技能均要求说明「外部核查未做」。
- 主题详情显示事实核查、批判性思考、梳理分歧、补充背景；楼层显示事实核查、批判性思考、查找前情。菜单名称与说明按 `Main.dc.html`，空态文案和键盘行为按 `Components.dc.html`。支持末尾 `/` 触发、名称前缀筛选、按钮打开、↑↓ 高亮、Enter/Tab 选择、Esc 关闭并抑制至文字变化、Shift+Enter 换行；输入法组合中不处理键盘发送。菜单在输入区上方以浮层显示，180ms 入场，不挤占半屏聊天区。
- 胶囊横向滚动，生成中禁用；选择在同一 conversationId 续聊，重复点击不重复派发，用户气泡带绘制的闪电图标。完成回答后显示「继续问」建议。技能工具行显示中文名称与读取状态，不在行内暴露应用私有路径。
- 真机验收尚未执行：本机 `adb devices` 只列出 Pixel_8 Android 17 模拟器，没有连接实体手机。对应交互已实现并通过模拟器测试，但不把模拟器结果计作真机验收；票内保留该项未勾选。

## 验证结果

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease --offline`：**BUILD SUCCESSFUL**，107 个任务；debug APK、完整离线单测、release APK 与 R8 均通过。JUnit 汇总 **1187 tests，0 failures，0 errors，4 skipped**。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest --offline -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.QuickActionComposerTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest,com.chasel.ng2n.data.ai.BuiltinSkillsAndroidTest`：**BUILD SUCCESSFUL**；Pixel_8 Android 17 模拟器 **6 tests，0 failures，0 errors，0 skipped**。覆盖筛选、空结果、Esc/Tab/方向键/Enter、禁用、入口切换、原主题面板交互，以及 Android 包内资源发现与框架文件读取。
- 新增离线用例验证升级失败保留旧版、成功升级清理、逐运行版本保留与旧提示替换、首个 HTTP 请求体不含正文、模型通过框架工具读取后正文进入后续请求、路径越界与符号链接拒绝、无脚本工具，以及快捷操作续聊与重复点击去重。
- 已查看筛选菜单和空结果截图，文字、行高和暗色配色正常。证据位于 `.scratch/ai-assistant/reports/06-evidence/`：`build.log`、`device.log`、`ai-quick-filtered.png`、`ai-quick-empty.png`。
- `git diff --check` 通过。期间发现并修复 Android artifact 缺少 JVM 文件适配、旧目录恢复和工具结果断言问题；一次设备属性读取超时后，单独重跑设备测试通过。Gradle 缓存访问通过环境审批后执行；没有调用真实付费模型或执行论坛写操作。回答示例为虚构评审样本，不代表真实模型质量评测。

## 回答示例（虚构资料，供体验评审）

- 讨论概览：已读第一页和热门回复。讨论集中于通勤成本；两方都重视可靠性，但对时间与费用的权重不同 [[s1]]。未读其他页。
- 事实核查：“今年客流下降三成”尚无统计来源 [[s1]]。这只是发言者的说法；外部核查未做，不能确认比例。
- 批判性思考：用通勤耗时比较方案是合理的 [[s1]]，但单次经历不足以代表全年；还需考虑路线和高峰时段。
- 梳理分歧：甲重视时间，乙重视费用 [[s1]] [[s2]]；双方都要求稳定到达，分歧主要在权重，而非对同一事实的否认。
- 补充背景：这里的“门到门时间”包含步行与换乘，不只是车内时间。主楼只给出车程 [[s1]]，所以比较仍不完整。
- 查找前情：这条回复回应的是主楼的换乘条件 [[s1]] [[s2]]。更早引用不可访问，前情未完整恢复。
- 检查各方论证：甲提供一次迟到经历，能说明风险存在，不能估计频率；乙给出的平均时间也不能证明不会迟到 [[s1]] [[s2]]。
- 查看倾向依据：所读样本中两次明确偏好公共交通，分别在五月和七月 [[s1]] [[s2]]；不足以认定所有出行情境都如此。
- 查找相反表述：另一次发言在携带行李时偏好打车 [[s2]]，与日常通勤条件不同，不能直接视为自相矛盾。
- 查看观点变化：五月更重视费用，七月强调可靠性 [[s1]] [[s2]]；只有两条样本，尚不能断言发生稳定转变。

## 改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/BuiltinSkills.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/RoomAgentPersistence.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/StorageBootstrap.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposer.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/ToolCallRows.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/BuiltinSkillsTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/QuickActionsTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/ai/BuiltinSkillsAndroidTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/QuickActionComposerTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheetTest.kt`
- `docs/ai-assistant.md`
- `app/src/main/assets/ai-skills/arguments/SKILL.md`
- `app/src/main/assets/ai-skills/arguments/references/example.md`
- `app/src/main/assets/ai-skills/background/SKILL.md`
- `app/src/main/assets/ai-skills/background/references/example.md`
- `app/src/main/assets/ai-skills/critical-thinking/SKILL.md`
- `app/src/main/assets/ai-skills/critical-thinking/references/example.md`
- `app/src/main/assets/ai-skills/disagreements/SKILL.md`
- `app/src/main/assets/ai-skills/disagreements/references/example.md`
- `app/src/main/assets/ai-skills/discussion-overview/SKILL.md`
- `app/src/main/assets/ai-skills/discussion-overview/references/example.md`
- `app/src/main/assets/ai-skills/fact-check/SKILL.md`
- `app/src/main/assets/ai-skills/fact-check/references/example.md`
- `app/src/main/assets/ai-skills/persona-changes/SKILL.md`
- `app/src/main/assets/ai-skills/persona-changes/references/example.md`
- `app/src/main/assets/ai-skills/persona-counterexamples/SKILL.md`
- `app/src/main/assets/ai-skills/persona-counterexamples/references/example.md`
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md`
- `app/src/main/assets/ai-skills/persona-evidence/references/example.md`
- `app/src/main/assets/ai-skills/prior-context/SKILL.md`
- `app/src/main/assets/ai-skills/prior-context/references/example.md`
- `.scratch/ai-assistant/issues/06-builtin-skills-and-quick-actions.md`
- `.scratch/ai-assistant/reports/06-impl.md`
- `.scratch/ai-assistant/reports/06-evidence/build.log`
- `.scratch/ai-assistant/reports/06-evidence/device.log`
- `.scratch/ai-assistant/reports/06-evidence/ai-quick-filtered.png`
- `.scratch/ai-assistant/reports/06-evidence/ai-quick-empty.png`
