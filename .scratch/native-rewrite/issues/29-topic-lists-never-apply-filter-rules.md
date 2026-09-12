# 29 — P1:屏蔽规则对主题列表完全不生效(只折楼层,不隐藏帖子)

**Status:** resolved

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

## Comments

### 2026-08-23 · 修复(票 29)

**照 RN 侧语义补的是「接线」,判定一行没改。**

- 新增 `data/filters/TopicFilter.kt` —— RN 侧 `useTopicFilter` 的等价物:
  `topicFilterSubject(topic)`(标题 / 作者 / 作者 uid 三样;**主题行没有正文**,
  `content` 一律缺省,与 RN 逐字一致)、`matchTopicFilterRules`、`filterTopics`。
  一条规则都没有时**返回原列表本身**(照抄 RN 的「不白造新引用」——屏上拿它当
  `remember` 的 key,新引用会让整屏彩色标题白重建一遍)。
- 新增 `ui/filters/FilterRulesState.kt` 的 `rememberFilterRules()` —— RN 侧
  `useFilterRules` 的等价物:订阅 `FilterRepository.allRules`(**本地在前、官方在后**),
  进屏调一次 `ensureBlockWords()`(幂等,5 分钟内不重问,同 RN 的 `staleTime`);
  官方那份没拉回来不阻塞列表,回来后流再发一次,那时命中的行才藏起来。

**接上的列表屏**(全部在「行构造处」`buildTopicRows` 之前过滤):

| 屏 | 文件 | RN 侧对应 |
| --- | --- | --- |
| 版块列表 | `ui/board/BoardScreen.kt` | `src/app/board/[id].tsx:117` |
| 24h 热帖 | `ui/board/SimpleListScreens.kt` | `src/app/board/hot.tsx:39` |
| 精华区 | `ui/board/SimpleListScreens.kt` | `src/app/board/recommend.tsx:49` |
| 搜索结果 | `ui/lists/SearchScreen.kt` | RN 版没接(见下) |
| 收藏夹 | `ui/lists/FavoritesScreen.kt` | RN 版没接(见下) |

**空态照 RN 分了两句话**:「拉回来了但被规则挡光了」与「本来就没有」不能共用一句 ——
版块「这一页的主题都被屏蔽规则挡住了」、热帖「榜单上的主题都被屏蔽规则挡住了」、
精华区「这一页的主题都被屏蔽规则挡住了」,图标换 `filter_alt`。版块屏的这一支排在
`listStructure == false`(被限流)那一支**前面**,与 RN 的 `!allFiltered && …` 同序。
搜索是「找到的主题都被屏蔽规则挡住了」、收藏夹是「「夹名」里的主题都被屏蔽规则挡住了」,
这两句 RN 版没有,是本票新写的。

**对 RN 版的有意偏离**

1. **搜索结果与收藏夹也过规则**(票面「顺带」那节记的是 RN 同样没接、不算回归)。
   接上的理由:屏蔽规则页承诺的是「命中的主题在列表里隐藏」,没说除了搜索和收藏;
   两屏用的都是同一个 `buildTopicRows`,接线成本是一行。
2. **「我的帖子 / 我的回复」(`UserPostsScreen`)没接**:那一屏是「点进某个人去看他发过什么」,
   人已经明确要看这个人了,再按用户规则把他全屏藏空是自相矛盾。RN 版同样没接。
3. 主题详情的楼层流仍走 `TopicViewModel` 自己那条路(`settings.localFilterRules` +
   自己 `fetchBlockWords`),**没有改成 `FilterRepository`** —— 那是票外重构,
   两条路读的是同一份 DataStore 与同一个接口,判定函数也是同一个。

**已知边界(与 RN 版同款,没修)**:整页被规则挡光时列表是空的,无限滚动不会自己
往下翻页(`shouldLoadMore` 看的是可见项),要用户下拉刷新或手动再进。RN 版行为一致,
真要改是「过滤后不足一屏就自动续拉」,属于新需求。

**单测**:`app/src/test/kotlin/com/chasel/ng2n/data/filters/TopicFilterTest.kt`(14 条),
逐条对着 RN 的 `useTopicFilter` + `matchFilterRules` 写:关键词(大小写不敏感 / 正则 /
写错的正则永不命中也不抛)、用户(名字 / uid 优先 / 匿名无 uid)、分类标签、
主题行没有正文不该被「只有正文才有的词」误伤、官方屏蔽表对列表同样生效、
官方表没拉回来时只按本地规则过、命中报最前面那条(本地优先)、空规则表返回同一个引用。

**待所有者**:票面的复现用的是登录态才有的官方屏蔽表那一半 —— 官方规则参与列表判定的
路径只有 JVM 单测覆盖,真机对拍(设一条云端屏蔽词,看版块列表少一行)需要 NGA 账号。
本地规则那一半可以游客态直接验:设置 → 屏蔽规则 → 关键词 `^.*$` + 正则 → 进任意版块,
应当显示「这一页的主题都被屏蔽规则挡住了」。

**主控验收(2026-08-23)**:模拟器复验通过,本地关键词规则 `Nexon` 保存后 fid=459 列表命中行由 1 → 0;规则已删。
