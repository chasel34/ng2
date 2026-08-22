# 20 — P1:带页码/楼层进主题详情被 Pager 打回第 1 页

**Status:** open

**Severity:** P1(「在原帖中查看」「上次读到第 N 楼」「通知点进来」「我的回复」四条入口全废)

## 现象

任何带 `page` / `floor` 的 `TopicKey` 进详情页,都落在**第 1 页顶部**,请求的页码与楼层被丢掉。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-22)

1. 抽屉 →「由 URL 读取」→ 填 `https://bbs.nga.cn/read.php?tid=47406116` → 打开
2. 滑到 40 楼一带,点某楼引用块的「查看对话链(4 层)」→ 进回复链屏(链上有 5 / 27 / 35 / 40 四楼)
3. 点其中任意一张卡的「在原帖中查看」

**实测**:新开的详情屏停在第 1 页,首屏是 `[1 楼] [2 楼] [3 楼]`;链上 27 楼(应到第 2 页)、40 楼(应到第 3 页)都一样。
**期望**:开在该楼所在页,并滚到该楼。

注:按返回能退回回复链,栈本身正常;栈里原来那张详情屏仍停在第 3 页,**说明两屏的 ViewModel 各自独立**,不是实例复用问题。

## 定位(供修的人参考,未改代码)

`ui/topic/TopicScreen.kt:331`

```kotlin
val pagerState = rememberPagerState(
  initialPage = (vm.page - 1).coerceAtLeast(0),
  pageCount = { vm.totalPages.coerceAtLeast(1) },
)
```

`TopicViewModel.totalPages` 初值是 `1`(`TopicViewModel.kt:97`),首帧数据还没回来。
`PagerState` 会把 `currentPage` 钳进 `[0, pageCount-1]`,于是 `initialPage = 2` 当场被钳成 `0`;
紧接着 `TopicScreen.kt:342` 的 `snapshotFlow { pagerState.settledPage }` 收到 `0`,回调
`vm.goToPage(1)` —— 把 `initialPage()`(`TopicViewModel.kt:236`,正确读到了 `key.page`)
算出来的页码**覆盖成 1**。

楼层锚点跟着废掉:`redeemPendingFloor()`(`TopicViewModel.kt:581`)有
`if (model.page != pageOfFloor(floor, model.rowsPerPage)) return` 的守卫,
现在 `model.page = 1` 而 `pageOfFloor(40, 20) = 3`,直接 return,`pendingFloor` 永远兑现不了。

链路本身没问题,已逐段排除:`ChainViewModel.openInTopicKey`(`ChainViewModel.kt:136`)
算出的 `page`/`floor` 正确(`__R__ROWS_PAGE` curl 对拍 = 20,`pageOfFloor(40,20)=3`);
`entry<TopicKey>` 的 ViewModel 是按 Nav3 条目分店的,没有跨屏复用。

## 影响面

同一条路径上的四个入口都吃这个 bug:
- 回复链「在原帖中查看」(已实测)
- 历史 / 「上次读到第 N 楼」的进度恢复(`TopicKey.page`)
- 通知点进来带 `page`/`pid`
- 「我的回复」带 pid 进只看该楼

## 期望

`initialPage` 生效,或数据回来后重新对齐:首帧 `pageCount` 不可信时不要让 `settledPage`
的回写覆盖 `vm.page`(例如 `totalPages` 初值用 `initialPage()` 兜底,或首次 settle 与
`vm.page` 不一致时以 `vm.page` 为准而不是反向覆盖)。
