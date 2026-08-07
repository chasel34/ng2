# 05 — 主题列表页

**What to build:** 点版块进入主题列表:35 条/页无限滚动 + 下拉刷新 + 底部「正在载入第 N 页」;标题呈现彩色标题掩码样式、[锁定]/合集加粗/镜像行/[分类] 标签/回复数;匿名作者本地还原为六字假名;fav 码与真实 tid(quote_from)在数据层正确提取;子版块 tag 横条展示;顶栏星标/搜索/菜单占位,FAB 点击 toast。排序切换(最后回复/发帖时间)经菜单可用。

**Blocked by:** 02, 04

**Status:** resolved

- [x] titlefont 与 topic_misc 两种彩色标题来源都解码正确(单测)
- [x] 匿名还原算法与参考实现输出一致(单测)
- [x] parent 字段对象/字符串化 JSON 两种形态都能解析(单测)
- [x] 列表行与设计稿 1:1(两行布局、图标、颜色、间距)

## Comments

### 实现摘要(2026-08-08)

**纯本地算法 `src/core/local/**`(新目录,零 RN 依赖,18 例单测)**

- `anonymous.ts` —— 匿名还原。**算法与两张字符表直接取自 NGA 官方前端 `commonui.anonyName`**
  (`https://img4.nga.cn/common_res/js_commonui.js`,与 06 票取表情映射表同一条来源约定;
  没碰 GPL-2.0 的 Justwen 代码,也没抄 MNGA)。取位规则是官方那段循环的直译:
  hex[0] 查天干地支表,[1,2]/[3,4] 查百家姓表,**hex[5] 跳过**,[6] 干支,[7,8]/[9,10] 百家姓,
  [11..16]/[17..22] 是两个色值。百家姓表官方只有 **255** 字而下标来自一整字节,`0xff`
  落在表外 —— 网页版此时就少一个字(`#anony_ffff…` → 「巳巳」),照抄不补齐。
  期望值是把官方那段函数原样跑出来的,不是照本实现算的。
- `title-style.ts` —— 彩色标题解码。`topic_misc` 是 base64(无 padding)的 TLV(1 字节 type +
  4 字节大端),type 1/2/3 = 字体掩码/stid/子版块 fid;`titlefont` 是同一套掩码的老字段。
  两个来源都在时以 `topic_misc` 为准。**颜色位可能同时点亮,官方是 else-if 链 → 红>蓝>绿>橙>银
  取第一个**;粗/斜/下划线可叠加。官方对以 `~`/`~1` 结尾的值直接跳过,一并复刻。
  base64 自己解(RN 没有稳定的 `atob`,core 又不能碰平台 API)。

**数据层 `src/core/api/topic-list.ts`(21 例单测)**

- `parseTopicList(data)` + `fetchTopicList(fetchNga, {boardId, kind, page, sort})`。
  合集传 `stid`、普通版块传 `fid`;`sort: 'postDate'` 才带 `order_by=postdatedesc`。
- **真实 tid**:`quote_from` 非 0 时以它为准,否则 `tid`,都没有就从 `tpcurl` 正则捞。
- **fav 码**:`tpcurl` 里正则抠 `fav=([0-9a-fA-F]+)`,随导航参数带进详情页。
- **`parent` 两形态都解**(对象 / 2024-04 之后的字符串化 JSON),解不出名字就当没有。
- **`type` 位掩码**:1024 锁定、8192 有附件、0x8000 合集、0x200000 版块镜像。后两者是
  「快捷方式行」,`shortcut` 指出该跳哪个版块(合集的 stid 就是它自己的 tid;镜像的目标 fid
  在 `topic_misc`/`topic_misc_var` 里)。
- **`sub_forums` 保留服务端顺序**:它的 key 是 fid/stid 不是下标,用 `orderedEntries` 按数字排
  会把子版块顺序打乱(写测试时抓到的,已改成 `Object.entries`)。key 带 `t` 前缀 = 合集。
- `mergeTopicPages` 按 tid 去重:**置顶主题与镜像行每页都会再回来**(实测 fid=-7 第 1、2 页重叠 20 条)。
- 作者与最后回复人都过 `resolveAuthorName`。
- fixture 是 2026-08-08 抓的 `thread.php?fid=-7` 原始 GBK 字节(51 条,含匿名主题、合集行、
  彩色/加粗标题、各式 `parent`),`__CU.uid` 已脱敏成 10000001。

