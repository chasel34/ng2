# 41 — P3:Tab 指示条只有文字宽(Expo 是整格宽),夜间还换了颜色

**Status:** open

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
