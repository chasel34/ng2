# 楼层卡片 / BBCode 正文:单次渲染成本

背景实测（release 包，模拟器 60Hz，`dumpsys gfxinfo`）：帖子详情页慢拖 janky frames 54%，
其中 “Slow issue draw commands” 45%；同脚本下版块主题列表 0.3%。

这一轮只管**单次渲染本身更便宜**；「每次渲染都全量重渲染」的上游问题
（`src/app/topic/[tid].tsx` / `src/store/history.ts` / `src/ui/paging.ts`）由另一个 agent 处理。

改动范围：`src/ui/bbcode/**`、`src/ui/floor-card.tsx`、`src/ui/avatar.tsx`。
`src/ui/vote.tsx` 读过没改，理由见最后。

排序按 team lead 的 JS 基准（解析 + 分段整页只要 0.1–0.2ms/Node，不是瓶颈）重排过：
**稳定引用 > 内存缓存 > 绘制/层级 > 布局跳动 > 解析路径卫生**。

---

## 1〔最高〕稳定住派生对象，让上游的 `floorContext` 稳定化真的生效

**改了什么**（`src/ui/floor-card.tsx`）

| 值 | 之前 | 现在 |
|---|---|---|
| `renderOptions` | 每次渲染新对象 | `useMemo`，依赖 `attachBase` / `postedAt` / `dice` / 字号行高 / `openImage` |
| `bodyOptions` | 每次渲染新对象 | `useMemo`，依赖 `renderOptions` / `chainDepth` / `onOpenChain` / `floor` |
| `openImage` | 每次渲染新闭包 | `useMemo`，依赖 `context.onOpenImage` / `floorImages`（后者本就 memo 过） |
| `openMenu`（长按整卡 + 菜单钮共用） | 两处各建一个新闭包 | `useCallback` |
| `Avatar` 的 `box` / 占位样式数组 | 每次渲染新对象 | `useMemo`，依赖 `size` / `key` |

另外给 `BBCodeBody` 本身加了 `memo`（`src/ui/bbcode/render.tsx:145`）——它是楼层里最贵的一块
（整棵 AST → 元素树）。楼层卡片因为赞踩变色、菜单开合之类的原因重渲染时，只要 AST /
渲染参数 / 字号没变就整块跳过。

**为什么必须和上游配套**：`renderOptions` 会一路传进 `BBCodeBody` 和各个卡片组件。
它每次渲染都换引用的话，下游任何 `memo` 都是白包；反过来，上游把 `floorContext` 稳住了，
但我这边的派生对象仍在换引用，`FloorCard` 的 `memo` 过了、正文照样全量重建。两边缺一不可。

**`FloorCard` 的 props** 确认只有 `floor` 和 `context` 两个（`FloorCardProps`），
`floor` 来自解析结果、天然稳定。

**怎么验证**：上游 `floorContext` 稳定化落地后，点一次赞 / 开一次楼层菜单，
用 React DevTools Profiler 看应该只有被点的那张卡片重渲染，且卡片内的 `BBCodeBody` 不重渲染。

### `memo` 的 props 引用稳定性（按你的提醒逐个核过）

| 组件 | props | 引用稳不稳 |
|---|---|---|
| `Avatar` | `user` = `context.users[floor.authorKey]` | 稳（解析产物，同一对象） |
| `UserBadges` | `user`（同上）、`isStarter`（boolean） | 稳 |
| `Signature` | `signature`（string）、`options` = `renderOptions` | 稳（本轮 memo 化后） |
| `NoteList` | `notes` = `floor.notes`、`users` = `context.users` | 稳 —— 注意取的是 `context.users` 这个**字段**，即使 `context` 对象本身换引用，`users` 仍是解析时那一个 |
| `AttachmentGrid` | `attachments` = `floor.attachments`、`onOpenImage` = `openImage` | 稳（`openImage` 本轮 memo 化） |
| `BBCodeBody` | `nodes`（AST）、`options`、`style` | 稳；**例外见下** |

