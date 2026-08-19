# 翻页 commit 后闪一下「载入中」/ 错位内容

Status: resolved（2026-08-19 修复并真机逐帧验证通过）

## 现象

板块与帖子横滑翻页，滑动动画停稳后约 60ms，整屏闪过一帧别的内容再回到正确页面。
体感「像重新加载了一次数据」。

## 逐帧证据（帖子详情，t3.mp4，1 页 → 2 页）

- 1489ms：滑动停稳，屏上是第 2 页楼层（20/21 楼），页码条还亮着 1（commit 前，正确）
- **1514–1548ms：整屏变成「第 3 页载入中」骨架屏，持续 4 帧（120Hz，约 43ms）**，页码条已亮 2
- 1557ms：恢复第 2 页内容

即：React commit 把三块面板重排成 [1|2|3骨架] 的那一帧，轨道 `translateX` 还停在
`-width` 上——屏幕正中显示的是**新的右侧面板**（下一页的骨架）。40ms 后
`translateX = 0` 才落到 UI 线程，画面跳回正确面板。

## 根因

`swipe-pager.tsx` 的 `useLayoutEffect` 里注释写「它与面板的重排在同一个提交里,
晚一拍就会闪一帧错位的内容」——**这个前提不成立**。`useLayoutEffect` 里写
`translateX.value = 0` 是 JS 线程对 Reanimated shared value 的写入，走 Reanimated
自己的批量通道；Fabric 的 mount 走另一条。同一个 React commit ≠ 同一个渲染帧，
实测差 4 帧。只要「复位轨道」和「重排面板」分属两条通道，这个闪就修不掉，
只能换架构（见 spec 讨论：每页固定绝对位置 `left=(page-1)*width`、轨道
`translateX = -(page-1)*width + drag` 全部由 UI 线程持有，commit 只增删远端面板，
不需要任何「复位」；或直接上 react-native-pager-view）。

## 板块页的加重症状（t2.mp4，网事杂谈 → 魔兽世界）

板块页三块面板在 commit 时**原地互换 LegendList 的 data**（pane2 从网事杂谈的行
换成魔兽世界的行）。回收池、已测量高度、滚动锚都是上一个分类的，结果不止闪一下：

- 落地后列表停在**中间位置**（顶部显示「暴雪游戏」区块，真实顶部是
  「艾泽拉斯议事厅」，上滚可证）
- 该屏所有版块图标空白，且**持续数秒不恢复**（同一批 board 在别的分类里图标正常）

修复方向：面板内容按页号/分类 id 加 key，换页时换实例而不是在同一个列表实例上
整表换数据（这条独立于上面的轨道复位问题，两个都要修）。

## 对拍

anzong（gov.anzong.androidnga，原生 ViewPager）同手势逐帧：停稳后无任何突变帧。

## Comments

**2026-08-19 修复**:`swipe-pager.tsx` 持有权改造——页码真值(`pageSV`)归 UI 线程,
面板按页号绝对定位加 key,松手时 worklet 同帧翻页+drag 重定基(画面数学上不动),
commit 提前到松手瞬间且不再有任何「复位」;收尾动画可被下一次按下半路接管。
首页 tab 下划线改为浮动单条,吃 pager 的 progress 共享值逐帧插值;
详情页 currentPage/NeighborPage 合并为单一 TopicPageView(激活只换 props 不换实例)。
待真机复测后回填验证结果。

**2026-08-19 验证通过**（重打 release 装机,逐帧对拍):
- 帖子 1→2:滑动 1129–1495ms 平滑衰减,全程无骨架帧(dark_px 判据),停稳后零突变;
- 板块 网事杂谈→魔兽世界(旧版闪最重的组合):停稳后零突变,落地在真实顶部、图标齐全;
- 最大分类(手机游戏 300+ 版块)落地正常。

**过程中踩的新坑**(第一版修复引入,已修):面板绝对定位在 `absoluteFill` 轨道的布局
边界外、靠 transform 拉回屏内——RN 的 JS 命中测试(点击/RNGH)不受影响,但 Android
**原生触摸分发按子 view 布局边界裁剪**,面板里的 ScrollView 收不到 move,纵向滚动
全灭。修法:轨道显式 `width = count * 屏宽`,让所有面板都在边界内(旧实现的
`width*3 + left:-width` 正是为此)。
