# 详情页滚动卡顿修复(TopicScreen)

Status: done
改动文件:`src/app/topic/[tid].tsx`、`src/store/history.ts`、`src/ui/paging.ts`、`src/ui/horizontal-drag.ts`
`pnpm typecheck` / `pnpm test`(1057 passed)全绿。

出发点是走查数据里那条系统性差别:同一套拖动脚本,版块主题列表 0.3% janky,详情页
慢拖 54%、横滑 45%,而快甩只有 4.8%。慢拖 = move 事件多,快甩 = move 少 + 原生惯性,
差一个数量级说明贵的是**每个 touch move 上的 JS**,不是单元格渲染本身。下面三条按
「每 move 还剩多少 JS」来收。

---

## 1. 楼层卡片的 memo 被 `floorContext` 废掉了

**改了什么**

- `floorContext` 从 `body()` 里现建改成组件级 `useMemo`,依赖收敛到
  `topicId / data.users / data.attachBase / recommendOf / chainDepthOf` 这几项。
- 上下文里「只是要读最新值」的回调(`onOpenImage`、`onRecommend`、`onOpenChain`)
  全部 `useCallback(…, [])`,最新值走一份 `latest` ref;`onOpenMenu` 直接给 setState。
- 两个**必须**跟着变的例外保留了引用变化:`recommendOf` 依赖 `recommend.markOf`
  (只在赞踩标记表真的动过时换引用)、`chainDepthOf` 依赖 `chainIndex`。
  这两处换引用的时刻,正是卡片该重画的时刻。
- `renderItem` 提成 `useCallback`(`renderFloor`),依赖只有 `blockedRuleOf` 与
  `floorContext`;`blockedRuleOf` 依赖 `unfolded / matchFloorFilter / users`。
- 新增 `getItemType`:被屏蔽折叠行 `'blocked'` 与普通楼层 `'floor'` 分池回收。
  判断与 `renderItem` 共用同一个 `blockedRuleOf`,两边不可能给出不同答案。

**为什么**

`FloorCard` 是 `memo` 的,但 `context` 每渲染换引用,memo 恒为 miss。而且
FlashList v2 的 `ViewHolder` 自己也是 `memo`,它的比较函数里就有
`prevProps.renderItem === nextProps.renderItem`(`dist/recyclerview/ViewHolder.js:73`)——
旧代码里 `renderItem` 是内联箭头函数,每渲染都是新的,于是 **TopicScreen 的任何一次
重渲染都会把屏上每个 ViewHolder 连同 BBCode 子树全量重画**。TopicScreen 因为
`unfolded / menuFloor / fabOpen / menuOpen / favorOpen / signUser / isFetching` +
旧的 swipe hint 而重渲染得非常勤,滚动期间尤其。

**预期消掉哪一部分 jank**:滚动中「阅读进度 setState → 整屏重渲染 → 全量重画楼层」
这条链的后半段;以及横滑期间 hint setState 引发的整屏重画(与第 3 条叠加)。
`getItemType` 针对的是折叠行/楼层卡片混池时 FlashList 反复重量的那部分
(只在开了屏蔽规则的帖子里才有收益,没规则的帖子它恒为 `'floor'`,零成本)。

**正确性**:`unfolded`(点开折叠行)、`matchFloorFilter`(规则变化)、`data.users`
三者变化时 `renderFloor` 引用会变 → ViewHolder 重渲染,行为不变。赞踩变色、
回复链层数同上。

---

## 2. 阅读进度不再在滚动线程上同步写 SQLite

**改了什么**(`src/store/history.ts`)

- `recordReadFloor(tid, lou)` 现在只更新模块内的 `pendingFloor` 水位线;
  距上次落盘 ≥ `FLOOR_FLUSH_INTERVAL_MS`(1s)才立即落,否则挂一个尾巴
  `setTimeout` 到点再落(手指停半路不动也一定落盘)。
- 新增 `flushReadFloor()`:立即落盘,没有待落盘的东西时是纯 no-op。
- `clearHistory()` 会把攒着的进度**丢掉**而不是落盘(清完再写回去等于把删掉的
  条目变出来)。
