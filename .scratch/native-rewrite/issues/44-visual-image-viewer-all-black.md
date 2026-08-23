# 44 — P2:看图屏整屏纯黑,RN 用的是主题底 + 主题顶栏

**Status:** resolved

**Severity:** P2(整屏配色与基准完全不同)

## 现象

看图屏在两边是两个长相:

| | Expo(基准) | 原生(实际) |
|---|---|---|
| 页面底 | 主题底,浅色档奶油 `#fcf4e1` | **纯黑** |
| 顶栏 | 主题青 `#14796b` + 白色图标 | **黑**(与页面底连成一片) |
| 状态栏图标 | 白 | 白 |
| 图片显示宽 | 占屏宽 ≈ **84%** | 占屏宽 ≈ **73%**,明显更小 |
| 保存图标 | 软盘 | 下载箭头(归票 40) |

**RN 源码 HEAD 站在 Expo 这一边**:`src/app/image-viewer.tsx:225` 是
`backgroundColor: theme.colors.bg`、`:233` 是 `color: theme.colors.onTopbar` ——
没有「纯黑看图态」这个设计。

票 32 的 Comments 里写过一句「`BackIcon` 在 `ui/image/ImageViewerScreen.kt` 还在用
(那一屏是纯黑看图态、故意不吃主题色),没动它」—— **这句判断与 RN 源码不符**,
纯黑不是有意为之的对齐,是偏离。

## 对照图

[`../acceptance/visual/20-image-viewer.png`](../acceptance/visual/20-image-viewer.png)

## 期望

页面底走 `LocalNg2nColors.current.bg`,顶栏走全 app 那套 `TopBar` + `TopBarButton`
(`topbar` / `onTopbar`),跟着主题风格与夜间档变。
顺带核一下初始 fit 比例:同一张图在原生里显示得比 Expo 小约 11 个百分点。

## 疑似代码位置

`ui/image/ImageViewerScreen.kt`(底色与顶栏)、`ui/image/ImageZoom.kt`(初始缩放/fit)。

## Comments

**完成摘要**

- `ui/image/ImageViewerScreen.kt`:根从 `Box(fillMaxSize).background(Color.Black)` 换成
  `ViewerRoot` = `Column(fillMaxSize).background(LocalNg2nColors.current.bg)`,顶栏进流里
  (RN 侧 `image-viewer.tsx` 的 `root` 就是竖排:顶栏把可视区往下压一截,不是浮在图上的遮罩)。
- 顶栏壳换成全 app 那套 `ui/common/TopBar`(底色 `topbar`、高 54、自己撑状态栏安全区),
  原来是本屏私有的一份「52 高 + `windowInsetsPadding` + `align(TopCenter)` 浮着」。
  `paddingHorizontal = 4.dp`,与 RN 的 `paddingHorizontal={4}` 同。
- 图标/计数文字全部 `Color.White` → `colors.onTopbar`;计数补上 RN 有而这边漏了的
  `letterSpacing = 0.5.sp`。空态那一屏的文案换 `Typo.notice` + `colors.fg2`,
  标题用 `TopBarTitle(variant = SUB)`(RN 是 `<TopBarTitle variant="sub">图片</TopBarTitle>`)。
- 加载转圈 `Color.White` → `colors.primary`(RN 侧 `image-gallery.tsx` 传的 `spinnerColor`
  就是 `theme.colors.primary`)。
- 顶栏进流之后,pager 的 `Modifier.fillMaxSize()` 必须换成 `weight(1f).fillMaxWidth()`:
  Column 给非加权子项的竖向约束是无界的,`fillMaxSize` 在那里量不出高度。

**关键决定(初始 fit 比例,票里让「顺带核一下」)**

- 票里那条「原生显示宽 73% vs Expo 84%」**是量法造成的**:这张图左右自带黑色 letterbox 边,
  原生底也是纯黑,黑边与背景连成一片,按「非背景色」量出来的只有图中间那块。
  逐像素复量对照图 `20-image-viewer.png`:Expo 整张图 39–500(462px)、其中黑边 39–85 与 455–500,
  可见内容 85–455 = **370px**;原生可见内容 622–1029 = **407px**。两边可见内容的宽高比一致
  (0.966 / 0.973),原生其实**大约 10%**,不是小 11%。底色一改成奶油,这条自然对上。
- 剩下的真差异是内距:RN 的 `styles.page`(`flex:1` + `padding:16`)被**同时**用在了分页容器
  和 `GalleryPage` 的根上(`image-gallery.tsx:248` 与 `:283`),等于四周 16 加了两遍 = 32;
  原生这边是一遍 16。RN 那份注释写的是「设计稿 isViewer 给图四周留 16 白边」,
  所以 **16 才是设计意图,32 是 RN 侧的意外**,本次按 16 保留 → 原生图会比 Expo 宽约 7.5%
  (对照图口径:512 vs 476 半缩放 px)。**这一条留给主控裁:**要像素级贴基准就把
  `ViewerPage` 的 `padding(16.dp)` 改成 32.dp,一行的事。

**未完成 / 待所有者**

- 实拍复验没做(本次要求不开模拟器)。
- 保存图标仍是下载箭头(应为软盘),按票面归**票 40**,没动。

**发现的票外问题**

- 空态用的是一行居中文案而不是 `ui/common/StateView.kt` 的 `EmptyState`:
  RN 是 `<EmptyState icon="image" …>`,而 `Ng2nIcon` 里没有 `IMAGE` 这一档,
  凑一个别的图标反而新增一处字形偏离(票 40 的地界),故维持文案版。
