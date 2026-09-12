package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.bbcode.parseBBCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** 手工移植自 `src/ui/bbcode/floor-images.test.ts`。 */
class FloorImagesTest {

  private val base = "https://img.nga.cn/attachments"

  /** 2026-08-07 12:00 (UTC+8),补 `[noimg]` 日期目录用。 */
  private val postedAt = 1786075200L

  private fun collect(
    source: String,
    attachments: List<FloorAttachment> = emptyList(),
    options: AttachmentUrlOptions = AttachmentUrlOptions(base = base),
  ) = collectFloorImages(parseBBCode(source), attachments, options, DefaultAttachmentUrls)

  @Test
  fun `正文 img 相对路径拼上附件基址 并配缩略图变体`() {
    assertEquals(
      listOf(
        ViewerImage(
          url = "$base/mon_202608/07/-abc-1.jpg",
          thumbnailUrl = "$base/mon_202608/07/-abc-1.jpg.thumb.jpg",
        ),
      ),
      collect("看图[img]./mon_202608/07/-abc-1.jpg[/img]"),
    )
  }

  @Test
  fun `嵌在引用块与行内标签里的图也收 和渲染器同一副视角`() {
    val images = collect(
      "[quote][img]./mon_202608/07/a.jpg[/img][/quote][b][img]./mon_202608/07/b.jpg[/img][/b]",
    )
    assertEquals(
      listOf("$base/mon_202608/07/a.jpg", "$base/mon_202608/07/b.jpg"),
      images.map { it.url },
    )
  }

  @Test
  fun `noimg 缺日期目录时按发帖时间补`() {
    val images = collect(
      "[noimg]./-7Qd36d-x.jpg[/noimg]",
      options = AttachmentUrlOptions(base = base, postedAt = postedAt),
    )
    assertEquals("$base/mon_202608/07/-7Qd36d-x.jpg", images.single().url)
  }

  @Test
  fun `站外图片原样收进来 不配缩略图`() {
    assertEquals(
      listOf(ViewerImage("https://i.example.com/pic.png")),
      collect("[img]https://i.example.com/pic.png[/img]"),
    )
  }

  @Test
  fun `album 里的裸地址逐张展开`() {
    val images = collect("[album]./mon_202608/07/a.jpg ./mon_202608/07/b.jpg[/album]")
    assertEquals(
      listOf("$base/mon_202608/07/a.jpg", "$base/mon_202608/07/b.jpg"),
      images.map { it.url },
    )
  }

  @Test
  fun `图片附件排在正文之后 带服务端给的缩略图 非图片附件不收`() {
    val attachments = listOf(
      FloorAttachment(
        url = "$base/mon_202608/07/att.jpg",
        kind = "img",
        thumbnailUrl = "$base/mon_202608/07/att.jpg.thumb.jpg",
      ),
      FloorAttachment(url = "$base/mon_202608/07/pack.zip", kind = "file"),
    )
    val images = collect("[img]./mon_202608/07/body.jpg[/img]", attachments)
    assertEquals(
      listOf("$base/mon_202608/07/body.jpg", "$base/mon_202608/07/att.jpg"),
      images.map { it.url },
    )
    assertEquals("$base/mon_202608/07/att.jpg.thumb.jpg", images[1].thumbnailUrl)
  }

  @Test
  fun `同一张图正文与附件都出现时按第一次出现去重`() {
    val images = collect(
      "[img]./mon_202608/07/dup.jpg[/img]",
      listOf(FloorAttachment(url = "$base/mon_202608/07/dup.jpg", kind = "img")),
    )
    assertEquals(1, images.size)
  }

  @Test
  fun `没有图时给空表`() {
    assertEquals(emptyList(), collect("纯文字"))
  }
}
