# 60 — P1:真机上覆盖装新包之后登录态丢失(账号头与需登录接口全成游客态)

**Status:** verified

**Severity:** P1(所有者的登录态是不可再生资产 —— 真机只有一台、账号只有一份,
重登要走 WebView 手动输密码;而验收流程本身每装一次包就可能再毁一次。
功能没坏,但**验收链会周期性摧毁被验收对象的前置条件**)

## 现象

小米真机(Android 16 / HyperOS),`com.chasel.ng2.n` **release**:

1. 装着 `34589c6` 时代的 release 包,所有者账号已登录、抽屉账号头显示用户名;
2. `adb install -r` 覆盖装 `adf3a3c` 的 release 包(**同一把 debug keystore,签名一致**);
3. 起来之后抽屉账号头 = 「未登录」,所有需登录接口按游客态走。

验收代理确认非 UI 异常(`.scratch/native-rewrite/acceptance/perf-report.md`
「登录态:初轮由所有者完成;本次新包复验时抽屉显示"未登录",验收未卸载、未清数据、
未代替所有者重登」)。release 不可 `run-as`,当时无法判断是「数据没了」还是「解不开」。

同一台机此前多次 `install -r` 覆盖装都保住了登录态(spec §四、perf-brief 都把
「同 keystore、install -r 保登录态」当成既定打法),所以第一反应是这次的包有问题。

## 根因

**不是包的问题,是包被装上去之前 app 已经被卸载了。**

`34589c6`(票 51 收口:提交 Baseline Profile)与 `adf3a3c`(票 19 复验前**重采**
baseline profile)之间,`native/` 侧**没有任何一行**碰过凭证存取:

```
git diff --stat 34589c6..adf3a3c -- native/
```
只有 `ui/` 若干屏、两处 math、baseline-prof.txt 和单测。
`data/account/**`、`di/DataModule.kt`、`AndroidManifest.xml`、`proguard-rules.pro`、
`app/build.gradle.kts`、`libs.versions.toml` **全部零改动**
(`data/account/` 最后一次改动还停在票 15 的 `a32fd95`)。于是先排掉:

- 序列化 schema / DataStore 文件名 / Keystore 别名变更 —— 没变(`accounts.v1`、
  `ng2n-accounts`、`ng2n.accounts.v1` 三个字面量一字未动);
- R8 把 `@Serializable` 字段改名 —— 不成立两次:proguard 规则没变,而且
  kotlinx.serialization 的 `PluginGeneratedSerialDescriptor` 里元素名是**编译期字面量**,
  R8 改 Kotlin 字段名不会改 JSON 键;
- backup 恢复 —— `android:allowBackup="false"`,没有这条路;
- 票 35 的 UA 锁 —— 提交 `16e6d1e` **早于** `34589c6`,不在窗口内;而且 UA 变化只会让
  服务端 session 失效,本地账号表还在、账号头照样显示用户名,与现象不符。

窗口里唯一碰过那台真机的动作是 **`adf3a3c` 那次 `generateBaselineProfile`**。
`benchmark/build.gradle.kts` 里 `baselineProfile { useConnectedDevices = true }`,
采集走的是 `:benchmark:connectedNonMinifiedReleaseAndroidTest`。而 AGP 的
connected androidTest **跑完默认把测试 APK 和被测 app 一起 `pm uninstall`** ——
开关就是 `BooleanOption` `android.injected.androidTest.leaveApksInstalledAfterRun`
(默认 `false`;在 AGP 9.3.1 的 `com/android/build/gradle/options/BooleanOption.class`
里能直接翻到这个字符串),本仓库从来没设过它。

卸载的后果比「清数据」更彻底:

- `/data/data/com.chasel.ng2.n/files/datastore/ng2n-accounts.preferences_pb` 没了;
- **`AndroidKeyStore` 里别名 `ng2n.accounts.v1` 的那把 AES 密钥也一并没了** ——
  keystore 条目绑 app UID,卸载即销毁。所以就算事后把文件恢复回去也解不开。

