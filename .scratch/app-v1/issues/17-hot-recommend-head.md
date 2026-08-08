# 17 — 热帖 + 精华区 + 版头

**What to build:** 列表页菜单三个入口:「24 小时热帖」并发拉取版块前若干页、按 24h 窗口过滤、按回复数排序的本地聚合列表(带刷新);「精华区」recommend=1 列表;版块数据带 head 字段时展示版头置顶入口,用普通详情页打开。

**Blocked by:** 05

**Status:** resolved

- [x] 热帖聚合纯本地实现,页数可配,失败页容错(部分页失败不整体失败)
- [x] 热帖/精华区复用统一列表行,与设计稿 1:1
- [x] 聚合排序逻辑单测

## Comments

- **分层**:聚合(24h 窗口过滤 + 回复数排序,时间作参数传入的纯函数)在 `src/core/local/hot-topics.ts`;并发拉页 + 失败页容错在 `src/core/api/hot-topics.ts`(`Promise.allSettled`,全失败才抛,默认 5 页可配);两段在 `src/store/hot-topics.ts` 接起来(staleTime 5min,免得切页面就重打 5 个 thread.php)。
- **窗口过滤看发帖时间**(postedAt)而不是最后回复:按最后回复过滤的话老坟顶一下就进榜。合集/镜像行与外链活动主题不进榜。
- **精华区**走现有 `useTopicList` 加 `recommend` flag(进 queryKey),请求带 `recommend=1&order_by=postdatedesc&user=1`(Android 同款,API 文档 §2),sort 不生效。
- **版头**:`__F.topped_topic` 解进 `Board.head`(05 票遗留的那一行),列表页头部出一条置顶入口(公告条的设计语言延伸,设计稿没画这屏),`/topic/[tid]` 普通详情页打开。
- **列表行复用**:`TopicRow` 加了可选 `time` 属性切到设计稿 simple-list 档(标题 16、when 槽 meta 色);热帖显示相对时间(基准=榜单算出的时刻,刷新前不跳),精华区显示发帖日期。
- **路由**:`/board/hot`、`/board/recommend`(静态段优先于 `[id]`,不用改现有路由结构),参数与 `/board/[id]` 同一套 id/kind/name。
- 热帖副标题条如实写「近 24 小时 · 按回复数排序」,部分页失败时在这里提示「N 页里 M 页拉取失败,榜单不完整」。
