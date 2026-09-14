# 本地存储

实现位于 [data](../app/src/main/kotlin/com/chasel/ng2n/data/)，以下按当前 Kotlin 实现整理。

Room、三份 DataStore 和图片尺寸 JSON 负责持久化；子版块覆盖与楼层点赞标记仅存于会话内。

| 引擎 | 落点 | 内容 |
|---|---|---|
| Room | `ng2n.db` | `browse_history`(tid 主键,历史与阅读进度同一条、200 条上限但有书签的主题不计入也不淘汰、1s 节流批刷)、`topic_cache`((tid,page) 主键、payload 是序列化信封文本、100 帖且 32MB、LRU 按 `used_at` **整帖驱逐**)、`notification_read`((uid,id) 主键、id 客户端合成 `${ts}-${type}-${tid}-${pid}`)、`bookmark`((tid,pid) 主键,主楼 pid 为 0;楼号、作者、摘要快照、可空备注、主题标题/版块/fav 码、创建与更新时间,全账号共享) |
| Preferences DataStore | `ng2n-settings` | 设置表、夜间模式、网络设置、本地屏蔽规则、搜索历史(三 tab 各 20)、版块树缓存(24h SWR)、已读公告、签到日期、诊断日志 50 条(**已脱敏**)、按 uid 分键的收藏反向索引 |
| Preferences DataStore + Android Keystore | `ng2n-ai-keys` | `ai.deepseek.apiKey.v1`：独立于论坛账号的 DeepSeek API Key，AES-256-GCM 加密，密钥别名 `ng2n.ai.keys.v1`；不记录加解密日志 |
| Preferences DataStore + Android Keystore | `ng2n-accounts` | `accounts.v1`:多账号 + currentUid,整表 JSON 走 AES-256-GCM(密钥别名 `ng2n.accounts.v1`)后再落盘 |

图片尺寸记忆由 `data/FileImageSizeStore.kt` 写入 `filesDir/image-sizes.v1.json`，使用临时文件加 rename 替换。
两个会话级数据集(子版块本地覆盖、楼层点赞标记)只有内存 holder
(`data/session/SessionOverrides.kt`),不给落盘的口子,原因写在那个文件里。

## 结构变更与迁移

Room 整库维护正式迁移，决策见 [ADR-0005](adr/0005-room-migrations-keep-user-data.md)：

- 改 schema 必须提高 `Ng2nDatabase` 版本并补迁移（纯加表/加列用 `autoMigrations`，其余写 `Migration`）。版本 1→2 用自动迁移新增 `bookmark` 表，2→3 自动迁移新增 AI 对话与运行表。
- 不再有破坏性回退：缺迁移或版本倒退时，`StorageBootstrap.open` 在启动预热里主动打开数据库，Room 的异常直接抛出，应用启动即崩溃，而不是静默清库或等首次写入才崩。
- `bookmark` 和 AI 对话是用户创建、无法重建的数据，它的迁移必须保留数据；`browse_history`、`topic_cache`、`notification_read` 可重建，其迁移允许删表重建。
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

## AI 助手设置与凭证

`data/ai/settings/AiSettingsStore.kt` 使用 `ng2n-settings` 中的独立版本键：

| 键 | 类型 | 含义与缺省值 |
| --- | --- | --- |
| `ai.analysisAllowance.v1` | String | `short` / `default` / `long` / `higher`，分别为短问答、默认、长楼与个人分析、更高；缺省 `default`，未知值回退默认档 |
| `ai.dailyEnabled.v1` | Boolean | 每日额度开关，缺省关闭；没有有效上限时不能启用 |
| `ai.dailyLimitUsdCents.v1` | Long | 用户填写的美元分整数，必须大于 0，缺省未配置；关闭每日额度保留上限，首次设置上限与启用开关原子写入 |

四档只保存用户选择，具体金额待代表性样本验证后确定，不使用设计稿示例金额作为正式默认值。今日用量当前显示零态，记账和额度执行由运行层接入。通用「恢复默认设置」不清除 AI 独立配置或模型 Key。

