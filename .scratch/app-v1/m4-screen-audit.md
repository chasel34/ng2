# M4 逐屏对照审计(27 票第 1 项)

- 日期:2026-08-09
- 基准:`design/project/NGA客户端.dc.html`(31 屏 + Design Token 表)
- 方法:四个只读子代理分片,把设计稿每屏的内联 style 逐元素抽成规格,再与 `src/` 的
  StyleSheet 值逐条比对;偏差由主控统一改。**纯静态比对**,真机截图复核另立清单
  (见 `m4-acceptance.md`)。
- 口径:web-only 属性(`cursor` / `overflow-y` / `transition` 的 web 写法)不算偏差;
  数值差 ≤0.5px 且无 token 档位可对应的不算;文案与功能差异不在本次范围。

## 统计

| | 屏数 | 说明 |
| --- | --- | --- |
| 一致,未改 | 8 | isHome / isFolders / isSearch / isNotify / isAccounts / isFilters / isChain / 弹出菜单 |
| 有偏差,已修 | 14 | 见下表 |
| 不实现(spec §1 排除) | 5 | isEditor ×2 / isMessages / isChat / isNewMsg |
| 有意分歧,记录不改 | 3 项 | 见「有意分歧」节 |

**共修正 41 处偏差**,其中:

- 往 `tokens.ts` 新增档位 7 个(字号 5 + 圆角 2),消灭了 5 处散写的魔法字号
- 全局组件级(一处改、多屏受益)9 处
- 单屏局部 25 处

## 新增 token(全部标注了设计稿出处)

| token | 值 | 出处 |
| --- | --- | --- |
| `typography.articleTitle` | 16.5 · 600 | isArticle 顶栏标题,比别的二级页矮半档 |
| `typography.webTitle` | 15.5 · 600 | isWebview 顶栏主标题(下面还压一行 URL) |
| `typography.signature` | 13.5 · 1.7 | isProfile 签名档正文 |
| `typography.dialogBody` | 13.5 · 1.6 | 对话框正文(比公告条的 1.5 松) |
| `typography.bannerMeta` | 13 | isProfile banner 的 UID 行(不带行高) |
| `radius.xs` | 8 | 页码格 / 签名框 / 版主标签 / 登录提示卡 |
| `radius.pill` | 16 | isSubboards 的 32 高订阅胶囊 |

`radius.sm`(9)原注释写着「分类标签 / 页码格」——Design Token 表那一档本来就是
「radius/sm 8–9」的区间,设计稿两个值都在用。拆成 xs/sm 两档后注释各归各位。
`typography.notice` 的注释里去掉了兼职的「对话框正文」。

## 逐屏结论

| 屏 | 结论 | 修了什么 |
| --- | --- | --- |
| isHome 首页 | **一致** | — |
| isList 主题列表 | 2 处 | 顶栏右侧钮 46→44;标题截断宽度补 150 |
| isArticle 帖子详情 | 6 处 | 顶栏标题 17→16.5(新档);右侧钮 46→44;页码格/跳页格圆角 9→8;楼层「更多」钮 38→34 宽;滑动翻页提示 15·600→14·600;提示盒改为整屏居中(原本盒顶对齐屏幕中线,整体偏低半个盒高) |
| isSimpleList 二级列表(6 屏共用) | 4 处 | 顶栏尾部钮 46→44;作者名 maxWidth 120→118;「我的收藏」补 `time` 切到 16 档标题 + meta 色右槽(原本误用主题列表的 17 档 + link 色);「我的缓存」页码槽 fg2→link |
| isSubboards 子版块 | 4 处 | 行内距/列距 12→14;副标题 12.5→12;订阅钮改成设计稿的 32 高描边胶囊(原本是 surface2 底的小方块);版块名并进顶栏标题,去掉多出来的副标题条 |
| isFolders 收藏夹管理 | **一致** | — |
| isSearch 搜索 | **一致** | — |
| isProfile 用户资料 | 6 处 | 签名正文 15.5/26.04/fg → 13.5/22.95/fg2(新档);banner UID 行去掉继承来的 20.25 行高;基础信息两列网格补 12 列距;卡片说明行去掉继承来的 20.63 行高;签名框与版主标签圆角 9→8;页面底留白 36→24 |
| isNotify 最近被喷 | **一致** | — |
| isLogin 登录 | 2 处 | 顶栏刷新钮 46→44;底部提示卡圆角 9→8 |
| isSettings 设置三屏 | 2 处 | 分组标题去掉 0.4 字间距(那是抽屉分区小标题的);页脚下方多出的 8px 去掉 |
| isAccounts 账号管理 | **一致** | — |
| isFontSize 字号调节 | 6 处 | 分组标题字间距同上;预览卡头像正圆→14 圆角方块;滑杆行下内距 8→4;± 步进钮改成压在轨道下方两端的绝对定位(原本与轨道并排,吃掉两端各 32px 行宽);滑杆区 70→66 高;预览卡头部文字块去掉垂直居中(头像拉到 160% 时用户名会低 13px) |
| isFilters 屏蔽规则 | **一致** | — |
| isError 加载失败 | 1 处 | 状态码那一截拆成独立字段:`describeFetchFailure` 现在返回 `{ headline, code? }`,只有 `code` 走等宽加粗。原本整句(含中文前缀)都被渲染成等宽粗体 |
| isWebview 网页兜底 | 1 处 | 顶栏主标题 17→15.5(新档) |
| isChain 回复链 | **一致** | 仅受全局顶栏钮尺寸修正影响 |
| isViewer 大图查看器 | 1 处 | 图四周补 16 内距(落在页上而不是根容器上,分页位移吃的是 onLayout 的整宽) |
| 抽屉 | 2 处 | 账号区副标题去掉 1.65 行高;底部留白 40→20(`content.paddingBottom` 与 `tail` 叠了两层) |
| 弹出菜单 | **一致** | 顺手补了设计稿声明过的 `overflow-y:auto`(条目多到 520 顶格时能滚) |
| 对话框(7 种变体) | 5 处 | 正文上距 16→9、行高 1.5→1.6(新档);单选行改 48 高 + gap 13 + 图标 22→21;单选副行 12.5→11.5 且去掉 3px 上距;按钮区上距 12→14、右缘 16→22 |
| Snackbar | 1 处 | omup 位移 16→14 |
| 全局顶栏 | 1 处 | 按钮从一刀切 46 拆成 46(返回/菜单)与 44(右侧动作钮)两档,21 个调用点跟着改 |

