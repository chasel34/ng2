# 15 — 登录与多账号(M3)

**What to build:** WebView 登录:加载 `nuke.php?__lib=login&__act=account&login`,轮询 `CookieManager.getCookie()` 收割 `ngaPassportUid`/`ngaPassportCid`(cid 是 HttpOnly,只能原生拿——RN 版 500ms 轮询节奏照抄);挂载前 `CookieManager.clearAll()` 保证多账号登录隔离。**修 P1-03**:app 的 HTTP 请求走自管 CookieJar(票 06),WebView CookieManager 只在「登录收割」这一个点交互,账号状态归属单一可推理。多账号管理屏(增删/切换/当前标记)、抽屉账号头左右滑循环切号、登出;30 天有效期的 UI 口径照抄(客户端假设值,不强制)。游客态:不发任何认证信息,全功能只读可用。

**Blocked by:** 01, 06

**Status:** in-review(待所有者登录验证)

- [ ] 真实登录→收割 cid→已登录请求链路通(测试账号)
- [x] 双账号切换后,收藏/通知等数据按 uid 隔离(P1-02 端到端验证)
- [x] 登出/切号后 WebView 与 CookieJar 状态互不污染

## Comments

### 完成摘要(2026-08-22)

`data/account`(登录 cookie 解析 + WebView cookie 出入口)+ `ui/login` + `ui/accounts` 落地。

`./gradlew :app:testDebugUnitTest` **490 tests / 0 failures / 1 skipped**(跳过的是票 06 那条
`NGA_INTEGRATION` 门控的联网冒烟),`:app:assembleDebug` 通过;
`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…AndroidWebCookieVaultTest`
在 `Pixel_8(AVD)` 上 **4 tests / 0 failures**。

新增文件:

- data:`account/LoginCookies.kt`(`login-cookies.ts` + `username.ts` 直译)、
  `account/WebCookieVault.kt`(`CookieManager` 的唯一出入口:接口 + `AndroidWebCookieVault`)
- ui:`login/{LoginViewModel,LoginScreen}.kt`、
  `accounts/{AccountsViewModel,AccountsScreen,AccountHeader,AccountIcons}.kt`
- test:`data/account/{AccountFakes,LoginCookiesTest,AccountsTest,AccountStoreTest,
  AccountCookieIsolationTest,AccountScopedStorageTest}.kt`、
  `ui/{login/LoginViewModelTest,accounts/AccountsViewModelTest}.kt`(共 53 条)
- androidTest:`data/account/{AndroidWebCookieVaultTest,LoggedInSmokeTest}.kt`

改动既有文件四处:

- `data/account/KeystoreCrypto.kt`:抽出 `AccountCrypto` 接口(见下「关键决定」),`KeystoreCrypto` 实现它;
- `data/account/AccountStore.kt`:crypto 收成构造参数;新增 `currentUid: Flow<String?>`(票 16/17 的
  Query key 用它,修 P1-02);新增 `withKeystore()` 工厂(给 androidTest 读设备上那份真存档);
- `di/DataModule.kt`:`provideAccountCrypto()` 与 `bindWebCookieVault()` 两条绑定;
- `ui/Ng2nApp.kt`:`Login` / `Accounts` 两个 Nav3 键 + 首页临时入口与账号头(**标了 TODO(票 16 移除)**)。

### 修 P1-03 的落法(与 RN 版的有意偏离)

审计 P1-03 的病根是「app 当前账号」与「WebView 里登着谁」是两个独立事实。这一版把
`CookieManager` 降级成**只在三个点被碰的临时区**,凭证的唯一权威来源是 `AccountStore`:

| 时机 | 动作 | RN 版原行为 |
|---|---|---|
| 登录屏挂载前 | `clearAll()` | 同(`src/app/login.tsx:45-55`) |
| 收割成功后 | `clearAll()` | **不清**——凭证留在 CookieManager 里,成为第二个身份来源 |
| 切号 / 登出后 | `clearAll()` | **不清**(`src/store/accounts.ts:53-67` 只动 zustand/SecureStore) |

