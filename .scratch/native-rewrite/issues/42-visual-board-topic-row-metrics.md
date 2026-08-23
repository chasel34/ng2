# 42 — P3:版块屏主题行标题偏大 6%、子版块 chip 偏大 11%

**Status:** resolved

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

## Comments

**结论**(2026-08-23):**这两条复现不出来,不是原生偏离;没有改任何字号,改的是把「齐」钉死。**

在对照图 `acceptance/visual/03-board.png` 上重量了一遍(两半都是 50% 缩放,同一张图、同一阈值,
所以两边可比;`< 210` 灰度算作有墨,才能把灰色的 `[版块名]` 后缀一起框进去):

| 量 | Expo(左半) | 原生(右半) |
|---|---|---|
| 「[赛事杂谈]Insane LFT + [守望先锋赛事]」整条 | x 22–401,**380px** | x 578–949,**372px** |
| 同一条只取深色那截「[赛事杂谈]Insane LFT」 | 209px | 205px |
| 标题字形高(同一条) | 22px | 22px |
| 「队友招募」chip 外框(扫边框像素) | x 20–118,**99px** | x 575–671,**97px** |
| 单行主题行的行距(相邻标题基线带) | 101px | 98px |

也就是说原生比 Expo **略窄 2%**(抗锯齿/阈值量级),不是票面写的 +6.5% / +11%;
「同屏 Expo 8 条、原生 7 条」也没复现:两边各 8 条,只是列表内容不同(Expo 那台的板块数据更旧,
多出「xhs看到的」「[比赛讨论]看隔壁瓦的」两条,少了「[赛事杂谈]韩国腾讯…」等)。

源码侧也对得上:

- 标题:原生 `ui/board/TopicRow.kt` 按 `model.simple` 取 `Typo.listTitle`(16)/ `Typo.topicTitle`(17);
  RN `src/ui/topic-row.tsx` 是 `time === undefined ? useListTitleStyle() : styles.titleLineSimple`,
  前者取设置里的 `listFontSize`(`src/core/local/settings.ts` 默认 **17**),后者是 `listTitle`(16)。
  即:主题列表屏两边都是 17,二级列表两边都是 16 —— **同一档**。
- 子版块 chip:原生 `ui/board/BoardScreen.kt` 的 `SubBoardBar` 是
  `padding(vertical = 6.dp, horizontal = 13.dp)` + `Radius.sm`(9)+ `Typo.listMeta`(12.5);
  RN `src/app/board/[id].tsx` 的 `subBoardTag` 是 `paddingVertical: 6, paddingHorizontal: 13,
  borderRadius: radius.sm` + `listMeta` —— **一字不差**。

**单测**:`ui/theme/ListMetricsTest.kt`,5 条 —— `topicTitle` 17/24.65(行高 1.45 倍)、
`listTitle` 16/23.2、两档比值 1.0625(选错档就是票面说的那 6%)、`listMeta` 12.5/18、
chip 用到的 `Radius.sm = 9` 与 `Spacing.sm/md/row = 8/12/14`。

**发现的票外问题**

- 原生 `TopicRow` 的标题字号是**写死的 token**,而 RN 那一档跟着设置「帖子列表字体大小」
  (12–26,默认 17)走。默认值下两版一致,用户调过滑块就会不一致 —— 属于设置项接线,不在本票,
  也不在票 42 的现象里。建议另开票。
