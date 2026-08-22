# 17 — 其余屏幕收齐(M3,24 屏封口)

**What to build:** 搜索(主题/版块/用户三 tab,主题可限版块可搜正文,每 tab 独立历史 20 条);收藏(多夹、20 夹上限、设默认/重命名/删除、tid→夹反向索引本地维护);历史(200 条+进度);缓存管理(逐帖查看/删除);通知(前台 60s 轮询、按类型分组、本地已读、清空);屏蔽规则三 tab(本地规则:用户/关键词(正则,**修 P3-05** 加资源上限)/分类;官方屏蔽词云端全表覆盖写);用户资料(本人可改签名)+ 我的主题/我的回复;设置树全部(通用/阅读/通知/内容与存储/高级 五分组,含域名 5 选 1、夜间/跟随系统、主题风格 ink/plain/近黑、左手模式、纯色背景、自动下一页、仅 Wi-Fi 图片、图片策略、签名档、手势返回开关、常亮、字号头像滑块屏、实验室(web 兜底四档 + WP UA 开关 + 组合表分享 + 诊断日志导出)、恢复默认);关于(抽屉入口);`/web` 网页兜底 WebView;toast 桩(`showNotAvailable` 等价物,桩清单照 research/inventory.md §2);深链接收(`ng2n://` scheme;NGA 域名 intent-filter **并行期不注册**;冷启深链用 TaskStackBuilder 垫首页防死返回)。

**Blocked by:** 07, 14, 15

**Status:** resolved

- [ ] 24 屏 checklist 其余屏全部通过
- [ ] 桩项逐个点击确认 toast 文案与 RN 版一致
- [ ] 设置每一项改动后行为立即生效(域名切换下个请求生效等语义照抄)

## Comments

### 17b — 屏蔽规则 / 用户资料 / 我的主题·我的回复 / toast 桩

**完成摘要**(commit `9c7cf01` `c30152d` `33fb50d` `1eb56ea`,全部编译 + 全仓单测绿)

| 交付 | 落在哪 |
|---|---|
| toast 桩 | `ui/common/Stubs.kt`:`showNotAvailable()` + inventory §2 第 50 行桩清单全表(逐条标了入口在哪、归哪张票接) |
| 屏蔽规则三 tab | `ui/filters/FiltersScreen.kt` · `FilterRuleDialog.kt` · `data/filters/FilterRepository.kt` |
| 用户资料(本人可改签名) | `ui/user/UserProfileScreen.kt` · `data/user/UserProfileRepository.kt` |
| 我的主题 / 我的回复 | `ui/user/UserPostsScreen.kt`(含 `ReplyRow`) · `data/user/UserPostsRepository.kt` |
| 导航 | `ui/filters/FiltersEntries.kt` 注册 FiltersKey / UserKey / UserPostsKey;`Ng2nApp.kt` 加一行调用;`HomeEntries.kt` 删掉对应占位 |

新增单测 15 条:`data/filters/FilterMappingTest`(8)、`ui/filters/FiltersScreenTextTest`(4)、
`ui/user/UserScreenTextTest`(3)。

**P3-05(用户正则资源上限)**

判定期那半**票 10 已经做完了**(`core/local/Filters.kt`:pattern ≤256、嵌套量词粗检、
步数预算 200k、输入截断 20k、有界 LRU 编译缓存,`FiltersGoldenTest` 里 5 条单测锁着)。
本票补的是**写入期**:对话框读的是 `core/local` 那份带上限的 `validateFilterRule`,
**不是** `data/settings/FilterRules.kt` 里那份同名却没有上限的。RN 版只有「能不能编译」
一档,`(a+)+$` 存得进去,然后在长正文上把 UI 线程跑到天荒地老。
`FilterMappingTest` 里另加一条:**病态规则已经躺在存档里**(老版本存的 / 手改过存档)时,
判定层也不卡死不抛,按不命中收场。

**对 RN 版的有意偏离**

