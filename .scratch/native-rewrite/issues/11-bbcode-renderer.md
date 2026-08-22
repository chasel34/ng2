# 11 — BBCode Compose 渲染器(M2)

**What to build:** AST→Compose,ADR-0001 结论沿用、实现重写。文本主体 `AnnotatedString` + `inlineContent`(表情 265 张 assets 随迁,内联= RN ImageSpan 的对应物;沿用 `scripts/fetch-smilies.mjs` 的产物,必要时补 Kotlin 侧生成表)。块级:引用、折叠(collapse)、代码、列表、表格(**定宽 108dp + 整表横滑**,不做内容测量——ADR-0001 降级照抄)、对齐、标题、分隔线、box/album;行内:链接、uid/pid/tid 引用、@提及、lessernuke 三档、防剧透 `[color=white]`(点击/选中可读的行为对照 RN 版);flash=video/audio 渲染媒体卡片外跳;图片/附件占位接票 12 的尺寸记忆表;签名档、贴条、热门回复折叠区的容器组件。
**设计原则(anzong 四条,GPL 思路可抄代码不可抄)**:解析+渲染模型构建(含 AnnotatedString 组装)全部后台一次完成,composition 只消费成品;渲染产物可按页常驻;滚动路径零计算;文字先出图片后到。渲染模型进不可变集合(kotlinx-collections-immutable),稳定性注解齐全。

**Blocked by:** 09

**Status:** in-review

- [x] coverage 29 类型逐一与 RN 版并排截图核对(功能对照,非像素级)——口径改为「demo 屏自检 + 对照 render.tsx 逐条核对」,见 Comments 的 32 行对照表
- [x] 超长楼层(最长 fixture)滚动无肉眼断续(初测;正式闸在票 19)——模拟器甩 12 次无崩溃无卡死,不下性能结论
- [x] 防剧透/折叠/表格横滑交互行为与 RN 版一致——三项逐一自测,防剧透的「点一下翻开」是有意偏离(RN 版实际没有翻开入口),见 Comments

## Comments

### 完成摘要

`native/app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/` 下 14 个文件 + `ui/theme/Tokens.kt`:

- **渲染模型**(纯 Kotlin,JVM 单测直接跑):`RenderModel.kt` 定义段类型,
  `RenderModelBuilder.kt` 把 AST 一次性转成段序列。文字段 = 预组装好的 `AnnotatedString`
  (底样式进字段、差异区间进 span、链接/uid/tid/pid/@/防剧透各钉一条
  `pushStringAnnotation`、表情用 `appendInlineContent` 占位);块段 = 成品数据
  (URL 拼好、列宽算好、嵌套正文也是成品模型)。全部 `ImmutableList` + `@Immutable`。
- **纯函数层**逐文件直译 RN 原件:`Segments.kt` / `Table.kt` / `Album.kt` / `Colors.kt` /
  `PlainText.kt` / `FloorImages.kt`,外加 `ReplyHeader.kt`(票 10 的三个判据,临时落点)。
- **Compose 层**:`BBCodeContent.kt`(段 → 组件、点击分派、防剧透翻开)、`Blocks.kt`
  (引用卡/标题/分割线/列表/折叠卡/lessernuke/表格)、`Media.kt`(骰子/媒体/附件/相册)、
  `FloorContainers.kt`(`SignatureBlock` / `CommentStrip` / `HotRepliesSection`)、
  `BBCodeIcons.kt`(8 个 Canvas 图标)。
- **表情**:`assets/smilies/` 265 张(238 PNG + 27 GIF)复制进
  `native/app/src/main/assets/smilies/`,文件名即 CDN 原名;`scripts/gen-smilies-kt.mjs`
  生成 `Smilies.kt`(7 套系 / 265 条 / 265 随包文件 + 每张的原始像素尺寸)。
- **设计 token**:`ui/theme/Tokens.kt`(颜色 24 档 × 浅/深/纯白三套、正文相关字号档、
  圆角、间距),`Ng2nTheme` 经 `LocalNg2nColors` / `LocalTextScale` 下发。
- **demo 屏**:首页「BBCode 渲染 demo」入口(标了 TODO 票 16 删),三档。

单测:`RenderModelBuilderTest`(29 类型逐种至少一条断言)、`SegmentsTest`、
`TableLayoutTest`、`AlbumTest`、`BBColorsTest`、`FloorImagesTest`、`SmileyResolveTest`。
`./gradlew :app:testDebugUnitTest :app:assembleDebug` 全绿、零警告。

### 表情表的生成方法

