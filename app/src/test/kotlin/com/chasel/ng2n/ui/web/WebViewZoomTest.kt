package com.chasel.ng2n.ui.web

import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewZoomTest {

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

  @Test
  fun `落到 WebView 上的四项与 RN 侧默认逐项相同`() {
    val sink = RecordingSink()
    applyRnWebViewZoom(sink)

    assertEquals(true, sink.calls["useWideViewPort"])
    assertEquals(true, sink.calls["loadWithOverviewMode"])
    assertEquals(true, sink.calls["builtInZoomControls"])
    assertEquals(false, sink.calls["displayZoomControls"])
  }

  @Test
  fun `useWideViewPort 必须打开 否则页面会被放大约三成`() {
    val sink = RecordingSink()
    applyRnWebViewZoom(sink)
    assertEquals(true, sink.calls["useWideViewPort"])
  }

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
