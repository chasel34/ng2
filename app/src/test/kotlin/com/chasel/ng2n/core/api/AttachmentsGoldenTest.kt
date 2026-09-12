package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import com.chasel.ng2n.golden.unknownField
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `attachments` domain 全量对拍(44 条)。
 *
 * 两处怪癖锁在这里:目标基址**只从响应的 `_ATTACH_BASE_VIEW` 来**(换基址就换域名),
 * 以及日期目录**固定 UTC+8**——`dated-directory-is-utc-plus-8` 那条在任何设备时区下
 * 都得算出 `mon_202505/26`,所以实现里用的是 `ZoneOffset.ofHours(8)` 而不是系统时区。
 */
class AttachmentsGoldenTest {

  @Test
  fun `attachments 金样本全量对拍`() = runGoldenDomain("attachments") {
    fn("normalizeAttachBase") { case -> normalizeAttachBase(case.unknownField("raw")) }
    fn("stripThumbnailSuffix") { case -> stripThumbnailSuffix(case.inputString()) }
    fn("rehostLegacyAttachment") { case ->
      rehostLegacyAttachment(case.stringField("src"), case.stringField("base"))
    }
    fn("thumbnailUrl") { case -> thumbnailUrl(case.stringField("url"), case.stringField("base")) }
    fn("attachmentUrl") { case ->
      val ref = case.field("ref").jsonObject
      val options = case.field("options").jsonObject
      attachmentUrl(
        src = ref.getValue("src").jsonPrimitive.content,
        needsAttachBase = ref.getValue("needsAttachBase").jsonPrimitive.booleanOrNull == true,
        base = options.getValue("base").jsonPrimitive.content,
        postedAt = options["postedAt"]?.jsonPrimitive?.long,
      )
    }
    fn("imageFileName") { case -> imageFileName(case.inputString()) }
    fn("imageMimeType") { case -> imageMimeType(case.inputString()) }
  }

  // --- 手工移植:`attachments.test.ts` 里金样本没覆盖的几条 ---------------------

  @Test
  fun `缺字段时退到兜底基址`() {
    val fallback = "https://$ATTACH_BASE_FALLBACK"
    assertEquals(fallback, normalizeAttachBase(null))
    assertEquals(fallback, normalizeAttachBase(""))
    assertEquals(fallback, normalizeAttachBase(42))
  }

  @Test
  fun `四种缩略图后缀都剥,正常文件名不动`() {
    for (suffix in listOf(".thumb.jpg", ".thumb_s.jpg", ".thumb_ss.jpg", ".medium.jpg")) {
      assertEquals("mon_202607/21/a.jpg", stripThumbnailSuffix("mon_202607/21/a.jpg$suffix"))
    }
    assertEquals("mon_202607/21/a.jpg", stripThumbnailSuffix("mon_202607/21/a.jpg"))
    assertEquals("mon_202607/21/thumb.jpg", stripThumbnailSuffix("mon_202607/21/thumb.jpg"))
  }

  /**
   * 版头 0 楼那张图(真实样本):正文里写死的是 `img.nga.178.com` 绝对地址,
   * 该域名已停(TLS 握手失败,2026-08-08 实测),同一路径挂响应给的基址仍是 200。
   *
   * TS 侧这条是从 `readBoardHead` fixture 一路走 `parseTopicDetail` 过来的;
   * 端点层是票 07 的地盘,这里只把它的**末段**(AST 引用 → 最终地址)钉住。
   */
  @Test
  fun `版头 0 楼那张图——老域名换成响应给的基址`() {
    val src = "https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png"
    assertEquals(
      "https://img.nga.cn/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png",
      attachmentUrl(
        src = src,
        needsAttachBase = false,
        base = normalizeAttachBase("img.nga.cn/attachments"),
        postedAt = 1591142400,
      ),
    )
  }

  @Test
  fun `整段路径没名字时用短哈希兜底,且同一地址两次同名`() {
    val name = imageFileName("https://example.com/")
    assertTrue(Regex("^image-[0-9a-z]+\\.jpg$").matches(name), name)
    // 缓存中转靠它幂等
    assertEquals(name, imageFileName("https://example.com/"))
  }
}
