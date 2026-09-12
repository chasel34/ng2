package com.chasel.ng2n.core.net

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {

  private val credential = Credential(uid = "10000001", token = "fake-cid-token")

  @Test
  fun `form 方式把凭证放 POST body(MNGA 的做法)`() {
    assertEquals(
      AuthAttachment(
        headers = emptyMap(),
        form = mapOf("access_uid" to "10000001", "access_token" to "fake-cid-token"),
      ),
      buildAuthAttachment(AuthMode.FORM, credential),
    )
  }

  @Test
  fun `cookie 方式把凭证放 Cookie 头(Android 的做法)`() {
    assertEquals(
      AuthAttachment(
        headers = mapOf("Cookie" to "ngaPassportUid=10000001; ngaPassportCid=fake-cid-token"),
        form = emptyMap(),
      ),
      buildAuthAttachment(AuthMode.COOKIE, credential),
    )
  }

  @Test
  fun `both(默认)两样都带·Cookie 头会被 okhttp 的 cookie jar 顶掉,form 顶不掉`() {
    assertEquals(
      AuthAttachment(
        headers = mapOf("Cookie" to "ngaPassportUid=10000001; ngaPassportCid=fake-cid-token"),
        form = mapOf("access_uid" to "10000001", "access_token" to "fake-cid-token"),
      ),
      buildAuthAttachment(AuthMode.BOTH, credential),
    )
  }

  @Test
  fun `none 或无凭证时什么都不加(游客访问)`() {
    val empty = AuthAttachment(emptyMap(), emptyMap())
    assertEquals(empty, buildAuthAttachment(AuthMode.NONE, credential))
    assertEquals(empty, buildAuthAttachment(AuthMode.COOKIE, null))
    assertEquals(empty, buildAuthAttachment(AuthMode.FORM, null))
  }

  @Test
  fun `凭证残缺时按游客处理`() {
    val empty = AuthAttachment(emptyMap(), emptyMap())
    assertEquals(empty, buildAuthAttachment(AuthMode.COOKIE, Credential("1", "")))
    assertEquals(empty, buildAuthAttachment(AuthMode.FORM, Credential("", "x")))
  }
}