`AiKeyStore` 使用单独的 Preferences DataStore `ng2n-ai-keys`。每次加密产生新 IV，落盘内容为 Base64 编码的 IV 与 GCM 密文及认证标签，Keystore 密钥不导出。加解密在 IO dispatcher 运行；UI 状态只包含未配置、已保存或无法读取，保存后以固定遮罩显示。输入仅保留在当前对话框内存，不使用 SavedState 或 rememberSaveable；取消丢弃输入，成功落盘后才关闭并清空输入。编辑已有 Key 时输入替换值，留空不能覆盖旧值。

读取发生 IO 异常时显示无法读取，并等待用户保存后重新订阅存储；成功保存可恢复当前页面，无需退出重进，不自动循环重试。取消继续向上传播。解密失败保留密文并提示重新输入，不返回可用 Key；加密或写入失败保留原值并显示保存失败。DataStore 文件结构损坏时写入不可读标记，页面仍要求重新输入，后续可保存新 Key。凭证与设置都不写诊断事件，现有默认拒绝参数白名单也会脱敏 Key、Authorization 和所有 AI 设置键。应用 `allowBackup=false`，凭证不进入系统备份。

## AI 对话与运行恢复

Room 版本 2→3 自动迁移新增以下表，原书签和浏览数据保留，schema 见 `app/schemas/com.chasel.ng2n.data.db.Ng2nDatabase/3.json`。

| 表 | 内容与生命周期 |
| --- | --- |
| `ai_conversation` | 独立 UUID、入口类型、标题、来源、时间、草稿、实际页码与屏蔽数量、状态和删除标记；本机全账号共享，不按 tid 合并，不设自动淘汰 |
| `ai_message` | 按对话与顺序保存问题、回答、流式草稿、工具进度和未完成标记 |
| `ai_reading_range` | 原入口当时的阅读范围、图片数量及每项读取状态 |
| `ai_source` | sourceId、tid/pid、页码/楼层、作者、发言时间、读取时间与 SHA-256；没有正文或图片快照字段 |
| `ai_run` | 每次操作独立运行 ID、运行状态、停止位置与恢复来源；与会话 ID 分开 |
| `ai_working_state` | Koog Persistence 最新检查点（每个 run）、ChatMemory（每条对话）、必要模型资料、工具成功结果与回复链游标；只用于继续执行，不用作引用预览或论坛档案 |
| `ai_usage` | 每次模型请求发送前写入独立请求 ID、所属对话/运行、时间与待核实状态；没有对话外键，删除历史不删除用量，也不把未结请求计为零费用 |

会话与消息、范围、引用、运行、工作上下文使用外键级联删除。单条删除先持久化删除标记并隐藏；Snackbar 可撤销，关闭后物理清除；进程结束后启动清理未撤销的删除。清空全部需要确认，只删除上述对话及其子记录，用量记录保留。删除后的迟到保存不会重建已删除对话。保存与删除标记变更使用同一互斥锁；清空事务失败或取消时仅撤回本次新增的内存删除标记，保留此前的标记。故障解除后，同一存储实例可再次保存未被删除的对话。

`RoomAiConversationStore.initialize` 在首次历史访问或创建对话之前原子地把旧 `running` 运行标为 `interrupted`；同进程只执行一次，避免将仍在执行的任务误标为中断。打开历史只读取可见记录，用户点继续才创建新 run，通过绑定的 Room provider 读取上一次 Koog 检查点。ChatMemory 的框架 runId 映射到 App 会话 ID，不与其他对话混合。不引入前台服务。

每次继续都指向当前最近的未完成运行，连续中断时沿运行链查找最近的检查点和成功工具结果。恢复工具结果发送节点时，按检查点中的 `read_image` 调用参数与结果中的图片标识读取持久化图片工作资料，并依据恢复后的 prompt 中实际已有的图片消息去重，不依赖内存待发送队列或重新执行成功工具。图片工作资料缺失时停止，不发送只有读图成功状态而没有图片的请求。

模型请求、检查点、工具成功结果和流式草稿的存储异常向上抛出并关闭本次请求入口；界面保留内存草稿，显示「对话未能保存」。重试保存仅写本机，成功后由用户另行继续，不自动产生模型请求。论坛正文只存在必要的模型工作上下文中，引用预览仍必须用当前账号重新读取；系统规则要求续聊引用原句前重读，无法读取时说明来源已删除或不可访问。
