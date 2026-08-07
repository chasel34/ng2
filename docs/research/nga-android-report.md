# NGA-CLIENT-VER-OPEN-SOURCE 复刻调研报告

> 仓库路径：`/private/tmp/claude-501/-Users-cola-Documents-code-ng2/7a950992-64a1-451b-9da6-a65f88a13d7c/scratchpad/NGA-CLIENT-VER-OPEN-SOURCE`
> 版本：`3.7.6`（versionCode 3076），applicationId `gov.anzong.androidnga`，minSdk 23 / targetSdk 30，Java 为主 + 少量 Kotlin。

**一句话结论**：这个客户端并没有用什么"NGA 开放 API"，而是直接调用 NGA 网页版的 PHP 端点（`read.php` / `thread.php` / `post.php` / `nuke.php` / `forum.php` / `app_api.php`），靠 `__output=8` 或 `lite=js` 让服务端吐 JSON，用 `ngaPassportUid` + `ngaPassportCid` 两个 Cookie 做认证，全程 **GBK 编码**。复刻的核心难点全在编码处理、响应清洗和 BBCode 渲染上，不在架构上。

---

# 第一部分：整体架构与功能清单

## 1. Module 划分

| Module | 作用 | 实际内容 |
|---|---|---|
| `nga_phone_base_3.0` | 主 App | 几乎所有业务代码：Activity/Fragment、MVP、网络请求、数据解析 |
| `lib_network` | 名义上的网络层 | **实际是空壳**，只有一个接口 `gov/anzong/androidnga/http/OnHttpCallBack.java`（`onSuccess/onError` 回调）。真正的网络代码在主 app 的 `sp/phone/http/retrofit/` 下 |
| `lib_core` | BBCode → HTML 渲染引擎 | `HtmlConvertFactory` + `decode/*Decoder` + `corebuild/*Builder` + assets（HTML 模板、CSS、JS、本地表情图） |
| `lib_common` | 基础工具 | `PreferenceKey`（所有 SP key）、`EmoticonUtils`（表情 URL↔本地路径映射）、Context/Theme/Thread/Toast/Preference 工具、`WebViewEx` |
| `lib_cloud` | 第三方 SDK 隔离层 | Bugly 崩溃上报 + 友盟统计，`CloudServerManager` / `ICloudSever` |

关键依赖：Retrofit 2.3.0、OkHttp、RxJava2 + RxAndroid、fastjson `1.1.71.android`、Glide 4.11、ARouter 1.2.4、ButterKnife 10.2.1、Room 2.4.1、PhotoView、rxpermissions。

## 2. 网络层组织

网络层是**双轨制**，这是复刻时最容易踩坑的地方：

### 轨道 A：Retrofit + OkHttp + RxJava（主力，用于 GET 和大部分 nuke.php POST）

- `sp/phone/http/retrofit/RetrofitService.java` — 唯一的 Retrofit 接口，只定义了通用方法（`@GET("nuke.php")`、`@GET("forum.php")`、`@GET @Url`、`@POST @Url`、`@FormUrlEncoded @POST("nuke.php")`），**不是每个业务一个方法**，而是各 Model/Task 自己拼 URL 或 Map。
- `sp/phone/http/retrofit/RetrofitHelper.java` — 单例。baseUrl 来自设置里选的域名，监听 `nga_domain` 偏好变化后重建 Retrofit。三个 OkHttp 拦截器：
  1. 注入 `Cookie`（若请求未自带）和 `User-Agent: Nga_Official/80023`
  2. **GBK POST 修正**：把 POST body 解码后如果含 `charset=gbk`，重新用 `application/x-www-form-urlencoded;charset=GBK` 包装
  3. Debug 抓包收集
- `sp/phone/http/retrofit/converter/JsonStringConvertFactory.java` — 响应体统一 **按 GBK 解码成 String**（不做 JSON 反序列化），后续由各 ConvertFactory 用 fastjson 手工解析。

### 轨道 B：裸 `HttpURLConnection`（遗留，用于需要 GBK 表单体的 POST）

- `sp/phone/param/HttpPostClient.java` — 手写 POST，`Accept-Charset: GBK`，`setInstanceFollowRedirects(false)`，UA 为 `Nga_Official/<versionCode>([机型];Android<版本>)`。用于：发帖 `TopicPostTask`、贴条 `PostCommentTask`、短信 `MessagePostTask`、改头像 `AvatarPostActivity`、投票 `ProxyBridge`。
- `sp/phone/util/HttpUtil.java` — `getHtml(uri, cookie)`，GZIP + GBK，用于 `GetAllForumsTask`、`JsonThreadLoadTask`（旧路径）。

### 数据解析

没有用任何自动映射，全部是 fastjson 手工遍历 + 正则清洗：

- `sp/phone/mvp/model/convert/ArticleConvertFactory.java` — 帖子详情
- `sp/phone/mvp/model/convert/TopicConvertFactory.java` — 主题列表
- `sp/phone/mvp/model/convert/MessageConvertFactory.java` — 短消息
- `sp/phone/mvp/model/convert/ForumNotificationFactory.java` — 通知
- `sp/phone/mvp/model/convert/ErrorConvertFactory.java` — 错误信息提取

## 3. 架构模式

**MVP 为主**（`sp/phone/mvp/` 下 `contract` / `model` / `presenter`，View 由 Fragment 实现）：

```
XxxContract (接口定义 View/Presenter/Model)
  ├─ XxxModel     extends BaseModel      → 发请求 + 解析
  ├─ XxxPresenter extends BasePresenter  → 业务编排
  └─ XxxFragment  extends BaseMvpFragment → UI
```
`BasePresenter` 持有 `mBaseView` / `mBaseModel`，用 RxLifecycle（`FragmentEvent.DETACH`）自动解绑。

**局部已迁到 MVVM**：`TopicListPresenter` 实际继承 `ViewModel implements LifecycleObserver`，用 `MutableLiveData` 暴露 `firstTopicList / nextTopicList / errorMsg / refreshingState / removedTopic`；`gov/anzong/androidnga/mvvm/` 下有 `MessageListModel` / `MessageListViewModel`；`ArticleShareViewModel` 用于帖子详情各页 Fragment 间共享状态（回复总数、刷新页码、缓存页码、楼主名）。

**Adapter 体系**：`ui/adapter/BaseAdapter` → `BaseAppendableAdapter`（支持翻页追加）→ `TopicListAdapter` / `ReplyListAdapter` / `ArticleListAdapter` 等；`ui/adapter/beta/` 下是一套未完全启用的新实现。

**路由**：ARouter，路由表在 `gov/anzong/androidnga/arouter/ARouterConstants.java`

| 常量 | path | 目标 |
|---|---|---|
| `ACTIVITY_PROFILE` | `/activity/profile` | ProfileActivity |
| `ACTIVITY_LOGIN` | `/activity/login` | LoginActivity |
| `ACTIVITY_POST` | `/activity/post` | PostActivity |
| `ACTIVITY_MESSAGE_POST` | `/activity/message_post` | MessagePostActivity |
| `ACTIVITY_TOPIC_CONTENT` | `/activity/topic_content` | ArticleListActivity |
| `ACTIVITY_TOPIC_LIST` | `/activity/topic_list` | TopicListActivity |
| `ACTIVITY_SEARCH` | `/activity/search` | SearchActivity |
| `ACTIVITY_NOTIFICATION` | `/activity/notification` | RecentNotificationActivity |
| `ACTIVITY_MESSAGE_LIST` | `/activity/message_list` | MessageListActivity |

`ACTIVITY_NEED_LOGIN` 数组（message_list / profile / notification / search / topic_list）配合 `ActivityInterceptor` 做登录拦截。

## 4. lib_core：BBCode → HTML 渲染管线

这是复刻工作量最大的一块，值得单独说明。

**入口**：`HtmlConvertFactory.convert(HtmlData, List<String> images)`
1. 黑名单用户 → `<h5>[屏蔽]</h5>`；内容和 alertInfo 都空 → `<h5>[隐藏]</h5>`
2. 有标题则加 `<div class='title'>`
3. `ForumDecoder.decode()` 按顺序跑四个 decoder
4. `HtmlBuilder.build()` 追加附件/贴条/签名/投票/尾部
5. 套模板 `assets/html/html_template.html`，夜间模式换 `style_dark.css`，否则 `style_light.css`

**Decoder 链**（`lib_core/.../core/decode/`）：

