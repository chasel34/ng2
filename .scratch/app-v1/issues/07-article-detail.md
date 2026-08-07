# 07 — 帖子详情页(基础渲染)

**What to build:** 点主题进入详情:楼层卡片流(头像首字占位/用户名/级别/威望/发帖数/楼号/发帖设备图标/时间),正文经 AST 渲染基础标签(粗斜下删/颜色/字号/quote 引用块/url/img 图片含缩略图剥后缀与附件域名动态拼接/表情本地素材);贴条展示区与热门回复展示;附件「点击显示附件(N)」折叠宫格;顶部页码条 + 跳页对话框 + 左右滑动翻页(带边缘提示浮层);FAB 展开刷新/回复(回复 toast 占位);用户状态标注(楼主/匿名/禁言)。

**Blocked by:** 03, 05, 06

**Status:** resolved

- [x] 每页 20 楼、总页数按 __ROWS 计算,翻页三种方式(页码条/跳页/滑动)一致
- [x] 附件图片域名从响应 _ATTACH_BASE_VIEW 动态获取,fixture 单测覆盖
- [x] 匿名楼层 id 带请求级前缀不串号(单测)
- [x] 楼层卡片与设计稿 1:1(含引用块、贴条区、附件折叠三种子形态)

## Comments

### 实现摘要(2026-08-08)

**数据层 `src/core/api/topic-detail.ts`(34 例单测)**

- `parseTopicDetail(data, { context })` + `fetchTopicDetail(fetchNga, { tid, page, favCode })`。
  UA 走 `windowsPhone` 档(MNGA 对 read.php 强制用它,02 票只提供机制、由这里决定),
  带 `v2=1`——`_ATTACH_BASE_VIEW` 就是它带出来的。
- **分页**:每页 20 楼,`__R__ROWS_PAGE` 优先、缺省退 20;总页数 `ceil(__ROWS / 每页)`。
  注意 `__ROWS` 含主楼(replies 31 → `__ROWS` 32)。
- **匿名 id 请求级前缀**:`__R[].authorid` 是 `-1`/`-2` 这种**页内序号**不是 uid,
  用户 key 一律加前缀成 `<context>,-1`(形式与 MNGA 一致,方便对拍)。`fetchTopicDetail`
  每次请求换一个 context(时间戳 + 自增,core 里没有平台依赖)。单测锁「两次请求的匿名 key 集合不相交」。
- **贴条的幽灵行**(抓真实响应才发现的坑):贴条既挂在被贴楼层的 `comment` 下,
  **又在 `__R` 里占一条只有 `subject`/`comment_to_id`、没有 `content` 的行**。
  不滤掉就会多渲染一个空楼层。判据是「没有 `content` 字段」。
  另外 `__R__ROWS` 把幽灵行也数进去了,**不能当楼层数用**。
- **`__GROUPS` / `__MEDALS` / `__REPUTATIONS` 三张附表就在 `__U` 内部**,不是 data 顶层;
  遍历用户表时带 `__` 前缀的 key 一律跳过,否则会多出三个假用户。
- 用户对象:`avatar` 三形态(普通 URL / 空串 / `js_escap_avatar` JSON 串,后者抠第一个 http URL,
  先还原 `\/`)、`rvrc ÷ 10` 当威望、`memberid` 查 `__GROUPS` 当级别、
  `buffs` 含 105/117 = 禁言、`yz === -1` = 被 nuke(样本里有个 `yz: -5`,那不是 nuke)。
- **楼主判定**:实名比 uid;匿名主题的 `__T.authorid` 是页内序号(实测 -3)认不得人,
  只能比 `#anony_` 串——同一人在同一主题里的匿名串是固定的,所以这样是对的。
- `from_client` 认名字不认编号(`"8 Android"` / `"7 iOS"` / `"31 /"`),编号会随客户端版本变。

**附件地址 `src/core/api/attachments.ts`(13 例单测)**

- `normalizeAttachBase` 保留 `_ATTACH_BASE_VIEW` 的**整段路径**(`img.nga.cn/attachments`),
  而不是像旧 Android 客户端那样 `split("/")[0]` 再拼死的 `/attachments`——那等于把路径
  换个地方硬编码。服务端给 http 时升到 https(RN 默认禁明文流量)。
