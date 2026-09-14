### 2026-09-13 实现记录

Status: implemented

验收结果：

- 接入 8 个首期 Koog 直接模块，无聚合依赖。预置策略和压缩节点由 core 提供；ChatMemory、Persistence、EventHandler、skills、文件工具、DeepSeek 与 OkHttp 后端均已解析。Koog stable 为 1.2.0；DeepSeek、skills 与文件工具为配套 1.2.0-beta，各模块用途及例外理由已写入版本目录。
- `KoogAgentFactory` 使用原生 `chatAgentStrategy()`，提供可注入执行器、配置、工具、feature 回调及有生命周期管理的运行入口。假执行器测试实际构造 agent，验证请求、工具参数执行、调用 ID 与结果回传、最终回答顺序。
- `DeepSeekClientFactory` 显式传入 `OkHttpKoogHttpClient.Factory()`，只注册 DeepSeek。MockWebServer 验证真实 Koog HTTP 序列化/响应解码；论坛请求携带测试 Cookie，模型请求不携带论坛 Cookie 或拦截器标记，使用不同连接，响应 Set-Cookie 也不被重放。
- 修复 Koog JVM-only OkHttp 后端与 Android utils 的 IO 辅助类名差异：只增加二进制转接入口，委托原有 Android dispatcher。R8 对 Ktor 已捕获异常的可选 JMX 桌面调试探测使用两条精确规则，无全局忽略缺类、无整包 Koog keep、无关闭压缩。
- 新增 ADR-0006 草稿及测量附件，补充 ADR-0004 例外、README 分层和测试说明。依赖树唯一坐标 194 → 309，新增 115 个（10 个新增直接坐标、105 个传递坐标，含 KMP 元数据、平台变体及 BOM）；完整新增传递清单在 `docs/adr/0006-koog-integration-measurements.md`，原始树在验证日志。明确记录 skills 的 rag-base 文件系统依赖及 core 带来的 Ktor、Jackson、OpenAI 等传递依赖，不启用其他服务商、向量检索或长期记忆。
- 已对照 `design/ai-assistant/README.md` 与实现票分工：01 是框架、构建和 ADR 接入票，没有对应的独立产品界面；设置、面板、历史等界面由 03–12 票覆盖。本票没有用设计稿示例额度或假对话生成产品界面。

验证结果（JDK 17，关闭 NGA_INTEGRATION / NGA_WRITE_SMOKE）：

- `./gradlew --offline :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:dependencies --configuration releaseRuntimeClasspath`：BUILD SUCCESSFUL。1122 项 JVM 测试，1118 通过、4 项线上冒烟按开关跳过，0 失败、0 错误；新增 Koog 3 项全部通过。
- `./gradlew --offline -PtestBuildType=release :app:connectedReleaseAndroidTest`：Pixel 8 AVD / API 37 上 3/3 通过、0 跳过。测试主体与生产代码共同经过 R8，由仅使用 Android 平台 API 的 runner 调用，验证工具 schema/序列化、通用循环和模型 HTTP 路径无运行缺类或反射失败。设备测试工具未收集到逐项 logcat，但结构化结果完整保存。
- 设备测试后重新执行普通 debug/release 构建。最终 APK 不包含冒烟测试类、测试专用网络策略、MockWebServer 或协程测试入口；冒烟仅对 127.0.0.1 开放 HTTP。普通 release 启用 R8 和资源压缩。
- debug APK：43,608,531 → 59,381,190 字节，增加 15,772,659 字节（15.04 MiB）。release APK：5,678,527 → 7,674,294 字节，增加 1,995,767 字节（1.90 MiB）。SHA-256 与测试统计见 `01-validation/summary.json`。数据只反映当前接入路径，不作为真机性能或完整 AI 功能最终包体结论。
- `git diff --check`：通过。文档本地链接、文件路径、票与报告一致性已检查。未执行真实模型付费请求、论坛写操作或 git commit；原有设计和其他功能工作区改动保留。

改动文件清单：

