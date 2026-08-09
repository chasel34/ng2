# 26 — 回复链视图

**What to build:** 楼层引用块出现「查看对话链(N 层)」入口:扫描本帖已加载楼层的 quote 关系建索引,未加载的引用楼按需懒加载;回复链页以缩进卡片呈现上下游,当前楼高亮徽标,每张卡可「在原帖中查看」跳回对应页楼层。

**Blocked by:** 07

**Status:** implemented

- [x] quote 关系索引单测(含跨页引用与引用缺失容错)
- [x] 懒加载引用楼失败时该节点降级显示不阻塞整链
- [x] 链页缩进/高亮/徽标与设计稿 1:1

## Comments

### 实现要点(2026-08-09)

索引是纯 TS(`core/local/reply-chain.ts`,16 例单测):认 `[quote][pid=…]` 与
`[b]Reply to [pid=…][/b]` 两种引用容器(正文里随手贴的 `[pid]` 链接不算),
双向索引 + `buildReplyChain` 上游沿主引用、下游沿最早回复展开成一条线,
visited 集合掐环;自引/跨帖引用不进索引。详情页扫 Query 缓存里本帖的**全部整页**
(`store/topic-detail.ts` 的 `loadedTopicPages`)建索引,N 即链长,引用块认得出
`[pid]` 且 N≥2 才画入口(手打 quote 无入口)。链页 `app/chain.tsx`:懒加载按引用
标记的页码走 `queryClient.fetchQuery`(staleTime Infinity,看过的页不再打 read.php),
失败/页里没有那楼/引用缺页码三种情况分别降级占位不阻塞;卡片正文
`stripQuoteMarkup` 剥掉引用容器(上一层就画在上面);「在原帖中查看」给详情页
新收的 `floor` 参数,复用 16 票 pendingFloor 兑现滚动。缩进每层 14、封顶 8 层。
