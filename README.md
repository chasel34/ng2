# NG2

基于 Kotlin / Jetpack Compose 的 NGA Android 客户端，支持多账号、收藏、搜索、通知、内容屏蔽与离线缓存。

## 构建

需要 JDK 17 和 Android SDK，支持 Android 12 及以上版本。在仓库根目录执行：

```bash
./gradlew :app:assembleDebug   # 调试包
./gradlew :app:assembleRelease # 正式包
./gradlew :app:testDebugUnitTest
```

APK 输出位于 `app/build/outputs/apk/`。正式包名为 `com.chasel.ng2`，调试包名为 `com.chasel.ng2.dev`，可同时安装。

依赖与 SDK 版本见 [libs.versions.toml](gradle/libs.versions.toml)。

## 代码结构

[应用源码](app/src/main/kotlin/com/chasel/ng2n/)按职责分层：

- `core`：协议、BBCode 解析与纯 Kotlin 算法。
- `data`：网络、存储与数据仓库；`data/ai` 隔离 Koog agent 与模型客户端适配，`data/topic` 提供共享的主题原始读取与缓存入口。
- `ui`：Compose 页面与组件。
- `di`：依赖注入。

`benchmark` 包含启动性能测试与 Baseline Profile 采集。

## 开发文档

- [开发约定](CLAUDE.md)
- [测试说明](docs/testing.md)
- [主题 AI 助手](docs/ai-assistant.md)
- [文档索引](docs/README.md)
- [设计参考](design/README.md)

## 致谢

- 样式参考 [Justwen/NGA-CLIENT-VER-OPEN-SOURCE](https://github.com/Justwen/NGA-CLIENT-VER-OPEN-SOURCE)。
- 反封锁逻辑参考 [BugenZhao/MNGA](https://github.com/BugenZhao/MNGA)。

## 许可证

本项目采用 [MIT License](LICENSE)。