**UI**

- `src/app/board/[id].tsx` 替换 04 的占位:顶栏(返回/标题/星标/搜索/更多)+ 子版块 tag 横条 +
  FlashList 无限滚动 + 下拉刷新 + 底部「正在载入第 N 页…」+ FAB。数值照设计稿 `isList` 段抄
  (行 14/16/12、标题 17·1.45、信息行 12.5、tag 6/13 圆角 9、载入提示 20 内距 + 70 让位给 FAB)。
- `src/ui/topic-row.tsx` 列表行:标题行 = 彩色标题 + `[锁定]` + 附件 `+` + 来源子版块 `[…]`,
  四段之间用不断行空格(设计稿的 `&nbsp;`);信息行 = 作者(匿名已还原)——推到右边——最后回复人 + 回复数。
- 排序切换在右上菜单里,选中项用主题色 + 600 标出(给 `ui/menu.tsx` 的 `MenuItem` 加了个
  可选的 `selected`,纯增量)。
- `src/app/topic/[tid].tsx`:帖子详情的占位路由,先把参数约定固定下来(`tid`/`fav`/`title`),07 票换掉。
- token 补两档并在 `tokens.test.ts` 登记:`topicTitle`(17·1.45)、`listMeta`(12.5);
  另加 `theme.titleColors` 五档(红/蓝/橙/银复用 danger/link/accent/meta,绿是 token 表没有、按同明度补的)。

### 与设计稿/票面的已知偏差(都有原因)

1. **信息行右侧是「最后回复人 + 回复数」,不是票面写的「时间」**:设计稿 `isList` 模板与参考截图
   都是最后回复人,验收项写的是「与设计稿 1:1」,按设计稿来。
2. **子版块横条没有第一枚「全部」chip**:设计稿的 `subTags` 第一项是「全部」,但本版本的 chip 点了是
   **跳到那个子版块**(没有「在本版块内按子版块过滤」这个能力),「全部」没有对应动作,先不画。
   23 票做子版块订阅/屏蔽时一起定。
3. **菜单里写「子版块」而不是设计稿的「子板块」**:CONTEXT.md 的词条是子版块,术语表优先。
4. **顶栏星标没有填充态**:设计稿有 `FILL` 切换,那是版块收藏(10 票)的状态,现在点了只 toast。
5. **镜像行不额外加粗**:设计稿数据里镜像行 `weight` 是默认 400,而真实响应里它的粗体本来就写在
   `topic_misc` 掩码(32)里,所以交给掩码;只有合集行按设计稿固定加粗。

### 遗留问题

1. **真机没验**:只跑了 `pnpm typecheck`、`pnpm test`(348 例)与 `expo export --platform android`。
   无限滚动的触发时机、下拉刷新手感、FlashList 在变高行(标题 1–3 行)上的表现都要 Android 真机确认。
2. **下拉刷新会砍掉已翻的页**:`useRefreshTopicList` 先把 pages 截到第一页再重取,否则翻到第 10 页
   下拉一次就是 10 个 `thread.php`(ADR-0002 的封号风险)。代价是刷新后要重新往下翻。
   18 票接反封锁链时请确认这个取舍。
3. **错误页与首页重复**:`center/errorText/retry/retryLabel` 四条样式与那段 JSX 和 `src/app/index.tsx`
   基本一样,`openBoard` 也是。等 07/10/11/14/15 再出现两三处时抽成 `ui/` 里的公共组件更划算,
   本票没动首页(纪律:不重构已交付的票)。
4. **fav 码只是带进了详情页的路由参数**,07 票要把它接到 `read.php?fav=`。
5. **`__F.topped_topic`(版头)没解**:17 票要用时补一行即可,本票不做(YAGNI)。
6. **精华区/热帖/浏览历史/收藏夹菜单项全是 toast**:分别等 17、16、11 票。
7. **没做「已读变暗」与屏蔽过滤**:功能文档 §2.2 里有,分别属 16 与 21 票。