| Decoder | 处理内容 |
|---|---|
| `ForumBasicDecoder` | 主力。`[b] [i] [u] [del] [quote] [code] [url] [color=] [size=%] [font=] [align=] [l] [r] [h] [list] [*] [table] [tr] [td colspan/rowspan/width] [collapse] [collapse=title] [uid] [tid] [pid] [@昵称] [flash] [flash=video] [flash=audio] [lessernuke] [hip] [item]` → HTML。`[pid=a,b,c]Reply[/pid]` 会变成指向 `read.php?searchpost=1&pid=a` 的链接 |
| `ForumAlbumDecoder` | `[album]` 相册标签 |
| `ForumEmoticonDecoder` | `[s:分类:名称]` → `<img src='file:///android_asset/<分类>/<文件名>'>`。内置 5 套映射表：`ac`(45个) / `a2`(46个) / `pst`(65个) / `dt`(33个) / `pg`(15个)。ac/a2 额外加 `class='emoticon invertFilter'`（夜间模式反色） |
| `ForumImageDecoder` | `[img]./xxx[/img]` → `http://img.nga.178.com/attachments/xxx`；`[img]http...[/img]` 直出；剥离缩略图后缀 `.thumb_s/.medium/.thumb/.thumb_ss.jpg`；若表情 URL 命中 `EmoticonUtils` 映射则换成本地 asset；关图片开关时替换成 `ic_offline_image.png`；同时收集图片 URL 列表供大图浏览 |

**Builder 链**（`lib_core/.../core/corebuild/`）：`HtmlAttachmentBuilder`（附件表格，图片默认折叠为"点击显示附件"按钮，mp3/mp4 走链接）、`HtmlCommentBuilder`（贴条）、`HtmlSignatureBuilder`（签名）、`HtmlVoteBuilder`（投票）、`HtmlTailBuilder`。

**表情素材**：`lib_core/src/main/assets/` 下 `ac/`(103) `pst/`(65) `a2/`(47) `dt/`(33) `pg/`(15)，主 app 另有 `assets/xiongmao/`(54)。`lib_common/.../EmoticonUtils.java` 存了 6 套表情的**远程 URL 全表**和 `EMOTICON_LABEL`（`ac→AC娘`、`a2→新版AC娘`、`pg→企鹅`、`pst→潘斯特`、`dt→外域三人组`、`xiongmao→熊猫`），`getPathByURI()` 做远程 URL → 本地 asset 路径反查。

**骰子**：`ArticleConvertFactory.getRealDice()` 在客户端本地复刻了 NGA 的伪随机算法（种子 = authorId + tid + pid，`(seed*9301+49297)%233280`）来算 `[dice]XdY[/dice]` 结果。

**投票**：不走原生，用 WebView 加载 `assets/vote/vote.js` + `vote.css`，通过 `@JavascriptInterface ProxyBridge.postURL()` 回调发请求。

## 5. 功能清单

### 5.1 版面 / 导航

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 主页版面导航 | 侧滑抽屉 + ViewPager + TabLayout，分类展示版面 | `sp/phone/ui/fragment/NavigationDrawerFragment.java`、`BoardCategoryFragment.java`、`ui/adapter/BoardPagerAdapter.java`、`BoardCategoryAdapter.java` |
| 内置版面表 | **不走网络**，读 `assets/json/category.json`（fid/stid/name/nameS/info/head 结构） | `sp/phone/mvp/model/BoardModel.java`、`http/bean/CategoryBean.java` |
| 我的收藏（版面） | 本地 SP 存 `bookmark_board`，支持增删、拖动排序、清空 | `BoardModel.addBookmark/removeBookmark/swapBookmark`、`BoardPresenter` |
| 手动添加版面 | 输入 fid 或 stid + 名称 | `ui/fragment/dialog/AddBoardDialogFragment.java` |
| 在线版面总表 | 拉服务端完整分类树（`app_api.php`） | `activity/ForumListActivity.java`、`task/GetAllForumsTask.java`、`mvp/model/ForumsListModel.java` |
| 子版块列表 / 订阅屏蔽 | 版面页菜单"子板块"，可勾选订阅/屏蔽 | `ui/fragment/BoardSubListFragment.java`、`task/SubscribeSubBoardTask.java`、`mvp/model/entity/SubBoard.java` |
| 由 URL 读取 | 粘贴 NGA 链接跳转，正则提取 tid/pid/fid/stid | `ui/fragment/dialog/UrlInputDialogFragment.java` |
| 桌面快捷方式 | 静态 shortcut：大漩涡(fid=-7)、议事厅(fid=7) | `res/xml-v25/static_shortcuts.xml` |
| 深链 | `http(s)://{bbs.nga.cn,bbs.ngacn.cc,nga.178.com,ngabbs.com}/thread.php` 和 `/read.php` 的 intent-filter | `AndroidManifest.xml` |

### 5.2 主题列表

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 版面主题列表 | 下拉刷新 + 滚动到底自动加载下一页 | `ui/fragment/TopicListFragment.java` → `TopicSearchFragment.java`、`mvp/presenter/TopicListPresenter.java`、`mvp/model/TopicListModel.java` |
| 精华区 | 菜单项，加 `recommend=1&order_by=postdatedesc&user=1` | `TopicListActivity.showRecommendTopicList()` |
| 24 小时热帖 | 客户端行为：并发拉 5 页 → 过滤 24h 内 → 按回复数排序 → 每次吐 20 条 | `TopicListPresenter.mTwentyFourCallBack`、`TopicListModel.loadTwentyFourList()` |
| 收藏夹（主题） | `favor=1` | `TopicFavoriteFragment.java`、`MainActivity.startFavoriteTopicActivity()` |
| 我的主题 / 我的回复 | `authorid=<uid>` [+ `searchpost=1`] | `MainActivity.startPostActivity(boolean)` |
| 浏览历史 | 本地记录 | `common/TopicHistoryManager.java`、`ui/fragment/TopicHistoryFragment.java` |
| 关键字过滤 | 标题命中关键字则整条隐藏 | `common/FilterKeywordsManagerImpl.java`、`TopicConvertFactory.filter()` |
| 黑名单过滤 | 作者在黑名单则隐藏 | 同上 |
| 按发帖时间排序 | 设置开关 `sort_by_post` | `TopicConvertFactory.sort()` |
| 匿名昵称还原 | `#anony_xxxxx` → 用「甲乙丙丁…」+ 百家姓两张表按 hex 位解码成中文名 | `TopicConvertFactory.getAnonymityName()`、`ArticleConvertFactory.buildRowUserInfo()` |
| 合集识别 | `type & 32768` → 当作合集，用 stid 再开列表 | `common/ApiConstants.MASK_TYPE_ASSEMBLE`、`TopicSearchFragment.handleClickEvent()` |
| 版头 | Board 带 `head`（一个 tid），菜单"版头"打开该帖 | `TopicListFragment` / `TopicListPresenter.startArticleActivity()` |
| 取消收藏（列表内） | `topic_favor action=del` | `TopicListModel.removeTopic()` |