**不重抓** `js_bbscode_core.js`,而是从 RN 侧已经落盘的 `src/core/smilies/table.generated.ts`
转换。理由:表情表与随包图片是同一批产物,重抓可能拿到官方新版而与 `assets/smilies/`
里已下载的 265 张对不上——「表里有、包里没有」的那几个会静默退化成远程 URL。
脚本把 TS 的 `import type` 与类型标注削掉后整段求值(剩下全是 JS 字面量),
再顺手从 PNG 的 IHDR / GIF 的逻辑屏描述符读出每张图的原始像素尺寸写进生成表。

尺寸在**生成期**读而不是运行期:RN 侧 `smiley.tsx` 拿的是打包期写进 bundle 的元数据,
理由是「一楼里同一个表情可能出现几十次、列表回收后还要再解一遍」。Android 侧对应的
`BitmapFactory(inJustDecodeBounds)` 是 `android.*` 调用,而建模器必须是纯 Kotlin,
且要为 265 张各开一次 assets 流。写死两个整数,运行期零开销。

### 29 类型对照表(与 `src/ui/bbcode/render.tsx` 逐条核对,功能对照非像素级)

| # | 类型 | RN 版行为(render.tsx / blocks.tsx / media.tsx) | 原生实现 | 结论 |
|---|---|---|---|---|
| 1 | text | 直接进 `<Text>` | 追加进 `AnnotatedString` | ✅ 一致 |
| 2 | linebreak | `'\n'` | `append('\n')` | ✅ 一致 |
| 3 | bold | `fontWeight:'700'` | `SpanStyle(FontWeight.Bold)` | ✅ 一致 |
| 4 | italic | `fontStyle:'italic'` | `SpanStyle(FontStyle.Italic)` | ✅ 一致 |
| 5 | underline | `textDecorationLine:'underline'` | `TextDecoration.Underline` | ✅ 一致 |
| 6 | strike | `line-through` | `TextDecoration.LineThrough` | ✅ 一致 |
| 7 | color | `resolveBBColor` 认得出才进 style | 同,认不出不进 span | ✅ 一致(色名→十六进制表,见「有意偏离」) |
| 8 | size | `bodyFontSize × scale`,行高同步放大 | 字号同;**行高不放大** | ⚠ 见「有意偏离」 |
| 9 | font | 只渲染内容,忽略字体名 | 同 | ✅ 一致 |
| 10 | code | monospace + `fg2` | 同 | ✅ 一致 |
| 11 | link | link 色 + 下划线,点了 `Linking.openURL`;无内容显示 href | 同,点击走 `onOpenLink` 回调 | ✅ 一致 |
| 12 | userRef | link 样式;无内容显示 uid | 同 + `USER` annotation | ✅ 一致(RN 那边是死入口,这边接了回调) |
| 13 | topicRef | link 样式;无内容显示 `#tid` | 同 + `TOPIC` annotation | ✅ 一致 |
| 14 | floorRef | link 样式;无内容显示 `#pid` | 同 + `FLOOR` annotation(args 原样带走) | ✅ 一致 |
| 15 | mention | link 样式 `@名字` | 同 + `MENTION` annotation | ✅ 一致 |
| 16 | smiley | RN `Image` 内联进 `<Text>`(=ImageSpan),高度取设置,宽按原始比例 | `appendInlineContent` + Coil 读 `file:///android_asset/`,同一口径 | ✅ 一致 |
| 17 | quote | 引用卡:radius 12 / quote 底 / 左 3 track 竖条 / 11·13 内距 / 内容降一档字号与 fg2;认得出 `[pid]` 才画「查看对话链」 | 同(竖条用 `drawBehind` 画,省一个节点) | ✅ 一致 |
| 18 | image | `attachmentUrl` 拼地址 + 缩略图变体,`marginTop:11` 摞在图上 | 同,交票 12 的 `PostImage` | ✅ 一致 |
| 19 | divider | `marginVertical:12` + 1px divider 色 | 同 | ✅ 一致 |
| 20 | heading | `marginTop:11` + 底边 1px divider + section 字号加粗 | 同 | ✅ 一致 |
| 21 | align | 容器 `alignItems` + 文字 `textAlign` 两处都给 | 同(容器 `horizontalAlignment` + 段的 `textAlign`) | ✅ 一致 |
| 22 | collapse | 折叠卡,默认收起,标题缺省「折叠的内容」,动作文案「点击展开/收起」 | 同 + 展开收起带 200ms 动画 | ⚠ 动画是新增,见「有意偏离」 |
| 23 | list | `·` 或 `n.` 标记宽 18 右对齐 + 内容 flex | 同(标记宽 18) | ✅ 一致 |
| 24 | table | 定宽 108dp、colspan 拉通、rowspan 忽略、短行补格、整表横滑 | 同;行内 `IntrinsicSize.Min` 让一行等高 | ✅ 一致 |
| 25 | box | lessernuke → danger 描边折叠卡 + 三句官方提示语;hip/item → surface2 普通块 | 同 | ✅ 一致 |
| 26 | dice | `ROLL 表达式 = 展开 = 合计`;无点数退回 `[骰子 …]` | 同 | ✅ 一致 |
| 27 | flash | 媒体卡「视频/音频/动画」+ 文件名 + 外跳角标 | 同 | ✅ 一致 |
| 28 | attach | 下载图标 + 文件名,点了外跳 | 同 | ✅ 一致 |
| 29 | album | 收起成「相册 · 共 N 张图片」,展开竖排 | 同 | ✅ 一致 |
| + | `[color=white]` 防剧透 | 白字照画,RN 没有翻开入口(`<Text>` 也不可选中) | 白字照画 + **点一下翻开/盖回** | ⚠ 见「有意偏离」 |
| + | `Reply to` 回复头 | 与 `[quote]` 画同一张卡片 | 同 | ✅ 一致 |
| + | 行内标签裹块级 | 升格成块,外层文字样式往下带 | 同(样式在建模期就压进内层段) | ✅ 一致 |

