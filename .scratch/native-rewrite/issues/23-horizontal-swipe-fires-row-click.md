# 23 — P2:列表屏横划会误触发落点那一行的点击

**Status:** resolved

**Severity:** P2(误导航,每个列表屏都中;主题页练出来的「横划翻页」肌肉记忆到列表上就翻车)

## 现象

在版块列表 / 搜索结果这类列表上**横向划一把**,手指落点那一行的 `clickable` 会被触发,
当场跳进那个主题。列表本身没有横向滚动,预期是「什么也不发生」。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,2026-08-22)

1. 首页 → 魔兽世界 →「艾泽拉斯议事厅」
2. `adb shell input swipe 900 1200 200 1200 200`(在列表区从右往左划)
3. 屏幕跳进落点那一行的主题(实测「我发现有没有上个版本的毕业装备差距比天大」)

搜索结果页同样(`input swipe 900 1200 200 1200 200` → 跳进落点那条结果)。

## 定位

这正是抽屉那处 2026-08-22 已经踩过并修好的坑,`ui/drawer/DrawerHost.kt:210-219` 写着:

> 面板那一处本来挂在 Main 通道上……模拟器实测不成立:抽屉里横划一把会**触发落点那一行的点击**……
> 原因是 Main 通道是子 → 父,`clickable` 的 `waitForUpOrCancellation` 处理完这一发时父节点
> 还没来得及消费,而它只在「手指离开边界」时才取消,横向平移到别处并不算离开。

抽屉靠把手势挪到 `PointerEventPass.Initial` 解决了。列表屏没有任何横向手势节点,
所以 `clickable` 那条路径无人拦截 —— 横向平移 700px 仍然算一次 tap。

主题详情不受影响:那里有 `HorizontalPager` 吃掉横向拖动。

## 影响面

用 `Modifier.clickable` 画行的列表屏都中,至少:版块列表、搜索结果、浏览历史、
我的缓存、收藏、精华区、24h 热帖。

## 期望

行的点击手势在横向位移超过 touch slop 后取消(例如行上挂一个只认横向、认领后 consume 的
`pointerInput`,或用带 slop 判定的自定义 tap),横划不产生导航。

## Comments

### 2026-08-23 修复

**改了什么**:新增 `ui/common/RowTap.kt` —— `Modifier.rowClickable()`(= 横划取消 +
`clickable`)与纯函数判据 `shouldCancelRowTap(dx, dy, slopPx)`,并把列表**整行**那一档
的 `Modifier.clickable` 换成它:

- `ui/board/TopicRow.kt`(版块列表 / 搜索结果 / 收藏 / 精华区 / 24h 热帖 / 某人的主题共用这一行)
- `ui/lists/HistoryScreen.kt`(浏览历史)、`ui/lists/CachesScreen.kt`(我的缓存)
- `ui/lists/NotificationsScreen.kt`(通知)、`ui/lists/SearchScreen.kt`(搜索历史 / 版块结果 / 用户结果)
- `ui/board/SubBoardsScreen.kt`(子版块行)、`ui/board/BoardScreen.kt`(版头行)、`ui/lists/ListCommon.kt`(`ListSubtitle`)

**做法照抄抽屉那处**(`ui/drawer/DrawerHost.kt` 的 2026-08-22 结论):手势跑
`PointerEventPass.Initial`,**认领前一个事件都不消费**(纵向滚动、长按、行内小按钮照常走);
一旦横向位移过 touch slop 且横向压过纵向,就把这一发起每一发都 `consume()` ——
内层 `clickable` 收到已消费的事件即取消按压,抬手不再报点击。Initial 是父 → 子,
消费一定早于 `clickable` 处理这一发;挂 Main 通道就是抽屉那次漏过去的原因。

判据与抽屉的 `shouldClaimDrawerDrag` 有一处**有意不同**:这里不看方向也不要求横纵比
过 1.3,只要 `|dx| > slop && |dx| > |dy|` 就取消 —— 抽屉要判「往哪边拉」,行只要判
「这不是一次点」,宁可多取消一次点击,也不要平白跳进一个主题。

**没动的地方**(有意):

- 行内的小按钮(缓存页的删除、子版块的订阅钮、搜索历史的删除)不单独挂 ——
  整行那一层的 Initial 消费是它们的祖先,已经把它们一起保护了;
- 首页版块格子(`ui/home/HomeScreen.kt` 的 `BoardCell`)、首页分类 tab、版块页子版块 chip:
  这三处的祖先分别是 `HorizontalPager` 与 `horizontalScroll`,**横向手势本来就有人接**
  (所以它们也不在本票的现象里),挂上去反而会把父容器的横滑吃掉;
- 顶栏 / 对话框 / 提示条的按钮:小目标上划不出 slop,不值得多一个 `pointerInput`。

**单测**:`ui/common/RowTapTest.kt` 4 条(复现票里那一发 `swipe 900 1200 200 1200`
的纯横向 700px;按住不动的抖动仍算点击;纵向滚动不归它管;斜着但横向占优也取消)。
手势接线(Initial 通道 + consume)只有真机能验,**待所有者**在真机上按复现步骤回归一次。

**发现的票外问题**:首页分类 tab、版块页子版块 chip 这类「横向滚动条里的可点项」是另一
套问题(横划它们本来就该滚动而不是点),本票没碰。

**主控验收(2026-08-23)**:合并后重打包装模拟器复验通过(版块列表左右各 3 次横划,未进任何主题)。
