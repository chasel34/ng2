# 16 — 首页 / 抽屉 / 版块面(M3)

**What to build:** 首页(分类 tab 横滑 pager + 版块宫格 + 版头公告 + 抽屉宿主);抽屉(300dp、遮罩面板共用一个 progress、左边缘 22dp 拉出并与首页横滑让位、位移>12dp 才认、40%/速度阈值完成、返回键关闭;14 项入口:账号切换头滑动循环切号、登录、**每日签到原地执行行内状态**(UTC+8 本地去重)、添加版面 ID、由 URL 读取(深链映射表复用)、收藏夹、收藏夹管理、清空我的收藏(**串行逐删**——克制原则照抄)、我的主题、我的回复、我的缓存、短消息桩、最近被喷(未读徽标)、设置、关于);版块/合集主题列表(fid/stid 互斥且 stid 优先、两种排序、版头置顶、子版块 chip 条、无限滚动+下拉刷新、彩色标题);24h 热帖(客户端聚合:并发拉前 5 页按 24h 窗口过滤重排);精华区;子版块订阅/屏蔽管理(会话级本地覆盖不持久化)。

**Blocked by:** 07, 14

**Status:** in-review

- [ ] 对应屏(首页/版块/热帖/精华/子版块)checklist 项与 RN 版对照通过
      —— 代码逐条对照完毕,**模拟器联网验证做不了**:NGA 全链 `connection closed`,并装的 RN 版同样连不上(宿主代理侧问题,见 Comments「待所有者」)
- [x] 抽屉手势与边缘让位行为与 RN 版一致(初测手感;正式闸票 19 场景 6) —— 模拟器手势矩阵 6/6


## Comments

### 2026-08-22 — 票 16 交付(子代理)

**完成摘要**

| 落点 | 内容 |
|---|---|
| `ui/nav/` | `Navigator`(只有 `push`/`pop` 的小接口)+ `Keys.kt` 一次定齐 24 屏的 NavKey |
| `ui/home/` | 首页(分类 tab 横滑 pager + `LazyVerticalGrid` 版块宫格 + 版头公告 + 抽屉宿主)、`HomeRows`、`BoardIcon`、`HomeEntries` |
| `ui/drawer/` | `DrawerGesture`(纯状态机)+ `DrawerHost`(自实现抽屉)+ `AppDrawerContent`(14 项) |
| `ui/board/` | 主题列表 / 24h 热帖 / 精华区 / 子版块订阅屏 + `TopicRow` + `TimeText` |
| `ui/common/` | TopBar、状态视图、输入/确认对话框、Snackbar、OverflowMenu、Placeholder、`Motion`(motion.ts 直译) |
| `ui/icons/` | Canvas 画的 33 枚图标(沿用票 11/12 的做法) |
| `ui/dev/` | 开发者入口(收 demo 用) |
| `data/board/` | 版块树 24h SWR、版块收藏、主题列表分页、热帖、签到、子版块 |
| `data/notifications/` | `NotificationPoller`(前台 60s,票 17 复用) |

- 编译通过;单测 **683 项 / 0 失败 / 4 跳过**(跳过的 4 项全是票 07 留的联网冒烟 `Assume`)。
- 本票新增单测 38 项:抽屉阈值状态机 13、版块树 SWR 12、签到去重 7、热帖聚合 6。

**关键决定(票里留白的「实现时定」项)**

1. **不引 ViewModel,仓库直接是 `@Singleton` + `StateFlow`**。这一版的数据本来就该活在进程级
   (对应 RN 侧那个全局 query cache:从版块进主题再返回,列表与滚动位置都还在;子版块屏
   更是直接读版块页已经拉过的第一页,ADR-0002「能少打就少打」)。再套一层 per-screen
   ViewModel 只会把同一份数据复制一遍,还要给 Nav3 配 `ViewModelStoreNavEntryDecorator`
   才不会串屏。屏幕拿仓库走 `ui/AppDeps.kt`(Hilt EntryPoint + `remember`,与票 12 的
   `rememberImagePipeline()` 同源)。`TopicListRepository` 有 8 个桶的 LRU,对应 gcTime。
2. **`BoardKey` 一个键带三档 `face`(LIST / HOT / RECOMMEND)**,而不是 RN 那样三个路由。
   三个面共用同一套参数(id / kind / name),拆三个键只会让「从热帖点回版块」多写三份映射。
3. **`Home` / `Login` / `Accounts` 三个键留在 `ui/Ng2nApp.kt`**(票 01 与票 15 就在那儿声明的),
   其余 20 个在 `ui/nav/Keys.kt`。刻意不搬,合并冲突最小。
4. **接收者类型是 `EntryProviderScope<NavKey>`**,不是票面写的 `EntryProviderBuilder` ——
   Nav3 1.1.6 里这个类叫 `EntryProviderScope`(`javap` 核实),`EntryProviderBuilder` 是更早的叫法。
