# MNGA（BugenZhao/NGA）深度调研报告

> 供复刻 NGA 第三方客户端参考
>
> 代码根目录（下文简称 `$ROOT`）：
> `/private/tmp/claude-501/-Users-cola-Documents-code-ng2/7a950992-64a1-451b-9da6-a65f88a13d7c/scratchpad/MNGA`
>
> 项目性质：iOS/iPadOS 的 NGA 论坛第三方客户端，SwiftUI 前端 + Rust 逻辑层 + Protobuf 通信。
> **注意：本项目 NO LICENSE，未经明确许可不可修改或再分发其代码。**

---

# 第 0 部分：整体架构

## 0.1 三层结构

```
┌─────────────────────────────────────────────────────────┐
│  SwiftUI (app/)                                          │
│  Views / Models(DataSource) / Storage(@AppStorage)       │
└──────────────┬──────────────────────────────────────────┘
               │ Protobuf 序列化字节流 + C ABI
               │ rust_call(sync) / rust_call_async(callback)
┌──────────────▼──────────────────────────────────────────┐
│  logic-logic (FFI 胶水层)                                │
│  logic/logic/src/c/{lib,byte_buffer,callback}.rs         │
│  logic/logic/src/{sync,async}.rs  ← tokio Runtime 单例   │
└──────────────┬──────────────────────────────────────────┘
               │ dispatch_sync / dispatch_async (oneof match)
┌──────────────▼──────────────────────────────────────────┐
│  logic-service (业务层)                                  │
│  fetch.rs(HTTP) topic.rs post.rs forum.rs user.rs        │
│  msg.rs noti.rs clock_in.rs history.rs cache.rs          │
│    ├─ logic-text  : BBCode 解析(peg 语法) / 转义         │
│    ├─ logic-cache : sled 本地 KV 缓存                    │
│    └─ logic-config: 运行时配置                           │
└──────────────┬──────────────────────────────────────────┘
               │ HTTPS (reqwest)
        ┌──────▼──────┐
        │  NGA 服务器  │
        └─────────────┘
```

## 0.2 C ABI 接口

`$ROOT/logic/logic/bindings.h`（cbindgen 生成，Xcode 通过 `SWIFT_OBJC_BRIDGING_HEADER` 引入）：

```c
typedef struct ByteBuffer {
    const uint8_t *ptr; uintptr_t len; uintptr_t cap; const char *err;
} ByteBuffer;
typedef void (*CallbackFn)(const void*, struct ByteBuffer);
typedef struct Callback { const void *user_data; CallbackFn callback; } Callback;

void rust_init(void);
struct ByteBuffer rust_call(const uint8_t *data, uintptr_t len);
void rust_call_async(const uint8_t *data, uintptr_t len, struct Callback callback);
void rust_free(struct ByteBuffer byte_buffer);
```

Rust 实现在 `$ROOT/logic/logic/src/c/{lib,byte_buffer,callback}.rs`。

## 0.3 数据流（以"打开帖子"为例）

1. Swift 侧 `TopicDetailsView.build(topic:)` 创建 `PagingDataSource`，构造 `AsyncRequest.OneOf_Value.topicDetails(TopicDetailsRequest)`。
   - `$ROOT/app/Shared/Models/PagingDataSource.swift`
2. `logicCallAsync` 把 `AsyncRequest` 序列化成 protobuf 字节，调 C 函数 `rust_call_async(ptr, len, Callback)`，Swift 闭包用 `Unmanaged.passRetained` 装进 `user_data`，C 回调里 `takeRetainedValue()` 释放。
   - `$ROOT/app/Shared/Logic/BasicLogicCall.swift`、`$ROOT/app/Shared/Logic/LogicCall.swift`
3. Rust `$ROOT/logic/logic/src/async.rs` 在全局 tokio Runtime 上 `spawn`，走 `service::dispatch_async`。
4. `$ROOT/logic/service/src/dispatch/mod.rs` match oneof → `handle_topic_details` → `topic::get_topic_details`。**每个 handler 都被 `catch_unwind` / `FutureExt::catch_unwind` 包裹**，panic 转成 `ServiceError::Panic`，不会让 App 崩溃。
5. `get_topic_details` 调 `fetch_package_with_retry("read.php", ...)` 发 HTTP，拿到 XML，用 XPath 抽取，用 `text::parse_content` 把 BBCode 解析成 `Span` 树。
6. 返回 `TopicDetailsResponse` protobuf，序列化成 `ByteBuffer`，回调回 Swift 主线程（`DispatchQueue.main.async`）。
7. SwiftUI 的 `ContentCombiner`（`$ROOT/app/Shared/Utilities/ContentCombiner.swift`，1020 行）递归遍历 `Span` 树，渲染成 SwiftUI 视图。

**关键设计点：BBCode 解析在 Rust 侧完成**，Swift 只负责把已结构化的 `Span` 树映射成视图。这是这个项目最值得借鉴的架构决策——换平台（Android）时渲染以外的逻辑全部复用。

## 0.4 Proto 契约

- `$ROOT/protos/DataModel.proto`（316 行）：`Topic` `Post` `User` `Forum` `Span` `Notification` 等共享模型。
- `$ROOT/protos/Service.proto`（402 行）：`SyncRequest`（9 个同步服务）/ `AsyncRequest`（29 个异步服务）两个 oneof。

**同步服务（9 个，立即返回，无网络）**：
`configure` `local_user` `auth` `content_parse` `subject_parse` `mark_noti_read` `set_request_option` `invalidate_client` `update_topic_progress`

**异步服务（29 个，均可能发网络请求）**：
`topic_list` `topic_details` `subforum_filter` `forum_list` `remote_user` `post_vote` `topic_history` `hot_topic_list` `forum_search` `favorite_topic_list` `topic_favor` `post_reply_fetch_content` `post_reply` `fetch_notification` `upload_attachment` `user_topic_list` `user_post_list` `short_message_list` `short_message_details` `short_message_post` `topic_search` `clock_in` `cache` `favorite_folder_list` `favorite_folder_modify` `favorite_folder_create` `user_signature_update` `favorite_forum_list` `favorite_forum_modify`

注册点：`$ROOT/logic/service/src/dispatch/handlers_async.rs`（用 `handle!` 宏）、`handlers_sync.rs`。

## 0.5 错误模型

`$ROOT/logic/service/src/error.rs` 定义 `ServiceError`，`to_app_string()` 输出 `"<kind>|<detail>"`，Swift 侧 `$ROOT/app/Shared/Utilities/Localization.swift` 按 `|` 切分并本地化前半段。

kind 取值：`MNGA` / `Error Response` / `NGA` / `Missing Field` / `Network Connection` / `XML Parse` / `JSON Parse` / `XPath Resolve` / `Cache` / `Text Parse` / `URL Parse` / `Protocol Buffer Encoding` / `Backend Panic`。

其中 `XML Parse` 在中文本地化里被译作「XML 解析（NGA 官方封禁访问）」——**解析失败基本等价于被封**，这也是重试机制的触发条件。

---

# 第 1 部分：功能清单

## 1.1 版块（Forum）

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 版块首页 | 按分类（Category）分组展示所有版块，分类可折叠（`@AppStorage("collapsedCategories")`） | `$ROOT/app/Shared/Views/ForumListView.swift` |
| 收藏版块 | 滑动收藏/取消，`EditButton` 拖动排序与删除 | `$ROOT/app/Shared/Views/ForumRowView.swift` |
| 本地 / 云端收藏切换 | `useRemoteFavoriteForums` 开关；本地存 App Group `group.com.bugenzhao.MNGA` 的 UserDefaults key `favoriteForums`，云端走 `forum_favor2` API | `$ROOT/app/Shared/Storage/FavoriteForumsStorage.swift` |
| 过滤模式 | 只看收藏 / 所有版块 | `ForumListView.swift` |
| 版块图标 | `https://img4.nga.cn/ngabbs/nga_classic/f/app/<fid>.png` | `$ROOT/app/Shared/Views/ForumIconView.swift` |
| 子版块列表 | 展示子版块，可点进、可滑动收藏，开关订阅/屏蔽（仅 `filterable` 的可切换） | `$ROOT/app/Shared/Views/SubforumListView.swift` |
| 版块搜索 | 按关键词搜版块 | `$ROOT/app/Shared/Views/GlobalSearchView.swift` |
| MNGA Meta 伪版块 | Rust 端硬编码注入 `fid = "mnga_root_0"`，走 Mock API；设置里可隐藏 | `$ROOT/logic/service/src/forum.rs:131-160` |

