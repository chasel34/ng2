# RN 版现状全景图(移植对照,探查归档)

> 探查 agent 报告,2026-08-21,基线 `main` @ `497c766`。移植期逐项对照用;file:line 以 RN 侧工作树为准。

## 0. 三个决定整体难度的判断

1. **不是薄客户端**:31.5K 行生产代码约一半是 NGA 私有协议逆向(GBK、宽容 JSON 清洗、反封锁链、HTML 反解)。纯逻辑可直译 Kotlin,但**不能简化**(ADR-0002 四次事故复盘全是砍「多余」逻辑造成的)。
2. **只读向**:发帖/回帖/短消息/投票操作全是 toast 桩(`showNotAvailable()`),编辑面零移植。
3. `docs/API文档.md`(NGA API 完整逆向,§12 端点总表、§13 移植优先级)与 ADR-0002 信息密度比代码还高,**一等移植文档**。

## 1. 导航与屏幕(24 屏)

expo-router 单一 Stack(`_layout.tsx:90`),无底部 tab、无路由级 drawer;「tab」全是屏内自绘横滑 pager;抽屉只挂首页(自实现)。转场 `slide_from_right` 220ms;`image-viewer` 用 transparentModal+fade;`unstable_settings.anchor='index'` 垫底防深链死返回(Kotlin 对应 TaskStackBuilder)。

| # | 路由 | 用途 |
|---|---|---|
| 1 | `/` | 首页:分类 tab + 版块宫格 + 抽屉宿主 |
| 2 | `/board/:id` | 版块/合集主题列表(`id,name?,kind?`) |
| 3 | `/board/hot` | 24h 热帖(客户端聚合) |
| 4 | `/board/recommend` | 精华区 |
| 5 | `/board/sub-boards` | 子版块订阅/屏蔽 |
| 6 | `/topic/:tid` | 主题详情(最大屏 1828 行;`tid,title?,fav?,page?,pid?,floor?`) |
| 7 | `/chain` | 回复链(`tid,pid,fav?`) |
| 8 | `/search` | 搜索三 tab(主题/版块/用户) |
| 9 | `/favorites` | 收藏主题(一次一夹) |
| 10 | `/favorites/folders` | 收藏夹增删改 |
| 11 | `/history` | 浏览历史(纯本地) |
| 12 | `/caches` | 离线帖子缓存管理 |
| 13 | `/filters` | 屏蔽规则三 tab |
| 14 | `/notifications` | 通知(最近被喷) |
| 15 | `/accounts` | 多账号管理 |
| 16 | `/login` | WebView 登录 |
| 17 | `/user/:uid` | 用户资料 |
| 18 | `/user/posts` | 我的主题/回复 |
| 19 | `/image-viewer` | 全屏图片查看器(payload 内存暂存) |
| 20 | `/web` | 网页兜底 WebView |
| 21 | `/settings` | 设置根(5 分组) |
| 22 | `/settings/font-size` | 字号/头像/表情滑块 |
| 23 | `/settings/lab` | 实验室与诊断 |
| 24 | `/settings/about` | 关于(只能从抽屉进) |

**抽屉 14 项**:账号切换头(左右滑循环切号)、登录、每日签到(原地执行行内状态)、添加版面 ID、由 URL 读取、收藏夹、收藏夹管理、清空我的收藏、我的主题、我的回复、我的缓存、短消息(桩)、最近被喷(未读徽标)、设置、关于。

**深链**(`core/local/deep-link.ts`):只认 `read.php`→主题、`thread.php`→版块;支持完整 URL/自定义 scheme/无 scheme 主机/纯路径;处理 `&amp;` 与 percent 解码;`#pid123Anchor` 提取 pid;非 NGA 主机返回 foreign-host。同一映射表服务系统深链与「由 URL 读取」。

## 2. 功能清单与读写边界

**真写操作(8 个)**:楼层点赞/点踩(`topic_recommend`)、版块收藏(`forum_favor2`)、主题多收藏夹 CRUD(`topic_favor_v2`)、每日签到(`check_in`)、子版块订阅/屏蔽(`user_option`)、改自己签名(`set_sign`)、官方屏蔽词增删(`ucp&__act=set_block_word`)、清空通知(`noti&__act=del`)。

