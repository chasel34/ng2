# 13 — 通知

**What to build:** 登录后前台每 60s 轮询通知;「最近被喷」页按类型分组(回复我的/@我的/给我贴条的),条目点击跳到对应主题楼层;已读状态纯本地(稳定 ID:时间戳-类型-tid-pid,刷新只增不覆盖);抽屉入口未读角标;顶栏删除按钮一键清空(服务端+本地)。短信类通知条目展示但点击 toast 占位。

**Blocked by:** 07, 09

**Status:** resolved

- [x] 本地已读模型单测:重复拉取不重置已读、新条目正确识别
- [x] 点击通知能定位到对方楼层所在页
- [x] 被喷页与设计稿 1:1(分组头/条目三行结构)

## Comments

### 实现摘要(2026-08-08)

`pnpm typecheck` 与 `pnpm test`(618 个单测,44 个文件)全绿,`npx expo export --platform android` 能打出包。零新增依赖(expo-sqlite 已在 package.json)。

**core 层(零 RN 依赖)**

- `src/core/api/notifications.ts` —— `nuke.php?__lib=noti&__act=get_all` 的解析(API 文档 §9.1)。三个容器 `"0"`/`"1"`/`"2"` 走同一个条目解析,**分类看条目自己的类型码而不是所在容器**;类型码归成 `reply/comment/mention/message/rating/other`,认不出的进 `other` 照样展示。缺类型码或时间戳的条目跳过(没有这两个就算不出稳定 ID),坏条目不带崩整份。另有 `clearNotificationFeed`(`raw=3&__act=del`,§9.2)。
- `src/core/local/notifications.ts` —— 已读模型。`notificationId({timestamp,type,tid,pid})` 是稳定 ID;`mergeNotifications` **只增不覆盖**(已认识的 ID 保留旧条目,服务端微调字段不会让同一条在两次刷新之间变脸);`newNotifications` / `unreadCount` / `markRead`(无新增时返回原集合,上层拿引用相等免写盘)/ `groupNotifications`。已读集合是独立的一组 ID,合并根本不碰它——「重复拉取不重置已读」是结构性的,不是靠判断。

**store 层**

`src/store/notifications.ts`:Zustand + expo-sqlite。已读 ID 按 `(uid, id)` 联合主键分桶存 `notifications.db`,切号各看各的;**条目列表本身不持久化**——get_all 每次返回近期全量,已读靠稳定 ID 对上号。sqlite 打不开(web 预览/存储损坏)就退化成内存态,不影响本次会话可用。`useNotificationsPoller()` 挂在 `_layout` 根上:只在 `AppState === 'active'` 起 60s 定时器,退后台立即 `clearInterval`,回前台先补一次;登出/切号由 `activate()` 换桶清列表并停掉旧账号的轮询。拉取期间切了号的结果整个丢弃(每个 await 后都比一次 `activeUid`)。

**页面**

`src/app/notifications.tsx` 照设计稿 `isNotify` 屏:顶栏「我的被喷」+ 删除按钮;分组头 = 图标 + 组名 + 「N 条」;条目 = 36 见方头像 + 三行。设计稿第二行画的是对方内容摘要,但 **noti 接口不给正文**(§9.1 只有主题标题),这一行放主题标题、第三行放「第 N 页 · 时间」。进页即把当前条目全部记已读(角标就是为了引到这儿);点条目 `router.push('/topic/[tid]', {tid, title, page})`,短信类和没有 tid 的条目 toast「本版本未开放」。

**抓不到带数据的真样本**:测试账号的 get_all 响应是空的,而且空账号的形状很刁——`data["0"]` 不是对象而是**空串**(fixture `noti-get-all-empty.gbk.bin`,已单测锁住「空串解成空列表」)。条目级用例按 API 文档 §9.1 + MNGA/Android 两份研报的字段口径构造,三处文档一致,风险可控;真机拿到真通知后建议复核一次字段下标。

**顺带改到的共享文件(都很小、可加性)**

1. `src/app/_layout.tsx` —— 加一行 `useNotificationsPoller()`。
2. `src/ui/app-drawer.tsx` —— 「最近被喷」那一行接上 `/notifications`,`DrawerEntry` 加可选 `badge` 字段,未读时右侧出红底角标(样式取自设计稿短消息屏那颗)。
3. `src/app/topic/[tid].tsx` —— 多认一个可选 `page` 路由参数作初始页码,不带就还是第 1 页。**这处会和 16 票(阅读进度定位)撞车**,合并时按「显式 page 参数优先于记忆的进度」收敛。
4. `src/ui/tokens.ts` + `tokens.test.ts` —— 补 3 档:`notifyMeta`(11)、`notifyInitial`(13/700)、`unreadBadge`(11/700)。

### 遗留问题

1. **真机没跑**。只有 core 单测 + `expo export` 打包通过;逐屏对照按票面约定留给 27 票。尤其要看:有真通知时的分组顺序与三行排版、点条目落在对方楼层那一页。
2. **一键清空没有二次确认**,照设计稿与票面的「一键清空」做。服务端 `del` 不可逆,真机验收时确认误触概率是否可接受。
3. **已读 ID 只增不删**。清空会整桶删掉,平时不修剪——一条记录几十字节,量级上不成问题,真要管的话可以按 read_at 过期。
4. **服务端 `unread` 只作参考**(解出来放在 `serverUnread`),角标用的是本地已读模型算的数,因为服务端不提供逐条已读。
