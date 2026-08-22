# 13 — 主题详情屏垂直切片(M2,最重的 UI 仗)

**What to build:** RN 版 `topic/[tid].tsx`(1828 行)的行为 1:1:楼层流(楼层卡:头像/用户名/楼层号/时间、正文(票 11)、贴条区、签名档、附件网格、投票只读、编辑标记)、热门回复折叠区、**横滑翻页**(Compose Pager/等价物:预渲染邻页、速度连续的松手接管——原生 fling 天然满足,RN 版弹簧参数就是对拍原生调的;「onPageSelected 时机不挂重渲染」纪律带上)、跳页、自动加载下一页、只看此人/只看该楼、引用块→回复链入口(`/chain` 屏一并做)、上次读到第 N 楼浮条(5s 自动消失)、缓存本页/整帖(进度+停止)、数据来源降级提示条(web 反解/离线缓存)、楼层菜单(点赞/点踩、收藏、屏蔽此人带撤销、查看签名、贴条桩、举报桩)、阅读进度记录(viewability 驱动,只前进)。
**性能设计(本票的存在理由)**:每页楼层的解析+渲染模型在数据到达时后台一次构建;页级产物常驻(anzong 原则);滚动路径零计算;`LazyColumn` key/contentType 齐全;拆掉 RN 版全部分帧补丁(contentReady/chromeReady/progressive)——原生不需要,若实测需要再加并记录。**修 P3-05**:屏蔽正则执行加长度/超时上限,且不在滚动路径上执行(过滤在数据层一次完成)。

**Blocked by:** 07, 11, 12

**Status:** in-review

- [x] 24 屏 checklist 的主题详情/回复链两屏全项与 RN 版对照通过(模拟器)
      —— 口径改为「按 research/inventory.md §2 的『主题详情』『楼层操作』两段逐条自测」
      (checklist 文档归票 18),真帖 tid=47406116 游客态实跑,逐条见 Comments
- [x] 初测:冷启后首次进主题起手无可见冻结、快甩无肉眼断续(正式闸在票 19)
      —— 模拟器 + debug 包只做「不崩、不卡死、无白屏」初测,**不下性能结论**
- [ ] 长帖(500+ 楼跨页)翻页/跳页/只看此人全链路可用
      —— 实跑的是 117 楼 / 6 页的真帖(翻页/跳页/横滑/只看此人/回到那里全通),
      **500+ 楼那一档没找到合适的公开长帖**,见 Comments「未完成」

## Comments

### 完成摘要(2026-08-22)

`native/app/src/main/kotlin/com/chasel/ng2n/ui/topic/` 下 19 个文件 + `ui/nav/Navigator.kt`,
外加对票 11 三处临时实现的收口与 `core/net/FetchDiagnostic.kt` 的一处追加。

| 层 | 文件 | 内容 |
|---|---|---|
| 导航 | `ui/nav/Navigator.kt` / `TopicKeys.kt` / `TopicEntries.kt` | `Navigator`(push/pop)、`TopicKey`/`ChainKey`/`UserKey`、`EntryProviderScope<NavKey>.topicEntries(nav)` |
| 数据 | `TopicRepository.kt` | 拉页 + 原始 `TopicDetail` 的**进程级 2 分钟缓存**(RN 侧 TanStack Query 的对应物)+ 后台建模 + 「缓存整帖」限速下载器(800ms、可停止、互斥)+ `TopicSnapshotSink` 接缝 |
| 建模 | `TopicRenderModel.kt` / `TopicPageBuilder.kt` | 一页 `read.php` → `PageRenderModel`(成品):`parseBBCode` + 骰子复算 + 匿名还原色 + 投票解析 + 贴条压平 + 图片收集 + 引用抽取 + `AnnotatedString` 组装,**全在 `Dispatchers.Default` 上一次做完** |
| 状态 | `TopicViewModel.kt` / `ChainViewModel.kt` / `TopicDeps.kt` / `FilterBridge.kt` | 页级常驻、翻页三入口、过滤视图、屏蔽表、回复链层数表、赞踩、阅读进度、缓存、签名弹窗 |
| 界面 | `TopicScreen.kt` / `ChainScreen.kt` / `FloorCard.kt` / `VoteBlock.kt` / `TopicChrome.kt` / `TopicOverlays.kt` / `TopicIcons.kt` / `Paging.kt` / `TopicDevEntry.kt` | 楼层流 / 横滑翻页 / 楼层卡 / 投票只读 / 顶栏页码条提示条骨架失败页 / 菜单对话框 Snackbar / 22 个 Canvas 图标 / 页码算术 / 票 16 删的手验入口 |