**toast 桩(不实现,入口保留)**:发帖、回帖、编辑、短消息、发贴条(展示照常)、举报、投票操作(结果只读渲染)、主题页复制链接/分享(图片查看器的保存/分享/复制是真的)、主题页菜单夜间模式、网页页字号、精华区按版块筛选。

**浏览**:两种排序、版头置顶、子版块 chip、精华区、24h 热帖(并发拉前 5 页按 24h 窗口过滤重排)、无限滚动+下拉刷新。
**主题详情**:横滑翻页(预渲染邻页)、跳页、自动加载下一页、只看此人/只看该楼、热门回复折叠、贴条展示、引用块→回复链、骰子本地复算、投票只读、附件网格、签名档、「上次读到第 N 楼」浮条(5s)、缓存本页/整帖(进度+停止)、数据来源降级提示条(web 反解/离线缓存)。
**楼层操作**:点赞踩、长按/kebab 菜单(查看签名/收藏/只看此人/屏蔽此人(本地规则带撤销)/贴条桩/举报桩)、点头像进资料。
**登录**:WebView 官方页 + 原生 CookieManager 轮询提取(`ngaPassportCid` 是 HttpOnly);多账号,抽屉滑动切号。
**收藏**:云端多夹(客户端限 20)+ 版块收藏;本地维护 tid→夹 反向索引(NGA 无此接口)。
**历史**:200 条,含阅读进度(只前进)。
**搜索**:主题(限版块/搜正文)/版块/用户;每 tab 独立历史 20 条。
**通知**:按类型分组,前台 60s 轮询,已读纯本地。
**图片查看器**:双指缩放、双击 2.5×、翻页、保存相册(NGA)、分享、复制地址、查看原图、浏览器打开、批量下载。
**屏蔽**:本地规则(用户/关键词(可正则)/分类)+ 官方屏蔽词(云端全表覆盖写)。
**实验室**:①网页数据源兜底四档 disabled/secondary(默认)/primary/only;②read.php 用 Windows Phone UA(默认开);+两个只读诊断(组合表分享、诊断日志导出 50 条)。
**设置树**:通用(域名 5 选 1、账号、夜间/跟随系统、主题风格 ink/plain/近黑、左手模式、纯色背景)/阅读(自动下一页、仅 Wi-Fi 图片、图片策略 original/smart/thumbnail、签名档、手势返回、常亮、字号头像)/通知(被喷提示、声音)/内容与存储(屏蔽规则、清阅读进度、清缓存)/高级(实验室、恢复默认)。

## 3. 网络层

**域名 5 个**(`net/constants.ts:2-8`):bbs.nga.cn(默认)/ngabbs.com/bbs.ngacn.cc/nga.178.com/nga.donews.com;用户可选;策略是域名×格式笛卡尔积轮换,当前设置域名永远最前。

**端点全表(25 个)**:见 `docs/API文档.md` §12 与 `src/core/api/*`。要点:全部默认 POST、业务参数进 URL query、POST body 只放表单字段;api 层文件不指定 method/format/host/auth/UA(100% 传输层决定)。已知坑:`removeTopicFavorite` 用 `tidarray` 不是 `tid`;`forum.php` 的 `key` 与 block-word 的 `data` 是 GBK 参数;user-profile 必须带 referer;`setSubBoardOption` 参数名本身是动词(add/del=<filterId>)。

**字符集**:响应「有声明信声明→无声明先试 UTF-8→U+FFFD 计数投票选 GB18030」;手写 WHATWG GB18030 状态机(Hermes TextDecoder 只认 utf-8)——**Kotlin 直接 `Charset.forName("GB18030")`,扔状态机、留策略**。出站逐参数 `gbk()` opt-in;表外字符(emoji)按 UTF-16 码元写十进制实体再 percent;**任一 GBK 参数出现→整个丢 `__inchst=UTF8` 声明**、POST Content-Type 加 charset=GBK。

**请求头**:`X-User-Agent: Nga_Official`(身份靠这个,不靠 UA);UA 默认取设备真实 WebView UA;`read.php` 可换 `NGA_WP_JW/(;WINDOWS)`(实验室开关,默认开);Referer=host/refererPath;登录后 Cookie `ngaPassportUid`/`ngaPassportCid`。**默认 authMode='both'**:Cookie 头 + `access_uid`/`access_token` 表单字段双通道——OkHttp BridgeInterceptor 会用 cookie jar 静默覆盖手设 Cookie 头(ADR-0002 第 4 条),Kotlin 同坑。