### 关键决定

1. **建模与渲染分家**(anzong 四原则的落点)。RN 是「渲染时递归 AST」,这里是
   「后台递归一次建成品,composition 只贴」。引用块/折叠块/表格单元格里的正文也是
   **嵌套的成品模型**,渲染期不再递归 AST。滚动路径上没有解析、没有字符串拼接、
   没有 URL 计算。
2. **骰子按文档顺序取点数,不按节点身份查表**。RN 用 `Map<DiceNode, DiceOutcome>` 靠
   JS 的对象身份;Kotlin 的 data class 是结构相等,同一楼里两个写法相同的
   `[dice]d100[/dice]` 会撞成同一个 key——而「写法一样、点数不同」正是要区分的场景。
   改成 `ImmutableList<DiceOutcome>` 按序取第 n 个,与 NGA「一楼内所有 [dice] 共用
   一条随机流按文档顺序推进」本来就是一回事。
3. **`containsBlock` 不做记忆化**。RN 挂了 `WeakMap`,因为它的渲染层会把同一棵子树
   反复交回 `BBCodeBody` 再切一次段。这边一次性建模,每棵子树只走一遍,记忆表没有
   命中的机会。RN 那条「同一棵子树重复问只遍历一次」的回归用例因此**不移植**——
   它锁的是这边不存在的行为。
4. **表格横滑与父级翻页的让路**留成 `LocalHorizontalDragGuard` 接口(默认 no-op),
   票 13 接主题详情屏时 provide 真实现。手指一 down 就 `begin()`、up/cancel 就
   `end()`,不用 `detectDragGestures`——那要等「确实横着拖了」才触发,而祖先在捕获
   阶段早把手势收走了。
5. **图标手画**(`BBCodeIcons.kt`,8 个),沿用票 12 `ViewerIcons.kt` 立下的做法,
   不引图标字体。完整图标体系归票 17。
6. **`coil-gif` 3.5.0 进 toml**(与 coil 同版本 stable):随包 265 张里有 27 张 GIF
   (默认套整套都是),Coil 核心只解静态图,不挂 `AnimatedImageDecoder` 那 27 个表情
   在正文里就是一帧不动的静态图。
7. `core/api/AttachmentUrls` 追加 `attachmentUrl` / `normalizeAttachBase` /
   `rehostLegacyAttachment`,沿用票 12 立下的「接口先行 + 最小实现 + TODO(票 10)」。
   `attachmentUrl` 多一个收散装字段的重载:`[album]` 的内容是一整串裸地址不是 AST 节点,
   而 `AttachmentRef` 是密封接口,包外造不出实例。

### 对 RN 版的有意偏离

1. **`[size=150%]` 只放大字号,不放大行高**。RN 同时给 `fontSize` 与 `lineHeight`;
   Compose 的行高是**段落属性**(`ParagraphStyle`),没法只作用在一段 span 上。
   行高跟着整段走,所以密排行里的大字会比 RN 挤一点。要完全一致得把 `[size]` 升格成
   独立段落,那会改变换行行为(RN 版里大字与前后文是同一行流),得不偿失。
2. **防剧透 `[color=white]` 加了「点一下翻开」**。RN 版把白字照画,注释写着「故意让人
   选中才看得见」,但全仓 `<Text>` 没有一处设 `selectable`(只有 `icon.tsx` 设了
   `selectable={false}`)——也就是说 **RN 版实际上没有任何翻开入口**,只能靠系统的
   文本选择(Android RN Text 默认不可选)。票面要求「点击或选中可读」,这里落成点击:
   白字**照画**(视觉与 RN 逐像素相同),另钉一条 `SPOILER` annotation,点一下把那一段
   叠一层正文色盖过去,再点盖回来。模拟器实测通过(截图见下)。
