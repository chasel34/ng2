# 板块 tab 高亮/滚动与横滑不同步

Status: resolved（2026-08-19 修复并真机逐帧验证通过）

## 现象

横滑切分类时，上面的 tab 条全程不动；内容动画结束后 tab 高亮才跳变，再过一拍
tab 条才开始滚动把选中项带进视野。

## 逐帧证据（t1.mp4，我的收藏 → 推荐版块）

- 1350–1703ms：内容滑动动画
- 1788ms：tab 高亮才从「我的收藏」跳到「推荐版块」（React commit，晚 85ms）
- 1820–1990ms：tab 条 `scrollTo({animated:true})` 才开始滚（`useEffect`，又晚一拍）

## 根因

tab 高亮是 React state（`activeCategoryId`），只在 commit 时变；tab 条滚动在普通
`useEffect` 里。SwipePager 的 `translateX` 没有以任何形式暴露给 tab 条——
架构上就没有「跟手进度」这条数据通路。

## 对拍

anzong 的 tab 指示器在手势进行中连续跟随内容位移（逐帧可见 tab 区与内容区同帧
活动），是 ViewPager + TabLayout 的标准联动。

## 修复方向

Pager 暴露一个进度 shared value（`page + translateX/width`），tab 指示器与
tab 条滚动位置用 `useAnimatedStyle`/`scrollTo` worklet 直接消费它，全程 UI 线程。

## Comments

**2026-08-19 修复**:`swipe-pager.tsx` 持有权改造——页码真值(`pageSV`)归 UI 线程,
面板按页号绝对定位加 key,松手时 worklet 同帧翻页+drag 重定基(画面数学上不动),
commit 提前到松手瞬间且不再有任何「复位」;收尾动画可被下一次按下半路接管。
首页 tab 下划线改为浮动单条,吃 pager 的 progress 共享值逐帧插值;
详情页 currentPage/NeighborPage 合并为单一 TopicPageView(激活只换 props 不换实例)。
待真机复测后回填验证结果。

**2026-08-19 验证通过**:录屏逐帧显示 tab 带与内容带同帧活动(内容 1172ms 起动,
tab 1257ms 起随手指连续移动直到 1481ms 收尾),下划线随滑动插值,无跳变。

**2026-08-19 勘误 + 二次修复**:上一条「验证通过」验错了对象——帧差里 tab 带的
活动是**文字变色和 tab 条滚动**,浮动下划线其实从上线起就没显示过(cola 盲测发现)。
教训:量化信号要绑定到具体元素,关键 UI 必须截图确认「它本身在」。

根因(模拟器 + metro 二分定位):9 个 tab 的 `onLayout` 各自对 `tabLayouts` 共享值
做「读-改-写」,写入互相覆盖,数组稀疏缺项 → 下划线 worklet 查到 `undefined`
永远 opacity 0。修法:几何攒在普通 ref 数组里,每次整体拷贝赋给共享值。

**同步时机对齐 anzong**:新增 `SwipePager.onTarget`(松手定向那一刻回调,即
ViewPager2 `onPageSelected` 的时机)——首页 tab 高亮/tab 条滚动、帖子页码条高亮
都在松手瞬间先行,重量级的窗口挪动仍等动画停稳(`onChange`)。模拟器录屏确认:
动画中段高亮已在目标 tab、下划线在两格之间滑动,与 anzong 行为一致。

**2026-08-19 真机全量回归(pudding 120Hz,release APK)**:

逐帧量化(帖子页 3→4,screenrecord 120fps,页码条带与正文带分别算帧差):

| 时刻 | 事件 |
| --- | --- |
| 1092ms | 正文带开始位移(手指起手) |
| **1208ms** | **页码条带跳变 7.63、正文带 0.07 —— 松手那一帧页码从 3 变 4** |
| 1300ms | 截帧确认:页码条已是 4,两个面板仍在滑动中,无白屏/骨架 |
| 1433ms | 正文带归零,弹簧收尾结束 |

页码比动画收尾早 **225ms** 切换,即「切页和滑动同时发生」,与 anzong 同时机。

功能回归(全部通过):

- 首页:下划线正常显示;快速连滑 +2(推荐版块 → 魔兽世界)不回跳,下划线落位正确;
  首分类边界连滑不越界;纵向滚动正常。
- 帖子页:快速连滑 +2(1 → 3);单滑 3→4;点页码跳页 4→7;**跳页后再横滑 7→8→7**
  (外部改页后 `pageSV` 重定基正确);末页 9 继续左滑被钳住;纵向滚动正常。

`tsc --noEmit` 干净,`pnpm test` 1114 passed / 14 skipped,`src/` 无 `[DEBUG-` 残留。