## 1.2 话题列表（Topic List）

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 两种排序 | 最新回复（默认）/ 最新发帖，各自独立 DataSource | `$ROOT/app/Shared/Views/TopicListView.swift` |
| 热门话题 | 并发抓取前 N 页，按回复数排序，限时间范围（日/周/月） | `$ROOT/app/Shared/Views/HotTopicListView.swift`、`$ROOT/logic/service/src/topic.rs:501` |
| 精华话题 | `recommended_only = true` | `$ROOT/app/Shared/Views/RecommendedTopicListView.swift` |
| 版头（置顶话题） | 读 `forum.topped_topic_id` | `TopicListView.swift` |
| 版块内搜索 | `.searchable` + `TopicSearchModel(id:)` | `$ROOT/app/Shared/Views/TopicSearchView.swift` |
| 标题渲染 | 标签（`[讨论]`）流式布局、彩色标题（`topic_misc` base64 位掩码解析）、已读变暗、收藏书签、无标题显示"无标题" | `$ROOT/app/Shared/Views/TopicSubjectView.swift`、`$ROOT/logic/service/src/topic.rs:68-116` |
| 版块镜像行 | `type` 字段位掩码 `0x8000`/`0x200000` 识别"快捷方式帖"，渲染成跳转版块的行 | `$ROOT/logic/service/src/topic.rs:177-197` |
| 行上下文菜单 | 前往话题、拷贝标题、分享；长按 preview | `$ROOT/app/Shared/Views/TopicRowView.swift` |
| 黑名单过滤 | `mayFilterBlocked()` —— 遮盖标题或隐藏整条 | `$ROOT/app/Shared/Views/BlockedView.swift` |

## 1.3 话题详情（Topic Details）

核心文件 `$ROOT/app/Shared/Views/TopicDetailsView.swift`（1039 行）。

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 分区结构 | 楼主楼 / 热门回复 / 全部回复 / 尾部"加载新回复"，均可折叠 | `TopicDetailsView.swift` |
| 分页阅读 | `usePaginatedDetails` 时按「第 N 页」分节 | `TopicDetailsView.swift` |
| 跳楼 / 跳页 | 楼层或页码两种模式，数字输入 + Slider，`postPerPage = 20` 换算 | `$ROOT/app/Shared/Views/TopicJumpSelectorView.swift` |
| 倒序 / 向前加载 | `loadFromPage` + `mayLoadBackButton`（加载上一页）+ `loadFirstPageButton` | `$ROOT/app/Shared/Models/PagingDataSource.swift` |
| 只看楼主 / 只看某人 | `authorid` 参数 | `$ROOT/app/Shared/Models/TopicDetailsActionModel.swift` |
| 只看匿名 | `opt=512` | 同上 |
| 阅读进度恢复 | 上次楼层 / 最高楼层两种策略，退出或进后台时同步 `update_topic_progress` | `$ROOT/logic/service/src/history.rs` |
| 回复链 / 引用 | 扫描 `[quote]` 建立 replyTo/quotedBy 索引；引用帖懒加载 | `$ROOT/app/Shared/Utilities/PostReplyRelationScanner.swift`、`$ROOT/app/Shared/Views/PostReplyChainView.swift`、`$ROOT/app/Shared/Models/QuotedPostResolver.swift` |
| 定位楼层 | pid → (floor, page) 缓存，找不到逐页扫描；定位后高亮 3 秒 | `$ROOT/app/Shared/Models/TopicPostLocator.swift` |
| 本地缓存查看 | 被封禁时读 sled 缓存，标记 `is_local_cache` | `$ROOT/logic/service/src/topic.rs:583-592` |
| 贴条（comment） | 楼中楼 | `$ROOT/app/Shared/Views/PostCommentRowView.swift` |
| 点赞 / 点踩 | 乐观更新 + Haptic 反馈 | `$ROOT/app/Shared/Views/PostRowView.swift` |
| 滑动操作 | 引用 / 点赞，顺序与触发边（leading/trailing）可在设置里调 | `PostRowView.swift` |
| 选择文本 | 单独 sheet，支持全部拷贝 / 拷贝原始 BBCode | `TextSelectionView` |
| 截图分享 | 含 MNGA logo + `mnga://` 深链二维码 | `$ROOT/app/Shared/Views/ScreenshotContainerView.swift`、`$ROOT/app/iOS/Utilities/Snapshot.swift` |
| XML 解析错误兜底 UI | 展示「刷新 / 在浏览器中打开」，可设置为自动跳浏览器 | `TopicDetailsView.swift` |
| Handoff | `NSUserActivity` + `webpageURL` | `TopicDetailsView.swift` |

## 1.4 发帖 / 回复编辑器

统一抽象在 `$ROOT/app/Shared/Models/GenericPostModel.swift`，三个实现：帖子回复、短消息、签名。

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 6 种操作 | 回复 / 引用 / 编辑 / 追加编辑 / 贴条 / 发新帖 / 举报 | `$ROOT/protos/DataModel.proto` `PostReplyAction.Operation` |
| BBCode 工具条 | 表情、图片、粗体、删除线、26 色颜色菜单、8 档字号（10%~200%）、折叠、@、引用、骰子、分割线、标题 | `$ROOT/app/iOS/Views/ContentTextEditorView.swift`、`$ROOT/app/Shared/Models/ContentEditorModel.swift` |
| 表情面板 | 6 套约 250 个表情（ac/a2/ng/pst/dt/pg），插入 `[s:ac:笑]`，最近使用上限 40 | `$ROOT/app/Shared/Views/StickerInputView.swift`、`$ROOT/app/Shared/Utilities/Stickers.swift` |
| 图片上传 | JPEG 0.8 压缩 → multipart 上传 → 插入 `[img]./<url>[/img]` | `$ROOT/app/iOS/Views/ContentEditorView.swift` |
| 草稿 | `contexts: [Task: Context]` 多草稿并存，下滑保存并 toast | `GenericPostModel.swift` |
| 匿名发帖 | `anony=1` | `$ROOT/logic/service/src/post.rs:262` |
| 预览 | 调同步 `contentParse` + `subjectParse`，用真实渲染管线预览 | `$ROOT/app/Shared/Views/GenericEditorView.swift` |
| 编辑器入口 | 帖子回复 / 短消息 / 签名分别为 `PostEditorView.swift` / `ShortMessageEditorView.swift` / `UserSignatureEditorView.swift` | `$ROOT/app/Shared/Views/` |

## 1.5 收藏

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 收藏夹管理 | 新建 / 重命名 / 设为默认 / 删除（删除时连带删除其中所有收藏话题） | `$ROOT/app/Shared/Views/FavoriteTopicListView.swift` |
| 多收藏夹归属 | 一个话题可属于多个收藏夹，帖内菜单用 Toggle 列表管理 | `TopicFavorMenuView`（在 `TopicDetailsView.swift` 内） |
| 本地收藏状态缓存 | sled 前缀 `/favor_response/topic`，浏览收藏列表时顺带更新 | `$ROOT/logic/service/src/topic.rs:279-310` |
| 版块收藏 | 见 1.1 | `FavoriteForumsStorage.swift` |

## 1.6 通知 / 私信

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 通知轮询 | Release 60 秒 / Debug 10 秒，新增未读时 Haptic 提醒 | `$ROOT/app/Shared/Models/NotificationModel.swift` |
| 通知列表 | 滑动标记已读/未读、全部标记已读、未读徽标 | `$ROOT/app/Shared/Views/NotificationListView.swift` |
| 已读状态 | **完全本地维护**（服务端不提供），存 sled 前缀 `/noti_v2` | `$ROOT/logic/service/src/noti.rs` |
| 应用内 Toast | AlertToast，四个通道 hud / banner / alert / editorAlert | `$ROOT/app/Shared/Models/ToastModel.swift`、`$ROOT/app/Shared/Modifiers/ToastModifier.swift` |
| 私信会话列表 | 分页 | `$ROOT/app/Shared/Views/ShortMessageListView.swift` |
| 私信详情 | 含参与者头像横排 | `$ROOT/app/Shared/Views/ShortMessageDetailsView.swift` |
| 新建 / 回复私信 | 多收件人用空格分隔 | `$ROOT/app/Shared/Views/ShortMessageEditorView.swift` |

通知类型（`DataModel.proto` `Notification.Type`）：`1` 回复我的话题、`2` 回复我的回复、`7` 话题中 @ 我、`8` 回复中 @ 我、`10` 新私信、`11` 私信回复、`17` 我的帖子获得评价。

## 1.7 搜索

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 全局搜索 | 一次构造三个 DataSource：版块 / 话题 / 用户 | `$ROOT/app/Shared/Views/GlobalSearchView.swift` |
| 用户搜索 | 纯数字按 uid 查，否则按用户名查 | `GlobalSearchView.swift` |
| 版块内话题搜索 | 可选是否搜正文 | `$ROOT/app/Shared/Views/TopicSearchView.swift` |
| 历史内本地过滤 | 纯客户端过滤 | `$ROOT/app/Shared/Views/TopicHistoryListView.swift` |
| 搜索基础设施 | `BasicSearchModel` / `SearchModel<DS>` / `SearchableModifier` | `$ROOT/app/Shared/Models/SearchModel.swift` |