**唯一一处 `memo` 打不满的地方**：`BlockNode` 递归调用 `BBCodeBody` 时传的是
`[style, extra]`，每次渲染都是新数组，所以**嵌套**在引用块/折叠块/表格里的 `BBCodeBody`
memo 会失效。要修得引入一张样式数组的缓存表，复杂度和收益不成比例——最外层那一次
memo 已经把绝大部分成本挡在门外了（外层跳过，里面根本不会被调用）。故意不修。

## 2〔次高〕`cachePolicy` 从 `disk` 改成 `memory-disk`

**改了什么**：`src/ui/avatar.tsx:70`、`src/ui/bbcode/content-image.tsx:104`、
`src/ui/floor-card.tsx:424`（附件宫格）。

**为什么**：核实过 expo-image 57 的语义
（`node_modules/expo-image/build/Image.types.d.ts:196-208`）——

- `disk`：只查磁盘缓存，**没有内存缓存**；
- `memory-disk`：内存缓存，回落磁盘。

`disk` 档下列表回收后同一张图重新上屏要重新读盘 + **重新解码 + 重新上传纹理**，
来回滚就是反复付这笔钱，正落在「提交/绘制」那一段。头像最典型：同一页里同一个人
可能出现十几次，同一份 bitmap 反复解码纯属浪费。

省内存的权衡不成立：expo-image 的内存缓存本来就会在内存吃紧时被快速清掉
（官方注释明写 “Memory cache may be purged very quickly to prevent … out of memory”）。

**范围**：只动了允许改的三处。`ui/image-gallery.tsx`、`ui/board-icon.tsx`、`app/chain.tsx`、
`app/user/[uid].tsx` 还是 `disk`，建议后续一起过——但**大图查看器那处要单独想**：
全屏原图一张几十 MB 纹理，留 `disk` 可能才是对的。

**怎么验证**：滚出去再滚回来，图片应当**没有** 120ms 的 `transition` 淡入
（命中内存缓存是同步出图）；再看 `dumpsys gfxinfo` 的 “Slow issue draw commands” 占比。
另外盯一下 §P2 的 PSS 曲线——内存缓存会让峰值抬一点，这是有意换来的。

## 3〔认真做过〕视图层级 / 绘制指令

先说一条**把我原来的判断纠正过来**的核实结果，它决定了这一项还剩多少可做的空间。

翻了 RN 0.86 的 `ReactCommon/react/renderer/components/view/ViewShadowNode.cpp:38-94` 和
`platform/android/…/HostPlatformViewTraitsInitializer.h`：New Arch 下一个 `View` 只有满足
`formsView` 才会真的创建 Android View，否则被**摊平**（flatten），根本不参与绘制。
`formsView` 的条件是：有意义的 `backgroundColor`、**任一边有 borderWidth**、`boxShadow`、
`backgroundImage`、`outlineWidth`、`testID`，或者 `formsStackingContext`（触摸事件、
`overflow:'hidden'`、`opacity != 1`、`transform`、`accessible`、`pointerEvents`…）。

**`borderRadius` 不在任何一张表里。**

推论——楼层卡片里这些「看着能合并」的 `View` **本来就不产生绘制指令**：
`header` / `headerText` / `nameRow` / `metaRow` / `floorNo` / `body` / `actions` /
`attachOpen` / `attachGrid` / `listRow` / `listContent`。合并它们省不到 45% 那个数字上。
所以我没有为了「少一层 View」去动排版——那既是白改，又拿 `design/` 的 1:1 还原冒险。

真正产生绘制指令的是：每个 `<Text>`、每个图片视图、以及**带背景/描边/裁切**的那几个容器
（`quote`、`notes`、`signature`、`attachToggle`、`plainBox`、`tableCell`、`card` 的底边线）。
按这个判据实际改了两处：

**(a) 附件宫格去掉一次画布裁切**（`floor-card.tsx` 的 `attachCell` / `attachImage`）

