VERDICT: FAIL

# 11 — 个人分析入口与报告 评审 2

复跑 `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest`：BUILD SUCCESSFUL，未开启 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`。逐条核对 `11-review-1.md` 的 12 条。

## 上一轮 12 条的核对结果

已真正修复：第 2 条（`docs/ai-assistant.md:35` 改为「只在个人入口提供快捷操作」，与 `QuickActions.forEntry("个人")` 及同文件第 69 行一致）、第 3 条（`sourceFloorLabel` / `sourceJumpLabel` 收敛到 `core/ai/TopicContext.kt:33-38`，`AiMarkdown.kt:21`、`TopicAiSheet.kt:155`、`AiSourcePreviewSheet.kt:66` 统一复用；`material()` 对回复样本改写为「回复 <pid>，页码未知」，不再输出构造的页码楼层）、第 4 条（`PersonaTendency.mentions` / `bodyTail`，卡片渲染为 body + 胶囊 + bodyTail，信号按 `hits` 分档并回退到代表性发言数，skill 同步说明）、第 5 条（`personaPostAccessible` 与 `personaPostBlockedByRules` 拆开，新增「匿名或无权限」行，`blocked` 只保留规则命中，`note` 说明两者区别）、第 6 条（`aiSourceInline` / `aiSourceLabel` 抽出，倾向卡正文内联渲染来源胶囊）、第 7 条（面板用 `remember(entryKind, turn.text, sources, sampleCount)` 缓存解析，ViewModel 用 `personaCovered` 取代逐轮重解析，并在新建、切换与恢复时重置）、第 8 条（`TopicContext.sampleCount` 结构化字段，随 `entryJson` 保存与恢复，两处消费点不再解析文案）、第 9 条（证据行右侧只留来源 ID）、第 10 条（`canAnalyzePersona` 只接收 uid）、第 11 条（入口按钮仅在 `state.profile` 非空时渲染，并用真实用户名发起）、第 12 条（`core/api` 的 `userPostKey` 被 `mergeUserPostPages` 与 `personaPostKey` 共用）。

第 1 条按方向修了（单条正文 2000 字截断 + 首轮内联 16000 字上限），但引入了下面两个新问题，其中一个直接影响这条修复本身的正确性。

## 1. 首轮内联截断的判据用错了长度（中，新引入）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:59-60`、`:66`、`:73`
- 实际截断点：`app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt:160`

`truncated` 由 `characters = sources.sumOf { it.text.length }` 判定，但真正被 `personaInlineMaterial` 截断的是 `material()`。后者除样本正文外还包含开头几行、五行范围明细、`note`，以及每条样本一行表头（`来源 sN，主题 <tid>，回复 <pid>，页码未知，<作者>，<ISO 时间>`，约 65 字）。表头部分与样本条数成正比，而 `characters` 完全不含它。

于是存在一整段区间：`characters <= 16000 < material().length`。例如 300 条、每条正文 40 字时，`characters` 为 12000，`truncated` 为 false，范围卡「正文字数」写「12000 字」、`note` 不提续读，而 `material()` 约 3.2 万字会被实际截断，模型收到的是「本轮只内联前 16000 字……用 read_user_history 从 offset=16000 续读」。阅读范围卡与 `note` 因此谎报了首轮实际范围，这恰恰是票要求该卡片保证的事。

现有测试没有覆盖这个区间：`PersonaContextTest.kt:51` 的 `bulky` 是 20 条 × 1800 字，`characters` 为 36000，落在 `truncated` 为 true 的一侧。

建议：`truncated` 直接按 `material().length > PERSONA_INLINE_LIMIT` 判定，范围卡明细同时给出总字数与首轮内联字数，并补一条「正文短、条数多」的用例。

## 2. 16000 的内联上限与同为 16000 的工具分页，在 8 次循环上限下覆盖不完满额样本（中，新引入）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:10`（`PERSONA_INLINE_LIMIT`）
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/ForumToolSession.kt:161-162`（`read_user_history` 每页 16000 字）
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntime.kt:166`（`maxAgentIterations = 8`）

满额 300 条历史的 `material()` 约为 300 × (65 字表头 + 正文) ≈ 8 万字，首轮只内联得下约前 60–80 条。要让 `processedSourceIds` 覆盖全部样本、状态从「个人报告尚未覆盖全部样本，可继续」转成「已完成」（`TopicAiViewModel.kt:447-452`），模型还得再做约 5 次 `read_user_history` 续读，外加一次 `__read_file__` 读 persona-evidence 正文。`cost-and-recovery.md:116` 定的「Koog 总循环上限维持 8」装不下这条链路，满额历史基本只能停在未完成态反复「继续」。

上一轮内联全部样本时不需要续读，所以这条链路是本轮新形成的。修复第 1 条时没有核对它与循环上限、工具分页三者的配合。

建议：个人入口的 `read_user_history` 单页显著放大（或改为按样本条数切页，让一次调用覆盖上百条），或把首轮内联上限提到预算允许的量级；并实测一次满额样本能否在循环上限内走到「已完成」。

## 3. `mentions` 越界导致整份报告作废，且 skill 示例本身越界（中低，新引入）

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt:30`
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md:23`

校验要求 `card.mentions in card.evidence.size..processed.size`，不满足就 `require` 失败，`parsePersonaReport` 返回 null，整份报告不展示，界面退回「报告结构尚未完整或来源校验未通过，请继续」。`mentions` 是模型自行清点的条数，偏差属于常见情况；把它和 ID 真实性、证据与相反表述不重合这类可核对的硬约束同等对待，代价与收益不匹配——一个数字数错就丢掉整份已经合规的报告。

而且 skill 里给模型看的结构示例正好违反这条约束：`"mentions":3` 配 `"processedSourceIds":["s1"]`、`"evidence":["s1"]`，按校验规则 `mentions` 只能是 1。模型照着这个形状写，越界概率不低。

建议：`mentions` 越界时归零，交给已有的 `PersonaTendency.hits` 回退到代表性发言数，硬拒只保留给 ID 类约束；同时把 skill 示例改成自洽的数值。

## 其他

票中第 4 项（真机用真实账号跑一份报告）仍未勾选，限制与上一轮相同：本轮同样只有离线单测与模拟器 Compose 测试，未调用付费模型、未用真实账号读取。
