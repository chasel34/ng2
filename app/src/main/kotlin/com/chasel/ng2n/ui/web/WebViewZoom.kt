package com.chasel.ng2n.ui.web

import android.webkit.WebView

/**
 * WebView 的**缩放**口径 —— 与 RN 侧 `react-native-webview` 的 Android 默认逐项对齐。
 *
 * ## 为什么需要这一份(票 46)
 *
 * 原生这边只开了 `javaScriptEnabled` / `domStorageEnabled`,`useWideViewPort` 一直是
 * Android 的默认值 `false`。`false` 时 WebView **不认页面的 `<meta name="viewport">`**,
 * 直接按 view 的物理宽当 CSS 视口铺,于是同一张 NGA 移动页在原生里整体放大了约 30%
 * (票 46 量到:角标块 68→90、页码钮 56→76),一屏能读的内容少一大截。
 *
 * RN 那边不写 `scalesPageToFit` 时它默认就是 `true`,而
 * `RNCWebViewManagerImpl.setScalesPageToFit` 一句把 `loadWithOverviewMode` 与
 * `useWideViewPort` **同时**打开(node_modules/react-native-webview,
 * `android/…/RNCWebViewManagerImpl.kt`)。缩放钮那两项 RN 也有自己的默认:
 * `setBuiltInZoomControls = true`、`setDisplayZoomControls = false`
 * (`src/WebView.android.tsx` 的解构默认值)—— 也就是「能双指缩放,但不出那两颗
 * 老式 ± 浮钮」。
 *
 * ## 故意**不设**的两项
 *
 * - `textZoom`:RN 侧没有默认值,不写这个 prop 就一次都不调 `settings.textZoom`。
 *   我们跟着不设 —— 系统「字体大小」无障碍设置对网页的影响两版因此一致。
 * - `initialScale`:同上,RN 没碰过 `WebView.setInitialScale`。写死一个初始缩放会
 *   把上面 `useWideViewPort` 刚谈妥的视口再顶掉一次。
 *
 * 这两项在 [WebViewZoomSink] 里留着方法,单测才能钉住「一次都没被调用」。
 */

/**
 * 缩放相关设置的写入面。
 *
 * 抽成接口是为了能在 **JVM 单测**里核对「写了哪几项、写成什么」——
 * `android.webkit.WebSettings` 在 unit test 的 android.jar 里只有会抛异常的桩,
 * 真造一个出来跑不起来,而这套值恰恰是票 46 的全部内容。
 */
interface WebViewZoomSink {
  fun setUseWideViewPort(value: Boolean)
  fun setLoadWithOverviewMode(value: Boolean)
  fun setBuiltInZoomControls(value: Boolean)
  fun setDisplayZoomControls(value: Boolean)

  /** RN 不设 —— 存在只是为了让单测能断言它没被调用。 */
  fun setTextZoom(value: Int)

  /** 同上。 */
  fun setInitialScale(value: Int)
}

/** 把 RN 侧那套默认值落到 [sink] 上。**加一项就得同时改 `WebViewZoomTest`**。 */
fun applyRnWebViewZoom(sink: WebViewZoomSink) {
  // scalesPageToFit(RN 默认 true)= 这两句
  sink.setUseWideViewPort(true)
  sink.setLoadWithOverviewMode(true)
  // 双指能缩放,但不出老式 ± 浮钮
  sink.setBuiltInZoomControls(true)
  sink.setDisplayZoomControls(false)
}

/** [WebViewZoomSink] 落到真 WebView 上的那一层。 */
private class RealWebViewZoomSink(private val view: WebView) : WebViewZoomSink {
  override fun setUseWideViewPort(value: Boolean) {
    view.settings.useWideViewPort = value
  }

  override fun setLoadWithOverviewMode(value: Boolean) {
    view.settings.loadWithOverviewMode = value
  }

  override fun setBuiltInZoomControls(value: Boolean) {
    view.settings.builtInZoomControls = value
  }

  override fun setDisplayZoomControls(value: Boolean) {
    view.settings.displayZoomControls = value
  }

  override fun setTextZoom(value: Int) {
    view.settings.textZoom = value
  }

  override fun setInitialScale(value: Int) {
    view.setInitialScale(value)
  }
}

/** 给一个真 WebView 套上 RN 那套缩放默认值。 */
fun WebView.applyRnWebViewZoom() {
  applyRnWebViewZoom(RealWebViewZoomSink(this))
}
