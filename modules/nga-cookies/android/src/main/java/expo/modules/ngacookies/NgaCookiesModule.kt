package expo.modules.ngacookies

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * 读 WebView 原生 cookie 仓库的最小封装。存在的唯一理由:登录 cookie 里的
 * ngaPassportCid 是 HttpOnly,页面 JS(document.cookie)看不到,只有原生
 * android.webkit.CookieManager 能读——NGA-CLIENT 走的同一条路。
 */
class NgaCookiesModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("NgaCookies")

    // 返回 "k=v; k2=v2" 原始串(含 HttpOnly),没有则空串
    AsyncFunction("getCookieString") { url: String ->
      CookieManager.getInstance().getCookie(url) ?: ""
    }

    // 清空 WebView 全部 cookie(多账号隔离:打开登录页前清掉上一个账号)
    AsyncFunction("clearAll") { promise: Promise ->
      // removeAllCookies 的回调依赖 Looper,固定切到主线程调
      Handler(Looper.getMainLooper()).post {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies { removed ->
          manager.flush()
          promise.resolve(removed)
        }
      }
    }
  }
}
