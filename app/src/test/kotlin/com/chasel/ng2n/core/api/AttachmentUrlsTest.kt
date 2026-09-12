package com.chasel.ng2n.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentUrlsTest {

  private val urls = DefaultAttachmentUrls
  private val base = "https://img.nga.cn/attachments"

  @Test
  fun `剥缩略图后缀 四种都认`() {
    for (suffix in listOf(".thumb_ss.jpg", ".thumb_s.jpg", ".thumb.jpg", ".medium.jpg")) {
      assertEquals("$base/x.png", urls.stripThumbnailSuffix("$base/x.png$suffix"))
    }
  }

  @Test
  fun `没有后缀的原样返回`() {
    assertEquals("$base/x.png", urls.stripThumbnailSuffix("$base/x.png"))
  }

  @Test
  fun `缩略图地址 只对本站附件动手`() {
    assertEquals("$base/x.png.thumb.jpg", urls.thumbnailUrl("$base/x.png", base))
    val outside = "https://example.com/x.png"
    assertEquals(outside, urls.thumbnailUrl(outside, base))
  }

  @Test
  fun `缩略图地址 先剥再加所以幂等`() {
    val once = urls.thumbnailUrl("$base/x.png", base)
    assertEquals(once, urls.thumbnailUrl(once, base))
    assertEquals("$base/x.png.thumb.jpg", urls.thumbnailUrl("$base/x.png.thumb_s.jpg", base))
  }

  @Test
  fun `文件名 取路径最后一段`() {
    assertEquals("abc.jpg", urls.imageFileName("$base/mon_202608/07/abc.jpg"))
  }

  @Test
  fun `文件名 去查询串与锚点`() {
    assertEquals("abc.jpg", urls.imageFileName("$base/mon_202608/07/abc.jpg?v=2"))
    assertEquals("abc.jpg", urls.imageFileName("$base/mon_202608/07/abc.jpg#top"))
  }

  @Test
  fun `文件名 剥掉缩略图后缀 —— 存的是原图`() {
    assertEquals("abc.jpg", urls.imageFileName("$base/mon_202608/07/abc.jpg.thumb.jpg"))
  }

  @Test
  fun `文件名 认不出扩展名时补 jpg`() {
    assertEquals("-7Qd36d-abc.jpg", urls.imageFileName("$base/mon_202608/07/-7Qd36d-abc"))
    assertEquals("x.bin.jpg", urls.imageFileName("$base/x.bin"))
  }

  @Test
  fun `文件名 替换文件系统不认的字符`() {
    assertEquals("a_b.jpg", urls.imageFileName("https://example.com/a b.jpg"))
    assertEquals("a_b.jpg", urls.imageFileName("https://example.com/a%20b.jpg"))
  }

  @Test
  fun `文件名 percent 转义按 decodeURIComponent 解 —— 加号是字面加号`() {
    assertEquals("中文.png", urls.imageFileName("https://example.com/%E4%B8%AD%E6%96%87.png"))
    assertEquals("a+b.png", urls.imageFileName("https://example.com/a+b.png"))
  }

  @Test
  fun `文件名 转不动的裸百分号原样保留不抛`() {
    assertEquals("100_.jpg", urls.imageFileName("https://example.com/100%.jpg"))
  }

  @Test
  fun `文件名 最后一段为空时用 djb2 兜底`() {
    assertEquals("image-uq7l9g.jpg", urls.imageFileName("https://x.com/"))
    assertEquals("image-sic163.jpg", urls.imageFileName("https://x.com/%20"))
  }

  @Test
  fun `MIME 按扩展名 大小写不敏感`() {
    assertEquals("image/png", urls.imageMimeType("a.png"))
    assertEquals("image/jpeg", urls.imageMimeType("a.JPG"))
    assertEquals("image/gif", urls.imageMimeType("a.gif"))
    assertEquals("image/webp", urls.imageMimeType("a.webp"))
  }

  @Test
  fun `MIME 认不出的按 jpeg 兜底`() {
    assertEquals("image/jpeg", urls.imageMimeType("a.bin"))
    assertEquals("image/jpeg", urls.imageMimeType("noextension"))
    assertEquals("image/jpeg", urls.imageMimeType(".hidden"))
  }
}
