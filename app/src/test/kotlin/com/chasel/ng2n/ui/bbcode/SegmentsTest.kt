package com.chasel.ng2n.ui.bbcode

import com.chasel.ng2n.core.bbcode.parseBBCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 手工移植自 `src/ui/bbcode/segments.test.ts`。 */
class SegmentsTest {

  private fun kinds(source: String): List<String> =
    splitIntoSegments(parseBBCode(source)).map {
      if (it is Segment.Inline) "inline" else "block"
    }

  @Test
  fun `纯文字只有一段行内`() {
    assertEquals(listOf("inline"), kinds("第一行<br/>第二行"))
  }

  @Test
  fun `图片从行内流里摘出来单独占一块`() {
    assertEquals(
      listOf("inline", "block", "inline"),
      kinds("看图<br/>[img]./mon_202608/07/a.jpg[/img]<br/>就这样"),
    )
  }

  @Test
  fun `引用块与分割线也是块`() {
    assertEquals(listOf("block"), kinds("[quote]引用[/quote]"))
    assertEquals(listOf("block"), kinds("======"))
  }

  /**
   * 回归锁:回复头(NGA「回复」按钮的产物,没有 quote 容器)要跟引用块一样单独成块,
   * 不然它会被当成普通 `[b]` 塞进正文那一段,画不成引用卡片。
   */
  @Test
  fun `Reply to 回复头单独成块 后面的正文另起一段`() {
    val source = "[b]Reply to [pid=879039681,47406116,1]Reply[/pid] Post by " +
      "[uid=64858574]开始了吗还没[/uid] (2026-08-20 15:13)[/b]<br/>也就治治马保国了"
    assertEquals(listOf("block", "inline"), kinds(source))
  }

  @Test
  fun `普通粗体还是行内`() {
    assertEquals(listOf("inline"), kinds("[b]重点[/b]内容"))
  }

  @Test
  fun `只有换行的段不单独成段`() {
    assertEquals(
      listOf("block", "block"),
      kinds("[img]./a.jpg[/img]<br/><br/>[img]./b.jpg[/img]"),
    )
  }

  /**
   * 这一组是回归锁。只按「节点自己是不是块级」切段的话,下面这些写法里的图片
   * 会被塞进文字流,而它们在 NGA 上极常见。
   */
  @Test
  fun `裹在行内标签里的块级内容升格成块 不会被吞掉`() {
    val cases = mapOf(
      "居中图片" to "[align=center][img]./mon_202608/07/a.jpg[/img][/align]",
      "加粗裹图片" to "[b][img]./mon_202608/07/a.jpg[/img][/b]",
      "颜色裹引用" to "[color=red][quote]引用[/quote][/color]",
      "折叠里的图片" to "[collapse=看图][img]./mon_202608/07/a.jpg[/img][/collapse]",
      "列表项里的图片" to "[list][*][img]./mon_202608/07/a.jpg[/img][/list]",
      "表格单元格里的图片" to "[table][tr][td][img]./mon_202608/07/a.jpg[/img][/td][/tr][/table]",
      "套两层" to "[b][color=red][img]./mon_202608/07/a.jpg[/img][/color][/b]",
    )
    for ((name, source) in cases) {
      assertEquals(listOf("block"), kinds(source), name)
    }
  }

  @Test
  fun `没裹块级内容的行内标签仍然留在行内`() {
    for (source in listOf("[b]粗[/b]", "[color=red]红[/color]", "[size=120%]大字[/size]")) {
      assertTrue(kinds(source).all { it == "inline" }, source)
    }
  }

  /**
   * 这些标签哪怕里面只有一行字也得占一块:对齐要作用在容器上、折叠块要有开关、
   * 表格要能横向滚、骰子和媒体是卡片——留在文字流里这些都做不到。
   */
  @Test
  fun `自带框或需要交互的进阶标签单独占一块`() {
    val cases = mapOf(
      "居中的一行字" to "[align=center]居中字[/align]",
      "折叠块" to "[collapse=提要]内容[/collapse]",
      "列表" to "[list][*]甲[*]乙[/list]",
      "表格" to "[table][tr][td]甲[/td][td]乙[/td][/tr][/table]",
      "版规警告块" to "[lessernuke]内容[/lessernuke]",
      "骰子" to "[dice]1d100[/dice]",
      "视频" to "[flash=video]./a.mp4[/flash]",
      "附件" to "[attach]./a.zip[/attach]",
      "相册" to "[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]",
      "标题" to "[h]小标题[/h]",
    )
    for ((name, source) in cases) {
      assertEquals(listOf("block"), kinds(source), name)
    }
  }

  @Test
  fun `containsBlock 递归看到任意深度`() {
    val node = parseBBCode("[b][i][u][img]./a.jpg[/img][/u][/i][/b]").single()
    assertTrue(containsBlock(node))
  }

  @Test
  fun `containsBlock 纯文字不算`() {
    assertTrue(!containsBlock(parseBBCode("就是一段字").single()))
  }
}