## 1.8 用户 / 签到 / 黑名单 / 多账号

| 功能 | 说明 | 关键文件 |
|---|---|---|
| 用户资料页 | 头像/发帖数/声望(fame/10)/注册日期/IP 属地/签名，Topics/Posts 两个分页 | `$ROOT/app/Shared/Views/UserProfileView.swift` |
| 用户视图 | compact/normal/huge/vertical 四种样式；点名字切换显示 uid；禁言标记；楼主标记 | `$ROOT/app/Shared/Views/UserView.swift` |
| 匿名用户名还原 | `#anony_<32位hex>` → 6 个汉字（天干地支 + 百家姓查表） | `$ROOT/logic/service/src/user.rs:76-131` |
| 签名编辑 | 仅本人可编辑 | `$ROOT/app/Shared/Views/UserSignatureEditorView.swift` |
| 签到 | 鉴权变化 5s 后 + 每 2 分钟触发，本地按日期去重 | `$ROOT/app/Shared/Models/CurrentUserModel.swift`、`$ROOT/logic/service/src/clock_in.rs` |
| 黑名单 | 关键词匹配「用户名\|标题\|标签」拼接串，命中后遮盖或隐藏；`BlockWord.fromUser` 生成 `"User: 名字"` | `$ROOT/app/Shared/Storage/BlockWordsStorage.swift`、`$ROOT/app/Shared/Views/BlockWordListView.swift` |
| 浏览历史 | sled 存 `TopicSnapshot`，limit 1000，支持本地过滤 | `$ROOT/logic/service/src/history.rs`、`$ROOT/app/Shared/Views/TopicHistoryListView.swift` |
| 多账号 | `allAuthInfos: Set<AuthInfo>`；切号只影响浏览/回复，通知和历史共享 | `$ROOT/app/Shared/Storage/AuthStorage.swift`、`$ROOT/app/Shared/Views/UserMenuView.swift` |

## 1.9 内容渲染（BBCode → SwiftUI）

`$ROOT/app/Shared/Utilities/ContentCombiner.swift` 支持的标签：

`img` `noimg` `album` `quote` `b` `i` `u` `del` `uid` `pid` `tid` `url` `code` `color`(26 色) `size`(百分比) `collapse`(折叠) `flash`(视频/音频) `attach` `list`/`[*]` `align`(left/center/right) `table`/`tr`/`td`(含 colspan，横向可滚动) `dice`(复刻 NGA 线性同余伪随机) `at` `_divider`/`h` `_mnga`

未知标签原样显示 `[tag]...[/tag]`；`font` 标签被忽略透传。

BBCode 语法解析用 peg 写在 `$ROOT/logic/text/src/content.rs`，支持：
- `[tag=attr1,attr2]` 简单属性
- `[td rowspan=2 colspan=3]` 复杂属性
- `[s:ac:笑]` 表情
- `<br/>` / `[stripbr]` 换行
- `===标题===` / `======` 分割线
- `[@用户名]` @ 提及

配套视图：
- 图片：`$ROOT/app/Shared/Views/ContentImageView.swift`（SDWebImage，三档缩放，深色模式调暗，引用内只显示占位按钮）
- 大图查看器：`$ROOT/app/iOS/Views/NewImageViewer.swift`（LazyPager 多图翻页、1–5× 缩放、下滑关闭、ShareLink）
- 折叠：`CollapsedContentView.swift`
- 引用：`QuoteView.swift` / `QuoteUserView.swift` / `InlineQuotedPostView.swift`
- 附件：`AttachmentsView.swift` + `$ROOT/app/Shared/Models/AttachmentsModel.swift`

标题（Subject）解析在 `$ROOT/logic/text/src/subject.rs`：用 peg 提取开头的连续 `[标签]` / `【标签】`，剩余为正文；正文为空时把最后一个标签当正文（包上 `【】`）。

## 1.10 设置项完整清单

`$ROOT/app/Shared/Views/PreferencesView.swift` + `$ROOT/app/Shared/Storage/PreferencesStorage.swift`

**外观**：配色方案（自动/浅色/深色）、13 种主题色、列表样式（紧凑/现代）、高刷新率优先、锁定竖屏

**阅读**：
- 屏蔽词管理
- 话题列表样式子页：默认排序 / 搜索栏位置 / 刷新按钮 / 屏蔽话题样式 / 显示版块镜像 / 彩色标题
- 话题详情样式子页（带实时预览）：滑动触发边缘 / 首选滑动操作 / 日期显示策略 / 显示签名·头像·作者标识·用户详情·注册时间 / 分页阅读 / 更大字体 / 图片缩放三档 / 深色模式调暗图片
- 恢复阅读进度（禁用 / 上次楼层 / 最高楼层）
- 隐藏通知快捷入口
- 始终使用内置 Safari

**连接（复刻时最关键）**：
- **后端域名切换**：`bbs.nga.cn` / `ngabbs.com` / `bbs.ngacn.cc`
- **设备身份 / UA**：apple / android / desktop / windowsPhone / custom（custom 可填自定义 UA）
- **Web API 策略**：Disabled / Secondary（默认）/ Primary / Only

**高级**：缓存管理（图片缓存 + 4 类数据缓存分别清除）、隐藏 MNGA Meta、封禁时自动开浏览器、始终以文件分享原图

## 1.11 深链 / 分享

`$ROOT/app/Shared/Models/SchemesModel.swift`、`$ROOT/app/Shared/Utilities/Constants.swift`

URL Scheme 注册在 `$ROOT/app/iOS/Info.plist`（`CFBundleURLSchemes: ["mnga"]`）。

| 目标 | mnga:// 形式 | 对应网页 URL |
|---|---|---|
| 话题 | `mnga://topic/<tid>[?fav=]` | `read.php?tid=` |
| 帖子 | `mnga://post/<pid>` | `read.php?pid=` |
| 版块 | `mnga://forum/f/<fid>` 或 `mnga://forum/st/<stid>` | `thread.php?fid=` / `?stid=` |
| 用户 | `mnga://user/<uid>` | `nuke.php?func=ucp&uid=` / `&username=` |

反向解析 `URL.mngaNavigationIdentifier` 同时识别 mnga scheme 与三个官方域名的网页链接。另有剪贴板监听：检测到合法 NGA/MNGA 链接时在版块首页底栏出现「跳转」按钮。

## 1.12 Plus 内购

`$ROOT/app/Shared/Models/PlusModel.swift`、`$ROOT/app/Shared/Utilities/PlusFeature.swift`、`$ROOT/app/Shared/Views/PlusView.swift`

StoreKit 2，产品 ID `mnga.unlock` / `mnga.unlock.trial14`（14 天试用）。13 项 Plus 功能：自定义外观、多账号、浏览历史、多收藏夹、只看作者、跳楼、恢复阅读进度、屏蔽内容、同步收藏版块、匿名发帖、发新话题、热门话题、短消息。

> 复刻时这部分可以整体略过——都是 App 层的商业化包装，与 NGA API 无关。

## 1.13 平台特性

- **iPad 三栏**：`NavigationSplitView`（sidebar=版块列表，content=话题列表，detail=话题详情），每栏独立 `NavigationStack`；跨栏导航 hack 在 `$ROOT/app/Shared/Utilities/CrossStackNavigationLink.swift`
- **macOS**：仅"Designed for iPad"运行，无 Catalyst target
- **Widget / Shortcuts**：无独立 target，只有 App Group 与 `NSUserActivity` 的基础设施
- 部署目标 iOS 18.4，工程用 Tuist 生成（`$ROOT/app/Project.swift`）

---

# 第 2 部分：API 调用清单（重点）

## 2.1 Base URL 与域名切换

`$ROOT/logic/service/src/constants.rs`：

```rust
pub const DEFAULT_BASE_URL: &str       = "https://bbs.nga.cn";
pub const DEFAULT_MOCK_BASE_URL: &str  = "https://mnga-pages.bugenzhao.com/api/";  // MNGA 自建，非 NGA
pub const DEFAULT_PROXY_BASE_URL: &str = "https://nga.bugenzhao.com";              // 作者自建反代
pub const FORUM_ICON_PATH: &str        = "https://img4.nga.cn/ngabbs/nga_classic/f/app/";
```

可选官方域名（Swift 侧 `$ROOT/app/Shared/Utilities/URLs.swift`）：

```swift
static let defaultHost = "bbs.nga.cn"
static let hosts = [defaultHost, "ngabbs.com", "bbs.ngacn.cc"]
```

