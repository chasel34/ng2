# 07 — 端点层(M1,25 个)

**What to build:** `docs/API文档.md` §12 + research/inventory.md §3.2 的 25 个端点全部落 Kotlin:全部默认 POST、业务参数进 URL query、表单只放表单字段;api 层不碰 method/format/host/auth/UA(100% 传输层决定,与 RN 版同构)。已知坑逐条带上:`removeTopicFavorite` 用 `tidarray`;`forum.php` `key` 与 block-word `data` 走 GBK;user-profile 带 referer;`setSubBoardOption` 参数名是动词;子版块 `attributes` 白名单**修掉魔法数误报**(spec §一.5);负数 fid 按有符号处理;热帖是客户端聚合(并发拉前 5 页、24h 窗口过滤重排)不是端点。各端点解析器配 `rejectNonTopicList` 式形状否决。

**Blocked by:** 04, 06

**Status:** in-review

- [x] 各端点解析器金样本对拍通过（`api/` 10 个 domain 113 条全绿）
- [x] 联网冒烟套件(默认跳过,`NGA_INTEGRATION=1` 开;沿用 app-v1 票 02 的模式与脱敏纪律)——游客态那条已实跑通过,登录态与写端点只写不跑(待所有者)
- [x] 写端点(8 个)全部带 operation=write 元数据(接票 06 的闸)——`WriteEndpointsTest` 逐端点回归「只发一次、不换账号」

## Comments

### 完成摘要(2026-08-22)

29 个请求函数(= API 文档 §12 的 25 个端点粒度;`thread.php` 一个端点覆盖 6 个业务场景)
全部落在 `native/app/src/main/kotlin/com/chasel/ng2n/core/api/`,零 Android 依赖:

| 文件 | 内容 |
|---|---|
| `Types.kt` | 全部 `@Serializable` 领域模型(Board / SubBoard / Topic / Floor / FloorUser / TopicDetail / TopicList / UserProfile / 通知 …),字段名与金样本 `expected` 一一对应 |
| `TopicList.kt` | `thread.php` 解析 + `rejectNonTopicList` + `mergeTopicPages` + `fetchTopicList` |
| `TopicDetail.kt` | `read.php` 解析、匿名 context 前缀、缓存快照入口 `TopicPageSnapshot` |
| `BoardTree.kt` / `BoardFavor.kt` / `TopicFavor.kt` / `Notifications.kt` / `SubBoard.kt` / `BlockWord.kt` / `Search.kt` / `UserProfile.kt` / `UserTopics.kt` / `TopicRecommend.kt` / `CheckIn.kt` / `SetSign.kt` / `HotTopics.kt` | 其余端点 |
| `Validate.kt` | 形状否决器一族(见下) |

- **金样本**:`api/` 下 10 个 domain **113 条全绿**(topic-list 22 / topic-detail 11 /
  notifications 18 / sub-board 16 / block-word 14 / board-favor 10 / search 8 /
  board-tree 6 / user-profile 5 / topic-favor 3)。对拍口径是「Kotlin 结果按
  `explicitNulls=false, encodeDefaults=true` 序列化后与 `expected` 深比较」。
- **手工移植**:`ReadEndpointsTest`(28 项)/ `WriteEndpointsTest`(22 项)/
  `ValidateTest`(6 项)/ `TopicCacheRoundTripTest`(7 项)——覆盖金样本装不下的
  出站 URL 装配、Referer、GBK 参数、错误分支与控制流。
- 全量单测 **516 项通过 / 4 项 Assume 跳过(全是联网冒烟)/ 0 失败**;`assembleDebug` 通过。

### 关键决定

1. **端点函数收 `NgaClient`**(TS 那边收 `NgaFetcher` 闭包)。`NgaClient` 本来就是
   「全 app 共用的请求器」,单测有现成的 `testClient(...)`,不再多一层接口。
2. **`Floor.content` 存 BBCode 原文,端点层不解析成 AST** —— 照抄 RN 版
   `topic-detail.ts` 的边界:解析是票 13 在后台一次性预转换的事(anzong 四原则第一条),
   而且端点层的产物要能原样进帖子缓存。
3. **形状否决器推广到其余读端点**(`Validate.kt`,票面要求)。RN 版只有 `thread.php`
   挂了这道闸。三条纪律写进了 KDoc:假错误一律放行;ucp 的**空 data 放行**
   (RN 把「data 在 user 不在」单独报成「资料响应里没有用户」,判成坏组合会吞掉那句话);
   版块收藏 / 收藏夹**故意不给**否决器 —— 它们的空态响应就是 `{}`,没有键可断言,
   硬编一条会在「一个都没收藏」时把好响应判成坏组合。
4. **热帖聚合**给了两个口子:`fetchHotTopicPages`(拉页,失败页容错)与
   `fetchHotTopics(client, boardId, kind, nowSeconds)`(拉页 + 票 10 的 24h 窗口聚合)。
   并发用 `supervisorScope`,`CancellationException` 原样抛回去(不当成「这一页失败」)。
5. **`serializeEnvelope` 补在 `core/net/strategies/TopicCache.kt`**(与 TS 同位置),
   `TopicCachePayloadReader` 从只读接缝变成两向接缝(`save(snapshot)` → 票 14 的 Room)。
   `TopicCacheRoundTripTest` 证明「在线那一页」与「缓存还回来的一页」除 `source` 外逐字节相同。

