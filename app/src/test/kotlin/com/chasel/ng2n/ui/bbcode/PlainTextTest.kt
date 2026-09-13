package com.chasel.ng2n.ui.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextTest {

  @Test
  fun `纯文本压缩空白 引用内容照收`() {
    assertEquals(
      "Reply Post by 某人 (2026): 被引用的话 我的回复",
      plainTextOf("[quote][b]Reply Post by 某人 (2026):[/b]\n被引用的话[/quote]\n我的回复"),
    )
  }

  @Test
  fun `楼层自己的话跳过引用块`() {
    assertEquals(
      "我的回复",
      ownTextOf("[quote][b]Reply Post by 某人 (2026):[/b]\n被引用的话[/quote]\n我的回复"),
    )
  }

  @Test
  fun `整层只有引用时退回全文而不是空串`() {
    assertEquals("被引用的话", ownTextOf("[quote]被引用的话[/quote]"))
  }
}
