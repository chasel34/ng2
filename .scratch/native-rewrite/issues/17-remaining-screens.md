# 17 — 其余屏幕收齐(M3,24 屏封口)

**What to build:** 搜索(主题/版块/用户三 tab,主题可限版块可搜正文,每 tab 独立历史 20 条);收藏(多夹、20 夹上限、设默认/重命名/删除、tid→夹反向索引本地维护);历史(200 条+进度);缓存管理(逐帖查看/删除);通知(前台 60s 轮询、按类型分组、本地已读、清空);屏蔽规则三 tab(本地规则:用户/关键词(正则,**修 P3-05** 加资源上限)/分类;官方屏蔽词云端全表覆盖写);用户资料(本人可改签名)+ 我的主题/我的回复;设置树全部(通用/阅读/通知/内容与存储/高级 五分组,含域名 5 选 1、夜间/跟随系统、主题风格 ink/plain/近黑、左手模式、纯色背景、自动下一页、仅 Wi-Fi 图片、图片策略、签名档、手势返回开关、常亮、字号头像滑块屏、实验室(web 兜底四档 + WP UA 开关 + 组合表分享 + 诊断日志导出)、恢复默认);关于(抽屉入口);`/web` 网页兜底 WebView;toast 桩(`showNotAvailable` 等价物,桩清单照 research/inventory.md §2);深链接收(`ng2n://` scheme;NGA 域名 intent-filter **并行期不注册**;冷启深链用 TaskStackBuilder 垫首页防死返回)。

**Blocked by:** 07, 14, 15

**Status:** open

- [ ] 24 屏 checklist 其余屏全部通过
- [ ] 桩项逐个点击确认 toast 文案与 RN 版一致
- [ ] 设置每一项改动后行为立即生效(域名切换下个请求生效等语义照抄)

## Comments

### 17a — 搜索 / 收藏 / 历史 / 缓存管理 / 通知(2026-08-22)

**完成摘要**（6 个键、5 组屏,`ui/lists/` + `data/search/` + `data/favorites/`）

- 搜索(`SearchKey`):三 tab。主题(限当前版块 / 包括正文,`thread.php` 无限滚动、结果统计条)、
  版块(`forum.php` 一次全量、关键词 GBK、行内星标收藏)、用户(纯数字按 uid、否则按用户名走 ucp,
  5 分钟保鲜)。每 tab 独立历史 20 条,落 `SettingsStore` 的 `search/history`(与 RN 同键同构),
  条目带范围标注、可单条删除、可清空、点击原样还原范围重搜。
- 收藏(`FavoritesKey` / `FavoriteFoldersKey`):一次一夹 + 副标题条换夹菜单 + 下拉刷新(砍回第一页)
  + 无限滚动;夹管理新建/重命名/设默认/删除,20 夹上限,写完必重拉。
- 历史(`HistoryKey`):200 条 + 「读到 N 楼 / 读完」,清空走确认对话框。纯本地零请求。
- 缓存管理(`CachesKey`):按主题聚合、总占用副标题、单条删除 + 清空;点行落在已缓存的第一页。
- 通知(`NotificationsKey`):按类型分组、进页全部标已读、清空(服务端 del + 本地已读),
  抽屉未读徽标接线沿用票 16 已有的 `deps.notifications.unread`(通知屏自己也 start/stop 轮询)。

**修的已知缺陷**

- **P1-02(收藏缓存按 uid 隔离)**:`TopicFavoriteRepository` 的两份缓存都带 uid ——
  夹列表按 uid 分桶、夹内主题的桶键是 `TopicsKey(uid, folderId)`。RN 版
  `store/topic-favor.ts:40-43` 的 `['favorite-folders']` / `['favorite-topics', folderId]`
  **都不带 uid**:切号后进收藏夹页看到的还是上一个账号的夹与夹内主题,夹 id 撞车时更是拿旧账号
  的 id 去打新账号的接口。游客态一律不发请求(接口对游客回「你必须先登录论坛」)。
  单测 `TopicFavoriteRepositoryTest`:「两个账号的夹列表互不串」「夹 id 撞车时夹内主题也不串」。
