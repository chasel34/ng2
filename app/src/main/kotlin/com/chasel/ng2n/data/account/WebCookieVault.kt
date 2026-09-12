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

interface WebCookieVault {

  suspend fun read(url: String): String

  suspend fun clearAll(): Boolean

  suspend fun seed(url: String, credential: Credential?)
}

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
    // WebView 共用全局 Cookie 存储，播种前须清掉上一账号的凭证。
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