app 自己的请求一枚 WebView cookie 都不用(票 06 的自管 `NgaCookieJar`,每请求现读
`AccountStore`),所以清干净不会影响任何功能。**票 17 的网页兜底屏(`/web`)要带登录态时,
用 `WebCookieVault.seed(url, credential)` 按当前账号现灌**——这就是审计说的「原生层支持按账号
安全同步 cookie,并向 WebView 暴露可验证的当前身份」,不要指望残留。

### 关键决定(票面留白的「实现时定」项)

1. **登录页跟着设置里的当前域名走**,RN 版写死 `DEFAULT_NGA_HOST`(`login.tsx:19`)。
   那个域名被墙时登录页根本打不开,而「换域名」本来就是给这种时候用的。
   cookie 也按同一个 host 读(`getCookie(host)` 会带上父域 cookie,两种域名都成立)。
2. **`AccountsViewModel` 挂在 Activity 上**(`Ng2nApp()` 在 `NavDisplay` 外面 `hiltViewModel()` 一次,
   传给各屏),对应 RN 版的全局 zustand store。每屏各取一个的话,切号之后另一屏的账号头不会跟着变。
   Nav3 的默认 `entryDecorators` 不含 `rememberViewModelStoreNavEntryDecorator`
   (`navigation3-ui` 不依赖 `lifecycle-viewmodel-navigation3`),所以 entry 内的
   `hiltViewModel()` 本来也是 Activity 作用域——这里写成显式传参,不靠这个隐式行为。
3. **VM 的判据读 `store` 的现值,不读 `state`**:`state` 是 `WhileSubscribed` 的 UI 缓存,
   没有订阅者时停在上一帧,拿它当判据会让后台发起的切号静默失效。
4. **`AccountCrypto` 接口**(票 14 的 `KeystoreCrypto` 现在实现它):实装要 `AndroidKeyStore` 与
   `android.util.Base64`,JVM 单测里两样都是会抛的桩,于是 `AccountStore` 的状态迁移
   (切号 / 登出 / 冷启读回)一条也测不了——而那正是本票验收②③。加密算法本身由
   androidTest 管,单测塞直通实现。**没有引入 Robolectric**。
5. **图标 Canvas 手画**(`AccountIcons.kt`),沿用票 12 `ViewerIcons.kt` 的理由:票 17 会铺完整
   图标体系,在那之前不引半套资源。视觉用 Material3 默认配色,**不自造 token**(票 17 的活)。
6. 登录态冒烟放 **androidTest** 而不是 JVM 单测:凭证是 DataStore + Android Keystore,
   离开这台设备解不开——也正因如此,cid 不需要以任何形式出现在仓库或环境变量里。

### 验收项逐条

**② 双账号切换后数据按 uid 隔离(P1-02 端到端)** — 打勾,证据:

- `AccountScopedStorageTest`:切号后 `SettingsStore.topicFavorIndex(currentUid)` 读到的是新 uid
  那把键;两个账号用**同一个 folder id**(审计点名的「相同 folder ID 碰撞」)也不串;全退光读到空索引。
- `AccountStoreTest.currentUid 这条流只在当前账号真的换了的时候才吐` — 票 16/17 的 Query key 接它。
- 存储侧按 uid 分键是票 14 做的;本票补的是「读的时候用的是**当前** uid」这一步——RN 版栽的正是这里。
- 真机/模拟器上的双账号手验要两次真实登录,见下「待所有者」。

**③ 登出/切号后 WebView 与 CookieJar 互不污染** — 打勾,证据两半:

- CookieJar 半边(JVM + `mockwebserver3`,断言的是**服务端收到的头**):
  `AccountCookieIsolationTest` — 登出后下一发不带任何 `Cookie`(游客态)、切号后换成新账号的
  cookie、退出当前账号后落到剩余那个、WebView 里留着别人的 cookie 也影响不到请求身份。
- WebView 半边:`AccountsViewModelTest` 证明切号/登出都会调 `clearAll()`;
  `AndroidWebCookieVaultTest`(模拟器实跑,4/4)证明 `clearAll()` 真的清得掉、
  `seed()` 先清后灌、`read()` 读得到 HttpOnly 的 cid。

**① 真实登录 → 收割 cid → 已登录请求链路通** — **未打勾,待所有者**。见下。

### 待所有者(真人介入)

