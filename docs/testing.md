# 测试说明

默认 JVM 测试使用本地样本、fake 或本机测试服务。离线验证可显式清除联网开关，避免继承终端环境：

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew :app:testDebugUnitTest
# 仅验证一个测试类时，在上述命令后追加 --tests '<完整类名>'
```

| 验证 | 启用条件与影响 |
|---|---|
| JVM 线上读取冒烟 | `NGA_INTEGRATION=1`；访问真实 NGA，登录态读取还需 `NGA_UID` / `NGA_CID` |
| JVM 线上写入冒烟 | 上述条件加 `NGA_WRITE_SMOKE=1`；会执行签到、点赞、收藏增删和签名回写，可能留下真实账号状态变化 |
| 设备登录态读取 | `-Pandroid.testInstrumentationRunnerArguments.nga_integration=1`；使用目标安装变体中已有账号读取通知 |
| 冷启动 Macrobenchmark | 启动真实 app，首页可能联网；每轮在计时外冷却 60 秒，五轮至少增加五分钟等待 |
| Room 迁移与 DAO | `:app:connectedDebugAndroidTest`；`MigrationTestHelper` 读取 `app/schemas/` 的旧版本 JSON，改数据库结构后必须跑 |

联网测试按任务授权范围启用。凭证不写入仓库或验证报告。`testDebugUnitTest` 的成功不表示被跳过的线上冒烟已通过。

## Koog 离线接入验证

`app/src/koogTest/kotlin` 用于 JVM 测试和设备冒烟测试。假执行器验证 `chatAgentStrategy()` 的模型请求、工具执行、调用 ID 回传和最终回答；MockWebServer 验证 DeepSeek HTTP 与论坛 Cookie、拦截器、连接的隔离，不需要 API Key 或真实网络服务。

release 设备冒烟通过 `testBuildType=release` 将同一组测试主体临时编入目标 APK，与生产实现一起经过 R8；设备 runner 只使用 Android 平台 API 跨 APK 调用测试入口。这避免为测试 runner 保留整套已被压缩的 Kotlin/AndroidX 公共 API。冒烟变体只对 `127.0.0.1` 允许 HTTP，MockWebServer 直接绑定回环地址，不依赖 DNS。普通 debug/release 包均不包含这些测试代码、测试网络策略、JUnit、MockWebServer 或协程测试库。

运行方式：

```bash
env -u NGA_INTEGRATION -u NGA_WRITE_SMOKE ./gradlew --offline -PtestBuildType=release :app:connectedReleaseAndroidTest
```

这项验证会安装 release 应用和测试 APK，应选择测试模拟器或允许覆盖安装的设备。包体数据和框架依赖边界见 [ADR-0006](adr/0006-koog-agent-runtime.md)。

## AI 论坛工具离线验证

`ForumToolSessionTest` 覆盖共享限速与取消、屏蔽与匿名化、错误分类、页内楼层缓存复用、正文及图片索引分页、回复链局部失败、热门回复的上游与下游、超过 300 节点的跨主题分支续读、游标重用与校验、两页之间通过楼层/主题页工具增加缓存时不重排旧分页，以及文本入口禁用图片。`TopicAgentRuntimeTest` 另验证连续工具调用、图文资料与工具结果分离、来源编号、完整协议续聊，以及参数解码失败时不读取论坛。

工具调用行的模拟器测试与截图：

```bash
./gradlew --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.ui.ai.ToolCallRowsTest
```

测试检查失败次数、展开明细、用户操作保持、回答时自动收起及深浅色。离线假模型验证协议与数据流，不能代替真实模型的自主翻页和读图质量验收。

## AI 对话持久化与恢复

离线单测覆盖 Koog 检查点续跑、成功工具不重读、完整协议与会话隔离、连续中断追溯、保存失败禁止后续请求、历史筛选与分组。设备测试 `AiPersistenceTest` 覆盖 2→3 正式迁移、书签保留、重开后的运行状态、删除撤销、级联删除和用量独立保留；`AiHistoryScreenTest` 与 `TopicAiSheetTest` 覆盖历史和状态卡，截图保存在测试设备的应用外部文件目录 `ai-07/`。

`AiProcessRecoveryTest` 默认跳过，需由测试主机显式分两阶段执行。模型和论坛均使用离线夹具，使用独立数据库 `ai-process-recovery.db`，不操作正式对话：

1. 安装 debug APK 和 androidTest APK，运行 `adb shell am instrument -w -e class com.chasel.ng2n.data.db.AiProcessRecoveryTest -e aiProcessPhase seed com.chasel.ng2.dev.test/androidx.test.runner.AndroidJUnitRunner`。
2. 等待 `adb shell run-as com.chasel.ng2.dev test -f files/ai-07-process-ready` 成功。此时工具结果与检查点已提交，第二次模型请求保留部分回答并等待。
3. 在另一终端运行 `adb shell am force-stop com.chasel.ng2.dev`。seed 的 instrumentation 被终止是预期结果，不应把这一阶段当作通过的测试。
4. 运行相同 instrumentation 命令，将 `aiProcessPhase` 改为 `verify`。它确认旧运行已中断、草稿及用量保留、读取历史不新增请求；显式继续后只新增一次请求、不重复问题或成功工具。

上述模拟器验证不替代真机后台回收验收，也不调用实际付费模型。日常构建与测试继续使用 JDK 17、已配置的 Android SDK 和 `--offline`。

## AI 预算与用量

`AiBudgetTest`、`AiExecutionQueueTest`、`AiRetryTest` 以及会话/运行单测覆盖原子额度规则、去重结算、跨日待核实、确认追加与停止、排队取消、HTTP 错误分类、Retry-After 和空流不重放。`AiBudgetPersistenceTest` 在模拟器验证 Room 3→4 迁移、真实事务并发、重开与删除对话后用量保留；`AiBudgetCardsTest` 检查显式确认、折叠重开及浅色/深色截图（设备目录 `ai-09/`）。

`AiBudgetCalibrationTest` 默认跳过；同时设置 `NGA_INTEGRATION=1` 与 `AI_BUDGET_CALIBRATION=1` 后才从游客版块列表选择少量样本并读取本机模型凭证。测试不执行论坛写入，不记录 Key 或论坛正文；只输出样本坐标和用量状态。调用受阻时停止模型验证，不能把缺失 usage 记为零费用或声称默认值已校准。
