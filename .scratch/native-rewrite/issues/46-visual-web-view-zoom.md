# 46 — P2:网页版 WebView 内容比 Expo 放大约 30%

**Status:** resolved

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

## Comments

**2026-08-23 — 修复**

根因是 `useWideViewPort`。原生这边两个 WebView 都只开了 `javaScriptEnabled` /
`domStorageEnabled`,`useWideViewPort` 留在 Android 的默认值 **false** —— 为 false 时
WebView **不认页面的 `<meta name="viewport">`**,直接按 view 的物理宽当 CSS 视口铺,
于是同一张 NGA 移动页整体放大了约 30%(票里量到 68→90、56→76)。

RN 那边 `scalesPageToFit` 不写时默认 `true`,而
`node_modules/react-native-webview/android/…/RNCWebViewManagerImpl.kt:682`
的 `setScalesPageToFit` 一句同时开 `loadWithOverviewMode` 与 `useWideViewPort`;
缩放钮两项的默认在 `src/WebView.android.tsx:77-78`(`setBuiltInZoomControls = true`、
`setDisplayZoomControls = false`)。

新增 `ui/web/WebViewZoom.kt`:`applyRnWebViewZoom()` 把这四项落下去,
`WebFallbackScreen.kt` 的 `factory` 里调一次。**故意不设** `textZoom` 与
`initialScale` —— RN 侧一次都没调过它们(`textZoom` 只在传了那个 prop 时才写),
写死任何一个都会把 `useWideViewPort` 刚谈妥的视口再顶掉一次,也会让系统「字体大小」
无障碍设置在两版里表现不同。

**单测**:`ui/web/WebViewZoomTest.kt`(3 例)。设置面抽成 `WebViewZoomSink` 接口就是
为了能在 JVM 上核对——`android.webkit.WebSettings` 在 unit test 的 android.jar 里只有
会抛的桩。第三例断言调用集合**恰好**是那四项,textZoom / initialScale 一次都没被调。

**有意偏离票里的建议**:票里写「登录屏那个 WebView 缩放是对的,可以直接拿它的
settings 对拍」—— 实际两屏的 settings **一模一样**(都没设 `useWideViewPort`),
登录页看着对是因为那张页面本身的排版,不是配置差异。所以登录屏的 WebView **没动**:
它在票 22 的对照里是通过项,而本轮不许上真机/模拟器,不能验证改完是否仍然正确。
helper 已经抽出来了,后续要统一直接在 `LoginScreen.kt` 的 factory 里加一行即可。

**顺带那条**(「用 APP 阅读这一页」的手机图标)属票 40,已随票 40 的图标集重做修掉:
`WebFallbackScreen.kt` 用的是 `Ng2nIcon.SMARTPHONE`。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**通过 —— 而且是这一轮最硬的一条证据:两边内容区逐像素相同。**

复验时两边打开的**恰好是同一个 tid 47422692 的同一页**(Expo 那半的截图就是这一页),
把两张全分辨率截图的 WebView 内容区 `y 340–1400 × 全宽`(1080×1060 = 1 144 800 px)做差:

```
diff bbox = None      # 完全没有差异像素
pixels with |Δ|>24 : 0 / 1144800 = 0.000%
```

票面量到的 68→90 / 156→172 / 56→76(整体 +30%)全部归零。`useWideViewPort` 那一改坐实。
「用 APP 阅读这一页」的手机图标也已随票 40 换成 smartphone,同图可见。

**顺带记一条不属本票的**:同一 tid 下,Expo 顶栏主标题是固定文案「网页版」,原生显示的是**帖子标题**。
原报告 21 #3 把它记为「待确认(两边 tid 不同)」——这一轮 tid 相同,说明**不是 tid 造成的**,
是真差异。不在本票范围,已在 `visual-parity.md` 的复验节里另记,请所有者裁。

对照图 [`../acceptance/visual/after/21.png`](../acceptance/visual/after/21.png)
