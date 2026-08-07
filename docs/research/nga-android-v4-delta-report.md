# NGA-CLIENT Justwen fork（v4.2.2）相对 ymback v3.7.6 的差异调研报告

> 对比基线：ymback fork d734716（2022-07，v3.7.6）→ Justwen fork HEAD cdad1abb（2026-08，v4.2.2/4022）。
> Justwen fork（https://github.com/Justwen/NGA-CLIENT-VER-OPEN-SOURCE ）为现行权威版本，**活跃维护中**（最新提交即调研当日）。
> 以下路径均为 nga-justwen 仓库内相对路径。增量 213 个提交，640 文件变更。

## A. 架构变化

### A1. 模块结构（组件化改造后的最终形态）

旧版 5 模块：`app(nga_phone_base_3.0) + lib_core + lib_network + lib_cloud + lib_common`。

新版 13 模块（`settings.gradle`）：

| 模块 | 职责 | 备注 |
|---|---|---|
| `nga_phone_base_3.0` | 主 app（帖子列表/详情、发帖、设置等老 View 体系） | 仍是最大模块 |
| `lib_core` | BBCode→HTML 解码器（`gov.anzong.androidnga.core.decode.*`）、HtmlData | |
| `lib_core_data` | 短消息数据 bean（MessageListInfo 等） | |
| `lib_base_common` | 旧 lib_common 更名，工具类 + KV 存储组件 `base/kv/DataStore.kt` | |
| `lib_base_network` | 旧 lib_network 演进：RetrofitHelper/RetrofitService/RetrofitServiceKt | |
| `lib_base_ui` / `lib_base_ui_compose` | Base Activity/Fragment；Compose 基建（`BaseComposeActivity.kt`、`PullRefreshLazyColumn.kt`、`ScaffoldApp.kt`、theme） | |
| `lib_base_service_api` | 组件化路由服务接口（`ARouterConstants.kt`、`IUserManagerService.kt`、`IThemeManagerService.kt`） | |
| `lib_bu_account` | 账号业务：`bu/login/LoginActivity.kt`、`bu/UserManager.kt`、Room `db/AppDatabase.java` | |
| `lib_bu_message` | 短消息业务（全 Compose） | |
| `lib_bu_statistics` | 旧 lib_cloud 更名（umeng/bugly） | |
| `lib_base_logger` | 日志模块 | |
| `lib_module_debug` | 调试模块，`DebugActivity.kt`、`FileLogger.kt` | |

组件化用 ARouter 1.5.2，跨模块跳转全部走 `ARouterConstants` 路由。

### A2. Kotlin / Compose 采用程度

**已 Compose 化**：搜索页（`activity/compose/SearchActivity.kt`）、板块列表首页（`activity/compose/board/ForumBoardView.kt` + ViewModel）、抽屉菜单（`activity/compose/drawer/NavigationDrawerFragment.kt`）、屏蔽管理（`activity/compose/filter/FilterWordFragment.kt`）、短消息列表/详情/发送（`lib_bu_message/.../compose/`）、登录页（Compose `AndroidView` 包 WebView）、用户管理（`lib_bu_account/.../user/UserManagerFragment.kt`）、Compose 模板容器 `TemplateComposeActivity.kt`。

**仍是老 View/WebView 体系**：主题列表 `sp/phone/ui/fragment/TopicListFragment.java`、帖子详情 `ArticleListFragment/ArticleListAdapter`、发帖编辑器、用户资料 `ProfileActivity.java`、设置页（androidx Preference）。

**帖子正文渲染没变**：仍是 BBCode→HTML→WebView（`lib_core/.../decode/` Decoder 链 + `ArticleListAdapter.loadDataWithBaseURL`）。

### A3. 网络层