1. **在 AVD 上真登录一次。** 登录屏已做到「打开即是 NGA 登录页」:
   `adb shell am start -n com.chasel.ng2.n/com.chasel.ng2n.MainActivity` → 首页「登录」按钮
   (content-desc `ng2n-login-entry`)→ 登录屏(`ng2n-login-screen`)。
   模拟器出网要先开代理:`adb shell settings put global http_proxy 10.0.2.2:7897`
   (**用完还原**:`adb shell settings delete global http_proxy` 与 `global_http_proxy_host/port`,
   本次验证已还原成原值「未设置」)。
2. **然后跑登录态冒烟**(默认 Assume 跳过):
   ```bash
   cd native
   ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.nga_integration=1 \
     -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.account.LoggedInSmokeTest
   ```
   它读设备上的真凭证打 `nuke.php?__lib=noti&__act=get_all`,断言 `data["0"].unread` 存在
   (游客拿不到这个结构)。日志只打 uid 与走通的组合,**绝不打 cid**(P1-04)。
3. **双账号手验**:登两个号 → 首页账号头左右滑切号(阈值 12dp 认、40dp 或 0.5dp/ms 提交)→
   看收藏/通知是否各是各的。手势逻辑有单测(`cycle`),但**滑动手势本身没在设备上验过**
   (要两个真账号才滑得动)。

### 未完成项 / 本次没能验的

**模拟器上 NGA 登录页加载不出来,原因在环境不在代码**——已用 RN 版做 A/B 对照:

- 本票的登录屏:顶栏、地址条 `bbs.nga.cn/nuke.php?__lib=login`、WebView、提示卡全部正常渲染,
  WebView 发出了请求并吃到 NGA 的重定向(`→ https://bbs.nga.cn/nuke/account_copy.html?login`),
  然后 `net::ERR_CONNECTION_CLOSED`;静置 75s 重试(NGA 限流冷却)结果相同。
- **同一台 AVD 上并装的 RN 版**(`ng2://login`)打开同一个页面**同样是空白页**,也没加载出来。
- 而**宿主机**经同一个代理 curl 这两个 URL 都是 200(`account_copy.html` 125KB)。

结论:模拟器 → 宿主代理这条路对 NGA 登录页走不通,两版表现一致,不是本票的实现问题;
真实登录只能在所有者的环境里做(见「待所有者」)。截图证据在会话里(登录屏框架 + WebView
错误页、账号管理屏空表态)。

### 发现的票外问题

1. **票 14 搬了 `accounts.ts` 但没搬它的测试**(`src/core/account/accounts.test.ts`,135 行)。
   本票补上了(`AccountsTest`,11 条),因为验收②③的语义全压在这几条纯函数上。
2. **票 14 的存档容错比 TS 弱一档**:TS 的 `parseStoredAccounts` 会**逐条剔除**坏账号
   (例:`{uid:'1002'}` 缺 `name`/`loginAt` → 只丢这一条);Kotlin 侧 `NgaAccount` 的
   `name`/`loginAt` 没有默认值,缺字段会让 `Json.decodeFromString` 抛,`decodeState` 于是
   **整张表退回游客态**。触发条件只有「换了结构又没换 key」,而项目策略是换结构就换 key,
   影响有限;但与 TS 不是同一语义,记在这里。
3. `AccountHeader` 的 `semantics` 节点边界包含了内边距(节点 bounds 是 padding 之后的区域),
   纯粹是 modifier 顺序造成的,uiautomator 定位不受影响;票 16 把它装进抽屉时顺手理一下即可。

**主控验收(2026-08-22)**:合并后主干 699 例全绿、`assembleDebug` 通过。②③ 通过;① 待所有者:AVD 的 WebView 打 NGA 登录页 `ERR_CONNECTION_CLOSED`(RN 版同样,宿主机 curl 正常——模拟器 WebView 网络环境问题),建议在小米真机上登录验证。票保持 in-review 直到所有者完成登录链路验证。

**所有者反馈(2026-08-22 晚)**:在 NG2N 上登录失败(未说明设备与现象)。所有者指示:等全部功能完成后再统一验收。→ 票 18 B 段走查登录屏时复现并建缺陷票(模拟器 WebView 登录页此前曾 `ERR_CONNECTION_CLOSED`,当时代理未还原;现已直连,需重测)。