历史域名迁移（`$ROOT/logic/service/src/request.rs:7`）：`https://nga.178.com` 会被自动重写为默认值。

**URL 解析规则**（`fetch.rs:44`）：先尝试把 api 当绝对 URL 解析（附件上传用），失败则用 `Url::parse(base)?.join(api)`。**base 必须带结尾斜杠**。

**图片资源域名规范化**（`URLs.swift`）：形如 `imgN.<legacy>` 的旧域名（`.nga.178.com` / `.ngacn.cc` / `.ngabbs.com`）统一改写为 `img.nga.cn`；路径以 `/ngabbs/` 开头的改写为 `img4.nga.cn`。附件基址：`https://img.nga.cn/attachments/`。

`[noimg]` 标签的图片路径需要按发帖日期（UTC+8）拼前缀 `mon_%04d%02d/%02d/`（`ContentCombiner.swift:588`）。

## 2.2 HTTP 请求的公共约定

全部在 `$ROOT/logic/service/src/fetch.rs`。**这是复刻时最需要精读的文件。**

### 2.2.1 请求方法与参数位置

```rust
// fetch.rs:301 —— 所有文本类请求一律用 POST
let response = do_fetch(api, kind, query, Method::POST, false, &add_form).await?;
```

**所有 API 都是 POST，但业务参数放在 URL query string 里，POST body（form）只放认证信息。** 这是 NGA 的惯例，复刻时最容易踩的坑。

### 2.2.2 强制附加的公共 query 参数

```rust
// fetch.rs:206
query.push(("__inchst", "UTF8"));
// 随后过滤掉所有 value 为空字符串的参数
query.into_iter().filter(|(_k, v)| !v.is_empty()).collect()
```

`__inchst=UTF8` 告诉 NGA 输入/输出用 UTF-8。**空值参数会被丢弃**——这个行为被大量利用（例如 `bool::to_value()` 中 `false => ""`，即 false 时该参数直接不出现；`fid`/`stid` 二选一也靠它）。

### 2.2.3 返回格式控制参数

三种响应格式，各自的 query 参数（`ResponseFormat::query_pairs()`）：

| 格式 | query 参数 | 用于 |
|---|---|---|
| XML | `lite=xml`（首选）或 `__output=10`（备选，紧凑 XML） | `thread.php` `read.php` `post.php` `forum.php` |
| JSON | `__output=8`（紧凑 JSON） | `nuke.php` `app_api.php` |
| Web HTML | `("", "")`（即不加，被空值过滤掉） | `read.php` 的网页版兜底 |

源码注释里还提到 `__output=9` ≡ `lite=xml`，`__output=11` 是详细 JSON。

### 2.2.4 认证

```rust
// fetch.rs:373-380
let auth_info = auth::AUTH_INFO.read().unwrap().clone();
form.push(("access_token", auth_info.get_token()));
form.push(("access_uid",   auth_info.get_uid()));
do_fetch_text(api, query, |b| b.form(&form), retry).await
```

**认证不走 Cookie，走 POST form 字段 `access_token` / `access_uid`。**

- `access_uid` ← 登录时抓到的 Cookie `ngaPassportUid`
- `access_token` ← 登录时抓到的 Cookie `ngaPassportCid`

multipart 上传时同样以 text part 形式附带这两个字段（`fetch.rs:444-450`）。

### 2.2.5 登录流程（WebView + Cookie 抓取）

`$ROOT/app/Shared/Views/LoginView.swift`：用 `WKWebView`（`WKWebsiteDataStore.nonPersistent()`）打开

```
https://<base>/nuke.php?__lib=login&__act=account&login
```

页面加载完后注入 JS：禁用 viewport 缩放、改背景色、在 iframe `#iff` 内用 XPath 自动点击"账号密码登录"入口、隐藏"二维码登录"和"第三方登录"（最多重试 60 次 × 0.5s）。NGA 原生 `alert` 被拦截并替换成本地化提示。

然后**每 0.5 秒轮询 cookie store**：

```swift
let timer = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()
...
.onReceive(timer) { _ in
  webViewStore.configuration.websiteDataStore.httpCookieStore.getAllCookies(authWithCookies)
}

func authWithCookies(_ cookies: [HTTPCookie]) {
  guard let uid   = cookies.first(where: { $0.name == "ngaPassportUid" })?.value else { return }
  guard let token = cookies.first(where: { $0.name == "ngaPassportCid" })?.value else { return }

  authing = true
  authStorage.setCurrentAuth(AuthInfo.with {
    $0.uid = uid
    $0.token = token
  })
}
```

拿到后通过同步请求 `SyncRequest.auth` 下发到 Rust 的全局 `AUTH_INFO`（`$ROOT/logic/service/src/auth.rs`）。

登录页底部还有 segmented picker 切换到用户协议（`/misc/agreement.html`）和隐私协议（`/misc/privacy.html`）。

> App 内提示：「如遇登录限制，请在 PC 网页端成功登录一次，然后再尝试登录 MNGA。」

### 2.2.6 User-Agent

```rust
// constants.rs:17-20
APPLE_UA:         "NGA_skull/7.3.1(iPhone17,1;iOS 26.0)"
ANDROID_UA:       "Nga_Official/80024(Android12)"
DESKTOP_UA:       "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.64 Safari/537.36"
WINDOWS_PHONE_UA: "NGA_WP_JW/(;WINDOWS)"
```

特殊规则（`fetch.rs:25-42`）：**`read.php` 在用户未自定义 UA 时，强制使用 `WINDOWS_PHONE_UA`**，源码注释说 "seems to be more robust"（更不容易被封）。

三个 header 同时设置：

```rust
.header("User-Agent",   ua)
.header("X-User-Agent", ua)
.header("Referer",      url_string)   // 任何以 base url 开头的 URL 都行
```

reqwest client 配置：`https_only(true)`、connect_timeout 5s、read_timeout 20s、`gzip(true)`。全局 client 单例，重试前会 `invalidate_global_client()` 重建。

### 2.2.7 编码处理

```rust
// fetch.rs:303
let response = response.text_with_charset("gb18030").await?;
```

优先用响应头 `Content-Type` 里声明的 charset；**没有声明时回落到 GB18030**。配合 `__inchst=UTF8` 请求 UTF-8 输出。

JSON 额外做两步修复（`fetch.rs:499-513`）：
1. `sanitize_json_control_chars_in_strings()` —— 把字符串里的裸控制字符转义（实现在 `$ROOT/logic/service/src/utils.rs:95`）
2. 正则 `([{,}]\s*)(\d+)(:)` → `$1"$2"$3` —— NGA 会返回**整数作为 JSON key**（非法 JSON），需要加引号

JSON 响应的实际数据在顶层 `data` 字段下（`value["data"].take()`）。

### 2.2.8 错误提取

**XML 错误**（`$ROOT/logic/service/src/utils.rs:252` `extract_error`）：检查三个位置
- `/root/__MESSAGE`（前端错误，第 0 个子节点是 code，第 1 个是 info）
- `/root/error`（后端错误）
- `/root/error_code`

**JSON 错误**（`fetch.rs:465`）：检查顶层 `error` 对象的 `code` 字段和其他字符串字段。实测格式：
- `{"error":{"0":"找不到用户"},"time":...}` → code 缺失时填 `"?"`
- `{"error":{"code":403,"0":"帖子不存在"},...}`

**"假错误"白名单**（`constants.rs:9-15`）——这些 info 出现时视为成功：

```rust
["完毕", "没找到", "没有符合条件的结果", "今天已经签到", "找不到用户"]
```

另外 `do_fetch` 默认 `check_status = false`：**NGA 会在 HTTP 非 2xx 状态下仍返回有意义的错误信息**，所以先尝试解析 body，body 为空且状态码非 2xx 时才用状态码报错。

### 2.2.9 反封锁重试机制（`RetryMode`）

NGA 会针对第三方客户端封锁，MNGA 用一套笛卡尔积重试：

```rust
attempts = fetch_kinds() × query_pairs()
```

- `fetch_kinds`：`Never` → `[Normal]`；`Use` → `[Normal, Proxy]`；`Only` → `[Proxy]`
- `query_pairs`：不用备选时只取第一个（`lite=xml`）；用备选时取全部（`lite=xml` 和 `__output=10`）

四种模式：

| 模式 | 尝试组合 | 用在哪 |
|---|---|---|
| `RetryMode::never()` | 只试 `lite=xml` + 官方域名 | **绝大多数 API** |
| `RetryMode::qp_only(key)` | 两种 query 参数 × 官方域名 | `read.php` SECONDARY 策略第一步 |
| `RetryMode::full(key)` | 两种 query 参数 × 官方/代理域名 | `read.php` DISABLED / PRIMARY 兜底 |
| `RetryMode::proxy_only(key)` | 两种 query 参数 × 仅代理 | `read.php` SECONDARY 最后兜底 |