- 换主题上报时先把上一条落定。
- 条目还没被 `recordTopicVisit` 建好时(core 层会原样丢弃这次上报),水位线一起丢,
  下一次上报才能重来。

`src/app/topic/[tid].tsx` 的 `useReadingProgress` 里补了三处兜底 flush:
`AppState` 变非 active、`useFocusEffect` 的 blur 清理、effect 卸载清理。

**为什么**

`onViewableItemsChanged` → `recordReadFloor` → `apply()` 里
`db().withTransactionSync(...) + runSync(...)`,是**滚动那一帧上的同步磁盘写**。
慢拖时可见项变化一秒十几次,每次都是一趟 WAL 写 + 一次 `useHistoryStore.setState`
(后者又会推着订阅者重渲染)。节流到 1s 后,这两样都降到每秒最多一次。

**语义没变**:仍然只前进(内存水位线 + core 的 `advanceHistoryFloor` 双重拦),
仍然一定落盘(节流 + 尾巴定时器 + 三处兜底)。`recordTopicVisit` 那条路径没动。

**预期消掉哪一部分 jank**:慢拖里最硬的那块——同步 I/O 落在 UI 帧上,一次就够丢帧。

**风险**:进程被系统直接杀掉(不经过 background 回调)时,最多丢最后 1 秒的进度。
以前是丢 0 秒。这个交换我认为是值的,但如果你不接受,把间隔调小即可(常量在
`src/store/history.ts` 顶部)。

---

## 3. 横滑翻页从 PanResponder 换成 gesture-handler + Reanimated

**改了什么**

- `useSwipePaging` 重写:`Gesture.Pan()` + shared value,`GestureDetector` 包
  `Reanimated.View`。位移 `translateX` 是 shared value,`useAnimatedStyle` 驱动,
  全程不过 JS。
- 手势判定用 `.manualActivation(true)` 自己在 `onTouchesMove` 里算(UI 线程 worklet):
  条件与旧实现逐字一致 —— `|dx| >= 12 && |dx| > |dy| * 1.3`。位移一律按
  「离按下点多远」算(`absoluteX - origin`),因为 RNGH 的 `activate()` 会
  `resetProgress()` 把 `translationX` 归零(`PanGestureHandler.kt:232`),用
  `event.translationX` 会白丢掉起手那 12px,翻页阈值就跟页码条不是一套了。
