package com.chasel.ng2n.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboundCharsetTest {

  private val jsonFormat = queryOf("__output" to "8")
  private val jsonLiteFormat = queryOf("lite" to "js")

  @Test
  fun `无 GBK 参数时带上 __inchst=UTF8,并剔除空值参数`() {
    val query = queryOf("fid" to 650, "stid" to null, "page" to 1)
    val url = outboundUrl("https://bbs.nga.cn", "thread.php", outboundQuery(query, jsonFormat))

    assertEquals("https://bbs.nga.cn/thread.php?__inchst=UTF8&__output=8&fid=650&page=1", url)
    assertFalse(url.contains("stid"), "空值参数必须从 query 中删除")
  }

  @Test
  fun `query 里有 GBK 参数就撤掉 __inchst=UTF8`() {
    val gbkQuery = queryOf("author" to gbk("原神"))
    val utf8Query = queryOf("key" to "原神")

    val gbkUrl = outboundUrl("https://bbs.nga.cn", "thread.php", outboundQuery(gbkQuery, jsonFormat))
    val utf8Url = outboundUrl("https://bbs.nga.cn", "thread.php", outboundQuery(utf8Query, jsonFormat))

    assertTrue(gbkUrl.contains("author=%D4%AD%C9%F1"), gbkUrl)
    assertFalse(gbkUrl.contains("__inchst"), "GBK 参数在场时必须撤掉 UTF8 声明:$gbkUrl")
    assertTrue(utf8Url.contains("key=%E5%8E%9F%E7%A5%9E"), utf8Url)
    assertTrue(utf8Url.contains("__inchst=UTF8"), utf8Url)
  }

  @Test
  fun `forum_php 的 key 走 GBK,thread_php 的 key 走 UTF-8`() {
    val forum = outboundUrl("https://bbs.nga.cn", "forum.php", outboundQuery(queryOf("key" to gbk("炉石")), jsonFormat))
    val thread = outboundUrl("https://bbs.nga.cn", "thread.php", outboundQuery(queryOf("key" to "炉石", "page" to 1), jsonFormat))

    assertTrue(forum.contains("key=%C2%AF%CA%AF"), forum)
    assertFalse(forum.contains("__inchst"), forum)
    assertTrue(thread.contains("key=%E7%82%89%E7%9F%B3"), thread)
    assertTrue(thread.contains("__inchst=UTF8"), thread)
  }

  @Test
  fun `block-word 的 data 是 GBK 载荷,同样撤掉 __inchst`() {
    val query = queryOf(
      "__lib" to "user_option",
      "__act" to "set_block_word",
      "data" to gbk("1\r\n加密货币 测试\r\n42/张三"),
    )
    val url = outboundUrl("https://bbs.nga.cn", "nuke.php", outboundQuery(query, jsonLiteFormat))

    assertTrue(url.contains("__act=set_block_word"), url)
    assertTrue(
      url.contains("data=1%0D%0A%BC%D3%C3%DC%BB%F5%B1%D2%20%B2%E2%CA%D4%0D%0A42%2F%D5%C5%C8%FD"),
      url,
    )
    assertFalse(url.contains("__inchst"), url)
  }

  @Test
  fun `表单里有 GBK 值时 Content-Type 声明 charset=GBK`() {
    val form = queryOf("content" to gbk("原神"))

    assertEquals("application/x-www-form-urlencoded;charset=GBK", formContentType(form))
    assertEquals("content=%D4%AD%C9%F1", buildQueryString(form))
  }

  @Test
  fun `表单全是 UTF-8 时 Content-Type 不加 charset`() {
    assertEquals(
      "application/x-www-form-urlencoded",
      formContentType(queryOf("access_uid" to "123", "access_token" to "abc")),
    )
    assertEquals("application/x-www-form-urlencoded", formContentType(null))
    assertEquals("application/x-www-form-urlencoded", formContentType(queryOf("content" to gbk(""))))
  }

  @Test
  fun `__inchst 只看 query、Content-Type 只看 form,两个判据各管各的`() {
    val query = queryOf("fid" to 650)
    val form = queryOf("content" to gbk("原神"))

    assertEquals(QueryValue.Text("UTF8"), inchstParam(query))
    assertEquals("application/x-www-form-urlencoded;charset=GBK", formContentType(form))

    assertEquals(null, inchstParam(queryOf("author" to gbk("原神"))))
    assertEquals("application/x-www-form-urlencoded", formContentType(queryOf("content" to "原神")))
  }

  @Test
  fun `请求自己写的 __inchst 盖掉默认值,但位置仍在最前`() {
    val query = outboundQuery(queryOf("fid" to 650, "__inchst" to "GBK"), jsonFormat)

    assertEquals(listOf("__inchst", "__output", "fid"), query.keys.toList())
    assertEquals("__inchst=GBK&__output=8&fid=650", buildQueryString(query))
  }

  @Test
  fun `没有任何参数时 URL 不带问号`() {
    assertEquals("https://bbs.nga.cn/thread.php", outboundUrl("https://bbs.nga.cn/", "thread.php", emptyMap()))
  }

  @Test
  fun `GBK 表外字符逐 UTF-16 码元写十进制实体再 percent 编码`() {
    assertEquals(
      "author=%C3%FE%D3%E3%26%2355357%3B%26%2356836%3B",
      buildQueryString(queryOf("author" to gbk("摸鱼😄"))),
    )
  }
}