1. `showNotAvailable` 的载体从 `ToastAndroid` 换成进程级 `Snackbars`(文案一字未改)。
   理由:桩入口散在叶子组件里,为一句提示逐层传 `Context` 不值当;Android 12 起后台
   `Toast` 会被系统改样式。理由写在 `Stubs.kt` 的 KDoc 里。
2. 云端屏蔽表的 uid **由仓库自己现读**,不从屏幕传。屏幕那份是
   `collectAsStateWithLifecycle(initialValue = null)`,进屏第一帧必然 null,传进去会被
   当成「切到游客了」把已拉到的表清掉。屏幕侧登录态因此是**三态**(未知 / 游客 / uid),
   未知时官方两 tab 先转圈 —— RN 版是同步读 MMKV,没有这一帧。
3. 「撤销」在 RN 里是 `showSnackbar(msg, {label, onPress})`;这里同形状,
   但撤销那次整表写回失败时**再弹一次**失败提示(RN 版那条 `.catch(cloudFailed)` 一样)。
4. 资料卡副说明在 RN 里靠 `marginTop:-spacing.xs` 吃掉标题下距;Compose 没有负内距,
   等效改成标题少给一点。视觉上差 4dp。

**关键决定(票里留白的)**

- 两个仓库都做成进程级 `@Singleton` + `StateFlow` + 按 key 分桶 LRU(与票 16 的
  `TopicListRepository` 同一套),对应 TanStack 的 `staleTime` / `gcTime`:
  资料 5min / 8 桶,某人的主题回复 4 桶。
- 「我的回复」翻页判据照抄 `hasMoreUserPosts`(这一页一条都没有),**不看 `totalPages`**;
  去重按 `reply.pid`,**不按 tid**。两条的出处在 `core/api/UserTopics.kt` 的 KDoc。
- 官方用户屏蔽 tab 只读 + 解除,不给 FAB(加人要 uid,输入框拿不到)—— RN 版同一取舍。
- 图标沿用票 11 / 12 立的「Canvas 画」做法,往 `ui/icons/AppIcons.kt` 补了 5 枚
  (TEXT_FIELDS / BLOCK / CHECK_BOX / CHECK_BOX_OUTLINE_BLANK / EDIT)。
- `ui/common/Dialogs.kt` 的 `InputDialog` 加了 `multiline` 档(签名可换行),默认值不变。

**未完成 / 待主控**

- **票 13 已合并(`4439b1c`),两处要主控在合并时处理**:
  1. `ui/topic/TopicEntries.kt` 里的 `UserKey` 占位(`UserPlaceholderScreen`)**待删** ——
     真屏在我这边的 `ui/filters/FiltersEntries.kt`(`filtersAndUserEntries`)。
     我这份基线里 `HomeEntries.kt` 删的是票 16 留的那三行占位,与票 13 的改动可能撞行。
  2. `ui/topic/FilterBridge.kt`(`toMatchRule`/`toStoredRule`)与我的
     `data/filters/FilterRepository.kt` 里的 `toCore`/`toStored` **是同一件事、语义一致**
     (含 origin 认不出退回 LOCAL 这一条)。合并后建议**删掉 FilterBridge.kt**,
     两个调用点改指 `data.filters.toCore` / `toStored`(我这边是 public)。
     我的基线里没有 topic 包,改不动,按主控指示保持现状。
     顺带:票 13 若在自己屏里另读了一份本地规则表,应改用
     `FilterRepository.allRules`(本地在前 + 官方在后,已按 RN 的 `useFilterRules` 拼好),
     否则楼层折叠看不到官方屏蔽词。
- **屏蔽规则屏的正经入口不在本票**:设置树最后一行归 17c、楼层菜单「屏蔽此人」归票 13。
  在那两处到位前,`Ng2nApp.kt` 的开发者菜单里临时加了一条「屏蔽规则(票 17b)」
  (tag `ng2n-filters-entry`),**17c 合并后请删掉**。
- **没上模拟器**(票 13 在用,按 17-split 的约定没 install/launch)。全部结论来自
  `:app:assembleDebug` + `:app:testDebugUnitTest`;真机/模拟器手验、改签名的
  「保存后回读一致」都要登录账号,**待所有者**。
