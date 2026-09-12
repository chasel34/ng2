package com.chasel.ng2n.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 票 12 只用到 `attachments` 这一族里的四个函数,这里逐条对着 TS 原件
 * `src/core/api/attachments.ts` 钉住。
 *
 * **注意**:这族函数整体归**票 10**,金样本 `attachments` domain 有 44 条。
 * 本文件不是那 44 条的替代品,是票 12 自用的最小回归——票 10 落地后,
 * 实现换成金样本对拍过的那份,这些用例应当照样绿。
 *
 * djb2 兜底哈希的期望值是拿 node 跑 TS 同一段算出来的(而不是照着 Kotlin 的输出填),
 * 两端不一致的话「同一张图在两端推出两个文件名」,重复保存检测就会失效。
 */
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
    // 站外图床没有这套后缀约定,加上去就是 404
    val outside = "https://example.com/x.png"
    assertEquals(outside, urls.thumbnailUrl(outside, base))
  }

  @Test
  fun `缩略图地址 先剥再加所以幂等`() {
    val once = urls.thumbnailUrl("$base/x.png", base)
    assertEquals(once, urls.thumbnailUrl(once, base))
    // 服务端给的就是 .thumb_s 时也归一到 .thumb
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
    // NGA 的 [noimg] 附件名是一串没有扩展名的哈希
    assertEquals("-7Qd36d-abc.jpg", urls.imageFileName("$base/mon_202608/07/-7Qd36d-abc"))
    // 落不进表的扩展名也当没有
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
    // URLDecoder 会把 + 变成空格再变成下划线,decodeURIComponent 不会
    assertEquals("a+b.png", urls.imageFileName("https://example.com/a+b.png"))
  }

  @Test
  fun `文件名 转不动的裸百分号原样保留不抛`() {
    assertEquals("100_.jpg", urls.imageFileName("https://example.com/100%.jpg"))
  }

  @Test
  fun `文件名 最后一段为空时用 djb2 兜底`() {
    // 期望值来自 node 跑 TS 侧同一段 djb2
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