5. **彩色标题在「数据到达时一次构建」而不是仓库里**:`buildTopicRows(topics, colors, titleColors)`
   放在 `ui/board/TopicRow.kt`,由屏幕在 `remember(topics, colors)` 里调一次。掩码解码更早
   (票 10 的 `decodeTitleStyle`,端点解析期就做完了),这里只把档位翻成 `SpanStyle`。
   之所以不塞进 `data/`:调色板只有 UI 层知道,而 `AnnotatedString` 是 `compose.ui.text` 的类型,
   放进 data 层等于把 UI 依赖倒灌进仓库。滚动路径上仍然零计算(anzong 四原则第一条)。
6. **抽屉手势两处都跑 `PointerEventPass.Initial`**,认领前一个事件都不消费。理由见下「实测修正」。
7. 图标沿用票 11/12 的 **Canvas 直接画**(`BBCodeIcons.kt` 的 KDoc 写着「票 17 会把完整图标
   体系铺开」),不引 `material-icons-extended`(已弃用 + 体积)。**没有加任何新依赖**,
   `libs.versions.toml` 一个字没动。

**对 RN 版的有意偏离**

- **边缘拉出跟手**。RN 的 `DrawerEdgeHandle` 只有 `onPanResponderRelease`,拖动期间抽屉
  **不跟手**,松手时只看 `dx > 22`(拉出区宽度)就开 —— 轻轻蹭一下边缘会整屏弹出抽屉。
  这里改成跟手 + 与关闭对称的判据(「从起手状态挪过 40% 就翻面」,票面原话「40% 或速度
  阈值完成」)。关闭那一侧与 RN 逐字等价(`-dx > W*0.4` ⇔ 进度从 1 掉到 0.6 以下)。
  票 19 场景 6 对拍的是 anzong(M3 抽屉,边缘拉出本来就跟手),这条偏离是往对拍口径靠。
- **子版块三态**:票 07 修掉的「白名单误报」在 UI 上落地 —— 白名单外画「未知」而不是
  「已屏蔽」(RN 版原行为是一律画成已屏蔽,实测「网络游戏综合」从没被屏蔽却显示已屏蔽)。
- **「用网页版打开」「活动帖外链」走站内网页兜底屏**(`WebKey`,票 17),不开系统浏览器 ——
  RN 版的活动帖走的是 `WebBrowser.openBrowserAsync`,而域名被封时系统浏览器一样打不开;
  站内那一档至少还能换域名(ADR-0002 链外第 6 步)。
- **`P1-02` 的另一半**:版块收藏按 uid 分桶(RN 侧 TanStack 的 key 也分了,这里从一开始就分)。

**模拟器实测修正(两条,已进 commit `6902ba1`)**

1. 松手那一帧要把**最后一个坐标**算进位移再判定。`adb input swipe` 的末段比真手指稀疏
   (实测目标 x=520 时最后一个 move 停在 498),只认最后一个 move 会把「刚好过 40%」判成没过。
   真手指抬手同样带着最终位置(RN 侧 `onPanResponderRelease` 读的 `gesture.dx` 也是)。
2. **面板左划关闭原来挂在 `Main` 通道上,实测不成立**:抽屉里横划一把会**触发落点那一行的
   点击**(划到「由 URL 读取」直接弹出那个对话框)。Main 是子 → 父,`clickable` 的
   `waitForUpOrCancellation` 处理完这一发时父节点还没来得及消费,而它只在「手指离开边界」
   时才取消 —— 横向平移到别处并不算离开。改跑 `Initial` 后正常。
   **这条值得记进 perf-playbook 的 P 段**(与 P1「触摸分发被父边界裁剪」同族:Compose 里
   父子争手势要靠 Initial 通道 + 认领后消费,不能指望 Main 通道的先后顺序)。

**验收项②:抽屉手势矩阵(模拟器 `emulator-5554`,density 420 ⇒ 2.625 px/dp)**

判据:边缘 22dp = 57.7px / 认领 12dp = 31.5px / 面板 300dp = 787.5px / 完成 40% = 315px。

| 用例 | 期望 | 实测 |
|---|---|---|
| 边缘内(x=20)快速右滑 500px | 开 | ✅ 开 |
| 边缘外(x=300)右滑 500px | 不开(让位) | ✅ 不开 |
| 边缘内右滑 25px(< 12dp) | 不动 | ✅ 不动 |
| 边缘内慢速右滑 320px(> 40%) | 开 | ✅ 开 |
| 边缘内慢速右滑 200px(< 40%) | 弹回 | ✅ 不开 |
| 开着按返回键 | 关 | ✅ 关 |
| 面板上左划 500px | 关且不误触条目 | ✅ 关,无误触 |

脚本 `drawer_test.py` 在会话 scratchpad 里(不进版本库,它依赖具体分辨率)。
**注意 `input swipe` 的注入时序 artefact(playbook T5)**:这一组只作「阈值对不对」的判据,
手感仍以票 19 场景 6 的真机逐帧对拍为准。