单测 49 例(`PagingTest` 8 / `TopicRepositoryTest` 10 / `TopicViewModelTest` 16 / `TopicPageBuilderTest` 14 + 1),
全仓 `./gradlew :app:testDebugUnitTest` **695 例 0 失败**(4 skipped = 票 07 的门控联网冒烟),
`:app:assembleDebug` 通过、零 `@Ignore`。

### 性能设计:RN 的三层分帧补丁**全部不移植**(票 11 票外 1 的决策落地)

RN 版为了压住 JS 单线程的首帧成本挂了三层补丁,这一版一条都没搬:

| RN 侧补丁 | 出处 | 这一版 | 理由 |
|---|---|---|---|
| `CONTENT_MOUNT_DELAY_MS`(转场期只画顶栏 + loading,列表壳等横推停稳再挂) | `topic/[tid].tsx:99` | **拆** | Compose 的首帧成本在 UI 线程,而**解析 + 建模整个在 `Dispatchers.Default`**;转场期没有重活可挂 |
| `chromeReady`(页码条 / FAB / 浮条等第 2 帧) | 同上 | **拆** | 同上;这三样加起来是几十个节点,不是 20 张楼层卡 |
| `progressive.tsx` 段级/楼级分帧(阈值 8 段、每帧 4 段) | `ui/progressive.tsx` | **拆** | `LazyColumn` 只组合视口内的项,一帧不会挂 20 张卡;而「一张卡里 420 段」那个场景(票 11 验收②)在真楼层里不存在 —— 段数由正文长度决定,长楼层是**一个 item**,滚动时才组合 |
| `CHAIN_INDEX_DELAY_MS = 1500`(回复链索引延后 1.5s 起跑) | `topic/[tid].tsx:110` | **拆** | RN 那 20–40ms 是因为 `buildQuoteIndex` 要把已加载楼层的 BBCode **重新解析一遍**;这一版引用在建模时就抽好了(`FloorRenderItem.quoteRefs`),索引只是拼一张 Map,而且在 `Dispatchers.Default` 上 |
| `HISTORY_VISIT_DELAY_MS = 96`(历史登记延后一拍) | 同上 | **拆** | RN 那是「同步 SQLite 写挤占同一帧」;这一版是 suspend + IO 协程(票 14 已修 P2-04) |

**若真机实测需要再加的挂钩**(票 19):先看 `LazyColumn` 的单个 item 是否过重 ——
真要分帧,做法是**把超长楼层按段切成多个 LazyColumn item**(让列表自己按视口分),
而不是把 RN 那套 `requestAnimationFrame` 分帧照搬过来:Compose 没有那个问题的成因。
`Modifier.preferredFrameRate(120f)` 已挂在 Pager 上(stack-2026-08 §12 ⑤,Compose UI 1.12 正式 API)。

### 修掉的已知缺陷:P3-05(屏蔽正则不在滚动路径上)

**RN 版原行为**:`TopicPageView.renderItem` 里现算 —— `useFloorFilter()` → `matchFilterRules(rules, …)`,
也就是**每次列表回收、每次屏级重渲染都跑一遍用户正则**(`topic/[tid].tsx:1338`)。
规则本身的资源上限票 10 已经修好(四道闸),但「在哪儿跑」这一半是本票的。