随后的 `adb install -r` 是装到一个**空数据目录**上,`install -r` 三个字给了
「只是覆盖」的错觉。旁证:perf-report 里那句「先做一次不计样本的**首装**预热(244 ms),
排除**首次安装数据初始化**」——写报告的人已经看见这是一次首装了,只是没把它和
登录态丢失连起来。

一句话:**采一次 baseline profile 就卸一次 app,登录态跟着 keystore 密钥一起蒸发。**

### 为什么现场分不清「丢了」还是「解不开」

因为凭证读写路径上**一行日志都没有**,而且失败即静默降级:

- `KeystoreCrypto.encrypt/decrypt` 全是 `runCatching{}.getOrNull()`,
  `AEADBadTagException`(钥匙换了)和 `KeyStoreException`(别名没了)在外面看长一个样;
- `KeystoreCrypto.secretKey()` 发现别名不在就**默默新建一把**,把「密钥丢了」这个
  最关键的信号原地抹平;
- `AccountStore.decodeState()` 把「没存过」和「存了但解不开」折成同一个空表;
- `DataModule` 给凭证 DataStore 挂的是 `ReplaceFileCorruptionHandler { emptyPreferences() }`,
  文件坏了直接换成空的,证物当场销毁。

## 二次隐患(同一次排查里发现,尚未发作但迟早会)

`AccountStore` 的读—改—写有两处**会把一次读失败升级成永久数据丢失**:

1. `mutate()` 拿 `decodeState()` 的结果当写入基线。所以「这次解不开」之后的第一次写
   (切号 / 重新登录 / 甚至只是改个显示名)会把空表加密回去,**原密文被覆盖**——
   本来只是钥匙暂时不对,写完就真的没了,再也无从判断。
2. 加密失败时 `prefs.remove(KEY)`:Keystore 临时不可用的那一下,盘上那份**好好的**
   凭证被顺手删掉。注释写的是「写不进就只活在内存」,代码干的是「写不进就把已有的毁了」。

## 修法

### 1. 堵住根因:连接测试跑完不许卸包

`native/gradle.properties`:

```properties
android.injected.androidTest.leaveApksInstalledAfterRun=true
```

这条一加,`generateBaselineProfile`、`:app:connectedDebugAndroidTest`(票 15 的
设备侧登录态冒烟)、`:benchmark:connectedCheck` 跑完都会**留包**,
`/data/data` 与 keystore 密钥都不动,同签名 `install -r` 才真的是覆盖。

### 2. 凭证读取失败:有日志、有降级、绝不毁证

- 新增 `AccountStoreLog` 接缝(`data/account/KeystoreCrypto.kt`):真机装
  `AndroidAccountStoreLog`(logcat tag `ng2n-accounts`),单测装 `AccountStoreLog.NONE`。
  收成接口的理由同票 15 的 `AccountCrypto` —— 本工程没开
  `unitTests.isReturnDefaultValues`,`android.util.Log` 在 JVM 单测里是会抛的桩,
  失败路径上一旦直接写 `Log.w` 那条路径就再也测不了。**只打异常类名,uid / cid / 密文不进 logcat。**
- `KeystoreCrypto`:`encrypt` / `decrypt` 失败都告警并带上异常类名
  (`AEADBadTagException` = 钥匙换了、`KeyStoreException` = 别名没了,排查时分得开);
  `secretKey()` 新建密钥时告警 —— **盘上有密文却要新建钥匙,就是「密钥没了、数据还在」的实锤**,
  票 60 这次真机上本该有的就是这一行。
- `AccountStore.readStored()` 取代 `decodeState()`,返回 `Readable` / `Unreadable(blob, reason)`:
  - 读:`Unreadable` → 仍然退游客态、绝不抛(契约不变),但**告警一次**且密文原样留在盘上;
  - 写:`Unreadable` 时把原密文挪到留证键 `accounts.v1.unreadable` 再从空表起,
    **永不静默覆盖**;
  - 加密失败:目标是空表(退光账号)才真删,否则保持盘上旧值不动 ——
    重启后回到上一次成功落盘的账号,而不是游客态。
