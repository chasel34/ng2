# Koog 接入测量

[ADR-0006](0006-koog-agent-runtime.md) 的构建与依赖证据，测量日期 2026-09-13。基线为接入前工作区（HEAD `bf7ef94`，已有设计文档修改保留），使用相同 JDK 17、SDK 与签名配置；最终比较的是未启用设备冒烟选项的普通 APK。

## APK 体积

| 变体 | 接入前（字节） | 接入后（字节） | 增量（字节） | 增量（MiB） |
| --- | ---: | ---: | ---: | ---: |
| debug | 43,608,531 | 59,381,190 | +15,772,659 | +15.04 |
| release | 5,678,527 | 7,674,294 | +1,995,767 | +1.90 |

release 开启 R8 与资源压缩；未关闭混淆、未整包保留 Koog。普通 APK 已检查不含冒烟测试类、MockWebServer、协程测试入口和测试专用网络策略。该增量只代表当前可达适配路径，后续接入更多功能可能增加包体；没有据此推断启动或运行性能。

## 离线验证

- `./gradlew --offline :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:dependencies --configuration releaseRuntimeClasspath`：通过。执行前清除 `NGA_INTEGRATION` 与 `NGA_WRITE_SMOKE`。JVM 共 1122 项，1118 通过、4 项线上冒烟按开关跳过，0 失败、0 错误；新增 Koog 3 项全部通过。
- `./gradlew --offline -PtestBuildType=release :app:connectedReleaseAndroidTest`：Pixel 8 AVD / Android API 37 上 3 项全部通过。测试主体与生产代码一起经过 R8，证明工具 schema/序列化、循环与 DeepSeek HTTP 运行路径可用；这不是付费模型、真机性能或完整 AI 产品验收。
- 设备冒烟后重新运行普通构建；最终 APK 不携带冒烟代码。设备测试成功，但工具未收集到逐测试 logcat，保留了结构化测试结果。

构建日志、测试结果与 APK SHA-256 见[本地验证记录](../../.scratch/ai-assistant/reports/01-validation/summary.json)，命令与测试结构见[测试说明](../testing.md)。

## 依赖增量

按 `releaseRuntimeClasspath` 依赖树中的唯一 `group:artifact` 统计：194 → 309，新增 115 个坐标，其中 10 个为新增直接依赖，105 个为传递依赖。此口径包含 KMP 元数据模块、Android/JVM 平台变体与 BOM，不等于 115 个运行时 JAR。除新增坐标外，`org.jetbrains:annotations` 从 `23.0.0` 解析到 `26.0.2-1`。

新增直接坐标：

```text
ai.koog:agents-core:1.2.0
ai.koog:agents-ext:1.2.0-beta
ai.koog:agents-features-event-handler:1.2.0
ai.koog:agents-features-memory:1.2.0
ai.koog:agents-features-snapshot:1.2.0
ai.koog:http-client-okhttp:1.2.0
ai.koog:prompt-executor-deepseek-client:1.2.0-beta
ai.koog:skills:1.2.0-beta
com.squareup.okhttp3:okhttp-bom:5.5.0
org.jetbrains.kotlin:kotlin-reflect:2.4.10
```

新增传递坐标（完整清单）：