原来是外层 `Pressable` 带 `borderRadius: 10 + overflow: 'hidden'` 去裁里面的 `Image`。
`overflow:'hidden'` 命中 `getClipsContentToBounds()`，每次绘制都要压一层 canvas clip。
现在圆角和底色直接落在 `Image` 自己身上（expo-image 本来就会自己画圆角），
外层 `Pressable` 只剩量出来的 `width/height`，`attachCell` 样式整条删掉。
**省的是一次 clip，不是一个 View**（Pressable 有触摸事件，本来就得成 View）。
展开三列宫格时每格省一次，观感一致。

**(b) 正文每张图省一个 shadow node**（`render.tsx` 的 `case 'image'`）

原来是 `<View style={{marginTop:11}}><ContentImage/></View>`，那层 View 只为一条 marginTop。
现在 `ContentImage` 收一个 `style` prop，三种形态（正常 / 移动网络折叠 / 加载失败）
的根节点都吃这份样式。

**诚实的收益范围**：那层 View 是会被摊平的，所以**省的不是绘制指令**，而是一个 React 元素、
一个 Yoga 布局节点和一次 props diff。Yoga 布局对摊平的 View 照样要算——而 lead 说贵的正是
「提交/布局/绘制」这一段，所以它落在「布局」那一档，只是别指望它动 45% 那个数。

**(c) 表情比例查表**（`bbcode/smiley.tsx`）：`Image.resolveAssetSource` 每次渲染都要过一遍
资源注册表，而一楼正文里同一个表情可能出现几十次、回收后还要再解一遍。比例是打包期定死的，
按 assetId 记进模块级 Map。这是 JS 成本，不是绘制。

**(d) 行内段的样式数组**（`render.tsx:159`）：`[styles.body, style]` 原来一段一个新数组，
现在整个 `BBCodeBody` 共用一份。纯 JS 分配，量小。

### 这一项里**看过但决定不改**的

- **`quote` 的 `borderRadius + backgroundColor + borderLeftWidth:3`（左边条另一个颜色）**：
  Android 上「圆角 + 不对称描边色」会走 `CSSBackgroundDrawable` 的 Path 慢路径，嵌套引用会翻倍。
  但这是设计稿 f.quote 的形（圆角 12 / quote 底 / track 竖条），`design/` 是还原基准，不动。
  **如果后面还要再挤，这里是楼层里最贵的单个绘制项**，值得单独立一票讨论视觉替代方案。
- **`attachToggle` / `content-image` 的 locked+failed 用 `borderStyle:'dashed'`**：
  虚线边在 Android 上是 `DashPathEffect` 的 Path 绘制，也是慢路径。同样是设计稿的形；
  而且只在「折叠 / 失败」态出现，不在滚动热路径上。不动。
- **`vote.tsx` 的 `bar` 上那个 `overflow:'hidden'`**：本来想照 (a) 的办法去掉，核对后**不能去**——
  `barFill` 自带 `borderRadius:3`，靠外层裁切才有「左端圆、右端平」的进度条形状；
  去掉裁切后部分填充的右端会跟着变圆，是可见的观感改变。整个 `vote.tsx` 因此一个字没改
  （而且投票楼层极少，不在热路径上）。
- **`actions` 行四个 `Pressable` 上的 `borderRadius:10`**：没有背景色也没有描边，今天什么都不画；
  但按上面的核实它既不影响摊平（Pressable 有触摸事件，必成 View）也不产生可见绘制，
  删了收益接近零，反而挡掉将来加按下态/水波纹的余地。留着。
- **`metaRow` 的三段 meta 文字合成一个 `<Text>`**：能省两个 TextView，但 `gap:10` 得换成
  空格字符，间距从 10dp 变成「随字号浮动的一个空格宽」，破 1:1。不改。
- **`floorNo` 里把 `Icon` 塞进 `<Text>` 嵌套**（`Icon` 本身就是 `<Text>`，技术上可行）：
  同样要拿空格字符换 `gap:4`，且图标在 Spannable 里的基线对齐在 Android 各 ROM 上不稳。不改。

