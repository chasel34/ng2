# Android 真机卡顿诊断记录（2026-08-15）

## 环境与方法

- 设备：25113PN0EC（Android，1220 × 2656；屏幕支持 120 Hz，复现时应用实际以 60 Hz 渲染）。
- 连接：无线 ADB，mDNS 调试地址 `192.168.0.102:46489`。
- 应用：`com.chasel.ng2`，versionCode 2。
- 构建：使用项目本地 Gradle `:app:assembleDebug` 构建 dev client，覆盖安装成功；未使用 EAS。
- 运行：本地 Expo Metro 提供当前工作区的最新 JS/TS bundle。
- 采样：Android `dumpsys gfxinfo` 冷/热态对照、慢拖/快速甩动/分页滚动对照、系统与 React Native 日志检查。
- 约束：本轮只复现和定位原因，没有修改源码。

## 已确认问题

### 1. 进入帖子时转场动画卡顿

#### 复现结果

- 冷态进入主题时出现约 65 ms 长帧。
- 立即退出再进入同一主题，最长帧约 38 ms，但仍有 2 个 frame deadline miss。
- 复现时应用以 60 Hz 渲染；65 ms 相当于连续错过约 3～4 帧。

#### 原因

全局页面转场只有 220 ms。在这段横推动画中，详情数据返回会把轻量的 `LoadingState` 替换为完整的 `FlashList`，并同时发生以下工作：

1. 可见 `FloorCard` 同步解析 BBCode、骰子、投票与楼层图片列表。
2. 扫描当前主题所有已加载页，构建回复链索引。
3. 请求返回前序列化整页响应，并用 `runSync` 同步写入帖子缓存 SQLite。
4. 详情页挂载后用同步 SQLite 事务登记浏览历史。
5. Fabric 提交并布局详情页首屏视图。

因此，问题不是单一动画曲线异常，而是路由转场与详情首屏重负载提交、同步数据处理和同步落盘重叠。冷态还承担首次资源和缓存成本，所以冷态 65 ms、热态约 38 ms。

#### 相关代码

- `src/app/_layout.tsx`：Stack 的 220 ms 全局转场。
- `src/app/topic/[tid].tsx`：详情 FlashList、回复链索引、浏览历史 effect。
- `src/ui/floor-card.tsx`：楼层 BBCode、投票、图片列表等首屏计算。
- `src/core/api/topic-detail.ts`：请求返回前生成缓存 snapshot。
- `src/store/topic-cache.ts`：同步 SQLite 缓存写入。
- `src/store/history.ts`：同步 SQLite 浏览历史事务。

### 2. 帖子详情滚动时偶发“抖一下”

#### 复现结果

- 纯文本主题慢滚：99 分位约 11 ms，1424 帧中仅 1 个 deadline miss。
- 图片尺寸已缓存后慢滚：99 分位约 16 ms，同样仅 1 个 deadline miss。
- 冷态首次图片滚动的帧时间仍可保持正常，但会发生内容几何位置的瞬时变化。

这说明现象主要不是持续渲染掉帧，而是列表内容高度变化造成的位置跳动。

#### 原因

正文图片首次出现时，服务端没有提供尺寸：

1. `ContentImage` 先按固定 4:3 比例占位。
2. `expo-image` 的 `onLoad` 返回真实尺寸。
3. 组件调用 `setLoaded`，把 `aspectRatio` 改成真实比例（竖长图最低限制为 0.6）。
4. 当前楼层高度随之显著改变，FlashList 重新测量并修正滚动偏移。

在真机样本中，一张竖图会从约 4:3 的占位高度扩展到接近一整屏以上。若用户正在滚动，偏移校正看起来就是“抖一下、卡一下”。`transition={120}` 只处理图片淡入，不会平滑容器高度。

附件展开还有一个类似的次要高度跳变：附件网格第一次以 `gridWidth=0`、`cellSize=0` 渲染，`onLayout` 后才获得真实三列尺寸。

#### 相关代码

