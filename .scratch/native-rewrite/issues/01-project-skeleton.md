# 01 — 原生工程骨架(M0)

**What to build:** `native/` 下新建 Gradle 工程:app 模块 + benchmark(macrobenchmark)模块;`gradle/libs.versions.toml` 按 research/stack-2026-08.md 草稿锁版本(ADR-0004)。applicationId `com.chasel.ng2.n`、显示名 **NG2N**、versionName 0.1.0;compileSdk 37 / targetSdk 36 / minSdk 31;强制竖屏、edge-to-edge、预测性返回开;权限对齐 RN 版(INTERNET、READ_MEDIA_IMAGES、READ_MEDIA_VISUAL_USER_SELECTED、VIBRATE;不带 SYSTEM_ALERT_WINDOW 先观察)。MainActivity 移植 `preferHighestRefreshRate()`(onCreate+onResume 双请求,逻辑照抄 `plugins/with-high-refresh-rate.js`);manifest 注入 `<profileable android:shell="true"/>`(release 可测量性,性能纪律优先于审计 P3-01 的收紧建议)。release 变体开 R8(AGP 9.3 optimization DSL)+ 沿用现 keystore(小米 `install -r` 保登录态打法);深链只注册 `ng2n://` scheme,**并行期不接管 NGA 域名**(spec §三)。Hilt/KSP/serialization/compose/room/baselineprofile 插件全部就位,写一个空首屏证明管线通。

**Blocked by:** None

**Status:** open

- [ ] 本机 `assembleDebug` 与 `assembleRelease` 出包(gradle 代理按 CLAUDE.md 的 GRADLE_OPTS 传)
- [ ] Pixel_8 AVD 与小米 17 可安装,与 RN 版并存,桌面名 NG2N
- [ ] release 包实测:`dumpsys` 确认 profileable 生效、滚动中 `frameRateOverride {uid 120}` 出现
- [ ] `gradlew test` 与一个冒烟 instrumentation 目标可运行(空测试即可)