- Retrofit 协程适配：新增 `lib_base_network/.../RetrofitServiceKt.kt`（suspend 版），供 Compose 页使用；老 RxJava2 `RetrofitService.java` 并存。
- Paging 3（3.3.0）：仅用于短消息列表/详情（`MessageRepository.kt` / `MessageDetailRepository.kt` 的 `Pager(PagingConfig(20))`）。
- fastjson2（2.0.59.android8）：仅 `lib_core/build.gradle:43` 声明依赖，**源码未见 import**，主力仍是 fastjson 1.1.71.android。
- 旧 HttpURLConnection GBK POST 通道仍在：`sp/phone/param/HttpPostClient.java`、`TopicPostTask.java`、`PostCommentTask.java`、`AvatarFileUploadTask.java`（发帖/回帖/贴条/上传头像）。短消息那条已删（改走 Retrofit suspend POST）。
- Cookie 注入：OkHttp 拦截器统一加 `Cookie` + `User-Agent` + `X-User-Agent: Nga_Official`（`RetrofitHelper.java` `createOkHttpClientBuilder()` 约 105-120 行）；另一拦截器把 `charset=gbk` 的 POST 重编码为 `application/x-www-form-urlencoded;charset=GBK`。

### A4. 版本现状

| 项 | 旧版 | 新版 |
|---|---|---|
| minSdk | 23 | **30**（Android 11） |
| targetSdk/compileSdk | 30 | **35** |
| AGP | 3.6.4 | 8.6.1 |
| Kotlin | 1.3.72 | 2.0.21（含 compose 编译器插件） |
| Compose | 无 | compose_ui 1.7.0 |
| 版本号 | 3.7.6 (3076) | 4.2.2 (4022) |
| 其它 | — | retrofit 2.6.0、paging 3.3.0、arouter 1.5.2、rxjava 2.2.6、room 2.4.1 |

## B. 功能变化

### B1. 短消息（重写为 Compose）

接口全部是 `nuke.php?__lib=message&__act=message&lite=js`：
- 列表：`act=list&page=N`（`MessageRepository.kt:29-31`）
- 详情：`act=read&mid=X&page=N`（`MessageDetailRepository.kt:36-42`）
- 发送：POST `nuke.php?__lib=message&__act=message&lite=js&charset=gbk`，body：`act`（`new`/回复）、`mid`、`to`（GBK URLEncode，中文逗号转英文）、`subject`、`content`（`MessagePostRepository.kt:22-46`）。成功判定：`"发送完毕"` / `"操作成功"` / `" @提醒每24小时不能超过50个"`。
- 响应为 lite=js 格式，解析在 `MessageConvertFactory.java:29-45`。
- 实际服务端可用性未运行验证，但持续修复提交（59cc925a 等）表明维护至可用。

### B2. 板块列表在线更新 / 内置数据 / 书签分离

- 在线更新：`{域名}/app_api.php?__lib=home&__act=category`（`ForumBoardRepository.kt:23`）。响应 `ForumsListBean`：`code/msg/result[].groups[].forums[]{id,name,stid}`。
- 更新时机：进入版面时触发，24 小时最多一次（`ForumBoardViewModel.kt:85-102`），`mergeBoardList` 增量合并后写 `files/board_list_remote.json`。
- 内置数据：`assets/board_list.json`（`BoardEntity` 数组，本地版本号 `BOARD_LOCAL_VERSION_CURRENT = 5`）；旧 `assets/json/category.json` 已删。
- 书签分离：单独存 `files/board_bookmark.json`（`ForumBoardRepository.kt:60-88`），UI 上是「我的收藏」分组。

### B3. 版头功能

「版头」= 版面头条帖。`board_list.json` 每版面可带 `head` 字段（值为帖子 tid）。版面菜单出现「版头」项，点击用普通帖子详情页打开该 tid（`TopicListPresenter.java:244-249`）。不调新接口。

### B4. 屏蔽体系

