# 45 — P2:登录屏顶栏与 URL 条没接主题,用的是 Material3 默认皮

**Status:** open

**Severity:** P2(与票 32 同族的最后一处;登录是新用户见到的第一屏)

## 现象

托管 NGA 官方登录页的那一屏,chrome 部分两边完全不同:

| | Expo(基准) | 原生(实际) |
|---|---|---|
| 顶栏底 | 主题青 `#14796b` | **奶油/近白**,没有底色 |
| 顶栏 ✕ / 标题 / 刷新 | **白**(`colors.onTopbar`) | **黑** |
| URL 条底 | `colors.surface2` 奶油 `#f5f0e0`(`src/app/login.tsx:143`) | **M3 默认淡紫**(≈ `#ece6f5`,`surfaceVariant`) |
| URL 条锁图标 | 青(`login.tsx:102` `theme.colors.primary`) | **紫** |
| 状态栏图标 | 白 | 黑(跟着变浅的顶栏,这里反而是对的) |

RN HEAD `src/app/login.tsx:83-99` 用的是统一的 `TopBar` + `TopBarButton` + `TopBarTitle`,
和别的二级屏一个模子。

WebView 里的内容、底部「客户端仅托管官方登录页……」提示卡的位置/圆角/底色两边一致,
**只有 chrome 需要换皮**。

## 对照图

[`../acceptance/visual/22-login.png`](../acceptance/visual/22-login.png)

## 疑似代码位置

`ui/login/LoginScreen.kt` —— 顶栏换 `ui/common/TopBar.kt` 那一套,
`MaterialTheme.colorScheme.*` 全换 `LocalNg2nColors.current`,
映射照票 32 给 `AccountsScreen.kt` 做过的那份(`surfaceVariant→surface2`、`primary→primary`)。
状态栏图标色跟着顶栏翻(见票 39)。