### 票 18 B 段走查:登录屏复测(2026-08-23,Pixel_8 AVD `emulator-5554`,直连无代理)

**结论:登录页这次完全加载出来了,app 侧没有可见故障。所有者那次「登录失败」在这台
模拟器上复现不了,需要所有者补现象(哪一步、什么设备、什么提示)。**

#### 观察到的

- 设置 →「账号管理」→「添加账号」→ 登录屏(`ng2n-login-screen`),顶栏「登录 NGA 账号」、
  地址条 `bbs.nga.cn/nuke.php?__lib=login`、右上「刷新登录页」。
- **页面正常渲染**(截图 `L1.png`),默认是**二维码扫码登录**那一档:
  二维码 + 「注册账号」「忘记密码/重置密码」「使用APP扫码登录」「下载社区APP >」
  「使用密码登录」+ QQ / 微博两个图标。
- 点「使用密码登录」切到密码表单(截图 `L2.png`):
  「用户名/邮箱/手机号」「密码」两个输入框都可见可点,
  下面是**已勾选**的「我已阅读并同意 用户协议 与 隐私协议」,再下面「登录」与「扫码登录」。
- **logcat 全程没有 `ERR_`、没有 WebView 崩溃、没有 net error**。
  只有页面自己的二维码轮询,每 3 秒一组:
  ```
  I chromium: [INFO:CONSOLE:14] "appid : {"no":"none"}", source: https://bbs.nga.cn/nuke.php?__lib=login&__act=account&login
  I chromium: [INFO:CONSOLE:14] "deviceid : {"no":"none"}", ...
  I chromium: [INFO:CONSOLE:14] "userAgent : {"no":"none"}", ...
  ```
- 返回键从登录屏退回账号管理屏,正常。

#### 与 2026-08-22 那次 `ERR_CONNECTION_CLOSED` 的差别

那次**模拟器上设了 `settings put global http_proxy 10.0.2.2:7897`**,
NGA 登录页经宿主代理走不通(当时 RN 版也是空白页,已作为环境问题记录)。
现在代理已还原成「未设置」,模拟器直连,同一个 URL 一次就出来了。
→ **「登录页打不开」那条基本可以从嫌疑名单里划掉了,前提是不要再给模拟器设代理。**

#### 代码侧复核(没找到毛病)

- 收割判据与 RN 版逐字一致:`UID_PATTERN = ^\d+$`、`CID_PATTERN = ^[0-9A-Za-z_-]{16,}$`
  (`data/account/LoginCookies.kt:40/43` vs `src/core/account/login-cookies.ts:31/33`)。
- 轮询 500ms、`Ready` 之前先 `clearAll()`、收割后再 `clearAll()`
  (`ui/login/LoginViewModel.kt:85-114`),与本票 Comments 描述一致。
- WebView 开了 `javaScriptEnabled` / `domStorageEnabled` /
  `setAcceptCookie(true)` / `setAcceptThirdPartyCookies(view, true)`
  (`ui/login/LoginScreen.kt:156-167`)。
- 设备上 `files/datastore/` 只有 `ng2n-settings.preferences_pb`,
  没有 `ng2n-accounts.preferences_pb` —— 与「这台机器从没登录成功过」一致。

#### 需要所有者补的信息(否则查不下去)

1. 失败发生在**哪一步**:登录页打不开 / 表单提交后报错 / 提交成功但 app 没退出登录屏 /
   退出了但账号管理里没有账号 / 有账号但请求仍是游客态?
2. 用的是**扫码登录还是密码登录**?(默认档是扫码)
3. 设备与网络(小米真机?WiFi/流量?有没有开系统代理或 VPN?)
4. 失败时如果 app 还在,顺手抓一份:
   `adb logcat -d | grep -iE 'chromium|ERR_|ng2n'`。

#### 走查建议

`ui/login/LoginViewModel.harvest()` 目前**一条日志都不打**,失败时外部完全看不到
「轮询有没有在跑」「读到的 cookie 里有哪几个名字」。建议加两句只打 **cookie 名字集合**
(绝不打值,P1-04)的诊断日志,接到「实验室与诊断 → 导出诊断日志」里,
所有者下次失败就能直接导出来给结论。
