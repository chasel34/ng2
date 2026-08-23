# 51 — P1:Baseline Profile 采集为空,票 19 release 性能验收被阻断

**Status:** open

**Severity:** P1(无法生成票 19 强制要求的 R8 + Baseline Profile release 包,十场景数据无有效被测基线)

## 现象

在指定小米真机 `192.168.0.101:40039`(`25113PN0EC` / `pudding`,Android 16,SDK 36)
运行票 19 规定任务:

```bash
cd native
ANDROID_SERIAL=192.168.0.101:40039 \
GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" \
./gradlew :app:generateReleaseBaselineProfile --console=plain
```

MIUI 安装确认放行后,测试确实在 `25113PN0EC - 16` 上执行,但
`BaselineProfileGenerator.startup` 失败:

```text
java.lang.IllegalStateException: Generated Profile is empty, before filtering.
Ensure your profileBlock invokes the target app, and runs a non-trivial amount of code
at androidx.benchmark.macro.BaselineProfilesKt.collect(BaselineProfiles.kt:134)
at com.chasel.ng2n.benchmark.BaselineProfileGenerator.startup(BaselineProfileGenerator.kt:20)
```

随后 `StartupBenchmark.coldStartupWithBaselineProfile` 被跳过,Gradle 任务失败。
`native/app/src/release/generated/baselineProfiles/baseline-prof.txt` 不存在。

## 排除项

- Gradle 通过 `ANDROID_SERIAL` 锁定指定真机;日志设备为 `25113PN0EC - 16`,未使用模拟器。
- 本轮安装已成功且测试进入采集逻辑,不是 `INSTALL_FAILED_USER_RESTRICTED`。
- 被采集的 `app-nonMinifiedRelease.apk` 包名为 `com.chasel.ng2.n`,SHA-256
  `db10ecccb254710eba3d35768d783a18c3df55e9bc7bd8dccd572be8d61cfd5a`,
  `apkanalyzer manifest debuggable` 为 `false`。
- 前一轮曾因无线 adbd 中断导致 instrumentation 被杀;连接恢复并重新放行安装后,
  本轮得到的是上述可重复到采集层的明确空 profile 错误,两者不是同一失败。
- 验收者未修改 `native/` 源码,也未继续打包一个无 profile 的 release 冒充有效样本。

## 期望

`:app:generateReleaseBaselineProfile` 在指定真机成功完成,生成非空
`app/src/release/generated/baselineProfiles/baseline-prof.txt`;之后 `assembleRelease`
将该 profile 打入 release APK,才能继续票 19 十场景验收与 profile 生效核验。

## 原始证据

- 测试报告:`native/benchmark/build/reports/androidTests/connected/nonMinifiedRelease/index.html`
- 测试结果:`native/benchmark/build/outputs/androidTest-results/connected/nonMinifiedRelease/`