### 交给你的一条线索（不在我可改文件范围内）

`src/ui/icon.tsx:47` 的 `Icon` 每次渲染都新建一个内联 style 对象 + 数组：

```tsx
style={[{ fontFamily: …, fontSize: size, color, includeFontPadding: false, textAlign: 'center' }, style]}
```

而 `Icon` 渲染的是真 `<Text>`（真 Android View），一张楼层卡片上有 5 个
（发帖设备 + 赞 + 踩 + 回复 + 更多），加上贴条/附件/引用链更多。
`Icon` 既没有 `memo`，样式又每次新建——楼层卡片每重渲染一次，这些图标就跟着走一遍完整的
元素创建 + props diff。按 `name/size/color` 缓存样式 + 包一层 `memo` 是很划算的一改，
但 `src/ui/icon.tsx` 不在这次允许改的文件里，留给你分派。

## 4〔按体验缺陷做〕正文图片加载完改高度 → 布局跳动

**改了什么**

- 新增 `src/ui/bbcode/image-size.ts`（纯函数模块）+ `image-size.test.ts`：
  `rememberImageSize` / `imageSizeOf`，`Map` 上限 512，到顶丢最早的一条。
- `src/ui/bbcode/content-image.tsx`：`onLoad` 量到的尺寸写进缓存；渲染时按
  「本次挂载量到的 → 以前量过的 → 4:3 占位」三级回落。

**为什么**：这是**体验缺陷（内容跳位）**，不是帧率问题——按你说的定位来做的。

服务端不给像素尺寸，这条核实过：`src/core/api/attachments.ts` 与 API 文档 §3 里 `attachs`
只有 `attachurl` / `size`（**字节数不是像素**）/ `type` / `thumb`，没有宽高可用，只能等 `onLoad`。
但同一张图在一次浏览里会被反复挂载（FlashList 回收后滚回来、翻页再翻回来、引用块里再引一遍），
每次都从 4:3 跳到真实比例 → 单元格高度变 → FlashList 重新量算、内容跳位。
记住之后第二次起首帧就是对的比例。

缓存 key 用**实际加载的那个地址**而不是原图地址：省流量档拉的是缩略图，像素尺寸跟原图不是
一回事，混在一起会让「小图按原尺寸摆」的判断认错。

顺带修了一个同源的回收 bug：`natural` / `failed` 原来是裸 state，FlashList 换一张图复用同一个
组件实例时，新图会顶着上一张的比例、甚至上一张的「图片加载失败」画。现在两个 state 都跟着
地址一起记（`{uri, size}` / `failedUri`），地址对不上就当没量过。`Avatar` 的 `failed` 同样处理。

**既有行为原样保留**（M4 验收缺陷 E7）：`SMALL_IMAGE_WIDTH = 200` 的「小图按原尺寸摆」、
`MIN_ASPECT = 0.6` 的「竖长图压封顶」两段判断一个字没动，只是喂给它的 `natural` 多了一路来源。

**怎么验证**：`image-size.test.ts` 五条（记/查、缩略图与原图分开记、宽高为 0 不记、
到上限丢最早一条、重复记同一张不占新坑）。真机：进一个多图帖 → 滚到底 → 滚回顶，
第二遍不应再有「先矮后高」的跳位。

## 5〔工程卫生，到此为止〕`containsBlock` 记忆化

`src/ui/bbcode/segments.ts`：`containsBlock` 挂 `WeakMap<BBCodeNode, boolean>`；
`render.tsx:158`：`splitIntoSegments(nodes)` 包 `useMemo([nodes])`。

按你的基准，解析 + 分段整页 0.1–0.2ms，**不是瓶颈**。保留是因为它是正确的工程卫生：
`containsBlock` 是整棵子树的深度遍历，而 `BlockNode` 会把块级节点的内容递归交回
`BBCodeBody` 再切一次段，嵌套引用深 N 层最里面那段就被走 N 次——fixture 里的帖子嵌套浅，
测不出来，真实的长引用链上才显形。**没有再往这条路上多投入。**

