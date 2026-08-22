# 12 — 图片管线与查看器(M2)

**What to build:** Coil 3 挂**同一个 OkHttpClient**(coil-network-okhttp;帖内图自动带 Cookie/UA,附件域名要登录态的场景才不豆腐)。策略照抄 RN 版:正文图/头像 memory+disk、查看器场景 disk 优先;`imageQuality` 三档(original/smart/thumbnail,默认 smart:Wi-Fi 原图蜂窝缩略)+ `wifiOnlyImages`(默认 true);网络计费状态单例订阅。**图片尺寸记忆表**(服务端不给像素尺寸):内存 512 条 + 磁盘持久化(1s 防抖),防 4:3 占位→真实比例跳动;超高图纵横比 0.6 封顶(护列表行高估算)。查看器:双指缩放、双击 2.5×、翻页(同一 Pan 按缩放拆「拖页/拖图钳边界」两路)、边界回弹、保存到相册 `NGA`(URL 稳定推文件名防堆积)、系统分享、复制地址、查看原图(按 index 覆盖)、浏览器打开、批量下载;transparentModal+fade 的呈现语义。**白捡改进**:`onTrimMemory` 清 Coil 内存缓存(RN 版没做,19MB/350MB 回收率是证据)。

**Blocked by:** 01

**Status:** in-review

- [x] 与 RN 版同帖对照:占位/缩略/原图切换、查看器手势语义一致
- [x] 尺寸记忆表命中时首帧即正确比例(录屏验证无跳动)
- [x] onTrimMemory 实测回收量记录进 Comments

## Comments

### 完成摘要(2026-08-22)

落地文件(全部在 `native/app/src/main/kotlin/com/chasel/ng2n/`):

| 层 | 文件 | 内容 |
|---|---|---|
| core/local | `ImagePolicy.kt` | `ImageQuality`/`ImageSettings`(默认 wifiOnly=true、smart)、`ImagePolicy.resolve`(正文图)与 `resolveViewer`(查看器)、`ImageSettingsSource`/`MeteredNetworkSource` 两个读口 |
| core/local | `ImageSizeCache.kt` | 内存 512 条(插入序淘汰)+ 1s 批窗口落盘;`sizeOf` 同步读、`revision` StateFlow;`CONTENT_IMAGE_MIN_ASPECT=0.6`/`INITIAL_IMAGE_ASPECT=4:3`/`SMALL_IMAGE_WIDTH=200`/`isLongImage` |
| core/api | `AttachmentUrls.kt` | 接口 + 最小实现(`thumbnailUrl`/`stripThumbnailSuffix`/`imageFileName`/`imageMimeType`),**TODO 票 10** |
| data | `MeteredNetworkMonitor.kt` | ConnectivityManager 默认网络回调 → `StateFlow<Boolean>` 单例 |
| data | `FileImageSizeStore.kt` | `filesDir/image-sizes.v1.json`,临时文件 + rename |
| data | `InMemoryImageSettingsSource.kt` | 内存默认值,**TODO 票 14/17** |
| data | `ImageSaver.kt` | MediaStore 写 `Pictures/NGA`、按文件名去重、系统分享(FileProvider)、批量下载 |
| di | `NetworkModule.kt` | `@Singleton OkHttpClient` **占位**,**TODO 票 06** 只换函数体 |
| di | `ImageModule.kt` | Coil `ImageLoader`(挂同一 OkHttpClient、内存 25% 堆、磁盘 256MB、crossfade 120ms)、`ImageSizeCache`、各绑定 |
| ui/image | `PostImage.kt` | 正文图:4:3 占位 → 命中即正确比例、封顶 + 长图角标、折叠态「点击显示」、加载失败态 |
| ui/image | `ImageZoom.kt` | `ZoomState` + `detectViewerTransform`(同一 Pan 按缩放拆两路) |
| ui/image | `ImageViewerScreen.kt` | Nav3 key(URL 列表 + index + 缩略图列表)、HorizontalPager、顶栏五钮、菜单五条 |
| ui/image | `ImagePipeline.kt` / `ViewerIcons.kt` | 叶子组件的依赖门面(Hilt EntryPoint 兜底);四个 Canvas 画的图标 |
| — | `Ng2nApplication.kt` | `SingletonImageLoader.Factory` + `onTrimMemory` 分档清理 + 记忆表异步预热 |
| — | `Ng2nApp.kt` | 查看器 Nav3 条目(fade 220ms 进出 + predictive pop)、**TODO 票 16 移除**的 demo 入口 |

