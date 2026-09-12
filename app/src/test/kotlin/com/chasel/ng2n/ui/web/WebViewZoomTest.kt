package com.chasel.ng2n.ui.web

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 网页屏 WebView 的缩放口径(票 46)—— 与 RN 侧 `react-native-webview` 的
 * Android 默认逐项对拍。数值出处写在 [applyRnWebViewZoom] 的 KDoc 里。
 */
class WebViewZoomTest {

  /** 记下「谁被调了、调成什么」,顺序也留着。 */
  private class RecordingSink : WebViewZoomSink {
    val calls = linkedMapOf<String, Any>()

    override fun setUseWideViewPort(value: Boolean) {
      calls["useWideViewPort"] = value
    }

    override fun setLoadWithOverviewMode(value: Boolean) {
      calls["loadWithOverviewMode"] = value
    }

    override fun setBuiltInZoomControls(value: Boolean) {
      calls["builtInZoomControls"] = value
    }

    override fun setDisplayZoomControls(value: Boolean) {
      calls["displayZoomControls"] = value
    }

    override fun setTextZoom(value: Int) {
      calls["textZoom"] = value
    }

    override fun setInitialScale(value: Int) {
      calls["initialScale"] = value
    }
  }

  /**
   * `scalesPageToFit` 在 RN 侧默认 true,而
   * `RNCWebViewManagerImpl.setScalesPageToFit` 一句同时开
   * `loadWithOverviewMode` 与 `useWideViewPort`;缩放钮两项的默认值在
   * `WebView.android.tsx` 的解构里(true / false)。
   */
  @Test
  fun `落到 WebView 上的四项与 RN 侧默认逐项相同`() {
    val sink = RecordingSink()
    applyRnWebViewZoom(sink)

    assertEquals(true, sink.calls["useWideViewPort"])
    assertEquals(true, sink.calls["loadWithOverviewMode"])
    assertEquals(true, sink.calls["builtInZoomControls"])
    assertEquals(false, sink.calls["displayZoomControls"])
  }

  /**
   * `useWideViewPort` 是这张票的正主:它为 false(Android 默认)时 WebView 不认
   * 页面的 viewport meta,同一张 NGA 移动页整体放大约 30%。
   */
  @Test
  fun `useWideViewPort 必须打开 否则页面会被放大约三成`() {
    val sink = RecordingSink()
    applyRnWebViewZoom(sink)
    assertEquals(true, sink.calls["useWideViewPort"])
  }

  /**
   * RN 不设 `textZoom` / `initialScale` —— 一次都不调。写死任何一个都会把
   * `useWideViewPort` 谈妥的视口再顶掉一次,也会让系统字号设置在两版里表现不同。
   */
  @Test
  fun `textZoom 与 initialScale 一次都不设 照 RN 侧`() {
    val sink = RecordingSink()
    applyRnWebViewZoom(sink)

    assertEquals(
      listOf("useWideViewPort", "loadWithOverviewMode", "builtInZoomControls", "displayZoomControls"),
      sink.calls.keys.toList(),
    )
  }
}