- `src/ui/bbcode/content-image.tsx`：4:3 占位、真实尺寸缓存、`onLoad` 修改比例。
- `src/ui/floor-card.tsx`：附件网格的 `gridWidth` / `cellSize` 二阶段布局。
- `src/app/topic/[tid].tsx`：承载动态高度楼层的 FlashList。

### 3. 首页版块列表左上角抽屉首次打开卡顿

#### 复现结果

- 第一次打开抽屉出现约 48 ms 和 65 ms 两个长帧，99 分位约 65 ms。
- 关闭后再次打开，99 分位降至约 11 ms，仅有一个约 20 ms 的边缘长帧。

#### 原因

抽屉关闭时完全不挂载。点击菜单后，`Drawer` 在同一个 effect 中调用 `setMounted(true)` 并立刻启动 220 ms 原生动画；下一次提交才创建抽屉视图。

这次首次提交会一次性创建：

- 全屏遮罩与带阴影的 300 pt 面板；
- 账号头部和手势处理器；
- ScrollView；
- 12 个菜单条目、状态文字和图标字形。

首次图标字形和相关原生资源尚未预热，整套视图创建、布局和首次绘制落在动画起始阶段，因此出现明显长帧。第二次打开时样式、字形和图形资源已热，卡顿显著减轻。

#### 相关代码

- `src/ui/drawer.tsx`：`mounted` 状态、挂载与动画同时启动、关闭后卸载。
- `src/ui/app-drawer.tsx`：ScrollView 和全部菜单条目的同步挂载。
- `src/app/index.tsx`：首页打开抽屉的状态入口。

### 4. 主题列表滚动时偶发迟滞

这里测试的是版块内的**主题列表**，不是主题详情页。

#### 复现结果

在同一份“网事杂谈”列表上做了四组对照：

| 场景 | p50 | p95 | p99 | 现象 |
| --- | ---: | ---: | ---: | --- |
| 三次慢速拖动 | 9 ms | 10 ms | 15 ms | 跟手，只有 1 个现代口径 janky frame |
| 两次快速甩动（第一轮） | 17 ms | 18 ms | 25 ms | 惯性滚动明显变“黏”，legacy janky 84.48% |
| 相同内容快速甩动（热态复测） | 17 ms | 19 ms | 25 ms | 迟滞保持，legacy janky 87.84% |
| 全新进程慢拖并跨过第一页分页边界 | 9 ms | 11 ms | 17 ms | 第二页已追加，没有额外长停顿 |

快速甩动两轮结果高度一致，说明它不是首次字形或首次内容缓存预热。帧时间主要密集在 17～19 ms，偶尔到 25～27 ms，没有出现类似进入详情时的 48～65 ms 硬卡；主观上更像用户描述的“迟滞”，而不是画面停住。

设备设置的峰值刷新率是 120 Hz，面板也处于 120 Hz 模式，但系统在应用运行期间报告 `renderFrameRate=60`，刷新率投票中也有应用请求 60 Hz。也就是说列表运动画面实际按约 60 Hz 的节奏输出；与系统 120 Hz 界面相比，天然会显得不够跟手。现代 FrameTimeline 口径因此只记到 1 个 deadline miss，而按更高刷新节奏衡量的 legacy 口径会把大量 17 ms 帧计为 janky。这与“没有明显长帧，但看起来发黏”的体感一致。

#### 原因

主因由两层叠加：

1. **当前 dev client 在这台 120 Hz 真机上只获得了 60 Hz 的应用渲染节奏。** 项目源码和 Android 配置中没有找到主动设置刷新率的代码，因此这一层来自当前 React Native / Android 窗口与系统刷新率协商，不是主题列表业务代码显式锁帧。
2. **快速甩动时，FlashList 的回收与文本重排把单帧渲染成本从约 9 ms 推到约 17 ms。** 当前 `FlashList` 没有配置 `drawDistance`，FlashList 2.0.2 在 Android 上默认只预渲染 250 px；向前滚动时实际分给前方的缓冲约 350 px，接近一条主题行的高度。高速惯性滚动会很快穿过这段缓冲，`onScroll` 每次算出新的 engaged indices 都会触发列表内部状态更新，回收单元格随后绑定新的 `TopicRow`。

