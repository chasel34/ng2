package com.chasel.ng2n.ui.web

import android.webkit.WebView

interface WebViewZoomSink {
  fun setUseWideViewPort(value: Boolean)
  fun setLoadWithOverviewMode(value: Boolean)
  fun setBuiltInZoomControls(value: Boolean)
  fun setDisplayZoomControls(value: Boolean)

  fun setTextZoom(value: Int)

  fun setInitialScale(value: Int)
}

fun applyRnWebViewZoom(sink: WebViewZoomSink) {
  sink.setUseWideViewPort(true)
  sink.setLoadWithOverviewMode(true)
  sink.setBuiltInZoomControls(true)
  sink.setDisplayZoomControls(false)
}

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

fun WebView.applyRnWebViewZoom() {
  applyRnWebViewZoom(RealWebViewZoomSink(this))
}