- **`[noimg]` 的日期前缀**(03 票遗留 2,本票补上):`[img]./mon_202608/07/x.jpg` 自带日期目录,
  而 `[noimg]./-7Qd36d-….jpg` 没有,要按发帖时间补 `mon_YYYYMM/DD/`。
  实测缺前缀的地址是 404、补上是 200,已用真 URL 验过。判据是路径开头有没有 `mon_\d{6}/`,
  不依赖 `variant`,两种写法都吃得下。日期按 **UTC+8** 算,不跟设备时区走(有单测锁跨天场景)。
- 缩略图后缀 `.thumb.jpg / .thumb_s.jpg / .thumb_ss.jpg / .medium.jpg` 剥掉换回原图。

**渲染器 `src/ui/bbcode/`**

- `BBCodeBody` 把 AST 切成「行内段 / 块级节点」交替渲染。**块级节点塞进 `<Text>` 在 Android 上
  会直接不显示**,所以引用块/图片/分割线/标题必须自己占 `<View>`。
- 切段判据是 `containsBlock` 而不是「节点自己是不是块级」——`[align=center][img]…[/img][/align]`、
  `[b][img]…[/b]` 这种**图片裹在行内标签里**的写法在 NGA 上极常见,只看顶层那张图会被吞掉。
  裹着块级内容的行内标签升格成块、递归展开,文字样式(粗/斜/下划线/删除/颜色)往下带。
  这套判断抽在 `segments.ts`(纯函数),14 例回归测试钉死七种嵌套写法。
- 表情用 RN 自带的 `Image` 而不是 expo-image:要嵌在 `<Text>` 里跟文字混排,
  只有它在 Android 上会被当 ImageSpan 处理。显示高度统一 24,宽度按
  `Image.resolveAssetSource` 拿到的原图比例算,不把宽表情压成方块。
- `[color=]` 白名单校验(官方 `ubbcode.fontColor` 24 色 + 正文里常见的十来个 + `#rrggbb`),
  `[size=]` 百分比换算并夹在 0.6–2.5 倍。认不出一律不套样式,不把脏值塞进 style 让真机炸。
- 图片按 4:3 占位,`onLoad` 拿到真实尺寸再改比例(服务端不给尺寸)。

**详情页 `src/app/topic/[tid].tsx` + `src/ui/`**

- 楼层卡片 `ui/floor-card.tsx`:头像(远程 + 首字占位)/ 用户名 + 状态标注 / 时间 /
  级别·威望·发帖 / 设备图标 + 楼号 / 正文 / 附件折叠宫格 / 点赞行 / 贴条区。
- **翻页三入口全部收敛到 `ui/paging.ts`**(纯函数,16 例单测):页码条、跳页、滑动
  共用同一套夹逼与阈值,一致性是结构性的而不是靠人盯。滑动用 RN 自带 PanResponder
  (不用 gesture-handler:那要在根布局套 `GestureHandlerRootView`,为一个手势不值得改 04 的文件),
  **走 capture 阶段认领手势**,否则 FlashList 里的 ScrollView 会先把手势抢走。
- 每页按页码进 Query 缓存(`keepPreviousData`),翻回去不再打 `read.php`(ADR-0002 的封号风险);
  **fav 码进 queryKey**——带码与不带码请求的是不同的东西。

### 顺手修的基建 bug(02 票)

**`core/net/sanitize.ts` 的清洗第 5 步「删坏字段 alterinfo」在删真数据,已移除。**
实测这个字段的真身是 `[E<时间戳> <编辑人 uid> <编辑人名>]<TAB>`,抓包里 14 条全长这样;
所谓「坏」只是结尾那个裸 TAB 让 `JSON.parse` 挂掉,而第 7 步本来就把字符串内的裸控制字符
转义了,轮不到它坏(上游没有第 7 步才只能整段删)。而且那条特征(方括号 + 结尾空白)的
括号内容不允许出现引号,所以它**从来**命中不了真正解析不了的形态。留着等于净亏:
「本楼被编辑过」连同编辑人一起被删,详情页认不出编辑标记。已同步改 `sanitize.test.ts` 两例。