### 5.3 帖子详情

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 分页浏览 | ViewPager 每页 20 楼，回复总数 / 20 算页数 | `ui/fragment/ArticleTabFragment.java`、`ArticleListFragment.java`、`ui/adapter/ArticlePagerAdapter.java` |
| 内容渲染 | WebView 加载 lib_core 生成的 HTML | `ui/adapter/ArticleListAdapter.java`、`view/webview/WebViewEx.java`、`LocalWebView.java` |
| 跳转楼层/页 | 菜单"跳转" | `ui/fragment/dialog/GotoDialogFragment.java` |
| 只看此人 | `authorid` 参数重开 | `ArticleListFragment` 上下文菜单 `menu_show_this_person_only` |
| 回复 / 引用回复 | 引用会拼 `[quote][pid=..]Reply[/pid] [b]Post by [uid=..]昵称[/uid] (时间):[/b]\n内容[/quote]` | `ArticleListPresenter.quote()` |
| 贴条（评论） | 楼中楼，独立接口 | `ArticleListPresenter.postComment()`、`task/PostCommentTask.java`、`ui/fragment/dialog/PostCommentDialogFragment.java` |
| 编辑自己的帖子 | `action=modify`，仅作者可见 | `ArticleListFragment` 上下文菜单 `menu_edit` |
| 点赞 / 点踩 | value=1 / -1 | `task/LikeTask.java` |
| 举报 | 弹框填理由 | `ui/fragment/dialog/ReportDialogFragment.java`、`task/ReportTask.java` |
| 收藏主题 / 收藏楼层 | tid 或 tid+pid | `task/BookmarkTask.java` |
| 查看签名 | 弹框 WebView 渲染签名 BBCode | `FunctionUtils.Create_Signature_Dialog()` |
| 投票 / 投注 | WebView + vote.js + JS Bridge | `FunctionUtils.createVoteDialog()`、`proxy/ProxyBridge.java`、`assets/vote/vote.js` |
| 拉黑用户 | 本地黑名单 | `ArticleListPresenter.banThisSB()` |
| 缓存本页 / 离线阅读 | 存 `filesDir/cache/<tid>/<page>.json` + `<tid>.json` | `ArticleListModel.cachePage/loadCachePage`、`activity/TopicCacheActivity.java`、`ArticleCacheActivity.java`、`ui/fragment/TopicCacheFragment.java` |
| 缓存导入/导出 | zip 打包到 Downloads | `TopicListPresenter.exportCacheTopic/importCacheTopic` |
| 分享 / 复制链接 / 浏览器打开 | 系统分享 | `ArticleTabFragment.share()/copyUrl()` |
| 内置浏览器兜底 | 解析失败时（`ServerException`）自动用 WebView 打开原页 | `ArticleListPresenter.showWithWebView()`、`activity/WebViewActivity.java` |
| 多账号自动重试 | 解析失败时用下一个账号的 Cookie 重试一次 | `ArticleListPresenter.retryWithNewAccount()`、`UserManagerImpl.getNextCookie()` |
| 热门回复 | 解析字段 `"17"` | `ArticleConvertFactory.buildRowHotReplay()` |
| 用户信息标注 | (VIP=被nuke) / (禁言) / (黑名单) / (匿名) / (楼主) | `FunctionUtils.handleNickName()` |
| 客户端来源图标 | `from_client` 前缀判 ios/android/wp | `ArticleConvertFactory.buildRowClientInfo()` |

### 5.4 发帖 / 编辑器

`activity/PostActivity.java` + `ui/fragment/TopicPostFragment.java` + `mvp/presenter/TopicPostPresenter.java`。`action` 取值 `new` / `reply` / `modify`。

工具栏 `view/toolbar/ToolbarContainer.java` 挂三个面板：
- `FormattedControlPanel` — `[@] [quote] [url] [b] [i] [u] [del] [collapse] [color=] [size=%]` 插入
- `EmoticonControlPanel` — 表情选择（`EmoticonParentAdapter` 分类 + `EmoticonChildAdapter`），插入时用 `ImageSpan` 在输入框内预览
- `CategoryControlPanel` — 主题分类标签（来自 `topic_key` 接口）

其它：匿名发帖复选框（提示扣 100 铜币）、图片上传（选图 → 传附件服务器 → 插入 `[img]./xxx[/img]`，>1MB 自动压缩，`error_code==9` 再压一次）、彩虹模式（`FunctionUtils.ColorTxt()` 逐字加随机 `[color=]`，设置项 `showColortxt`，提示"有被禁言风险"）、发帖后自动刷新（`refresh_after_post_setting_mode`）、草稿保存到 `savedInstanceState`。

### 5.5 消息 / 通知

| 功能 | 关键文件 |
|---|---|
| 短消息会话列表 | `activity/MessageListActivity.java`、`ui/fragment/MessageListFragment.java`、`mvvm/model/MessageListModel.java`、`mvvm/viewmodel/MessageListViewModel.java` |
| 会话详情（分页） | `activity/MessageDetailActivity.java`、`ui/fragment/MessageDetailFragment.java`、`mvp/model/MessageDetailModel.java` |
| 发新短信 / 回复短信 | `activity/MessagePostActivity.java`、`ui/fragment/MessagePostFragment.java`、`param/MessagePostParam.java`、`task/MessagePostTask.java` |
| 提醒（被喷 / @ / 贴条 / 新短信） | `activity/RecentNotificationActivity.java`、`ui/fragment/RecentNotificationFragment.java`、`task/ForumNotificationTask.java` |
| 后台轮询 + 系统通知 | `common/NotificationController.java`，30s 节流，登录状态下触发，分"被喷提醒"和"新短消息"两条通知 |
| 一键清空提醒 | `ForumNotificationTask.clearAllNotification()` |

通知类型常量在 `common/ApiConstants.java`：1=主题回复、2=回复的回复、3=主题贴条、4=回复贴条、7=主题@、8=回复@、10=新短信、11=短信回复。

### 5.6 搜索

`activity/SearchActivity.java`，三个 Tab（`SearchHistoryTopicFragment` / `SearchHistoryBoardFragment` / `SearchHistoryUserFragment`），各自有独立的搜索历史 SP key。

- **主题搜索**：可选"本版/全站"、"是否包含正文"（`content=1`），走 `thread.php?key=`
- **版面搜索**：`forum.php?key=`（`task/SearchBoardTask.java`），找到 fid 后可加进收藏
- **用户搜索**：按用户名或 UID，直接跳 ProfileActivity

### 5.7 用户

| 功能 | 关键文件 |
|---|---|
| 资料页（威望/铜币金币银币换算/注册时间/发帖数/邮箱/手机/用户组/禁言状态/管理版面/声望明细/签名） | `activity/ProfileActivity.java`、`task/JsonProfileLoadTask.java`、`http/bean/ProfileData.java`、`ReputationData.java`、`AdminForumsData.java` |
| 修改签名 | `activity/SignPostActivity.java`、`task/SignPostTask.java`、`param/SignPostParam.java` |
| 修改头像 | `activity/AvatarPostActivity.java`、`task/AvatarFileUploadTask.java`、`param/AvatarPostAction.java` |
| 搜索该用户的主题/回复 | ProfileActivity 菜单 |
| 发短信给 TA | ProfileActivity 菜单 |
| 拉黑 / 取消拉黑 | ProfileActivity 菜单 |

> **注意**：项目中**没有"签到"功能**。`SignPostActivity` / `SignPostTask` 里的 "Sign" 指的是**签名（signature）**，接口是 `__lib=set_sign&__act=set`。`http/bean/MissionDetialData.java` 是个死代码，无任何引用。

### 5.8 登录 / 多账号

- **WebView 登录**（主推）：`ui/fragment/LoginWebFragment.java` 加载 `https://ngabbs.com/nuke.php?__lib=login&__act=account&login`，`onPause` 时从 `CookieManager` 取 Cookie 交给 `LoginPresenter.parseCookie()` 解析出 uid/cid/用户名。**提示"不支持 QQ 和微博登录"**。
- **账号密码登录**（辅助，`ui/fragment/LoginFragment.java` + `mvp/model/LoginModel.java`）：先取图形验证码，再 multipart POST。代码里成功回调只是弹了个 Toast 打印原始响应，**基本处于半废弃状态**。
- **多账号**：`common/UserManagerImpl.java` + Room（`db/AppDatabase.java`、`db/user/UserDao.java`）。侧栏 `ViewFlipperEx` 左右切换账号；设置里 `SettingsUserFragment` 管理列表（增删、拖动排序）。头像 URL 缓存在独立 SP `avatar`。

### 5.9 图片

`gallery/ImageZoomActivity.java`（PhotoView + ViewPager）、`GalleryAdapter`、`SaveImageTask`、`DownloadImageTask`。支持单张保存、**批量下载本楼所有图**、分享。`util/CustomGlideModule.java` 配置 Glide。

### 5.10 设置项完整清单（`res/xml/settings.xml`）

**通用设置**

| key | 类型 | 含义 |
|---|---|---|
| `nga_domain` | List，默认 `1` | NGA 域名（见第二部分域名表） |
| `pref_user` | 子页 | 账号管理 `SettingsUserFragment` |
| `nightmode` | Switch，false | 夜间模式 |
| `left_hand` | Switch，false | 左手模式（FAB 移到左侧） |
| `swipe_back` | Switch，false | 右滑返回 |
| `use_solid_color_bg` | Switch，true | 主题列表和详情用纯色背景 |
| `enableNotification` | Switch，true | 开启提醒 |
| `notificationSound` | Switch，true | 提醒声音（依赖上一项） |
| `material_theme` | List，1 | 主题配色 |
| `adjust_size` | 子页 | 字号调节 `SettingsSizeFragment` |
| `key_clear_cache` | 子页 | 清除缓存 |

**主题列表设置**