- 官方关键词是否该按正则跑:`officialFilterRules` 一律 `regex = false`(票 07 的决定),
  与网页版对拍前不动。

**发现的票外问题**

1. `data/settings/FilterRules.kt`(票 14)与 `core/local/Filters.kt`(票 10)有**两份**
   `FilterRule`/`FilterRuleKind`/`FilterRuleOrigin`/`FilterRuleInput`/`validateFilterRule`/
   `createFilterRule`/`upsertFilterRule`/`removeFilterRule`。两份的 `validateFilterRule`
   **行为不同**(存储那份没有 P3-05 的上限,错误文案还用了半角冒号,而 core 那份是全角),
   谁调到哪一份全靠 import —— 这正是「P3-05 修了一半」的温床。
   建议排一张票合一:存储层只留 `@Serializable` 的数据形状 + `sanitize`,
   校验/建/增删一律指 core。`SettingsTest.kt` 里锁存储那份文案的两条断言要跟着改。
2. `ui/common/Snackbars` 的自动消失是 4s 固定;RN 侧 snackbar 与 toast 是两档时长。
   桩提示走 snackbar 后比 RN 的 `Toast.LENGTH_SHORT`(2s)长一倍。不影响功能,记一笔。

**主控验收 17b(2026-08-22)**:合并进 android-native(`4546d50`);`TopicEntries.kt` 的 UserKey 占位与 `UserPlaceholderScreen` 已删,真屏走 `filtersAndUserEntries`;800 单测/0 失败。FilterBridge 与 data/filters 重复、两份 `validateFilterRule` 行为不同 → 记为票 20 候选(票 18 走查时若楼层折叠看不到官方屏蔽词即升级为缺陷票)。DevMenu「屏蔽规则(票 17b)」入口在 17c 合并后删。
### 17c(设置树 / 关于 / web 兜底 / 深链)

**完成摘要**

- `ui/settings/`:通用件(`SettingsUi.kt`:外壳 / 分组标题 / 开关行 / 跳转行 / 自绘开关 /
  单选对话框 / 滑杆)+ 三屏(`SettingsScreen` 五分组 21 行、`FontSizeScreen` 预览卡 + 五根滑杆、
  `LabScreen` 兜底四档 + WP UA + 组合表分享 + 日志导出)。
- `ui/about/AboutScreen.kt`:抽屉「关于」进,五行照设计稿形状,版本号从 `PackageManager` 现读。
- `ui/web/WebFallbackScreen.kt`:站内 WebView + 底部回切钮 + 网页菜单五条。
- 深链:`ui/nav/DeepLinkKeys.kt`(链接 → NavKey)、`ui/nav/DeepLinkInbox.kt`(Activity 投递 /
  宿主取件),`MainActivity` 接 `onCreate` + `onNewIntent`。manifest **一个字没改**:
  只有 `ng2n://` 那条 intent-filter,NGA 域名不注册。
- 命令:`./gradlew :app:assembleDebug` BUILD SUCCESSFUL;`:app:testDebugUnitTest` 750 → 762
  条全绿(新增 DeepLinkKeysTest 7 / SettingsEffectiveTest 6 / DiagnosticExportTest 2)。

**关键决定**

1. **「垫首页防死返回」不用 `TaskStackBuilder`**。单 Activity + Nav3 下它是退化的(它铺的是
   Activity 栈,这里只有一个 Activity,真调它只会把自己重启一遍)。同一个结果由
   `rememberNavBackStack(Home)` 免费得到:back stack 恒以首页开局,深链目标是 `add` 上去的
   第二格 —— 从深链进主题按返回必回首页。理由写在 `DeepLinkInbox.kt` 的类注释里。
2. **DevMenu 留在「关于」长按**,不并进实验室(`Ng2nApp.kt` 里票 16 留的 TODO 就地结掉):
   实验室收的是排查线上问题的四条,DevMenu 是 demo 屏的手验入口,混在一起会让一个用户会
   点开的页面里出现两类东西。