- `DataModule` 的凭证 DataStore:损坏时先把文件另存 `ng2n-accounts.preferences_pb.corrupt`
  再重建,并告警。设置那份坏了当空的重来无所谓,凭证这份不行。

### 3. 改动清单

| 文件 | 改动 |
|---|---|
| `native/gradle.properties` | `leaveApksInstalledAfterRun=true`(**根因修复**) |
| `native/app/src/main/kotlin/com/chasel/ng2n/data/account/KeystoreCrypto.kt` | `AccountStoreLog` / `AndroidAccountStoreLog`;三处失败告警 |
| `native/app/src/main/kotlin/com/chasel/ng2n/data/account/AccountStore.kt` | `Stored` 密封接口 + `readStored`;`mutate` 不再毁证、不再删旧值 |
| `native/app/src/main/kotlin/com/chasel/ng2n/di/DataModule.kt` | 绑 `AccountStoreLog`;凭证文件损坏留 `.corrupt` |
| `native/app/src/test/.../AccountStoreTest.kt` | 四条新用例 |
| `.scratch/native-rewrite/acceptance/perf-brief.md` | 验收纪律:采 profile / 跑连接测试前先确认这条开关 |

## 验证

### 已验(本地)

- `:app:assembleDebug :app:testDebugUnitTest` 绿;
- `:app:assembleRelease` 出包正常(R8 规则未动,新增的 `fun interface` / 密封接口不受影响);
- 新增单测四条:
  - 解不开的存档不会被下一次写入覆盖掉,原密文进 `accounts.v1.unreadable`;
  - 存档读不出时读、写两侧都告警;
  - 加密失败时盘上旧存档保持不变(换实例重读仍是原账号);
  - 加密失败但目标是空表(退光)时照样清干净。

### 待验(真机,下次装机由验收代理执行)

根因发生在设备与构建流程之间,本地无法复现。下次上真机时按序走:

1. 确认当前登录态:抽屉账号头显示用户名;
2. `cd native && ./gradlew :app:assembleRelease`,`adb install -r app-release.apk`;
3. **仍然登录** → 第 2 条通过(同签名覆盖装保住凭证);
4. 再跑一次采集:`./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest`
   (或 `generateBaselineProfile`);跑完 `adb shell pm list packages | grep ng2.n`
   **必须还在**,冷启后**仍然登录** → 第 1 条(根因修复)通过;
5. 反证一次日志通路(可选,需要重登一次):`adb shell cmd package clear ...` 不要用,
   改用 debug 包 + `adb logcat -s ng2n-accounts`,确认凭证读失败时有告警行。

**验收纪律(写进 perf-brief):真机上跑任何 `connected*AndroidTest` / baseline profile
采集之前,先确认 `native/gradle.properties` 里那条 `leaveApksInstalledAfterRun=true`
还在。它没了,下一次采集就会再毁一次所有者的登录态。**

## Comments

- 2026-08-25:开票并修复。根因判定依据是「窗口内 `native/` 零凭证相关改动 + 窗口内唯一
  设备侧动作是 baseline profile 采集 + AGP 默认卸包」三者合围,以及 perf-report 里
  「首装预热」那句旁证。没有真机 logcat / `pm` 事件日志留存,所以严格说是**强推断而非直接实证**;
  第 2 条(读失败不毁证 + 有日志)是无论根因判定对错都该修的部分,且已用单测钉死 ——
  即便根因另有其人,下一次再丢也能从 logcat 直接读出是「钥匙没了」还是「文件没了」。
- 2026-08-27:真机合并复验通过。`dumpsys package` 记录本包 `lastUpdateTime=2026-08-27
  21:07:59`，当天覆盖安装后的抽屉仍显示「已登录 1 个账号」与
  `当前：lemon43(67296151)`；release APK MD5 与交付值一致为
  `2c985a568f20326ff44aae592edcd02d`。本轮未代登录、未卸载、未清数据，按票面口径判
  **verified**。证据：`acceptance/perf/t60-login-state.xml`、
  `acceptance/perf/t59-drawer-ink.png`。