| key | 类型 | 含义 |
|---|---|---|
| `sort_by_post` | Switch，false | 按发帖时间排序 |
| `filter_sub_board` | Switch | 过滤子版块置顶帖（fid=-7 且 recommend>9） |
| `pref_keyword` | 子页 | 关键字屏蔽 `FilterKeywordsFragment` |

**主题详情设置**

| key | 类型 | 含义 |
|---|---|---|
| `pref_black_list` | 子页 | 黑名单管理 |
| `pref_load_pic_strategy` | List，0 | 载入帖子图片：0=总是 / 1=从不 / 2=仅WiFi |
| `pref_load_avatar_strategy` | List，0 | 载入头像，同上 |
| `bottom_tab` | Switch，false | 页码 Tab 放底部 |
| `hardware_accelerated` | Switch，true | 硬件加速 |
| `showSignature` | Switch，false | 显示签名 |
| `pref_show_with_webview` | Switch，true | 解析失败时用内置浏览器打开 |

**发帖设置**

| key | 类型 | 含义 |
|---|---|---|
| `showColortxt` | Switch，false | 彩虹模式发帖 |
| `refresh_after_post_setting_mode` | Switch，true | 发帖后刷新 |

**`SettingsSizeFragment` 可调尺寸**（`common/Constants.java`）：主题标题 18(1~25)、正文 24(1~34)、头像 104(1~140)、表情 150(1~200)、WebView textZoom 70。

**其它 SP key**（`lib_common/.../PreferenceKey.java`，不在 settings.xml 里但被代码使用）：`userList`、`user_active_index`、`bookmark_board`、`filter_keywords`、`topic_history`、`reply_count`、`search_history_topic/board/user`、`key_night_mode_follow_system`、`key_preload_board_version`、`version_code` / `previous_version_code`、`key_check_upgrade_state/time`。

### 5.11 其它

- 关于页 `activity/AboutActivity.java`（material-about-library）
- 版本升级提示 `common/VersionUpgradeHelper.java` + `VersionUpgradeTips.java` + `ui/fragment/dialog/VersionUpgradeDialogFragment.java`（本地更新日志，**不联网检查更新**）
- 主题/夜间模式 `theme/ThemeManager.java`、`ThemeConstants.java`、`WebViewTheme.java`，支持跟随系统
- 崩溃处理 `ExceptionHandlerProxy.java` + `lib_cloud` Bugly

---

# 第二部分：API 调用清单（重点）

## 0. 全局约定

### 0.1 域名与切换

`res/values/arrays.xml`：

| index | `nga_domain` | `nga_domain_no_http` |
|---|---|---|
| 0 | `https://bbs.ngacn.cc` | `bbs.ngacn.cc` |
| 1（默认） | `https://bbs.nga.cn` | `bbs.nga.cn` |
| 2 | `https://nga.178.com"` ⚠️ | `nga.178.com"` ⚠️ |
| 3 | `https://nga.donews.com` | `nga.donews.com` |
| 4 | `https://ngabbs.com` | `ngabbs.com` |

> ⚠️ index 2 的两个字符串**末尾都多了一个引号**，是仓库里存在的 bug，复刻时不要照抄。

取值入口：
- `sp/phone/util/ForumUtils.java` → `getAvailableDomain()` / `getAvailableDomainNoHttp()`，读 SP `perference` 的 `nga_domain`，默认 `"1"`
- `gov/anzong/androidnga/Utils.java` → `getNGAHost()` = 域名 + `/`；`getNGADomain()` 硬编码返回 `bbs.ngacn.cc`
- `RetrofitHelper` 构造时取一次，并注册 SP 监听，域名一变就重建 Retrofit 实例

**不受域名设置控制的硬编码地址**（复刻时建议全部改成动态域名）：

| 位置 | 地址 | 用途 |
|---|---|---|
| `RetrofitHelper.URL_NGA_BASE_CC` | `https://bbs.ngacn.cc/` | 账号密码登录、验证码 |
| `LoginModel.loadAuthCode()` | `https://bbs.ngacn.cc/login_check_code.php` | 图形验证码 |
| `LoginWebFragment.URL_LOGIN` | `https://ngabbs.com/nuke.php` | WebView 登录 |
| `SearchBoardTask` | `http://bbs.nga.cn/forum.php` | 版面搜索 |
| `SubscribeSubBoardTask` | `http://bbs.ngacn.cc/nuke.php` | 子版块订阅 |
| `AvatarPostActivity.REPLY_URL` | `http://nga.178.com/nuke.php?` | 修改头像 |
| `ProfileActivity.getUrl()` | `http://bbs.ngacn.cc/nuke.php?func=ucp&` | 仅用于"复制链接/浏览器打开" |
| `TopicPostModel.BASE_URL_ATTACHMENT_SERVER` | `https://img8.nga.cn/attach.php?` | 发帖附件上传 |
| `AvatarFileUploadTask.ATTACHMENT_SERVER` | `http://app.myauth.us/api/attach.php?` | 头像图床（**第三方，非 NGA**） |
| `HttpUtil.NGA_ATTACHMENT_HOST` / `ForumImageDecoder` | `img.nga.178.com` | 附件/图片主机 |
| `HttpUtil.Server` / `servers[]` | `https://bbs.nga.cn`；轮换池 `{https://nga.178.com, https://bbs.ngacn.cc}` | 遗留 `switchServer()` |
| `ApiConstants.URL_BOARD_ICON` | `http://img4.nga.178.com/ngabbs/nga_classic/f/app/%s.png` | 版面图标（%s = fid） |
| `ApiConstants.URL_BOARD_ICON_STID` | `https://img4.nga.178.com/proxy/cache_attach/ficon/%sv.png` | 合集图标（%s = stid） |

### 0.2 认证

**Cookie 是唯一凭证**，格式（`UserManagerImpl.getCookie()`）：

```
Cookie: ngaPassportUid=<uid>; ngaPassportCid=<cid>
```

由 OkHttp 拦截器统一注入（`RetrofitHelper.createOkHttpClientBuilder()`）；若 Request 已带 `Cookie` header 则不覆盖 —— 多账号重试就是靠这个机制传入 `getNextCookie()`。

**Cookie 获取方式**：

1. **WebView 登录（主）**：加载 `https://ngabbs.com/nuke.php?__lib=login&__act=account&login`，登录成功后 `CookieManager.getInstance().getCookie(url)` 里会有：
   - `ngaPassportUid` — 用户 ID
   - `ngaPassportCid` — 会话凭证
   - `ngaPassportUrlencodedUname` — 用户名，**需要 URLDecode 两次，字符集 GBK**（`LoginPresenter.parseCookie()`）

2. **账号密码登录（辅）**：
   - 先 GET `https://bbs.ngacn.cc/login_check_code.php?id=_<Math.random()>/` 拿验证码 PNG（`Referer: https://bbs.ngacn.cc/nuke.php?__lib=login&__act=login_ui`），转 base64 显示
   - 再 POST `https://bbs.ngacn.cc/nuke.php`（multipart/form-data），字段：`name` / `type=name` / `password` / `rid=<验证码id>` / `captcha=<大写验证码>` / `__lib=login` / `__act=login` / `__output=1` / `__inchst=UTF-8` / `raw=3` / `qrkey=`

**游客访问**：没有 `guestJs` 之类的专门机制。未登录时 `getCookie()` 返回空串，请求照发，服务端返回 `{"error":{"0":"未登录"}}`，`ErrorConvertFactory` 把含"未登录"的响应统一映射成"请重新登录"。

**User-Agent**（服务端会校验，必须伪装）：
- Retrofit 路径：`Nga_Official/80023`（`RetrofitHelper` 硬编码）
- HttpURLConnection 路径：`Nga_Official/<versionCode>([<机型>];Android<系统版本>)`，机型 <19 字符时用 `[]` 包起来（`HttpPostClient` / `HttpUtil.getHtml`）
- 账号密码登录：伪装成 Chrome 66 桌面浏览器（见 `RetrofitService` 的 `@Headers`）

### 0.3 公共查询参数

