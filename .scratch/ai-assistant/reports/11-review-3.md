VERDICT: PASS

# 11 — 个人分析入口与报告 评审 3

复跑 `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest`：BUILD SUCCESSFUL。按 JUnit XML 汇总为 1245 项、0 failures、0 errors、5 skipped（默认关闭的联网测试），与实现报告一致。`git diff --check` 通过。未开启 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`。

`11-review-2.md` 的 3 条均已修复，未发现新引入的问题。

## 1. 首轮内联截断的判据（已修复）

`buildPersonaContext`（`app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaContext.kt:86-97`）改为先构造上下文、再用 `context.material().length` 判断，不再用正文字数近似。范围卡新增独立的「样本正文 N 字」与「首轮内联」两条明细，未截断写「全部内联」，截断写「前 16000 字 / 共约 N 字」，`note` 同步。

我上一轮指出的正是「正文短、条数多」这段区间，新测试直接覆盖了它：`PersonaContextTest.kt:56-60` 用 300 条 × 40 字构造，先断言 `sources.sumOf { it.text.length } < PERSONA_INLINE_LIMIT` 且 `material().length > PERSONA_INLINE_LIMIT`，再断言 `note` 与范围卡如实标注截断。`PersonaContextTest.kt:69-73` 另断言短样本走「全部内联」且 `personaInlineMaterial` 不改写内容。

## 2. 续读与循环上限的配平（已修复）

改成「一次取回」而不是多页续读：新增 `PERSONA_MATERIAL_LIMIT = 64000` 作为整份资料上限，构造时按比例丢弃较早发言直到 `material()` 落在上限内（`PersonaContext.kt:86-97`），丢弃量单列「长度上限 N 条 · 未纳入」范围行并写进 `note`；`read_user_history` 的单页容量改为 `PERSONA_HISTORY_PAGE = 68000`（`ForumToolSession.kt:161-162`），大于资料上限，因此任意 offset 的续读都能一次取回剩余全部样本。

验证是充分的：`TopicAgentRuntimeTest.fullPersonaHistoryIsCoveredWithinTheAgentIterationLimit`（`app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/TopicAgentRuntimeTest.kt:64-99`）用满额 300 条样本走真实 Koog 工具循环，第一轮补读主楼占住技能正文读取的循环预算、第二轮一次 `read_user_history` 续读、第三轮产出覆盖全部 300 条的报告，断言请求数为 3、提示词中不出现 `nextOffset`、最后一条样本 `来源 s300` 进入提示词、报告解析后 `processedSourceIds` 覆盖全部样本。该测试确实在 `testDebugUnitTest` 中执行（`app/build.gradle.kts:78` 把 `src/koogTest/kotlin` 并入 test 源集，结果 XML 中该类 13 项全绿）。`PersonaHistoryReaderTest.oversizedPersonalHistoryStillPagesWithContinuation` 另用超出单页的构造上下文保住分页续读的退路。其他工具的正文分页仍为 16000（`ForumToolSession.kt:207-209`），与 `docs/ai-assistant.md:25` 一致。

我复核了截断说明的余量：目标长度取 `PERSONA_MATERIAL_LIMIT - 500`，而最终加上截断措辞只增加约 55 字，因此最终 material 仍在 64000 内，单页 68000 一定覆盖得住；`PersonaContextTest.kt:62-68` 对这两点都有断言。

## 3. 命中条数越界作废报告（已修复）

`parsePersonaReport` 把 `mentions` 的范围检查从 `require` 改为 `withCheckedMentions` 归零回退（`PersonaReport.kt:31-36`），越界时由已有的 `hits` 退回代表性发言数，硬拒只保留给来源 ID、证据归属与反例不重合这类可核对约束。`app/src/main/assets/ai-skills/persona-evidence/SKILL.md:23` 的结构示例改为自洽数值（3 条已处理样本、2 条代表性发言、`mentions` 为 3），正文也说明越界数字会被忽略、不要用估计值充数。

## 文档

`docs/ai-assistant.md:65` 记录了单条 2000 字截断、整份 64000 字上限与取舍规则、首轮 16000 字内联与一次续读，以及范围卡新增的两类未计入行；`:69` 记录了命中条数的回退规则。与代码一致。

## 需要知情的取舍与遗留

- 64000 字的整份上限意味着样本条数不再总能到 300：每条都接近 2000 字上限的重度用户，实际只能保留 30 条左右。这是为了在 Koog 循环上限内完成报告而做的取舍，范围卡的「长度上限 N 条 · 未纳入」行与 `note` 都如实披露，`sampleCount` 也随之下调，报告校验与「已处理 / 未处理」计数都以下调后的条数为准，内部一致。
- 循环次数的结论来自假 executor 走真实工具循环的离线测试，实现报告已声明它不代表真实模型一定按同样次数完成。
- 票中第 4 项「真机用真实账号跑一份报告」仍未勾选，三轮评审都只有离线单测与模拟器 Compose 测试，未调用付费模型、未用真实账号读取。这是本票唯一仍未闭环的验收项。