成功的组合会被记入全局 `RETRY_ATTEMPT_CACHE`（`DashMap<String, (FetchKind, query_pair)>`），下次同 key 请求优先用上次成功的组合（`attempts.swap(0, index)`）。

只有 **XML/JSON 解析错误或 HTTP Status 错误**才触发重试（解析失败 ≈ 被封），其他错误直接返回。每次重试前调 `invalidate_global_client(false)` 重建 HTTP 连接。

`invalidate_global_client(warmup=true)` 还会发一个 `HEAD thread.php` 预热连接。

---

## 2.3 API 逐个清单

下表中"公共参数"指每个请求都会自动加的 `__inchst=UTF8` + 格式参数（`lite=xml` 或 `__output=8`），form 里的 `access_token` / `access_uid` 同理，不再重复列出。**所有请求均为 POST。**

---

### 【A】版块相关

#### A1. 获取版块列表（分类树）

- 文件：`$ROOT/logic/service/src/forum.rs:130`
- `POST app_api.php`

| Query | 值 |
|---|---|
| `__lib` | `home` |
| `__act` | `category` |

响应：JSON。`data` 是对象，每个 value 是一个 Category：`_id`、`name`、`groups.*.forums.*`（版块对象含 `id`/`fid`/`stid`/`name`/`info`/`topped_topic`）。

版块 id 解析规则：**stid 优先于 fid**（`stid.or(fid)`）。图标 id 取 `id` 或 `fid`。

MNGA 在结果最前面硬编码插入了一个 `id="mnga"` 的假分类（含 `fid = "mnga_root_0"`）。

#### A2. 版块搜索

- 文件：`$ROOT/logic/service/src/forum.rs:191`
- `POST forum.php`

| Query | 值 |
|---|---|
| `key` | 搜索关键词 |

响应：XML，XPath `/root/item`，字段 `fid` `stid` `name` `info` `topped_topic`。

#### A3. 获取云端收藏版块

- 文件：`$ROOT/logic/service/src/forum.rs:206`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `forum_favor2` |
| `__act` | `forum_favor` |

| Form | 值 |
|---|---|
| `action` | `get` |

响应：JSON，版块数组在 `data["0"]`。

#### A4. 增删收藏版块

- 文件：`$ROOT/logic/service/src/forum.rs:247`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `forum_favor2` |
| `__act` | `forum_favor` |

| Form | 值 |
|---|---|
| `action` | `add` 或 `del` |
| `fid` | fid 或 stid（二选一，取有值的那个） |

#### A5. 子版块订阅 / 屏蔽

- 文件：`$ROOT/logic/service/src/forum.rs:170`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `user_option` |
| `__act` | `set` |
| `del` 或 `add` | subforum_filter_id（SHOW 用 `del`，BLOCK 用 `add`） |

| Form | 值 |
|---|---|
| `fid` | 父版块 fid |
| `type` | `1` |
| `info` | `add_to_block_tids` |

> 注意参数名本身就是操作：订阅是 `del=<id>`（从屏蔽列表删除），屏蔽是 `add=<id>`。成功时响应含"操作成功"。

---

### 【B】话题列表相关（全部走 `thread.php`，返回 XML）

#### B1. 版块话题列表

- 文件：`$ROOT/logic/service/src/topic.rs:441`
- `POST thread.php`

| Query | 值 | 说明 |
|---|---|---|
| `stid` | 合集 id | 与 `fid` 二选一（空值被过滤） |
| `fid` | 版块 id | |
| `page` | 页码，从 1 开始 | |
| `order_by` | `""`（按最后回复）或 `postdatedesc`（按发帖时间） | 见 `$ROOT/logic/protos/src/to_value.rs:44` |
| `recommend` | `1`（仅精华）或 `""`（全部） | bool 的 `to_value()`：true→`"1"`，false→`""` |

响应 XML 结构：
- `/root/__T/item` —— 话题列表
- `/root/__F` —— 当前版块信息
- `/root/__F/sub_forums/*` —— 子版块（**子节点名为 `item` 时用 fid，否则用 stid**）
- `/root/__ROWS` 总条数、`/root/__T__ROWS_PAGE` 每页条数（默认 35）→ 计算总页数

话题 `item` 字段：`tid` `fid` `quote_from` `subject` `author` `authorid` `postdate` `lastpost` `replies` `type` `tpcurl` `topic_misc` `titlefont`、子节点 `parent/_0`(fid) `parent/_1`(stid) `parent/_2`(name)

几个非直观的解析（`topic.rs:118-230`）：

1. **真实 tid**：优先用 `quote_from`（非空且非 `"0"`），否则用 `tid`。有单测覆盖（`topic.rs:1050`）。
2. **`fav` 码**：从 `tpcurl` 里正则提取 `fav=([a-fA-F0-9]+)`，用于访问隐藏/过期帖。
3. **`type` 位掩码**：`& 0x8000` → 该帖是 stid 快捷方式；`& 0x200000` → 从 `./topic_misc_var/item` 取 fid 快捷方式。
4. **`topic_misc` 标题字体**：base64（`STANDARD_NO_PAD`）解码后是 TLV 结构（1 字节 type + 4 字节 BE u32），type=1 时取值作为位掩码：

   | 掩码 | 效果 |
   |---|---|
   | `0x1` | 红 |
   | `0x2` | 蓝 |
   | `0x4` | 绿 |
   | `0x8` | 橙 |
   | `0x10` | 银 |
   | `0x20` | 粗体 |
   | `0x40` | 斜体 |
   | `0x80` | 下划线 |

**子版块 `attributes` 的魔法数**（`topic.rs:471`，源码注释是 "how can I fucking know this ??"）：

```rust
s.selected   = [7, 558, 542, 2606, 2590, 4654].contains(&s.get_attributes());
s.filterable = attributes > 40;
```

子版块节点按位置取值（`extract_subforum`）：第 0 项=id、第 1 项=name、第 2 项=info、第 3 项=filter_id、第 4 项=attributes。

#### B2. 热门话题

- 文件：`$ROOT/logic/service/src/topic.rs:501`
- **不是独立 API**。并发调 B1 抓前 `fetch_page_limit`（下限 10）页，过滤 `post_date > now - range`（DAY=1天 / WEEK=7天 / MONTH=30天），按 `replies_num` 倒序，取前 `limit`（下限 30）条。

#### B3. 话题搜索

- 文件：`$ROOT/logic/service/src/topic.rs:551`
- `POST thread.php`

| Query | 值 |
|---|---|
| `fid` / `stid` | 版块 id（**都为空 = 全站搜索**） |
| `key` | 关键词 |
| `recommend` | `1` / `""` |
| `content` | `1`（搜正文）/ `""`（仅搜标题） |
| `page` | 页码 |

#### B4. 收藏话题列表

- 文件：`$ROOT/logic/service/src/topic.rs:318`
- `POST thread.php`

| Query | 值 |
|---|---|
| `favor` | 收藏夹 id（默认收藏夹也可用 `1`） |
| `page` | 页码 |

副作用：浏览时会把每个话题的收藏状态写进本地 sled 缓存（`/favor_response/topic/<tid>`）。

#### B5. 某用户的话题列表

- 文件：`$ROOT/logic/service/src/topic.rs:751`
- `POST thread.php`

| Query | 值 |
|---|---|
| `authorid` | 用户 uid |
| `page` | 页码 |

#### B6. 某用户的回复列表

- 文件：`$ROOT/logic/service/src/post.rs:384`
- `POST thread.php`

| Query | 值 |
|---|---|
| `searchpost` | `1` |
| `authorid` | 用户 uid |
| `page` | 页码 |

响应中每个 `/root/__T/item` 额外含 `./__P` 子节点（轻量帖子：`pid` `tid` `authorid` `content` `postdate`）。

---

### 【C】话题详情

#### C1. 获取帖子回复列表

- 文件：`$ROOT/logic/service/src/topic.rs:611-679`
- `POST read.php`

| Query | 值 | 说明 |
|---|---|---|
| `tid` | 话题 id | |
| `page` | 页码 | |
| `fav` | 收藏码 | 访问隐藏/过期帖用，来自 `tpcurl` 提取 |
| `pid` | 帖子 id | 只看某一楼时用 |
| `authorid` | 用户 uid | 只看某人 |
| `opt` | `512` 或 `""` | `512` = 只看匿名 |

响应 XML：
- `/root/__T` —— 话题信息
- `/root/__U/item` —— 本页出现的用户
- `/root/__R/item` —— 回复列表
- `/root/__F/name`（或 `/root/__F`）—— 版块名
- `/root/__ROWS` + `/root/__R__ROWS_PAGE`（默认 20）→ 总页数

回复 `item` 字段：