`TopicRow` 的标题允许自然换行，行高在约 253～415 px 之间；每个回收单元格还要重新布局嵌套标题文本、作者、最后回复人、两个图标和回复数。慢拖时新行进入缓冲的频率低，约 9 ms；高速甩动时这套回收、文本布局和 GPU 栅格化连续发生，CPU 帧时间升到 17～19 ms，GPU 中位数也从约 5 ms 升到约 13 ms，于是 60 Hz 节奏进一步贴近预算上限并偶发到 25 ms。

分页不是主因。全新进程从第一页慢拖触发第二页后，第二页内容已实际出现在列表中，但 p50 仍为 9 ms、p99 为 17 ms，最长约 21 ms。代码中的分页合并是 `Set` 去重的线性扫描，`useMemo` 会在页数据改变时才重算；已有主题对象引用保持不变，`memo(TopicRow)` 也阻止了加载状态变化时旧行的全量重画。因此分页可能增加一次轻量提交，但没有形成这次偶发迟滞。

#### 相关代码

- `src/app/board/[id].tsx`：FlashList、分页追加、`onEndReachedThreshold=0.6`；未设置 `drawDistance`。
- `src/ui/topic-row.tsx`：可变高度的多段嵌套标题与元信息行；`TopicRow` 已使用 `memo`。
- `src/core/api/topic-list.ts`：`mergeTopicPages` 使用 `Set` 线性去重。
- `node_modules/@shopify/flash-list/src/native/config/PlatformHelper.android.ts`：Android 默认 `drawDistance=250`。
- `node_modules/@shopify/flash-list/src/recyclerview/helpers/EngagedIndicesTracker.ts`：滚动方向缓冲分配与 engaged indices 计算。
- `node_modules/@shopify/flash-list/src/recyclerview/RecyclerView.tsx`：engaged indices 改变后更新内部 render state。

## 已排除或暂不支持的原因

本轮复现日志中没有发现：

- GC 暂停导致的对应卡点；
- SQLite locked；
- React Native 运行时异常；
- 崩溃或图片解码错误。

阅读进度在前进时最多每秒同步写一次 SQLite，属于潜在的 JS 线程停顿来源；但纯文本主题连续慢滚非常平稳，没有形成与现象匹配的周期性卡顿，因此不是本轮详情滚动抖动的主因。

主题列表分页追加同样没有出现对应的长帧、GC、SQLite 锁或 React Native 异常，因此不支持“网络请求完成或分页合并导致偶发卡顿”这一假设。

## 修复与复测（2026-08-15）

### 修复内容

#### 1. 帖子转场与详情首屏

- 详情路由开始后的 252 ms 内只挂顶栏和轻量加载态，完整楼层树在 220 ms 横推结束后再提交。
- 回复链索引改为首屏提交后延迟构建，不再在详情首屏 render 中扫描、解析所有已加载楼层。
- 浏览历史登记延后到首屏提交之后。
- 前台帖子缓存改为惰性快照：网络请求返回时不立即序列化整页，320 ms 后才创建快照并写入 SQLite；后台“缓存整帖”仍保持立即写入。

相关改动：

- `src/app/topic/[tid].tsx`
- `src/core/api/topic-detail.ts`
- `src/store/topic-detail.ts`
- `src/store/topic-cache.ts`
- `src/app/chain.tsx`

#### 2. 详情滚动、正文图片与附件

- 正文图片从 4:3 占位切换到真实宽高比时，容器高度使用 180 ms UI 线程布局动画平滑过渡。
- 详情 FlashList 关闭 `maintainVisibleContentPosition`，避免动态高度变化触发主动 `contentOffset` 修正。
- 附件网格改成首帧就确定的百分比宽度加 `aspectRatio: 1`，删除 `0 × 0 -> onLayout -> 实际尺寸` 的二阶段布局。

相关改动：

- `src/ui/bbcode/content-image.tsx`
- `src/ui/floor-card.tsx`
- `src/app/topic/[tid].tsx`

#### 3. 首页抽屉

