# ng2

## Agent skills

### Issue tracker

Issues 以本地 Markdown 文件形式存放在 `.scratch/<feature>/` 下。See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context：根目录 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.

## 构建

### dev client 本地打，不进 EAS 队列

本机有完整 Android 打包环境（SDK、NDK、JDK 17、`Pixel_8` AVD，`ANDROID_HOME`/`JAVA_HOME`/PATH
都在 zsh 配置里）：

```bash
pnpm exec expo prebuild --platform android          # android/ 是 gitignored 的生成物
cd android && ./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
```

gradle 是 JVM 进程，不认 shell 的 `http_proxy`，拉依赖要显式传：
`GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"`。
preview / production 出包仍走 EAS 云端，且 `eas build` 反过来必须**绕开**代理环境变量
（`env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy -u ALL_PROXY -u all_proxy …`）。
