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

/** uiautomator / 票 18 找网页兜底屏的锚点。 */
const val WEB_SCREEN_TAG: String = "ng2n-web-screen"

/**
 * 网页版是浅色纸底,深色主题下也别在加载间隙闪黑(照抄 RN 版写死的这一档)。
 */
private val PAPER = Color(0xFFF2EFE6)

/**
 * 网页版兜底页(设计稿 isWebview,ADR-0002 反封锁链的最后一档)——
 * `src/app/web.tsx` 的移植。
 *
 * 反封锁链把格式 × 域名、换账号、Web 反解全试完还是拿不到数据时,用户至少还能读到
 * 这一页 —— 直接把网页版装进 WebView。
 *
 * 它是**站内页**而不是系统浏览器:回退到系统浏览器就丢了「用 APP 阅读这一页」这个
 * 回切入口,而被封往往是一时的,下一次多半就通了。
 *
 * ## 登录态
 *
 * 靠 Android 的原生 cookie 仓库(与登录屏共用同一个 `CookieManager`)。
 * **多账号时那份 cookie 是最后一次登录的账号**,不一定是 app 里当前切到的那个 ——
 * 这与 RN 版是同一个差异,记在这里。app 自己的请求不受影响:它们走票 06 的自管
 * CookieJar,账号状态只有 `AccountStore` 一个来源(P1-03)。
 *
 * ## 返回键
 *
 * 页内有历史时先在 WebView 里后退,退到头才退出这一屏(Android 惯例;
 * RN 版没做这一层,是原生这边该有的行为)。
 */
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

  /** 顶栏第二行那串地址,照设计稿去掉协议头。 */
  val urlHint = remember(url) { url.replace(Regex("^https?://"), "") }

  /** 网页菜单,条目与顺序照设计稿 `MENUS.web`。 */
  fun pick(run: () -> Unit): () -> Unit = {
    menuOpen = false
    run()
  }
  val menuItems = listOf(
    MenuItem("reload", "刷新页面", onClick = pick {
      webView?.reload()
      toast("已刷新网页")
    }),
    // 回切:退回来的就是那一屏帖子详情,它在重新获得焦点时会再打一次原生接口
    MenuItem("app", "用 APP 阅读", onClick = pick(onBack)),
    MenuItem("copy", "复制网址", onClick = pick {
      copyToClipboard(context, url)
      toast("已复制网址")
    }),
    MenuItem("browser", "在系统浏览器打开", onClick = pick {
      runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { toast("没有可用的浏览器") }
    }),
    // 网页字号要往页面里注 JS 改 zoom —— 桩项(research/inventory.md §2)
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
        // 设计稿这里是两行:标题 + 等宽字的地址
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
                // 页内跳转留在 WebView 里,别甩给系统浏览器
                view.webViewClient = object : WebViewClient() {
                  override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                  }

                  override fun onPageFinished(v: WebView?, u: String?) {
                    loading = false
                  }
                }
                // 登录 cookie 在原生仓库里(见文件头),不用 incognito 否则读不到
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
                // 加载遮罩不吃点击穿透以外的任何事:pointerInput 空实现挡住底下的 WebView
                .pointerInput(Unit) {},
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(color = colors.primary)
            }
          }
        }
      }
    }

    // 设计稿:悬浮在底部的回切按钮,左右 16、底 20、高 48
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
      // RN 侧 `src/app/web.tsx:130` 写死的是 `smartphone`(票 40:原来错拿了文档图标)
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
