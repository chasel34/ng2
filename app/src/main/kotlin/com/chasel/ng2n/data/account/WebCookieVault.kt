package com.chasel.ng2n.data.account

import android.webkit.CookieManager
import com.chasel.ng2n.core.net.COOKIE_PASSPORT_CID
import com.chasel.ng2n.core.net.COOKIE_PASSPORT_UID
import com.chasel.ng2n.core.net.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * WebView 那份 cookie 仓库(`android.webkit.CookieManager`)的**唯一**出入口。
 *
 * ## 修 P1-03:两份 cookie 各归各家
 *
 * app 自己的 HTTP 请求**一枚 WebView cookie 都不用** —— 走票 06 的自管
 * `data/net/NgaCookieJar`,凭证每请求现读 `AccountStore`。RN 版的毛病是两边共用一份
 * (`ExpoFetchModule` 挂的就是 WebView 的 `CookieManager`),于是「app 当前账号」和
 * 「WebView 里登着谁」可以长期不一致,退出后网页仍是登录态(审计 P1-03)。
 *
 * 这一版把 `CookieManager` 降级成**一个只在三个点被碰的临时区**:
 *
 * 1. **登录屏挂载前** [clearAll] —— 多账号隔离,不清的话轮询会立刻「捕获」上一个账号;
 * 2. **登录收割成功后** [clearAll] —— 凭证已经进 `AccountStore` 了,WebView 里那份
 *    留着只会变成「谁也说不清现在是谁」的第二个身份来源(**这一条是对 RN 版的有意偏离**:
 *    `src/app/login.tsx` 只在进场清,收割完不清);
 * 3. **切号 / 登出后** [clearAll] —— 审计 P1-03 的「至少在退出时清理 cookie」。
 *
 * 网页兜底屏(票 17 的 `/web`)要带登录态时,**不要**指望这里残留着什么,
 * 用 [seed] 按 app 的当前账号现灌一份:这就是审计说的「原生层支持按账号安全同步 cookie,
 * 并向 WebView 暴露可验证的当前身份」。
 *
 * ## 为什么是接口
 *
 * `CookieManager` 是静态单例,JVM 单测里连类都加载不了。登录收割与登出清理的**逻辑**
 * 归 ViewModel,`CookieManager` 的**真实行为**归 [AndroidWebCookieVault] 与它的
 * androidTest —— 两边各测各的,谁都不用为对方降低标准。
 */
interface WebCookieVault {

  /** 读 `k=v; k2=v2` 原始串(含 HttpOnly 的 `ngaPassportCid`);没有则空串。 */
  suspend fun read(url: String): String

  /** 清空 WebView 全部 cookie 并 flush;返回是否真的清掉了什么。 */
  suspend fun clearAll(): Boolean

  /**
   * 按 app 的当前账号灌一份 passport cookie(先清后灌,**不做合并**)。
   * 游客态传 null,等同只清空。
   */
  suspend fun seed(url: String, credential: Credential?)
}

/**
 * [WebCookieVault] 的真实装:`android.webkit.CookieManager`。
 *
 * `removeAllCookies` 的回调依赖调用线程有 Looper(RN 版 `NgaCookiesModule.kt` 里
 * 那句 `Handler(Looper.getMainLooper()).post` 就是为这个),所以整个类都钉在主线程上跑;
 * 这几个调用本身是内存操作 + 一次 `flush()`,不在冷启动路径上。
 */
@Singleton
class AndroidWebCookieVault @Inject constructor() : WebCookieVault {

  override suspend fun read(url: String): String = withContext(Dispatchers.Main) {
    runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: ""
  }

  override suspend fun clearAll(): Boolean = withContext(Dispatchers.Main) {
    val manager = runCatching { CookieManager.getInstance() }.getOrNull()
      ?: return@withContext false
    val removed = suspendCancellableCoroutine { continuation ->
      manager.removeAllCookies { removed -> continuation.resume(removed) }
    }
    manager.flush()
    removed
  }

  override suspend fun seed(url: String, credential: Credential?) {
    clearAll()
    if (credential == null || credential.uid.isEmpty() || credential.token.isEmpty()) return
    withContext(Dispatchers.Main) {
      val manager = runCatching { CookieManager.getInstance() }.getOrNull()
        ?: return@withContext
      manager.setCookie(url, "$COOKIE_PASSPORT_UID=${credential.uid}; Path=/")
      manager.setCookie(url, "$COOKIE_PASSPORT_CID=${credential.token}; Path=/")
      manager.flush()
    }
  }
}
