# 29 — P1:屏蔽规则对主题列表完全不生效(只折楼层,不隐藏帖子)

**Status:** open

**Severity:** P1(功能整块缺失,而且屏幕上白纸黑字承诺了这件事;RN 版三个列表屏都做了)

## 现象

屏蔽规则页顶部的说明写着:

> 仅存在本机,卸载即丢失;**命中的主题在列表里隐藏**,命中的楼层折叠成一行。

后半句是对的,**前半句一条也没实现**:版块 / 24h 热帖 / 精华区列表里,
命中规则的主题照常列出来。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 设置 → 屏蔽规则 → 新增规则 → 类型「关键词」→ 填 `^.*$` → 勾「按正则匹配」→ 保存
   (这条规则匹配任何标题,所以「一条都不该剩」是个不会看错的判据)

   落盘确认:
   ```
   {"id":"local:keyword:^.*$","kind":"keyword","origin":"local","value":"^.*$","regex":true,"createdAt":1787468848}
   ```

2. 进任意主题详情 —— **楼层全折了**,每条都是「已屏蔽含「^.\*$」的楼层 / 展开」,
   点「展开」能还原。**这一半是好的。**

3. 返回,进版块「守望先锋」 —— **列表纹丝不动**:
   `[新闻] 《守望先锋》精彩聚焦:黑爪之治开始`、`[杂谈] 玩快速遇到的这种号目的是啥` … 全在。

**期望**:命中的主题从列表里消失(或按 RN 版的口径折叠)。
**实际**:列表完全不看规则。

## 根因

`matchFilterRules` 在 native 侧**只有一个调用点**:

```
$ grep -rn "matchFilterRules" native/app/src/main/kotlin/ | grep -v core/local/Filters.kt
ui/topic/TopicViewModel.kt:32   (import)
ui/topic/TopicViewModel.kt:438  (唯一一次真调用 —— 楼层流)
core/api/BlockWord.kt:128       (注释)
data/filters/FilterRepository.kt:42 (注释)
data/settings/FilterRules.kt:9  (注释)
```

`ui/board/`(`BoardScreen.kt` / `TopicRow.kt` / `SimpleListScreens.kt`)里没有任何一处碰规则表。

RN 版是有的,三个列表屏各接一个 hook:

```
src/app/board/[id].tsx:117       const filterTopics = useTopicFilter()
src/app/board/hot.tsx:39         const filterTopics = useTopicFilter()
src/app/board/recommend.tsx:49   const filterTopics = useTopicFilter()
```

`src/core/local/filters.ts:5` 的注释也点名了这个共用关系:
「主题列表与楼层流共用一次 `matchFilterRules`」。移植时楼层那半搬了,列表那半漏了。

判定函数本身是好的(`core/local/Filters.kt:290` `matchFilterRules`,
`FilterSubject` 已经带 `title` / `content` / `author` / 分类四个字段),
缺的只是列表侧的接线 + 一个 `useTopicFilter` 等价物(可以放 `data/filters/`,
让三个列表屏和将来的搜索结果共用)。

## 顺带

`matchFilterRules` 也没接到 `/search` 的主题结果上;RN 版同样没接,
所以这一条不算回归,只是记一笔口径。
