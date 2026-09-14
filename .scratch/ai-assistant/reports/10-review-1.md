VERDICT: FAIL

评审范围：仅票 10 实现清单中的 diff 与未跟踪文件；相关既有实现只用于确认调用契约。未修改代码。

1. **[P2] 历史恢复会把尚未准备的入口范围当成可用上下文，继续发起模型请求。**

   位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt:303–323`，关联 `load()` 的第 118–124 行。

   列表或回复链在排队、读取官方屏蔽规则或下载初始图片期间停止、失败或被结束进程时，第 293 行已保存会话，但尚未保存 `context/current`。进程重建后，`load()` 无条件用来源元数据构造非 null 的 `TopicContext`；这里读取工作上下文失败后又回退到该展示对象。因此第 321 行的 `context == null` 检查不会生效，且恢复后的 `prepareEntry` 为 null，点击“继续”会以空资料进入模型运行。列表原主题坐标、回复链节点及原始范围都没有恢复，仍可能消耗额度。现有恢复测试只覆盖初始上下文已经保存的路径，未覆盖这个中断窗口。

   修复建议：明确区分供历史展示的来源元数据与已准备的模型工作上下文。列表／回复链缺少已保存上下文且不能重建原入口时，应在模型调用前显示现有“入口范围不可恢复”状态；如需支持继续，则持久化足以重建原快照的入口信息。补充“初始准备完成前中断 → 新实例加载历史 → 继续”的测试，断言不以空资料调用模型。

2. **[P2] 新入口的来源预览没有接入“切换账号”操作。**

   位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/EntryAiSheet.kt:10–11`。

   该包装器用于版块列表、热帖、精华区和回复链，但调用 `TopicAiSheet` 时没有传入 `onAccounts`，实际采用默认空回调。引用重读返回 `permission_denied` 时，预览会显示可点击的“切换账号”按钮；点击只关闭预览，不打开账号管理，无法按界面提示恢复访问。主题详情已有正确接线，新增入口遗漏了相同能力，也与 `docs/ai-assistant.md` 中的来源恢复行为不一致。

   修复建议：传入 `onAccounts = { nav.push(com.chasel.ng2n.ui.Accounts) }`，并补充新入口包装器在无权限预览中点击按钮会导航到账号管理的验证。

核实记录：

- 已阅读 `CLAUDE.md`、票据、实现报告、`koog-best-practices.md` 与 `cost-and-recovery.md`，核对清单内核心上下文、入口接线、范围卡、快捷操作、引用、历史恢复、测试及文档。新增核心逻辑未引入 Android API；论坛读取复用显式 READ 请求；本票没有新增 WRITE、独立模型重试循环或依赖版本。未发现另外需要修改的注释或依赖目录问题。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest :app:assembleDebug` 成功；首次结果为 UP-TO-DATE，随后执行 `./gradlew --offline :app:testDebugUnitTest --rerun` 强制重跑成功。JUnit XML 合计 1232 项，0 failures、0 errors、5 skipped，包含列表排序／300 上限／过滤、回复链范围、列表追问正文的 Koog 工具循环和历史分类测试。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.AiEntryPanelsTest,com.chasel.ng2n.ui.ai.TopicAiSheetTest` 成功：Pixel_8 AVD / Android 17，8/8 通过。
- 清单中已跟踪文件的 `git diff --check` 通过。Gradle 与 adb 最初受沙箱限制，获准重跑后完成；没有剩余权限阻塞。
- `adb devices` 只有 `emulator-5554`。票中真机四入口验收仍未完成；组件仪器测试不能代替真机入口端到端验收。本次没有调用付费模型或执行 NGA 写操作。
