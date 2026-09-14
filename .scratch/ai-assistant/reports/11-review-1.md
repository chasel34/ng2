VERDICT: FAIL

# 11 — 个人分析入口与报告 评审 1

评审范围：票 11 涉及的 Kotlin 源码、内置 skill 正文、单测与 androidTest、`docs/ai-assistant.md`。对照票的验收项、`design/ai-assistant/Persona.dc.html`、`CLAUDE.md` 约定、`koog-best-practices.md` 与 `cost-and-recovery.md`。

已核实：`ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest` 通过（未开启 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`），任务全部 up-to-date，说明现有源码与实现报告中记录的那次成功运行一致。`core/ai/` 未引入 `android.*`；`fetchUserTopics` 保留 `Operation.READ`；个人会话未注册 `read_image` / `list_images`，`ForumToolSession.execute` 对两者返回 `status=disabled`；内置技能目录为 10 份，`BuiltinSkillsAndroidTest` 断言一致。未新增依赖，`gradle/libs.versions.toml` 未改动。

## 1. 个人历史全文无上限进入首轮请求（高）

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt:154` — `user { text(initial.material()) }`
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:33-37`

个人入口的 300 条样本中，回复条目携带完整正文，`buildPersonaContext` 对单条正文和总长度都没有任何上限，`material()` 的结果整段作为首条 user 消息发出。重度用户的 300 条回复很容易到几万至十几万字符，可能超出模型上下文，或在 `budget.prepare` 的字节预估阶段直接超出 `cost-and-recovery.md` 给个人分析定的单次额度，于是整轮在任何工具调用之前就失败，没有任何降级路径。同时 `read_user_history` 按 16000 字符分页的设计在这里失去意义：同样的正文已经完整内联在提示里了。

建议：对单条样本正文和首轮 `material()` 总长度设上限，把截断事实写进范围卡或 `note`，超出部分交给已有的 `read_user_history` 续读。

## 2. `docs/ai-assistant.md` 自相矛盾，与实现不符（中）

- `docs/ai-assistant.md:35` — 「个人分析的三个技能暂不开放快捷入口，其文本约束已保留。」

同一文件 `docs/ai-assistant.md:69` 写「三项快捷操作为「查看倾向依据、查找相反表述、查看观点变化」」，代码 `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt:28-29` 也确实对「个人」入口返回这三条。第 35 行是票 06 时期的表述，本票开放入口后未同步，违反 CLAUDE.md 的文档同步约定。

建议：删除或改写第 35 行的「暂不开放快捷入口」。

## 3. `floor = -1` 哨兵在两处界面未处理，会显示「-1 楼」（中）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:34` 给回复样本写入 `floor = -1`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt:152` — `"${source.floor} 楼"`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/AiSourcePreviewSheet.kt:64` — 「跳到 ${…} 楼」

`AiMarkdown.kt:32` 与 `AiMarkdown.kt:70` 都按 `floor < 0` 显示「回复」，说明 -1 是刻意的哨兵值；但报告下方的「N 个来源」展开列表和来源预览的跳转按钮没有做同样处理。报告的 `judgment` / `timeline` 正文里带 `[[sN]]` 时前者必然渲染，后者在预览尚未返回或读取失败时渲染，两处都会出现「-1 楼」「跳到 -1 楼」。

建议：把 `floor < 0` 的显示收敛到一处工具函数，这两处复用。

同处还有 `app/src/main/kotlin/com/chasel/ng2n/core/ai/TopicContext.kt:21` 的 `material()`：个人回复样本会向模型写出「第 1 页，-1 楼」，页码和楼层都是构造出来的假值，与「不要编造来源」的系统提示相抵触。建议个人样本走与 `part == "summary"` 类似的分支，只写 tid/pid 与日期。

