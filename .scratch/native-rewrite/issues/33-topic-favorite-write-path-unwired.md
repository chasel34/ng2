# 33 — P1:主题收藏根本没有写入口,收藏夹是个只能看不能装东西的空盒子

**Status:** open

**Severity:** P1(整条写路径缺失;data/api 两层都写完了、单测也绿,只差 UI 没接。
RN 版是有的,属于移植漏项)

## 现象

「收藏夹」「收藏夹管理」两屏都在,夹能新建 / 重命名 / 设默认 / 删除,列表能翻页,
**但全 app 没有任何一个地方能把一个主题放进收藏夹,也没有地方能把它拿出来**:

| 入口 | 设计上应该做的 | 实际 |
|---|---|---|
| 主题详情顶栏菜单「收藏本帖」 | 弹多选夹对话框 → `topic_favor_v2 add` | Toast「本版本未开放」 |
| 楼层菜单「收藏」 | 同上 | Toast「本版本未开放」 |
| 「已收藏的主题」列表里取消收藏 | `removeTopicFavorite`(`tidarray`) | **压根没有这个动作** |

于是 `/favorites` 对任何账号都只会是空的,`/favorites/folders` 里所有夹的计数永远是 0。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 进任意主题详情 → 楼层菜单 →「收藏」→ Toast **「本版本未开放」**(截图 `FV.png`)
   — 注意不是「登录后才能收藏」,是桩话术,说明它根本没实现,不是登录门。
2. 顶栏「更多」→「收藏本帖」同样。

## 证据:下面两层是**做完了的**,只有 UI 没接线

```
$ grep -rn "applyTopicFavorites" native/app/src/ | grep -v TopicFavoriteRepository.kt
native/app/src/test/kotlin/.../TopicFavoriteRepositoryTest.kt:243
native/app/src/test/kotlin/.../TopicFavoriteRepositoryTest.kt:257
```

**唯一的调用方是单测。** 生产代码里一次都没被调过。

- `core/api/TopicFavor.kt:103` `addTopicFavorite`、`:115` `removeTopicFavorite` —— 有。
- `data/favorites/TopicFavoriteRepository.kt:266-285` `applyTopicFavorites(uid, tid, added, removed)`
  —— 有,连「逐个串行发、不并发(ADR-0002)」「失败也要善后夹计数」都写好了,
  KDoc 第一句就是「**多选对话框点「完成」**:把勾选的差异逐个落到服务端」——
  那个多选对话框没被造出来。
- 本机反向索引 `core/local/topic-favor-index.ts` 的对应物、按 uid 分键的存储也都在。
- 设计 token 里甚至留好了这个对话框的字号:
  `ui/theme/Tokens.kt:221` 「对话框列表条目 …(设计稿「收藏到…」多选夹那档)」。

而两个入口被写成桩:

```
ui/topic/TopicScreen.kt:695   MenuItem("favor", "收藏本帖", onClick = pick(notAvailable))
ui/topic/TopicScreen.kt:723   MenuItem("favor", "收藏",     onClick = pick(notAvailable))
```

`ui/lists/FavoritesScreen.kt` 全屏只有「返回」「收藏夹管理」两个顶栏钮和一个换夹菜单,
没有任何删除动作。

## RN 版是实现了的

```
src/app/topic/[tid].tsx:709   { key: 'favor', label: '收藏本帖', onPress: pick(() => setFavorOpen(true)) }
src/store/topic-favor.ts:261  await addTopicFavorite(fetchNga, { tid, folderId })
```

所以这不是「v1 不做」的取舍,是移植时把 UI 那一层落下了。
(对照:真正的 v1 桩清单在 spec §一.2 —— 短消息、回帖、贴条、举报、分享、按版块筛选,
**收藏不在里面**;`research/inventory.md` §2 把 `topic_favor_v2` 列为读写边界内的功能。)

## 建议

补一个多选夹对话框(设计稿「收藏到…」),接 `applyTopicFavorites`;
`FavoritesScreen` 的条目补一个「取消收藏」。游客态按 `showLoginPrompt` 走,
别再用 `notAvailable`——那句话在这里是误导。

## 影响到的 checklist 条目(票 18)

- #6「楼层菜单「收藏」(`topic_favor_v2`,需登录)」
- #9「取消收藏(`removeTopicFavorite` 用 `tidarray`)」
- #9「列表渲染 + 点进主题」、#10「夹列表渲染」—— 就算所有者登录了,
  也造不出「夹里有主题」的状态来验,除非先在网页版收藏几个帖。