**修法**:`TopicViewModel.recomputeBlocked()` 在**规则表或页数据变化时**算一次,
整个在 `Dispatchers.Default` 上,产物是一张 `pid → FilterRule` 表;
楼层卡只做 `blockedFloors[pid]` 一次查表(`blockedRuleOf`)。滚动路径上零正则。

### 关键决定(票里留白的「实现时定」项)

1. **ViewModel 的作用域**:Nav3 的 `NavDisplay` 默认只装 `SaveableStateHolder` 一个装饰器,
   ViewModel 的作用域要自己加。`Ng2nApp.kt` 里补了
   `entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator())`
   —— 不加的话条目里的 ViewModel 挂在 Activity 上、pop 之后不 clear,页级渲染成品(几百 KB/页)会一直留着。
   票面要求的「离开屏幕才释放」就靠这一条。
2. **不用 `@HiltViewModel`**:那个装饰器给的是**裸** `ViewModelStoreOwner`,不实现
   `HasDefaultViewModelProviderFactory`,`hiltViewModel()` 在条目里拿不到 Hilt 工厂;
   而 ViewModel 又必须收 `TopicKey`。沿用票 12 `ImagePipeline` 的做法:
   **EntryPoint 取依赖(`TopicDeps`)+ `viewModel { }` 手写工厂**。
3. **原始 `TopicDetail` 也缓存 2 分钟**(不只缓存渲染成品):照抄 RN 的 `TOPIC_DETAIL_STALE_MS`
   与它的理由 —— 退回列表再点进同一个帖、从回复链返回、深链来回跳都是几秒内的事,
   各打一发 `read.php` 就是白担一份被封风险(ADR-0002)。**显式刷新无视它**。
   缓存的是原始 detail 而不是 `PageRenderModel`:后者按配色/字号烤过,换夜间模式就整份作废。
4. **建模调度器抠成参数**(`@ComputeDispatcher` / ViewModel 的 `compute`):
   单测要换成测试调度器,否则 `advanceUntilIdle()` 管不到真线程池里的活。
5. **「缓存整帖」的快照就地写盘**,不像前台那样丢给 IO scope 延后:
   后台批量本来就是慢活,顺序确定,「跑完了没」与「存完了没」不脱节。
6. **公共 UI 就近放在 `ui/topic/`**(顶栏 / 菜单 / 对话框 / Snackbar / 图标):
   完整公共体系归**票 17**,本票只做主题三屏用得到的那一份,避免与并行的票 16/17 抢同一批文件。
   票 17 铺开时把它们提升到 `ui/common/` 即可。
7. **横滑翻页用 `HorizontalPager` + `beyondViewportPageCount = 1`**:速度连续的松手接管是免费的
   (`research/inventory.md` §8:RN 的弹簧参数本来就是对拍原生 ViewPager 调出来的)。
   「onPageSelected 时机不挂重渲染」落成:`targetPage` 只喂页码条高亮(`pageInFlight`),
   真正换数据等 `settledPage`。**P1(子面板必须落在容器布局边界内)**天然满足 ——
   全靠 Pager 自己布局,没有「布局在外面再 transform 拉回来」的写法。
8. **表格横滑让路**:票 11 留的 `LocalHorizontalDragGuard` 这一版**用不上也没接** ——
   `HorizontalPager` 是 Compose 的嵌套滚动参与者,内层 `horizontalScroll` 的表格会先消费横向拖动,
   父 Pager 只拿到剩余的。RN 那套「模块级计数 + UI 线程镜像 SharedValue」是 gesture-handler
   抢手势的对策,原生这边没有那个成因。**实测**:demo 屏与真帖里的宽表都能独立横滑而不翻页。
   接口保留(默认 no-op),真遇到抢手势时再 provide。

### 对 RN 版的有意偏离

1. **匿名楼层的用户名用官方配的颜色**(`decodeAnonymousName().colors.first`)。
   RN 版解出了这两个颜色但**一处都没用**(全仓只有测试引用 `decodeAnonymousName`);
   票面明写「用户名(匿名还原色)」,所以这一版用上了。实名用户仍是主题色。
