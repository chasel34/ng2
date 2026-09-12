package com.chasel.ng2n.data.account

import com.chasel.ng2n.core.net.encoding.encodeUriComponent
import com.chasel.ng2n.core.net.encoding.gbkEncodeUriComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginCookiesTest {

  private val uid = "67241234"
  private val cid = "Xa0123456789abcdefABCDEF0123456789abcdef"

  @Test
  fun `解析分号分隔的键值对并去掉两侧空白`() {
    val jar = parseCookieString("a=1; b=2 ;c= 3")
    assertEquals("1", jar["a"])
    assertEquals("2", jar["b"])
    assertEquals("3", jar["c"])
  }

  @Test
  fun `值里再出现等号时不截断`() {
    assertEquals("abc=def", parseCookieString("token=abc=def")["token"])
  }

  @Test
  fun `无等号的碎片与空键直接跳过`() {
    val jar = parseCookieString("junk; =orphan; ok=1")
    assertEquals(1, jar.size)
    assertEquals("1", jar["ok"])
  }

  @Test
  fun `uid 与 cid 都齐才算登录成功 并顺手带上用户名 cookie`() {
    val cookie = "ngaPassportUid=$uid; ngaPassportCid=$cid; ngaPassportUrlencodedUname=%25D2%25F5"
    assertEquals(
      LoginCookies(uid = uid, cid = cid, urlencodedUname = "%25D2%25F5"),
      extractLoginCookies(cookie),
    )
  }

  @Test
  fun `用户名 cookie 缺失时给 null 不影响凭证识别`() {
    assertEquals(
      LoginCookies(uid = uid, cid = cid, urlencodedUname = null),
      extractLoginCookies("ngaPassportUid=$uid; ngaPassportCid=$cid"),
    )
  }

  @Test
  fun `登录前的占位值不算 uid 是 guest cid 是短垃圾串 只有其一`() {
    assertNull(extractLoginCookies("ngaPassportUid=guest; ngaPassportCid=$cid"))
    assertNull(extractLoginCookies("ngaPassportUid=$uid; ngaPassportCid=deleted"))
    assertNull(extractLoginCookies("ngaPassportUid=$uid"))
    assertNull(extractLoginCookies("ngaPassportCid=$cid"))
    assertNull(extractLoginCookies(""))
  }

  @Test
  fun `无关 cookie 一大堆也能认出目标两枚`() {
    val cookie =
      "lastvisit=1754600000; guestJs=1754600001; ngaPassportUid=$uid; " +
        "bbsmisccookies=%7B%7D; ngaPassportCid=$cid"
    assertEquals(uid, extractLoginCookies(cookie)?.uid)
  }

  @Test
  fun `中文用户名 硬编码向量`() {
    assertEquals(
      "阴阳师妄想",
      decodeLoginUsername("%25D2%25F5%25D1%25F4%25CA%25A6%25CD%25FD%25CF%25EB"),
    )
  }

  @Test
  fun `与本仓库 GBK 编码器对拍 中文 混排 带数字`() {
    for (name in listOf("阴阳师妄想", "chasel43", "猫猫头MK2", "天使动漫")) {
      assertEquals(name, decodeLoginUsername(encodeUriComponent(gbkEncodeUriComponent(name))))
    }
  }

  @Test
  fun `兼容 Java URLEncoder 的习惯 小写十六进制与加号空格`() {
    assertEquals("阴", decodeLoginUsername("%25d2%25f5"))
    assertEquals("a b", decodeLoginUsername("a+b"))
  }

  @Test
  fun `畸形输入返回 null 调用方回落 UID 展示`() {
    assertNull(decodeLoginUsername("%2"))
    assertNull(decodeLoginUsername("%25ZZ"))
    assertNull(decodeLoginUsername(""))
    assertNull(decodeLoginUsername("%2581"))
    assertNull(decodeLoginUsername("名字"))
  }
}
