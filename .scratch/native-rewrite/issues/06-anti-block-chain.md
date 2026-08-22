# 06 — 反封锁链(M1,核心;照抄 + 修 P1)

**What to build:** fetcher 策略链五段照抄(装配序:web-fallback(primary 档)→ format-rotation → switch-account → web-fallback(secondary 默认档)→ topic-cache 槽位(实现挂票 14 之后);链外 /web 兜底归票 17):
- 引擎:按序试、首成功返回、`retryable=false` 立刻抛、全程 attempts 诊断挂载。
- format-rotation:格式 `__output=8`/`__output=11`/`lite=js`;顺序=缓存好组合→调用方指定→域名外层×格式内层笛卡尔积;**上限 8**;组合缓存 key 接口粒度(`path` 或 `path?__lib&__act`)、TTL 10min、**不持久化**、自愈;游客短路(未登录且服务端报未登录→停换域名)。
- switch-account:≥2 账号才触发、循环到当前 uid 下一槽、只试一次。
- **`renewTransport` 真实现**(原生的白捡增益,ADR-0002 第 3 条):`OkHttpClient.newBuilder()` + 独立 ConnectionPool/Dispatcher,换域名真换 TCP 连接。
- 凭证双通道照抄(Cookie 头 + `access_uid`/`access_token` 表单;OkHttp BridgeInterceptor 覆盖 Cookie 头的坑与 RN 相同)+ **自管 CookieJar**,WebView CookieManager 只在登录收割点交互(修 P1-03)。
- **修 P1-01/P1-02**(spec §一.5):`NgaRequest` 加 `operation(read/write)` 与 accountPolicy 元数据——写操作固定 uid、**禁入 format-rotation 与 switch-account**、失败不自动重放;读侧缓存按 uid 隔离的原则从第一天生效。
- 无退避/无超时照抄?**不照抄**:加统一超时预算(修审计 P2-06,connect/read/整体 deadline 各一档),背靠背轮换行为保持。

**Blocked by:** 01, 04

**Status:** open

- [ ] 手工移植回归全绿:combo 投毒(`rejectNonTopicList` 防假成功)、「未登录=可重试」、游客短路、`__output=11` 真数组
- [ ] 新增回归:写操作不轮换/不换号/不重放;读写元数据缺省安全(未标注=按写处理或编译期强制标注,二选一并记录)
- [ ] renewTransport 实测换连接(日志验证新 socket)
- [ ] 组合缓存 TTL/自愈/接口粒度语义与 TS 版一致(单测)
