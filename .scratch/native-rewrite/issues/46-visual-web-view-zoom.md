# 46 — P2:网页版 WebView 内容比 Expo 放大约 30%

**Status:** open

**Severity:** P2(单屏信息量明显少一截)

## 现象

内嵌「网页版」屏里,同一个 NGA 移动页在原生下整体放大约 30%:

| 元素 | Expo | 原生 | 比 |
|---|---|---|---|
| 左上 `NGA` 角标块宽 | 68px(半缩放) | 90px | 1.32 |
| 版块名 chip 宽(均为 5 字) | 156px | 172px | 1.10(单字 31px → 34px) |
| 页码钮高 | ≈ 56px | ≈ 76px | 1.36 |

顶栏底色、两行式标题(标题 + 小字 URL)、右上 ⋮、底部「用 APP 阅读这一页」青色胶囊钮的
宽度与位置 —— 这些 app 自己画的部分两边一致,**放大的只有 WebView 里的内容**,
所以是 WebView 的 `initialScale` / `settings.textZoom` / `useWideViewPort` 配置差异,
不是布局问题。

## 对照图

[`../acceptance/visual/21-web.png`](../acceptance/visual/21-web.png)

注:两边打开的是不同 tid(Expo 47422692 / 原生 47406116),但比对的是页面框架元素
(NGA 角标、版块 chip、页码钮),与内容无关。

## 期望

WebView 缩放与 RN 侧 `react-native-webview` 的默认一致(RN 那边没有显式设 `textZoom`,
用的是系统默认 + `scalesPageToFit` 语义)。

## 顺带

同屏的「用 APP 阅读这一页」图标应是手机(RN `src/app/web.tsx:130` 写死
`Icon name="smartphone"`),原生画成了文档/列表 —— 归票 40。

## 疑似代码位置

`ui/web/WebFallbackScreen.kt` 里 `WebView` 的 `settings`(`textZoom`、`loadWithOverviewMode`、
`useWideViewPort`、`initialScale`)。登录屏那个 WebView(`ui/login/LoginScreen.kt`)
在 22 的对照里内容缩放是**对的**,可以直接拿它的 settings 对拍。
