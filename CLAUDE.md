# ng2

Kotlin / Jetpack Compose 原生 Android 项目，工程位于仓库根目录。

## 项目文档

- 构建与架构：`README.md`。
- 术语与技术决策：`CONTEXT.md`、`docs/adr/`。
- 本地 Issue：`.scratch/<feature>/`，流程见 `docs/agents/issue-tracker.md`。
- `core/**` 保持纯 Kotlin，不引入 `android.*`。

## 构建

使用 JDK 17，设置 `ANDROID_HOME`，在仓库根目录运行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

依赖版本统一维护在 `gradle/libs.versions.toml`。需要代理时通过 `GRADLE_OPTS` 显式传入 JVM 代理参数。