| 参数 | 取值 | 含义 |
|---|---|---|
| `__lib` | `login` / `message` / `noti` / `ucp` / `topic_favor` / `topic_recommend` / `topic_key` / `set_sign` / `log_post` / `user_option` / `vote` | 服务端功能库 |
| `__act` | 与 `__lib` 配套的动作 | 动作名 |
| `__output` | `8`（JSON） / `1`（登录用） | 输出格式；`8` 是最常用的 JSON |
| `lite` | `js` / `htmljs` | 精简输出，返回 JS 变量赋值 |
| `noprefix` | 无值 flag | 去掉 `window.script_muti_get_var_store=` 前缀（**不总是生效**，解析器仍需容错） |
| `raw` | `3` | 原始输出 |
| `v2` | 无值 / `1` | 新版数据结构 |
| `__inchst` | `UTF-8` | 声明入参编码（仅登录用；**其余接口都是 GBK**） |
| `charset` | `gbk` | 声明 POST 表单编码，OkHttp 拦截器识别到这个值就切 GBK Content-Type |
| `page` | 整数 | 页码，从 1 开始 |

参数语义说明在 `sp/phone/param/ParamKey.java` 里有注释，值得直接参考。

### 0.4 响应格式与解析

**编码**：响应体一律按 **GBK** 解码（`JsonStringConvertFactory` / `IOUtils.toString(is,"gbk")`）。

**外壳**：可能带前缀 `window.script_muti_get_var_store=`，需先剥离。

**结构**：
```json
{
  "data": { },
  "error": { "0": "错误信息" },
  "time": 1601530856
}
```
`data` 与 `error` 互斥。`data` 内部大量用 **字符串数字键**（`"0"`, `"1"`, `"2"`…）当数组用，fastjson 只能当 `JSONObject` 手动遍历。

**必做的清洗步骤**（各 ConvertFactory 里重复出现，建议复刻时抽成一个函数）：
```java
js = js.replaceAll("window.script_muti_get_var_store=", "");
if (js.indexOf("/*error fill content") > 0)
    js = js.substring(0, js.indexOf("/*error fill content"));
js = js.replaceAll("/\\*\\$js\\$\\*/", "");
js = js.replaceAll("\"content\":\\+(\\d+),",  "\"content\":\"+$1\",");   // +123 不是合法 JSON
js = js.replaceAll("\"subject\":\\+(\\d+),",  "\"subject\":\"+$1\",");
js = js.replaceAll("\"content\":(0\\d+),",    "\"content\":\"$1\",");     // 前导 0 不是合法 JSON
js = js.replaceAll("\"subject\":(0\\d+),",    "\"subject\":\"$1\",");
js = js.replaceAll("\"author\":(0\\d+),",     "\"author\":\"$1\",");
js = js.replaceAll("\"alterinfo\":\"\\[(\\w|\\s)+\\]\\s+\",", "");        // 修复部分页面打不开
```
（`ArticleConvertFactory.parseJsonThreadPage()` 是最全的一版）

**编码陷阱汇总**（这是复刻最容易翻车的点）：
- 响应：GBK
- POST 表单体：GBK urlencode（`StringUtils.encodeUrl(s, "GBK")`）
- `thread.php` 的 `author` 参数：GBK urlencode
- `thread.php` 的 `key` 参数：**UTF-8** urlencode（和 author 不一致，`TopicListModel.getUrl()` 第 255 行）
- `forum.php` 的 `key` 参数：GBK
- `nuke.php?func=ucp&username=`：GBK
- 登录接口：`__inchst=UTF-8`
- 附件上传的 `attachment_file1_url_utf8_name`：UTF-8

---

## 1. 版面 / 分类

### 1.1 获取完整版面分类树

```
GET  {host}/app_api.php?__lib=home&__act=category
```
- 文件：`sp/phone/task/GetAllForumsTask.java`
- 走 `HttpUtil.getHtml()`（HttpURLConnection，GBK）
- 响应：`{code, msg, result:[{id, name, groups:[{id, name, forums:[{id, name, stid}]}]}]}`
- 映射类：`sp/phone/mvp/model/ForumsListModel.java`
- 注意：这是**唯一一个返回标准 JSON**（有 `code`/`msg`）的接口，格式和其余接口完全不同

### 1.2 按名称搜索版面

```
GET  http://bbs.nga.cn/forum.php?&__output=8&key={GBK urlencode(版面名)}
```
- 文件：`sp/phone/task/SearchBoardTask.java`
- 响应：`data["0"] = {fid: int, name: string}`

### 1.3 子版块订阅 / 屏蔽

```
POST http://bbs.ngacn.cc/nuke.php?__lib=user_option&__act=set&raw=3&type={type}&__output=8&fid={parentFid}&{add|del}={fid或tid}
```
- 文件：`sp/phone/task/SubscribeSubBoardTask.java`
- `type`：来自 `SubBoard.getType()`，0 = 普通子版块（fid），1 = 合集型（用 tid 字符串）
- **动作名是反的**：`type==1` 时订阅用 `del`、取消用 `add`；其它 type 反过来（源码注释："NGA 后台好变态啊，某个板块的操作居然是反的"）
- POST 但无 body，参数全在 query
- 成功判定：响应包含"成功"
- 订阅状态判定：`ForumUtils.isBoardSubscribed(statusCode)` —— `7 / 558 / 542 / 2606 / 2590 / 4654` 视为已订阅（这是从数据里试出来的魔数）

### 1.4 版面图标

```
GET  http://img4.nga.178.com/ngabbs/nga_classic/f/app/{fid}.png
GET  https://img4.nga.178.com/proxy/cache_attach/ficon/{stid}v.png
```
`sp/phone/common/ApiConstants.java`

---

## 2. 主题列表 `thread.php`

**唯一入口**：`sp/phone/mvp/model/TopicListModel.getUrl(int page, TopicListParam)`

```
GET {host}/thread.php?[authorid=N&][searchpost=N&][favor=N&][content=N&]
                      ( author={GBK urlencode}& | [stid=N&|fid=N&][key={UTF-8 urlencode}&][fidgroup=S&] )
                      page=N&lite=js&noprefix[&recommend=1&order_by=postdatedesc&user=1]
```

参数表（对应 `sp/phone/param/TopicListParam.java` / `ParamKey.java`）：

| 参数 | 类型 | 含义 |
|---|---|---|
| `fid` | int | 版面 ID，可为负（如 `-7` = 大漩涡）。也支持逗号分隔多版面 |
| `stid` | int | 合集 ID。**与 fid 互斥，stid 优先** |
| `authorid` | int | 按作者 UID 筛选（"我的主题"） |
| `searchpost` | int | `1` = 搜回复而非主题（"我的回复"），返回结构里带 `__P` 子对象 |
| `favor` | int | `1` = 我的收藏夹 |
| `content` | int | `1` = 搜索包含正文 |
| `author` | string | 按作者名筛选，**GBK urlencode**；带 `&searchpost=1` 后缀时会被特殊拆分 |
| `key` | string | 搜索关键字，**UTF-8 urlencode** |
| `fidgroup` | string | 版面组；`user` = 全部用户版；不传 = 全部非用户版 |
| `page` | int | 页码 |
| `recommend` | int | `1` = 精华区，此时额外附加 `order_by=postdatedesc&user=1` |
| `lite=js&noprefix` | flag | 固定 |

> `twentyfour`（24 小时热帖）**不是服务端参数**，是客户端拉 5 页再本地过滤排序。

**响应结构**（参考 `http/bean/TopicListBean.java` 的注释样例）：
```json
{"data":{
  "__CU":{"uid":0,"group_bit":622816,"admincheck":"","rvrc":-10},
  "__F":{"fid":275,"name":"版面名","topped_topic":"","sub_forums":{}},
  "__ROWS":2,
  "__T":{"0":{
      "tid":11915941,"fid":275,"author":"xxx","authorid":"0",
      "subject":"标题","type":516,"postdate":1498529634,"lastpost":1499236460,
      "lastposter":"yyy","replies":13,"recommend":1,"titlefont":"","topic_misc":"",
      "tpcurl":"/read.php?tid=11915941&fav=c7cf9a59",
      "parent":{"0":275,"2":"父版面名"},
      "__P":{"pid":0,"authorid":0,"content":"..","postdate":0}
  }},
  "__T__ROWS":1,"__T__ROWS_PAGE":35,"__R__ROWS_PAGE":20
},"time":0}
```
（`__P` 仅在 `searchpost=1` 时存在）

解析要点（`TopicConvertFactory`）：
- `tid` 优先从 `tpcurl` 里正则提取（`tpcurl` 更可靠）
- `author` 以 `#anony_` 开头 → 匿名，用 hex 解码成中文名
- `__F.sub_forums` 是子版块 map，key 以 `t` 开头表示合集（用 stid），含 `"3"` 键表示 type=1，`"4"` 键是订阅状态码
- `type` 位掩码（`ApiConstants`）：`1024`=锁定、`8192`=有附件、`32768`=合集
- `titlefont` 位掩码：1=红 2=蓝 4=绿 8=橙 16=银 32=粗 64=斜 128=下划线

