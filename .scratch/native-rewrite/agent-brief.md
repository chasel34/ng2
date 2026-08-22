# 子代理工作简报(native-rewrite,每张票开工前必读)

主控(orchestrator)按票派活、按票验收。你只负责**一张票**,做完交付,不要越票。

## 必读上下文(按顺序)

1. `.scratch/native-rewrite/spec.md` — 目标、铁律、验收口径
2. `docs/adr/0002-anti-block-chain-first-class.md`、`0003`、`0004` — 架构决定(0001 只有结论)
3. 你这张票 `.scratch/native-rewrite/issues/NN-*.md` — 全文,含验收项
4. `.scratch/native-rewrite/research/*.md` — 票里引用到的节(inventory / perf-history / anzong / stack)
5. `docs/API文档.md` — 协议相关票(03–08)必读全篇
6. RN 侧对应 TS 源码与测试(`src/core/**`、`src/ui/**`、`src/store/**`、`src/app/**`)— 这是移植原件,**逐行读,照抄语义**

## 铁律

- **照抄不简化**:协议/算法/反封锁链/清洗逻辑的每一个分支都有事故出处(ADR-0002 十条)。看到「多余」逻辑先去 TS 测试里找它的回归用例,找不到也保留并在 Comments 里记问题,不许删。
- **逆向怪癖勿修**(骰子共享随机流、匿名 hex[5] 跳过、255 表越界、UTC+8 目录、双重实体解码)。
- **已知缺陷不随迁**(spec §一.5 列的 P1-01/02/03/04、子版块白名单、P2-04、P3-05)——这些**要修**,并在 Comments 记「修了什么、RN 版原行为是什么」。
- anzong(`gov.anzong.androidnga`,GPL v2)**只抄思路,零代码搬运**。不要 clone 它的仓库。
- 技术栈与版本**只认** `native/gradle/libs.versions.toml`(来源 research/stack-2026-08.md)。需要加库:加进 toml 的 `[libraries]`,stable 版本,Comments 里写理由。不进 alpha/beta。
- 模拟器永不裁性能;debug 包永不裁性能。性能只在票 19 真机裁决。
- 不做票外的重构/整理。发现票外问题 → 写进你票的 Comments「发现的票外问题」小节,主控处理。

## 工程约定(native/)

- 包名根 `com.chasel.ng2n`。分层:`core/`(纯 Kotlin,零 Android 依赖:net / api / bbcode / local)、`data/`(Room/DataStore/仓库)、`ui/`(Compose 屏幕与组件)、`di/`(Hilt)。core 层**禁止 import android.\***(JVM 单测可直接跑)。
- 单测在 `native/app/src/test`(JVM,JUnit4 + kotlin-test 或 JUnit5 按骨架定),金样本放 `native/app/src/test/resources/goldens/<domain>/`。
- 协程 / Flow;`@Serializable` 数据类;不可变集合用 kotlinx-collections-immutable。
- Gradle:代理按 CLAUDE.md 的 `GRADLE_OPTS`(127.0.0.1:7897)传;本机只有 16GB 内存、可能有多个子代理并行构建——**`gradle.properties` 里 `org.gradle.jvmargs` 不超过 `-Xmx2g`、`kotlin.daemon.jvmargs` 不超过 `-Xmx1536m`**,收工前 `./gradlew --stop`。
- 命令统一在 `native/` 下跑:`./gradlew :app:testDebugUnitTest`(单测)、`./gradlew :app:assembleDebug`(出包)。
- **KDoc/注释里不要写含 `*/` 的路径**(如 `data/*/x.kt`)——注释被静默截断,KSP2 只报 `[MissingType]`,极难定位(票 14 踩坑)。写成 `data/…/x.kt`。
- 跑完必须 **编译通过 + 单测全绿**。禁止用 `@Ignore`/跳过来「变绿」;真做不到的用例在 Comments 里逐条列出原因。

## 工作树与提交

- 你可能在一个独立 git worktree 里(`git worktree list` 可见)。所有改动在本 worktree 提交,**不要切分支、不要 push、不要 rebase**。
- 每张票结束时提交一次(允许中途多次提交),commit message 格式:`feat(native): 票NN <一句话>`(docs/脚本类用 `docs`/`chore`),正文简述做了什么、没做什么。提交尾部照 Bash 工具说明带 `Co-Authored-By` 与 `Claude-Session` 两行。
- **不提交**:`native/build/`、`native/app/build/`、`native/.gradle/`、`native/local.properties`、`native/.kotlin/`——骨架票负责 `native/.gitignore`,后续票检查 `git status` 别混进产物。

## 票据回写(交付物的一部分)

在你的票文件里:
1. `**Status:** open` → `**Status:** in-review`(主控验收后改 resolved,不要自己改 resolved)。
2. 验收项 `- [ ]` 做到的打 `- [x]`;没做到的保留 `[ ]` 并在 Comments 说明。
3. 文末追加 `## Comments` 小节(已有则追加条目),写:完成摘要 / 关键决定(含票里留白的「实现时定」项)/ 对 RN 版的有意偏离 / 未完成项及原因 / 发现的票外问题 / 需要所有者(真人)介入的事项(登录、真机)。**写事实,不写宣传。**

## 真人介入

需要登录 NGA 账号、需要小米真机、需要所有者做选择 —— **不要等、不要猜凭证**:把该项标为「待所有者」写进 Comments,做完其余部分交付。模拟器 `emulator-5554`(Pixel_8,API 37)已在跑,可直接 `adb` 用;上面已装 RN 版 `com.chasel.ng2`(release)与 `com.chasel.ng2.dev`,**不要卸载它们**。

## 交付报告(你最后一条消息,主控只看这个)

用这个骨架,≤400 字:
- 结果:验收项逐条 ✅/❌ + 一句话证据(命令与关键输出行)
- 提交:commit hash 列表
- 偏离/决定:
- 未完成 / 待所有者:
- 票外发现:
