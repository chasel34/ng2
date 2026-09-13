# 本地存储

实现位于 [data](../app/src/main/kotlin/com/chasel/ng2n/data/)，以下按当前 Kotlin 实现整理。

Room、两份 DataStore 和图片尺寸 JSON 负责持久化；子版块覆盖与楼层点赞标记仅存于会话内。

| 引擎 | 落点 | 内容 |
|---|---|---|
| Room | `ng2n.db` | `browse_history`(tid 主键,历史与阅读进度同一条、200 条上限但有书签的主题不计入也不淘汰、1s 节流批刷)、`topic_cache`((tid,page) 主键、payload 是序列化信封文本、100 帖且 32MB、LRU 按 `used_at` **整帖驱逐**)、`notification_read`((uid,id) 主键、id 客户端合成 `${ts}-${type}-${tid}-${pid}`)、`bookmark`((tid,pid) 主键,主楼 pid 为 0;楼号、作者、摘要快照、可空备注、主题标题/版块/fav 码、创建与更新时间,全账号共享) |
| Preferences DataStore | `ng2n-settings` | 设置表、夜间模式、网络设置、本地屏蔽规则、搜索历史(三 tab 各 20)、版块树缓存(24h SWR)、已读公告、签到日期、诊断日志 50 条(**已脱敏**)、按 uid 分键的收藏反向索引 |
| Preferences DataStore + Android Keystore | `ng2n-accounts` | `accounts.v1`:多账号 + currentUid,整表 JSON 走 AES-256-GCM(密钥别名 `ng2n.accounts.v1`)后再落盘 |

图片尺寸记忆由 `data/FileImageSizeStore.kt` 写入 `filesDir/image-sizes.v1.json`，使用临时文件加 rename 替换。
两个会话级数据集(子版块本地覆盖、楼层点赞标记)只有内存 holder
(`data/session/SessionOverrides.kt`),不给落盘的口子,原因写在那个文件里。

## 结构变更与迁移

Room 整库维护正式迁移，决策见 [ADR-0005](adr/0005-room-migrations-keep-user-data.md)：

- 改 schema 必须提高 `Ng2nDatabase` 版本并补迁移（纯加表/加列用 `autoMigrations`，其余写 `Migration`）。版本 1→2 用自动迁移新增 `bookmark` 表。
- 不再有破坏性回退：缺迁移或版本倒退时，`StorageBootstrap.open` 在启动预热里主动打开数据库，Room 的异常直接抛出，应用启动即崩溃，而不是静默清库或等首次写入才崩。
- `bookmark` 是用户创建、无法重建的数据，它的迁移必须保留数据；`browse_history`、`topic_cache`、`notification_read` 可重建，其迁移允许删表重建。
- 不开「降级时清库」，装回旧版本需要手动清应用数据。
- `exportSchema = true`，schema JSON 落 `app/schemas/` 并进版本库；设备测试用 `MigrationTestHelper` 从旧版本 JSON 建库、跑迁移并校验，`androidTest` 的 assets 指向该目录。
- DataStore：键名自带版本尾巴（`settings.v1` / `topic-favor-index/v1/<uid>` …），改结构就换 `.v2`，老键留在文件里没人读。
- 凭证：结构和密钥别名有版本，变更会影响已有账号可读性，需同步检查 `AccountStore` 与 `KeystoreCrypto`。

账号密文解不开时以游客态启动，但保留原密文；后续写入前将其备份到 `accounts.v1.unreadable`。加密失败且目标仍有账号时保留旧存档，不删除凭证。

## 异步存储访问

DAO 全 `suspend`/`Flow`,DataStore 天生只有 `suspend`/`Flow` —— **没有同步读的口子**。
预热走 `data/StorageBootstrap.kt`(`Ng2nApplication#onCreate` 里往 IO scope 扔协程就返回),
不阻塞 `onCreate` 返回。debug 变体在 `Ng2nApplication` 里装 `StrictMode`(penaltyLog;
`PENALTY_DEATH` 常量翻开即 penaltyDeath),主线程一碰磁盘就往 logcat 报警。

## 诊断日志脱敏

`data/diagnostics/DiagnosticLog.kt` 的 `redactDiagnosticParams` 是**默认拒绝**的白名单:
只有结构性参数(tid/fid/stid/pid/page…)原样进日志,其余一律换成 `<redacted>`(键名保留)。
`fav` 码、搜索词 `key`、整张屏蔽词表 `data` 都在白名单之外,单测逐条钉住。