单测 44 条(`ImagePolicyTest` 14 / `AttachmentUrlsTest` 14 / `ImageSizeCacheTest` 10 / `LongImageTest` 6),
`./gradlew :app:testDebugUnitTest :app:assembleDebug` 全绿,零 `@Ignore`。
`libs.versions.toml` / `build.gradle.kts` **一行未改**(coil-compose、coil-network-okhttp 骨架期已在表里)。

### 验收证据

**① 手势语义(Pixel_8 AVD,demo 入口,uiautomator + 像素级 bbox 测量,不靠肉眼)**

| 项 | RN 侧常量(`src/ui/image-gallery.tsx`) | 原生实测 |
|---|---|---|
| 双击倍率 | `DOUBLE_TAP_SCALE = 2.5` | 图片高 664px → 1660px,**比值 2.5000** |
| 放大态拖动 = 拖图 | `if (scale > ZOOM_EPSILON)` 分流 | 放大后连滑两次,页码恒 `1 / 3`;还原后再滑 → `2 / 3` |
| 原始尺寸拖动 = 拖页 | 同上另一支 | 左滑 `1/3→2/3→3/3`,右滑 `3/3→2/3→1/3` |
| 边界阻尼 | `EDGE_RESISTANCE = 0.55` | 纵向边界为 0 时拖 400px,实测位移 **220px = 0.55×400** |
| 回弹时长 | `BOUNCE_MS = 220` | 松手后 300ms 采样已完全归位(下沿 1809→2029) |
| 页边 | 第一页继续右拖 | 连拖两次仍 `1 / 3` |
| 邻页挂载 | `Math.abs(i-index) <= 1` | `beyondViewportPageCount = 1` |

菜单五条(保存到相册/复制图片地址/查看原图/在浏览器中打开/下载全部(3 张))uiautomator 逐条可见;
顶栏保存 → `content query media/external/images` 出现 `Pictures/NGA/800.jpg`,**再点一次不产生第二份**(G8);
缓存中转文件落在 `cache/ng2n-images/800.jpg`。
计费网络切换实测:`svc wifi disable` → 三张图全部折叠成「移动网络 · 点击显示图片」(42dp 条),
点第一条 → 就地展开且**首帧就是 3:2**,其余两条仍折叠;`svc wifi enable` → 全部恢复。

RN 版(`com.chasel.ng2`,已登录,网事杂谈某帖)同口径跑了一轮:双击进/出、放大态拖动会移动图片、
双击还原后与初始帧**逐像素相同**(mean|diff| = 0.00)——语义一致。**但 RN 侧拿不到可比的数值**:
它的查看器底色是主题米色而不是黑,阈值分割依赖图片内容;且那一帧是竖图,2.5× 后画面在两个方向
都溢出量测窗口,倍率与阻尼都量不出来。所以数值对拍是「原生实测 vs RN 源码常量」,不是双端实测。
要双端实测数值,得在 RN 版里找一张横图的楼层——**留给票 18 功能验收**顺手做。

**② 尺寸记忆表命中 → 首帧即正确比例**

launch 后 200ms 一张连拍 18 张,像素级量第一张图框的上下沿:

- 冷启(`pm clear`,表是空的):`#4 h=747`(4:3 占位)→ `#5 起 h=664`(真实 3:2)——**跳动可见**;
- 热启(表已落盘):**`#4` 起恒 `h=664`,全程没出现过 747** —— 命中即正确,无跳动。

