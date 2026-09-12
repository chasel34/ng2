# 43 — P3:楼层长按菜单几乎占满屏宽,版块 kebab 菜单也偏宽

**Status:** resolved

**Severity:** P3(纯视觉;但楼层菜单那一下观感差异很大)

## 现象

1. **楼层长按菜单**:Expo 是贴着右侧 ⋮ 弹出的一列,宽 242px(半缩放)≈ **184dp**;
   原生宽 518px ≈ **394dp** —— 左边一直铺到屏幕左缘,**几乎占满整屏宽**。
2. **版块顶栏 kebab 菜单**:Expo 242px ≈ 184dp、右边距 ≈ 14dp;原生 270px ≈ **206dp**、
   右边距 ≈ 8dp。

同一个 app 里这两个菜单在原生下宽度差了近一倍(206dp vs 394dp),自家也不自洽。
条目字号一致(「24 小时热帖」两边渲染宽都是 120px),条目行距一致(65.5px),
菜单顶端 y 也一致 —— 差的只有宽度与左右内距。

## 对照图

[`../acceptance/visual/05-floor-menu.png`](../acceptance/visual/05-floor-menu.png)(楼层菜单)、
[`../acceptance/visual/03b-board-kebab.png`](../acceptance/visual/03b-board-kebab.png)(kebab 菜单)

## 期望 / 实际

| | Expo(基准) | 原生(实际) |
|---|---|---|
| 楼层菜单宽 | ≈ 184dp,右锚定在 ⋮ 下方 | ≈ 394dp,左缘贴屏 |
| kebab 菜单宽 | ≈ 184dp,右边距 ≈ 14dp | ≈ 206dp,右边距 ≈ 8dp |

## 疑似代码位置

`ui/common/OverflowMenu.kt` —— 两处菜单大概率共用它;楼层那一路的调用点在
`ui/topic/FloorCard.kt` / `ui/topic/TopicOverlays.kt`。RN 侧原件是 `src/ui/menu.tsx`,
宽度与最大宽在那里。

## Comments

**完成摘要**(2026-08-23)

两个菜单原来各写各的,现在共用同一组档位常量(定义在 `ui/common/OverflowMenu.kt`,
数值就是 RN `src/ui/menu.tsx` 的 `minWidth: 186` / `maxHeight: 520` / `item.height: 50` /
`item.paddingHorizontal: 22`):

- **楼层长按菜单**(`ui/topic/TopicOverlays.kt`)394dp 的成因:面板列没有任何宽度上界,
  而条目是 `fillMaxWidth` —— 于是条目吃满父约束(屏宽 − 左右各 8),把整列撑到满屏。
  `defaultMinSize(186)` 只管下界,管不了这个。改成 `defaultMinSize(minWidth = MENU_MIN_WIDTH)`
  \+ `width(IntrinsicSize.Max)`:宽度收到「最长那条条目」(文字 + 左右 22)上,再由 minWidth 兜底,
  与 RN 的 `minWidth + wrap-content` 同义。
- **顶栏 kebab 菜单**(`ui/common/OverflowMenu.kt`)206dp 的成因:写死 `width(208.dp)`。
  换成同一套「最小 186 + 内容撑宽」;条目左右内距 `Spacing.xl`(20)→ 22(RN 值)。
- 右边距保持 `Spacing.sm` = 8dp —— RN 那边就是 `right: spacing.sm`(8),票面量到的 ≈14dp
  是 RN 面板 `boxShadow` 的模糊边把视觉边缘往里推的结果,**不照它改**。

顺带(同一个组件的同类偏离,复量确认):楼层菜单条目字号原来是 `Typo.notice`(13.5),
RN 那边所有菜单条目都是 `menuItem`(15.5)。对照图 `05-floor-menu.png` 上同一条「贴条」
Expo 39×19px、原生 35×17px(半缩放),比值 1.11 ≈ 15.5/13.5。已改为 `Typo.menuItem`。
票面说「条目字号一致」是拿 kebab 菜单量的(那份本来就用 `menuItem`),楼层这份没量到。

**单测**:`ui/common/MenuMetricsTest.kt`,5 条 —— `MENU_MIN_WIDTH = 186`、`MENU_MAX_HEIGHT = 520`、
`MENU_ITEM_HEIGHT = 50` 与 `MENU_ITEM_PADDING = 22`、条目字号 `menuItem` 15.5/22、
「186 的面板给文字留 142」这条换算关系(条目宽 = 文字宽 + 44)。
两个菜单现在引用同一批常量,数值只要有一处被改回去,这五条就会红。

**未完成 / 待复验**

- 宽度是布局期算出来的,JVM 钉不住,只钉了输入档位。设备上复验两点:楼层菜单右锚定、
  宽度落在 186 与「最长条目」之间(菜单文案最长的是「按最后回复排序」七字,15.5sp 下约 109dp + 44 = 153 < 186,
  所以两个菜单实际都应该正好是 **186dp**);kebab 菜单右边距 8dp。

**发现的票外问题**

- 楼层菜单面板没有阴影(RN 面板带 `elevation2`),顶栏 kebab 那份有。同样记在票 40 里,本票没动。
- 顶栏 kebab 菜单的分组分隔线左右内缩了 `Spacing.lg`(16),RN 那条 hairline 是**通宽不留外距**
  (`menu.tsx` 的 `separator` 注释专门写了这一点)。属于票 40/43 之外的细节,没动。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**通过 —— 两个菜单的面板宽与右边距和 Expo 逐像素相同。**

面板底(`#f7f4ee`)在同一行上的像素跨度:

| 菜单 | Expo | 原生(修后) | 原生(修前) |
|---|---|---|---|
| 版块顶栏 kebab | x 571–1058,**488px = 186dp**,右边距 21px = 8dp | **x 571–1058,488px = 186dp,右边距 21px** | 206dp |
| 楼层长按菜单 | x 571–1058,**488px = 186dp**,右边距 21px = 8dp | **x 571–1058,488px = 186dp,右边距 21px** | 394dp(几乎满屏) |

修票 Comments 里「两个菜单实际都应该正好是 186dp」的预测**一字不差地兑现**。
右边距 8dp 也与「票面量到的 ≈14dp 是 RN 阴影模糊边」的判断一致 —— Expo 实测就是 21px。

条目字号:同一条「贴条」两边视觉一致(原生节点 bounds 83px 宽,含左右 bearing),
`Typo.menuItem` 15.5 那一改在设备上看不出偏差。条目行距两边同为 65.7px。

对照图 [`../acceptance/visual/after/03b.png`](../acceptance/visual/after/03b.png)、
[`../acceptance/visual/after/05.png`](../acceptance/visual/after/05.png)
