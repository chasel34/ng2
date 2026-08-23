# 35 — P0:登录后每次冷启动都死锁(白屏 → ANR),游客态永远碰不到

**Status:** resolved

**Severity:** P0(**已登录用户的 app 起不来**:冷启动后首页永远停在转圈,15s 后系统弹
「NG2N isn't responding」。不是偶发,**7/7 全中**(普通冷启 5 次 + `ng2n://` 深链冷启 2 次)。这多半就是所有者在票 15 里
反馈的「登录失败」——他登录其实是**成功**的,失败的是**下一次打开 app**)

## 现象

`emulator-5554`(Pixel_8,API 37),`com.chasel.ng2.n` debug = HEAD c6c1e27,
账号 lemon43(67296151)**已登录**:

1. `am force-stop com.chasel.ng2.n`
2. `am start -n com.chasel.ng2.n/com.chasel.ng2n.MainActivity`
   (或 `am start -a android.intent.action.VIEW -d "ng2n://bbs.nga.cn/read.php?tid=47421607"`
   —— 深链冷启也一样,首页作为 anchor 照样组合,轮询照样起)
3. 顶栏(墨绿 + NG2 + 抽屉钮 + 搜索钮)画出来了,**内容区永远是那颗转圈**
4. 15s 后随便点一下 → 系统 ANR 对话框「NG2N isn't responding / Close app · Wait」

`uiautomator dump` 全程 `ERROR: null root node`(主线程卡死,无障碍树取不到)。
`logcat -b crash` 干净(**不是崩溃,是死锁**)。

```
08-23 18:39:38.581 W InputDispatcher: Window com.chasel.ng2.n/…MainActivity is unresponsive:
  … is not responding. Waited 15001ms for MotionEvent
08-23 18:39:41.433 E ActivityManager: ANR in com.chasel.ng2.n (…/com.chasel.ng2n.MainActivity)
08-23 18:39:41.433 E ActivityManager: Reason: Input dispatching timed out …
```

**游客态从来碰不到**:A / B / C 三段走查(2026-08-22/23)全程冷启动无数次,一次都没遇到。

## 根因:两个线程抢同一把 `lazy` 锁,互等

`adb bugreport` 里的 `VM TRACES JUST NOW`(以及 `/data/anr/anr_2026-08-23-18-39-38-595`)
把两半都摆在明面上:

**主线程(tid=1,Blocked)** —— 等锁:

```
"main" prio=5 tid=1 Blocked
  at kotlin.SynchronizedLazyImpl.getValue(LazyJVM.kt:81)
  - waiting to lock <0x09cc2a6a> (a kotlin.SynchronizedLazyImpl) held by thread 16
  at com.chasel.ng2n.di.NetworkModule.provideUserAgents$lambda$1(NetworkModule.kt:91)
  at com.chasel.ng2n.core.net.UserAgents.get(Constants.kt:77)
  at com.chasel.ng2n.core.net.strategies.AttemptKt.runAttempt-bvV1_eA(Attempt.kt:103)
  at com.chasel.ng2n.core.net.strategies.FormatRotationStrategy.run(FormatRotation.kt:82)
  at com.chasel.ng2n.core.net.ChainKt.runStrategyChain(Chain.kt:201)
  at com.chasel.ng2n.core.net.NgaClient.execute(NgaClient.kt:79)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
  at androidx.compose.ui.platform.AndroidUiDispatcher.performTrampolineDispatch(…:79)
  at android.os.Looper.loop(Looper.java:398)
```

**`DefaultDispatcher-worker-3`(tid=16,Waiting)** —— 持着那把锁,却在等主线程:

```
"DefaultDispatcher-worker-3" daemon prio=5 tid=16 Waiting
  at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:230)
  at com.android.webview.chromium.WebViewChromiumFactoryProvider$StaticsAdapter.getDefaultUserAgent(…)
  at android.webkit.WebSettings.getDefaultUserAgent(WebSettings.java:1430)
  at com.chasel.ng2n.di.NetworkModule.provideUserAgents$lambda$0(NetworkModule.kt:92)
  at kotlin.SynchronizedLazyImpl.getValue(LazyJVM.kt:86)
  - locked <0x09cc2a6a> (a kotlin.SynchronizedLazyImpl)
  at com.chasel.ng2n.core.net.UserAgents.get(Constants.kt:77)
  at com.chasel.ng2n.core.net.strategies.AttemptKt.runAttempt-bvV1_eA(Attempt.kt:103)
  at com.chasel.ng2n.core.net.NgaClient.execute(NgaClient.kt:79)
  at com.chasel.ng2n.core.api.NotificationsKt.fetchNotificationFeed(Notifications.kt:105)
  at com.chasel.ng2n.data.notifications.NotificationPoller.pollOnce(NotificationPoller.kt:147)
  at com.chasel.ng2n.data.notifications.NotificationPoller.tick(NotificationPoller.kt:123)
  at com.chasel.ng2n.data.notifications.NotificationPoller$start$1.invokeSuspend(NotificationPoller.kt:92)
```