- `horizontal-drag` 那套机制保留,但多了一份给 UI 线程读的镜像
  `horizontalDragActive`(`makeMutable`)。**没有**改用
  `blocksExternalGesture`/`simultaneousWithExternalGesture`:那要拿到表格那个
  ScrollView 的 ref,而它在 `ui/bbcode/blocks.tsx`,隔着 FlashList + 渲染器好几层,
  跨层传 ref 比现在这个模块级标志更脆(而且 bbcode/** 这轮不归我改)。
  也没用 `activeOffsetX`/`failOffsetY`:原生阈值表达不了「横向**压过**纵向」这个
  比例关系,而且没有地方插进「先看一眼标志位」这一步。
  时序上不怕抢跑:标志是手指按下那一刻(JS 线程)写的,判定发生在走够 12px 之后。
  副作用是 `isHorizontalDragActive()` 没人用了,已删。
- 提示文案不再是 TopicScreen 的 state:抽成 `SwipeHint` 小组件,手势通过
  `runOnJS` 调它的 `show()`,而且**只在文案真的变了**才回一次 JS(一次拖动通常 2–3 下)。
  样式与设计稿 `swipeHint` 一字未改。
- 翻页算术仍然全部走 `ui/paging`。为了让它们能在 UI 线程跑,
  `clampPage / swipeDirection / swipeTargetPage / swipeHintText / swipeOffset`
  加了 `'worklet'` 指令 —— 在 JS 线程上是一句无害字符串,页码条、跳页对话框、
  单测全部照旧(`src/ui/paging.test.ts` 无改动、全绿)。
- 松手回弹的缓动:`withTiming` 要 Reanimated 的 Easing,`ui/motion` 的
  `easeDecelerate` 是 RN `Animated.Easing`,搬不过去,所以在 `[tid].tsx` 里按同样的
  控制点 `cubic-bezier(.2,.8,.3,1)` 声明了一份 `swipeEase`。**这是一处重复**,
  正确的家是 `ui/motion.ts`,但那个文件不在这轮允许改的清单里 —— 建议后面挪过去。

**为什么**

`onMoveShouldSetPanResponderCapture` 在**纵向滚动时也会被每个 move 调用**(捕获阶段
从根往下走,不管认不认领),认领之后 `onPanResponderMove` 每个 move 再
`setHint(...)` 触发整屏重渲染。这就是慢拖 54% / 横滑 45% 而快甩只有 4.8% 的
直接解释。现在纵向滚动那条路上,每个 move 只跑一段 UI 线程 worklet(两次减法一次比较),
**一句 JS 都不跑**。

**预期消掉哪一部分 jank**:慢拖与横滑翻页两栏的大头。

**根布局注释**:`useSwipePaging` 里那句「不值得为它改根布局」已经过时
(`src/app/_layout.tsx:63` 早就有 `GestureHandlerRootView` 了),连同整段文档一起重写了。

---

## 你该怎么验证

出 release 包跑同一套 `dumpsys gfxinfo` 脚本,四个场景全跑一遍。我的预期:

| 场景 | 现在 | 预期 |
|---|---|---|
| 版块主题列表 慢拖 | 0.3% | 不动(对照组) |
| 帖子详情 慢拖 | 54% | 个位数 |
| 帖子详情 横滑翻页 | 45% | 个位数 |
| 帖子详情 快甩 | 4.8% | 持平或略好 |

三条改动是可以分开归因的,如果总数没到位,建议按这个顺序拆:

1. 把 `FLOOR_FLUSH_INTERVAL_MS` 临时改成 `0`(等于退回每次都写盘),只跑慢拖 ——
   差值就是第 2 条的收益。
2. 把 `renderItem={renderFloor}` 临时换回内联箭头函数,只跑慢拖 ——
   差值就是第 1 条的收益(注意这条也会连带废掉 `FloorCard` 的 memo)。
3. 横滑那一栏基本只归第 3 条。

**功能回归请手点这几处**(都不在单测覆盖范围里,本仓库跑不了渲染测试):

- 左右滑动翻页:走够 60px 才翻、拖到第 1 页/最后一页有强阻尼、中途松手会弹回;
  过程中「第 N 页 / 已是第一页 / 已是最后一页」提示浮层照常出现和消失。
- 上下滚动不会被误判成翻页(尤其斜着起手再转横向的那种)。
- **楼层里带 `[table]` 的帖子**:表格能横向滚,滚它的时候不会翻页。这条是这次
  时序改动最需要真机确认的一处。
- 屏蔽规则命中的楼层:折叠行显示正常,点「展开」能就地展开(验证 `getItemType`
  与 `renderItem` 的一致性)。
- 赞踩:点了立刻变色变数(验证 `recommendOf` 的引用变化没被 memo 吃掉)。
- 引用块的「查看对话链(N 层)」:翻几页后 N 会变大(验证 `chainDepthOf`)。
- 阅读进度:读到几十楼 → 直接返回 → 重进,提示条说的楼号是对的;
  读到几十楼 → 直接切后台 → 杀进程 → 重进,楼号也是对的(最多差 1 秒内的楼)。
- 历史页「清空」之后,刚才那个帖子不会又冒出来。

## 有意没做的

- **`ListHeaderComponent` 没有 memo 化**:它每渲染都换新元素,会让 header cell
  重渲染。但里面是三个小条子(续读提示 / 只看此人 / 热门回复折叠头),热门回复
  展开后的 `FloorCard` 已经吃到稳定 context 的好处了,收益不值一个额外的 memo 边界。
- **`FloorCard` / `bbcode/**` / `avatar` 一律没碰**(另一个 agent 在改)。
  注意:如果那边动了 `FloorContext` 的字段,这边 `useMemo` 的依赖数组要跟着对一遍。
- **没有给 FlashList 加 `extraData`**:现在靠 `renderItem` 的引用变化来驱动重渲染,
  语义更准(`extraData` 是全表失效,`renderItem` 也是,但后者不用多维护一个字段)。

---

# 第二轮

改动文件:`src/ui/motion.ts`、`src/app/topic/[tid].tsx`、`src/store/topic-detail.ts`、
`src/app/_layout.tsx`、`src/store/notifications.ts`。
`pnpm typecheck` 干净、`pnpm test` 1060 passed / 14 skipped 全绿(跑的是包含其它 agent
中间态的工作区;`board/[id].tsx` 一度有两条 TS 报错,不是我的文件,现在也已经消失)。

## 4. `swipeEase` 归位到 `ui/motion.ts`

改成 `easeDecelerateWorklet`,和 `easeDecelerate` 上下挨着放,注释写明**为什么必须是两份**:
一条是 `react-native` 的 `Animated.Easing`(JS 线程上的普通闭包),一条是 Reanimated 的
`EasingFunctionFactory`(要能序列化搬到 UI 线程),两者互不通用,拿错了直接运行时报错。
注释里点名「改控制点时两条一起改」。`[tid].tsx` 里的本地副本已删。

## 5. 查询缓存策略:分接口,不设全局值

**`store/topic-detail.ts`**:`staleTime: 2 分钟`(`TOPIC_DETAIL_STALE_MS`)。
吃掉的是「重新挂载一个已经在缓存里的页」这一类请求 —— 退回主题列表再点进同一个帖、
从回复链页返回、深链来回跳,都是几秒内的事,内容不可能变却各打一发 `read.php`。
2 分钟这一档是按「用户觉得该有新楼了没有」定的:再长会让人退出去再进来还看到旧页。

**显式刷新不受影响**,已经对着 `@tanstack/query-core@5.101.4` 的源码确认过:

- `queryObserver.js:158` 的 `refetch()` 直接 `this.fetch()` → `#executeFetch()`,
  **完全不看 staleness**。所以下拉刷新、FAB「刷新」、提示条「重试原生」照常真发请求。
- staleTime 只在 `shouldFetchOnMount`(`:447`)那条路上起作用,而且只在
  `query.state.data !== undefined` 时 —— 翻到没读过的页是新 queryKey、没有缓存条目,
  一定发请求。

**全局默认没有动**,但写成了显式的 `staleTime: 0` 并附了理由(`app/_layout.tsx`):
各接口「多久算旧」差着量级,一刀切会悄悄给版块列表 / 通知 / 签到都上缓存;
更要紧的是**它会挡住你正在做的 P0 验证** —— 请求根本没发,日志里什么都看不到,
「多个版块都出内容」会误判成修好了。版块列表那条按你说的没碰(在 net agent 手里)。

**行为变了的 key 只有一个**:`['topic-detail', tid, page, fav, pid, authorId]`。
其余 key 一律照旧。

## 6. 内存:**建议先别改**,理由是算出来的

我按你说的没有猜。拿仓库里的真实 fixture 跑了一遍解析,量的是
「一页 `TopicDetail` 进了 Query 缓存之后到底占多大」:

| fixture | 线上字节 | 楼层 | 解析后 JSON | UTF-16 保守估 |
|---|---|---|---|---|
| `read-anonymous-hotreply` | 17.4KB | 19 + 4 热回 | 11.1K chars | ~22KB |
| `read-attachments` | 22.1KB | 20 + 4 热回 | 14.0K chars | ~28KB |
| `read-board-head` | 13.3KB | 2 | 7.1K chars | ~14KB |

**满员一页 ≈ 28KB。** 于是:

- 20 帖 × 每帖看 3 页 ≈ 60 页 ≈ **1.7MB**
- 极端情况(20 帖全整帖缓存、每帖 13 页)≈ 260 页 ≈ **7MB**

而实测涨的是 **115MB**(174 → 289)。**差两个数量级 —— `gcTime` 不可能是原因。**
按 5.75MB/帖 的涨幅倒推,更像是解码后的位图:一张 1080×1920 的 ARGB_8888 就是 8MB。

所以我**没有动 `gcTime`**,也没加 QueryClient 上限策略。收紧它的代价是实打实的:
每次都要重打 `read.php`(ADR-0002),而且 `loadedTopicPages` 是靠扫 Query 缓存来建
quote 索引的(`store/topic-detail.ts:44`),页被 GC 掉之后「查看对话链(N 层)」的 N 会缩水。
为一个算得出来只有个位数 MB 的收益去付这两样,不划算。

**需要等 `dumpsys meminfo` 分项才能判断的**(我列出来,不猜):

1. `Graphics` / `EGL mtrack` / `GL mtrack` 有多大 —— 这是位图缓存的读数。如果它就是那 115MB,
   方向是 expo-image 的内存缓存档与解码尺寸(`ui/bbcode/content-image.tsx`、`ui/avatar.tsx`、
   `ui/floor-card.tsx` 的附件宫格,都在 render agent 手里)。走查报告 P1-3 已经记了
   `cachePolicy="disk"` 不带内存缓存这件事 —— 注意那条改完(改成 `memory-disk`)**内存只会更高**,
   两件事要一起看,别把它的回归算到别处。
2. `Native Heap` 有多大 —— Hermes 的 JS 堆算在这里。如果它涨得多,那才轮到 Query 缓存
   与 BBCode AST 的嫌疑,那时再回头收 `gcTime` 不迟。
3. `Code` / `.so mmap` —— 这一项不随浏览增长,用来当对照。
4. 「不回落」是在多久之后测的?如果不到 5 分钟,`gcTime` 根本还没到期,这条观察不能用来
   证明或否证任何缓存假说。**下一轮请在退出主题后等满 6 分钟再读一次 PSS。**

## 7. 通知轮询:加了「拉空就退避」

`store/notifications.ts`。原来是前台常驻 60s 一发 `nuke.php`。

「页面不可见时停」**你要的那条已经有了**(`AppState` 变非 active 就 `clearInterval`),
所以只加了退避:连续拉空 1/2/3+ 次分别跳过 1/2/4 格,实际频次退到
60s → 120s → 180s → 300s。挂着不管的账号从一小时 60 发降到 ~12 发。

两处回到 60s:拉到了新通知(`items` 变长,`mergeNotifications` 只增不覆盖,所以条数是可靠信号);
或者 app 刚回到前台(那时用户大概率就是冲着看消息来的)。通知页自己那次主动拉不受影响。

**退避靠「跳格」而不是改周期**:定时器仍然稳稳 60s 一格,只是到点了不一定发请求。
这样 start/stop 那套一个字都不用动,也没有「改周期要重建定时器」的竞态。

代价:最坏情况角标晚 5 分钟亮(原来最坏 1 分钟)。对一个只读向客户端的「被喷提示」
我认为可以接受;不接受的话把 `POLL_BACKOFF_SKIPS` 的尾巴砍短即可。

## 这一轮怎么验证

- **`staleTime`**:进一个帖 → 返回 → 立刻再进,抓包/看日志应该**只有第一次**打 `read.php`;
  然后在帖子里下拉刷新,**必须**看到一发新请求(验证 `refetch` 不受 staleTime 影响)。
  再等 2 分钟以上重进,应该又打一发。
- **P0 验证不受影响**:版块列表那条 key 我没碰,`staleTime` 仍是 0,连开多个版块该发的请求
  一发不少。
- **横滑回弹手感**:缓动搬家后应该和第一轮**完全一样**(同一条 bezier,只是换了个出处)。
  松手回弹肉眼觉得不对就是搬错了。
- **通知**:登录后挂着不动几分钟,`nuke.php` 的间隔应该逐步拉长到 5 分钟;
  切后台再回前台,应该立刻打一发并回到 60s。