3. **折叠块展开/收起加了 200ms 动画**(设计稿 `duration.base`)。RN 版是
   `open && <View>` 直接切,没有动画。票面明写「折叠 collapse 展开/收起带动画」。
   用 Compose 自己的 `AnimatedVisibility`,不涉及 CLAUDE.md 里那条「RN Animated
   预采样」的坑。
4. **颜色名落成十六进制表**。RN 把 `'skyblue'` 这种关键字直接交给平台解析,
   Compose 的 `Color` 没有 CSS 关键字表。名字集合与 RN 版逐字相同(24 官方色 + 17 常见色),
   认得出的与认不出的完全一致,只是这边多知道具体色值(取 CSS Color Module Level 4,
   与平台解析的是同一份标准表)。
5. **`AttachCard` / `MediaCard` 的外跳走回调**而不是直接 `Linking.openURL`:
   渲染器不该认识 `Intent`。demo 屏用 `LocalUriHandler` 接上。

### 验收自测

① **29 类型逐一核对**:上表 32 行(29 类型 + 3 条渲染器特有分支)。票面原案「与 RN 版
并排截图」不可行(RN 版 `com.chasel.ng2` 已登录,但找一个同时含 29 种标签的真帖不现实),
按主控指示改为「原生 demo 屏截图逐类型自检 + 对照 `render.tsx` 渲染规则逐条核对」。
模拟器截图覆盖:行内 16 种、quote/image/divider/heading/align/collapse/list/table/box/
dice/flash/attach/album 全部块级、防剧透、回复头、行内裹块级、宽表格。
深色一档另测一屏(`cmd uimode night yes`),配色正确、测完已 `night auto` 还原。

② **超长楼层滚动初测**:demo「超长楼层」档 = `coverage-all-joined` ×30(420 段)
+ `deep-nesting-5000`。`adb shell input swipe` 上下各甩 8/4 次,**无崩溃、无卡死**,
进程存活(`pidof` 有值)、crash buffer 空。**不下性能结论**(模拟器 + debug 包,
spec §五)。两条观察留给票 13/19:
   - 首次进「超长楼层」档有约 4–15s 的「建模中…」。JVM 侧同一份数据 parse 3ms +
     build 5.8ms(一次性探针,跑完删),所以这几秒**不是建模**,而是 debug 包首次走通
     整条渲染路径的类加载/解释执行 + 420 段一次性 composition。
   - **420 段在一个 LazyColumn item 里一次性组合**。RN 侧对此有 `progressive.tsx`
     段级分帧(阈值 8 段、每帧 4 段)。原生要不要同样的分帧归**票 13**决定——
     模型这一层已经把「段」切好了,分帧只是取前 n 段的事。

③ **交互行为**:
   - 防剧透:点白字 → `42` 显形(截图对拍前后两张);再点盖回。
   - 折叠:点「相册 · 共 2 张图片」→ 展开、文案变「收起」,两张图按正文图样式竖排
     (demo 用的是假地址,所以显示票 12 的「图片加载失败」占位——占位本身就说明
     `PostImage` 接线通)。
   - 表格横滑:6 列宽表在 390dp 宽屏上右侧截断,`input swipe` 横拖后露出「丙丁戊 / 3 4 5」,
     短行补的空格子也在。父级翻页的让路接口 demo 屏里用不上(没有 Pager),留给票 13。

### 未完成 / 待所有者

- 无待所有者事项(不需要登录、不需要真机)。
- 票面「与 RN 版并排截图」按主控指示改口径,见上。

### 发现的票外问题

1. **票 13 的分帧决策**:见验收 ② 第二条。RN 侧 `progressive.tsx` 的 `SEGMENT_REVEAL_MIN=8`
   / `INITIAL=4` / `STEP=4` 那组参数有真机出处(2026-08-21「超长楼层单帧挂 100ms+」),
   原生要不要照搬得在票 13/19 上量。
2. **`ui/theme/Theme.kt` 里两个 Material3 scheme 的私有名让了位**
   (`LightColors`/`DarkColors` → `M3LightScheme`/`M3DarkScheme`),因为设计 token 表
   要占这两个名字。行为不变,只是改名。
3. **票 10 的三块地盘被临时借用**:`ui/bbcode/ReplyHeader.kt`(`isReplyHeaderNode` /
   `quoteRefOf` / `replyHeaderRefOf`)、`RenderModel.kt` 里的 `DiceOutcome`/`DiceTerm`/
   `formatDiceTerms`、`core/api/AttachmentUrls` 的三个新方法。三处都标了 `TODO(票 10)`
   并写明「删掉改 import 那一份」。票 10 落地时会撞上,主控注意排期。
4. **`ui/bbcode/FloorImages.kt` 里的 `FloorAttachment`** 是票 13 楼层数据模型的临时替身
   (只要「地址 + 是不是图」两项),标了 `TODO(票 13)`。