回归锁在 `segments.test.ts`：用一个 `children` getter 数子树被展开的次数
（`childNodeLists` 取的就是它），断言重复调用只遍历一次、同一棵子树挂两个父节点下也只走一次。
去掉 WeakMap 这条用例立刻红。原有的行内/块级切分用例全部保留，是行为不变的锁。

---

## 验证状态

- `pnpm typecheck`：**干净**（之前那条 `topic/[tid].tsx` 的报错是另一个 agent 的中间状态，已消失）。
- `pnpm test`：78 passed / 4 skipped，1057 tests 全绿
  （含新增 `image-size.test.ts` 5 条、`segments.test.ts` 新增 1 条）。
- 真机 / 模拟器 `dumpsys gfxinfo` 对拍**没做**：按纪律没碰 adb / 模拟器。
  下一轮走查时建议单独看两个数：`Slow issue draw commands` 占比（对应第 2、3 条）
  和滚动回滚时图片是否还有淡入（对应第 2 条）。

---
---

# 第二轮：图片缓存档扫尾 + 列表屏卫生

范围：`ui/board-icon.tsx`、`ui/image-gallery.tsx`、`app/chain.tsx`、`app/user/[uid].tsx`、
`app/index.tsx`、`app/history.tsx`、`app/caches.tsx`、`app/favorites/**`、`app/notifications.tsx`。

## 6. `cachePolicy` 扫尾 —— 三处改、一处**故意不改**

先把判据钉死。翻了 expo-image 57 的 Android 源码：
`android/src/main/java/expo/modules/image/ExpoImageViewWrapper.kt:440-442`

```kotlin
.customize(`when` = cachePolicy != CachePolicy.MEMORY_AND_DISK && cachePolicy != CachePolicy.MEMORY) {
  skipMemoryCache(true)
}
```

也就是说 `cachePolicy` 在 Android 上**就是 Glide 的 `skipMemoryCache`**。而 Glide 的内存缓存是
**整个进程共用的一个 `LruResourceCache`**，容量按「解码后位图的字节数」算（`MemorySizeCalculator`，
默认约两屏像素）。两个推论：

1. 它**不会无限涨**（是 LRU，会自己淘汰），所以「怕 OOM」不是留 `disk` 的理由；
2. 但它是**一个共用的池子**，所以真正的代价是**污染**——往里塞大的，小的就被挤出去。

按这条判据逐个定：

| 位置 | 决定 | 理由 |
|---|---|---|
| `ui/board-icon.tsx:70` 版块图标 | → `memory-disk` | 32×32，解码后一张 ~4KB；首页一屏三十来个、切 tab 来回换，最划算的一类 |
| `app/chain.tsx` 链卡头像 | → `memory-disk` | 一条链上同一个人常出现多次；且与详情页楼层头像**共用 Glide 那份缓存**，从详情页进来基本直接命中 |
| `app/user/[uid].tsx` 资料页头像 | → `memory-disk` | 和楼层头像是同一张图，同上 |
| `ui/image-gallery.tsx` 大图查看器 | **保持 `disk`** | 见下 |

### 为什么查看器留 `disk`（判断，不是遗漏）

理由写进代码注释了（`ui/image-gallery.tsx` 的 `GalleryPage`），要点：

- 查看器画的是**整屏原图**，`contentFit="contain"` 到全屏，Glide 会按目标视图降采样，
  所以一张解码位图大致就是屏幕像素级：1080×2400×4B ≈ **10MB**。
- 往那个共用池里塞几张 10MB，就能把池子挤空——**被挤掉的正是头像和缩略图**，
  也就是第一轮和这一轮刚决定要留在内存里的东西。用大图换小图，净亏。
