# Android 发布

发布工作流见 [android.yml](../.github/workflows/android.yml)。GitHub Actions 使用 JDK 17、Android SDK 和仓库 Gradle Wrapper 构建，无需 Expo 或本机打包。

## 版本规则

版本在 [app/build.gradle.kts](../app/build.gradle.kts) 的 `defaultConfig` 中维护：

- `versionName` 使用 `X.Y.Z`：修复递增修订号，新增功能递增次版本，正式稳定阶段进入 `1.0.0`。
- `versionCode` 每次发布新的 APK 递增，不能回退或使用 CI 运行次数代替。
- Git tag 必须为 `v<versionName>`，例如 `v0.2.0`。CI 根据实际 APK 输出元数据校验包名和版本。
- 同一版本不替换已发布的 APK；发布后如需修复，增加版本和 code，创建新 tag。

## CI 与发版

PR 和 `main` 推送运行离线单元测试及 release 构建；`v*` tag 在相同检查成功后自动发布 GitHub Release。CI 使用已提交的 Baseline Profile，显式关闭构建时设备采集；真机性能验证按[性能手册](perf-playbook.md)单独执行。

1. 完成代码和相关验证，更新版本号及 code。
2. 编写 `docs/releases/vX.Y.Z.md`：查看上一版本以来的提交，整理面向用户的功能与修复，不堆砌内部构建记录。
3. 将代码和发布说明提交到 `main`，确认待发提交及工作区范围。首次发布可以使用尚未发布过的现有版本。
4. 打 tag 并推送，例如：

   ```bash
   git push origin main
   git tag v0.2.0
   git push origin v0.2.0
   ```

5. 在 Actions 确认 `build`、`publish` 都成功，检查 Release 的 APK、SHA-256 校验文件和说明。Release 仅在产物完整后创建。
6. 使用已有正式包验证覆盖安装，检查账号、设置和书签保留。CI 成功不代表已验证设备升级。

产物为 `ng2-vX.Y.Z.apk` 和对应 `.sha256` 文件。测试报告与混淆映射保存在 Actions 的 `build-reports` artifact，映射应在 artifact 过期前归档用于崩溃排查。

构建失败且 Release 尚未创建时，可以修复外部环境并重新运行失败任务。代码修复应提交新版本，不移动已经对外发布的 tag。当前流程不覆盖既有 Release。

## 签名与数据保留

当前 release 沿用仓库 `app/debug.keystore`，用于兼容既有正式包。CI 必须使用相同密钥，不能临时生成 debug keystore。该密钥已经入库，不是私密的长期发布凭证；后续正式密钥迁移需配置签名继承关系，并验证 Android 12 及以上设备的覆盖升级。

保留数据要求维持包名 `com.chasel.ng2`、兼容签名、可升级的 versionCode，以及非破坏性的数据库迁移。不要通过卸载旧应用解决安装失败。开发包 `com.chasel.ng2.dev` 与正式包的数据不互通。

## 本机构建备用流程

GitHub 构建不可用时，在 JDK 17 和 Android SDK 环境执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease -Pandroid.baselineProfile.automaticGenerationDuringBuild=false
python3 scripts/prepare-release.py v0.2.0
```

将 `build/release/` 中的产物和同版本发布说明用于对应 tag 的 Release。发布前确认本地构建提交与 tag 完全一致，不重复发布正在由 CI 处理的版本。

## App 内更新

当前版本只提供 GitHub Release 分发，尚未实现 App 内检查、下载和安装。计划通过最新 Release 获取更新信息，App 内下载后调用系统安装器；这部分单独实现和验证。
