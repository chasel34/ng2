# 回归复验 R2 修复说明

验证命令（JDK 17，`--offline`，未开 NGA_INTEGRATION / NGA_WRITE_SMOKE）：

```bash
ANDROID_HOME=/Users/cola/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

结果：BUILD SUCCESSFUL，JUnit XML 合计 1307 项、0 failures、0 errors、5 skipped（默认关闭的联网测试）；修复前为 1305 项。另单独执行 `:app:compileDebugAndroidTestKotlin` 确认改过的 UI 用例可编译。`git diff --check` 通过。未执行 git commit，未读取 `.env.local`，未在模拟器上安装或运行 App。

---

## P3 倾向卡数量胶囊插进正文后语序破坏

**根因**：胶囊文案是「${hits} 条发言」，而技能只说「body 与 bodyTail 要能和数量连成一句话」，没有约定切分点，模型按「N 条」造句并把量词写进 bodyTail，拼出「5 条发言次…」。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`：新增 `PersonaTendency.countPill()`（固定为「N 条」）与 `PersonaTendency.sentence()`（body + 胶囊 + bodyTail），胶囊文案与拼接顺序由这两个函数统一定义。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt`：胶囊文案改用 `countPill()`，行内占位宽度按缩短后的文案从 `digits * 7 + 56` 调到 `digits * 7 + 32`。
- `app/src/main/assets/ai-skills/persona-evidence/SKILL.md`：写明胶囊文案固定为「N 条」，body 停在数量前、bodyTail 从数量之后接着写且不得以量词或「条」「次」「篇」开头，并给出正误各一例（正：「…的发言有」＋「，集中在 2026 年 8 月。」；误：「在已处理样本中」＋「次以主题形式关注…」）。JSON 结构示例里的 body / bodyTail 占位改为自洽的示范句。
- `app/src/main/kotlin/com/chasel/ng2n/core/ai/QuickActions.kt`：`BuiltinQuickActions.VERSION` 由 `4` 升到 `5`，让已安装设备重新释放改过的技能目录。

**验证方式**：`PersonaContextTest.countPillCarriesOnlyTheNumberAndJoinsBodyIntoOneSentence` 断言胶囊为「3 条」、按技能写法的 body/bodyTail 拼成「以主题形式关注新区预约的发言有3 条，集中在 2026 年 8 月。」，并覆盖 bodyTail 为空与回退到代表性发言数两种情形。`PersonaReportContentTest` 的渲染断言同步改为「3 条」「1 条」。

## P3 零样本 / 历史读取失败轮同屏出现矛盾说明

**根因**：「已读取 N 条；…已有草稿保留，可继续。」只按 `turn.reportExpected && turn.incomplete && persona == null` 显示，而零样本与历史读取失败根本没请求过模型，也没有草稿。

**改动文件**：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt`，该说明增加 `turn.card !in listOf("READ", "EMPTY")` 条件。这两种卡片下只剩状态卡与底部「重试」按钮，`reportExpected` 字段本身不变，正常报告轮的说明保持原样。

**验证方式**：`TopicAiSheetTest.zeroSampleReadFailureOffersOnlyRetryWithoutClaimingADraft`（androidTest）在 `READ` 与 `EMPTY` 两种卡片下断言不存在「待确认处理」文案且按钮为「重试」，把卡片置空后断言说明与「继续」按钮仍在。

## P3 「查看依据」证据行类型胶囊按来源真实楼层判定

**根因**：胶囊只判断 `part == "summary"`，其余一律写「回复」；`read_floor(tid, pid=0)` 补读回来的主楼 `part` 是 `floor`，因此被标成「回复」，与预览里的「0 楼」「跳到主楼」自相矛盾。

**改动文件**：

- `app/src/main/kotlin/com/chasel/ng2n/core/ai/PersonaReport.kt`：新增 `personaEvidenceKind(source)`，只有标题的样本为「主题标题」，`pid == 0` 或 `floor == 0`（NGA 主楼 pid 固定为 0）为「主楼」，其余为「回复」。补读来源与初始样本走同一判据。
- `app/src/main/kotlin/com/chasel/ng2n/ui/ai/PersonaReportContent.kt`：证据行胶囊改用该函数。

**验证方式**：`PersonaContextTest.evidenceKindFollowsTheRealFloorForSamplesAndToolReadSources` 断言初始回复样本为「回复」、只有标题的样本为「主题标题」、`pid=0/floor=0` 的补读来源为「主楼」、同一来源换成 `pid=45/floor=12` 后回到「回复」。`PersonaReportContentTest` 的样本里新增一条编号在初始样本之后的补读主楼 `s7`，断言抽屉里同时出现「主楼」与两条「回复」。

## 文档

`docs/ai-assistant.md`：数量胶囊只写「N 条」且由技能约定 body / bodyTail 的切分点；证据行类型按来源真实楼层判定（主题标题 / 主楼 / 回复）；尚未请求模型的读取失败与零样本状态卡下不附「待确认处理」说明，只提供「重试」。

## 未做的事

- 未 git commit，未读取 `.env.local`。
- 未在模拟器上运行 `:app:connectedDebugAndroidTest`：它会重装 `com.chasel.ng2.dev` 的 debug 包，与「随后统一装」的约定冲突，因此只做了 `:app:compileDebugAndroidTestKotlin`。三条修复的实际渲染（胶囊宽度与换行、零样本卡片、主楼胶囊）尚未在设备上复验。
- 胶囊缩短后占位宽度按 12sp 字面宽度重新估算，没有在设备上量过；若真机上出现胶囊过窄或文字被裁，调整 `PersonaReportContent.kt` 里的 `Placeholder` 常量即可。
- 技能改了正文，`BuiltinQuickActions.VERSION` 已升到 5；装新包后首次进入需要让 App 重新释放技能目录，旧会话里已经写好的报告不会因此重排。