拆开说三件事,**每一件单独都不致命,凑齐就锁死**:

1. **`WebSettings.getDefaultUserAgent()` 在非主线程调用是要等主线程的。**
   第一次调用要把 WebView provider 拉起来,provider 的启动必须在 UI 线程跑;
   在别的线程调,它就 `CountDownLatch.await()` 等主线程把那段任务跑完
   (`di/NetworkModule.kt:92`)。

2. **它被包在一把 `lazy` 里,求值期间锁是被持着的。**
   `NetworkModule.provideUserAgents`(`:89-96`)是
   `val systemUserAgent by lazy { runCatching { WebSettings.getDefaultUserAgent(context) } … }`。
   `SynchronizedLazyImpl` 求值全程 synchronized,于是「等主线程」变成了「**攥着锁**等主线程」。
   那段 KDoc 写的是「只在第一发请求时求值,那时已经在 IO 协程里,冷启动路径上碰不到它」——
   前半句成立,**后半句不成立**(见第 3 条),而且「在 IO 协程里」恰恰是**危险**的一侧,
   不是安全的一侧。

3. **首页把请求发在了主 dispatcher 上。**
   `ui/home/HomeScreen.kt:152-153` 两个 `LaunchedEffect` 用的是组合的
   `AndroidUiDispatcher`(= 主线程),而 `BoardTreeRepository` / `BoardFavoriteRepository`
   里都没有 `withContext(Dispatchers.IO)`,所以 `NgaClient.execute` 的**前半段(含
   `UserAgents.get`)就在主线程上跑**。主线程栈里那句
   `AndroidUiDispatcher.performTrampolineDispatch` 就是实锤。

**为什么只有登录态中招**:

- 抢锁的那一半是通知轮询。`NotificationPoller.pollOnce`(`:141-145`)在
  `uid == null` 时**直接 return,一个请求都不发** —— 游客态永远没有这个线程去抢锁。
- 被锁住的那一半是 `LaunchedEffect(uid) { deps.boardFavorites.ensureLoaded(uid) }`
  (`HomeScreen.kt:153`),`ensureLoaded` 同样 `if (uid == null) return`
  (`BoardFavoriteRepository.kt:63`) —— 游客态它也不发请求。

两边都只在登录后才活,于是**登录之前 100% 正常、登录之后 100% 死锁**。
`BoardFavoriteRepository` 的 `buckets` 是纯内存(`:46`),冷启动 `fetchedAt == 0`,
所以这一发 `forum_favor2` **每次冷启动必发**,躲不过去。

顺带解释了所有者那次「登录成功了却说失败」:登录屏自己就是个 WebView,
provider 在**主线程**上初始化完了,所以那个进程里 `getDefaultUserAgent` 立刻返回、
登录后一路都好用;**下一次冷启动**才第一次让后台线程去初始化 provider —— 卡死。

## 影响

- 已登录用户**每次**冷启动都是白屏 + ANR。app 基本不可用。
- 票 18 D 段(登录态走查)因此被挡在门外:31 条待所有者项里只走完了 6 条,
  其余全部卡在「app 起不来」。

## 期望

主线程与后台线程都不要在持锁状态下等对方。三条改法,建议**全做**:

1. **别用 `WebSettings.getDefaultUserAgent()` 做懒初始化的内容**,或者至少在
   `Application.onCreate` 的**主线程**上预热一次(`getDefaultUserAgent` 在主线程调是安全的),
   `lazy` 之后拿到的就是现成值。
2. **`lazy` 换成不在求值期间持锁的形式**(`LazyThreadSafetyMode.PUBLICATION`,
   或者 `@Volatile` + 双检,或者干脆 `AtomicReference` + 允许重复求值)——
   UA 求两遍没有任何副作用,而「求值期间独占锁」有。
3. **请求链不许跑在主 dispatcher 上**:`BoardTreeRepository.ensureLoaded` /
   `BoardFavoriteRepository.ensureLoaded` / `reload`(以及同类仓库入口)加
   `withContext(Dispatchers.IO)`;或者屏幕侧的 `LaunchedEffect` 显式切上下文。
   这一条本身也是个独立缺陷:**首页冷启动在主线程上跑网络请求链**,
   哪怕不死锁也是白白卡首帧。

## 验收判据

登录态(`files/datastore/ng2n-accounts.preferences_pb` 存在)下:

