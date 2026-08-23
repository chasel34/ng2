# 34 — P1:跳楼永远只落到该页顶部,`scrollTarget` 从不真的滚

**Status:** resolved

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

## Comments

**2026-08-23 / 修复完成**

断在三处,一处是票里定位的,两处是顺着查出来的:

1. **`LaunchedEffect(vm.scrollTarget, page)` 自己杀自己**(票里已定位)。
   `consumeScrollTarget()` 改的就是这个 key,下一帧重组旧协程被取消、新协程以
   `null` 立刻返回,滚动挂起函数活不过一帧。改成
   `LaunchedEffect(listState, page) { snapshotFlow { vm.scrollTarget }.collect { … } }` ——
   消费与滚动都不再动这条协程的生死。

2. **header 那一格恒在,offset 却按热门回复现算**(票里没提,同一段代码)。
   `FloorList` 给主动页固定挂一个 `ListKeys.HEADER` item(「只看此人」条与热门回复区
   塞在**同一个** item 里),所以楼层永远从列表第 1 格起。老代码
   `val offset = if (model.hotReplies.isNotEmpty()) 1 else 0` 只有第 1 页(唯一有热门回复的页)
   算得对,**其余每一页都少一格**。就算修好第 1 条,B/C 两个现场也还是会差一楼。

3. **顺带一处(票里已记)**:`isScrollInProgress` 无条件置 `userScrolled = true`,
   程序化跳楼也会点亮它,跳到页尾那一下会顺手自动翻页,把刚定位好的楼翻走。
   改成接 `listState.interactionSource` 的 `DragInteraction.Start` ——
   RN 侧接的就是 `onScrollBeginDrag`,语义逐字对上。

**换算抽成纯函数**:`Paging.kt` 的 `floorScrollIndex(floorLous, targetFloor, page, rowsPerPage, headerRows)`
→ 直接给出**能喂给 `scrollToItem` 的 item index**。页码核对、被删楼的空洞、header 那一格
全在里面,ViewModel 只管「什么时候算」,屏幕一次算术都不做。`ScrollTarget.index`
随之改名 `listIndex` —— 两个 index 空间同名叫 `index`、各自补一次 offset,正是第 2 条的成因。

**关键决定**

- 用瞬时 `scrollToItem` 而不是 `animateScrollToItem`:动画型滚动要占着
  `MutatePriority.Default` 几百毫秒,被下拉刷新 / pager 收尾 / 手指抢走就停在半路,
  而本票的验收判据是「首屏第一楼 == 目标楼」。**对 RN 的有意偏离**:RN 侧是
  `scrollToIndex({animated:true})` + 700ms 补一脚 `animated:false` —— 那一脚是给
  LegendList 按估高短滚擦屁股的,Compose 的 snap 本身精确,不需要。
- 滚之前等 `listState.layoutInfo.totalItemsCount > listIndex`:「该页数据到位」只是一半,
  列表这一帧还没量出来时滚了是空转。
- 目标楼不在本页时 `floorScrollIndex` 给 null,`redeemPendingFloor` **不清 `pendingFloor`**,
  等真正那一页回来再兑现(与老代码的 `model.page` 守卫同语义)。

**单测**(`./gradlew :app:assembleDebug :app:testDebugUnitTest -q` 全绿,937 tests / 0 failed / 4 skipped,
4 skipped 是既有的):

- `PagingTest` +9 条:目标楼在页中部(74/26/7 三个现场楼号,都离页顶 ≥ 5 楼)/
  目标楼不在本页给 null / 有热门回复区(过一遍真 `TopicPageBuilder`,断言它不额外占一行)/
  页顶那一楼落在第 1 格不是第 0 格 / 被删楼的空洞 / 尾巴删光落末楼 / 空页与负楼号 /
  每页楼数不是 20 / 预览页 `headerRows = 0`。
- `TopicViewModelTest` +2 条:74 楼跨页进场端到端(`listIndex == 15`)、
  第 1 页有热门回复区时 7 楼仍是 `listIndex == 8`;「回到那里」跨页 —— 目标页没回来时
  不给滚动目标,回来了才兑现。既有两条断言按新口径改了预期(+1 格 header)。

**未完成 / 待所有者**

- **真机/模拟器复验没做**(本票明确要求不用模拟器)。票里 A/B/C 三个现场的
  uiautomator 判据仍需所有者或后续验收票跑一遍:目标楼选 7 / 26 / 74,
  断言**首屏第一楼 == 目标楼**,不能只断言「在屏上」。

**发现的票外问题**

- `TopicViewModelTest` 的 `viewModel(key, fakes)` 按 `key.toString()` 走 `ViewModelStore`,
  同一个 key 建第二次拿到的是**同一个实例**(踩了一次:第二个 VM 的 `resumeFloor`
  还是第一个进场时算的)。本票里靠给 key 显式加 `page = 1` 绕开,没动这个 helper。

