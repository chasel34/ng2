# NG2 — NGA 原生 Android 客户端

Kotlin / Jetpack Compose 编写的个人用 NGA 论坛客户端，以阅读为主，支持多账号、收藏、搜索、通知、屏蔽与离线缓存。Android 工程位于仓库根目录；旧 Expo / React Native 实现可从 Git 历史查看。

## 构建与验证

准备 JDK 17 和 Android SDK，通过 `ANDROID_HOME` 或本机 `local.properties` 配置 SDK 路径。使用仓库自带的 Gradle wrapper；版本和 SDK 级别见 [版本目录](gradle/libs.versions.toml) 与 [wrapper 配置](gradle/wrapper/gradle-wrapper.properties)。当前 minSdk 为 31。

在仓库根目录运行：

```bash
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease           # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest         # JVM 单测
./gradlew :app:connectedDebugAndroidTest # 已连接设备上的 instrumentation 测试
./gradlew :benchmark:assemble            # 编译性能测试模块
./gradlew --stop                        # 需要释放本机构建资源时停止 daemon
```

需要网络代理时，将代理显式传入 JVM；只设置 shell 的 `http_proxy` 不足以配置 Gradle 的依赖下载。下面的地址和端口需替换为本机代理：

```bash
export https_proxy=http://127.0.0.1:7897
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
```

## 安装变体

| 变体 | applicationId | 用途 |
|---|---|---|
| release | `com.chasel.ng2` | 日常使用、真机性能验证；开启 R8 与资源收缩 |
| debug | `com.chasel.ng2.dev` | 开发调试；可与 release 并装 |

两个变体都使用仓库的 `app/debug.keystore`。`adb install -r` 仅在**同包名、同签名**时覆盖安装并保留数据；debug 与 release 的账号和数据相互独立。Kotlin namespace 为 `com.chasel.ng2n`，不要把它当成安装包名。

## 代码结构

源码根目录：[app/src/main/kotlin/com/chasel/ng2n](app/src/main/kotlin/com/chasel/ng2n/)。

| 目录 | 职责 |
|---|---|
| `core/net` | 请求模型、反封锁策略链、认证、逐参数编码、响应清洗与信封解析 |
| `core/api` | NGA 端点、字段容错解析与数据模型 |
| `core/bbcode` | BBCode 清洗、AST 与提交转义 |
| `core/local` | 骰子、匿名还原、标题样式、图片尺寸等本地算法 |
| `data` | OkHttp 传输、Android Cookie/Keystore、Room/DataStore、仓库与缓存 |
| `ui` | Compose 页面、BBCode 原生组件、主题与导航 |
| `di` | Hilt 装配 |

`core/**` 保持纯 Kotlin，禁止引入 `android.*`。正文走 BBCode → AST → Compose；WebView 用于登录和网页兜底。

测试在 `app/src/test/kotlin` 与 `app/src/androidTest`；[金样本说明](app/src/test/resources/goldens/README.md)记录解析和算法对拍数据。

`benchmark` 是独立的 `com.android.test` 模块，包含冷启动 Macrobenchmark 与 Baseline Profile 采集。性能裁决必须使用真机 release 包，流程和限制见[性能手册](docs/perf-playbook.md)及[采样脚本说明](scripts/perf/README.md)。编译通过不代表完成性能验收。

## 文档

- [文档索引](docs/README.md)：接口、存储、性能、技术决策和历史资料。
- [术语表](CONTEXT.md)：领域概念与统一命名。
- [开发约定](CLAUDE.md)：代码边界、验证与文档维护规则。
- [设计参考](design/README.md)：HTML 原型及其使用范围。

依赖版本统一维护在 `gradle/libs.versions.toml`，默认使用 stable；当前 Baseline Profile Gradle 插件的 RC 例外及原因记录在该文件中。
