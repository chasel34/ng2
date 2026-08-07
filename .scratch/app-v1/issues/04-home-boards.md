# 04 — 首页:版块分类树 + 抽屉骨架

**What to build:** 打开 app 即见设计稿首页:顶栏 + 分组横向 tab + 版块三列宫格(在线拉取分类树,24 小时节流的本地缓存起底),公告条可关闭。点版块进主题列表(可先占位)。抽屉可拉出,条目齐全(登录/添加版面/由 URL 读取/收藏夹管理/清空收藏/最近被喷/设置/关于),未实现项点击 toast「本版本未开放」。首页右上菜单同理。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 冷启动无网时用缓存的分类树渲染,有网时静默增量更新(24h 节流)
- [x] 宫格/tab/公告条与设计稿 1:1(含版块图标远程加载失败时的首字占位样式)
- [x] 合集(stid)与普通版块(fid)在数据层区分,stid 优先

## Comments

### 实现摘要(2026-08-07)

**数据层 `src/core/api/**`(纯 TS,零 RN 依赖,36 例单测)**

- `board-tree.ts` —— `POST app_api.php?__lib=home&__act=category` 的拉取与解析。分类/分组/版块三层
  全是「字符串数字键当数组」,手工遍历 + 逐字段类型容错,坏条目跳过、整棵树不炸;一个版块都没解析出来
  才抛 `kind:'parse'`(等价于被封,调用方应继续用缓存)。
- **stid 优先**:`id = stid ?? fid`,`kind` 分 `collection`/`board`,`fid`/`stid` 原值都留着(子版块订阅、
  版块收藏要分别传)。**`stid:0` 当成「不是合集」**——普通版块常带这个 0,不防会把整版块错判成合集。
- **版块图标**:地址 = `other.forum_icon_list` 的 `f_px_l`/`s_px_l` 前缀 + id + 后缀。`f`/`s` 两个字符串是
  「登记过图标的 id 清单」,清单外的 id 请求必 404 —— 抽样 14 个版块逐个实测,命中与否与 200/404 完全一致,
  所以不在清单里就不给地址,免得一屏几十个格子打一片 404。
- **公告**:解析 `other.appcolumn_notis`,`pickActiveAnnouncement` 按 `start_at`/`end_at` 窗口挑当前该显示的一条。
- `board-tree-cache.ts` —— 24 小时节流(与官方 Android v4 同窗口)。`loadBoardTree` 的次序:缓存没过期就
  一个请求都不发 → 过期则拉线上并**增量合并**(结构以服务端为准,只有服务端这次没下发的 info/图标才回落缓存值)
  → 拉失败但有缓存就**静默**用缓存并把失败原因随结果带出去 → 没缓存才抛。设备时钟往回跳按过期处理,否则缓存卡死。
- `fields.ts` —— `orderedEntries/str/int/nonZero` 等手工遍历工具,05/07 解析别的接口直接用,别再各写一份。
- fixture 是 2026-08-07 游客身份 curl 到的**原始 GBK 响应字节**(7 分类 / 673 版块),测试连着 core/net 的
  解码与清洗一起跑,等于端到端。

**设备侧接线 `src/store/**`**

- `storage.ts`(MMKV v4 是 `createMMKV()` 工厂,不再 `new MMKV()`)、`nga-client.ts`(core/net 策略链 + expo/fetch)。
- `board-tree.ts` —— core 那个 `BoardTreeStore` 接口的 MMKV 实现 + `useBoardTree()`。冷启动用
  `initialData` 直接拿缓存渲染(读一次就记住:整棵树 JSON 有 100 KB,`initialData` 每次 render 都会被调),
  `staleTime` 24h;读缓存失败一律当没缓存,不能在 render 期间抛。公告的「关过哪些」也落 MMKV。

**UI**

- `src/app/index.tsx` 首页:顶栏 + 分组 tab + 公告条 + 三列宫格,数值逐条对着设计稿抄(顶栏 54、tab 44、
  宫格 3 列行距 14、图标 32 圆、公告条 14/18 内距……)。tab 选中态用绝对定位的 3pt 下划线而不是 border——
  设计稿用的是不占布局的 inset box-shadow。**正文按行虚拟化**:最大的分类(手机游戏)有 333 个版块,
  一次铺完会连带发出三百多个图标请求,所以摊平成「公告条 / 分组标题 / 一行三个版块」交给 FlashList。
- `src/ui/drawer.tsx` 抽屉:遮罩 + 300pt 面板,点遮罩/左划/系统返回键都能关,左边缘 22pt 可拉出。
  用 RN 自带 Animated + PanResponder(位移与透明度都走原生驱动),不引 reanimated/gesture-handler,
  省掉一层 babel 插件与 New Arch 的适配假设。