- 本地合并：用户 + 关键词统一由 `activity/compose/filter/FilterManager.kt` 管理，独立 KV 文件 `filter`，带旧 SP 迁移。
- 正则：`FilterKeyword.java:33-39` 关键词直接 `Pattern.compile` + `find()`，天然支持正则。
- 官方屏蔽（NGA 服务端同步）：
  - 读：POST `nuke.php?__lib=ucp&__act=get_block_word&__output=8&uid={uid}`，需 Referer `{域名}/nuke.php?func=ucp&uid={uid}`（`FilterWordModel.kt:106-122`）。响应 `data["0"]` 多行文本：第 2 行空格分隔关键词、第 3 行空格分隔 `uid/用户名`。
  - 写：`__act=set_block_word&__output=8&data={GBK URLEncode("1\r\n词列表\r\n用户列表")}`（`FilterWordModel.kt:53-105`）。
  - UI 四分组：本地用户/本地关键词/官方用户/官方关键词，官方组只读。

### B5. 抽屉 / 用户管理 / 登录

- 抽屉改版：Compose 版 `NavigationDrawerFragment.kt`，入口全走 ARouter；带被喷计数徽标。
- 用户管理：Compose 重写；用户数据从 SP JSON 改为 **Room**（`lib_bu_account/.../db/AppDatabase.java`）；管理类 `UserManager.kt`。多账号 Cookie 轮换重试仍在（`ArticleListPresenter.java:82-113` + `UserManager.kt:146-149 getNextCookie`）。
- 登录重构：**仅保留 WebView 抓 Cookie**。账密+验证码登录已删（`RetrofitService.java:49-66` 残留声明为死代码）；QQ/微博明确不支持。登录页 URL 不变（`https://ngabbs.com/nuke.php?__lib=login&__act=account&login`，`LoginViewModel.kt:14`），成功后从 CookieManager 解析 `ngaPassportUid`/`ngaPassportCid`/`ngaPassportUrlencodedUname`（GBK 两次 URLDecode，`LoginViewModel.kt:46-79`）。

### B6. 评分界面优化

指评分帖（`[randomblock]`+`[style ...]` BBCode 模板）的渲染优化：新增 `lib_core/.../decode/ForumVoteDecoder.java`（把 `[style ...]`/`[comment game_*]` 转内联 CSS div）+ `corebuild/HtmlVoteBuilder.java`，仍在 WebView 显示。不涉及新接口。加分接口未变（`LikeTask.java:36-48`）。

### B7. 掷骰子

`[dice]` 本地复算逻辑从 `ArticleConvertFactory` 抽成 `lib_core/.../decode/ForumDiceDecoder.kt`（种子 = authorId+tid+pid，`(seed*9301+49297)%233280`）。纯本地。

### B8. 被喷数量提示

复用提醒轮询 `nuke.php?__lib=noti&__act=get_all&__output=8`（`ForumNotificationTask.java:29`），前台 ≥30s 一次（`NotificationController.java:78-85`），回复提醒条数写 SP `KEY_REPLY_COUNT`，Compose 抽屉显示红点。清除：`nuke.php?__lib=noti&raw=3&__act=del`。

### B9. WebView 相关

- **兜底逻辑仍在**：详情解析失败（ServerException）且多账号重试无效时，`pref_show_with_webview`（默认 true，`res/xml/settings_lab.xml:8`）开启则 WebView 打开 `read.php?tid=/pid=`（`ArticleListPresenter.java:122-142`）。
- 实现更换：删除 `WebViewerActivity`/老 `WebViewFragment`，新 `lib_base_ui/.../WebViewFragment.kt` + `ForumWebFragment.kt`（含外部浏览器菜单、nga/178 域名链接放行）。
- 7d468acc 修「一直重试」= 删掉 manifest 里会拦截 nga 链接造成循环的 `WebViewActivity`。
- 「多次返回」修复 = `BaseActivity.kt`/`BaseFragment.kt` 用 OnBackPressedCallback 让 WebView 先 goBack。

### B10. 其它

- 移除：老短消息发送界面、老板块列表在线浏览入口（`ForumListActivity/GetAllForumsTask`）、旧板块管理代码、侧滑返回、32 位 so、硬件加速开关（默认开启）。
- 新增：自动签到（`CheckInTask.java:30`，`nuke.php?__lib=check_in&__act=check_in&lite=js`，默认关）；Material You 图标；调试模块与文件日志；快捷更新账号；多用户提示（板块页顶部显示当前账号）；NG 娘表情包与表情 BBCode 化（来自 mlzzen PR）；edge-to-edge 适配。