### 2.1 取消收藏 / 删除收藏项

```
POST {host}/nuke.php
Content-Type: application/x-www-form-urlencoded
Body: __lib=topic_favor&__act=topic_favor&__output=8&action=del&page={page}&tidarray={tid}[_{pid}]
```
- 文件：`sp/phone/mvp/model/TopicListModel.removeTopic()`
- `tidarray` 格式：只有 tid 时是 `12345`；带 pid 时是 `12345_67890`
- 成功判定：响应包含"操作成功"

---

## 3. 帖子详情 `read.php`

```
GET {host}/read.php?&page={page}&__output=8&noprefix&v2[&tid=N][&pid=N][&authorid=N]
```
- 文件：`sp/phone/mvp/model/ArticleListModel.getUrl()` / `sp/phone/param/ArticleListParam.java`
- 参数：`tid` 主题 ID、`pid` 楼层 ID（只看某楼/从提醒跳转）、`authorid` 只看此人、`page` 页码（每页 20 楼）
- 注意 URL 里 `?` 后直接跟 `&page=`，是源码原样（服务端容忍）

**响应结构**：
```json
{"data":{
  "__ROWS": 128,
  "__R__ROWS": 20,
  "__T": { },
  "__R": { "0": {}, "1": {} },
  "__U": { "<uid>": {}, "__GROUPS": {"<memberid>": {"0":"用户组名"}} }
}}
```
- `__ROWS` = 回复总数，用来算总页数 `ceil(n/20)`
- `__R__ROWS` = 本页楼层数
- `__T` = 主题信息，映射到 `ThreadPageInfo`
- `__R` = 楼层列表（字符串数字键）
- `__U` = 用户信息表

**楼层对象字段**（`http/bean/ThreadRowInfo.java`）：`tid` `fid` `pid` `lou`(楼层号) `author` `authorid` `subject` `content` `postdate` `alterinfo`(编辑记录) `vote`(投票原始串) `attachs`(Map<String,Attachment>) `comment`(贴条，嵌套同结构) `from_client` `score` `score_2` `"17"`(热门回复 pid 逗号串)

**用户对象字段**（在 `__U` 里，`ArticleConvertFactory.buildRowUserInfo()`）：`username`（`#anony_` 开头则匿名）、`avatar`（`js_escap_avatar`，是个 JSON 串，需 `FunctionUtils.parseAvatarUrl()` 抠出第一个 http URL）、`yz`（`-1` = 被 nuke）、`mute_time`、`rvrc`（声望，除以 10 显示）、`signature`、`postnum`、`memberid`、`buffs`（含 `117` 或 `105` 表示禁言，见 `ForumConstants.BUFF_MUTE_IDS`）

**附件 URL 拼装**：`http://img.nga.178.com/attachments/{attachurl}`，`thumb=="1"` 时缩略图加 `.thumb.jpg` 后缀。

---

## 4. 发帖 / 回复 / 编辑 `post.php`

### 4.1 获取发帖上下文（附件 auth code）

```
POST {host}/post.php?fid={fid}&lite=js[&action={action}][&pid=N][&tid=N][&stid=N]
```
- 文件：`sp/phone/mvp/model/TopicPostModel.getPostInfo()`
- POST 无 body，参数全在 query
- 响应：`{"data":{"auth":"<token>"}}` → `http/bean/TopicPostBean.java`，`auth` 是上传附件必需的凭证
- 失败提示："获取附件验证码失败，将无法上传附件！！"

### 4.2 提交发帖 / 回复 / 编辑

```
POST {host}/post.php?
Content-Type: application/x-www-form-urlencoded
Accept-Charset: GBK
Body: step=2&post_content={GBK urlencode}
      [&pid=N][&tid=N][&action={new|reply|modify}]
      [&post_subject={GBK urlencode}][&fid=N][&anony=1]
      [&attachments={A}&attachments_check={B}][&stid=N]
```
- 文件：`sp/phone/param/PostParam.toString()`（拼 body）+ `sp/phone/task/TopicPostTask.java`（发送，走 `HttpPostClient`）
- `action` 取值：`new` 发新帖 / `reply` 回复 / `modify` 编辑（编辑需带 pid）
- `anony=1` 匿名（扣 100 铜币）
- 多个附件时 `attachments` 用 **GBK urlencode 的 `\t`**（即 `%09`）分隔，`attachments_check` 同理（`PostParam.appendAttachment()`）
- **响应是 HTML 不是 JSON**：从 `<span style='color:#aaa'>&gt;</span>` 到 `<br/>` 之间截取结果文本
- 成功判定：结果含 `发贴完毕` 或 `@提醒每24小时不能超过50个`

### 4.3 贴条（楼中楼评论）

```
POST {host}/post.php
Body: post_content={GBK urlencode}&tid=N&pid=N&fid=N&nojump=1&step=2&action=reply&comment=1&lite=htmljs[&anony=1]
```
- 文件：`sp/phone/task/PostCommentTask.java`
- 与普通回复的区别：`comment=1` + `nojump=1` + `lite=htmljs`
- 响应：HTML 里夹 `window.script_muti_get_var_store=...</script>`，取 `data.__MESSAGE`，`__MESSAGE["3"]==200` 且 `__MESSAGE["1"]` 含 `发贴完毕` 即成功（UI 上把"发贴完毕"文案替换成"贴条成功"）

### 4.4 获取主题分类标签

```
GET {host}/nuke.php?__lib=topic_key&__act=get&fid={fid}&__output=8
```
- 文件：`sp/phone/mvp/model/TopicPostModel.loadTopicCategory()`
- 响应：`data["0"]["0"]["0"]`、`data["0"]["1"]["0"]`… 依次取分类名字符串

### 4.5 上传发帖附件

```
POST https://img8.nga.cn/attach.php?
Content-Type: multipart/form-data
```
表单字段（`TopicPostModel.buildMultipartBody()`）：

| 字段 | 值 | 说明 |
|---|---|---|
| `attachment_file1` | 文件二进制 | filename 用 `contentType.replace('/','.')`，如 `image.jpeg`；Content-Type 固定 `image/jpeg` |
| `attachment_file1_url_utf8_name` | 文件名 UTF-8 | |
| `fid` | 版面 ID | |
| `auth` | 4.1 拿到的 auth | **必需** |
| `func` | `upload` | |
| `v2` | `1` | |
| `lite` | `js` | |
| `attachment_file1_auto_size` | `""` | `1` = 自动缩图 |
| `attachment_file1_watermark` | `""` | 水印位置 `tl`/`tr`/`bl`/`br`，空 = 无水印 |
| `attachment_file1_dscp` | `""` | 描述 |
| `attachment_file1_img` | `1` | 标记为图片 |
| `origin_domain` | `bbs.ngacn.cc` | 来源域名 |

- 响应：`{"data":{"attachments":"...","attachments_check":"...","url":"..."}}`，`url` 是相对路径，插入正文为 `[img]./{url}[/img]`
- `error_code == 9` = 附件过大，客户端自动压缩后重传一次（`ImageUtils.fitImageToUpload()`，>1MB 才压）
- 该请求的 OkHttpClient connectTimeout 放宽到 5 分钟

---

## 5. 收藏

### 5.1 收藏主题 / 收藏楼层

```
POST {host}/nuke.php?__lib=topic_favor&lite=js&noprefix&__act=topic_favor&action=add&tid={tid}[&pid={pid}]
```
- 文件：`sp/phone/task/BookmarkTask.java`
- POST 无 body
- 响应文本里用字符串截取取 `{"0":"` 到 `"},"time"` 之间的提示语直接 Toast

### 5.2 取消收藏

见 2.1（`action=del` + `tidarray`）。

### 5.3 查看收藏夹

见第 2 节，`thread.php?favor=1&page=N&lite=js&noprefix`。

---

## 6. 点赞 / 点踩

```
POST {host}/nuke.php
Content-Type: application/x-www-form-urlencoded
Body: __lib=topic_recommend&__act=add&raw=3&__output=8&tid={tid}&pid={pid}&value={1|-1}
```
- 文件：`sp/phone/task/LikeTask.java`
- `value=1` 赞（SUPPORT），`value=-1` 踩（OPPOSE）
- `pid` 默认 `0`（对主题操作）
- 响应：`data["0"]` 是提示文案

