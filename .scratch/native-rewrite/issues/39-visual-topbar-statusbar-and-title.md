# 39 — P2:浅色下状态栏图标是黑的;顶栏标题可用宽度与 Expo 不同

**Status:** open

**Severity:** P2(状态栏在深青顶栏上几乎看不清)

## 现象

1. **状态栏图标/时间在浅色档是黑色**。顶栏底是深青 `#14796b`,黑色的时间与信号/电池
   图标基本糊在里面;Expo 那边是白色。夜间档两边都是白色 —— 说明只有浅色档的
   `isAppearanceLightStatusBars` 取反了(它跟的应该是**顶栏色**,不是页面深浅)。
2. **顶栏标题可用宽度比 Expo 大**。同一帖同一顶栏(返回 + 标题 + 地球 + ⋮,三颗图标两边同位),
   Expo 在 13 个字后截断,原生到 17 个字才截断;标题框宽 ≈ 251px vs 365px(半缩放)。
   截断点不同会让同一个帖子在两边显示成不同的标题。

## 对照图

- [`../acceptance/visual/01-home.png`](../acceptance/visual/01-home.png)(状态栏,原报告 01 #1)
- [`../acceptance/visual/03-board.png`](../acceptance/visual/03-board.png)、
  [`../acceptance/visual/04-topic.png`](../acceptance/visual/04-topic.png)(状态栏,浅色多屏复现)
- [`../acceptance/visual/N1-home-dark.png`](../acceptance/visual/N1-home-dark.png)(夜间两边都白,反证)
- [`../acceptance/visual/04b-topic-floors.png`](../acceptance/visual/04b-topic-floors.png)(标题截断)

## 期望 / 实际

| | Expo(基准) | 原生(实际) |
|---|---|---|
| 浅色档状态栏图标 | 白(`(990,66)` = `#ffffff`) | 黑(`#000000`) |
| 夜间档状态栏图标 | 白 | 白(对) |
| 顶栏标题宽 | ≈ 251px(半缩放),13 字截断 | ≈ 365px,17 字截断 |

## 疑似代码位置

- 状态栏:`ui/theme/Theme.kt` 或 `MainActivity` 里对
  `WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars` 的赋值 ——
  判据要用「顶栏底色是否浅」,浅色档顶栏是深青,所以这里应当是 `false`。
  注意登录屏(票 45)那种顶栏变浅的屏要跟着翻过来。
- 标题宽:`ui/common/TopBar.kt` 的 `TopBarTitle` —— Expo 侧 `src/ui/top-bar.tsx` 给标题留的
  是「屏宽 − 返回钮 − 右侧按钮 − 间距」,原生这边多半是 `weight(1f)` 之后没扣掉某段边距。