3. 设置二级页的两个键(`FontSizeKey` / `LabKey`)**跟着屏幕住**在 `ui/settings/`,不进
   `ui/nav/Keys.kt` —— 那张表是为「抽屉十几个入口指向谁」定的,这两个没有外部引用者
   (与 `ImageViewerKey` / `BBCodeDemoKey` 同一个做法)。
4. 设置行不做 RN 那套 `ProgressiveChildren` 分帧挂载:Compose 的 `LazyColumn` 只让视口内的行
   进 composition,那个补丁的病因在原生这边不存在。

**修的缺陷**

- **P0(本票发现并修)**:`core/local/DeepLink.kt` 的自有 scheme 表原样抄了 RN 的
  `ng2` / `ng2-dev`,而 manifest 注册的是 `ng2n://` —— 系统深链**一条都进不来**
  (解析器判 `unsupported-scheme`)。改成 `APP_SCHEME = "ng2n"`,RN 版那两个作
  `RN_SCHEME` / `RN_DEV_SCHEME` 一并认下(并装期旧链接照样打得开)。
  这是与 RN 版的**有意偏离**:那边的 scheme 就是 `ng2`。
- 映射去重:票 16 在 `ui/home/HomeScreen.kt` 里那份 `NgaLink.toNavKey()` 搬进
  `ui/nav/DeepLinkKeys.kt`,「由 URL 读取」与系统深链现在共用一份(TS 侧那句
  「两处各写一份参数映射迟早走偏」的原意)。

**对 RN 版的有意偏离**

- 网页页菜单的「复制网址」**做成真的**(RN 那边是 `showNotAvailable`,原因写在代码里:
  没装 expo-clipboard)。Android 的 `ClipboardManager` 是系统件,没有那个依赖问题;
  `research/inventory.md` §2 的桩清单里也没有这一条。「网页字号」仍是桩(清单里有)。
- 网页页加了「WebView 内有历史先后退」的返回键行为(Android 惯例,RN 版没做)。
- 关于屏第 2 行 RN 写的是「系统设置 · 开『打开支持的链接』后 NGA 链接才会跳进本 app」;
  并行期本 app 不注册 NGA 域名,所以副标题改成「打开本应用的系统设置(权限 / 默认打开方式)」,
  行的位置与图标形状不动。第 4 行的开源许可表换成 `libs.versions.toml` 里的真依赖。
- 图标:关于屏五行与滑杆 ±、单选圈**没有往 `ui/icons/AppIcons.kt` 加档**(并行期三份子代理
  同时开工,共用枚举是最容易冲突的一处)。五行复用了已有档位,单选圈/减号就地两行 Canvas。
  票 17 收口后若要统一图标体系,把这几处换掉即可。

**「每一项立即生效」的现状(逐项)**

已接通并有回归:域名 / Web 反解档位 / read.php 的 UA(反封锁链**每请求现读**,
`SettingsNetworkSource`);夜间模式 + 跟随系统 + 主题风格 ink/plain(`Ng2nAppTheme`,
不重启 Activity);字号三档(`LocalTextScale`);仅 Wi-Fi 图片 + 图片加载策略
(新 `data/settings/StoredImageSettingsSource` 换掉票 12 的内存占位,票 12 那个
`data/InMemoryImageSettingsSource.kt` 已删);被喷提示(`NotificationPoller` 每轮现读,
关掉即停轮询且角标归零);清阅读进度 / 清缓存 / 恢复默认。

**值已下发、消费点在别人票里**(本票只把它们经 `LocalAppSettings` 放到 composition 上,
消费方一行 `LocalAppSettings.current.xxx` 即可):

| 设置项 | 消费点 | 归属 |
|---|---|---|
| `leftHanded`(FAB / 菜单靠左) | 版块页 FAB、主题页菜单 | 票 16 / 13 |
| `solidBackground` | 主题列表与详情页的根底色 | 票 16 / 13 |
| `autoLoadNextPage` | 楼层流触底 | 票 13 |
| `showSignature` | 楼层签名档 | 票 13 |
| `keepScreenOn` | 主题详情屏根上调一次 `KeepScreenOn()`(本票已提供该 composable) | 票 13 |
| `noticeSound` | 无声音钩子可接,RN 版同样只存值 | —— |
| `gestureBack` | **原生这边没有消费点**:左边缘返回是系统手势,app 只能声明排除矩形,关不掉。行照留(照抄不简化),但它当前不改变行为 —— 需要所有者裁决是删行还是改成「预测性返回动画开关」。 | 待所有者 |