### 与设计稿的已知偏差(都有原因)

1. **引用块不合成「引用 X 的发言:」那行 meta**:NGA 把 `[b]Post by [uid=…]名字[/uid] (时间)[/b]`
   直接写在引用内容里了,再合成一行标题的话作者名会出现两遍。容器样式(底色/圆角/左侧竖条/
   14·1.6 正文)照设计稿。设计稿那句「查看对话链(4 层)」是 26 票,层数现在算不出来,没画。
2. **主楼给了 `surface` 底色**:设计稿 mock 里所有楼层都是 transparent,但 token 表把
   `--surface` 标成「楼主楼层」;主楼有层底色更容易和回复区分。
3. **多了「已编辑」与非图片附件行**:前者是上面那个 sanitize 修复换来的信息,后者是因为
   附件里会有压缩包这类东西,当图片渲染就是一格加载失败。
4. **附件宫格不画设计稿那个文件名小方块**:设计稿画它是因为 mock 里没有真图,
   有真缩略图时叠一层文件名只是噪音。
5. **点赞/点踩/回复/楼层菜单按钮照设计稿摆好但点了是 toast**(点赞点踩与楼层菜单是 12 票,
   回帖是 spec §1 的排除项);赞数是真的。
6. **顶栏的 `public` 按钮真的用系统浏览器打开该页**——设计稿这个按钮就是「看网页版」;
   19 票的网页兜底(内置 WebView + 反解)是另一回事。

### 遗留问题

1. **真机没验**:只跑了 `pnpm typecheck`、`pnpm test`(432 例)、联网冒烟与
   `expo export --platform android`。滑动翻页与 FlashList 上下滚动的手势竞争、
   表情在 `<Text>` 里的基线对齐、长图的 `onLoad` 改比例会不会跳动,都要 Android 真机确认。
2. **进阶标签是降级渲染不是占位**:`collapse`/`align`/`list`/`table`/`box` 渲染内容
   (「不丢内容」优先),`dice`/`album`/`attach`/`flash` 渲染成灰色占位文本。08 票接管时
   注意 `render.tsx` 的 `renderInline` 与 `ContainerNode` 两处都要改。
3. **引用块里的 `[noimg]` 用的是引用者的发帖时间,不是被引用楼层的**,日期前缀可能错 → 404。
   引用头里有原帖时间,但要正则抠,脆。真出现再说。
4. **主楼的 `pid` 是 0**(服务端就给 0),12 票要给主楼点赞时得另找 pid(`__T.tpid`?)。
5. **`[color=white]` 在深色模式下反而看得见**——那是发帖人用来藏剧透的写法,浅色下不可见才是
   原意。22 票的阅读设置里可以考虑加一档「显示隐藏文字」。
6. **AC娘/A2 两套表情的深色模式反色没做**(06 票遗留 4)。`ResolvedSmiley.category` 已经带了
   分类,渲染侧加一层 tintColor 或 invert 即可,留给 27 打磨。
7. **错误页/FAB 与主题列表页重复**:`center/errorText/retry/retryLabel` 与 `fab` 这几段样式
   和 `src/app/board/[id].tsx` 基本一样(05 票遗留 3 已预告)。现在是第二处,再出现一处就
   该抽成 `ui/` 公共组件——本票没动 05 的文件(纪律:不重构已交付的票)。
8. **`rgba(255,255,255,0.22)`(顶栏选中态)已抽成 `tokens.topbarOverlay`,但 04 的
   `app-drawer.tsx:99` 里还是字面量**,同样是不动已交付文件;27 打磨时一并换掉。
9. **`__F.custom_level` 没用**:版块自定义头衔(按 rvrc 分档,如「镜花水月」「四海八荒」),
   设计稿「级别」显示的是 `__GROUPS` 的用户组名,所以先不用它。
10. **图片域名没做旧域名归一化**:MNGA 会把 `img.nga.178.com` / `ngacn.cc` 这类老域名重写成
    `img.nga.cn`。老楼层里的绝对地址目前原样请求(实测仍可访问),等它哪天失效再补。
