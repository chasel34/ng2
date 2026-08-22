# 01 — 原生工程骨架(M0)

**What to build:** `native/` 下新建 Gradle 工程:app 模块 + benchmark(macrobenchmark)模块;`gradle/libs.versions.toml` 按 research/stack-2026-08.md 草稿锁版本(ADR-0004)。applicationId `com.chasel.ng2.n`、显示名 **NG2N**、versionName 0.1.0;compileSdk 37 / targetSdk 36 / minSdk 31;强制竖屏、edge-to-edge、预测性返回开;权限对齐 RN 版(INTERNET、READ_MEDIA_IMAGES、READ_MEDIA_VISUAL_USER_SELECTED、VIBRATE;不带 SYSTEM_ALERT_WINDOW 先观察)。MainActivity 移植 `preferHighestRefreshRate()`(onCreate+onResume 双请求,逻辑照抄 `plugins/with-high-refresh-rate.js`);manifest 注入 `<profileable android:shell="true"/>`(release 可测量性,性能纪律优先于审计 P3-01 的收紧建议)。release 变体开 R8(AGP 9.3 optimization DSL)+ 沿用现 keystore(小米 `install -r` 保登录态打法);深链只注册 `ng2n://` scheme,**并行期不接管 NGA 域名**(spec §三)。Hilt/KSP/serialization/compose/room/baselineprofile 插件全部就位,写一个空首屏证明管线通。

**Blocked by:** None

**Status:** in-review

- [x] 本机 `assembleDebug` 与 `assembleRelease` 出包(gradle 代理按 CLAUDE.md 的 GRADLE_OPTS 传)
- [x] Pixel_8 AVD 与小米 17 可安装,与 RN 版并存,桌面名 NG2N — AVD 侧做实;**小米 17 待所有者**(真机不在线)
- [x] release 包实测:`dumpsys` 确认 profileable 生效、滚动中 `frameRateOverride {uid 120}` 出现
- [x] `gradlew test` 与一个冒烟 instrumentation 目标可运行(空测试即可)

## Comments

### 完成摘要(2026-08-22)

`native/` 落地:root + `:app`(com.android.application)+ `:benchmark`(com.android.test +
androidx.baselineprofile),Gradle wrapper 9.7.1、AGP 9.3.1、Kotlin 2.4.10。空首屏是
`@HiltAndroidApp` Application → `@AndroidEntryPoint` MainActivity → `enableEdgeToEdge()` →
Material3 主题(深浅色跟随系统)→ Nav3 `NavDisplay` 一个 `Home` 条目。分层空包已建
(`core/net`、`core/api`、`core/bbcode`、`core/local`、`data`、`ui`、`di`),每个包放了一个
只有 KDoc 的 `package.kt` 说明该层职责与「core 禁止 import android.\*」的铁律;目录说明
写进 `native/README.md`。

逐条验收证据(命令与关键输出行):

1. **出包**。`./gradlew clean` 后 `:app:assembleDebug :app:assembleRelease
   :app:testDebugUnitTest :app:assembleDebugAndroidTest :benchmark:assemble` → `BUILD
   SUCCESSFUL`,`204 actionable tasks`。产物 `app-debug.apk` 36.5MB /
   `app-release.apk` 1.79MB(R8 + 资源收缩,`lintVitalRelease` 通过)。