- 换来的好处又很小：查看器同时只挂当前页与两侧邻页（`Math.abs(i - index) <= 1`，
  `image-gallery.tsx:250`），活着的三张本来就被视图持有，内存缓存只在「翻出 ±1 窗口再翻回来」
  时才有用；而那一次已经有 `placeholderUri`（缩略图，现在是走内存缓存的）先糊着看，
  底下只是一次本地磁盘读，图早就下载好了。
- 再叠上走查 §P2 记的「看完 20 帖 PSS 174→289MB 且不回落」，更不该往这个池子里塞整屏位图。

**复验建议**：如果之后实测发现查看器翻页回看有明显白屏，可以考虑改 `memory` 档
（内存缓存但不占磁盘）或给查看器单独一个 Glide `RequestManager`——但那要动原生配置，
不是改一个 prop 的事。

## 7. 首页版块网格（`app/index.tsx`）

> **这部分未经实测**（首页没跑过帧率），收益是**推断**的：673 个版块、最大的分类
> 300 多个版块摊成一百多行，而 `HomeScreen` 会因为抽屉 / 菜单 / 三个对话框的 state
> 频繁重渲染。按「工程卫生 + 明显缺陷」做的，没做激进重构。

改了三处：

1. **`renderRow` 从组件体内的闭包提成模块级的 `HomeRowView` + `memo`**。
   原来 `renderItem={({item}) => renderRow(item)}` 里的 `renderRow` 每次渲染都是新函数，
   而且它闭包了 `styles` / `theme` / `dismiss` / `openBoard`。现在行组件自己拿 `useStyles`/`useTheme`
   （两者都是按配色缓存的稳定对象），只从外面收三个 prop。
2. **`renderItem` 与 `openBoard` 包 `useCallback`**。
3. **加 `getItemType={(row) => row.kind}`**。行是异构的：公告条、分组标题、一行三个版块的宫格、
   空态说明（`paddingVertical: 56`）、错误块，高度差好几倍。不给 `getItemType` 就全挤在同一个
   回收池里，复用到形状完全不同的行必然重量。

**props 引用稳定性**（照第一轮那张表核过）：

| prop | 来源 | 稳不稳 |
|---|---|---|
| `row` | `rows` 那个 `useMemo`（依赖 category / announcement / favoriteBoards / placeholder） | 稳 |
| `onOpenBoard` | 本轮加的 `useCallback([router])` | 稳 |
| `onDismiss` | `useDismissedAnnouncements(s => s.dismiss)`，zustand 建仓时定死的 action | 稳 |

顺带一提，这个文件原来就有一条同类的防护，写得很对，我照着它的思路做的：

```ts
/** 收藏还没拉回来时的空列表。用常量而不是 `?? []`,免得每次渲染都换一个引用把 memo 打穿。 */
const NO_BOARDS: readonly Board[] = [];
```

**顶部分类 tab 的横向 `ScrollView`（`:422`）：看过，没改。** 里面每次渲染确实会新建
`onPress` 闭包和 `[styles.tabLabel, …]` 样式数组，但分类只有十来个、都是纯 `<Text>`，
而且 RN 的样式数组会在 C++ 侧摊平成同一份 ViewProps（内容相同就不产生原生更新，
见第一轮第 3 节）。为它再抽一个 memo 组件属于「为不确定的收益加结构」，收益不抵噪声。

## 8. 其余列表屏

### `app/history.tsx` / `app/caches.tsx`

同一套改法：

- `renderItem` 包 `useCallback`；
- `HistoryRow` / `CacheRow` 加 `memo`，并把 `onPress` / `onDelete` 的签名从
  `() => void` 改成**收 entry/topic 的函数**——原来一行一个 `() => openEntry(item)`，
  props 恒不等，`memo` 加了也是白包（这正是 lead 提醒的那点；`ui/topic-row.tsx:43-46`
  的注释里早就写过同一条约定，这里是照抄它）；
- `ListHeaderComponent` / `ListFooterComponent` 原来是**内联 JSX 元素**，每次渲染都是新元素，
  FlashList 每次都要重挂头尾；改成 `useMemo`。
