# ng2

Kotlin / Jetpack Compose 原生 Android 项目，工程位于仓库根目录。

## 项目入口

- 构建、包名与分层：[README.md](README.md)。
- CI、版本规则、签名与 GitHub Release 发布：[发布说明](docs/releasing.md)。
- 文档导航：[docs/README.md](docs/README.md)。领域命名遵循 [CONTEXT.md](CONTEXT.md)，修改架构前阅读相关 [ADR](docs/adr/)。
- 本地需求与 Issue 放在 `.scratch/<feature>/`，约定见 [issue-tracker.md](docs/agents/issue-tracker.md)。旧票和诊断记录用于追溯；当前实现以 Kotlin 源码与构建配置为准。

## 开发约定

- `app/src/main/kotlin/com/chasel/ng2n/core/**` 保持纯 Kotlin，不引入 `android.*`；设备 API、网络传输和持久化实现放在 `data`，通过 `di` 装配。
- 正文使用 BBCode → AST → Compose，不在楼层流中嵌入 WebView。
- NGA 请求显式声明 `Operation.READ` 或 `Operation.WRITE`。写请求固定发起账号，只走 direct，不自动轮换、换号或重放；读请求按 [ADR-0002](docs/adr/0002-anti-block-chain-first-class.md) 处理。
- 依赖和 SDK 版本统一维护在 `gradle/libs.versions.toml`；新增依赖说明用途，保留现有版本例外的理由。
- 修改公开行为、构建方式或架构时同步更新相应文档；不要把已经结束的票号或临时机器配置写成长期前提。README 只保留项目通用介绍与入口，不写本机配置或历史迁移细节。

## 注释约定

- 代码能够表达清楚的内容不写注释，不复述名称、类型、控制流程或测试断言。
- 注释只说明所在代码当前自身的必要信息，例如非显然的原因、约束、单位、协议差异或并发不变量，表述简短准确。
- 不在注释中写票号、任务完成情况、开发日记、迁移历史，也不解释已经删除的功能；历史记录放在提交、Issue 或相应文档中。
- 本约定同样适用于测试、构建配置、脚本、资源文件及文档字符串。许可证、工具指令、shebang 和必要的生成来源说明应保留。
- 修改代码时同步维护相关注释，删除失效或多余的说明；清理注释不得改变执行逻辑、字符串数据或必要换行。

## 验证

使用 JDK 17，配置 Android SDK，在仓库根目录按改动范围选择检查：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

默认执行相关离线测试，修复本次改动造成的失败并复测。联网冒烟由 `NGA_INTEGRATION` 控制，真实账号写操作另由 `NGA_WRITE_SMOKE` 控制；按任务已授权范围启用，具体边界见 [测试说明](docs/testing.md)。

实现任务完成于行为落地、相关验证通过、必要文档同步；遇到阻塞时说明已完成部分、失败证据和缺失条件。

需要设备的验证使用 `:app:connectedDebugAndroidTest`。纯文档变更检查链接、路径和与源码的一致性即可。

性能结论必须来自真机 release 包，遵循 [perf-playbook.md](docs/perf-playbook.md)。正式包名 `com.chasel.ng2`，开发包名 `com.chasel.ng2.dev`，采样前确认前台变体。