1. `am force-stop` → `am start` **连做 5 次**,每次 8s 内 `uiautomator dump` 都能拿到
   非空节点树且版块宫格已渲染;
2. 5 次里 `logcat | grep "ANR in com.chasel.ng2.n"` 命中数为 0;
3. 顺带断言首页冷启动路径上 `NgaClient.execute` 不在主线程(可用 `StrictMode`
   `detectNetwork().penaltyLog()` 或单测断言仓库入口的 dispatcher)。

## 现场留档

- `scratchpad/br.zip`(`adb bugreport`,含 `VM TRACES JUST NOW` 与 `VM TRACES AT LAST ANR`
  两份完整线程栈);解开在 `scratchpad/br/`。
- 截图 `scratchpad/D_check{,2,3,5,6,7,8}.png`(顶栏出来了、内容区永远转圈)。

## 试过、但绕不过去的几条(给后来人省时间)

- **把分类树缓存改新**(DataStore 里 `board-tree/v1/fetchedAt` 改成「现在」,让首页走 24h
  缓存不发请求):没用。主线程那一半不是分类树,是 `boardFavorites.ensureLoaded(uid)` ——
  `BoardFavoriteRepository.buckets` 是纯内存(`:46`),冷启动 `fetchedAt == 0`,
  这一发 `forum_favor2` **每次冷启动必发**。
  ⚠ 这个改动**留在设备上了**:`ng2n-settings.preferences_pb` 里 `board-tree/v1/fetchedAt`
  被写成 `1787482202074`(2026-08-23 18:50),原值是 `1787409467154`。
  纯缓存时间戳,24 小时后自然过期重拉,不影响别的东西;想立刻还原就在首页下拉刷新一次。
- **走 `ng2n://` 深链冷启**(指望首页不组合、轮询不起):没用,首页是 anchor,照样组合。
- **反复冷启碰运气**(指望主线程抢先拿到 lazy):7/7 全死。抢锁那一半只要两次
  DataStore 读(settings + accounts)就到 `UserAgents.get`,主线程那一半要等 accounts
  这个 flow 先喂进组合、再重组、再起 `LaunchedEffect(uid)`,稳定慢一步。
- **关掉「启用被喷提示」**(`sprayNotice=false` → `tick()` 直接 return,不发请求):
  这条是**有效**的,但设置页够不着(app 起不来),直接改 DataStore 又被权限系统挡下了。
  票 35 修完之前,谁要在这台机器上做登录态走查,可以让所有者在**登录当次会话里**
  先把这个开关关掉 —— 之后的冷启动就都能起来。

## Comments

### 2026-08-23 修复(两处断点各怎么修)

**断点一:UA 求值「攥着锁等主线程」** —— `di/NetworkModule.kt` 里那个
`val systemUserAgent by lazy { WebSettings.getDefaultUserAgent(context) }` 整个删掉,
换成新的 `data/net/SystemUserAgent.kt`(纯 Kotlin,JVM 可测:「在不在主线程」「怎么把活儿
丢给主线程」「怎么问系统要 UA」都是构造参数,生产实现由 `NetworkModule` 注入
`Looper` / `Handler` / `WebSettings`)。三条纪律:

1. **求值只在主线程发生**:`Ng2nApplication.onCreate` 里 `systemUserAgent.prewarm()`
   (主线程调 `getDefaultUserAgent` 是安全的 —— provider 的初始化任务就在这条线程上跑);
2. **非主线程读永不阻塞**:`get()` 拿不到现成值时把求值 `post` 给主线程,自己立刻返回
   兜底 UA。多求一遍没有副作用,多等一次就是本票;
3. **全程不持锁**:值放 `@Volatile`,一次引用赋值;`AtomicBoolean` 只是「别重复往主线程
   排队」的去重旗,不是互斥锁。求值失败 / 拿到空串 = 当没求到,下次再说。

UA 语义照抄没简化:兜底值仍是 `USER_AGENT_PROFILES[WEBVIEW]`,与 RN 版
`src/core/net/constants.ts` 的 `webview` 档**同一个常量、同一条口径**;`UserAgents` 这个
类、四个档位、`X-User-Agent: Nga_Official` 辅助头、`runAttempt` 里的档位轮换一行没动。
**没有**用 `System.getProperty("http.agent")` 兜底:Android 上它是 Dalvik 的 UA
(`Dalvik/2.1.0 (Linux; U; Android …)`),拿它冒充 WebView 档等于给服务端递一个新的
识别特征,比常量兜底更危险(理由写进了 `SystemUserAgent` 的 KDoc)。

