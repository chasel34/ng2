package com.chasel.ng2n.ui.login

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.MonoFontFamily
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

const val LOGIN_SCREEN_TAG: String = "ng2n-login-screen"

@Composable
fun LoginScreen(onBack: () -> Unit) {
  val viewModel: LoginViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val colors = LocalNg2nColors.current
  var webView by remember { mutableStateOf<WebView?>(null) }

  LaunchedEffect(state) {
    val captured = state as? LoginUiState.Captured ?: return@LaunchedEffect
    Toast.makeText(context, "已登录 ${captured.name}", Toast.LENGTH_SHORT).show()
    onBack()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(colors.bg)
      .semantics { contentDescription = LOGIN_SCREEN_TAG },
  ) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.CLOSE,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "关闭登录页",
        onClick = onBack,
      )
      TopBarTitle(text = "登录 NGA 账号", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.REFRESH,
        size = 22.dp,
        contentDescription = "刷新登录页",
        onClick = { webView?.reload() },
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surface2)
        .padding(horizontal = Spacing.row, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      AppIcon(icon = Ng2nIcon.LOCK, tint = colors.primary, size = 16.dp)
      Text(
        text = urlHint(state),
        style = TextStyle(
          fontSize = Typo.meta.size,
          lineHeight = Typo.meta.lineHeight,
          fontFamily = MonoFontFamily,
          color = colors.fg2,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color.White),
    ) {
      val ready = state as? LoginUiState.Ready
      if (ready != null) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            val view = WebView(ctx)
            view.layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = WebViewClient()
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(view, true)
            view.loadUrl(ready.url)
            webView = view
            view
          },
          onRelease = { view ->
            webView = null
            view.destroy()
          },
        )
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.bg)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(Spacing.md),
    ) {
      Text(
        text = "客户端仅托管官方登录页，不接触你的密码；cookie 保存在本地，可在「账号管理」中随时删除。",
        style = TextStyle(
          fontSize = 11.5.sp,
          lineHeight = 18.4.sp,
          color = Color(0xFF8A6D1F),
        ),
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFFFFF8E6), RoundedCornerShape(6.dp))
          .border(1.dp, Color(0xFFF0E0B0), RoundedCornerShape(6.dp))
          .padding(Spacing.md),
      )
      Spacer(Modifier.height(4.dp))
    }
  }
}

private fun urlHint(state: LoginUiState): String {
  val url = (state as? LoginUiState.Ready)?.url ?: return "正在准备登录页…"
  return url.removePrefix("https://").removePrefix("http://").substringBefore("&__act=")
}