### 修掉的已知缺陷

- **子版块 `attributes` 白名单误报**(spec §一.5):新增 `SubBoardSubscription`
  三态(SUBSCRIBED / BLOCKED / UNKNOWN)。**RN 原行为**:白名单外一律 `subscribed:false`,
  UI 直接画成「已屏蔽」——实测「网络游戏综合」从没被屏蔽却显示已屏蔽。
  **修法**:白名单是「已订阅」的唯一证据,没命中只报 UNKNOWN;只有本地刚做完操作
  (`nextSubBoardState`)才敢说 BLOCKED。**魔法数判据一个字没改**(改它需要带登录态
  重新抓包,见下「待所有者」),`subscribed`/`filterable` 两个字段的取值也没变,
  所以 `api/sub-board` 16 条金样本照旧全绿(新那一位是 `@Transient`)。
- **P1-01 写操作重放**:8 个写端点全部 `operation = Operation.WRITE`,
  `WriteEndpointsTest` 里有一条逐端点的回归:同样被封的响应下,写请求只发一次、
  不换账号;对照组的读请求会轮换。

### 对 RN 版的有意偏离

- `parseBoardIdInput` / `parseUserSearchInput` / `blockWordError` 用 `jsTrim()` 而不是
  Kotlin 的 `trim()`(JS 的空白集合多一个 BOM,票 04 已把这个差异抠成显式函数)。
- `subBoardOptionParam` 的 `filterType` 收 `Int`(TS 是 `0 | 1` 字面量类型)。
- 热帖聚合的 `now` 改成必填的 `nowSeconds`(TS 是 options 里的可选项),纯函数不看表。

### 未完成 / 待所有者

- **联网冒烟只跑了游客态那一条**(2026-08-22 实跑通过:thread.php topics=47 /
  read.php floors=20 / app_api categories=8 / thread.php?key topics=32)。
  需要登录的两条(`ucp get` + `topic_favor_v2 list_folder` + `forum.php` 版块搜索)
  与写端点那条**只写不跑**,门控 `NGA_UID` / `NGA_CID`(写端点还要 `NGA_WRITE_SMOKE=1`)。
- 子版块 `attributes` 魔法数表要**带登录态重新抓包**才能修准
  (`.scratch/ui-polish-2026-08-20/issues/02-...` 的下一步 1/2):抓几个「确实订阅了 /
  确实屏蔽了 / 没动过」的样本,看它是不是位标志。本票只做了「不再误报」。

### 发现的票外问题

1. **`forum.php`(版块搜索)游客不可用**:服务端回
   `{"error":{"0":"2048:必须登录才能使用此功能"}}`,2026-08-22 本机 curl 用同一串字节
   对拍确认是服务端规矩(不是 GBK 编码坏了)。**搜索页(票 17)要对游客挡住「版块」tab
   或提示登录**,RN 版没有这一档——它的冒烟一直带着凭证跑,所以没暴露。
2. **搜索接口比普通列表更容易撞限流**:与前三发只隔 20s 会回 `2048:service error`,
   静置一分钟后同一串字节照常有结果。冒烟里搜索前静置调到 70s;UI 侧的连续搜索
   (票 17)也该有节流。
3. **`notificationId` 有两处口径**:`core/api/Notifications.kt`(本票,解析时生成稳定 ID)
   与 `data/notifications/NotificationPolicy.kt`(票 14 的已读模型)。没能共用是因为
   分层——core 不能 import data,而那份已读模型住在 data 层。它的 KDoc 自述是
   `core/local/notifications.ts` 的直译,**本该在 `core/local`**;顺带 `NotificationLike`
   接口也在 data 层,导致 `NgaNotification` 实现不了它。建议票外把 NotificationPolicy
   迁进 `core/local`,两处口径合一。
4. **`__output=11` 下楼层附件可能静默丢失**:`parseFloor` 照抄 RN 的 `isRecord(attachs)`,
   **真数组不认**。`__output=11` 会把「字符串数字键当数组」的字段发成真 JSON 数组
   (ADR-0002 第 10 条、fid=414 那次),此时 `attachs` 若是数组就会被整条跳过。
   没有样本能确认 read.php 的 `attachs` 在 `__output=11` 下是什么形态,所以**照抄未改**;
   要修的话把 `as? JsonObject` 换成 `orderedValues` 直收即可(`orderedValues` 两种都吃)。
5. **RN 版 `topic-favor.test.ts` 的一句注释不准**:写着「opt=0 会被 buildQueryString 剔掉」,
   但规则是「数字 0 保留」,实际一直在发 `opt=0`。Kotlin 侧如实钉住了实际行为。

### 顺手改动(票面点名)

- `core/local/DeepLink.kt` 的 `NGA_HOSTNAMES` 副本改成从 `core/net/Constants.kt` 的
  `NGA_HOSTS` 派生(域名表只剩一处真相源)。
- `core/local/TitleStyle.kt` 只加了 `@Serializable` / `@SerialName("red")` 这类注解
  (主题行整份要进金样本对拍与本地缓存),**取值与判定逻辑一个字没动**。
