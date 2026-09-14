# 01 — Koog 接入与 ADR

**What to build:** 应用能够在 debug 与 release（含 R8）下带着 Koog 首期模块出包，并用一个假的模型执行器跑通 `chatAgentStrategy()` 的通用循环。依赖只取首期需要的模块：agent core、预置策略、ChatMemory、Persistence、EventHandler、skills、DeepSeek 客户端与 OkHttp 后端，不用聚合依赖。HTTP 后端显式传入 OkHttp factory，与 NGA 的 CookieJar 和连接池分开。新增 ADR 记录对 ADR-0004「裸 OkHttp、只用 stable」的例外：Koog 带来 Ktor 等传递依赖，DeepSeek 客户端为 beta，附上这次测得的依赖增量、包体变化与理由；版本目录写明用途与例外原因。

**Blocked by:** None — can start immediately

**Status:** implemented

- [x] `assembleDebug` 与 `assembleRelease` 通过，release 包 R8 无缺类或反射失败；记录 APK 体积前后对比
- [x] 一个离线单测用假执行器构造 Koog agent，走通「请求模型 → 工具调用 → 回传 → 最终回答」一轮
- [x] Koog 的 HTTP 请求不经过 NGA 的 OkHttp 实例，不携带论坛 Cookie（单测或拦截器断言）
- [x] 新 ADR 草稿进入 docs/adr，版本目录注释说明每个 Koog 模块用途与 beta 例外；README 分层说明若受影响则同步
- [x] 依赖树中新增的传递依赖清单附在 ADR 或票内 Comments

## Comments

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