---

## 7. 举报

```
POST {host}/nuke.php?__lib=log_post&__act=report&__output=8&charset=gbk
Content-Type: application/x-www-form-urlencoded;charset=GBK
Body: __output=8&__lib=log_post&__act=report&charset=gbk&pid={pid}&tid={tid}&info={举报理由}
```
- 文件：`sp/phone/ui/fragment/dialog/ReportDialogFragment.java` + `sp/phone/task/ReportTask.java`
- **query 和 form 都带一遍** `__lib`/`__act`/`__output`/`charset`（用的是 `post(@QueryMap, @FieldMap)`）
- `charset=gbk` 触发 OkHttp 拦截器切 GBK Content-Type
- 响应：`{"error":{"0":"你在217秒后方可举报"}}` 或 `{"data":{"0":"操作成功"},"time":1601530856}`

---

## 8. 投票 / 投注

```
POST {host}/nuke.php?{params}
Body: {params}
```
（query 和 body 内容相同）参数由 `assets/vote/vote.js` 生成，通过 `window.ProxyBridge.postURL()` 传给 native：

| 动作 | 参数串 |
|---|---|
| 投票 | `__lib=vote&raw=3&lite=js&__act=vote&tid={tid}&voteid={id1,id2,...}` |
| 结算/开奖 | `__lib=vote&raw=3&lite=js&__act=settle&tid={tid}&voteid={id1,id2,...}` |

- 文件：`sp/phone/proxy/ProxyBridge.java`（走 `HttpPostClient`，GBK 读取）
- 响应：取 `data["0"]` 或 `error["0"]`，以"操作成功"开头即成功
- 投票原始数据来自帖子详情楼层的 `vote` 字段，是 `~` 分隔的 key-value 串，由 `vote.js` 解析渲染

---

## 9. 短消息

### 9.1 会话列表

```
GET {host}/nuke.php?__lib=message&__act=message&act=list&lite=js&page={page}
```
- 文件：`gov/anzong/androidnga/mvvm/model/MessageListModel.java`
- 响应：`data["0"]` 里有 `nextPage` / `currentPage` / `rowsPerPage`，以及 `"0"`, `"1"`… 每项字段：`mid`（会话 ID）、`posts`（消息数）、`subject`、`from_username`、`last_from_username`、`time`、`last_modify`（都是 Unix 时间戳）
- 解析：`MessageConvertFactory.getMessageListInfo()` → `http/bean/MessageListInfo.java` / `MessageThreadPageInfo.java`

### 9.2 会话详情

```
GET {host}/nuke.php?__lib=message&__act=message&act=read&lite=js&page={page}&mid={mid}
```
- 文件：`sp/phone/mvp/model/MessageDetailModel.java`
- 解析：`MessageConvertFactory.getMessageDetailInfo()` → `sp/phone/util/MessageUtil.java` → `http/bean/MessageDetailInfo.java` / `MessageArticlePageInfo.java`
- 特殊清洗：`[img]./mon_` → `[img]http://img6.nga.178.com/attachments/mon_`

### 9.3 发送 / 回复短信

```
POST {host}/nuke.php?
Content-Type: application/x-www-form-urlencoded
Body: __lib=message&__act=message&lite=js&act={new|reply}
      &to={GBK urlencode 收件人}&mid={mid}
      &subject={GBK urlencode}&content={GBK urlencode}
```
- 文件：`sp/phone/param/MessagePostParam.toString()` + `sp/phone/task/MessagePostTask.java`（走 `HttpPostClient`）
- 收件人多个用 `,` 分隔，代码会先把中文逗号 `，` 替换成 `,`
- `act=new` 时 `mid=0`；`act=reply` 时 `mid` 为会话 ID
- 成功判定：`data["0"]` 含 `发送完毕 ...` 或 ` @提醒每24小时不能超过50个` 或 `操作成功`

---

## 10. 提醒 / 通知

### 10.1 拉取所有提醒

```
GET {host}/nuke.php?__lib=noti&__output=8&__act=get_all
```
- 文件：`sp/phone/task/ForumNotificationTask.java`
- 响应：`data["0"]` 下：
  - `unread`：未读数（>0 表示有未读）
  - `"0"`：**数组**，回复/@/贴条类提醒。每项是数字键对象：`"0"`=type、`"1"`=uid、`"2"`=用户名、`"5"`=标题、`"6"`=tid、`"7"` 或 `"8"`=pid、`"9"`=时间戳
  - `"1"`：**数组**，短消息类提醒。`"0"`=type、`"2"`=用户名
- 解析：`mvp/model/convert/ForumNotificationFactory.java` → `mvp/model/entity/RecentReplyInfo.java` / `NotificationInfo.java`
- type 含义见 `ApiConstants`（1/2=回复，3/4=贴条，7/8=@，10/11=短信）

### 10.2 清空所有提醒

```
POST {host}/nuke.php?__lib=noti&raw=3&__act=del
```
POST 无 body。

---

## 11. 用户资料

### 11.1 获取资料

```
GET {host}/nuke.php?__lib=ucp&__act=get&lite=js&noprefix&{uid=N | username={GBK urlencode}}
Referer: {host}/nuke.php?func=ucp&lite=jsx&{同样的参数}
```
- 文件：`sp/phone/task/JsonProfileLoadTask.java`
- **Referer 是必须的**（否则接口会拒绝）
- 参数二选一：`uid=<数字>` 或 `username=<GBK urlencode 用户名>`；若用户名以 `UID` 开头则截取后面数字当 uid 用
- 响应：`data["0"]` 下字段：

| 字段 | 含义 |
|---|---|
| `uid` / `username` | 用户 ID / 用户名 |
| `money` | 铜币总数（÷10000=金，余÷100=银，余=铜） |
| `fame` | 威望（显示时 ÷10） |
| `posts` | 发帖数 |
| `email` / `phone` | 邮箱 / 手机 |
| `group` | 用户组 |
| `regdate` | 注册时间戳 |
| `sign` | 签名（BBCode） |
| `avatar` | 头像 JSON 串 |
| `verified` | `-1` = NUKED，`< -1` = 禁言 |
| `muteTime` | 禁言到期时间戳 |
| `buffs` | 含 `"0"` 键则禁言，值为禁言描述 |
| `reputation` | 各版面声望，`{"0":{"0":"版面名","1":"数值"}}` |
| `adminForums` | 管理的版面，`{"<fid>":"<版面名>"}` |

- 解析失败时错误信息取 `error["0"]`，兜底文案"二哥玩坏了或者你需要重新登录"

### 11.2 修改签名

```
POST {host}/nuke.php
Content-Type: application/x-www-form-urlencoded;charset=GBK
Body: __lib=set_sign&__act=set&raw=3&lite=js&charset=gbk&uid={uid}&sign={GBK urlencode}
```
- 文件：`sp/phone/task/SignPostTask.java`
- 成功判定：响应含"操作成功"

### 11.3 修改头像

```
POST http://nga.178.com/nuke.php?
Content-Type: application/x-www-form-urlencoded
Body: lite=js&noprefix&func=avatar&icon={GBK urlencode 图片URL}&__ngaClientChecksum={checksum}
```
- 文件：`gov/anzong/androidnga/activity/AvatarPostActivity.java` + `sp/phone/param/AvatarPostAction.java`
- `__ngaClientChecksum` = `MD5(uid + <R.string.checksecret> + 秒级时间戳) + 秒级时间戳`（`FunctionUtils.getngaClientChecksum()`）—— **`checksecret` 在 strings.xml 里，复刻时需自行取得**
- 成功判定：`data["0"]` 含"操作成功 你可能需要重新登录以显示新的头像"
- 头像图片本身先上传到 **第三方图床** `http://app.myauth.us/api/attach.php?`（multipart，字段 `v2=1` / `attachment_file1_watermark` / `attachment_file1_dscp` / `attachment_file1_url_utf8_name` / `fid=-7` / `func=upload` / `attachment_file1_img=1` / `origin_domain=bbs.ngacn.cc` / `lite=js` + 文件），响应 `{error, errorinfo, data:"<url>"}`。上传前若尺寸 >255×180 会先缩到 PNG。**这个域名早已失效，复刻时应换成 NGA 自己的 attach.php**

---

## 12. 登录

### 12.1 WebView 登录（推荐）

