package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import kotlin.test.Test
import kotlin.test.assertEquals

/** 手工移植自 `src/ui/bbcode/album.test.ts`。取法照 NGA 官方 `js_bbscode_core.js` 的 `[album]` 分支。 */
class AlbumTest {

  private val base = "https://img.nga.cn/attachments"
  private val options = AttachmentUrlOptions(base = base)

  private fun urls(value: String, options: AttachmentUrlOptions = this.options) =
    albumImageUrls(value, options, DefaultAttachmentUrls)

  @Test
  fun `img 写法 取标签之间的地址 相对路径拼附件域名`() {
    assertEquals(
      listOf("$base/mon_202608/07/a.jpg", "$base/mon_202608/07/b.jpg"),
      urls("[img]./mon_202608/07/a.jpg[/img][img]./mon_202608/07/b.jpg[/img]"),
    )
  }

  @Test
  fun `url 写法与绝对地址一样认`() {
    assertEquals(
      listOf("https://example.test/a.png"),
      urls("[url]https://example.test/a.png[/url]"),
    )
  }

  @Test
  fun `裸地址堆在一起时退回扫地址`() {
    assertEquals(
      listOf(
        "$base/mon_202608/07/a.jpg",
        "$base/mon_202608/07/b.jpg",
        "https://example.test/c.png",
      ),
      urls("./mon_202608/07/a.jpg ./mon_202608/07/b.jpg https://example.test/c.png"),
    )
  }

  @Test
  fun `相对路径没带日期目录时按发帖时间补前缀`() {
    assertEquals(
      listOf("$base/mon_202505/26/-7Qd36d-x.jpg"),
      urls("[img]./-7Qd36d-x.jpg[/img]", options.copy(postedAt = 1748252025L)),
    )
  }

  @Test
  fun `一个地址都没有时给空表 而不是抛异常`() {
    assertEquals(emptyList(), urls(""))
    assertEquals(emptyList(), urls("这里什么都没有"))
  }
}
