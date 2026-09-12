# 45 — P2:登录屏顶栏与 URL 条没接主题,用的是 Material3 默认皮

**Status:** resolved

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

## Comments

**2026-08-23 — 修复**

`ui/login/LoginScreen.kt` 的 chrome 全部换皮,改法与票 32 给 `AccountsScreen.kt` 做的那份一致:

- 顶栏:就地拼的 `Row` + `IconButton` + `MaterialTheme.typography.titleMedium`
  → 全 app 同一套 `TopBar` / `TopBarButton(CLOSE, 24, box 46)` / `TopBarTitle(SUB)` /
  `TopBarButton(REFRESH, 22)`。底色随之变回 `colors.topbar`(墨绿),图标与标题走
  `colors.onTopbar`(白)。状态栏安全区不再由本屏 `windowInsetsPadding` 撑,归 `TopBar`。
- URL 条:`MaterialTheme.colorScheme.surfaceVariant`(M3 淡紫)→ `colors.surface2`;
  文字 `onSurfaceVariant` → `colors.fg2` 且补上 RN 侧的等宽字体(`MonoFontFamily` +
  `Typo.meta`,原先用的是 M3 `labelMedium`);锁图标 `colorScheme.primary`(紫)→
  `colors.primary`(青)。
- `HorizontalDivider()`(M3 默认描边色)→ 1dp `colors.divider`,对上 RN 的
  `borderBottomColor: theme.colors.divider`。
- 根底色与提示卡外层 `colorScheme.background` → `colors.bg`;写死的 12/14/8 换成
  `Spacing.md` / `Spacing.row` / `Spacing.sm`。
- 关闭钮补了 `contentDescription = "关闭登录页"`(RN 有 `accessibilityLabel`,原生这颗
  之前在无障碍树里没名字)。

**状态栏图标**:不在本屏处理。`Theme.kt` 里的 `StatusBarIconsEffect(palette.topbar)`
按顶栏底色统一投票(票 39),顶栏一变回墨绿,图标自己就是白的。

**没动**:WebView 本身(内容缩放在票 22 的对照里是对的)、琥珀色提示卡的写死配色
(RN 侧同样写死,不跟主题走)。

**发现的票外问题**:`ui/accounts/AccountIcons.kt` 里的 `CloseIcon` / `LockIcon` /
`RefreshIcon` 现在没有调用点了(本屏是它们唯一的使用者)。没删——删了是票外改动。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**通过 —— 三个表现全部对上,取样值逐位相同。**

| 量 | Expo | 原生(修后) | 原生(修前) |
|---|---|---|---|
| 顶栏底 | `#14796b` | **`#14796b`** | 奶油/近白 |
| ✕ / 标题 / 刷新 | 白 | **白** | 黑 |
| URL 条底 | `#f5f0e0`(surface2) | **`#f5f0e0`** | M3 淡紫 |
| URL 条锁图标 | rgb(20,121,107) = `#14796b` | **rgb(20,121,107)** | 紫 |
| 状态栏图标 | 白 | **白** | 黑 |

状态栏那条没有在本屏写任何代码,是票 39 的 `StatusBarIconsEffect(palette.topbar)`
顶栏一变回墨绿自己翻过来的 —— 修票里的预期成立。

复验只走到登录页渲染,**没有登录、没有输入任何凭证**。

对照图 [`../acceptance/visual/after/22.png`](../acceptance/visual/after/22.png)
