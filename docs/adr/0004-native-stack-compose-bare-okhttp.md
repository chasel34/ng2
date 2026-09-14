# ADR-0004：Compose 与裸 OkHttp

状态：已采用。单 `app` application 模块加 `benchmark` 测试模块，使用 MVVM + Flow。

技术栈为 Kotlin、Jetpack Compose、Navigation 3、Hilt/KSP、OkHttp、kotlinx.serialization、Coil、Room、Preferences DataStore，以及 Macrobenchmark / Baseline Profile。具体版本和 SDK 级别统一见 [gradle/libs.versions.toml](../../gradle/libs.versions.toml)，不在 ADR 复制版本清单。

默认只使用 stable。当前 Baseline Profile **Gradle 插件**使用 `1.5.0-rc01`，以兼容 AGP 9 的 AndroidComponents API；运行时 benchmark 库仍为 stable。例外原因记录在版本目录，升级时复核兼容性。

AI 模型与 agent 的框架依赖例外见 [ADR-0006](0006-koog-agent-runtime.md)；论坛网络仍遵循本决策。

选择裸 OkHttp，是因为网络层需要自定义拦截器、独立连接池、字节级响应解码，以及按请求控制 CookieJar；这些都是 OkHttp 直接提供的能力。Retrofit 的 Converter 层对按参数编码、清洗非法 JSON 的协议适配没有必要收益。

`core/net` 定义协议和策略，`data/net` 实现 OkHttp 与设备侧能力，Hilt 负责装配。正文使用原生 Compose 渲染，见 [ADR-0001](0001-native-bbcode-ast-rendering.md)。

预测性返回开启，交互允许遵循 Android 原生惯例。个别 Compose 场景若经过真机验证仍不能满足性能要求，可局部使用 AndroidView / RecyclerView，不推翻整体选型。

曾考虑 Retrofit、Ktor Client、Moshi、SQLDelight 与其他 DI 框架；最终以 Android 单平台需求、协议适配和现有测试边界为准选择上述组合。
