# 14 — 存储层(M3)

**What to build:** 三库直译(research/inventory.md §7)+ 修 P2-04:
- **Room**:`browse_history`(tid 主键,历史与阅读进度同一条记录、200 条上限、1s 节流批刷、updated_at 索引);`topic_cache`((tid,page) 主键、payload=序列化信封(AST 可序列化直接存)、100 帖且 32MB、LRU 按 usedAt **整帖驱逐**、图片字节不计入预算);`notification_read`((uid,id) 主键、id=客户端合成 `${ts}-${type}-${tid}-${pid}`、通知条目本身不持久化)。
- **Preferences DataStore**:承接 MMKV 全部键(设置逐字段容错解析、主题模式、网络开关、本地屏蔽规则、搜索历史、版块树缓存 24h SWR、已读公告、签到日期、诊断日志 50 条、**按 uid 分键**的收藏反向索引、图片尺寸缓存)。
- **凭证**:多账号+currentUid(`accounts.v1` 语义:每请求现读、切号下个请求生效),存 Android Keystore 加密的 DataStore(或 EncryptedFile,实现时定,记录进 Comments)。
- **修 P2-04**:冷启动路径零同步磁盘 IO——所有 load 走后台协程,首屏不等待非必需数据。
- 「换结构就换 key/表名,老数据作废」策略照抄,不做迁移机制;两个会话级数据集(子版块本地覆盖、点赞标记)**保持不持久化**。**修 P1-04**:诊断日志脱敏 fav 码/搜索词/屏蔽词表。

**Blocked by:** 01

**Status:** open

- [ ] 缓存上限/驱逐/节流语义单测与 RN 版一致
- [ ] StrictMode 验证冷启无主线程磁盘 IO
- [ ] 诊断日志导出样本人工检查无敏感字段