**登录**:WebView 加载 `nuke.php?__lib=login&__act=account&login`,每 500ms 轮询原生 CookieManager;挂载前 clearAll(多账号隔离的实现方式)。凭证存 expo-secure-store 单键 `accounts.v1`(多账号+currentUid),**每次请求现读当前账号**。无签名/加密——cid 是长期静态 cookie。

**反封锁链装配序**(`store/nga-client.ts:83-91`):web-fallback(primary 档)→ format-rotation → switch-account → web-fallback(secondary 默认档)→ topic-cache;链外第 6 步 /web 页(用户手点)。引擎:按序试,首成功返回;`retryable===false` 立刻抛;全程 attempts 诊断。
- format-rotation:格式 `__output=8`/`__output=11`(不同序列化器,能绕字节损坏)/`lite=js`;组合顺序=缓存好组合→调用方指定→域名外层×格式内层;**上限 8**;组合缓存 key 是接口粒度(`path` 或 `path?__lib&__act`——`thread.php` 六个业务共用一槽,已知风险)、TTL 10min、**故意不持久化**;`renewTransport()` 每次重试前调用但 RN 上是 no-op(Kotlin 要真建新 client);游客短路(未登录且服务端报未登录→停止换域名)。
- switch-account:≥2 账号才触发,循环到当前 uid 下一槽,只试一次。
- web-fallback:只对 `read.php`;html 组合+复用上阶段域名;`only` 模式失败改写成不可重试。
- topic-cache:只读 SQLite;仅整帖普通阅读(带 pid/authorid 的过滤视图排除)。
- **无退避/无超时/无请求去重**:所有尝试背靠背;超时是 OkHttp 默认值(审计 P2-06);combo 缓存不是去重。唯一克制:clearBoardFavorites 串行逐删。

**响应清洗六步**(`net/sanitize.ts:151-158`,顺序敏感):剥 `window.script_muti_get_var_store=` 前缀/切 `</script>` → 切 `/*error fill content` 尾 → 去 `/*$js$*/` → 非法数字加引号 → **一趟扫描**同时给裸整数键加引号+转义字符串内裸控制字符 → 去尾分号外层括号。**不删 alterinfo**(上游参考实现删,这里刻意保留「已编辑」标记)。信封解包后 `orderedEntries()` 兼容「字符串整数键对象」与真数组(`__output=11` 返回真数组,曾被误判为被封)。业务形状否决器 `rejectNonTopicList` 防假成功进组合缓存(回归测试 `combo-poison.test.ts`)。

**错误分类**(`net/errors.ts`):network/http/parse(≈被封)/server/unavailable;默认 `retryable = kind!=='server'`;**「未登录」强制可重试**(传输身份失败非语义失败);假错误白名单(完毕/没找到/没有符合条件的结果/今天已经签到/找不到用户)短路成成功。

**Web HTML 反解**(`net/web/read-html.ts`):零依赖手写引号感知括号匹配器;抓 `commonui.postArg.proc`(**参数位置表 :52-68 是拿 3 份真实抓包对拍出来的,全网无第二份文档**)、`userInfo.setAll`、`__PAGE`+`setDefault` 交叉校验、msgcode、`ubbcode.attach.load`、loadAlertInfo、`__ATTACH_BASE_VIEW`;输出与 `__output=8` 同构信封,`source:'web'`。已知不可恢复:投票内容、嵌套贴条/热回 from_client、第 1 页外匿名楼主标记。

## 4. 帖子内容渲染

**零 WebView**(全仓 WebView 只有登录与 /web 兜底)。手写单遍扫描解析器(`core/bbcode/parse.ts`):sticky 正则、显式栈、深度上限 64、**永不抛异常**(未知标签降级文本、EOF 未闭合上提子节点)。**29 种运行时节点**(全部可 JSON 序列化,直接进帖子缓存):text/linebreak/bold/italic/underline/strike/color/size/font/quote/code/collapse/list/table/align/heading/divider/link/userRef/topicRef/floorRef/mention/image/attach/album/flash/smiley/dice/box。`src/ui/bbcode/coverage.test.ts` 是权威覆盖表。**不支持**(降级纯文本):pre/hide/spoiler/randomblock/email;防剧透靠 `[color=white]`。**投票不是 BBCode**:`Floor.vote` 字段 `~` 分隔 kv,独立解析、兄弟节点渲染。