- **不加 `getItemType`**：这两屏的行是同构的（每行都是同一个行组件），加了没有意义。

### `app/chain.tsx`

除了上面的头像 `cachePolicy`，修了三处：

1. **`extraData={{ loadingPage, failedPages }}` 是每次渲染现建的对象**（原 `:170`）。
   FlashList 拿 `extraData` 的引用判「外部数据变没变」，每渲染必变 = 每次都把所有单元格重画，
   `ChainCard` 的 `memo` 完全被抵消。改成 `useMemo`。
2. **`ChainCard` 的 `onOpenInTopic` 是一行一个新闭包**，同样打穿 `memo`。
   改成给 `ChainCard` 加一个 `node` prop（`chain` 数组里的稳定对象），
   回调换成整屏共用的一个 `useCallback`。
3. **`ChainCard` 里给 `BBCodeBody` 传的 `options` 是内联对象**——第一轮把 `BBCodeBody` 包成
   `memo` 之后，这里必然每次都落空。改成 `useMemo`
   （`theme.typography` 是 `ui/tokens.ts:331` 的模块级常量，两套配色共用同一个对象，
   所以 `theme.typography.chainBody` 可以直接当依赖）。
4. 加了 `getItemType`：链上有两种形状差很远的行（已加载的楼层卡 / 降级占位卡）。

### `app/favorites/index.tsx`

`renderItem` 包 `useCallback`（`openTopic` 本来就是 `useCallback`，`TopicRow` 本来就是 `memo`，
`time` 是字符串按值比较——所以只差这一层）。行同构，不加 `getItemType`。

## 9. 看过但没改的（连同理由）

- **`app/notifications.tsx`**：用的是 `ScrollView` 不是 FlashList，条目全量挂载，
  没有回收池也就没有 `getItemType` / `renderItem` 稳定性的问题。数据是有界的（一屏通知），
  轮询 60s 才换一次 `items`。要提速得先把它换成 FlashList，那是重构不是卫生，
  **未实测，不做**。（另外确认了一件事：`markRead` 只改 `readIds` 不改 `items`
  （`store/notifications.ts:143-151`），所以 `useEffect([items])` 里调它不会自激。）
- **`app/favorites/folders.tsx`**：`ScrollView` + 对收藏夹列表 `map`，量级是个位数。不动。
- **`app/user/[uid].tsx`**：`ScrollView`，几个固定的信息卡 + 版面/威望列表，量级有界。
  只改了头像的 `cachePolicy`。
- **`app/favorites/index.tsx` 的 `ListFooterComponent`**：它真的依赖
  `isFetchingNextPage` / `error` / `loadedPages`，不是常量元素；包 `useMemo` 也得列四个依赖，
  收益（一个 footer 元素）不抵可读性损失。留着。
- **`ui/board-icon.tsx` 的 `StripedBackground`**：占位斜纹是 8 条绝对定位 + `rotate` 的
  `View`，每条都有 `backgroundColor` 和 `transform`——按第一轮那套摊平规则，
  这 8 个都是**真的 Android View**，还各带一个 stacking context。一屏三十个没图标的版块
  就是 240 个视图节点，这是首页真正可能贵的地方。但它已经 `memo` 了，且要改就得换方案
  （单张 9-patch / 一张随包底纹图 / Skia），属于**重构 + 要重新对设计稿**，
  首页又没实测数据——留给你决定要不要立票。

## 验证状态（第二轮）

- `pnpm typecheck`：**我改的文件零报错**。仓库当前剩下的报错都在
  `src/core/api/user-topics.test.ts`（`TopicList` 少了 `listStructure` 字段），
  是另一个 agent 正在改 `core/api` 类型的中间态，不属于本轮。
- `pnpm test`：78 passed / 4 skipped，1060 tests 全绿。
- 没碰 adb / 模拟器。**首页那一节（第 7 条）未经实测，收益是推断的**，
  下一轮走查值得单独给首页跑一遍慢拖——尤其是切到「手机游戏」那个 300+ 版块的分类。
