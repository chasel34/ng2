# native/ —— NG2N 原生 Android 工程

Kotlin/Compose 全量重写(ADR-0003 / ADR-0004,spec 见 `.scratch/native-rewrite/spec.md`)。
RN 版留在仓库根冻结作移植参照,两者并行安装:

| | RN 版 | 原生版 |
|---|---|---|
| applicationId | `com.chasel.ng2`(dev:`.dev`) | `com.chasel.ng2.n` |
| 显示名 | NGA 阅读器 | **NG2N** |
| 工程目录 | `android/`(expo prebuild 生成,gitignored) | `native/`(手写,进版本库) |

## 环境

JDK 17、`ANDROID_HOME` 指向本机 SDK。Gradle wrapper 9.7.1(首次会自己下)。
**所有 gradle 命令在 `native/` 下跑,并且要把代理显式传给 JVM**——gradle 是 JVM 进程,
不认 shell 的 `http_proxy`(CLAUDE.md 有同样的坑):

```bash
cd native
export https_proxy=http://127.0.0.1:7897        # wrapper 自己下发行版时认这个
export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"

./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk(R8 + 资源收缩)
./gradlew :app:testDebugUnitTest        # JVM 单测
./gradlew :app:connectedDebugAndroidTest# 设备上的 instrumentation
./gradlew :benchmark:assemble           # macrobenchmark / baseline profile 模块
./gradlew --stop                        # 收工:本机 16GB,别把 daemon 留着
```

签名:debug 与 release **都用** `app/debug.keystore`(RN 模板自带的公开 debug keystore,
与 RN 版同一把)。目的不是保密,是让两个变体签名一致,小米真机上 `install -r` 覆盖安装
能保住登录态。

## 模块

- `app` —— 唯一的 application 模块(ADR-0004:单 app 模块 + benchmark 模块)。
- `benchmark` —— `com.android.test` + `androidx.baselineprofile`,装 Baseline Profile 采集与
  macrobenchmark。骨架期只要求能编译;真跑与判据在票 19,**必须真机**
  (模拟器与 debug 包的数字永不用于性能裁决,spec §五)。

## 分层(`app/src/main/kotlin/com/chasel/ng2n/`)

```
core/net      传输层:OkHttp client 工厂、自管 CookieJar、拦截器链、反封锁链、GB18030 逐参数编解码
core/api      端点层:25 个 NGA 端点、信封拆解、错误分类、orderedEntries 对象/数组双兼容
core/bbcode   BBCode → AST(29 节点)、清洗六步;零 WebView
core/local    逆向纯算法:骰子、匿名还原、彩色标题 TLV、附件目录(UTC+8)
data          仓库层:Room / DataStore / 内存组合缓存(10min TTL、不持久化)、多账号
ui            Compose 屏幕与组件(含 ui/theme)
di            Hilt 装配
```

**`core/**` 禁止 `import android.*`** —— 这四个包是纯 Kotlin,JVM 单测直接跑得起来,
金样本对拍(票 05)靠的就是这条。碰到需要 Android API 的,说明东西放错层了。

单测在 `app/src/test/kotlin`,金样本放 `app/src/test/resources/goldens/<domain>/`。

## 版本

技术栈版本的唯一真相源是 `gradle/libs.versions.toml`,来源 ADR-0004 /
`.scratch/native-rewrite/research/stack-2026-08.md`。只进 stable;加库要写进 toml 的
`[libraries]` 并在票的 Comments 里记理由。当前有**一处非 stable 例外**,原因写在 toml 里
(`baselineprofilePlugin`)。

## 存储(票 14)

三份持久化,外加两份**刻意不落盘**的会话态。

| 引擎 | 落点 | 内容 |
|---|---|---|
| Room | `ng2n.db` | `browse_history`(tid 主键,历史与阅读进度同一条、200 条、1s 节流批刷)、`topic_cache`((tid,page) 主键、payload 是序列化信封文本、100 帖且 32MB、LRU 按 `used_at` **整帖驱逐**)、`notification_read`((uid,id) 主键、id 客户端合成 `${ts}-${type}-${tid}-${pid}`) |
| Preferences DataStore | `ng2n-settings` | 设置表、夜间模式、网络两开关、本地屏蔽规则、搜索历史(三 tab 各 20)、版块树缓存(24h SWR)、已读公告、签到日期、诊断日志 50 条(**已脱敏**)、按 uid 分键的收藏反向索引 |
| Preferences DataStore + Android Keystore | `ng2n-accounts` | `accounts.v1`:多账号 + currentUid,整表 JSON 走 AES-256-GCM(密钥别名 `ng2n.accounts.v1`)后再落盘 |

图片尺寸记忆表**不在这里** —— 它归票 12,自己落 JSON 文件。
两个会话级数据集(子版块本地覆盖、楼层点赞标记)只有内存 holder
(`data/session/SessionOverrides.kt`),不给落盘的口子,原因写在那个文件里。

### 换结构就换 key/表名,不写迁移

RN 版没有迁移机制(无 `PRAGMA user_version`,只有 `CREATE TABLE IF NOT EXISTS`),
明文策略是「换结构就换 key/表名,老数据作废」(源码里 6 处注释重复)。原生版**照抄**:

- Room:`fallbackToDestructiveMigration(dropAllTables = true)`;改表结构就把表名换成 `_v2`,
  老表随下一次 destructive 重建一起消失;
- DataStore:键名自带版本尾巴(`settings.v1` / `topic-favor-index/v1/<uid>` …),
  改结构就换 `.v2`,老键留在文件里没人读;
- 凭证:换存储结构就换 Keystore 密钥别名 —— 老密钥解不动新数据,自然退回游客态。

三张表全是可重建的本机数据(历史/缓存/已读),重建代价远小于维护一条迁移链。
审计 P2-05 提过这一点,但 spec §一.5 的必修清单里没有它 —— 这是选择,不是疏漏。
`exportSchema = true`,schema JSON 落 `app/schemas/` **并进版本库**:没有迁移测试时,
那份 JSON 就是「表结构曾经长什么样」的唯一书面记录,改结构时 diff 一眼看得出动了哪列。

### 冷启动零同步磁盘 IO(修 P2-04)

DAO 全 `suspend`/`Flow`,DataStore 天生只有 `suspend`/`Flow` —— **没有同步读的口子**。
预热走 `data/StorageBootstrap.kt`(`Ng2nApplication#onCreate` 里往 IO scope 扔协程就返回),
首屏一秒都不等。debug 变体在 `Ng2nApplication` 里装 `StrictMode`(penaltyLog;
`PENALTY_DEATH` 常量翻开即 penaltyDeath),主线程一碰磁盘就往 logcat 报警。

### 诊断日志脱敏(修 P1-04)

`data/diagnostics/DiagnosticLog.kt` 的 `redactDiagnosticParams` 是**默认拒绝**的白名单:
只有结构性参数(tid/fid/stid/pid/page…)原样进日志,其余一律换成 `<redacted>`(键名保留)。
`fav` 码、搜索词 `key`、整张屏蔽词表 `data` 都在白名单之外,单测逐条钉住。
