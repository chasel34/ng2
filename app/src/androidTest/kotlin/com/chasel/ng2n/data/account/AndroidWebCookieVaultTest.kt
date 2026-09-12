package com.chasel.ng2n.data.account

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chasel.ng2n.core.net.Credential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `CookieManager` 真实行为的验证 —— **在设备上跑**
 * (`./gradlew :app:connectedDebugAndroidTest --tests '*AndroidWebCookieVaultTest'`)。
 *
 * 票 15 验收③「登出 / 切号后 WebView 与 CookieJar 互不污染」的 WebView 那一半:
 * JVM 单测证明的是「登出会调 clearAll」(对着假实现),这里证明「clearAll 真的清得掉」。
 * 两条合起来才算数。
 *
 * ⚠️ 它会清掉本 app 进程里 WebView 的全部 cookie —— 那正是它要证明的事,不碰别的 app。
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebCookieVaultTest {

  private val vault = AndroidWebCookieVault()
  private val url = "https://bbs.nga.cn"

  @Before
  fun setUp() {
    CookieManager.getInstance().setAcceptCookie(true)
  }

  @After
  fun tearDown() = runTest { vault.clearAll() }

  @Test
  fun clearAll_把登录_cookie_清干净() = runTest {
    CookieManager.getInstance().setCookie(url, "ngaPassportUid=10000001; Path=/")
    CookieManager.getInstance().setCookie(url, "ngaPassportCid=cid-a; Path=/")
    CookieManager.getInstance().flush()
    assertTrue("前置条件:cookie 得先写进去", vault.read(url).contains("ngaPassportUid"))

    vault.clearAll()

    val after = vault.read(url)
    assertFalse("退出之后 WebView 不该还留着登录 cookie(修 P1-03)", after.contains("ngaPassportUid"))
    assertFalse(after.contains("ngaPassportCid"))
  }

  @Test
  fun seed_按当前账号灌一份_先清后灌() = runTest {
    CookieManager.getInstance().setCookie(url, "ngaPassportUid=99999999; Path=/")
    CookieManager.getInstance().setCookie(url, "ngaPassportCid=cid-stale; Path=/")
    CookieManager.getInstance().flush()

    vault.seed(url, Credential(uid = "10000002", token = "cid-b"))

    // 网页兜底屏(票 17)靠它保证「网页那边就是 app 的当前账号」
    val cookies = parseCookieString(vault.read(url))
    assertEquals("10000002", cookies["ngaPassportUid"])
    assertEquals("cid-b", cookies["ngaPassportCid"])
  }

  @Test
  fun seed_游客态等同只清空() = runTest {
    CookieManager.getInstance().setCookie(url, "ngaPassportUid=99999999; Path=/")
    CookieManager.getInstance().flush()

    vault.seed(url, null)

    assertFalse(vault.read(url).contains("ngaPassportUid"))
  }

  @Test
  fun read_读得到_HttpOnly_的_cid() = runTest {
    // 收割能成立的前提:HttpOnly 的 ngaPassportCid 只有原生 CookieManager 拿得到,
    // 页内 document.cookie 看不见(真机实测 2026-08-08)
    CookieManager.getInstance().setCookie(url, "ngaPassportCid=cid-httponly; Path=/; HttpOnly")
    CookieManager.getInstance().flush()

    assertTrue(vault.read(url).contains("ngaPassportCid=cid-httponly"))
  }
}
