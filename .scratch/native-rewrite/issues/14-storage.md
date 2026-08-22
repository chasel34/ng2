# 14 — 存储层(M3)

**What to build:** 三库直译(research/inventory.md §7)+ 修 P2-04:
- **Room**:`browse_history`(tid 主键,历史与阅读进度同一条记录、200 条上限、1s 节流批刷、updated_at 索引);`topic_cache`((tid,page) 主键、payload=序列化信封(AST 可序列化直接存)、100 帖且 32MB、LRU 按 usedAt **整帖驱逐**、图片字节不计入预算);`notification_read`((uid,id) 主键、id=客户端合成 `${ts}-${type}-${tid}-${pid}`、通知条目本身不持久化)。
- **Preferences DataStore**:承接 MMKV 全部键(设置逐字段容错解析、主题模式、网络开关、本地屏蔽规则、搜索历史、版块树缓存 24h SWR、已读公告、签到日期、诊断日志 50 条、**按 uid 分键**的收藏反向索引、图片尺寸缓存)。
- **凭证**:多账号+currentUid(`accounts.v1` 语义:每请求现读、切号下个请求生效),存 Android Keystore 加密的 DataStore(或 EncryptedFile,实现时定,记录进 Comments)。
- **修 P2-04**:冷启动路径零同步磁盘 IO——所有 load 走后台协程,首屏不等待非必需数据。
- 「换结构就换 key/表名,老数据作废」策略照抄,不做迁移机制;两个会话级数据集(子版块本地覆盖、点赞标记)**保持不持久化**。**修 P1-04**:诊断日志脱敏 fav 码/搜索词/屏蔽词表。

**Blocked by:** 01

**Status:** resolved

- [x] 缓存上限/驱逐/节流语义单测与 RN 版一致
- [x] StrictMode 验证冷启无主线程磁盘 IO
- [x] 诊断日志导出样本人工检查无敏感字段

## Comments

### 2026-08-22 — 票 14 交付(子代理)

**完成摘要**

- Room 库 `ng2n.db`(`data/db/`):三张表照抄 RN 版列名与主键,`browse_history.updated_at` /
  `topic_cache.used_at` 各带一条 DESC 索引;DAO 全 suspend/Flow,只做薄层,
  两个 `@Transaction` 方法把「写一条 + 删被淘汰的」收进一个事务。
- 纯 Kotlin 策略类(JVM 单测直接跑,**没引 Robolectric**):
  `cache/TopicCachePolicy.kt`(聚合/LRU 预算/整帖驱逐/字节数/文案)、
  `history/HistoryPolicy.kt`(200 条 LRU、只前进的进度、时间文案)、
  `history/ReadFloorThrottle.kt`(1s 节流批刷的状态机)、
  `notifications/NotificationPolicy.kt`(稳定 ID、只增不覆盖的合并、已读集合)、
  `settings/`(设置表逐字段容错、收藏反向索引、屏蔽规则、搜索历史、签到日、版块树 SWR)、
  `diagnostics/DiagnosticLog.kt`(脱敏 + 50 条上限 + 文本形态)。
- `settings/SettingsStore.kt`:一个 Preferences DataStore 承接 **RN 版 MMKV 全部键**,
  键名与 RN 版一一对齐(表在该文件头部的 KDoc 里)。
- `account/AccountStore.kt` + `account/KeystoreCrypto.kt`:`accounts.v1` 加密落盘。
- `core/net/CredentialSource.kt`:core 层唯一认识的凭证接口(零 Android 依赖),
  `AccountStore` 在 `di/CredentialModule` 里 `@Binds` 上去。
- `data/session/SessionOverrides.kt`:两个会话级数据集的内存 holder,不给落盘口子。
- `data/StorageBootstrap.kt` + `Ng2nApplication` 的 StrictMode。
- `native/README.md` 加「存储(票 14)」小节:三份落点、换 key/表名策略、P2-04、P1-04。

**关键决定(票里留白的「实现时定」项)**

