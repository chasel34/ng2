package com.chasel.ng2n.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import com.chasel.ng2n.ui.common.MenuItem
import com.chasel.ng2n.ui.common.NOT_AVAILABLE_MESSAGE
import com.chasel.ng2n.ui.common.OverflowMenu
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.rememberToaster
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.MonoFontFamily
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

const val WEB_SCREEN_TAG: String = "ng2n-web-screen"

private val PAPER = Color(0xFFF2EFE6)

@Composable
fun WebFallbackScreen(url: String, title: String?, onBack: () -> Unit) {
  val colors = LocalNg2nColors.current
  val context = LocalContext.current
  val toast = rememberToaster()
  var webView by remember { mutableStateOf<WebView?>(null) }
  var loading by remember(url) { mutableStateOf(true) }
  var menuOpen by remember { mutableStateOf(false) }

  BackHandler(enabled = true) {
    val view = webView
    if (view != null && view.canGoBack()) view.goBack() else onBack()
  }

  val urlHint = remember(url) { url.replace(Regex("^https?://"), "") }

  fun pick(run: () -> Unit): () -> Unit = {
    menuOpen = false
    run()
  }
  val menuItems = listOf(
    MenuItem("reload", "刷新页面", onClick = pick {
      webView?.reload()
      toast("已刷新网页")
    }),
    MenuItem("app", "用 APP 阅读", onClick = pick(onBack)),
    MenuItem("copy", "复制网址", onClick = pick {
      copyToClipboard(context, url)
      toast("已复制网址")
    }),
    MenuItem("browser", "在系统浏览器打开", onClick = pick {
      runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { toast("没有可用的浏览器") }
    }),
    MenuItem("font", "网页字号", onClick = pick { toast(NOT_AVAILABLE_MESSAGE) }),
  )

  Box(
    Modifier
      .fillMaxSize()
      .background(colors.bg)
      .semantics { contentDescription = WEB_SCREEN_TAG },
  ) {
    Column(Modifier.fillMaxSize()) {
      TopBar(paddingHorizontal = 4.dp) {
        TopBarButton(
          icon = Ng2nIcon.ARROW_BACK,
          size = 24.dp,
          box = 46.dp,
          contentDescription = "返回",
          onClick = onBack,
        )
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
          Text(
            text = title ?: "网页版",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
              fontSize = 15.5.sp,
              lineHeight = 21.sp,
              fontWeight = FontWeight.SemiBold,
              color = colors.onTopbar,
            ),
          )
          Text(
            text = urlHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
              fontSize = 10.5.sp,
              lineHeight = 14.sp,
              fontFamily = MonoFontFamily,
              color = colors.onTopbar.copy(alpha = 0.75f),
            ),
          )
        }
        TopBarButton(
          icon = Ng2nIcon.MORE_VERT,
          size = 22.dp,
          contentDescription = "网页菜单",
          onClick = { menuOpen = true },
        )
      }

      if (url.isEmpty()) {
        EmptyState(
          icon = Ng2nIcon.CLOUD_OFF,
          text = "没有可打开的网页地址",
          modifier = Modifier.weight(1f),
        )
      } else {
        Box(Modifier.fillMaxSize().background(PAPER)) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
              WebView(ctx).also { view ->
                view.layoutParams = ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT,
                )
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.applyRnWebViewZoom()
                view.webViewClient = object : WebViewClient() {
                  override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                  }

                  override fun onPageFinished(v: WebView?, u: String?) {
                    loading = false
                  }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.loadUrl(url)
                webView = view
              }
            },
            onRelease = { view ->
              webView = null
              view.destroy()
            },
          )
          if (loading) {
            Box(
              Modifier
                .fillMaxSize()
                .background(PAPER)
                .pointerInput(Unit) {},
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(color = colors.primary)
            }
          }
        }
      }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = Spacing.lg)
        .padding(bottom = bottomInset + 20.dp)
        .fillMaxWidth()
        .height(48.dp)
        .shadow(Elevation.level1, RoundedCornerShape(15.dp))
        .clip(RoundedCornerShape(15.dp))
        .background(colors.fab)
        .clickable(onClickLabel = "用 APP 阅读这一页", onClick = onBack)
        .semantics { contentDescription = "用 APP 阅读这一页" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
    ) {
      AppIcon(icon = Ng2nIcon.SMARTPHONE, tint = colors.onFab, size = 21.dp)
      Text(
        text = "用 APP 阅读这一页",
        style = TextStyle(
          fontSize = 15.sp,
          lineHeight = 21.sp,
          fontWeight = FontWeight.SemiBold,
          color = colors.onFab,
        ),
      )
    }

    OverflowMenu(open = menuOpen, onDismiss = { menuOpen = false }, items = menuItems)
  }
}

private fun copyToClipboard(context: Context, text: String) {
  val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  manager.setPrimaryClip(ClipData.newPlainText("网址", text))
}
