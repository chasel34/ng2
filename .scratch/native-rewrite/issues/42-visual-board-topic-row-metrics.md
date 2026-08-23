# 42 — P3:版块屏主题行标题偏大 6%、子版块 chip 偏大 11%

**Status:** open

**Severity:** P3(纯视觉;后果是单屏少装一条主题)

## 现象

同一版块同一条主题,两边渲染宽度不同 —— **同一串文字的渲染宽是与截图坐标原点无关的量**,
所以这是字号差,不是内距差:

| 元素 | 样本 | Expo | 原生 | 差 |
|---|---|---|---|---|
| 主题标题 | 「[赛事杂谈]Insane LFT + [守望先锋赛事]」 | 387px(半缩放) | 412px | **+6.5%** |
| 子版块 chip | 「队友招募」整颗 chip | 100px | 111px | **+11%** |

+6.5% 恰好是 `Typo.listTitle`(16sp)与 `Typo.topicTitle`(17sp)之间的比例 ——
怀疑这一屏取错了字号档。

**可视后果**:同一屏 Expo 装得下 8 条主题,原生只装 7 条。

**没有偏离的**:meta 行(人形图标 + 作者名左对齐 / 最后回复人 + 气泡 + 回复数右对齐)、
分隔线、隔行底色、版头行、FAB —— 逐项同位。楼层正文字号也没问题
(04b 里同一段正文两边**同一处折行**)。

## 对照图

[`../acceptance/visual/03-board.png`](../acceptance/visual/03-board.png)

## 期望

主题行标题与 RN `src/app/board/` 列表行取同一档字号(对照 `src/ui/tokens.ts` 里
`listTitle` / `topicTitle` 的用法),chip 的字号与左右内距同样对齐 RN 侧。

## 疑似代码位置

`ui/board/TopicRow.kt`(标题 `Typo.*` 取档)、`ui/board/BoardScreen.kt` 或
`ui/board/SubBoardsScreen.kt`(子版块 chip 的 `Typo` 与 `Spacing`)。