- **通知条目切号不清(顺手修)**:票 14 的 `NotificationPoller` 每轮现读 uid 却从不清条目,
  切号之后上一个账号的通知留在列表里、未读数跟着串(与 P1-02 同一类)。这一版补了 `activeUid`
  重置,并补齐 RN 侧那半:`refreshing` 状态、`clearAll()`、连续拉空退避(60/120/180/300s,
  靠跳格实现,定时器仍是 60s 一格)、「被喷提示」关掉就不轮询且角标恒 0。

**关键决定 / 对 RN 版的有意偏离**

1. **游客禁用「搜板块」**(票 17a 明确要求):`forum.php` 对游客回「你必须先登录论坛」,
   RN 版没挡,游客点进去只看得到一句看不懂的服务端错误。这一版 tab 压成 meta 色,点了走
   `showLoginPrompt`(与版块收藏、收藏夹同一套「去登录」话术)。**这是相对 RN 版的行为变化**。
2. **搜索 / 收藏做成进程级仓库而不是 ViewModel**:与票 16 的 `TopicListRepository` 同构,
   理由同 `ui/AppDeps.kt` 的注释(RN 侧这些数据住全局 query cache,从结果进主题再返回,
   列表与已翻的页都还在)。桶数上限 8,满了丢最老的(对应 TanStack 的 gcTime)。
3. **搜索的「重试」会 `forgetSuccessfulCombo("thread.php")`**:与版块列表同一条理由
   (2026-08-13「版块全空」),两条路本来就共用同一条 comboCache 记录。
4. **历史/缓存的清空先问一句**而不是「立即清空 + 可撤销 toast」:照抄 RN 版当初退这一步的决定
   (清完没有可撤销的对象)。
5. 用户搜索结果的头像先用「纯色圆底 + 首字」占位,**没接远程头像** —— 那要走票 12 的图片管线,
   而资料页归 17b,统一在那儿接更省事。散列口径(`avatarColorFor`)已与 RN 版对齐并单测钉住。

**改到的公共件**(改动最小,主控合并时留意)

- `ui/theme/Tokens.kt`:追加 7 档字号(`searchSection` / `dialogListItem` / `cardMeta` /
  `folderBadge` / `avatarInitial` / `notifyInitial` / `notifyMeta`)+ `AvatarColors` 与
  `avatarColorFor()`。文件里原本就写着「完整 token 表是票 17 的活」。
- `ui/icons/AppIcons.kt`:`Ng2nIcon` 追加 14 枚(history / delete / delete_sweep / edit /
  folder / create_new_folder / expand_more / 单选与勾选各两枚 / download / @ / 贴条 / 点赞),
  枚举与 `when` 分支都追加在末尾。17b/17c 若也要加图标,冲突点就在这两处。
- `ui/AppDeps.kt`:追加 `search` / `topicFavorites` / `history` / `topicCache` 四个口子。
- `data/notifications/NotificationPoller.kt`:见上文「顺手修」。构造参数多了 `SettingsStore`。
- `ui/Ng2nApp.kt` 加一行 `listEntries(nav = nav)`;`ui/home/HomeEntries.kt` 只删了自己那 6 行占位。

**未完成 / 待所有者**

- **模拟器未验**:票 13 在用 `emulator-5554`,按 17-split 的约定没有 install/launch。
  编译(`:app:assembleDebug`)与全仓单测(`:app:testDebugUnitTest`,770 用例 0 失败)是通过的,
  屏上手验留给票 18。
- **需要登录账号**才验得了的:收藏夹 CRUD、通知拉取与清空、搜板块。游客态那几条路已可自测。
- 收藏「多选夹」对话框(`applyTopicFavorites`)仓库侧已就位并有单测,**UI 入口在主题详情页(票 13)**,
  本票没有可挂的地方。

**发现的票外问题**

1. `NotificationPoller` 目前只在屏幕的 `DisposableEffect` 里 start/stop,**没有接 app 前后台**
   (RN 侧是 `AppState` 监听)。退到后台时若首页仍在 composition 上,轮询会继续跑。
   要接的话得在 `MainActivity` 的 lifecycle 上挂一层,属于票 01/02 的地盘。
2. `NotificationLike` 住 `data/notifications` 而 `NgaNotification` 住 `core/api`,导致
   `NotificationPoller` 里要包一层 `Wrapped`(票 07 Comments 已记过)。
3. `ui/board/TopicRow.kt` 的 `buildTopicRows` 没吃「帖子列表字体大小」设置(RN 侧 `useListFontSize`),
   本票沿用现状;设置树归 17c,那时一起接。
