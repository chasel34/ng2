# 11 — 个人分析入口与报告

**What to build:** 用户资料页新增「AI 分析发言」，匿名作者不显示此入口。发起后阅读范围卡显示回复数、主题数（仅标题）、合计最多 300 条与时间跨度、被屏蔽未计入、图片「不读取 · 本期仅文本」。新增用户历史工具：按时间从近到远合并主题与回复、按独立发言去重、合计上限 300 由工具执行；回复自带正文，主题条目只有标题，模型判断需要时再用读主楼工具补读，受限速与预算约束；个人分析对话中读图工具不可用。报告全屏展示：概述段说明样本与「只整理本人明确表达过的内容」；分节「兴趣与偏好」「议题立场」用倾向卡片（标题、正文带数量胶囊、信号条区分反复出现 / 多次出现 / 偶尔提及、「查看依据」抽屉列代表性发言与来源、「相反表述 N」抽屉）；「判断侧重」段；「稳定性与变化」时间线；末尾边界声明说明未推断政治、健康、性向、宗教、民族等未公开属性、样本范围与反讽或引用他人的处理。三条快捷操作（查看倾向依据 / 查找相反表述 / 查看观点变化）与个人分析 skill 正文在本票完成。

**Blocked by:** 05 论坛读取工具与工具调用行、06 内置 skills 与快捷操作

**Status:** implemented

- [x] 合并去重单测：主题与回复混合按时间排序、同 pid 或 tid 去重、上限 300 且不足时用实际数、时间跨度正确
- [x] 个人分析对话中调用读图工具被拒绝并返回结构化说明（单测）
- [x] 匿名用户资料页无入口；主题分析中匿名作者不可跳转到个人分析
- [ ] 真机：用真实账号跑一份报告，卡片、抽屉、时间线与边界声明与设计稿一致；代表性发言可预览到原楼层
- [x] 报告只对成功处理的样本下结论；额度不足时说明已处理与未处理数量，允许继续
- [x] 一份报告示例记录在 Comments 供评审敏感属性边界

## Comments

### 2026-09-14 实现记录

已实现用户资料页的个人分析入口、用户历史工具、个人报告全屏展示与三条个人快捷操作，复用既有 Koog 通用工具循环、会话、预算、来源预览与历史存储；没有新增依赖、数据库迁移或 git commit。

- 资料页在 `canAnalyzePersona` 通过时显示「✦ AI 分析发言」按钮（primaryContainer 底、1.5dp primary 描边、44dp 高），uid 非正或匿名不渲染入口；主题页匿名楼层没有 `profileUid`，无法进入资料页，因而也没有个人分析路径。
- `PersonaHistoryReader` 分别读取主题与回复两类分页（`order_by=postdatedesc`），合并后按发言时间从近到远排序，以 pid、主楼用 tid 去重，合计上限 300 由工具执行；每类最多 100 页，读取失败、重复页或触顶都记为历史不完整并保留已成功取得的资料。回复列表顶层作者属于主题，正文作者按被查询用户回填，匿名主题下的本人回复仍归本人。屏蔽规则（本地 + 官方）命中的发言不计入样本，单独计数。
- 回复带正文，主题条目只保留标题，主楼正文由模型按需 `read_floor(tid, pid=0)` 补读，初始不批量补读。正文经 BBCode 解析为纯文本，引用他人包裹为「引用他人，非本人立场」，图片节点替换为占位，不进入图片输入。
- 范围卡按设计稿显示历史回复、历史主题（标注「仅标题」）、合计 N / 300 条与时间跨度、顺序、去重明细、屏蔽规则（标注「未计入」）、图片「不读取 · 本期仅文本」。
- 个人对话关闭图片能力：`ForumToolSession` 对 `read_image`、`list_images` 返回 `status=disabled` 的结构化说明，注册表也不暴露这两个工具；新增 `read_user_history` 仅在个人入口可用，按字符偏移分页返回本次去重样本与 `sampleCount`。
- 报告以 persona-evidence 约定的 JSON 结构承载，`parsePersonaReport` 校验来源 ID 真实、无重复、属于本次样本，卡片证据与相反表述互不重合且都属于已处理样本，时间线来源同样受限；不合法或截断的结构不展示为有效报告，界面保留草稿并提示可继续。
- 报告全屏展示概述（含「只整理本人明确表达过的内容」与已处理 / 未处理条数）、「兴趣与偏好」「议题立场」倾向卡、「判断侧重」、「稳定性与变化」时间线与末尾边界声明。倾向卡按去重证据数显示数量胶囊与三格信号：1 条偶尔提及（meta、0 格）、2–4 条多次出现（accent、2 格）、5 条及以上反复出现（green、3 格）。「查看依据」「相反表述 N」抽屉列出引文、来源类型与日期，整行点击按当前账号重新读取原楼层；没有相反表述的卡片不显示该按钮，并在依据抽屉说明「未找到不代表不存在」。
- 快捷操作为「查看倾向依据 / 查找相反表述 / 查看观点变化」，对应 persona-evidence、persona-counterexamples、persona-changes 三个内置 skill，随内置技能目录发布（当前共 10 个），其正文要求区分本人观点、引用他人、反讽与讨论条件，并禁止按版块或语言风格推断政治、健康、性向、宗教、民族等未公开属性。