**未完成 / 待所有者**

1. **模拟器联网验证做不了(环境问题,不是本票的代码)**。首页/版块/热帖/精华/子版块五屏
   的 checklist 需要真数据,而模拟器上 NGA 全链失败:诊断日志(票 14 的
   `diagnostics.log.v1`,`adb shell run-as com.chasel.ng2.n cat files/datastore/…` 读得到)
   显示 **8 次尝试全部 `network: connection closed`**,5 个域名 × 3 种格式一个不落。
   **A/B 对照:并装的 RN 版 `com.chasel.ng2` 同一时刻同样「连不上服务器」**,而宿主上
   `NGA_INTEGRATION=1 NGA_TEST_PROXY=127.0.0.1:7897 ./gradlew :app:testDebugUnitTest
   --tests '*NgaIntegrationSmokeTest'` 通过 —— 所以是宿主代理侧/模拟器出口的问题。
   主控 2026-08-22 仲裁同结论。**待所有者:把模拟器的联网通路修通后重跑这一屏的 checklist。**
   顺带证明这条路是通的:票 12 的图片 demo 在同一台模拟器上正常出图。
2. **写操作(签到 / 版块收藏 / 子版块订阅)需要登录,标「待所有者」**:代码已按票 07 的
   写链(`Operation.WRITE`,禁入格式轮换与换账号)接好,但没有凭证跑不了真验证。
3. **票 15 合并接线(留给主控,两处)**:
   - `ui/home/HomeEntries.kt` 里 `entry<Login>` / `entry<Accounts>` 现在是
     `PlaceholderScreen`,换成票 15 的 `LoginScreen(onBack)` / `AccountsScreen(viewModel, onBack, onAddAccount)`;
   - `ui/home/HomeScreen.kt` 里 `AppDrawerContent(accountHeader = { GuestAccountHeader(...) })`
     换成票 15 的 `AccountHeader(viewModel = accounts, onOpenAccounts = …, onLogin = …)`;
     `AccountsViewModel` 按主干那样在 `Ng2nApp` 里 `hiltViewModel()` 取一次往下传。
   `GuestAccountHeader` 是游客态那一档的占位实现,可以直接删。
4. **票 13 合并**:`ui/nav/Keys.kt` 里的 `TopicKey(tid, title?, fav?, page?, pid?, floor?)`
   与 `HomeEntries.kt` 里的 `entry<TopicKey>` 占位,由票 13 的 `TopicEntries.kt` 顶掉。
5. **票 17 合并**:`HomeEntries.kt` 里 12 个 `PlaceholderScreen("…", "票 17", …)`,一行换一屏。
6. **模拟器被我改过两处设置,没来得及还原**(主控仲裁把设备交给票 13 时我已停手):
   - `settings put global http_proxy 10.0.2.2:7897` + `global_http_proxy_host/port`
     (**原值是未设置**,还原用 `settings delete global http_proxy` 等三条);
   - 导航栏 overlay 切成三键(`cmd overlay enable …navbar.threebutton` +
     `disable …navbar.gestural`),**原值是 gestural**。切三键是为了排除系统返回手势对
     左边缘 22dp 的干扰 —— 后来证明干扰不成立(三键下第一轮同样失败,真因是上面那两条),
     所以还原回 gestural 即可。

**发现的票外问题**

1. **手势导航下左边缘 22dp 与系统返回手势重叠**。Android 的手势返回占用左右各约 20dp,
   而抽屉的拉出区是 22dp。真机上用户从最边缘划会触发系统返回而不是拉抽屉。
   `Modifier.systemGestureExclusion()` 能排除,但系统**每边最多只认 200dp 高**,
   全高的边缘条排不掉。RN 版有同样的约束(它也没排)。建议票 19 真机手感那一轮决定:
   接受现状 / 排除底部 200dp / 把拉出区往里挪。
2. **`SubBoardOverrides.beginToggle` 的返回值有歧义**(票 14 的 `data/session/SessionOverrides.kt`):
   `null` 同时表示「已经在途」与「本来就没有覆盖」,调用方分不开。本票在
   `SubBoardRepository` 里改成先查 `inFlight` 再调它,绕开了这个歧义;接口本身没动。
3. **`NotificationLike` 住 `data/notifications`,而 `NgaNotification` 住 `core/api`**,
   core 不能反过来依赖 data,于是 `NotificationPoller` 里得包一层适配壳。
   把 `NotificationLike` 下沉到 core 会更顺,但那是票 07/14 的地盘,本票没动。
4. **Kotlin 反引号外的中文测试名不能以数字开头**(`fun 22dp以外…` 编译报
   「Function declaration must have a name」)。agent-brief 里记了「不能含 ASCII `:`」,
   建议一并补上这一条。