- 抽屉随首页预挂载，关闭时只禁用点击与无障碍访问，不再卸载整棵菜单树。
- 打开动作只改变已存在视图的原生动画值；面板启用 Android 硬件纹理，首次打开不再同时创建菜单、布局、栅格化并启动横移动画。

相关改动：

- `src/ui/drawer.tsx`

#### 4. 主题列表与高刷新率

- 主题列表 FlashList 的前向预绘制距离从 Android 默认 250 px 提高到 1200 px。
- `renderItem`、列表头、列表尾与加载下一页回调改成稳定引用，减少列表内部状态变化时的无效工作。
- 关闭主题列表的可见位置锚点修正。
- Android 窗口在创建和恢复时请求当前物理分辨率下的最高刷新模式。实现放在 Expo config plugin 中，避免 `android/` 重新生成后丢失。

相关改动：

- `src/app/board/[id].tsx`
- `plugins/with-high-refresh-rate.js`
- `app.json`

### 真机复测结果

复测设备、ADB 连接、Debug 构建和 Metro 环境与上文相同。APK 由本地 Gradle 构建并覆盖安装，没有使用 EAS。

| 场景 | 修复前 | 修复后 | 结论 |
| --- | --- | --- | --- |
| 帖子 220 ms 转场窗口 | 冷态最长 65 ms；热态最长约 38 ms | p50 8 ms，p95 25 ms，p99/最长 27 ms；1 个现代口径 janky frame | 转场窗口内不再出现 38～65 ms 的硬卡；完整内容提交已移到动画后 |
| 帖子详情含大图慢滚 4 次 | 图片比例落地时会发生几何跳变；图片缓存后 p99 约 16 ms | 1024 帧，p50 5 ms，p95 10 ms，p99 13 ms；2 个现代口径 janky frame（0.20%） | 未再观察到滚动位置突然回弹，图片高度变化为平滑展开 |
| 附件首次展开 | 首帧 0 × 0，下一次布局整块跳高 | 6 帧，p99 12 ms；0 个现代口径 janky frame | 网格首帧即有稳定尺寸 |
| 首页抽屉首次打开 | p99 约 65 ms，出现 48/65 ms 长帧 | 200 帧，p50 9 ms，p95 13 ms，p99 40 ms；1 个现代口径 janky frame（0.50%） | 首次打开的两次明显硬卡已消除，仍有一个孤立 40 ms 首帧成本 |
| 首页抽屉再次打开 | p99 约 11 ms | 206 帧，p50 6 ms，p95 7 ms，p99 10 ms；1 个现代口径 janky frame（0.49%） | 热态保持平滑 |
| 主题列表慢拖 3 次 | p50 9 ms，p95 10 ms，p99 15 ms | 1936 帧，p50 9 ms，p95 11 ms，p99 15 ms；1 个现代口径 janky frame（0.05%） | 没有回退 |
| 主题列表快速甩动 3 次 | p50 17 ms，p95 18～19 ms，p99 25 ms；legacy janky 84～88% | 1382 帧，p50 13 ms，p95 19 ms，p99 21 ms；现代 janky 0.07%，legacy janky 46.89% | 中位成本下降约 24%，尾部下降 4 ms，偶发迟滞显著减轻但 p95 仍接近 19 ms |

快速滚动进行中再次读取显示服务，系统报告：

- `renderFrameRate=120.00001`
- `frameRateOverride { uid=10368 frameRateHz=120.00001 }`
- `PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE=120.00001`

应用静止时系统会因自适应刷新降回 60 Hz；手势滚动时会升到 120 Hz。原诊断中的“应用始终按 60 Hz 输出”已经修复。

### 复测边界

- 帖子完整楼层树在转场结束后挂载，因此把采样窗口延长到 1.8 秒时，仍可能捕捉到极少数 44/53 ms 的内容提交帧；它们不再与 220 ms 路由动画重叠。若后续要求“内容出现本身也完全无长帧”，需要继续把首屏楼层拆成更细的渐进提交，这属于下一层优化，不是原来的转场重叠问题。
- 主题列表快速甩动已不再锁在 60 Hz，p50 和 p99 均改善，但多行中文标题在高速回收时的文本布局成本仍让 p95 保持在 19 ms。现代 FrameTimeline 只记录 1 个 deadline miss，主观迟滞已减轻；要稳定贴近 120 fps，需要进一步约束行高或把标题排版移出高速回收路径，会改变当前列表信息密度和视觉行为。
- 抽屉首次打开仍有一个孤立 40 ms 帧，但修复前连续出现的 48/65 ms 两个长帧已经消失，热态 p99 为 10 ms。