```
GET https://ngabbs.com/nuke.php?__lib=login&__act=account&login
```
在 WebView 里完成，登录后从 `CookieManager` 取 `ngaPassportUid` / `ngaPassportCid` / `ngaPassportUrlencodedUname`。文件：`sp/phone/ui/fragment/LoginWebFragment.java` + `sp/phone/mvp/presenter/LoginPresenter.parseCookie()`。

### 12.2 图形验证码

```
GET https://bbs.ngacn.cc/login_check_code.php?id=_{Math.random()}/
Referer: https://bbs.ngacn.cc/nuke.php?__lib=login&__act=login_ui
```
返回 PNG 二进制，转 base64 用 `data:image/png;base64,` 显示。`id` 的值同时作为登录时的 `rid`。文件：`sp/phone/mvp/model/LoginModel.loadAuthCode()`。

### 12.3 账号密码登录

```
POST https://bbs.ngacn.cc/nuke.php
Content-Type: multipart/form-data
Referer: https://bbs.ngacn.cc/nuke/p2.htm?login
```
字段：`name` / `type=name` / `password` / `rid` / `captcha`（大写） / `__lib=login` / `__act=login` / `__output=1` / `__inchst=UTF-8` / `raw=3` / `qrkey=`

`RetrofitService` 里还有一个 form-urlencoded 变体，路径写死为 `nuke.php?__lib=login&__act=login&raw=3`，带完整浏览器伪装 header（Chrome 66 UA、Origin、Accept、Upgrade-Insecure-Requests）。文件：`sp/phone/mvp/model/LoginModel.login()` + `sp/phone/http/retrofit/RetrofitService.java:53-65`。

> 这条路径在代码里没有做成功/失败判定（`onNext` 里只 `showToast(s)` 打印原始响应），**实际不可用**，复刻时建议只做 WebView 登录。

---

## 13. 其它资源地址

| 用途 | URL |
|---|---|
| 附件 / 正文图片 | `http://img.nga.178.com/attachments/{path}` |
| 短信内图片 | `http://img6.nga.178.com/attachments/{path}` |
| 表情（远程） | `http://img4.nga.cn/ngabbs/post/smile/{a2,pg}XX.png`、`http://img4.nga.178.com/ngabbs/post/smile/{pt,dt}XX.png`、`http://img.nga.178.com/attachments/mon_YYYYMM/DD/xxx.png`（AC娘旧版）。全表见 `lib_common/.../EmoticonUtils.EMOTICON_URL` |
| 音频附件 | `http://img.ngacn.cc/attachments{path}&filename=nga_audio.mp3` |
| 视频附件 | `http://img.ngacn.cc/attachments{path}` |
| 用户主页（外链用） | `{host}/nuke.php?func=ucp&username={name}` 或 `&uid={uid}` |
| 帖子外链 | `{host}/read.php?tid={tid}` / `?pid={pid}` / `?searchpost=1&pid={pid}` |
| 版面外链 | `{host}/thread.php?fid={fid}` / `?stid={stid}` |

---

## 14. 复刻时的重点提醒

按踩坑概率排序：

1. **编码是第一大坑**。响应 GBK、POST body GBK urlencode、但 `thread.php` 的 `key` 是 UTF-8、登录是 `__inchst=UTF-8`。不统一，必须逐接口对照。
2. **响应不是合法 JSON**。必须先剥 `window.script_muti_get_var_store=`、截掉 `/*error fill content` 之后的内容、去掉 `/*$js$*/`、修 `"content":+123` 和 `"content":0123` 这类非法数字字面量。
3. **User-Agent 必须伪装**成 `Nga_Official/xxxxx`，否则服务端会拒。
4. **`data` 里用字符串数字键当数组**，任何自动 JSON 映射库都会失败，必须手工遍历。
5. **`noprefix` 不总生效**，解析器必须容忍前缀存在与否两种情况。
6. **`nuke.php` 的 POST 常常参数在 query 而 body 为空**（用的是 `@POST @Url`），别想当然放进 form。
7. **`post.php` 的响应是 HTML 不是 JSON**，靠字符串截取判定成败。
8. **匿名昵称还原**要照抄那两张字符表和 hex 解码逻辑，否则显示成 `#anony_xxxxx`。
9. **子版块订阅的 add/del 语义按 type 反转**，别按字面理解。
10. **`__ngaClientChecksum`（改头像）依赖 `R.string.checksecret`**，是个未公开的盐值，源码 strings.xml 里有，复刻时需要取出来。
11. 硬编码到 `bbs.ngacn.cc` / `nga.178.com` / `app.myauth.us` 的那几处（登录、验证码、版面搜索、子版块订阅、改头像、头像图床）**在原项目里是历史遗留**，很多已失效，复刻时应统一走可配置域名。
12. `res/values/arrays.xml` 里 `nga.178.com"` 多出的引号是 bug，别抄。

---

## 附：核心文件索引（绝对路径，前缀省略为 `<REPO>` = `/private/tmp/claude-501/-Users-cola-Documents-code-ng2/7a950992-64a1-451b-9da6-a65f88a13d7c/scratchpad/NGA-CLIENT-VER-OPEN-SOURCE`）

**网络层**
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/http/retrofit/RetrofitService.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/http/retrofit/RetrofitHelper.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/http/retrofit/converter/JsonStringConvertFactory.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/param/HttpPostClient.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/util/HttpUtil.java`
- `<REPO>/lib_network/src/main/java/gov/anzong/androidnga/http/OnHttpCallBack.java`

**域名与认证**
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/util/ForumUtils.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/gov/anzong/androidnga/Utils.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/common/UserManagerImpl.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/mvp/presenter/LoginPresenter.java`
- `<REPO>/nga_phone_base_3.0/src/main/res/values/arrays.xml`

**业务 API**
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/mvp/model/{ArticleListModel,TopicListModel,TopicPostModel,MessageDetailModel,MessagePostModel,LoginModel,BoardModel,ForumsListModel,UserModel,BaseModel}.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/gov/anzong/androidnga/mvvm/model/MessageListModel.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/task/*.java`（17 个：BookmarkTask / ForumNotificationTask / GetAllForumsTask / JsonProfileLoadTask / JsonThreadLoadTask / LikeTask / MessagePostTask / PostCommentTask / ReportTask / SearchBoardTask / SignPostTask / SubscribeSubBoardTask / TopicPostTask / AvatarFileUploadTask / ChangeAvatarLoadTask / DownloadImageTask / BaseRxTask）
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/param/{PostParam,MessagePostParam,AvatarPostAction,SignPostParam,TopicListParam,ArticleListParam,LoginParam,ParamKey}.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/proxy/ProxyBridge.java`
- `<REPO>/nga_phone_base_3.0/src/main/assets/vote/vote.js`

**解析**
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/mvp/model/convert/{ArticleConvertFactory,TopicConvertFactory,MessageConvertFactory,ForumNotificationFactory,ErrorConvertFactory}.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/http/bean/*.java`（20 个 bean）

**渲染**
- `<REPO>/lib_core/src/main/java/gov/anzong/androidnga/core/HtmlConvertFactory.java`
- `<REPO>/lib_core/src/main/java/gov/anzong/androidnga/core/decode/{ForumDecoder,ForumBasicDecoder,ForumEmoticonDecoder,ForumImageDecoder,ForumAlbumDecoder,IForumDecoder}.java`
- `<REPO>/lib_core/src/main/java/gov/anzong/androidnga/core/corebuild/{HtmlBuilder,HtmlAttachmentBuilder,HtmlCommentBuilder,HtmlSignatureBuilder,HtmlVoteBuilder,HtmlTailBuilder}.java`
- `<REPO>/lib_core/src/main/assets/html/{html_template.html,style_light.css,style_dark.css,script.js}`
- `<REPO>/lib_common/src/main/java/gov/anzong/androidnga/common/util/EmoticonUtils.java`

**配置**
- `<REPO>/lib_common/src/main/java/gov/anzong/androidnga/common/PreferenceKey.java`
- `<REPO>/nga_phone_base_3.0/src/main/java/sp/phone/common/{PhoneConfiguration,Constants,ApiConstants,ForumConstants}.java`
- `<REPO>/nga_phone_base_3.0/src/main/res/xml/settings.xml`
- `<REPO>/nga_phone_base_3.0/src/main/AndroidManifest.xml`
- `<REPO>/nga_phone_base_3.0/src/main/assets/json/category.json`