1. **凭证用自写 Keystore 封装,不用 `EncryptedFile`** —— `androidx.security:security-crypto`
   已弃用,按铁律不引入。实现:`AndroidKeyStore` 里一把 AES-256 密钥,别名
   **`ng2n.accounts.v1`**;`AES/GCM/NoPadding`,IV 由 Cipher 随机生成不复用,
   密文 = `IV(12B) || ciphertext+tag` → Base64 → DataStore 的一个 String 键。
   不设 `setUserAuthenticationRequired`(凭证是每请求现读的,没法交互)。
   密钥被系统清掉(改锁屏/恢复出厂/备份还原)时 decrypt 返回 null → 退回游客态,不抛。
2. **`exportSchema = true`,schema JSON 进版本库**(`native/app/schemas/…/1.json`,5.8KB)。
   二选一里选这一档:没有迁移测试时,那份 JSON 是「表结构曾经长什么样」的唯一书面记录。
3. **无迁移机制**:`fallbackToDestructiveMigration(dropAllTables = true)`,
   策略照抄「换结构就换表名」。审计 P2-05 提过这条,但 spec §一.5 的必修清单里没有它 ——
   记为**有意选择**,理由写进 README 与 `Ng2nDatabase` 的 KDoc。
4. **两个库合成一个**:RN 版是 `ng2.db` + `notifications.db`,分库的唯一理由是
   expo-sqlite 每库一个连接;Room 没这个约束,而三张表本来就要在同一事务里被清。
5. **诊断日志脱敏走中央白名单(默认拒绝)**,而不是审计建议的「按请求声明
   `safeDiagnosticParams`」—— 一处漏声明就重新泄露一次,不能靠调用方自觉。
   白名单只有结构性参数:`tid/fid/stid/pid/page/lite/v2/opt/order_by/recommend/authorid`。
6. **每次尝试那一行的 `uid` 保留**(RN 版原行为):反封锁链「换账号重试」排障必须知道
   用的哪个号;`cid` 从来没有进日志的地方,单测把这条钉住。
7. 收藏反向索引按 uid 分键(`topic-favor-index/v1/<uid>`)—— **P1-02 的存储侧**。
   RN 版这一层本来就分了,漏的是 TanStack Query 的 key,那半归票 16/17 的仓库层。
8. `NGA_HOSTS` 暂居 `data/settings/Settings.kt`(设置只用到「host 必须是表里的一个」)。
   **票 03/06/07 把 `core/net` 常量表落地后,这里改成引用,别留两份。**
9. StrictMode 的开关判据用 `ApplicationInfo.FLAG_DEBUGGABLE` 而不是 `BuildConfig.DEBUG` ——
   本工程 release 也用 debug keystore 签名,但 release 变体不是 debuggable,判据仍然精确,
   而且不必为它打开 `buildConfig` 特性。

**对 RN 版的有意偏离(修缺陷,不是随迁)**

- **P2-04**:RN 版在 zustand store 的**模块初始化**里同步开库并全表扫描
  (`store/topic-cache.ts:33`、`store/history.ts:30`、`store/notifications.ts:43`),
  外加 `SecureStore.getItem` 同步读账号。这一版全部 suspend/Flow,
  预热在 `StorageBootstrap` 里往 IO scope 扔协程就返回,首屏不等。
- **P1-04**:RN 版把所有非 `__` 开头的 query 参数原样写进未加密 MMKV 并可一键分享;
  这一版默认拒绝(见上)。
- **P1-02(存储侧)**:见上。
- `utf8ByteLength` 手写而不是 `toByteArray(UTF_8).size` —— JVM 编码器把落单代理项写成
  `?`(1 字节),RN 版(与 `TextEncoder`)按 U+FFFD 算 3 字节。口径照抄 RN 版,单测钉住。

**验收证据**

