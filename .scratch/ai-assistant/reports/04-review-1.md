VERDICT: FAIL

评审范围：04 票与 `04-impl.md` 清单中的 diff、未跟踪新文件，以及理解这些改动所必需的调用契约。未修改代码。

## 需要修改的问题

1. **[P2]「只看某用户」下的楼层入口可能完全漏掉选中发言。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicScreen.kt:101`；关联 `ui/ai/TopicAiViewModel.kt:85`。
   - 问题：入口使用筛选后的 `vm.page`，却没有传递 `vm.onlyUser` 对应的 `authorId`，也没有用选中楼层的 pid 定位读取。例如「只看某用户」第一页显示原主题第 74 楼，点击该楼 AI 时传入普通主题第一页与 `selectedPid=74`；ViewModel 复用普通第一页，`buildTopicContext` 找不到 74 楼，最终只将主楼送给模型，界面仍把“选中楼层”标为完成。顶栏入口也会将筛选页码当作普通页码，读取错误的当前页。违反单楼范围与实际阅读范围契约。
   - 核实：调用链静态核对；用当前编译产物构造普通第一页和 `selectedPid=74`，实际来源为 `[0]`，没有目标发言。
   - 修复建议：单楼入口按选中 pid 获取目标发言，或完整传递当前筛选条件；当前页复用和上下文去重不能仅比较页码（筛选第一页不等于普通第一页）。目标不存在时明确显示未读取，不能把该读取项标为完成。补充只看用户模式下跨原始页码的入口测试。

2. **[P2] 热门回复改变了当前页首张图片的选取顺序。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt:72`–`75`。
   - 问题：来源先按“第一页、热门回复、当前页”加入，再在 `sources` 中寻找属于当前页的首个有图来源。若主楼无图、当前页第 21 楼有 A 图、第 25 楼有 B 图且第 25 楼属于热门回复，代码会选 B 图，违反“主楼无图时取当前页第一张”。当前页 pid 集合只检查归属，没有保留当前页顺序。
   - 核实：临时 Java 探针直接调用当前编译产物 `buildTopicContext`，上述输入期望 `a.jpg`，实际返回 `https://example.org/b.jpg`。
   - 修复建议：主楼无图时按 `current.floors` 的原始顺序查找通过屏蔽规则的来源及其首图；来源去重顺序不得决定图片优先级。补充当前页较后楼层同时为热门回复的测试。

3. **[P2] 围栏代码块仍被执行标题、引用和列表转换，改变回答内容。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiMarkdown.kt:52`–`60`。
   - 问题：进入围栏后仍无条件识别 `# `、`> `、`- `、`* ` 并删改前缀，然后才读取 `fenced`。例如围栏中的 Python `# comment` 会显示为标题 `comment`，YAML `- item` 会变成 `• item`。这不是样式差异，而是代码内容被改写，与 ADR 声明的围栏代码支持不符。
   - 核实：逐分支核对渲染逻辑；现有 Compose 测试只覆盖标题、强调和普通列表，没有代码块用例。
   - 修复建议：围栏内保留原始行，禁用块级 Markdown 转换及相应标题/引用样式；保留 ADR 要求的来源标记校验。行内代码也应按字面量渲染，避免递归处理其中的强调或链接。补充代码块与行内代码的文字保真测试。

## 验证记录

- 已阅读 `CLAUDE.md`、04 票、实现报告、`koog-best-practices.md`、`cost-and-recovery.md`，核对技术方案及 ADR 的引用、渲染、取消和内存会话契约。
- 本票新增 core 文件未引入 Android；论坛读取复用仓库与显式 `Operation.READ` 的屏蔽词接口，未新增 WRITE；本票未新增依赖版本。注释及对应文档同步已检查。后续票的持久化、费用账本和论坛工具不作为本票缺陷。
- 使用 JDK 17，设置 `ANDROID_HOME=/Users/cola/Library/Android/sdk`，移除 `NGA_INTEGRATION`、`NGA_WRITE_SMOKE` 后执行：
  - `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug`：成功（任务命中已有构建缓存）。
  - `./gradlew --offline :app:testDebugUnitTest --rerun`：强制重新执行成功；XML 汇总 1151 项，0 失败，0 错误，4 跳过。
- 本票已跟踪改动的 `git diff --check` 通过；临时探针仅写入系统临时目录，运行后删除。
- 本轮未运行设备测试、真实 NGA 或付费模型请求，未重跑 release；实现报告中的真机验收仍未完成，不将既有模拟器结果当作本轮真机验证。
