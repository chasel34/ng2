# 快速连滑丢页/回跳（时序竞态）

Status: resolved（2026-08-19 修复并真机验证通过）

## 现象

快速连续横滑，可能只前进一页、或画面跳回上一个分类。

## 复现（可脚本化，确定性较高）

```
adb shell "input swipe 1000 1600 250 1600 100; input swipe 1000 1600 250 1600 100"
```

从「推荐版块」连滑两次，预期落在「魔兽世界」（+2），实际落在「网事杂谈」（+1）
——第二次滑动被竞态吞掉。anzong 同样操作稳定 +2。

## 根因

`swipe-pager.tsx` 存在一个明确的竞态窗口：

1. 翻页动画的 `withTiming` 回调在 **UI 线程当场**把 `settling.value = false`
   （注释自述：不等 JS 重渲染，怕吞掉下一次横滑）；
2. 但 `paging.value`（页码镜像）要等 `runOnJS(commit)` → React 重渲染 →
   `useLayoutEffect` 之后才更新。实测这个窗口约 40–90ms（板块页更长）。

窗口内新手势：

- `onEnd` 用**旧页码**算目标 → commit 到已经是当前页的目标（滑动被吞，实测 +1）；
  往回滑则会 commit 到更早的页 = 「调回上一个板块」；
- `onUpdate` 直接覆写 `translateX`（此刻停在 `-width`）为小位移 → 轨道跳回
  显示**旧页面**，即用户看到的「跳回上一个」。

另：`commit` 里的 `COMMIT_GUARD_MS` 兜底是死代码——`settling` 在动画回调里已经
置 false，`setTimeout` 进来必然提前 return，「调用方吞掉 page 时收回轨道」的
承诺实际不成立。

## 修复方向

竞态的根子和 01 相同：页码真值在 JS、轨道位置在 UI 线程，两边靠「commit 后复位」
缝合。若改为 UI 线程持有 `page`（动画结束当帧在 worklet 里更新页码与基准位移，
JS 只负责渲染面板内容），窗口即消失；或改用 react-native-pager-view 由原生托管。

## Comments

**2026-08-19 修复**:`swipe-pager.tsx` 持有权改造——页码真值(`pageSV`)归 UI 线程,
面板按页号绝对定位加 key,松手时 worklet 同帧翻页+drag 重定基(画面数学上不动),
commit 提前到松手瞬间且不再有任何「复位」;收尾动画可被下一次按下半路接管。
首页 tab 下划线改为浮动单条,吃 pager 的 progress 共享值逐帧插值;
详情页 currentPage/NeighborPage 合并为单一 TopicPageView(激活只换 props 不换实例)。
待真机复测后回填验证结果。

**2026-08-19 验证通过**:板块与帖子各做快速连滑两次(100ms 间隔 input swipe),
均精确 +2(推荐版块→魔兽世界… / 帖子第 1→3 页),无吞滑、无回跳。
配套修正:`swipeTargetPage` 拆开 `reach`(视觉位移,接管时含余位)与 `dx`(本把
手势位移)——甩动看速度方向、慢拖看视觉位置,否则接管后连甩会因余位方向相反被
判成「往回翻」;新增两条单测锁住该场景。
