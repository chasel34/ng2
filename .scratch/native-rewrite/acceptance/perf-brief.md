# 票 19 真机性能验收 · 执行简报(给验收代理)

你是**验收者**,不是修复者。只测、只记、只写报告;发现问题回填成缺陷票(编号从 **45** 起,格式照 `issues/20-*.md`),**不改 `native/` 源码**。主控负责派修,修完再叫你复核。

## 必读

1. `.scratch/native-rewrite/issues/19-performance-acceptance.md` — 十场景闸,全过才算过
2. `docs/perf-playbook.md` — 判据、陷阱、工具口径(票 02 产物),**一切以它为准**
3. `.scratch/native-rewrite/spec.md` §五 — 性能条款;`research/perf-history.md` — RN 版历史基线与已踩的坑
4. `.scratch/native-rewrite/agent-brief.md` — 工程约定(gradle 代理、内存上限、收工 `./gradlew --stop`)

## 设备与包

- 真机:小米 `25113PN0EC`(HyperOS / Android 16 / SDK 36,1220×2656 @520dpi,**120Hz**,Adreno),无线 adb。`adb devices -l` 里非 emulator 的那台;没连上就 `adb mdns services` 重新发现,大文件传输会冲断链路,`adb push` 比流式 `adb install` 稳,要写重连循环。
- **模拟器 `emulator-5554` 的任何数据一律无效,不要碰它**(另一个代理在用)。
- 被测包:`com.chasel.ng2.n`(原生,`native/`),**必须 release**(R8 + Baseline Profile)。debug 包数据无效。
  - 打包:`cd native && GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" ./gradlew :app:assembleRelease`。release 与 debug 共用 debug keystore(见 `app/build.gradle.kts`),`adb install -r` 覆盖装能保住登录态。
  - Baseline Profile:`benchmark` 模块 `useConnectedDevices = true`,先 `./gradlew :app:generateReleaseBaselineProfile`(连真机跑),确认 `app/src/release/generated/baselineProfiles/baseline-prof.txt` 生成后再 `assembleRelease`;装完用 `adb shell cmd package compile -r bg-dexopt com.chasel.ng2.n` 或 `pm dump` 里看 profile 状态,**冷启对比数据(无/有 profile)要记**。
- 对拍包:RN release 版 `com.chasel.ng2`(真机上已装,若没装用 `eas build` 产物或 `android/` 本地打——问主控),以及 anzong `gov.anzong.androidnga`(只做录屏对拍,不碰其代码)。
- MIUI 装包:`adb install` 拒装**可调试**APK(`INSTALL_FAILED_USER_RESTRICTED`),release 正常。所有者账号在真机上登录态要保住:**不要卸载**,只 `install -r`。
- **票 60(必读)**:`generateBaselineProfile` / 任何 `connected*AndroidTest` 跑完,AGP **默认会把被测 app 连同测试 APK 一起卸载**(`android.injected.androidTest.leaveApksInstalledAfterRun` 默认 false)。卸载连 `AndroidKeyStore` 里的 `ng2n.accounts.v1` 密钥一起带走,所有者登录态当场蒸发 —— 2026-08-23 那次「install -r 之后丢登录态」就是这么来的。`native/gradle.properties` 已把这条开关钉成 `true`,**跑采集/连接测试前先确认它还在**;跑完用 `adb shell pm list packages | grep ng2.n` 确认包还在。
- 登录态出问题时先看日志再下结论:`adb logcat -s ng2n-accounts`。「账号密文解不开(…)」= 数据还在钥匙没了;「Keystore 里没有 ng2n.accounts.v1」= 卸载过。凭证读失败不再静默清空,原密文会留在 `accounts.v1.unreadable`(票 60)。

## 测量纪律(踩过的坑,别再踩)

- **NGA 限流**:连续冷启动 ≥60s 间隔;成片「连不上服务器」先怀疑是自己打出来的,静置 60s 对照。
- **屏幕变暗锁 60Hz**:每次采样前 `adb shell settings put system screen_off_timeout 600000`,滚动中确认 `dumpsys SurfaceFlinger | grep -i frameRateOverride` 有 `{uid 120}`;采完改回。
- **120Hz 判据**:看 `latch2present` 是否单峰(队列振荡 vs 帧耗时是两种病);`gfxinfo` 的 Janky 百分比在真机上可用,在模拟器上不可信。逐帧录屏用 `adb shell screenrecord --bit-rate 20000000 --time-limit 30`,拉回后 `ffmpeg -vf "select=gt(scene\,0.0)" ` 或逐帧抽帧比对,对拍 RN/anzong 同一操作。
- **profileable**:release 包若 `gfxinfo` 拿不到数据,检查 manifest `<profileable android:shell="true"/>`(票 02 应已加)。
- 每场景 **30s 脚本化操作**(`input swipe` 固定坐标/速度,不用手),同一脚本跑三个包。
- 不要设 `settings put global http_proxy`。

## 十场景与输出

按票 19 列的 10 条逐项:每条给 **数值 + 判据 + 过/不过**,关键场景(1 冷启闪烁、2 首次进主题冻结、5 横滑、6 抽屉)附录屏帧表(帧号/时间戳/异常描述)。不过闸的每条开缺陷票(45 起,标题带场景号与 P 级,附复现脚本与数据)。

报告落 `.scratch/native-rewrite/acceptance/perf-report.md`:环境(设备/包 hash/profile 状态)、每场景表、缺陷票表、伞条款遍历清单、结论。录屏与原始数据放 `.scratch/native-rewrite/acceptance/perf/`(录屏 >20MB 的不进 git,放 `/Users/cola/.claude/jobs/e7f2363b/tmp/perf/` 并在报告里写路径)。

收工:`git add .scratch/native-rewrite && git commit -m "docs(native-rewrite): 票19 真机性能验收报告"`。不 push。最终回复只给:十场景过/不过清单、缺陷票列表、报告路径、commit hash。
