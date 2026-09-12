# 39 — P2:浅色下状态栏图标是黑的;顶栏标题可用宽度与 Expo 不同

**Status:** resolved

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

## Comments

**完成摘要**

- 新增 `ui/theme/SystemBars.kt`:`relativeLuminance` / `isLightSurface` /
  `appearanceLightStatusBarsFor(topbar)` 三个纯函数 + `StatusBarIconsEffect(topbar)` 这个
  把结论写进 `WindowInsetsControllerCompat.isAppearanceLightStatusBars` 的 SideEffect。
  判据只认**顶栏底色**,与页面深浅、系统夜间模式都无关。
- `ui/theme/Theme.kt`:`Ng2nTheme` 先算出 palette,再 `StatusBarIconsEffect(palette.topbar)`,
  主题风格/夜间档一变就重投。
- `MainActivity`:`enableEdgeToEdge()` → `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(TRANSPARENT))`。
  **根因就在这里**:不带参时是 `SystemBarStyle.auto`,按系统夜间模式投票,浅色档下把图标刷成黑的。
  首帧就定死,避免开屏闪一下黑图标;之后交给上面那个 effect。
- 顶栏标题:`ui/topic/TopicScreen.kt` 的标题从 `Modifier.weight(1f)`(铺满剩余空间)改成
  `maxWidth = TOPIC_TITLE_MAX_WIDTH = 190.dp` —— RN 侧 `src/app/topic/[tid].tsx:871` 就是
  `maxWidth={190}`;右侧两枚图标改由新加的 `Spacer(Modifier.weight(1f))` 推到底
  (对应 RN 的 `topBarSpacer` = `margin-left:auto`)。
- `ui/topic/TopicChrome.kt` 的 `TopBarTitle`:`modifier.width(maxWidth)` → `widthIn(max = maxWidth)`。
  RN 那边是 `maxWidth` + `flexShrink:1`,是**上限**不是定宽,短标题不该占满 190。

**关键决定**

- 「浅底」的阈值取 **0.179**(WCAG 黑白前景对比度的翻转点,解 `(L+.05)/.05 = 1.05/(L+.05)`),
  不用拍脑袋的 0.5。主题青 `#14796B` 的相对亮度是 0.1489 → 白图标;奶油 `#FCF4E1` 是 0.91 → 深图标。
- 亮度自己算(sRGB 解伽马 + 人眼加权)而不是用 Compose 的 `Color.luminance()`,
  为的是判据留在纯算术里、JVM 单测能逐值对拍(`SystemBarsTest`,7 例:三套配色的顶栏各一、
  「判据不是页面底」一条、纯黑纯白、纯蓝 vs 纯绿(同通道均值不同亮度)、WCAG 已知值)。
- 没有照 RN 那样全局钉死 `<StatusBar style="light" />`:票里点名说票 45 的登录屏之后可能出现浅顶栏,
  跟色判据到那时会自己翻过来,不用改代码。

**未完成 / 待所有者**

- 状态栏颜色与标题截断点的实拍复验没做(本次要求不开模拟器)。
  190dp 在对照图那台机器上 ≈ 261 半缩放 px,票里量到的 Expo 侧是 251 —— 同一档。

**发现的票外问题**

- 其余屏的顶栏标题仍是 `TopBarTitle(...)` 不带 `maxWidth`(靠 `weight(1f)` 或自然宽度),
  与 RN 一致(RN 只给版块列表 150、详情 190 两处标了截断宽度),没动。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**两条都通过。**

**#1 浅色档状态栏图标**:`(990,66)` 取样 —— 首页 / 版块 / 主题详情 / 看图屏 / 登录屏
五屏在浅色档均为 **`#ffffff`**(修前 `#000000`)。夜间档仍是白(N1 复截确认)。
登录屏那种「顶栏也是墨绿」的屏跟色判据没跑偏。

**#2 顶栏标题可用宽度**:同一帖同一顶栏,uiautomator 取标题节点 bounds ——
原生 `[143,172][631,235]`,**宽 488px = 186dp**(修前 ≈278dp;Expo 票面量到 ≈191dp)。
落在 `TOPIC_TITLE_MAX_WIDTH = 190dp` 上,与 RN 的 `maxWidth={190}` 同档。
同图上标题墨迹:Expo x144–606(463px)、原生 x146–567(422px)——
原生少截一个字,是 CJK 字形整体窄约 2% 的连带(见票 42 复验),不是宽度设错。

对照图 [`../acceptance/visual/after/04b.png`](../acceptance/visual/after/04b.png)、
[`../acceptance/visual/after/01.png`](../acceptance/visual/after/01.png)