### 验收与验证

1. 合并去重（票中第 1 项）：`PersonaContextTest` 覆盖主题与回复混合按时间排序、同 pid / tid 去重、400 条截到 300、不足时用实际数、时间跨度取样本首尾日期、空样本显示「无可访问样本」；`PersonaHistoryReaderTest` 覆盖两类分页合并不读取主题正文、匿名主题下的本人回复归属、单流失败保留另一流、重复页停止。
2. 读图工具拒绝（第 2 项）：`PersonaHistoryReaderTest.personalSessionRejectsImagesAndExposesBoundedHistoryAfterRestore` 与 `TopicAiViewModelTest.personalEntryAndHistoryKeepTextOnlyPolicyAndOriginalUser` 断言 `allowImages=false`，`read_image`、`list_images` 返回 `disabled` 与「个人分析仅支持文本」说明，并验证 `read_user_history` 的分页续读。
3. 匿名无入口（第 3 项）：`canAnalyzePersona` 对 uid ≤ 0 与匿名返回 false（`PersonaContextTest`）；`TopicAiViewModelTest` 断言 `openPersona(-1, …)` 不打开面板、不发起任何论坛请求；`TopicPageBuilderTest` 已断言匿名楼层 `profileUid` 为空，主题页无法跳转到资料页与个人分析。
4. 只对成功处理的样本下结论（第 5 项）：新增 `TopicAiViewModelTest.personalReportCoversOnlyProcessedSamplesAndKeepsDraftForContinuation`，两条样本下模型先只确认 s1，回答标记未完成、状态为「个人报告尚未覆盖全部样本，可继续」，草稿保留；继续追问确认两条后状态转「已完成」。`PersonaContextTest` 另断言证据引用未处理样本、证据与相反表述重合、时间线来源越界的结构均被拒绝。界面在报告顶部显示「已处理 N 条 · 未处理 M 条」，额度不足时沿用原有追加额度确认继续原对话。
5. 界面（第 4 项的离线部分）：`PersonaReportContentTest` 在 Pixel_8 AVD / Android 17 通过，覆盖已处理 / 未处理计数、多次出现与偶尔提及两种信号、依据与相反表述抽屉互斥展开、代表性发言整行点击回传正确 tid/pid、无相反表述时的说明、时间线与边界声明。截图见 `.scratch/ai-assistant/reports/11-artifacts/persona-top.png`、`persona-boundary.png`，已逐张查看，卡片、胶囊、信号条、抽屉与边界卡无截断或重叠。