2. **「用网页版打开」直接开系统浏览器**,不是站内 `/web` 页 —— `/web` 屏归**票 17**。
   `LoadFailed` 的「用网页版打开」按钮同理,现在接的是空实现(那一屏没有 host 上下文),
   顶栏那枚是通的。
3. **顶栏菜单的「收藏本帖」与楼层菜单的「收藏」是 toast 桩**。
   `topic_favor_v2` 的四个端点票 07 已经就绪,但多选收藏夹对话框要接
   票 14 的 `TopicFavorIndex`(按 uid 分键的本机反向索引)+ 票 15 的账号态 + 收藏夹列表屏,
   那三样合起来是**票 17 的收藏页**。本票留入口不留半套实现。
4. **`LastReadBanner` 的 5 秒兜底从「进场动画真的跑完」起算**:
   `Animatable.animateTo` 挂起到动画结束才返回,正好是 RN 侧 `onShown` 那个判据
   (RN 的注释:按 `resumeVisible` 起算会让首屏重时只亮 0.8 秒)。
5. **`FloorActions` 一次性建好、引用终生不变**:RN 那套「上下文按页做身份缓存 + 回调走 ref」
   是为了让 memo 不被打穿;Compose 这边同一个目的,做法是把回调收进一个 `@Immutable`
   的 `FloorActions`,`remember` 在屏级持有。
6. **`BBCodeCallbacks` 加了 `chainDepth` 与 `onLongPress` 两个字段**(票 11 的类型)。
   前者是把「查看对话链(**N 层**)」的 N 补回来(票 11 只画了「查看对话链」);
   后者见下面的 bug。
7. **`describeFetchFailure` 补进 `core/net/FetchDiagnostic.kt`**(直译 TS 的同名函数)。
   票 04/06 没搬它,而失败页要它 —— 新增函数,没动既有代码。

### 收口票 11 的三处临时实现(票 11 Comments 票外 2 / 4)

- 新增 `ui/bbcode/BBCodeShapeAdapter.kt`:`object BBCodeNodeShape : BBCodeShape<BBCodeNode>`
  (类型名走 `@SerialName` 反射 + 按类缓存,不再手写对照表)、AST → `DiceScope` 抽取器、
  以及 **`resolveFloorDice`**;
- 删 `ui/bbcode/ReplyHeader.kt`,三个判据改用 `core/local/ReplyChain.kt` 的泛型版;
- `RenderModel.kt` 删掉 `DiceOutcome`/`DiceTerm`/`formatDiceTerms` 替身,改 import `core/local/Dice.kt`;
  `QuoteRef` 同样改 import `core/local`;
- `ui/bbcode/FloorImages.kt` 的 `FloorAttachment` 替身换成 `core/api/Types.kt` 的正式类型;
- 测试侧删 `core/local/MiniBBCode.kt`,`dice` / `reply-chain` 两个 domain 改用正式
  `parseBBCode` + `BBCodeNodeShape`,**`core/local` 主源码一个字没动**(票 10 的设计意图兑现)。

**这里有一个不接就出错的坑**:票 10 的 `resolveDice` 按**作用域顺序**返回
(本层全部 → 再逐个折叠块),而 `RenderModelBuilder` 是按**文档顺序**取第 n 个
(碰到 `[collapse]` 当场展开它的内容)。两者在「折叠块出现在某颗顶层骰子**之前**」时不同 ——
`[collapse][dice]d100[/dice][/collapse][dice]d8[/dice]` 会把 d100 的点数贴到 d8 上。
`resolveFloorDice` 就是那一步重排,有单测钉住(`TopicPageBuilderTest` 三条)。

### 手验里抓到并当场修掉的 bug