**表情**:265 文件 7 分类(默认 27/AC娘v1 45/AC娘v2 46/NG娘 34/潘斯特 65/外域三人组 33/企鹅 15),238 PNG+27 GIF 共 1.1MB 全打包 APK,文件名即 CDN 原名;生成器 `scripts/fetch-smilies.mjs`(从 js_bbscode_core.js GBK 解码非 eval 扫描);RN 里用 RN Image 内联进 Text(即 Android ImageSpan 路数)。

**骰子**:种子=authorId+tid+pid;LCG `state=(state*9301+49297)%233280`;文法照抄 NGA 宽松正则;**一楼内所有 [dice] 共用一条随机流按文档顺序推进**;collapse 内 seedOffset 只对 tid>10246184 生效。
**匿名还原**:`#anony_<32hex>`;22 字天干地支+255 字百家姓;6 段固定偏移,**hex[5] 静默跳过**;255 表按整字节索引越界掉字符——NGA 原样,勿修;颜色取 hex[11:17]/[17:23]。
**彩色标题**:掩码 1红2蓝4绿8橙16银32粗64斜128下划线;颜色位互斥按优先级(红>蓝>绿>橙>银),样式位叠加;`topic_misc` 是无 padding base64 TLV(5 字节:type+大端 u32;0=结束 1=掩码 2=stid 3=sfid),**优先于 titlefont**;sfid 有符号修正只对 fid/sfid。
**HTML 实体**:**双重转义解两遍**;超 BMP 按 UTF-16 码元十进制实体,逐码元解码自然重组代理对;孤立代理最后清洗;命名实体只有 amp/lt/gt/quot/apos/nbsp;**正文裸 HTML 除 `<br/>` 一律当字面文本**。
**附件 URL**:base 每次从响应 `__GLOBAL._ATTACH_BASE_VIEW` 取(兜底 img.nga.cn/attachments);noimg 缺日期目录按 postedAt **固定 UTC+8** 合成 `mon_YYYYMM/DD`;老帖死域名替换;缩略后缀 4 种(.thumb_ss/.thumb_s/.thumb/.medium)。

## 5. 列表与转场(RN 侧参考)

`@legendapp/list` v3.3.5 用在 11 屏,统一 recycleItems;`drawDistance=2400`(版块列表);主题详情 `getItemType` 分池;长楼层 >8 段分帧挂载(`progressive.tsx`,行回收 resetKey 立刻追平);图片尺寸缓存(内存 512+MMKV 持久化 1s 防抖)防 4:3→真实比例跳动;超高图 0.6 纵横比封顶(否则搞乱行高估算);表格定宽 108dp 交横滑。转场分帧 `CONTENT_MOUNT_DELAY_MS = duration.panel+32`:转场期只画顶栏+loading,列表壳等横推停稳再挂。

## 6. 图片(RN 侧参考)

expo-image;正文/头像 `memory-disk`(只用 disk 会行回收重复解码),查看器 `disk`;app 层不管缓存大小 TTL;图片字节不计入帖子缓存 32MB 预算。imageQuality original/smart/thumbnail(默认 smart:Wi-Fi 原图蜂窝缩略)+ wifiOnlyImages(默认 true);网络状态单例订阅。保存:file-system 缓存中转(URL 稳定推文件名)→ media-library 相册 NGA / sharing。图标:Material Icons Outlined 静态 OTF 331KB 84 字形,`<Text>` 渲染,**根布局等字体加载完才放行首屏**(否则豆腐块)。

## 7. 本地存储

| 引擎 | 内容 |
|---|---|
| MMKV(id 'ng2') | 设置、主题模式、网络开关、本地屏蔽规则、搜索历史、版块树缓存(24h SWR)、已读公告、签到日期、诊断日志、**按 uid 分键**的收藏反向索引、图片尺寸缓存 |
| expo-sqlite | `ng2.db`:browse_history(tid PK,历史与阅读进度同一条记录,200 条,1s 节流批刷)+ topic_cache(tid,page PK;payload 完整信封;100 帖且 32MB,LRU 按 usedAt 整帖驱逐);`notifications.db`:notification_read(uid,id PK;id=客户端合成 `${ts}-${type}-${tid}-${pid}`;通知条目本身不持久化) |
| expo-secure-store | 单键 `accounts.v1`(全部账号+currentUid) |

