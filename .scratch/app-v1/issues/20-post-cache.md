# 20 — 帖子缓存 + 我的缓存页

**What to build:** 浏览过的主题页自动写入本地缓存(容量上限 LRU 淘汰);详情页菜单「缓存本页/整帖」手动缓存;「我的缓存」页展示已缓存主题(页范围/大小/时间)、可打开与清空;断网或反封锁链全败时自动从缓存渲染并标注数据来源;缓存作为反封锁链的倒数第二环接入。

**Blocked by:** 07, 18

**Status:** implemented

- [ ] 飞行模式下能打开已缓存主题并正常渲染
- [ ] LRU 上限生效,清理缓存后容量归零
- [x] 我的缓存页与设计稿 1:1(大小/页范围元信息)

## Comments

**落地方式(2026-08-09)**

- 缓存模型:SQLite `topic_cache` 一行一页(`PRIMARY KEY (tid, page)`),存序列化后的**信封**
  (`core/net/strategies/topic-cache` 的 `serializeEnvelope`,存顶层 root)。还原走 `parseNgaJson`,
  与在线那条路完全同一段代码,所以信封天然同构(单测 `core/api/topic-detail.cache.test.ts`:
  缓存还回来的一页与在线那一页除 `source` 外深比较相等)。
- LRU 口径:**按主题整体淘汰**(只淘汰某一页会让离线阅读中途断掉),`used_at` 在写入与
  读取时都推到现在。上限 100 个主题 / 32 MB,最近使用的那个主题永不淘汰。
  淘汰规划是纯函数(`core/local/topic-cache.ts` 的 `planCacheEviction`),有单测。
- 链上位置:`web-fallback(secondary)` 之后接 `createTopicCacheStrategy`,命中即返回并把
  `TopicDetail.source` 标成 `cache`,详情页出「在线拿不到这一页,当前是缓存数据」提示条
  (沿用 19 的 fallbackBar 样式,动作是「重新联网」)。没命中报 `unavailable`,
  链继续走到错误页/网页兜底,真正的失败原因不会被它盖掉。
- 过滤视图(`pid` 只看该楼、`authorid` 只看某人)既不写也不读缓存:服务端会重排楼层与页码。
- 手动缓存:⋮ 菜单「缓存本页」(已自动缓存过就只回一句,不再打 read.php)与「缓存整帖」
  (第 1 页顺序拉到尾页,**每页间隔 800ms**,页码条下有进度条与「停止」)。
- 顺带修了 19 票发现的 bug:`TopicDetailParams` 缺 `pid`,深链的「只看该楼」被静默丢弃;
  现在 `pid` 进 queryKey 并透传到 `fetchTopicDetail`。

**留待真机验收**

1. 飞行模式打开已缓存主题:应正常渲染并显示「缓存数据」提示条(链上前四档全失败后落到缓存档)。
2. LRU:验收时把上限调小更容易看到效果(`core/local/topic-cache.ts` 的两个常量);
   本地只验到淘汰口径的单测,SQLite 侧的删除与「已占用 X MB」归零要在机器上看。
3. 缓存整帖的进度/停止在长帖(几十页)上的手感,以及连续 read.php 会不会触发风控。
