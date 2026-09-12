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
    CookieManager.getInstance().setCookie(url, "ngaPassportCid=cid-httponly; Path=/; HttpOnly")
    CookieManager.getInstance().flush()

    assertTrue(vault.read(url).contains("ngaPassportCid=cid-httponly"))
  }
}