### 工程验证

- `npm run typecheck`：通过。
- `npm test`：79 个测试文件通过、4 个跳过；1103 个测试通过、14 个跳过。
- 新增缓存测试覆盖“延迟到转场后才创建序列化快照”。
- 本地 `:app:assembleDebug`：成功，599 个 Gradle task，Kotlin/Android SDK 编译通过。
- 真机覆盖安装与冷启动：成功；本轮日志未发现崩溃、SQLite locked 或与修复相关的 React Native 运行时异常。
- `npm run lint`：仓库当前仍有 114 个错误、14 个警告，主要是原有 React Compiler 规则（例如 render 中的 `Date.now()`、旧 Animated ref 模式等）；本次新增代码的同步 effect setState 问题已清理，但全仓 lint 尚不能作为绿色门禁。

## 第二轮:120Hz 手感的真因与修复(2026-08-15 下午)

用户反馈修复后仍「完全没有 120Hz 该有的流畅度」,以本机另一个 NGA 客户端
(`gov.anzong.androidnga`,release 包)为流畅度基线继续排查。

### 关键测量:送显节奏而不是帧耗时

`dumpsys gfxinfo` 的帧耗时和 janky 比例两边差不多,甚至我们更好;但
`SurfaceFlinger --timestats` 的送显间隔与 latch→present 分布揭示了真差距:

| 指标(主题列表快甩) | anzong | ng2(修复前) |
| --- | --- | --- |
| present2present | 411/415 帧稳定 8ms | 443/447 帧稳定 8ms |
| latch2present | 9~10ms 单峰(队列深度恒定) | 9~10ms 与 17~18ms 双峰(深度在 1↔2 振荡) |
| appBufferStuffingJankyFrames | 339/414(深度恒定,无感) | 298/450(深度振荡,判为卡) |

两边都是满 120fps 送显;区别是我们的缓冲队列深度在 1 和 2 之间来回跳——
内容时间轴每跳一次错位 8.3ms,肉眼看就是「有帧率没手感」的微顿,
且多背 1 帧触摸延迟。

### 机制(profileable release + Perfetto + framestats 逐帧)

- RenderThread 实际渲染 ~1.4ms/帧,GPU ~3.7ms/帧——绝对成本完全够 120Hz;
  每帧 4ms+ 花在 `queueBuffer→waitForever`,即队列常满时的排队。
- 队列被塞满的起点是「迟到帧 + Choreographer 补课双连发」:拖动期 240Hz
  触控采样下滚动位置每帧推进 ~1.9 次;一旦某帧 UI 线程超时(FlashList 挂载
  突发 traversal 最大 7.8ms),下一 vsync 立即补一帧,两帧间隔可短至 1.4ms,
  队列 +1 后在连续滚动中永不排空。
- 对照实验:首页版块列表(行轻、无挂载)同一手势全程 2~5ms/帧、零堆积;
  确认不是 RN 在此设备的通病,而是主题列表行的挂载成本让帧时间方差过大。
- debug dev client 与 release 的双峰形态一致——此问题与 JS dev 模式无关,
  但 release 是用户对比基线的公平口径,验收一律用本地 assembleRelease
  (与 debug 同 keystore,`adb install -r` 覆盖不丢登录态)。

### 修复

`src/ui/topic-row.tsx` 行结构瘦身,把挂载突发压回预算内:

- meta 行 6 视图 → 3:person/chat_bubble 图标改为字形 span 内联进 Text
  (嵌套 Text 不产生独立原生视图),回复数保留独立 Text 防长昵称截断吃数字。
- 标题行首个彩色 span 的样式合并进外层 Text,常见情形整行单文本节点;
  锁定/附件/子版块标记 span 显式重置粗/斜/下划线,不吃标题掩码样式。