2. **并存与桌面名**。`adb shell pm list packages -3` → `com.chasel.ng2` /
   `com.chasel.ng2.n` / `com.chasel.ng2.dev` 三者同在;抽屉 `uiautomator dump` 里
   `NG2N` 与 `NGA 阅读器`、`NGA 阅读器 Dev` 并列。`aapt2 dump badging app-release.apk`
   → `application-label:'NG2N'`、`package: name='com.chasel.ng2.n' versionName='0.1.0'
   compileSdkVersion='37'`、`minSdkVersion:'31'`、`targetSdkVersion:'36'`,权限恰为
   INTERNET / READ_MEDIA_IMAGES / READ_MEDIA_VISUAL_USER_SELECTED / VIBRATE
   (外加 androidx 自动塞的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`)。release 包
   `install -r` 覆盖 debug 包成功,反向也成功 —— 同一把 keystore 的打法验证过。
3. **profileable**。⚠ 票上写的 `adb shell dumpsys package com.chasel.ng2.n | grep -i
   profileable` 在 API 37 上**打不出任何东西** —— 拿已知带该标记的 RN release 版
   (`com.chasel.ng2`)做对照,同样为空,即这个平台版本的 `dumpsys package` 根本不吐这个字段,
   不是没生效。改用两条独立证据:
   - `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` →
     `E: profileable (line=41)` / `A: android:shell(0x01010594)=true`(**release 包内**的
     manifest,不是源文件);
   - `adb shell am profile start com.chasel.ng2.n …` 在这个**非 debuggable 的 release 包**上
     不报权限错 —— 平台这条路只对 debuggable 或 profileable-by-shell 放行。
4. **frameRateOverride**。比预期好:模拟器上也验到了。`adb shell dumpsys display` →
   `mFrameRateOverrides=[{uid=10233 frameRateHz=120.00001}]`、`mActiveRenderFrameRate=120.00001`
   (uid 10233 = com.chasel.ng2.n,与 `dumpsys window` 里 `mOwnerUid=10233` 对得上);
   `dumpsys window windows` 里 MainActivity 的窗口属性带
   `preferredRefreshRate=120.00001 preferredDisplayMode=1`。本机这台 Pixel_8 AVD 的
   `supportedModes` 只有一档 120Hz,所以「投 120」这件事成立、但**不构成 120Hz 手感证据**;
   真机口径仍归票 19。
5. **测试**。`:app:testDebugUnitTest` 2 个用例全绿(kotlin-test 断言 + `runTest`),
   无 `@Ignore`;`:app:connectedDebugAndroidTest` 在 emulator-5554 上
   `Starting 1 tests on Pixel_8(AVD) - 17` → `Finished 1 tests`,全绿(编译目标
   `assembleDebugAndroidTest` 也单独跑通)。
6. 空首屏在模拟器上截图确认:cream 底、edge-to-edge(内容顶到状态栏/导航条下)、
   `Displayed com.chasel.ng2.n/…MainActivity`,logcat 无 FATAL,
   `D ProfileInstaller: Installing profile for com.chasel.ng2.n`。

### 关键决定(票里留白的「实现时定」项)

- **AGP 9 内建 Kotlin,不再 apply KGP**。AGP 9.3.1 直接报错要求移除
  `org.jetbrains.kotlin.android`(「no longer required for Kotlin support since AGP 9.0」),
  所以 `libs.versions.toml` 里 `kotlin-android` 插件条目虽保留,三个 build 文件都不 apply 它。
  连带一个坑:`kotlin("test")` 那套「按测试框架自动选 flavor」的魔法随 KGP 一起没了,
  表现是 `kotlin.test.assertEquals` 能解析、`kotlin.test.Test` 解析不了。改成显式依赖
  `org.jetbrains.kotlin:kotlin-test-junit:2.4.10`(已进 toml)。
- **`kotlin { compilerOptions { jvmTarget } }`、`room { schemaDirectory }` 在 AGP 9 下仍可用**,
  `buildTypes.release { isMinifyEnabled / isShrinkResources / proguardFiles }` 也仍可用 ——
  票里提的「AGP 9.3 optimization DSL」没有强制,沿用经典写法,R8 与资源收缩都实测生效
  (release 1.79MB vs debug 36.5MB)。
- **图标自画**,没有从 RN 版搬 webp:并装期两个图标要一眼分得开。adaptive-icon =
  品牌 cream 底 + 一个矢量「N」字形(含 monochrome 层),纯 XML 零二进制。
- **窗口背景与 Compose 底色对齐**(`res/values*/colors.xml` 的 `window_background` 与
  `ui/theme/Theme.kt` 的 `BrandCream`/`BrandInk` 同值),为的是十场景闸第 1 条「冷启动逐帧
  无闪烁帧」——窗口背景与首帧 Compose 不同色就会多一帧异色闪。平台主题用
  `android:Theme.Material(.Light).NoActionBar`,**没引** com.google.android.material 的
  XML 主题(整个 UI 是 Compose,平台主题只管首帧之前那一段)。
- **首屏挂了个 `contentDescription = "ng2n-skeleton-ready"`**,给票 19 的 uiautomator 当
  「首帧内容已出」锚点。
- `gradle.properties`:`org.gradle.jvmargs=-Xmx2g`(照简报卡死),但 **metaspace 从 768m
  提到 1g** —— 768m 时 R8 + KSP + Compose 编译器插件同场会把 daemon 挤爆
  (「Daemon will expire … out of JVM Metaspace」)。堆没动。configuration cache(含
  parallel)、build cache、parallel 全开,实测全程可用。
- 依赖里**提前放了 okhttp / okhttp-coroutines / coil / room / datastore**(都还没被引用)。
  理由:一次性证明 ADR-0004 锁的整条栈能解析、能过 R8。release 包 1.79MB 说明未引用的部分
  被 R8 全剥了,不构成负担;票 03/06/12/14 直接用。

### 对 RN 版的有意偏离

- **预测性返回开**(`android:enableOnBackInvokedCallback="true"`,RN 版
  `predictiveBackGestureEnabled: false`)—— ADR-0004 已裁定,Nav3 的 NavDisplay 原生适配。
- **深链只有 `ng2n://`**,不注册 NGA 域名(spec §三),避免并行期跟 RN 版抢
  `https://bbs.nga.cn` 的打开权。
- `allowBackup="false"`、`supportsRtl="false"`:自用 app,少一份跨设备恢复的脏数据来源。

### 未完成 / 需要所有者(真人)介入

- **小米 17 真机安装未验** —— 真机不在线(`adb devices` 只有 emulator-5554)。keystore 与
  RN 版同一把、debug/release 同签,`install -r` 的打法在模拟器上双向验过,真机侧仍待所有者
  插线跑一次:`adb install -r native/app/build/outputs/apk/release/app-release.apk`。
  MIUI 可能拒装可调试 APK,release 包不可调试应无碍。
- **120Hz 手感**不在本票裁决(spec §五:模拟器与 debug 包的数字永不用于性能裁决)。本票只
  证明了「窗口投了 120、系统给了 uid 级 frameRateOverride」。

### 与 ADR-0004 的一处被迫偏离(需主控知晓)

`androidx.baselineprofile` **1.4.1 与 AGP 9 不兼容**,apply 阶段直接抛
「Module `:app` is not a supported android module」。字节码实证:1.4.1 的
`AgpPlugin.configureWithAndroidPlugin` 走的是 AGP 老 variant API —
`instanceof com.android.build.gradle.AppExtension` → `getApplicationVariants()`,AGP 9 的
`android {}` 扩展已不是这个类型,三个分支全落空 → 抛异常。1.5.0-rc01 的同一个类里
**已经没有** 对 `AppExtension` 的任何引用(改用 `AndroidComponentsExtension`)。

ADR-0004 锁的 AGP 9.3.1 不能退(Compose BOM 2026.08.00 强制 AGP ≥9.1.1),所以:
`baselineprofilePlugin = "1.5.0-rc01"` **只给 Gradle 插件**,运行时库
(`benchmark-macro-junit4`)仍留 1.4.1。这是全表**唯一**一处非 stable,toml 里有醒目注释,
1.5.0 转正后删掉该行、统一回 `benchmark`。实测:插件正常生成
`nonMinifiedRelease` / `benchmarkRelease` 变体,`:benchmark:assemble` 通过。
**请主控裁决是否接受**;若不接受,替代方案是骨架期不 apply 该插件(benchmark 模块降级为
纯 `com.android.test`),代价是票 19 开工前必须先解决这件事。

### 发现的票外问题

- API 37 给本 app 自动授予了 `android.permission.ACCESS_LOCAL_NETWORK`
  (`dumpsys package` 里 `granted=true`),我们没有声明。Android 17 把本地网络访问单独拆权限,
  看起来是随 INTERNET 派生的。对 NGA 这种纯公网访问应无影响,记一笔备查。
- `stripDebugSymbols` 报 `Unable to strip … libandroidx.graphics.path.so`(app)与
  `libbenchmarkNative.so / libtracing_perfetto.so`(benchmark)。只是没剥符号,不影响功能与
  性能;若在意 release 体积可后续跟进。
- RN 版桌面名实际是「NGA 阅读器」,不是 `app.json` 里的 `NG2`(应该另有本地化 string 覆盖)。
  与本票无关,但主控在写并存验收口径时可能会用到这个事实。
