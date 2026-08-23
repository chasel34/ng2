# 20 — P1:带页码/楼层进主题详情被 Pager 打回第 1 页

**Status:** resolved

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

## Comments

**修复(2026-08-23)**

根因就是票里定位的那条:`TopicViewModel.totalPages` 初值 `1`,而首帧的 `page` 已经是
`initialPage()` 算出来的第 3 页 —— 这个「页码超出总页数」的状态被两处夹逼同时碾平:

1. `HorizontalPager` 的 `pageCount = totalPages.coerceAtLeast(1)` 装不下第 3 页,
   `PagerState` 把 `currentPage` 钳成 0,`snapshotFlow { settledPage }` 立刻把这一下
   **回写**成 `goToPage(1)`;
2. 就算绕过 pager,`goToPage()` 里的 `clampPage(next, totalPages)` 也会把 3 夹成 1。

所以修的是**初值**,不是回写:

- `TopicViewModel.kt` — `totalPages` 初值从 `1` 改成 `page`(即 `initialPage()`)。
  真实页数在 `onPageLoaded()` 里覆盖它,估多的那几页只活到第一发响应回来。
  一处改动同时解开上面两道夹逼,`redeemPendingFloor()` 的
  `model.page != pageOfFloor(...)` 守卫也随之不再误挡,楼层锚点跟着活了。
- `Paging.kt` — 新增 `pagerPageCount(totalPages, page) = maxOf(1, totalPages, page)`,
  把「pager 至少装得下当前页」立成**结构性**下界,而不是靠 `totalPages` 初值这一个巧合。
  `TopicScreen.kt` 的 `pageCount` 与外部换页的 `coerceIn` 都改走它。

顺带修了 checklist #11(与本票同根):

- `ui/lists/HistoryScreen.kt` — 新增 `historyTopicKey(entry)`,历史条目 push 的
  `TopicKey` 带上 `floor = lastFloor`(≥1 才带;只读过主楼就不带,那本来就是第 1 页顶部)。
  **只带 `floor` 不带 `page`**:历史里没存 `rowsPerPage`,页码交给 `initialPage()`
  按每页 20 楼估,真实值回来后 `redeemPendingFloor()` 再核对一次。
- `TopicViewModel.kt` — 键上带的楼号 == 历史进度楼层时**不再弹**「上次读到第 N 楼」浮条:
  人已经被送到那一楼了,再问一句「回到那里?」是自问自答。别的楼号进场
  (回复链「在原帖中查看」)照旧提示。

**对 RN 版的有意偏离**:RN 的 `src/app/history.tsx` push 时只带 `tid/title/fav`,
不带楼层 —— 这是 checklist #11 记下的已知缺陷,按简报「已知缺陷不随迁」修掉。

**单测**(JVM,`:app:testDebugUnitTest` 全绿 861 例):
- `PagingTest` +3:`pagerPageCount` 的下界、不小于 1、与 `clampPage` 合起来的恒等圈
- `TopicViewModelTest` +3:带页码进场首帧不被夹逼吃掉(并断言**第一发请求就是第 3 页**)、
  带第 3 页楼号进场兑现滚动目标、历史进度楼层进场不重复弹浮条
- `TopicNavKeysTest`(新)6 例,其中 2 例钉 `historyTopicKey`

**未做 / 待所有者**:模拟器被另一个代理占用,四条入口(回复链「在原帖中查看」、历史、
通知、我的回复)的**运行期复验没做**,只有 JVM 单测与代码路径推演。

**发现的票外问题**:无。