| 字段 | 含义 |
|---|---|
| `pid` `tid` `fid` | 标识 |
| `lou` | 楼层号 |
| `authorid` | 作者 uid（`-` 开头是匿名） |
| `content` | BBCode 正文 |
| `postdatetimestamp` | 时间戳 |
| `score` | 赞数 |
| `alterinfo` | 非空表示被编辑过 |
| `./hotreply/item` | 热门回复（仅主楼有） |
| `./comment/item` | 贴条（楼中楼） |
| `./attachs/item` | 附件（`attachurl` `size` `type`） |
| `.//from_client` | 发帖设备（含 android/ios 关键字判断） |

用户 `item` 字段：`uid` `username` `avatar` `regdate` `postnum`/`posts` `fame`/`rvrc` `signature`/`sign` `buffs`（含 `105` 表示禁言中）`ipLoc`

**匿名用户处理**：`authorid` 以 `-` 开头的是匿名用户，会被加上本次请求的 uuid context 前缀（`<context>,-1` 形式），避免不同页/不同帖的匿名用户互相串号（`$ROOT/logic/service/src/user.rs:24`）。

#### C2. Web API 兜底（重要）

`$ROOT/logic/service/src/topic/web_to_xml.rs`（1289 行）——当 XML 接口被封时，请求**同一个 `read.php` 但不带任何格式参数**，拿到网页 HTML，从内联 JS 里反解数据，再合成出与 `lite=xml` 完全一致的 XML 结构喂给下游 XPath 提取器（下游代码零改动）。

解析的 JS 标记：

| 标记 | 内容 |
|---|---|
| `commonui.postArg.proc(` | 每层楼的元数据（至少 23 个参数） |
| `commonui.userInfo.setAll(` | 用户信息 |
| `commonui.loadAlertInfo(` | 提示信息 |
| `var __PAGE` | 分页信息 |
| `<!--msgcodestart--> / <!--msginfostart-->` | 错误页（HTML 注释包裹） |

`postArg.proc` 参数下标（`web_to_xml.rs:323-334`）：`args[0]`=key、`args[10]`=pid、`args[11]`=post_type、`args[13]`=author_id、`args[14]`=timestamp、`args[15]`=分数串、`args[16]`=内容长度、`args[19]`=from_client、`args[22]`=follow。

四种策略（`TopicDetailsRequest.WebApiStrategy`，`topic.rs:665-670`）：

| 策略 | 尝试顺序 |
|---|---|
| `DISABLED` (0) | XML（full 重试：两种 query 参数 × 官方/代理） |
| `SECONDARY` (1) 默认 | XML(qp_only) → XML 解析失败则 Web → Web 失败则 XML(proxy_only) |
| `PRIMARY` (2) | Web → 失败则 XML(full) |
| `ONLY` (3) | 仅 Web |

响应的 `api_used` 字段会记录实际用了哪条路径（走代理时加 `-p` 后缀），便于调试。

#### C3. 本地缓存兜底

当在线请求返回 `ServiceError::Nga(_)` 时，尝试读 sled 缓存（前缀 `/topic_details_response/topic/<tid>/page/<page>`），成功则返回并设置 `local_reason`。仅当请求没有 `post_id` / `author_id` / `anonymous_author_only` 时才缓存（`topic.rs:35`）。

---

### 【D】发帖 / 回复（走 `post.php`，返回 XML）

#### D1. 拉取编辑器初始内容

- 文件：`$ROOT/logic/service/src/post.rs:274`
- `POST post.php`

| Query | 值 |
|---|---|
| `action` | `reply` / `quote` / `modify` / `new`（`comment` 也映射成 `reply`） |
| `tid` | 话题 id（非 NEW 时） |
| `pid` | 帖子 id（非 NEW 时） |
| `fid` 或 `stid` | 版块 id（NEW 时） |

> 源码里 `tid`/`pid` 被 push 了两次（一次显式，一次通过 `query_insert_id!` 宏），实际发出的 URL 会有重复参数——NGA 能接受。

响应 XPath：

| XPath | 含义 |
|---|---|
| `/root/content` | 预填内容（需 `text::unescape` 两轮 HTML 实体解码 + UTF-16 代理对还原） |
| `/root/subject` | 预填标题 |
| `/root/modify_append` | 非空表示只能追加编辑（超时无法原地改） |
| `/root/auth` | **上传附件用的鉴权码** |
| `/root/attach_url` | **附件上传的目标 URL（绝对地址）** |

后三项打包成 `PostReplyVerbatim`，需要**原样**带到后续的发帖/上传请求里。

#### D2. 发帖 / 回复 / 引用 / 编辑 / 贴条

- 文件：`$ROOT/logic/service/src/post.rs:206`
- `POST post.php`

| Query | 值 | 条件 |
|---|---|---|
| `action` | `reply` / `quote` / `modify` / `new` | COMMENT 也用 `reply` |
| `step` | `2` | 固定 |
| `post_content` | 转义后正文 | |
| `attachments` | 附件名，多个用 `\t` 连接 | |
| `attachments_check` | 附件校验码，多个用 `\t` 连接 | |
| `post_subject` | 转义后标题 | 有标题时 |
| `comment` | `1` | 操作是"贴条"时 |
| `modify_append` | `1` | 编辑且只能追加时 |
| `anony` | `1` | 匿名发帖时 |
| `tid` + `pid` | | REPLY/QUOTE/MODIFY/COMMENT/REPORT |
| `fid` 或 `stid` | | NEW |

**提交前的转义**（`$ROOT/logic/text/src/escape.rs:65`）—— 这是复刻时的关键坑：

```rust
pub fn escape_for_submit(text: &str) -> String
```

以下字符必须转成 **UTF-16 码元的十进制 HTML 实体**（`&#NNNNN;`），否则旧版 NGA 接口会拒绝或乱码：

| 范围 | 说明 |
|---|---|
| 码点 > `0xFFFF` | 所有 emoji 等星平面字符，转成代理对两个实体 |
| `0x200D` | 零宽连接符（emoji ZWJ 序列，如家庭 emoji） |
| `0xFE00`–`0xFE0F` | 变体选择符（如 `❤️` 强制 emoji 呈现） |
| `0x2600`–`0x27BF` | 杂项符号与装饰符 |

例（有单测）：

```
"A😂B❤️C👨‍👩‍👧‍👦"
→ "A&#55357;&#56834;B&#10084;&#65039;C&#55357;&#56424;&#8205;&#55357;&#56425;&#8205;&#55357;&#56423;&#8205;&#55357;&#56422;"
```

反向解码（读取时，`escape.rs:59`）：`unescape()` 做**两轮** `decode_html_entities`（NGA 会双重转义），再还原 UTF-16 代理对实体。例：`"&amp;#55357;&amp;#56836;"` → `"😄"`。

#### D3. 举报

- 文件：`$ROOT/logic/service/src/post.rs:212-226`
- `POST nuke.php`（**不是 post.php**）

| Query | 值 |
|---|---|
| `__lib` | `log_post` |
| `__act` | `report` |
| `raw` | `3` |
| `info` | 转义后的举报理由 |
| `tid` + `pid` | 被举报的帖子 |

#### D4. 上传附件

- 文件：`$ROOT/logic/service/src/post.rs:331`
- `POST <verbatim.attach_url>`（D1 返回的绝对 URL），**multipart/form-data**

| Multipart 字段 | 值 |
|---|---|
| `v2` | `1` |
| `origin_domain` | `ngabbs.com`（源码有 todo：应该用实际域名） |
| `func` | `upload` |
| `auth` | D1 返回的 `verbatim.auth` |
| `fid` | 版块 fid |
| `attachment_file1_img` | `1` |
| `attachment_file1_dscp` | 文件名（`<uuid>.jpeg`） |
| `attachment_file1_url_utf8_name` | 同上 |
| `attachment_file1_watermark` | `""` |
| `attachment_file1_auto_size` | `""` |
| `attachment_file1` | 二进制，`filename=<uuid>.jpeg`，`Content-Type: image/jpeg` |
| `access_token` / `access_uid` | 认证 |

form 用了 `.percent_encode_path_segment()`。query 里仍会自动带上 `lite=xml` + `__inchst=UTF8`。

响应 XML：

| XPath | 含义 |
|---|---|
| `/root/attachments` | 附件名 |
| `/root/url` | 附件 URL |
| `/root/attachments_check` | 校验码 |

后续 D2 发帖时要带上 `attachments` 和 `attachments_check`。

#### D5. 点赞 / 点踩

- 文件：`$ROOT/logic/service/src/post.rs:145`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `topic_recommend` |
| `__act` | `add` |
| `value` | `1`（赞）或 `-1`（踩） |
| `tid` | 话题 id |
| `pid` | 帖子 id（**主楼用 `0`**） |