### 复测(release 包,主题列表快甩 3 次,SurfaceFlinger timestats)

| 指标 | 修复前 | 修复后 | anzong 基线 |
| --- | --- | --- | --- |
| latch2present | 9~10ms 与 17~18ms 双峰(342/194) | **9~10ms 单峰**(524/527,余 3 帧 14ms) | 9~10ms 单峰(411/415) |
| present2present 8ms 占比 | 540/542 | 526/527 | 411/415 |
| gfxinfo janky(legacy) | 33~81% | 1~11% | 0.8~46% |
| 单甩 total | p50 17.7ms(全程队列满) | p50 9.0ms,max 9.6ms | p50 9ms |
| 单甩 UI 线程 | 尖峰 18.6ms | p90 1.4ms,max 3.5ms | — |

详情页(纯文本帖)同协议:latch2present 单峰 9~10ms(309+297),送显 604/607 帧稳定
8ms——codex 首轮的转场/惰性快照修复已够,FloorCard 未再改动。进帖转场 130 帧
p50 6ms、最长 22ms,不与 220ms 动画重叠。

### 测量陷阱(记档)

- 屏幕闲置变暗后 HyperOS 会把刷新率锁到 60Hz,连带 app 窗口投票失效;此时
  latch2present 全落 17~18ms,看起来像队列回退。判定前必须在滚动中确认
  `dumpsys display` 里有 `frameRateOverride {uid=<app> 120}`。原诊断第 4 节
  「应用始终按 60Hz 输出」的一部分现象即来源于此。
- `gfxinfo` 的 GPU 直方图在队列满时含 swap 排队时间,会把 3~4ms 的真实 GPU
  成本虚标成 14ms+;要看真值用 Perfetto 里 RenderThread 的 queueBuffer/
  waitForever 切片(需要 manifest 里 `<profileable android:shell="true"/>`,
  已做成 `plugins/with-profileable.js` 常驻)。


## 第三轮:动画场景的首入卡顿(2026-08-15 晚)

用户反馈进设置、进版块这类 push 转场"初次进入卡顿明显"。逐场景 framestats 复现:

| 场景 | 修复前最差帧(total/UI) | 修复后 | 手段 |
| --- | --- | --- | --- |
| 进版块(冷,数据到达) | 40.9/34.2 + 39.2/35.3 连续两帧 | 单帧 ~21-28/≤24 | 分帧揭示 initial=0 step=3 |
| 进版块(热) | 34.6/31.6 | ~19-23(大头是转场首帧队列) | 同上 |
| 进设置屏 | 22.5/19.3(热态也 19.3/16.2) | max 13.7,无 >16.7 帧 | SettingsShell 分帧揭示 2+3 |
| 进帖(转场后提交) | 27.8/19.8 单次整页 | 每帧 1 楼;巨型主楼卡仍 ~20ms(见边界) | 楼层分帧 1+1,带楼号进场不分片 |
| 抽屉首开 | (前轮已修)| max 6.8 | — |
| 顶栏菜单 | 瞬时 17~18ms 数帧 | 未处理(优先级低) | — |

机制:push 第 1 帧同步挂载整屏在 120Hz 上必然掉 2~5 个 vsync。新增
`src/ui/progressive.tsx`(useProgressiveReveal / ProgressiveChildren):内容从第一帧
就开始出现,但每帧只挂一小片,横推动画走完前全部就位——不引入骨架等待期。
列表页视口外的切片增长不产生挂载,揭示期间挡住 onEndReached 防止每次进版块
白拉第二页。

### 边界与陷阱

- 版块「数据到达帧」仍有 ~15-24ms:listHeader(版头+chips)+ FlashList 初始化
  必须同帧挂,分片分不掉;要再压需要预挂列表壳或瘦 SubBoardBar,收益存疑。
- 帖子巨型主楼(长 BBCode)单张卡就 ~20ms,分片的下限是单卡成本;发生在转场
  结束后的内容浮现期,不与动画重叠。要再压需要 BBCode 内容级分片。
