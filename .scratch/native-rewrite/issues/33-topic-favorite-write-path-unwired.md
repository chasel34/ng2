# 33 — P1:主题收藏根本没有写入口,收藏夹是个只能看不能装东西的空盒子

**Status:** resolved

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

## Comments

### 2026-08-23 — 写路径接上了(票 33)

**完成摘要**

- 新增「收藏到…」多选夹对话框 `ui/favorites/FavoriteFolderDialog.kt`,直译 RN 侧
  `src/ui/favorite-folder-dialog.tsx`:复选行(`Ng2nIcon.CHECK_BOX` / `CHECK_BOX_OUTLINE_BLANK`,
  字号 `Typo.dialogListItem` —— 就是票里点名的 `Tokens.kt:221` 那档)、副行「N 个主题 · 默认夹」、
  夹多了列表自己滚(maxHeight 320)、「新建收藏夹…」整面板换成 `InputDialog`(不叠两层遮罩)、
  「勾选状态取自本机记录」那句脚注、取消/完成两钮。点「完成」调
  `TopicFavoriteRepository.applyTopicFavorites`。
- `ui/topic/TopicScreen.kt`:顶栏「收藏本帖」与楼层菜单「收藏」两条从 `notAvailable` 桩换成
  同一个 `openFavor`(`topic_favor_v2` 只收 tid,没有「收藏某一楼」,所以两处是同一件事、
  共用一个状态位)。对话框只在开着时挂载,进详情页不会白打一发 `list_folder`。
- 游客态改走 `showLoginPrompt(nav, "登录后才能收藏")` —— 与抽屉、版块收藏同一套
  snackbar +「去登录」,不再是误导性的「本版本未开放」。
- `ui/lists/FavoritesScreen.kt`:长按一行 → `ConfirmDialog`「取消收藏」→
  `TopicFavoriteRepository.unfavoriteTopic`(走 `removeTopicFavorite`,参数名 `tidarray`)。
  翻到底时有一句「长按一条可以把它从这个收藏夹里移出」——这个动作本来没有可发现性。
- 状态/差异逻辑抽成纯函数 `data/favorites/FavoriteFolderSelection.kt`
  (`favoriteFolderRows` / `toggleFolderSelection` / `selectCreatedFolder` /
  `canCreateFavoriteFolder` / `planFavoriteApply` / `favoriteFolderName` /
  `favoriteResultText` / `unfavoriteConfirmMessage`),19 条 JVM 单测
  (`FavoriteFolderSelectionTest`)+ 2 条仓库单测。

**关键决定**

- **新增 `TopicFavoriteRepository.unfavoriteTopic`,而不是让屏直接调 `applyTopicFavorites`。**
  `applyTopicFavorites` 的善后 `afterFolderChange` 会把受影响的夹连同已翻的页整桶丢掉;
  收藏夹屏还开着,进屏那个 `ensureTopics` 的 key(uid + folderId)没变、不会再跑,
  不补一发 `refreshTopics` 屏上就永远停在 loading。`try/finally` 补,失败也补
  (失败也可能是服务端已经删了、只是回包没读到)。两条单测钉住。
- **「完成提示语」在发请求之前就算好**:做完再算的话夹列表已经被重拉过,
  名字要是同时在别处改了就对不上。
- **打开对话框那一刻的归属冻结成 `initial`**,之后勾选只动 `selected`。跟着索引跑的话,
  写成功后仓库改了索引,第二次点「完成」会把刚做的事再算一遍差。
- **登录态按三态处理**(还没从磁盘读到 / 游客 / 某 uid),照 `FiltersScreen` 的先例:
  账号表是 DataStore,第一次发射晚于首帧,少了「还没读到」这一档,已登录用户在进屏
  头几帧点「收藏本帖」会吃一句冤枉的「登录后才能收藏」。
- **长按取消收藏要过一次确认**:收藏夹里误触的代价是「收藏没了、找不回来」,
  而列表行本来没有长按语义,用户不会预期长按会写点什么。
- 夹 id 口径:本机索引里是 Int(`TopicFavorIndex`),接口与 `FavoriteFolder` 上是 Long。
  转换只发生在 `FavoriteApplyPlan.addedIds` / `removedIds` 这一处,中间一律 Int。

**对 RN 版的有意偏离**

- RN 版 `src/app/favorites/index.tsx` **也没有**取消收藏入口(只有换夹菜单)。
  票里要求补,所以这是新增,不是移植 —— RN 侧无对应物可照。
- RN 版把差异计算、提示语拼装写在组件里;这里抽成纯函数并补单测(票面要求)。
- 对话框里的「新建收藏夹」失败时 RN 版留在输入框,这里同样留在输入框、只弹提示。

**票外改动(为接线所必需,已尽量小)**

- `ui/common/Dialogs.kt`:`DialogShell` / `DialogActions` 从 `private` 提到 `internal`
  (新对话框要复用同一套遮罩+面板动效与按钮排布,不然会长出第二套对话框皮肤);
  `DialogActions` 加 `enabled` 参数(写操作在飞时压暗确认钮,对应设计稿 `confirmBusy`
  的 opacity .6)。既有调用方默认 `enabled = true`,行为不变。
- `ui/common/RowTap.kt`:`rowClickable` 加可选 `onLongClick`。**不传就还是原来的
  `clickable`**,不换成 `combinedClickable` —— 后者为了等长按会把点击判定往后拖,
  列表上每一行都吃这一下不值当。横划取消点击那一层(票 23)不受影响,照旧在外面。
- `ui/board/TopicRow.kt`:加可选 `onLongClick`,只有收藏夹屏传。

**未完成 / 待所有者**

- **真机/真账号验收待所有者**:游客态没有收藏夹,`list_folder` / `add` / `del` 三个接口
  一个都打不通,本地只能验到「点『收藏本帖』弹出登录提示条」这一步。要验
  「弹夹列表 → 勾选 → 完成 → 夹计数变了 → 收藏夹屏里长按能移出」必须先登录 NGA 账号。
  票面的 checklist #6 / #9 同此。
- 按要求**没有用模拟器**,本轮只有编译 + JVM 单测两道闸。

**发现的票外问题**

1. `FavoriteFolderDialog` 与 `FavoritesScreen` 各自 `collectAsStateWithLifecycle` 账号表并
   自己算 uid 三态,`FiltersScreen` 又是第三份写法。这套「登录态三态」值得收进
   `ui/` 的一个 `rememberSignedInUid()`,现在是三处各写一遍。
2. `applyTopicFavorites` 串行写到一半失败时,已做成的那几个不回滚(RN 版同,是有意的),
   但**对话框关不掉、勾选状态也不刷新** —— 用户只看到一句错误提示,不知道前两个夹
   已经写进去了。要做对得让仓库把「做成了哪几个」回出来。RN 版有同样的洞。
3. 收藏夹屏的「换夹」用的是 `OverflowMenu`,夹超过十几个时菜单会顶到屏幕外(没有滚动)。