**无迁移机制**(无 PRAGMA user_version,只有 CREATE IF NOT EXISTS):「换结构就换 key/表名,老数据作废」是明文策略(6 处注释重复);MMKV 设置逐字段容错。zustand 16 store 全手写 load/save;TanStack Query 13 个 key 零磁盘持久化,全局 staleTime:0 显式决定。**两个数据集刻意会话级不持久化**:子版块订阅/屏蔽本地覆盖、楼层点赞标记。

## 8. 动画与手势(RN 侧参考)

全 Reanimated 4.5.1。`motion.ts`:easeStandard=bezier(0.25,0.1,0.25,1)、easeDecelerate=bezier(0.2,0.8,0.3,1)、duration={menu:160,quick:180,base:200,panel:220,notice:280}、RISE_OFFSET=14、POP_SCALE=0.94(每个数值有设计稿出处行号)。
- 抽屉:宽 300pt,开 220/关 200ms,遮罩面板共 progress,左边缘 22px 拉出(EDGE_WIDTH 导出给首页让位),位移>12px 才认,40% 或速度阈值完成,PanResponder,返回键关闭。
- 横滑翻页:收尾弹簧 stiffness 500/damping 48/overshootClamping(对拍原生 ViewPager 逐帧调出:定时曲线不吃松手速度)——**Kotlin 用 ViewPager2/Pager 免费获得**;onTarget 在松手定向刻触发(勿挂重渲染);COMMIT_GUARD_MS 400。
- 手势冲突:楼层内横向滚动表格与翻页手势——模块级计数标记+UI 线程镜像 SharedValue;**Kotlin 对应 requestDisallowInterceptTouchEvent,容易得多**。
- 图片查看器:同一 Pan 按缩放拆两路(原始尺寸拖页/放大拖图钳边界),双击 2.5×,回弹阻尼 0.55、220ms。
- 120Hz:`with-high-refresh-rate.js` 往 MainActivity 注 `preferHighestRefreshRate()`(onCreate+onResume,厂商后台恢复重投票)——**现成 Kotlin 代码直接搬**。

## 9. 应用配置(RN 版)

applicationId `com.chasel.ng2`(dev 变体 `.dev`);名 NG2/NG2 Dev;scheme ng2/ng2-dev;versionName 0.1.0/versionCode 2;Expo SDK 57.0.10 / RN 0.86.2 / React 19.2.3(React Compiler 开);minSdk 24/target 36/compile 36;newArch+hermes;四 ABI;edge-to-edge;强制竖屏;深链 5 域名×read/thread(autoVerify false);权限 INTERNET/READ_MEDIA_IMAGES/READ_MEDIA_VISUAL_USER_SELECTED/READ_WRITE_EXTERNAL(≤32)/SYSTEM_ALERT_WINDOW/VIBRATE;`predictiveBackGestureEnabled: false`(**原生版改开,ADR-0004**);自写模块 `modules/nga-cookies`(Kotlin,getCookieString/clearAll)。

## 10. 体量

src/ 311 文件 44,114 行:生产 31,553(189 文件)+ 测试 11,211(84 文件,**1114 用例**)+ 生成物 964 + fixtures 3,259。最大文件 topic/[tid].tsx 1828。分层:core/api 7342 / ui 7524 / core/local 4562 / app 4245 / store 3293 / core/net 3120 / ui/bbcode 2347 / core/bbcode 1774。资源:images 15MB、smilies 1.1MB、fonts 332KB。生产依赖 31 个(克制);core 层零依赖。

## 移植风险 TOP5

1. **反封锁链语义细节**(哪些错误可重试):照抄别优化,`combo-poison.test.ts` 类回归一起移植。
2. **P1-01 写操作被链重放/跨账号提交**:移植时修——请求加 operation/accountPolicy 元数据,写操作固定 uid、禁入 format-rotation 与 switch-account。
3. **GBK 逐参数不是全局**:响应投票策略/逐参数标记/`__inchst` 整体撤销/表外字符实体化,四处漏一处=偶发乱码。
4. **OkHttp cookie jar 静默覆盖手设 Cookie 头**:双通道认证照抄;多账号+WebView 共享 CookieManager 的归属问题(P1-03)用自管 CookieJar 解。
5. **逆向算法无第二份文档**:尤其 read-html proc 参数位置表,最易被 NGA 改版打掉——移植前对线上重新抓包验证。