## 有意分歧(记录,不改)

1. **抽屉入场是横推,不是设计稿的 omup 上浮**。左抽屉横推是 Android 的系统语言,
   也是「左边缘右滑拉出、左滑关掉」那套手势成立的前提——手势拖的就是这个位移。
   遮罩与面板共用一个 progress(设计稿是分开的 .2s / .22s),因为拖动时遮罩必须跟着
   手指一起深浅;差的那 20ms 换手势跟手值。
2. **`save` 图标没有实心变体**。设计稿写的是 `font-variation-settings:'FILL' 1`,
   实现装的是静态版 Material Icons Outlined,没有 FILL 轴。为一个字形多打一份实心
   字体不划算,记为已知差异。
3. **`fontWeight: 650` 在 RN 上落成 600**。设计稿多处用 650(楼层作者名、引用块链
   入口、滑动提示、账号页按钮),RN 的字重只有百位档。全项目统一取 600。

## 未修的细偏差(≤2px,需要位置变体才能表达)

- isProfile 基础信息卡内距:设计稿只有第一张卡是 `16 16 14`,其余 16;实现统一 16,
  底部多 2px。
- isProfile 声望卡说明行下距:设计稿 12、管理权限卡 10;实现两处都是 10,少 2px。

两条都要引入「第几张卡」的位置变体才能表达,收益不抵复杂度,留作已知差异。

## 顺带收敛的两件事

**空态 / 加载态**(27 票第 6 项)。原本 15 个屏各写各的 `styles.empty` / `styles.center`,
图标 36–44、间距 8–16 都有,失败态还普遍把 `error.message` 直接摆到屏幕上
(M3 缺陷 3 那一类)。新增 `src/ui/state-view.tsx`(`EmptyState` / `LoadingState` /
`LoadingFooter`),口径统一为:

- 正在拉 → `LoadingState`(primary 转圈)
- 拉成功但没内容 → `EmptyState`(40 号 meta 图标 + notice 文案 + 可选出路)
- 拉失败 → `LoadFailedNotice`(已有,走 `describeFetchFailure` 的友好文案)

14 个屏改完,顺手消掉了 8 处「把 `java.io.IOException` 摊给用户」的路径。

**动效**(27 票第 2 项)。新增 `src/ui/motion.ts`,把设计稿第 38–40 行那三条 keyframe
(omup / omfade / ompop)的时长、缓动、位移量收成常量:

- `easeStandard = cubic-bezier(.25,.1,.25,1)` —— CSS 的 `ease`。原本全项目只有详情页
  「上次读到」提示条用对了,其余一律 `Easing.out(quad)`(收得更急)
- `duration` 五档:menu 160 / quick 180 / base 200 / panel 220 / notice 280
- `RISE_OFFSET = 14`、`POP_SCALE = 0.94`

套用到:弹出菜单、五个对话框、抽屉、Snackbar、设置开关、FAB 展开与图标旋转、
横滑翻页回弹(它用的是另一条 `easeDecelerate = cubic-bezier(.2,.8,.3,1)`)、
以及 expo-router 的屏幕转场。新增 `src/ui/overlay.tsx` 把对话框的
「遮罩淡入 + 面板弹出」抽成一个 hook——五个对话框原本遮罩都是静态底色,开框瞬间满黑。