`run-as … cat files/image-sizes.v1.json` 三条记录齐全(1121×747 / 800×1200 / 600×2400)。
`adb shell screenrecord` 在这台 AVD 上不可用(`--time-limit 8` 只出 4.1s / 14 帧,重启 app 之后不再更新画面,
逐帧全是启动图),所以改用上面的连拍——采样密度 200ms、读的是真实像素,比录屏更硬。

**③ onTrimMemory**

`adb shell am send-trim-memory com.chasel.ng2.n RUNNING_CRITICAL`:

```
Ng2nApplication: onTrimMemory(level=15) coil memoryCache 5635KB -> 0KB
dumpsys meminfo   Java Heap 13424KB -> 12832KB   TOTAL PSS 101814 -> 104653
```

机制通(Coil 内存池 5.6MB → 0)。PSS 反而涨了 2.8MB —— 模拟器上 dumpsys 的噪声,
按票面口径**数值不做裁决**,真数字留给票 19 真机。

### 关键决定(票里留白的「实现时定」)

1. **记忆表持久化用裸 JSON 文件,不用 DataStore**:这份数据是纯缓存(丢了只是下次启动第一遍图跳一次),
   不需要 DataStore 的事务保证;DataStore 装配归票 14,票 12 用它就得抢着做一半接线,合并时打架。
   无迁移机制,与 RN 侧同一条明文策略:换结构就换文件名(`v1` 在名字里)。
2. **`AttachmentUrls` 接口 + 最小实现**(票面二选一,选了「两个都做」):图片侧只依赖接口;
   实现体逐行照抄 TS 原件并配了 14 条单测,其中 djb2 兜底哈希的期望值是**拿 node 跑 TS 那段算出来的**
   (两端不一致就会「同一张图推出两个文件名」,重复保存检测直接失效)。票 10 落地后换实现、删实现体、留接口。
3. **图标手画(Canvas)不引资源**:只要四个;RN 侧那套「Material Icons OTF 打进包 + `<Text>` 渲染 +
   根布局等字体加载完才放行首屏」是 RN 特有的解法,没有理由继承。完整图标体系归票 17。
4. **叶子组件的依赖走 `rememberImagePipeline()`**(CompositionLocal + Hilt EntryPoint 兜底),
   不强制屏级 provide:票 11 的渲染器会把 `PostImage` 塞进任意层级,少一条「忘了 provide 就崩」的路。

### 对 RN 版的有意偏离

1. **计费判定改用 `NET_CAPABILITY_NOT_METERED`**。RN 用 expo-network 只把 `type === CELLULAR` 算计费,
   于是「手机热点 / 用户手动标为按流量计费的 Wi-Fi」被当成不限流,该省流量时照拉原图。
   原生这边用系统自己的计费判定。拿不到时(无活动网络)仍按**不限流**走,与 RN 同一条兜底。
2. **翻页交给 `HorizontalPager`**,不再自己算位移阈值(0.35 页宽)/速度阈值(600)/页边回弹。
   `research/inventory.md` §8 原话:「RN 的收尾弹簧 stiffness 500/damping 48 是对拍原生 ViewPager
   逐帧调出来的,Kotlin 用 Pager 免费获得」。**图自己的边界回弹(0.55 / 220ms)仍是手写**,Pager 给不了。
3. **捏合时跟手平移**。RN 的 `Gesture.Pan().maxPointers(1)` 让两指移动只能通过 pinch 的焦点跟随间接推图;
   原生这边双指手势同时吃缩放与平移(系统相册的手感)。单指路径与 RN 完全一致。
4. **保存到相册不再要运行时权限**。RN 走 expo-media-library,`Asset.create` 统一按授权把关,每次保存先弹框;
   minSdk 31 往 MediaStore 写自己创建的图片不需要任何运行时权限,整条权限路径删掉——少一个弹窗。
   `MediaPermissionError` 这个类型也随之取消。
