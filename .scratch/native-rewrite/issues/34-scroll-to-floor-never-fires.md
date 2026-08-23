# 34 — P1:跳楼永远只落到该页顶部,`scrollTarget` 从不真的滚

**Status:** open

**Severity:** P1(带楼号的四条入口——回复链「在原帖中查看」/ 浏览历史点条目 /「上次读到第 N 楼 · 回到那里」/ 通知与「我的回复」带 pid ——**页码都对了,楼层一个都没到**)

## 现象

票 20 修好的是**页码**那一半:带 `page`/`floor` 的 `TopicKey` 现在确实开在正确的页。
但**楼层锚点这一半仍然是废的**——落地永远是那一页的第一楼,目标楼常常连屏都不在。

## 复现(模拟器 emulator-5554,`com.chasel.ng2.n` debug = HEAD a40e24a,游客态,2026-08-23)

三条入口全中,每条都独立复现:

**A. 回复链「在原帖中查看」**
1. `am start -a android.intent.action.VIEW -d "'ng2n://bbs.nga.cn/read.php?tid=47406116&page=4'"`
2. 62 楼的引用块 →「查看对话链(2 层)」→ 链上是 7 楼(上游)与 62 楼(当前楼)
3. 点 7 楼那张卡的「在原帖中查看」

- 实测:开在**第 1 页**(页码对),首屏 `[1 楼] [2 楼] [3 楼]`,7 楼在屏外;等 2s / 5s / 10s 三次 dump 位置都没动
- 期望:第 1 页且滚到 7 楼

**B. 浏览历史点条目**(票 20 的 `historyTopicKey` 带 `floor = lastFloor`)
1. 版块页「更多」→「浏览历史」
2. 点「[新闻]《守望先锋》精彩聚焦：黑爪之治开始 … 读到 26 楼」

- 实测:开在**第 2 页**(26/20+1,页码对),首屏 `[20 楼] [21 楼] [22 楼] [23 楼]`
- 期望:第 2 页且滚到 26 楼

**C.「上次读到第 N 楼 · 回到那里」**(这条 A 段标了 `[x]`,**其实也是坏的**)
1. `am force-stop` 后 `am start … read.php?tid=47406116`(该帖本机进度 = 74 楼)
2. 浮条在 **t=3–4s** 出现(`AUTO_DISMISS` 之前),固定坐标点 `(833, 467)` 的「回到那里」
3. 6s 后 dump

- 实测:开在**第 4 页**(74/20+1,页码对),首屏 `[60 楼] [61 楼] [62 楼]`,**74 楼不在屏上**
- 期望:第 4 页且滚到 74 楼

> A 段把这条记成「跳到第 4 页并停在 62 楼」——当时的进度楼层正好是 62,而 62 楼是
> 第 4 页的第三楼、本来就在首屏里,所以「落到页顶」被误读成「停在 62 楼」。
> 票 20 的主控验收「冷启深链 page=3 落 40–42 楼」是同一个误读:40 楼就是第 3 页的页顶。
> **判据要选一个离页顶远的楼**(如本票的 74 楼 / 26 楼 / 7 楼),否则两种行为长得一样。

## 定位(未改代码)

`ui/topic/TopicScreen.kt:464-473`:

```kotlin
LaunchedEffect(vm.scrollTarget, page) {
  val target = vm.scrollTarget ?: return@LaunchedEffect
  if (target.page != page) return@LaunchedEffect
  vm.consumeScrollTarget()                       // ← 把自己的 key 写成 null
  val offset = if (model.hotReplies.isNotEmpty()) 1 else 0
  listState.animateScrollToItem((target.index + offset).coerceAtLeast(0))
}
```

`consumeScrollTarget()` 改的就是这个 `LaunchedEffect` 的 **key**(`vm.scrollTarget`)。
下一帧重组时 key 变了 → 旧协程被**取消**、新协程以 `scrollTarget == null` 立刻 return。
`animateScrollToItem` 是个跑几百毫秒的动画型挂起函数,~16ms 后就被砍断,
所以看起来「一动没动」;只有目标楼本来就在首屏里时才像是成功了。

上游没问题,已逐段排除:
- `ChainViewModel.openInTopicKey`(`ChainViewModel.kt:137-149`)两个分支都会带上 `floor`
  (链上两张卡都渲染出来了,说明 `entries[node.pid]` 命中,走的是带 `floor` 的那支);
- `TopicViewModel.initialPage()`(`:247`)与 `redeemPendingFloor()`(`:607`)算出的页码
  **实测都对**(三条入口落的页 1 / 2 / 4 与 `pageOfFloor(7|26|74, 20)` 逐一吻合),
  说明 `pendingFloor` 被兑现成 `scrollTarget` 了,断在最后那一跳。

## 顺带一处(同一段代码,单独记)

紧邻的第二个 `LaunchedEffect`(`TopicScreen.kt:475-482`)拿
`listState.isScrollInProgress` 无条件置 `vm.userScrolled = true`。
程序化跳楼也会触发它,而 `onReachedEnd()`(`TopicViewModel.kt:359-360`)的 KDoc 写的是
「只认用户亲手滚出来的到底,**程序化滚动(跳楼落到页尾)不算**」。
跳楼修好之后,跳到页尾那一下会顺手触发一次自动翻页——建议一并处理。

## 期望

滚动真的发生:目标楼落到视口顶部。
最小改法是别让消费动作改自己的 key(例如 key 换成 `target.page to target.index`、
或先 `animateScrollToItem` 再 `consumeScrollTarget()`、或把消费收进
`snapshotFlow { vm.scrollTarget }.collect {}`)。

**验收判据**:目标楼必须选**离该页页顶 ≥ 5 楼**的楼(本票用 7 / 26 / 74),
且断言「首屏第一楼 == 目标楼」,不能只断言「目标楼在屏上」。