**断点二:请求链跑在主 dispatcher 上** —— 按票面第 3 条,**仓库层保证**,不改屏幕侧:
仓库里凡是能摸到网络的入口一律 `withContext(Dispatchers.IO)`。
`BoardFavoriteRepository`(`reload` / `clear` / 三个写操作共用的 `mutate`,`ensureLoaded`
经 `reload` 覆盖)、`TopicListRepository`、`HotTopicsRepository`、`UserPostsRepository`、
`UserProfileRepository`(含 `saveSignature`)、`SearchRepository`(主题 / 版块 / 用户三条)、
`TopicFavoriteRepository`(夹列表、夹内主题、夹的增删改、`applyTopicFavorites`)、
`FilterRepository`(`load` / 官方屏蔽表的 `edit`)、`SubBoardRepository.toggle`、
`CheckInRepository.checkInNow`、`NotificationPoller`(`refresh` / `clearAll` / `markRead`;
轮询循环本来就在 `@IoScope` 上)。`BoardTreeRepository` 本来就是 `scope.launch`(IoScope),
不用改。

### 验收项

- [x] 判据 3(单测断言仓库入口的 dispatcher):新增 `data/RepositoryDispatcherTest`(8 例)——
  假 transport 与假 `UserAgents` 各记一次「我在哪条线程上被调的」,断言都不是调用方线程。
  **UA 那一发单独断**:它是死锁现场主线程栈的最后一帧。这两个探针在改动前都会命中调用方
  线程(假 transport 不切上下文),所以是真回归网。
- [x] UA provider 在非主线程读不阻塞:`data/net/SystemUserAgentTest`(11 例)。核心那例用
  一个**永不放行的 `CountDownLatch`** 复刻 `getDefaultUserAgent` 等主线程的行为,
  真起一条线程读 UA,断言 5s 内拿到兜底值 —— 旧实现在这里会挂死。另有:主线程就地求值、
  预热后后台直接读真值、排队跑完后读到真值、求值抛异常 / 空串退回兜底、8 线程并发只排一次队、
  失败后还能再排一次、兜底值不会被当成真值记住。
- [ ] 判据 1 / 2(登录态连做 5 次 `force-stop` → `start`,`uiautomator dump` 非空 +
  0 条 ANR):**没做,模拟器被另一个代理占用**(本票开工时的约束)。需要真机 / 模拟器复验:
  装 `native/app/build/outputs/apk/debug/app-debug.apk`,登录态冷启动 5 次。
  顺带提醒:票里记了「设备上 `board-tree/v1/fetchedAt` 被改过」与「`sprayNotice` 关掉能规避」
  两条现场改动,复验前最好把 `sprayNotice` 打回**开**,否则轮询不发请求 = 抢锁那一半不出场,
  测不到东西。

`./gradlew :app:assembleDebug :app:testDebugUnitTest` 通过:956 例、0 失败、4 跳过
(跳过的是原有的两个联网冒烟,默认 `assumeTrue` 关着)。

### 有意偏离 / 决定

- **预热放在 `Application.onCreate` 同步调**,没有 `post` 到主线程后面去:两种写法在冷启动
  的主线程时间上其实一样(`onCreate` 之后主线程立刻就空,post 的活儿马上就跑),同步调
  少一层不确定性 —— 「第一发请求一定用真 UA」。代价是冷启动主线程上多一次 WebView provider
  初始化,**票 19 真机裁性能时值得单量一下这一笔**;真嫌贵可以退回 `post` 版本,
  兜底 UA 本来就是合法档位,退化只是「头几发用兜底 UA」,不会死锁。
- debug 变体的 StrictMode 会因此在冷启动多报几条主线程磁盘读(WebView 拉 provider),
  这是预期内的噪声,`Ng2nApplication.installStrictMode` 的 KDoc 早就写了这一类。

### 发现的票外问题

- `ui/topic/TopicRepository.loadDetail` 是**同一类缺陷**:`fetchTopicDetail` 跑在调用方
  dispatcher 上,而 `TopicViewModel` 一律 `viewModelScope.launch`(= `Main.immediate`),
  于是详情页每翻一页,请求链的前半段都在主线程上组装。本票没动它:它的单测用
  `advanceUntilIdle()` 驱动 `cacheTopicPages`,正确的修法是照着同文件里现成的
  `@ComputeDispatcher` 再注入一个 `@IoDispatcher`(生产 `Dispatchers.IO`、单测给测试调度器),
  那是票 13 的地盘,建议单开一张。死锁本身已经与它无关(UA 那把锁没了)。

**主控验收(2026-08-23)**:合并后 956 单测全绿;登录态(lemon43)连做 5 次冷启动(间隔 60s),5/5 首页 skeleton-ready,logcat ANR 计数 0。