响应 JSON：`data["1"]` 或 `data["0"]` 是分数增量（`delta`）。客户端据此判断最终状态：

- UPVOTE 且 delta > 0 → `UP`
- DOWNVOTE 且 delta < 0 → `DOWN`
- 其他 → `NONE`（即再点一次是取消）

投票状态本地 sled 缓存在 `/vote_response/topic/<tid>/post/<pid>`。

---

### 【E】收藏

#### E1. 收藏 / 取消收藏话题

- 文件：`$ROOT/logic/service/src/topic.rs:729`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `topic_favor_v2` |
| `__act` | `add`（收藏）/ `del`（取消） |

| Form | 值 |
|---|---|
| `tid`（ADD 时）/ `tidarray`（DELETE 时） | 话题 id |
| `folder` | 收藏夹 id（`-1` 表示新建收藏夹） |

> **增删用的参数名不同**：加是 `tid`，删是 `tidarray`。成功时响应含"操作成功"。

#### E2. 收藏夹列表

- 文件：`$ROOT/logic/service/src/topic.rs:351`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `topic_favor_v2` |
| `__act` | `list_folder` |
| `page` | `1` |

响应 JSON：`data["0"].*`，字段 `id` `name` `length`（话题数）；**存在 `default` 键表示默认收藏夹**。

#### E3. 新建收藏夹

- 文件：`$ROOT/logic/service/src/topic.rs:414`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `topic_favor_v2` |
| `__act` | `new_folder` |
| `raw` | `3` |

| Form | 值 |
|---|---|
| `name` | 收藏夹名 |
| `opt` | `2`（设为默认）/ `0` |

响应：新 folder_id 在 `data["1"]` 或 `data["0"]`。

#### E4. 修改 / 删除收藏夹

- 文件：`$ROOT/logic/service/src/topic.rs:376`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `topic_favor_v2` |
| `__act` | `modify_folder`（重命名/设默认）或 `del_folder`（删除） |
| `raw` | `3` |

| Form | 值 |
|---|---|
| `name` | 新名字（重命名时） |
| `opt` | `2`（设为默认时） |
| `folder` | 收藏夹 id |

---

### 【F】通知 / 私信

#### F1. 拉取全部通知

- 文件：`$ROOT/logic/service/src/noti.rs:87`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `noti` |
| `__act` | `get_all` |

响应 JSON，从三个 pointer 取数组：`/0/0`、`/0/1`、`/0/2`。每条通知是**纯数字下标的对象**：

| Key | 含义 |
|---|---|
| `0` | 通知类型（见下） |
| `1` | 对方 uid |
| `2` | 对方用户名 |
| `5` | 话题标题（原始，需 `parse_subject`） |
| `6` | 话题 tid |
| `7` | 对方的 pid |
| `8` | 我的 pid |
| `9` | 时间戳 |
| `10` | 对方帖子所在页码 |

通知类型枚举值：

| 值 | 含义 |
|---|---|
| `1` | 回复我的话题 |
| `2` | 回复我的回复 |
| `7` | 话题中 @ 我 |
| `8` | 回复中 @ 我 |
| `10` | 新私信 |
| `11` | 私信回复 |
| `17` | 我的帖子获得评价 |

**已读状态完全是本地的**：MNGA 用 `id = "<timestamp>-<type>-<tid>-<pid>"` 生成稳定 ID，存进 sled（前缀 `/noti_v2`），**只在本地不存在时插入**（这样已读标记不会被刷新覆盖）。`mark_noti_read` 是纯本地同步操作，不发网络请求。

响应里其实还有个 `data.0.unread` 未读数字段，但源码里注释掉了。

#### F2. 私信会话列表

- 文件：`$ROOT/logic/service/src/msg.rs:57`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `message` |
| `__act` | `message` |
| `act` | `list` |
| `page` | 页码 |

响应 JSON：会话在 `data["0"].*`，字段 `mid` `subject` `from` `from_username` `time` `last_modify` `posts` `all_user`。

`all_user` 是 **`\t` 分隔的 (uid, username) 交替序列**，按 2 个一组切分。

`data.nextPage` 非空表示还有下一页（MNGA 把 pages 设成 `u32::MAX` 表示"未知但还有"）。

#### F3. 私信详情

- 文件：`$ROOT/logic/service/src/msg.rs:106`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `message` |
| `__act` | `message` |
| `act` | `read` |
| `mid` | 会话 id |
| `page` | 页码 |

响应 JSON：
- `data["0"].userInfo.*` —— 参与用户
- `data["0"].allmsgs.*` —— 消息（字段 `id` `from` `subject` `content` `time`）
- `data["0"].nextPage` —— 是否有下一页
- `data["0"].allUsers` —— 同 F2 的 `\t` 分隔格式

#### F4. 发送 / 回复私信

- 文件：`$ROOT/logic/service/src/msg.rs:180`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `message` |
| `__act` | `message` |
| `act` | `reply` 或 `new` |
| `subject` | 转义后标题 |
| `content` | 转义后正文 |
| `to` | 收件人（见下） |
| `mid` | 会话 id（回复时） |

`to` 参数被 push 了两次：一次是 `action.single_to`（单发某 uid），一次是 `to` 数组用**空格**连接的字符串。空值会被自动过滤，所以实际只有一个生效。

`ShortMessagePostAction.Operation`：`REPLY`→`reply`、`NEW`→`new`、`NEW_SINGLE_TO`→`new`。

---

### 【G】用户

#### G1. 获取用户信息

- 文件：`$ROOT/logic/service/src/user.rs:212`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `ucp` |
| `__act` | `get` |
| `uid` 或 `username` | uid 优先；uid 为空时用 username |

响应 JSON：用户在 `data["0"]`，字段同 C1 的用户字段（`uid` `username` `avatar` `regdate` `postnum`/`posts` `fame`/`rvrc` `signature`/`sign` `buffs` `ipLoc` `mute`）。

用户不存在时返回 `{"error":{"0":"找不到用户"},...}`（注意"找不到用户"在成功白名单里，需要额外判断 user 是否为空）。

#### G2. 获取用户头像（G1 没返回头像时的补充请求）

- 文件：`$ROOT/logic/service/src/user.rs:234`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `ucp` |
| `__act` | `get_avatar` |
| `uid` | 用户 uid（**必须用 uid，不能用用户名**） |

响应：头像 URL 在 `data["0"]`。

#### G3. 修改签名

- 文件：`$ROOT/logic/service/src/user.rs:261`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `set_sign` |
| `__act` | `set` |
| `uid` | 当前登录用户 uid |
| `sign` | `escape_for_submit` 转义后的签名 |

成功后需要失效本地用户缓存。

#### G4. 签到

- 文件：`$ROOT/logic/service/src/clock_in.rs:26`
- `POST nuke.php`

| Query | 值 |
|---|---|
| `__lib` | `check_in` |
| `__act` | `check_in` |

无额外参数。客户端按 `UTC+8` 的日期字符串（`%Y-%m-%d`）在 sled 里记录 `/clock_in/user/<uid>`，同一天不重复请求。"今天已经签到"在成功白名单里，不算错误。

---

### 【H】非 NGA 的 API（MNGA 自建）

#### H1. Mock API

- 文件：`$ROOT/logic/service/src/fetch.rs:597`
- `GET https://mnga-pages.bugenzhao.com/api/<base62(protobuf(MockApi))>`

用于"MNGA Meta"伪版块（关于/反馈页面）。请求把 `MockApi` protobuf 序列化后 base62 编码成路径，响应直接是 protobuf 编码的 `TopicListResponse` / `TopicDetailsResponse`。

触发条件：`TopicListRequest.id.fid` 或 `TopicDetailsRequest.topic_id` 以 `mnga_` 开头。

生成器：`$ROOT/logic/mock_gen/`（读 YAML 生成静态 protobuf 文件）。

> 复刻时这部分可整体略过。

#### H2. 反向代理

`https://nga.bugenzhao.com` —— 作者自建，路径与官方一致，仅在 `RetryMode` 允许代理时作为兜底。

> 复刻时需要自建；或者把 `ProxyMode` 一律设成 `Never`。

---

## 2.4 完整端点汇总表