- `README.md`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/proguard-test-rules.pro`
- `app/proguard-koog-smoke-rules.pro`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/KoogAgentFactory.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/DeepSeekClientFactory.kt`
- `app/src/main/kotlin/ai/koog/utils/io/CoroutinesJvmBridge.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/KoogAgentFactoryTest.kt`
- `app/src/koogTest/kotlin/com/chasel/ng2n/data/ai/DeepSeekClientFactoryTest.kt`
- `app/src/koogAndroidTest/java/com/chasel/ng2n/ai/KoogSmokeInstrumentation.java`
- `app/src/koogSmoke/AndroidManifest.xml`
- `app/src/koogSmoke/res/xml/koog_smoke_network_security.xml`
- `docs/README.md`
- `docs/testing.md`
- `docs/adr/0004-native-stack-compose-bare-okhttp.md`
- `docs/adr/0006-koog-agent-runtime.md`
- `docs/adr/0006-koog-integration-measurements.md`
- `.scratch/ai-assistant/spec.md`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/issues/01-koog-integration-and-adr.md`
- `.scratch/ai-assistant/reports/01-impl.md`
- `.scratch/ai-assistant/reports/01-validation/baseline-build.log`
- `.scratch/ai-assistant/reports/01-validation/offline-build-and-tests.log`
- `.scratch/ai-assistant/reports/01-validation/release-smoke.log`
- `.scratch/ai-assistant/reports/01-validation/release-smoke.xml`
- `.scratch/ai-assistant/reports/01-validation/summary.json`

### 2026-09-13 评审第 1 轮修复

评审来源：`01-review-1.md`。接受唯一的 P3 问题，已全部修复，无不同意项。

本轮修复说明：

- 直接更新 `spec.md` 与 `koog-best-practices.md` 的当前正文，删除“尚未开始代码实现”“未完成 Android 构建”“未安装依赖、改动应用源码”等过时结论。明确框架接入、离线构建与 R8 设备冒烟已完成，业务能力及真实模型联调仍待完成，并引用 ADR-0006 及其测量附件。
- 修正模块边界：skills、框架文件工具与 DeepSeek 客户端使用配套 beta；不启用检索、嵌入、向量存储或长期记忆，允许文件系统接口所需的 `rag-base` / `rag-base-android` 传递依赖。同步说明核心的 Ktor、Jackson、OpenAI 传递依赖及 IO 兼容桥的包名例外。
- 检查关联文档并同步修正 `technical-design.md` 中仍待新增 ADR、接入和构建验证的表述，将已完成的票 01 与待实施的读取层抽取区分开；修正 `cost-and-recovery.md` 中对全项目“未新增依赖、修改源码”的过时概括，保留其预算与异常恢复业务尚未实现的状态。
- 四份方案文档的带日期 Comments 历史记录逐字保留。本轮只修改文档和验证记录，未修改应用代码、构建配置或依赖，未执行 git commit。

本轮验证：

- 使用 JDK 17，清除 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`，执行 `./gradlew --offline :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest --rerun`：BUILD SUCCESSFUL。普通 debug/release 构建通过；单测任务实际重跑，共 1122 项，1118 通过、4 项线上冒烟按开关跳过，0 失败、0 错误。日志见 `01-validation/review-1-fix-build.log`。
- 四份方案正文的过时状态扫描、本地 Markdown 链接与路径检查、与 ADR-0006 和版本目录的一致性核对、历史 Comments 未改动检查均通过；`git diff --check` 通过。
- 本轮为纯文档修订，未重复设备测试。正文中的 R8 设备冒烟完成状态依据实现及评审已经通过的 3/3 测试记录，不将其表述成本轮重新执行，也不据此声称业务联调已完成。

本轮最新改动文件清单：

- `.scratch/ai-assistant/spec.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/cost-and-recovery.md`
- `.scratch/ai-assistant/reports/01-impl.md`
- `.scratch/ai-assistant/reports/01-validation/review-1-fix-build.log`
