# 44 — P2:看图屏整屏纯黑,RN 用的是主题底 + 主题顶栏

**Status:** open

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