| 端点 | `__lib` | `__act` | 功能 | 响应 |
|---|---|---|---|---|
| `app_api.php` | `home` | `category` | 版块分类树 | JSON |
| `forum.php` | — | — | 版块搜索（`key`） | XML |
| `thread.php` | — | — | 话题列表 / 搜索 / 收藏 / 用户话题 / 用户回复 | XML |
| `read.php` | — | — | 帖子详情 | XML / HTML |
| `post.php` | — | — | 拉编辑内容（`action`）/ 发帖（`action`+`step=2`） | XML |
| `<attach_url>` | — | — | 附件上传（multipart） | XML |
| `nuke.php` | `noti` | `get_all` | 通知列表 | JSON |
| `nuke.php` | `message` | `message` | 私信（`act`=`list`/`read`/`new`/`reply`） | JSON |
| `nuke.php` | `ucp` | `get` / `get_avatar` | 用户信息 / 头像 | JSON |
| `nuke.php` | `set_sign` | `set` | 修改签名 | JSON |
| `nuke.php` | `check_in` | `check_in` | 签到 | JSON |
| `nuke.php` | `topic_favor_v2` | `add`/`del`/`list_folder`/`new_folder`/`modify_folder`/`del_folder` | 话题收藏 & 收藏夹 | JSON |
| `nuke.php` | `forum_favor2` | `forum_favor` | 版块收藏（form `action`=`get`/`add`/`del`） | JSON |
| `nuke.php` | `topic_recommend` | `add` | 点赞 / 点踩 | JSON |
| `nuke.php` | `log_post` | `report` | 举报 | JSON |
| `nuke.php` | `user_option` | `set` | 子版块订阅 / 屏蔽 | JSON |
| `nuke.php` | `login` | `account` | 登录页（WebView 打开，非 API） | HTML |

---

# 第 3 部分：复刻要点与坑

按重要性排序，这些是从源码里能看出来、但接口文档里通常不写的东西：

1. **认证走 POST form 的 `access_token`/`access_uid`，不是 Cookie**。Cookie（`ngaPassportUid`/`ngaPassportCid`）只在 WebView 登录时抓一次，之后转成这两个字段。

2. **所有请求都是 POST，但业务参数在 URL query 里**，body 只放认证。

3. **空值参数必须丢弃**。整套代码大量依赖这个行为（bool 的 false 编码成空串、fid/stid 二选一、`to` 参数双写等）。

4. **响应编码回落 GB18030**（响应头没声明 charset 时），同时请求带 `__inchst=UTF8`。

5. **JSON 响应不合法**：整数会直接作 key（需正则 `([{,}]\s*)(\d+)(:)` 加引号），字符串里可能有裸控制字符（需转义）。

6. **提交内容必须做 UTF-16 实体转义**（emoji、ZWJ `0x200D`、变体选择符 `0xFE00`-`0xFE0F`、`0x2600`-`0x27BF`），读取时要做**两轮** HTML 实体解码 + 代理对还原。

7. **`read.php` 用 Windows Phone UA 更不容易被封**（`NGA_WP_JW/(;WINDOWS)`）。

8. **`lite=xml` 和 `__output=10` 是等价的两套返回格式**，交替尝试可绕过部分封锁；成功组合应缓存下来优先使用。

9. **真实 tid 要看 `quote_from`**：非空且非 `"0"` 时它才是真 tid，`tid` 字段反而是引用来源。

10. **`fav` 码从 `tpcurl` 正则提取**（`fav=([a-fA-F0-9]+)`），是访问隐藏/过期帖子的钥匙。

11. **匿名用户名可本地还原**：`#anony_<32 hex>` 按 6 段还原（第 0、3 段查 22 字天干地支表 `甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥`，其余段查约 440 字百家姓表），算法在 `$ROOT/logic/service/src/user.rs:76-131`，有单测可对照。

12. **匿名用户 id 需要加请求级 context 前缀**，否则不同页面的 `-1`、`-2` 会串号。

13. **通知已读状态服务端不提供，必须本地维护**，ID 用 `<timestamp>-<type>-<tid>-<pid>` 拼接，刷新时只插入新的、不覆盖已有的。

14. **收藏话题增删的参数名不同**：加是 `tid`，删是 `tidarray`。

15. **子版块订阅/屏蔽的参数名就是操作**：`del=<id>` 是订阅，`add=<id>` 是屏蔽。

16. **附件上传是两步**：先 `post.php?action=...` 拿 `auth` + `attach_url`，再往 `attach_url` POST multipart；发帖时带 `attachments`（`\t` 连接）+ `attachments_check`。

17. **热门话题不是 API**，是客户端抓多页后本地排序。

18. **Web HTML 兜底方案值得实现**：`$ROOT/logic/service/src/topic/web_to_xml.rs` 从 `commonui.postArg.proc(...)` 等内联 JS 反解数据，合成成 XML 结构复用下游解析代码，是 XML 接口被封时的救命稻草。

19. **NGA 会在 HTTP 非 2xx 状态下仍返回有意义的错误信息**，所以先解析 body、body 为空时才用状态码报错。

20. **五个"假错误"要当成功处理**：`完毕` `没找到` `没有符合条件的结果` `今天已经签到` `找不到用户`。

21. **`topic_misc` 的标题样式是 base64 TLV**，不是普通整数字段。

22. **子版块的 `attributes` 魔法数**（`[7, 558, 542, 2606, 2590, 4654]` 表示已订阅，`> 40` 表示可过滤）没有文档依据，是作者试出来的，可能随 NGA 更新失效。

---

# 关键文件索引

| 用途 | 绝对路径 |
|---|---|
| **HTTP 层（最重要）** | `$ROOT/logic/service/src/fetch.rs` |
| 常量（UA / base URL / 成功白名单） | `$ROOT/logic/service/src/constants.rs` |
| 域名规范化 | `$ROOT/logic/service/src/request.rs` |
| 认证 | `$ROOT/logic/service/src/auth.rs` |
| 话题列表 / 详情 / 收藏 | `$ROOT/logic/service/src/topic.rs` |
| Web HTML 兜底 | `$ROOT/logic/service/src/topic/web_to_xml.rs` |
| 发帖 / 投票 / 附件 | `$ROOT/logic/service/src/post.rs` |
| 版块 | `$ROOT/logic/service/src/forum.rs` |
| 用户 / 匿名名还原 | `$ROOT/logic/service/src/user.rs` |
| 私信 | `$ROOT/logic/service/src/msg.rs` |
| 通知 | `$ROOT/logic/service/src/noti.rs` |
| 签到 | `$ROOT/logic/service/src/clock_in.rs` |
| 浏览历史 / 阅读进度 | `$ROOT/logic/service/src/history.rs` |
| 缓存管理 | `$ROOT/logic/service/src/cache.rs`、`$ROOT/logic/cache/src/lib.rs` |
| XPath / 错误提取工具 | `$ROOT/logic/service/src/utils.rs` |
| 服务分发 | `$ROOT/logic/service/src/dispatch/{mod,handlers_async,handlers_sync}.rs` |
| BBCode 语法（peg） | `$ROOT/logic/text/src/content.rs` |
| 标题解析（peg） | `$ROOT/logic/text/src/subject.rs` |
| 提交转义 / 解码 | `$ROOT/logic/text/src/escape.rs` |
| 枚举 → query 值映射 | `$ROOT/logic/protos/src/to_value.rs` |
| 服务契约 | `$ROOT/protos/Service.proto` |
| 数据模型 | `$ROOT/protos/DataModel.proto` |
| Swift FFI | `$ROOT/app/Shared/Logic/BasicLogicCall.swift`、`LogicCall.swift` |
| C ABI 头文件 | `$ROOT/logic/logic/bindings.h` |
| 登录（Cookie 抓取） | `$ROOT/app/Shared/Views/LoginView.swift` |
| 鉴权存储 / 多账号 | `$ROOT/app/Shared/Storage/AuthStorage.swift` |
| URL / 图片域名规范化 | `$ROOT/app/Shared/Utilities/URLs.swift` |
| BBCode → SwiftUI 渲染 | `$ROOT/app/Shared/Utilities/ContentCombiner.swift` |
| 分页数据源 | `$ROOT/app/Shared/Models/PagingDataSource.swift` |
| 设置项 | `$ROOT/app/Shared/Views/PreferencesView.swift`、`$ROOT/app/Shared/Storage/PreferencesStorage.swift` |
| 深链 | `$ROOT/app/Shared/Models/SchemesModel.swift`、`$ROOT/app/Shared/Utilities/Constants.swift` |

---

# 附：外部参考资料

`$ROOT/AGENTS.md` 里作者列出了三个 NGA 接口文档参考源，建议交叉验证：

- https://github.com/wolfcon/NGA-API-Documents/wiki
- https://gitee.com/AgMonk/nga-api-doc
- 安卓开源客户端：https://github.com/Justwen/NGA-CLIENT-VER-OPEN-SOURCE

作者也提醒：「这些信息可能不完全准确或不是最新的，行动前请比对多个信息源。」

另外，Rust 侧的每个 service 模块都带 `#[ignore = "manual: requires network or mutable external state"]` 的真实网络集成测试，里面有大量可直接复用的**真实 tid / uid / fid 样例**（例如 tid `45150945` 用于通用功能验证、fid `650` 是"原神"版、uid `41417929` 是作者本人），复刻时可作为对拍基准。