命令（JDK 17，本机 Android SDK，全部 `--offline`，未开启 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`）：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：BUILD SUCCESSFUL；JUnit XML 合计 1243 项，0 failures、0 errors、5 skipped（默认关闭的联网测试）。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.PersonaReportContentTest`：BUILD SUCCESSFUL，1/1 通过。
- `git diff --check`：通过。

验证限制：`adb devices` 只有 emulator-5554，没有物理设备，且本次未授权调用付费模型或使用真实账号发起读取，因此票中第 4 项「真机用真实账号跑一份报告」未完成，复选框保留未勾选。上面的界面结论来自模拟器上的离线 Compose 测试，不代表真实模型输出质量已验证。本次没有发送任何模型请求，也没有执行 NGA 写操作。

### 报告示例（供评审敏感属性边界）

下面是离线样例，不是真实账号的分析结果，用于评审 JSON 结构与边界表述；实际 ID 由 App 分配。

```json
{
  "overview": "样本为最近 120 条可访问发言（回复 96 条、主题 24 条仅标题），时间跨度 2025-03 至 2026-09。以下只整理本人明确表达过的内容。",
  "processedSourceIds": ["s1", "s2", "s3", "s4"],
  "interests": [
    {"title": "长期关注物价与收入", "body": "讨论物价与工资的发言集中在 2026 年，多以自己的开支为例。", "evidence": ["s1", "s2"], "counter": []}
  ],
  "positions": [
    {"title": "反对超前消费", "body": "明确表达这一立场的发言较少，另有引用他人观点后反驳的内容已按反驳计入。", "evidence": ["s3"], "counter": ["s4"]}
  ],
  "judgment": "多用亲身经历作依据，较少引用数据或新闻[[s2]]。",
  "timeline": [
    {"period": "2026-01 起", "body": "涉及收入的发言增多，措辞转向谨慎；讨论条件也从个人开支扩展到行业情况，不能只按语气判断立场变化。", "sources": ["s2"]}
  ],
  "boundary": "结论仅覆盖上述已处理样本，更早或不可访问的历史未读取。引用他人的内容按引用处理，反讽与条件限定单独核对；没有相反表述不等于不存在。未推断政治、健康、性向、宗教、民族等本人未公开的身份或归属。"
}
```

界面在报告末尾固定展示的边界声明为：

> 只整理本人明确表达过的内容；不推断政治、健康、性向、宗教、民族等未公开身份或归属。结论限于已处理样本，不代表完整历史。引用他人、反讽与不同讨论条件须分别核对；证据不足时不判断。