① **上限/驱逐/节流语义单测**(`cd native && ./gradlew :app:testDebugUnitTest`):
`total tests=104 skipped=0 failures=0 errors=0`。其中手工移植自 RN 的四份 +
节流状态机:`TopicCachePolicyTest`(15)、`HistoryPolicyTest`(18)、
`ReadFloorThrottleTest`(9)、`NotificationPolicyTest`(9)、`TopicFavorIndexTest`(20)、
`SettingsTest`(23)、`DiagnosticLogTest`(8)。
DAO 薄层用 instrumentation 在 emulator-5554 跑了一次:
`./gradlew :app:connectedDebugAndroidTest` → `Finished 12 tests on Pixel_8(AVD) - 17`,BUILD SUCCESSFUL。

② **StrictMode**:
- 装没装 —— `StrictModeTest`(instrumentation)在**主线程**上把 `ThreadPolicy` 读回来,
  断言掩码非零(policy 忘了装的话 logcat 当然也是干净的,那种「无违规」不算证据);
  VmPolicy 同样断言。两条都过。
- 有没有违规 —— `adb install -r app-debug.apk`;
  `adb shell am force-stop com.chasel.ng2.n && adb logcat -c && adb shell am start -W -n com.chasel.ng2.n/com.chasel.ng2n.MainActivity`,
  静置 5s 后 `adb logcat -d | grep -i strictmode` → **零行**。
  Activity 起来了(`Displayed … for user 0`),说明不是没跑起来导致的空。
- 模拟器上其它 app 未卸载(RN 版 `com.chasel.ng2` / `.dev` 原样保留)。

③ **诊断日志导出样本**(单测断言 + 人工看一眼实际输出):

```
2026-08-07T10:53:20Z read.php?tid=44191387&page=3&fav=<redacted> 失败:全链失败
  1. [direct] json @ https://bbs.nga.cn ua=windowsPhone uid=42 → http 403: Forbidden
```

fav 码、搜索词 `key`、整张屏蔽词表 `data` 三条泄露源逐条断言不出现;
反面也钉住(`tid`/`page`/`fid` 必须还在,否则脱敏就把日志本身废掉了)。

**未完成 / 待所有者**

- 无。凭证加密的**真机**验证(小米 17 上 Keystore 行为、`install -r` 覆盖安装后能否解开)
  没做 —— 需要真机与真登录,归票 15/18/19 的验收场次。模拟器上加解密路径由
  `AccountStore` 的代码路径覆盖,但没有专门的 instrumentation 用例(见下)。

**做得不够的地方(如实记)**

- `AccountStore` / `SettingsStore` / `DiagnosticLogStore` **没有 instrumentation 测试**:
  它们要真 Context + 真 Keystore,而票面只要求 DAO 层跑一次设备测试。
  三个 store 的纯逻辑部分(`sanitizeAccounts`、`parseSettings`、`redactDiagnosticParams` …)
  都有 JVM 单测,接线部分靠票 15/16/17 用起来时暴露。

**发现的票外问题**

1. **KDoc 里出现 `*/` 会静默截断注释**,把整个文件变成 KSP 的 error type,
   而 KSP2 只报一句 `[MissingType]: Element 'X' references a type that is not present`,
   不报语法错误 —— 本票在 `Daos.kt` 的一句中文注释里写了 `data/*/…Policy.kt`,
   排查花了六轮构建。**后续票的中文注释里别写带 `*/` 的路径通配**(写成 `data/…/xxx.kt`)。
   建议主控在简报的「工程约定」里加一句。
2. `app/build.gradle.kts` 只做了**追加**:`androidTestImplementation(libs.kotlinx.coroutines.test)`
   (DAO 的设备端测试要 `runTest` 驱动 suspend DAO)。`libs.versions.toml` **没动**。
3. 票 12 的图片尺寸缓存与本票的 DataStore 没有交集(它自己落 JSON 文件),
   `SettingsStore` 的键表里注明了归属,不会冲突。

**主控验收(2026-08-22)**:合并后主干 `testDebugUnitTest` 135 例全绿。接受自写 AndroidKeyStore+AES-GCM、schema 进库、单库 `ng2n.db`。跟进项:`NGA_HOSTS` 常量待票 06/07 落 `core/net` 后改引用(记入票 07 提示)。
