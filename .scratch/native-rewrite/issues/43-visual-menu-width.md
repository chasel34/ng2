# 43 — P3:楼层长按菜单几乎占满屏宽,版块 kebab 菜单也偏宽

**Status:** open

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