**长按正文出不来楼层菜单**。正文段自己用 `detectTapGestures` 认点击(链接/uid/tid/pid/@/防剧透
都钉在 annotation 上),而 `detectTapGestures` 会把 down 事件吃掉 —— 祖先楼层卡的
`combinedClickable(onLongClick = …)` 因此收不到长按,「长按整卡出菜单」只在正文以外的空白处才灵。
RN 版没有这个问题(`<Text>` 的 onPress 不吃长按)。修法:`BBCodeCallbacks` 加 `onLongPress`,
`TextSegmentView` 接进 `detectTapGestures(onLongPress = …)`,楼层卡转发成「打开本楼菜单」。
重装后实测:长按正文 → 六条菜单。

### 验收自测

**环境说明**:模拟器 `emulator-5554` 与票 16 共用,期间踩到两件事,都记在这里:
① 票 16 设的 `http_proxy=10.0.2.2:7897` 没还原,**模拟器所有 HTTPS 经代理全断**
(我的 `read.php`、票 16 的 `app_api.php`、RN 版 `com.chasel.ng2` 一起中招,
诊断日志八个组合全是 `network: connection closed`);主控还原成直连后恢复。
② 期间抓到过 launcher / RN 版被切到前台(`docs/perf-playbook.md` T12「测错对象」),
所以手验脚本每次 dump 前先核对 `package`。

#### ① 主题详情屏 —— 按 `research/inventory.md` §2「主题详情」「楼层操作」逐条

真帖 `tid=47406116`「人民日报下场了,提到了牛来」,6 页 117 楼,**游客态**:

| 条目 | 结果 |
|---|---|
| 楼层卡:头像(占位色+首字)/用户名/级别·威望·发帖/设备图标/[N 楼]/时间 | ✅ |
| `alterinfo` 编辑标记 | ✅ 「· 已编辑」 |
| 正文(票 11 渲染器):加粗/颜色/字号/表情内联/换行 | ✅ |
| 引用块 → 回复链入口 | ✅ 「查看对话链(2 / 3 / 4 层)」,层数随已加载页动态涨 |
| 贴条区 | ✅ 压成一行「谁:内容」 |
| 签名档 | ✅(含 `[collapse]` 签名) |
| 附件网格 | ✅ 折叠条「点击显示附件(N)」→ 三列宫格 + 非图片单列(离线帖验) |
| 投票只读 | ✅(离线帖验:选项 + 百分比条 + 底部说明 + 按钮) |
| 骰子本地复算 | ✅ `ROLL 1d100 = d100(76) = 76` |
| 热门回复折叠区 | ✅ 「热门回复(4)」 |
| 横滑翻页 | ✅ page2(楼 20/21/22)→ 横滑 → page3(楼 40/41/42) |
| 跳页 | ✅ 页码条 6 格 + 「跳页」对话框(共 N 页 · 输入 1–N;超范围只提示不夹逼) |
| 自动加载下一页 | ✅(设置默认开;只认手指滚出来的到底,单测另有两条) |
| 只看此人 | ✅ 页码条塌成 1 页 + 过滤条 + 「退出」回到进入前那一页;`authorid` 进 URL |
| 只看该楼 | ✅ 提示条「只看该楼 / 看全部」(路由带 pid 进场那条路) |
| 上次读到第 N 楼浮条(5s) | ✅ 二次进帖出「上次读到 第 42 楼」;「回到那里」→ 跳第 3 页并定位到 42 楼 |
| 缓存本页 / 整帖(进度 + 停止) | ✅ 菜单两条;**自动缓存实测落了 4 页**(`topic_cache` 里 `total_pages=6`) |
| 数据来源降级提示条 | ✅ 离线帖走缓存档时出「在线拿不到这一页,当前是**缓存数据**」+「重新联网」+ 关闭钮 |
| 加载失败页 | ✅ 「2048:找不到主题 / 这是论坛给出的说明…」+ 重试 / 用网页版打开 / 重新登录 |
| 阅读进度(只前进) | ✅ `browse_history` 实测 `last_floor=42, max_floor=117` |

**楼层操作**:

| 条目 | 结果 |
|---|---|
| 赞 / 踩 | ✅ 钮在、计数在;**写操作没实跑**(游客态,点了走「登录后才能点赞点踩」) |
| 长按 / kebab 出菜单 | ✅ 两条路都通(长按那条是上面修掉的那个 bug) |
| 菜单六条与顺序(贴条 / 举报 / 查看签名 / 收藏 ‖ 只看此人 / 屏蔽此人) | ✅ 分组线在「只看此人」前 |
| 查看签名 | ✅ 弹窗「查看签名 / …没有设置签名 / 取消 · 知道了」 |
| 屏蔽此人 + 撤销 | ✅ 楼层当场折成「已屏蔽 UID:… 的楼层 / 展开」+ Snackbar「已屏蔽 …,其发言将折叠」 |
| 点头像进资料 | ✅ push 占位 `UserKey`(资料屏归票 17) |
| 贴条 / 举报 | ✅ toast「本版本未开放」 |

#### ② 回复链屏

从第 8 楼的引用块进:顶栏「回复链 · 2 层」+ 回复(桩)、说明行「从第 8 楼展开:上游是它引用的
楼层,下游是引用它的楼层。」、上游卡(UID / [2 楼] / 时间 / 正文**已剥掉引用容器** / 赞数 /
「在原帖中查看」)、当前楼卡带「当前楼层」徽标与缩进;「在原帖中查看」回跳详情页并定位到那一楼。✅

#### ③ 初测(**不下性能结论**:模拟器 + debug 包,spec §五 / T7 / T8)

冷启后首次进主题、连续快甩十余次、六次翻页、进出回复链与过滤视图:
**无崩溃、无卡死、无白屏**,进程存活。真机裁决归票 19。

### 未完成 / 待所有者

- **长帖 500+ 楼那一档没跑**:手边能确认的公开帖最大是 117 楼 / 6 页。
  跨页翻页/跳页/横滑/只看此人/回到那里在这一档上全通,但「上千页页码条窗口」
  与「几十页跨页」这两件事只有单测(`visiblePages(50, 200)`)覆盖。
  **建议票 18 找一个水区热帖补跑**。
- **写操作(赞/踩/收藏)没实跑**:游客态。需要登录 —— **待所有者**,与票 15 的登录验收一并做。
- **收藏对话框未实现**(见「有意偏离」3),归票 17。
- **`/web` 网页兜底屏**归票 17,现在是开系统浏览器。
- 深色/浅色两套配色只验了深色一档(模拟器当前是 night)。

### 发现的票外问题

1. **屏蔽规则有两份模型**:票 14 的 `data/settings/FilterRules.kt`(`kind`/`origin` 是 String,
   为落盘容错)与票 10 的 `core/local/Filters.kt`(枚举 + P3-05 四道闸)。字段一一对应,
   本票加了 `ui/topic/FilterBridge.kt` 做搬运。**两份该合一**(存储那份保留 String 容错解析、
   判定那份只留枚举),归主控排期。
2. **票 16 改了模拟器的 `http_proxy` 没还原**,导致设备上所有 app 的 HTTPS 全断(见上)。
   建议在简报的「真人介入 / 模拟器」一段加一句:**改 `settings put global` 的人负责还原**。
3. `native/README.md` 的分层表里还没有 `ui/topic` / `ui/nav` 两级(票 12 也提过 `ui/image`),
   票 17 铺开公共 UI 时顺手补。
4. `Ng2nApp.kt` 本票只加了四处:`Navigator` 实例、`entryDecorators`、`topicEntries(nav)`、
   一行 `TopicDevOpenSection`(票 16 删)。**手验入口独立成 `TopicDevEntry.kt`**,
   就是为了让并行改 `Ng2nApp.kt` 的票 15/16 少一处冲突面。
5. 手验期间往设备的 `topic_cache` 塞过一个假帖(tid=90000001)走缓存档验离线路径,
   **收工已删干净**;仓库里没有任何 seed 数据,正常启动不会写入。