5. **transparentModal 语义**:Nav3 只渲染栈顶条目,做不到「下面那屏透出来」。查看器本身在 RN 里
   根节点也是不透明底色(`backgroundColor: theme.colors.bg`),真正靠 transparentModal 拿到的只是 fade。
   所以这里落成 fade 进/出 220ms(+ predictive pop),底色黑。真要「透出下层」等票 13/17 定场景策略。
6. **长图渐隐用真渐变**。RN 没装渐变库,拿 12 层 View 手搓(`content-image.tsx:24-32`);Compose 有
   `Brush.verticalGradient`,一层搞定。
7. **`onTrimMemory` 是新增的**(票面「白捡改进」),RN 版没有。Coil 自己也挂了一份策略同构的
   ComponentCallbacks2,这里显式再写一遍是为了「这条策略是我们的决定、可改可测」。

### 修掉的 / 撞上的坑

1. **`ACCESS_NETWORK_STATE` 漏在 manifest**:骨架期没人碰网络,票 12 首次装包直接崩在 Application 里
   (`ConnectivityService.enforceAccessPermission` → SecurityException)。RN 版由 expo-network 自动合并,
   原生要自己写。已补,并把 `MeteredNetworkMonitor` 的 init 整块 runCatching 住——
   「查一下是不是流量」不该有能力把 app 拦在启动那一步。
2. **回弹动画串行**:`Animatable.animateTo` 是挂起函数,顺着写就成了「缩放 220ms 跑完才轮到位移」,
   总时长三倍、观感是两段动作。模拟器实测抓到:松手 450ms 后纵向回弹只走了 44%(下沿 1906 而不是 2029)。
   改成 `coroutineScope { launch ×3 }` 后 300ms 采样已完全归位。RN 侧三个 `withTiming` 天然并行,
   这是 Kotlin 侧独有的坑,注释留在 `ImageZoom.animateTogether` 上。
3. **`Arrangement.Center` + `verticalScroll`**:内容比视口高时,溢出的上半截会被顶到滚动范围之外、够不着。
   票 01 的首屏只有两行字所以没暴露,加了 demo 就中招。改 `Arrangement.Top`。

### 未完成 / 待其它票

- 设置持久化(票 14 DataStore)与设置屏(票 17):现在是 `InMemoryImageSettingsSource` 内存默认值,可读不可存。
- `OkHttpClient`(票 06):`NetworkModule` 里是裸 client 占位,只需换函数体。
- `attachments` 全族与 44 条金样本(票 10):本票只实现了用到的四个纯函数 + 14 条自用回归。
- 头像:票面提到「正文图/头像 memory+disk」,头像组件本身没有落点(用户信息行归票 13),
  策略已经在 `ImageLoader` 上,票 13 直接用 `AsyncImage` 即可。
- demo 入口(`Ng2nApp.ImageDemoSection` + `IMAGE_DEMO_BUTTON_TAG`)**票 16 铺真首页时删掉**。
- 双端**数值**对拍(见验收①末段):需要 RN 版里一张横图楼层,建议并进票 18。

### 发现的票外问题

1. `native/README.md` 的分层表里没有 `ui/image` 这一级,后续票如果继续按功能建子包,README 可以顺手补一句。
2. 骨架的 `SkeletonInstrumentationTest` / `SKELETON_READY_TAG` 现在挂在同一个 Column 上,
   票 16 换首屏时要注意别把票 19 的首帧锚点一起删了。
3. 这台 Pixel_8 AVD 的 `screenrecord` 不可靠(见验收②),票 18/19 若要在模拟器上录屏取证,
   得先确认它能出完整文件,或者改用连拍。

### 需要所有者介入

无。登录态是现成的(模拟器上 RN 版 `com.chasel.ng2` 已登录,只用来做行为对照,没动它的数据)。
真机性能仍归票 19。
