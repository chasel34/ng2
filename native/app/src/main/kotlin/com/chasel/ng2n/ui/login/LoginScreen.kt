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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.ui.accounts.CloseIcon
import com.chasel.ng2n.ui.accounts.LockIcon
import com.chasel.ng2n.ui.accounts.RefreshIcon

/** uiautomator 找登录屏的锚点(票 18 / 手验用)。 */
const val LOGIN_SCREEN_TAG: String = "ng2n-login-screen"

/**
 * WebView 登录屏 —— `src/app/login.tsx` 的移植。逻辑全在 [LoginViewModel],
 * 这里只负责「把官方登录页原样摆出来」。
 *
 * ## 为什么必须是 WebView
 *
 * 客户端不接触密码:NGA 的登录是官方网页流程(含验证码 / 二次验证),
 * 我们只在它成功之后从原生 cookie 仓库里把 `ngaPassportCid` 收走 —— 那个 cookie 是
 * HttpOnly,页内 JS 看不见,只有 `android.webkit.CookieManager` 拿得到。
 *
 * ## 与 CookieManager 的接触面
 *
 * 全 app **只有这一屏**碰 `CookieManager`,而且只碰两件事:
 * 一是这里打开 cookie 接收开关(不开的话登录页根本走不完),
 * 二是 [LoginViewModel] 经 `WebCookieVault` 做的「进场清空 / 收割后清空」。
 * app 自己的 HTTP 请求一枚 WebView cookie 都不用(票 06 的自管 CookieJar)——
 * 这就是 P1-03 的修法:账号状态**只有 `AccountStore` 一个来源**。
 */
@Composable
fun LoginScreen(onBack: () -> Unit) {
  val viewModel: LoginViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var webView by remember { mutableStateOf<WebView?>(null) }

  // 收割成功:提示一句然后退场(RN 版 `showToast` + `router.back()`)
  LaunchedEffect(state) {
    val captured = state as? LoginUiState.Captured ?: return@LaunchedEffect
    Toast.makeText(context, "已登录 ${captured.name}", Toast.LENGTH_SHORT).show()
    onBack()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .semantics { contentDescription = LOGIN_SCREEN_TAG },
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
        CloseIcon(tint = MaterialTheme.colorScheme.onBackground)
      }
      Text(
        text = "登录 NGA 账号",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
          .weight(1f)
          .padding(start = 4.dp),
      )
      IconButton(
        onClick = { webView?.reload() },
        modifier = Modifier
          .size(46.dp)
          .semantics { contentDescription = "刷新登录页" },
      ) {
        RefreshIcon(tint = MaterialTheme.colorScheme.onBackground)
      }
    }

    // 地址提示条:让用户看得见自己在跟哪个域名打交道(照设计稿只到 __lib=login 一段)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 14.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LockIcon(tint = MaterialTheme.colorScheme.primary)
      Text(
        text = urlHint(state),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    HorizontalDivider()

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        // 官方登录页本身是白底,深色主题下也保持,免得页面加载间隙闪黑
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
            // 登录页要 JS(验证码、表单校验都靠它);domStorage 照 RN 版一并打开。
            // UA **不改**:RN 版用的就是系统 WebView 默认 UA,登录页认这个。
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            // 页内跳转留在 WebView 里,别甩给系统浏览器(那样 cookie 就收不到了)
            view.webViewClient = WebViewClient()
            // ⚠️ 不用 incognito:Android 上独立 cookie 仓库的 WebView,原生
            // CookieManager 读不到里面的登录 cookie(真机实测 2026-08-08)。
            // 多账号隔离靠挂载前 clearAll(见 LoginViewModel)。
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

    // 琥珀色提示卡照设计稿写死:它压在白底登录页下面,不跟主题走
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(12.dp),
    ) {
      Text(
        text = "客户端仅托管官方登录页，不接触你的密码；cookie 保存在本地，可在「账号管理」中随时删除。",
        fontSize = 11.5.sp,
        lineHeight = 18.4.sp,
        color = Color(0xFF8A6D1F),
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFFFFF8E6), RoundedCornerShape(6.dp))
          .border(1.dp, Color(0xFFF0E0B0), RoundedCornerShape(6.dp))
          .padding(12.dp),
      )
      Spacer(Modifier.height(4.dp))
    }
  }
}

/** 地址条文案:去掉 scheme,只留到 `__lib=login` 那一段。 */
private fun urlHint(state: LoginUiState): String {
  val url = (state as? LoginUiState.Ready)?.url ?: return "正在准备登录页…"
  return url.removePrefix("https://").removePrefix("http://").substringBefore("&__act=")
}