- 帧级 A/B 的运行间噪声 ~±5ms(列表内容、字形缓存、调度都在变),小于一档
  vsync 的差异别当结论。
- `SurfaceFlinger --timestats` 反复 enable/clear 若干轮后会卡死(dump 0 层),
  disable/enable 也救不回;改用 gfxinfo framestats 的 IntendedVsync 间隔 +
  FrameCompleted 总耗时判定节奏。
- 测量前确认前台焦点是 app(通知栏/锁屏盖住时 gfxinfo 读数是垃圾)。

## 第四轮:复现「快滑时文字向上跳一下/闪烁」(2026-08-15 晚)

方法:screenrecord 120fps 录屏 → ffmpeg 抽帧 → 逐帧相位相关测内容位移,
与 anzong 用完全相同的注入手势(input swipe 120ms 匀速拖拽)做 A/B。

结果(主题列表,4 次甩动):

- ng2:拖拽段稳定 104px/帧的匀速里,出现**单帧 0px 停格 → 数帧后 208px
  双倍补跳**(1 次)和 **48/56px 半步顿挫**(2 次),都在手势中段;
- anzong:除了手势注入起点的固定 artifact(两家都有),中段零异常,全程恒定
  104px/帧。

「向上跳一下」= 停格后的双倍补跳帧;「顿一下」= 停格/半步帧。与 framestats
在拖拽期抓到的 vsync 双连发(帧间隔 1.4ms)/16.6ms 空档完全对应——拖拽期
UI 线程偶发超过 8.3ms 预算,错过一次上屏,Choreographer 补课时内容一次前进
两帧的量。发生率约 1 次/甩动。

注意:第一次录屏误拍了详情页(兜底 tap 打开了主题),详情页真实滚动段全程
平滑——该现象目前只在主题列表复现。70ms 极速注入手势的起步段振荡两家 app
相同,是注入时序 artifact,不能作为依据。

尚未处理,待讨论方向:拖拽期 UI 线程超预算的来源需要 profileable 包在拖拽
中抓 Perfetto 确认(挂载?onScroll JS 往返?);drawDistance 已 1200,再增会
加重进场提交,与第三轮的分帧揭示相互制约。

### 第四轮定位与修复(拖拽期 Dropped Frame)

Perfetto(profileable 包)拖拽中取证:

- UI 线程 doFrame 759 帧仅 2 帧超 8ms,节奏干净;RenderThread 实际绘制 1~3ms。
- 病灶是**行回收重绑的节奏**:FlashList 每隔一帧提交一次 `IntBufferBatchMountItem`
  (2.4~5.1ms),轻重帧交替让 RenderThread 生产时序摆动 ±3ms;慢性满队列
  (Buffer Stuffing ~96% 帧,零时序余量)下,重帧周期性越过 SF latch 边界 →
  SF 丢弃旧帧(Dropped Frame)→ 屏幕先停格再双倍跳。9 个 drop 集中成一簇时,
  等效一段 60Hz 且步进翻倍的闪烁段。
- 判别实验:350ms 温和拖拽仅 1 drop vs 120ms 激进拖拽 9 drop——
  掉帧率 = 重绑抖动幅度 × 满队列零余量,后者是平台行为,前者可压。

修复(本轮):

- `drawDistance` 1200 → 2400:静止/间歇期由 premountViews 预绑,单次拖拽
  基本落在预绑区,拖拽中不再触发重绑。
- TopicRow 右侧「最后回复人+回复数」合并为一个 Text(昵称 clipName JS 截断,
  防省略号吃掉回复数),重绑时每行再少一个视图 diff。

复测(同协议 120ms×3 拖拽):第 1、3 次拖拽零 drop;**连续甩动的中段仍有
一簇 drop**——前一次惯性把预绑缓冲耗尽,补绑批次(最大 5.1ms)撞上拖拽期。
这是当前 RN/FlashList 在 120Hz 上的结构性余量问题:重绑成本下限 ~2-4ms/批,
慢性满队列不可从 app 层解除。要继续压需要上游动作(FlashList 手势期暂停
premount、或 RN 队列深度控制);单次甩动的常见路径已与 anzong 无感知差异。