- `src/ui/app-drawer.tsx` 八个条目、`src/ui/menu.tsx` 首页右上菜单六条:**全部 toast「本版本未开放」**,
  各自等后续票换掉自己那一行(登录 09、收藏夹 11、通知 13、我的主题/回复 14、缓存 20、设置与关于 22、由 URL 读取 24)。
- `src/ui/board-icon.tsx`:expo-image 远程加载 + 磁盘缓存,没图标或加载失败回落设计稿那个「斜纹圆底 + 首字」占位
  (RN 没有 repeating-linear-gradient,用几条转 45° 的细线铺出同样纹理)。
- `src/ui/top-bar.tsx`:顶栏色块/圆形按钮/标题三件套,首页与占位页共用,后面 29 屏别再各抄一遍。
- `src/app/board/[id].tsx`:主题列表的占位路由,先把参数约定固定下来 —— `id`(stid 优先的那个)、`kind`、`name`。
- 根布局挂 `QueryClientProvider` + `SafeAreaProvider`,并等图标字体加载完再渲染路由。

**图标字体(01 票遗留问题 6 的收尾)**

`@expo/vector-icons` 在 SDK 57 里根本没装,pnpm 又是隔离布局,所以自建管线:`scripts/fetch-icon-font.mjs`
从设计稿扫出用到的 74 个图标名 → 拉 Google 官方码点表 → 下载 `MaterialIconsOutlined-Regular.otf`(331 KB,
Apache-2.0)进 `assets/fonts/` → 生成 `src/ui/icons.generated.ts`。按码点渲染,不用连字(部分 Android ROM 不稳)。

**token**

设计稿实际用到的字号多于 token 表的六档(01 票遗留问题 3 已预告)。按当时的建议**补档进 `typography`**
而不是在页面里散写:新增 gridLabel/notice/section/menuItem/drawerItem/caption/initial/badge 八档,
`tokens.test.ts` 分「token 表六档」「补档」「没有别的自造档位」三组断言。

### 与设计稿的已知偏差(都有原因)

1. **首个 tab 是「推荐版块」而不是「我的收藏」**:设计稿第一个 tab 是我的收藏,那是**版块收藏(10 票)**的数据。
   现在用服务端单独下发的 `forum_recommend` 占这一档。10 票接云端收藏后,在分类列表最前面插一档即可。
2. **抽屉头部是未登录态**:设计稿画的是「已登录 2 个账号 + 左右滑动切换」,那是 **09 票**的多账号。
   现在固定渲染未登录态(64 见方头像框 + 点此登录),布局位置留着,09 直接换内容。
3. **图标字体是 Material Icons Outlined,设计稿用的是它的下一代 Material Symbols Outlined**:
   图标名一一对应、造型同源,但笔画细节有出入。见下面遗留问题 1。
4. 「添加版面 ID」保留了设计稿原字。CONTEXT.md 的 `版块` 词条已补一句:代码与文档一律写「版块」,
   个别 UI 文案沿用设计稿原字。

### 遗留问题

1. **图标字体可以换成设计稿同款**:`@expo-google-fonts/material-symbols` 已经作为 expo-router 的传递依赖
   躺在 bundle 里了(962 KB 的 `MaterialSymbols_400Regular.ttf`,`expo export` 的资产清单里能看到),
   但 pnpm 隔离布局下 src 里 import 不到。**在 package.json 加这一条依赖就能拿到设计稿同款字体,
   而且 APK 体积一分不多**(资产已经在打包了),还能顺手删掉我这 331 KB 的 otf 与整条脚本。
   本票纪律是不改 package.json,所以没动 —— 建议 27 票(1:1 打磨)前拍板。
2. **没有下拉刷新**:设计稿首页没画,`loadBoardTree` 里也没留 `force`(YAGNI,自查时删了)。
   05/22 要加下拉刷新时,给 `LoadBoardTreeOptions` 补回 `force` 即可,缓存策略本身不用动。
3. **断网用缓存渲染时没有任何提示**:`loadBoardTree` 会把失败原因放进结果的 `error`,但首页现在没消费它。
   18 票(反封锁链 + 错误页)统一设计离线提示条时接上。
4. **tab 不会自动滚到选中项**:分类有 8 个,横向 tab 里选到靠后的分类再切回来,选中项可能在可视区外。
   设计稿没画这个行为,27 票打磨时再说。
5. **公告条的 `url` 解析了但没用**:服务端公告可以点进一个主题,要等 07 票的详情页才有地方跳。
6. **`_layout.tsx` 在图标字体加载完之前不渲染路由**:冷启动会有一帧空背景(不是白屏,根背景色已设)。
   要更顺滑得配 `expo-splash-screen` 的 `preventAutoHideAsync`,留给 27 票。
7. **真机没验**:本轮只跑通了 `pnpm typecheck`、`pnpm test`(300 例)与 `expo export --platform android`
   (Metro 打包无解析错误)。抽屉手势、图标字体渲染、MMKV 落盘、图标 404 回落这些都要 Android 真机确认。