### 改动文件清单

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt`（新增）
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`（新增）
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/api/UserTopics.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/PersonaHistoryReader.kt`（新增）
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt`（新增）
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/ToolCallRows.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/user/UserProfileScreen.kt`
- `app/src/main/assets/ai-skills/persona-evidence/`（新增 SKILL.md 与 references/example.md）
- `app/src/main/assets/ai-skills/persona-counterexamples/`（新增 SKILL.md 与 references/example.md）
- `app/src/main/assets/ai-skills/persona-changes/`（新增 SKILL.md 与 references/example.md）
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/PersonaContextTest.kt`（新增）
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/PersonaHistoryReaderTest.kt`（新增）
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/ForumToolSessionTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModelTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContentTest.kt`（新增）
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/ai/BuiltinSkillsAndroidTest.kt`
- `docs/ai-assistant.md`
- `.scratch/ai-assistant/issues/11-persona-analysis.md`
- `.scratch/ai-assistant/reports/11-impl.md`（新增）
- `.scratch/ai-assistant/reports/11-artifacts/`（新增界面截图）

### 2026-09-14 第一轮评审修复

接受 `11-review-1.md` 的全部 12 条意见，均已修复，没有异议项。没有 git commit。

1. **个人历史首轮内联设上限（高）。** 单条样本正文超过 2000 字时截断并标明「需要原句用 read_floor 重新读取」；首轮 user 消息经 `personaInlineMaterial` 限制在 16000 字，超出部分附明确说明，要求用 `read_user_history` 从 `offset=16000` 续读，未读部分不得当作已分析。`read_user_history` 仍按完整样本分页，因此截断的只是首轮内联量，不是可读范围。范围卡「合计」增加「正文字数」明细，超限时写明首轮内联字数，`note` 同步说明。
2. **文档自相矛盾。** `docs/ai-assistant.md` 中「个人分析的三个技能暂不开放快捷入口」改为「只在个人入口提供快捷操作」，与 `QuickActions.forEntry("个人")` 和同文档后半段一致。
3. **`floor = -1` 哨兵收敛到工具函数。** `core/ai` 新增 `sourceFloorLabel` 与 `sourceJumpLabel`，`AiMarkdown`、报告下方「N 个来源」列表与来源预览跳转按钮统一复用，不再出现「-1 楼」「跳到 -1 楼」。`TopicContext.material()` 对回复样本改写为「回复 <pid>，页码未知」，不再向模型写出构造的页码与楼层。
4. **数量胶囊恢复设计稿口径。** `PersonaTendency` 增加 `mentions`（该倾向命中的发言条数）与 `bodyTail`，卡片渲染为「body + N 条发言胶囊 + bodyTail」，信号强弱按 `mentions` 分档，缺少该字段时回退到代表性发言数。`parsePersonaReport` 校验 `mentions` 不小于 evidence 条数且不超过已处理样本数；persona-evidence skill 同步说明字段含义与分档规则。
5. **未计入原因拆分。** `personaPostAccessible` 与 `personaPostBlockedByRules` 分开判定：匿名、无权限与非正 uid 计入新的「匿名或无权限」行，`TopicContext.blocked` 与 `material()` 的「屏蔽 N 条」只保留屏蔽规则命中，`note` 在有不可访问样本时明确说明其不是屏蔽规则命中。
6. **卡片正文的来源标记回到句子里。** 从 `AiMarkdown` 抽出 `aiSourceInline` 与 `aiSourceLabel`，倾向卡复用同一套来源胶囊，正文按 `parseCitations` 顺序内联渲染，不再把 `[[sN]]` 剥离到下一行。
7. **报告解析缓存。** 面板用 `remember(entryKind, turn.text, sources, sampleCount)` 缓存解析结果，流式输出期间不再每帧反序列化；ViewModel 用 `personaCovered` 记录是否已有覆盖全部样本的报告，新建、切换与历史恢复时重置，不再每轮重解析全部历史回答。
8. **样本数改为结构化字段。** `TopicContext` 增加 `sampleCount`，由 `buildPersonaContext` 写入，随会话 `entryJson` 保存与恢复；面板与 ViewModel 不再从「合计」文案反推数字。
9. **证据行不再重复类型。** 左侧胶囊显示「主题标题 / 回复」，右侧只保留来源 ID。
10. **删除未生效的形参。** `canAnalyzePersona` 只接收 uid；匿名不可达由「匿名楼层没有 `profileUid`」保证，单测改为断言 uid 边界与匿名样本不计入。
11. **资料加载失败不渲染入口。** 入口按钮改为仅在 `state.profile` 已加载时显示，并直接使用真实用户名发起分析，不再以「UID N」为标题。
12. **去重键合并。** `core/api` 新增 `userPostKey`，`mergeUserPostPages` 与 `personaPostKey` 共用同一规则（pid 必须大于 0，否则用 tid）。

测试同步更新：`PersonaContextTest` 覆盖拆分后的两类未计入计数、单条截断长度、首轮内联上限与续读提示；`PersonaHistoryReaderTest` 改用多条样本验证分页续读并断言单条截断；`PersonaReportContentTest` 断言「body + N 条发言 + bodyTail」在同一段文字中渲染、正文内联来源胶囊，以及证据行右侧只显示来源 ID。

最终验证：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：BUILD SUCCESSFUL；JUnit XML 合计 1243 项，0 failures、0 errors、5 skipped。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.PersonaReportContentTest`：BUILD SUCCESSFUL，1/1 通过；截图已按新渲染重新导出并逐张查看。
- `git diff --check`：通过。
- 仍为离线与模拟器验证，未调用付费模型、未使用真实账号读取、未执行 NGA 写操作；票中第 4 项真机验收的限制不变。