**碰了别人的文件**(都按最小改动,列全):

- `ui/Ng2nApp.kt`:+1 行 `settingsEntries(…)`、+ 深链取件的 5 行、结掉票 16 留的 DevMenu TODO;
- `ui/home/HomeEntries.kt`:删掉 SettingsKey / AboutKey / WebKey 三行占位;
- `ui/home/HomeScreen.kt`:删掉重复的 `toNavKey()`,改成 import(见上「映射去重」);
- `ui/AppDeps.kt`:+4 个仓库(history / topicCache / diagnostics / ngaClient);
- `MainActivity.kt`:`Ng2nTheme` → `Ng2nAppTheme`,+ intent 收件;
- `di/ImageModule.kt`:`ImageSettingsSource` 的绑定换成 DataStore 实现(票 12 留的 TODO);
- `core/local/ImagePolicy.kt`:上面那句 TODO 注释同步;
- `data/notifications/NotificationPoller.kt`:注入 `SettingsStore`,轮询前判 `sprayNotice`;
- `core/local/DeepLink.kt`:scheme 表(见「修的缺陷」)。

**未完成 / 待所有者**

- 没上模拟器(票 13 在用,主控未给时间片):设置树 / 关于 / 网页页 / 深链**都只过了编译与单测**,
  界面与 `adb shell am start -a android.intent.action.VIEW -d 'ng2n://read.php?tid=…'` 的
  实跳未验。
- `gestureBack` 见上表,需要裁决。
- 网页页的登录态跟 RN 版是同一个已知差异:WebView 用的是 `CookieManager` 里**最后一次登录**
  那个号的 cookie,不一定是 app 里当前切到的那个(app 自己的请求不受影响,走票 06 的自管
  CookieJar)。

**发现的票外问题**

- `ui/theme/Tokens.kt` 的 `Typo` 还缺设计稿的 `sliderLabel` / `sliderValue` / `webTitle` /
  `cardMeta` 等几档;本票就地内联了取值(并行期不动共用表),票 17 收口后建议统一补进 `Typo`。
- `ui/common/Dialogs.kt` 的 `DialogShell` 是 private,单选对话框只能在 `ui/settings/` 里复刻
  一份同款(动效常量取自同一个 `Motion`,不会各说各话)。收口后建议把它提成 internal 共用。

**主控验收 17c(2026-08-22)**:合并进 android-native;AppDeps/Ng2nApp 两票追加项都保留;HomeEntries 占位只剩 17a 六个键;DevMenu「屏蔽规则(票 17b)」临时入口已删。815 单测/0 失败。`gestureBack` 语义待所有者裁决(原生左边缘返回是系统手势);深链 `am start -d ng2n://…` 实跳与设置界面归票 18 走查。
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

**主控验收 17a + 票 17 整体(2026-08-22)**:17a 合并进 android-native;AppIcons 枚举/when/helper 三处按三方重组去重(EDIT/CHECK_BOX/CHECK_BOX_OUTLINE_BLANK 取 17b 版);`NotificationPoller` 取 17a 版(已含 17c 的 sprayNotice 门)。HomeEntries 占位全部清空。assembleDebug 通过,单测 849/0/4 跳过。三份验收项:24 屏代码齐、桩文案一致(单测锁)、设置即时生效(单测锁)——**界面走查与桩点击归票 18**,写操作类归所有者登录。票外汇总 → 票 18 时统一建缺陷票:轮询未接前后台;`NotificationLike` 下沉 core;`buildTopicRows` 未吃字号设置;两份 `validateFilterRule`;FilterBridge 重复;`gestureBack` 语义待裁。
