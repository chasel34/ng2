# 41 — P3:Tab 指示条只有文字宽(Expo 是整格宽),夜间还换了颜色

**Status:** resolved

**Severity:** P3(纯视觉)

## 现象

首页分类 tab 与「屏蔽规则」三 tab 的选中指示条,原生**只画到文字宽**,Expo 画的是
「文字 + 左右各 16dp 内距」的整格宽:

| 屏 | Expo 指示条 | 原生指示条 |
|---|---|---|
| 首页(01) | x=16–259,**244px = 93dp** | x=42–197,**156px = 59dp** |
| 屏蔽规则「本地规则」(12a) | 112px(半缩放) | 82px |
| 屏蔽规则「官方用户屏蔽」(12b) | 120px | 112px |
| 屏蔽规则「官方关键词」(12c) | 112px | 88px |

夜间档还多一条:**选中指示条的颜色**在 Expo 是白色,原生是青色 `#1e9384`。

**反证**:搜索屏(07)的三个 tab,两边指示条**都是整格宽 180px = 屏宽/3** —— 偏离出在
首页/屏蔽规则用的那个 Tab 组件上,不是全局,搜索屏那份是对的,可以拿来对拍。

## 对照图

[`../acceptance/visual/01-home.png`](../acceptance/visual/01-home.png)、
[`../acceptance/visual/12-filters-a.png`](../acceptance/visual/12-filters-a.png)、
[`../acceptance/visual/12-filters-b.png`](../acceptance/visual/12-filters-b.png)、
[`../acceptance/visual/12-filters-c.png`](../acceptance/visual/12-filters-c.png)、
[`../acceptance/visual/N1-home-dark.png`](../acceptance/visual/N1-home-dark.png)(颜色)、
[`../acceptance/visual/07-search-a.png`](../acceptance/visual/07-search-a.png)(对的那一份)

## 期望

指示条宽 = 该 tab 整格的 layout 宽(含 `paddingHorizontal`),不是文字测量宽;
夜间选中色跟 Expo 走白(`colors.onTopbar`),不要用 `primary`。

## 疑似代码位置

`ui/home/HomeScreen.kt` 的分类 tab 条、`ui/filters/FiltersScreen.kt` 的三 tab;
RN 侧对应 `src/app/index.tsx:676-688`(tab `paddingHorizontal: lg`,indicator 取整格 layout)。
`ui/lists/SearchScreen.kt` 里那一份是正确实现,可直接对拍。

## Comments

**完成摘要**(2026-08-23)

- **宽度**:两处都是**修饰符顺序**的问题 —— `onGloballyPositioned` / `drawBehind` 报的是它
  右边那截修饰符链的几何,挂在 `padding` **后面**量到的就是内容框(文字宽),
  RN 那边量的是 tab 容器(含 `paddingHorizontal`)。
  - `ui/home/HomeScreen.kt`:`onGloballyPositioned` 提到链首(整格几何),
    并新增 `TAB_BAR_PADDING = 6.dp` —— 每格量到的 x 是它在 Row **内容**里的位置(不含这一档
    padding),而下划线画在外层 Box 上,所以画的时候补回 6dp。RN 侧 x 是含 padding 的,
    原生这边旧值 42px(=16dp)正好是「只有 tab 自己那档 16 内距」,佐证了这一层。
  - `ui/filters/FiltersScreen.kt`:指示条的 `drawBehind` 挪到 `padding(horizontal = 15.dp)` 之前。
- **夜间颜色**:**复现不出来**。两处指示条画的本来就是 `colors.onTopbar`
  (`DarkColors.onTopbar = #F2F0EB`),不是 `primary`。回到对照图 `N1-home-dark.png` 逐像素取值:
  原生指示条(y 225–228,x 628–708)是 `#f5f2ed`,Expo 那条(x 8–129)是 `#f1efea` ——
  **两边都是白的**,票面的「原生青色 `#1e9384`」应是看图时把青底上的白线判成了青。
  同一张图上 Expo 宽 122px(半缩放)= 93dp、原生 81px = 62dp,**宽度那条确认属实**。
- 搜索屏那份(`ui/lists/SearchScreen.kt`)是 `weight(1f)` 的等分 tab,指示条 `fillMaxWidth`,
  本来就是整格宽,没动。

**单测**:这两处是布局顺序,JVM 上钉不住(没有可断言的数值 token,改错了只有渲染看得出)。
新增的数值只有 `TAB_BAR_PADDING = 6.dp`,与 RN `tabBar.paddingHorizontal: 6` 同源,写在常量注释里。

**未完成 / 待复验**

- 效果要在设备上看:首页选中格下划线应为「文字 + 左右各 16」且左端与 tab 左沿齐(6dp 起),
  屏蔽规则三 tab 的指示条应为「文字 + 左右各 15」。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**宽度通过;夜间颜色那条按修票的结论收敛(两边本来都是白)。**

指示条像素扫描(全分辨率,不是半缩放;判据 = 顶栏底上的近白像素行):

| 屏 / tab | Expo | 原生(修后) | 原生(修前) |
|---|---|---|---|
| 首页「我的收藏」 | x 16–259,**244px = 93dp** | **x 16–255,240px = 91.4dp** | 156px |
| 屏蔽规则「本地规则」 | x 16–242,227px | **x 16–237,222px** | 82px |
| 屏蔽规则「官方用户屏蔽」 | x 243–542,300px | **x 238–531,294px** | 112px |
| 屏蔽规则「官方关键词」 | x 543–806,264px | **x 532–789,258px** | 88px |
| 首页(夜间)「我的收藏」 | x 16–259,244px | **x 16–255,240px** | — |

四处的**左沿都与 Expo 同位**,宽度差 4–6px(1.5–2.3%),就是 CJK 字形整体窄约 2% 的连带
(同一现象见票 42 复验),不是内距设错。

**夜间颜色**:`N1-home` 复截取样 —— Expo 指示条 `#f2f0eb`、原生 `#f2f0eb`,**同一色**;
夜间底两边都是 `#1c1c1b`、顶栏都是 `#1c1c1b`。修票 Comments 里「票面把青底上的白线判成了青」
的结论在设备上坐实。

对照图 [`after/01.png`](../acceptance/visual/after/01.png)、[`after/12a.png`](../acceptance/visual/after/12a.png)、
[`after/12b.png`](../acceptance/visual/after/12b.png)、[`after/12c.png`](../acceptance/visual/after/12c.png)、
[`after/N1.png`](../acceptance/visual/after/N1.png)