## 4. 数量胶囊与信号条的口径偏离设计稿（中）

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt:78-95`

设计稿 `design/ai-assistant/Persona.dc.html` 的 `count` 是表达该倾向的发言条数（示例 41 条、23 条、12 条、3 条），内嵌在正文句子中间（`body` + `count` + `body2`），`TONES` 的强弱由这个条数决定。实现改成了 `card.evidence.size`，文案为「N 条依据」，并追加在正文末尾。由于 skill 只要求列代表性发言，卡片通常只会给 1–2 条，「反复出现」（≥5）几乎不可能出现，信号条会系统性低报；「41 条发言」这类事实也无处显示。

建议：要么在 skill 里要求 `evidence` 枚举全部命中样本，要么给卡片增加一个独立的命中条数字段并按设计稿放回正文中间；两种都改不了的话，需要在票或设计稿里明确记录这次口径变更。

## 5. 匿名与无权限主题被计入「屏蔽规则」（中低）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:14-16`、`:31`、`:45`

`personaPostAllowed` 把 `denied`、`anonymous`、非正 `authorId` 和命中过滤规则四种情况合并返回 false，`buildPersonaContext` 把它们一起计入 `blocked`，范围卡标成「屏蔽规则 N 条 · 未计入」，`material()` 也写成「屏蔽 N 条未计入」。本人的匿名主题和无权限版块主题并不是屏蔽规则命中，这个计数会误导用户，也会误导模型对样本缺口的判断。

建议：拆成两个计数，或把该行改名为「未计入」并在明细里区分原因。

## 6. 卡片正文里的来源标记被剥离后另起一行（低）

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt:87-97`

正文只取 `AnswerPart.Text`，把 `[[sN]]` 抽出来在下方另用一个 `AiMarkdown` 渲染。设计稿里来源胶囊是嵌在句子里的。建议直接把 `bodyParts` 交给 `AiMarkdown`，数量胶囊用 `InlineTextContent` 追加。

## 7. 每次重组都解析报告 JSON（低）

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt:139`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt:444`

前者在组合期对每个 turn 调用 `parsePersonaReport`，流式输出期间每帧都要反序列化一次完整报告；后者每轮结束时把之前所有 turn 再解析一遍。建议前者用 `remember(turn.text, sources, sampleCount)` 缓存，后者缓存上一轮的解析结果。

## 8. 样本数靠解析界面字符串取得（低）

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt:138`
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiViewModel.kt:441`

两处都用 `ranges.first { it.label == "合计" }.amount.substringBefore(" /").toIntOrNull() ?: 0` 从展示文案反推样本数。一旦 `PersonaContext.kt:44` 的文案措辞变动，`sampleCount` 会静默变成 0，`parsePersonaReport` 的 `1..sampleCount` 校验随之全部失败，报告永远不展示。建议把样本数放进 `TopicContext` 的结构化字段。

## 9. 证据行重复显示来源类型（低）

- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt:149`、`:158`、`:161`

同一行左侧胶囊写「回复」，右侧又写「回复 · s3」。设计稿里胶囊位置是来源位置（版块或主题名），不是再写一遍类型。建议右侧只留来源 ID，或把胶囊换成可得的定位信息。

## 10. `canAnalyzePersona` 的 `anonymous` 形参没有生产调用方（低）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:8`

两个生产调用点（`UserProfileScreen.kt:162`、`TopicAiViewModel.kt:188`）都只传 uid，只有 `PersonaContextTest.kt:38` 传 true。票的验收项 3 实际由「匿名楼层没有 profileUid」保证，这个形参既不生效也让单测看起来覆盖了并不存在的路径。建议删掉形参，或在资料页真正把匿名状态传进来。

## 11. 入口按钮在资料加载失败时仍然显示（低）

- `app/src/main/kotlin/com/chasel/ng2n/ui/user/UserProfileScreen.kt:159-166`

按钮渲染在处理 `state.profile == null` 的 `when` 之前，资料加载失败时依然可点，随后以「UID N」为标题发起分析。建议加载失败时不渲染入口。

## 12. 去重键重复实现（低）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:9` 与 `app/src/main/kotlin/com/chasel/ng2n/core/api/UserTopics.kt:44-52`

`personaPostKey` 和 `mergeUserPostPages` 用的是同一套 `p<pid>` / `t<tid>` 规则，只差一个 `takeIf { it > 0 }`。建议合并到一处，避免两边规则漂移。

## 未完成的验收项

票中第 4 项（真机用真实账号跑一份报告）仍未勾选，实现报告已说明原因（无物理设备、未授权付费模型调用）。本次评审同样无法覆盖真实模型输出质量与真机界面，报告中的界面结论来自模拟器上的离线 Compose 测试。
