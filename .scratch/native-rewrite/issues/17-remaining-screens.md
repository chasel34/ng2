# 17 — 其余屏幕收齐(M3,24 屏封口)

**What to build:** 搜索(主题/版块/用户三 tab,主题可限版块可搜正文,每 tab 独立历史 20 条);收藏(多夹、20 夹上限、设默认/重命名/删除、tid→夹反向索引本地维护);历史(200 条+进度);缓存管理(逐帖查看/删除);通知(前台 60s 轮询、按类型分组、本地已读、清空);屏蔽规则三 tab(本地规则:用户/关键词(正则,**修 P3-05** 加资源上限)/分类;官方屏蔽词云端全表覆盖写);用户资料(本人可改签名)+ 我的主题/我的回复;设置树全部(通用/阅读/通知/内容与存储/高级 五分组,含域名 5 选 1、夜间/跟随系统、主题风格 ink/plain/近黑、左手模式、纯色背景、自动下一页、仅 Wi-Fi 图片、图片策略、签名档、手势返回开关、常亮、字号头像滑块屏、实验室(web 兜底四档 + WP UA 开关 + 组合表分享 + 诊断日志导出)、恢复默认);关于(抽屉入口);`/web` 网页兜底 WebView;toast 桩(`showNotAvailable` 等价物,桩清单照 research/inventory.md §2);深链接收(`ng2n://` scheme;NGA 域名 intent-filter **并行期不注册**;冷启深链用 TaskStackBuilder 垫首页防死返回)。

**Blocked by:** 07, 14, 15

**Status:** open

- [ ] 24 屏 checklist 其余屏全部通过
- [ ] 桩项逐个点击确认 toast 文案与 RN 版一致
- [ ] 设置每一项改动后行为立即生效(域名切换下个请求生效等语义照抄)

## Comments

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