本轮改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/api/UserTopics.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiMarkdown.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/user/UserProfileScreen.kt`
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/PersonaContextTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/PersonaHistoryReaderTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContentTest.kt`
- `docs/ai-assistant.md`
- `.scratch/ai-assistant/reports/11-impl.md`
- `.scratch/ai-assistant/issues/11-persona-analysis.md`
- `.scratch/ai-assistant/reports/11-artifacts/`（按新渲染更新的截图）

### 2026-09-14 第二轮评审修复

接受 `11-review-2.md` 的 3 条意见，均已修复，没有异议项。没有 git commit。

1. **首轮截断判据改用 material 长度。** `buildPersonaContext` 先按当前样本构造上下文，用 `material().length` 判断是否超过 16000 字，再决定范围卡与 `note` 的措辞，不再用正文字数近似。未截断时「首轮内联」明细写「全部内联」，截断时写「前 16000 字 / 共约 N 字」，并保留独立的「样本正文 N 字」明细。新增「正文短、条数多」用例：300 条 × 40 字，正文合计 12000 字但 material 超过 16000 字，断言范围卡与 `note` 如实标注截断；另有短样本用例断言「全部内联」且 `personaInlineMaterial` 不改写内容。

2. **续读与循环上限重新配平。** 先用 koogTest 实测出 `maxAgentIterations = 8` 只容得下两轮工具调用，因此把「多页续读」改为「一次取回」：新增 `PERSONA_MATERIAL_LIMIT = 64000` 作为整份资料上限，构造时保留较新发言、丢弃超出部分并单列「长度上限 N 条 · 未纳入」范围行与 `note` 说明；`read_user_history` 单页容量设为上限加余量（68000 字），使任意 offset 的续读都能一次取回剩余全部样本。新增 `TopicAgentRuntimeTest.fullPersonaHistoryIsCoveredWithinTheAgentIterationLimit`：满额 300 条样本，走真实 Koog 工具循环，首轮一次补读主楼（占住技能正文读取的循环预算）、一次 `read_user_history` 续读、第三轮产出覆盖全部 300 条的报告，断言请求数为 3、续读结果不含 `nextOffset`、最后一条样本进入提示词、报告解析后 `processedSourceIds` 覆盖全部样本。另补 `oversizedPersonalHistoryStillPagesWithContinuation`，用超出单页的构造上下文验证分页续读仍然可用。

3. **命中条数越界不再作废报告。** `parsePersonaReport` 把 `mentions` 的范围检查从 `require` 改为归零回退，超出「不少于代表性发言数、不多于已处理样本数」时按代表性发言数显示，硬拒只保留给来源 ID、证据归属与反例不重合这类可核对约束；persona-evidence 的结构示例改为自洽数值（3 条已处理样本、2 条代表性发言、mentions 为 3），并说明越界数字会被忽略、不要用估计值充数。

`docs/ai-assistant.md` 同步记录单条与整份长度上限、一次续读的约定，以及命中条数的回退规则。

最终验证：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：BUILD SUCCESSFUL；JUnit XML 合计 1245 项，0 failures、0 errors、5 skipped。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.PersonaReportContentTest`：BUILD SUCCESSFUL，1/1 通过。
- `git diff --check`：通过。
- 仍未调用付费模型、未用真实账号读取、未执行 NGA 写操作；票中第 4 项真机验收的限制不变。上面的循环上限结论来自假 executor 走真实工具循环的离线测试，不代表真实模型一定按同样的次数完成。

本轮改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt`
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md`
- `app/src/test/kotlin/com/chasel/ng2n/core/ai/PersonaContextTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/PersonaHistoryReaderTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt`
- `docs/ai-assistant.md`
- `.scratch/ai-assistant/reports/11-impl.md`
- `.scratch/ai-assistant/issues/11-persona-analysis.md`