```text
ai.koog:agents-core-android:1.2.0
ai.koog:agents-ext-android:1.2.0-beta
ai.koog:agents-features-event-handler-android:1.2.0
ai.koog:agents-features-memory-android:1.2.0
ai.koog:agents-features-snapshot-android:1.2.0
ai.koog:agents-tools:1.2.0
ai.koog:agents-tools-android:1.2.0
ai.koog:agents-utils:1.2.0
ai.koog:agents-utils-android:1.2.0
ai.koog:http-client-core:1.2.0
ai.koog:http-client-core-android:1.2.0
ai.koog:prompt-executor-clients:1.2.0
ai.koog:prompt-executor-clients-android:1.2.0
ai.koog:prompt-executor-deepseek-client-android:1.2.0-beta
ai.koog:prompt-executor-model:1.2.0
ai.koog:prompt-executor-model-android:1.2.0
ai.koog:prompt-executor-openai-client:1.2.0
ai.koog:prompt-executor-openai-client-android:1.2.0
ai.koog:prompt-executor-openai-client-base:1.2.0
ai.koog:prompt-executor-openai-client-base-android:1.2.0
ai.koog:prompt-llm:1.2.0
ai.koog:prompt-llm-android:1.2.0
ai.koog:prompt-markdown:1.2.0
ai.koog:prompt-markdown-android:1.2.0
ai.koog:prompt-model:1.2.0
ai.koog:prompt-model-android:1.2.0
ai.koog:prompt-processor:1.2.0
ai.koog:prompt-processor-android:1.2.0
ai.koog:prompt-structure:1.2.0
ai.koog:prompt-structure-android:1.2.0
ai.koog:rag-base:1.2.0
ai.koog:rag-base-android:1.2.0
ai.koog:serialization-core:1.2.0
ai.koog:serialization-core-android:1.2.0
ai.koog:serialization-jackson:1.2.0
ai.koog:skills-android:1.2.0-beta
ai.koog:utils:1.2.0
ai.koog:utils-android:1.2.0
com.fasterxml.jackson.core:jackson-annotations:2.21
com.fasterxml.jackson.core:jackson-core:2.21.3
com.fasterxml.jackson.core:jackson-databind:2.21.3
com.fasterxml.jackson.module:jackson-module-kotlin:2.21.3
com.fasterxml.jackson:jackson-bom:2.21.3
com.squareup.okhttp3:okhttp-sse:5.5.0
com.typesafe:config:1.4.5
io.github.oshai:kotlin-logging:8.0.01
io.github.oshai:kotlin-logging-android:8.0.01
io.ktor:ktor-client-content-negotiation:3.3.3
io.ktor:ktor-client-content-negotiation-jvm:3.3.3
io.ktor:ktor-client-core:3.3.3
io.ktor:ktor-client-core-jvm:3.3.3
io.ktor:ktor-client-logging:3.3.3
io.ktor:ktor-client-logging-jvm:3.3.3
io.ktor:ktor-events:3.3.3
io.ktor:ktor-events-jvm:3.3.3
io.ktor:ktor-http:3.3.3
io.ktor:ktor-http-cio:3.3.3
io.ktor:ktor-http-cio-jvm:3.3.3
io.ktor:ktor-http-jvm:3.3.3
io.ktor:ktor-io:3.3.3
io.ktor:ktor-io-jvm:3.3.3
io.ktor:ktor-network:3.3.3
io.ktor:ktor-network-jvm:3.3.3
io.ktor:ktor-serialization:3.3.3
io.ktor:ktor-serialization-jvm:3.3.3
io.ktor:ktor-serialization-kotlinx:3.3.3
io.ktor:ktor-serialization-kotlinx-json:3.3.3
io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.3
io.ktor:ktor-serialization-kotlinx-jvm:3.3.3
io.ktor:ktor-server-cio:3.3.3
io.ktor:ktor-server-cio-jvm:3.3.3
io.ktor:ktor-server-core:3.3.3
io.ktor:ktor-server-core-jvm:3.3.3
io.ktor:ktor-server-sse:3.3.3
io.ktor:ktor-server-sse-jvm:3.3.3
io.ktor:ktor-sse:3.3.3
io.ktor:ktor-sse-jvm:3.3.3
io.ktor:ktor-utils:3.3.3
io.ktor:ktor-utils-jvm:3.3.3
io.ktor:ktor-websocket-serialization:3.3.3
io.ktor:ktor-websocket-serialization-jvm:3.3.3
io.ktor:ktor-websockets:3.3.3
io.ktor:ktor-websockets-jvm:3.3.3
org.fusesource.jansi:jansi:2.4.2
org.jetbrains.kotlin:kotlin-bom:2.3.10
org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.11.0
org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.0
org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.9.0
org.jetbrains.kotlinx:kotlinx-io-core:0.9.0
org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.0
org.jetbrains.kotlinx:kotlinx-schema-annotations:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-annotations-jvm:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-generator-core:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-generator-core-jvm:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-generator-json:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-generator-json-jvm:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-json:0.4.4
org.jetbrains.kotlinx:kotlinx-schema-json-jvm:0.4.4
org.jetbrains.kotlinx:kotlinx-serialization-json-io:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm:1.11.0
org.reactivestreams:reactive-streams:1.0.3
org.slf4j:slf4j-api:2.0.17
org.slf4j:slf4j-simple:2.0.17
```

依赖树原始记录：[接入前](../../.scratch/ai-assistant/reports/01-validation/baseline-build.log)、[接入后](../../.scratch/ai-assistant/reports/01-validation/offline-build-and-tests.log)。
