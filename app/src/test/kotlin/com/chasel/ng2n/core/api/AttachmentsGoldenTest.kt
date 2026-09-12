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
    assertEquals(name, imageFileName("https://example.com/"))
  }
}
