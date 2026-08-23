# 38 — P2:抽屉账号头用 Material3 默认紫,且没吃状态栏安全区

**Status:** open

**Severity:** P2(全 app 最显眼的一块面积用了错色;头像被状态栏压住)

## 现象

抽屉顶部那块账号头(头像 + 「已登录 1 个账号 · 左右滑动切换」+「当前:lemon43(…)」)有两处偏离:

1. **底色是 Material3 默认紫**(≈ `#7B5EA7`),头像圆也是浅紫。Expo 那边是主题青
   `#14796b`(= `colors.primary`)。抽屉一拉开,整块紫色压在一片墨绿/奶油的 app 上,
   是当前视觉对照里最扎眼的一条。
2. **没吃 `statusBars` 安全区**:头像圆心与状态栏时间**并排**,而 Expo 的头像整个落在状态栏
   下方。连带整块头比 Expo 矮约 47dp,下面「论坛功能」分区标题跟着上移。

这不是新发现 —— 票 32 的 Comments「没做 / 待所有者」第 1 条已经写明
`ui/accounts/AccountHeader.kt` 还是 `colorScheme.primary` / `onPrimary` /
`typography.titleMedium|labelMedium`,并建议主控单开一张票。本票就是那张票,
外加实拍出来的第 2 条(安全区)。

## 对照图

[`../acceptance/visual/02-drawer.png`](../acceptance/visual/02-drawer.png)(左 Expo / 右原生)

## 期望 / 实际

| | Expo(基准) | 原生(实际) |
|---|---|---|
| 账号头底色 | `colors.primary` `#14796b`(夜间 `#1E9384`) | M3 `colorScheme.primary` 淡紫 |
| 头像圆底 / 文字 | 半透明白圆 + 白字 | 浅紫圆 + 白字 |
| 顶部内距 | 顶到状态栏底边,头像完整落在状态栏之下 | 头像与状态栏时间并排,顶部少了一个状态栏高度 |
| 整块高度 | ≈ 206dp | ≈ 159dp |

其余(头像直径、左右箭头位置、两行副文案的字号与行距、条目区起始位置相对账号头的偏移)
两边一致,**只换皮 + 补安全区即可**,不要重排。

## 疑似代码位置

- `ui/accounts/AccountHeader.kt` —— `MaterialTheme.colorScheme.*` 全换
  `LocalNg2nColors.current`(`primary` / `onPrimary`),`MaterialTheme.typography.*` 换 `Typo`,
  照票 32 给 `AccountsScreen.kt` 做过的同一套映射。
- 安全区:这块头是抽屉内容的第一项(`ui/drawer/AppDrawerContent.kt` → `AccountHeader`),
  抽屉是 edge-to-edge 容器,需要在头这一块自己 `windowInsetsPadding(WindowInsets.statusBars)`
  (或 `statusBarsPadding()`),而不是靠外层。