## C. API 变化

### C1. 图片域名从服务端返回（8862cdd4，2026-08-07）

- 来源：**read.php 响应 JSON 的 `data.__GLOBAL._ATTACH_BASE_VIEW` 字段**，取第一段（`split("/")[0]`）作附件图片域名。解析：`ArticleConvertFactory.java` `getAttachmentHost()`（约 128-134 行），写入 `ThreadRowInfo.attachmentHost` → `HtmlData.attachmentHost` → `AttachmentData`。
- 使用：`ForumImageDecoder.java:44-48` 相对路径 `[img]` 拼接时优先用它，硬编码 `img.nga.178.com` 仅作 fallback（`ForumImageDecoder.java:35`；`HttpUtil.java:25` 和 `ForumVoteDecoder.java:63` 评分帖封面仍硬编码）。
- 结论：部分替代——正文与附件走服务端域名，评分帖封面等仍硬编码。

### C2. 新增/变化接口汇总

| 功能 | 方法+URL | 参数 | 响应 | 证据 |
|---|---|---|---|---|
| 板块列表在线更新（新） | GET `{host}/app_api.php` | `__lib=home&__act=category` | 标准 JSON `{code,msg,result[]}` | ForumBoardRepository.kt:23 |
| 官方屏蔽-读（新） | POST `{host}/nuke.php` | `__lib=ucp&__act=get_block_word&__output=8&uid=` | lite JSON，`data["0"]` 多行文本 | FilterWordModel.kt:106-122 |
| 官方屏蔽-写（新） | POST `{host}/nuke.php` | `__lib=ucp&__act=set_block_word&__output=8&data=`（GBK 编码 `1\r\n词\r\n用户`） | `data["0"]` 成功文案 | FilterWordModel.kt:53-105 |
| 短消息列表/详情/发送 | 见 B1 | | lite=js | |
| 自动签到（新） | GET `nuke.php?__lib=check_in&__act=check_in&lite=js` | — | lite=js | CheckInTask.java:30 |
| 帖子详情（小变） | GET `read.php?...&page=N&__output=8&noprefix&v2` | 新增关注 `__GLOBAL._ATTACH_BASE_VIEW` | JSON | ArticleListModel.java:54 |

兼容性备注：b733097a（2024-04）——主题列表 `__T[].parent` 字段从 JSON 对象变为**字符串化 JSON**，客户端已做兼容（`TopicConvertFactory.java:220-231`）。

### C3. UA / 域名 / 格式参数

- **UA（变化大）**：请求 UA 不再写死 `Nga_Official/80023`，默认取**系统 WebView UA**（`RetrofitHelper.java` 构造函数 `WebSettings.getDefaultUserAgent(context)`），支持用户自定义（SP key `USER_AGENT`），WebView 界面也可自定义 UA。辅助头 **`X-User-Agent: Nga_Official`** 一直存在。
- 域名列表无实质变化：`bbs.ngacn.cc / bbs.nga.cn（默认） / nga.178.com / nga.donews.com / ngabbs.com`（`lib_base_common/src/main/res/values/arrays.xml:3-9`；`nga.178.com"` 末尾多引号疑似 bug 仍在）。切域名会重建 Retrofit。
- `__output`/`lite` 格式参数无变化：`lite=js`（消息、签到、主题列表）、`__output=8`（详情、提醒、点赞、官方屏蔽）、`raw=3`（提醒删除、点赞）并存。

### C4. 登录/鉴权

- 本质未变：Cookie `ngaPassportUid; ngaPassportCid`，OkHttp 拦截器统一注入。
- 获取方式收敛为仅 WebView 登录；账密直连接口调用代码已删。
- 新增多账号自动切换鉴权重试与 Room 持久化。

### 不确定项

- 短消息新接口的实际服务端可用性（未运行验证）。
- fastjson2 的实际调用点（源码未见 import）。
- `nga_domain` 中 `nga.178.com"` 引号是否已知 bug。
