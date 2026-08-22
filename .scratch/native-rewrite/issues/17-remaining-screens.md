# 17 — 其余屏幕收齐(M3,24 屏封口)

**What to build:** 搜索(主题/版块/用户三 tab,主题可限版块可搜正文,每 tab 独立历史 20 条);收藏(多夹、20 夹上限、设默认/重命名/删除、tid→夹反向索引本地维护);历史(200 条+进度);缓存管理(逐帖查看/删除);通知(前台 60s 轮询、按类型分组、本地已读、清空);屏蔽规则三 tab(本地规则:用户/关键词(正则,**修 P3-05** 加资源上限)/分类;官方屏蔽词云端全表覆盖写);用户资料(本人可改签名)+ 我的主题/我的回复;设置树全部(通用/阅读/通知/内容与存储/高级 五分组,含域名 5 选 1、夜间/跟随系统、主题风格 ink/plain/近黑、左手模式、纯色背景、自动下一页、仅 Wi-Fi 图片、图片策略、签名档、手势返回开关、常亮、字号头像滑块屏、实验室(web 兜底四档 + WP UA 开关 + 组合表分享 + 诊断日志导出)、恢复默认);关于(抽屉入口);`/web` 网页兜底 WebView;toast 桩(`showNotAvailable` 等价物,桩清单照 research/inventory.md §2);深链接收(`ng2n://` scheme;NGA 域名 intent-filter **并行期不注册**;冷启深链用 TaskStackBuilder 垫首页防死返回)。

**Blocked by:** 07, 14, 15

**Status:** open

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
