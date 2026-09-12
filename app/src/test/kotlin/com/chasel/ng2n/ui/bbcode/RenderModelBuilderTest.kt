package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.bbcode.Align
import com.chasel.ng2n.core.bbcode.BoxVariant
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.DiceOutcome
import com.chasel.ng2n.core.local.DiceTerm
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.ui.theme.MonoFontFamily
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderModelBuilderTest {

  private val base = "https://img.nga.cn/attachments"

  private fun options(
    postedAt: Long? = null,
    dice: List<DiceOutcome> = emptyList(),
  ) = BBCodeRenderOptions(
    attachBase = base,
    postedAt = postedAt,
    dice = dice.toImmutableList(),
  )

  private fun model(source: String, options: BBCodeRenderOptions = options()) =
    RenderModelBuilder.build(parseBBCode(source), options)

  private fun single(source: String, options: BBCodeRenderOptions = options()): RenderSegment {
    val segments = model(source, options).segments
    assertEquals(1, segments.size, "「$source」应当只切出一段,实际 $segments")
    return segments.first()
  }

  private fun text(source: String): TextSegment {
    val segment = single(source)
    assertTrue(segment is TextSegment, "「$source」应当是行内段,实际 ${segment::class.simpleName}")
    return segment
  }

  private fun AnnotatedString.spanAt(index: Int) =
    spanStyles.filter { index >= it.start && index < it.end }.map { it.item }

  @Test
  fun `text 一段字进文字段`() {
    val segment = text("一段字")
    assertEquals("一段字", segment.text.text)
    assertEquals(0, segment.smilies.size)
  }

  @Test
  fun `linebreak 换成真的换行符`() {
    assertEquals("上\n下", text("上<br/>下").text.text)
  }

  @Test
  fun `bold 整段加粗`() {
    val segment = text("[b]粗[/b]")
    assertTrue(segment.text.spanAt(0).any { it.fontWeight == FontWeight.Bold })
  }

  @Test
  fun `italic 整段斜体`() {
    assertTrue(text("[i]斜[/i]").text.spanAt(0).any { it.fontStyle == FontStyle.Italic })
  }

  @Test
  fun `underline 整段下划线`() {
    val spans = text("[u]下划线[/u]").text.spanAt(0)
    assertTrue(spans.any { it.textDecoration == TextDecoration.Underline })
  }

  @Test
  fun `strike 整段删除线`() {
    val spans = text("[del]删除线[/del]").text.spanAt(0)
    assertTrue(spans.any { it.textDecoration == TextDecoration.LineThrough })
  }

  @Test
  fun `color 认得出的色名落成真颜色`() {
    val spans = text("[color=red]红[/color]").text.spanAt(0)
    assertTrue(spans.any { it.color == Color(0xFFFF0000) })
  }

  @Test
  fun `color 认不出的值不进 span 不吞字`() {
    val segment = text("[color=红色]字[/color]")
    assertEquals("字", segment.text.text)
    assertTrue(segment.text.spanAt(0).all { it.color == Color.Unspecified })
  }

  @Test
  fun `size 按正文字号乘倍数 而不是按当前上下文`() {
    val spans = text("[size=120%]大[/size]").text.spanAt(0)
    assertTrue(spans.any { it.fontSize.value in 18.5f..18.7f }, "实际 $spans")
  }

  @Test
  fun `font 只渲染内容 不认字体名`() {
    val segment = text("[font=宋体]宋体[/font]")
    assertEquals("宋体", segment.text.text)
    assertTrue(segment.text.spanAt(0).isEmpty())
  }

  @Test
  fun `code 走等宽字体`() {
    val segment = text("[code]const a = 1[/code]")
    assertEquals("const a = 1", segment.text.text)
    assertTrue(segment.text.spanAt(0).any { it.fontFamily == MonoFontFamily })
  }

  @Test
  fun `link 钉一条 LINK annotation 盖住可见文字`() {
    val segment = text("[url=https://example.test]站外[/url]")
    assertEquals("站外", segment.text.text)
    val annotations = segment.text.getStringAnnotations(BBCodeAnnotation.LINK, 0, segment.text.length)
    assertEquals(1, annotations.size)
    assertEquals("https://example.test", annotations[0].item)
    assertEquals(0, annotations[0].start)
    assertEquals("站外".length, annotations[0].end)
  }

  @Test
  fun `link 没写内容就把地址当内容显示`() {
    val segment = text("[url]https://example.test[/url]")
    assertEquals("https://example.test", segment.text.text)
  }

  @Test
  fun `userRef 钉 uid`() {
    val segment = text("[uid=123]某人[/uid]")
    val annotations = segment.text.getStringAnnotations(BBCodeAnnotation.USER, 0, segment.text.length)
    assertEquals("123", annotations.single().item)
    assertEquals("某人", segment.text.text)
  }

  @Test
  fun `topicRef 没写内容时显示井号加 tid`() {
    val segment = text("[tid]45150945[/tid]")
    assertEquals("#45150945", segment.text.text)
    val annotations = segment.text.getStringAnnotations(BBCodeAnnotation.TOPIC, 0, segment.text.length)
    assertEquals("45150945", annotations.single().item)
  }

  @Test
  fun `floorRef 把三个参数原样带走 回复链要用`() {
    val segment = text("[pid=1,2,3]Reply[/pid]")
    val annotations = segment.text.getStringAnnotations(BBCodeAnnotation.FLOOR, 0, segment.text.length)
    assertEquals("1,2,3", annotations.single().item)
  }

  @Test
  fun `mention 前面补一个 at`() {
    val segment = text("[@某人]")
    assertEquals("@某人", segment.text.text)
    val annotations =
      segment.text.getStringAnnotations(BBCodeAnnotation.MENTION, 0, segment.text.length)
    assertEquals("某人", annotations.single().item)
  }

  @Test
  fun `smiley 占一个内联位 指向随包 assets`() {
    val segment = text("[s:ac:blink]")
    assertEquals(1, segment.smilies.size)
    val placement = segment.smilies.single()
    assertEquals("ac0.png", placement.id)
    assertEquals("file:///android_asset/smilies/ac0.png", placement.url)
    assertTrue(placement.aspect > 0f)
  }

  @Test
  fun `smiley 同一个表情出现两次只登记一条占位`() {
    val segment = text("[s:ac:blink][s:ac:blink]")
    assertEquals(1, segment.smilies.size)
  }

  @Test
  fun `smiley 查不到的原样显示原文 不吞字`() {
    val segment = text("[s:ac:根本没有这个]")
    assertEquals("[s:ac:根本没有这个]", segment.text.text)
    assertEquals(0, segment.smilies.size)
  }

  @Test
  fun `quote 切成引用卡`() {
    val segment = single("[quote]引用[/quote]")
    assertTrue(segment is QuoteSegment)
    assertEquals("引用", (segment.body.segments.single() as TextSegment).text.text)
    assertNull(segment.chain)
  }

  @Test
  fun `quote 认得出 pid 时带上链引用`() {
    val segment = single("[quote][pid=879039681,47406116,1]Reply[/pid] 正文[/quote]")
    assertTrue(segment is QuoteSegment)
    assertEquals(QuoteRef(pid = 879039681L, tid = 47406116L, page = 1L), segment.chain)
  }

  @Test
  fun `Reply to 回复头也画成引用卡`() {
    val source =
      "[b]Reply to [pid=879039681,47406116,1]Reply[/pid] Post by [uid=64858574]谁[/uid] (2026-08-20 15:13)[/b]<br/>也就治治马保国了"
    val segments = model(source).segments
    assertEquals(2, segments.size)
    assertTrue(segments[0] is QuoteSegment)
    assertEquals(879039681L, (segments[0] as QuoteSegment).chain?.pid)
    assertTrue(segments[1] is TextSegment)
  }

  @Test
  fun `image 拼出附件绝对地址与缩略图`() {
    val segment = single("[img]./mon_202608/07/a.jpg[/img]")
    assertTrue(segment is ImageSegment)
    assertEquals("$base/mon_202608/07/a.jpg", segment.url)
    assertEquals("$base/mon_202608/07/a.jpg.thumb.jpg", segment.thumbnailUrl)
  }

  @Test
  fun `image 站外图不配缩略图 图床没有这套后缀约定`() {
    val segment = single("[img]https://i.example.com/pic.png[/img]") as ImageSegment
    assertEquals("https://i.example.com/pic.png", segment.url)
    assertNull(segment.thumbnailUrl)
  }

  @Test
  fun `noimg 缺日期目录时按发帖时间补 UTC 加八`() {
    val segment = single("[noimg]./-7Qd36d-x.jpg[/noimg]", options(postedAt = 1786075200L))
    assertEquals("$base/mon_202608/07/-7Qd36d-x.jpg", (segment as ImageSegment).url)
  }

  @Test
  fun `divider 单独一段`() {
    assertEquals(DividerSegment, single("======"))
  }

  @Test
  fun `heading 内容进小标题块 且加粗`() {
    val segment = single("===标题===")
    assertTrue(segment is HeadingSegment)
    val inner = segment.body.segments.single() as TextSegment
    assertEquals("标题", inner.text.text)
    assertTrue(inner.text.spanAt(0).any { it.fontWeight == FontWeight.Bold })
  }

  @Test
  fun `align 对齐同时落到容器与文字上`() {
    val segment = single("[align=center]居中[/align]")
    assertTrue(segment is AlignSegment)
    assertEquals(Align.CENTER, segment.align)
    assertEquals(TextAlign.Center, (segment.body.segments.single() as TextSegment).textAlign)
  }

  @Test
  fun `collapse 标题进卡片提要行`() {
    val segment = single("[collapse=提要]藏起来的话[/collapse]")
    assertTrue(segment is CollapseSegment)
    assertEquals("提要", segment.title)
    assertEquals("藏起来的话", (segment.body.segments.single() as TextSegment).text.text)
  }

  @Test
  fun `collapse 没标题时给一句默认文案`() {
    assertEquals("折叠的内容", (single("[collapse]话[/collapse]") as CollapseSegment).title)
  }

  @Test
  fun `list 每一项一份嵌套模型`() {
    val segment = single("[list][*]甲[*]乙[/list]")
    assertTrue(segment is ListSegment)
    assertEquals(2, segment.items.size)
    assertEquals(false, segment.ordered)
    assertEquals("甲", (segment.items[0].segments.single() as TextSegment).text.text)
  }

  @Test
  fun `table 定宽列 与 rowspan 忽略`() {
    val segment = single("[table][tr][td]甲[/td][td]乙[/td][/tr][/table]")
    assertTrue(segment is TableSegment)
    val row = segment.rows.single()
    assertEquals(2, row.cells.size)
    assertEquals(108.dp, row.cells[0].width)
    assertEquals(0, row.paddingCells)
  }

  @Test
  fun `table 短行补格 免得最后一格右边缺竖线`() {
    val segment = single(
      "[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][/tr][/table]",
    ) as TableSegment
    assertEquals(0, segment.rows[0].paddingCells)
    assertEquals(1, segment.rows[1].paddingCells)
  }

  @Test
  fun `box lessernuke 带官方提示语 且默认收起`() {
    val segment = single("[lessernuke]处罚说明[/lessernuke]")
    assertTrue(segment is BoxSegment)
    assertEquals(BoxVariant.LESSERNUKE, segment.variant)
    assertEquals("用户因此帖中的发言被处罚", segment.notice)
  }

  @Test
  fun `box hip 与 item 只是普通一块 没有提示语`() {
    assertNull((single("[hip]内容[/hip]") as BoxSegment).notice)
    assertNull((single("[item]内容[/item]") as BoxSegment).notice)
  }

  @Test
  fun `dice 调用方没给点数时退回显示表达式`() {
    val segment = single("[dice]1d100[/dice]")
    assertTrue(segment is DiceSegment)
    assertEquals("1d100", segment.expression)
    assertNull(segment.outcome)
  }

  @Test
  fun `dice 按文档顺序取点数 写法相同的两颗不会串`() {
    val first = DiceOutcome("1d100", listOf(DiceTerm.Roll(100, 7)), sum = 7)
    val second = DiceOutcome("1d100", listOf(DiceTerm.Roll(100, 88)), sum = 88)
    val segments = model(
      "[dice]1d100[/dice]<br/>[dice]1d100[/dice]",
      options(dice = listOf(first, second)),
    ).segments
    assertEquals(7L, (segments[0] as DiceSegment).outcome?.sum)
    assertEquals(88L, (segments[1] as DiceSegment).outcome?.sum)
  }

  @Test
  fun `flash video 画媒体卡 标签是视频`() {
    val segment = single("[flash=video]./a.mp4[/flash]")
    assertTrue(segment is MediaSegment)
    assertEquals("视频", segment.label)
    assertEquals("a.mp4", segment.fileName)
    assertEquals("$base/a.mp4", segment.url)
  }

  @Test
  fun `flash audio 与裸 flash 各有各的标签`() {
    assertEquals("音频", (single("[flash=audio]./a.mp3[/flash]") as MediaSegment).label)
    assertEquals("动画", (single("[flash]./a.swf[/flash]") as MediaSegment).label)
  }

  @Test
  fun `attach 画附件卡 文件名去掉路径`() {
    val segment = single("[attach]./a.zip[/attach]")
    assertTrue(segment is AttachSegment)
    assertEquals("a.zip", segment.fileName)
  }

  @Test
  fun `album 逐张展开成图片段`() {
    val segment = single("[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]")
    assertTrue(segment is AlbumSegment)
    assertEquals(
      listOf("$base/a.jpg", "$base/b.jpg"),
      segment.images.map { it.url },
    )
  }

  @Test
  fun `行内标签裹着块级内容时 升格成容器 且样式往下带`() {
    val segment = single("[b][color=red][img]./a.jpg[/img][/color][/b]")
    assertTrue(segment is GroupSegment)
    val inner = segment.body.segments.single()
    assertTrue(inner is GroupSegment)
    assertTrue(inner.body.segments.single() is ImageSegment)
  }

  @Test
  fun `行内标签裹块级时 同层的文字仍然带着外层样式`() {
    val segment = single("[b]前面的字[img]./a.jpg[/img][/b]") as GroupSegment
    val first = segment.body.segments.first() as TextSegment
    assertEquals("前面的字", first.text.text)
    assertTrue(
      first.text.spanAt(0).any { it.fontWeight == FontWeight.Bold },
      "外层 [b] 的加粗必须带进块里,否则升格之后字就不粗了",
    )
  }

  @Test
  fun `引用块里的正文换一档字号与颜色`() {
    val outer = (model("正文").segments.single() as TextSegment)
    val inner = (single("[quote]引用[/quote]") as QuoteSegment)
      .body.segments.single() as TextSegment
    assertTrue(inner.fontSize.value < outer.fontSize.value)
    assertTrue(inner.color != outer.color)
  }

  @Test
  fun `防剧透 color white 白字照画 另钉一条 SPOILER annotation`() {
    val segment = text("[color=white]答案[/color]")
    val spans = segment.text.spanAt(0)
    assertTrue(spans.any { it.color == Color(0xFFFFFFFF) }, "白字要照画,和 RN 版一样")
    val annotations =
      segment.text.getStringAnnotations(BBCodeAnnotation.SPOILER, 0, segment.text.length)
    assertEquals(1, annotations.size)
    assertEquals(0, annotations[0].start)
    assertEquals("答案".length, annotations[0].end)
  }

  @Test
  fun `防剧透段按出现顺序编号 两段互不干扰`() {
    val segment = text("[color=white]甲[/color]中间[color=white]乙[/color]")
    val annotations =
      segment.text.getStringAnnotations(BBCodeAnnotation.SPOILER, 0, segment.text.length)
    assertEquals(listOf("0", "1"), annotations.map { it.item })
  }

  @Test
  fun `普通白色以外的 color 不当防剧透`() {
    val segment = text("[color=red]红[/color]")
    assertEquals(
      0,
      segment.text.getStringAnnotations(BBCodeAnnotation.SPOILER, 0, segment.text.length).size,
    )
  }

  @Test
  fun `29 种样例逐个都建得出非空模型`() {
    for ((type, source) in COVERAGE_SAMPLES) {
      val built = model(source)
      assertTrue(built.segments.isNotEmpty(), "$type 的样例建出了空模型:$source")
    }
    assertEquals(29, COVERAGE_SAMPLES.size)
  }

  @Test
  fun `29 种样例拼成一段长正文 一次建模不抛不吞`() {
    val joined = COVERAGE_SAMPLES.values.joinToString("<br/>")
    val built = model(joined)
    val kinds = built.segments.map { it::class.simpleName }.toSet()
    for (expected in listOf(
      "QuoteSegment", "ImageSegment", "HeadingSegment", "AlignSegment", "CollapseSegment",
      "ListSegment", "TableSegment", "BoxSegment", "DiceSegment", "MediaSegment",
      "AttachSegment", "AlbumSegment",
    )) {
      assertTrue(expected in kinds, "长正文里少了 $expected;实际有 $kinds")
    }
    assertTrue(built.segments.any { it === DividerSegment })
    assertNotNull(built.segments.firstOrNull { it is TextSegment })
  }

  private companion object {
    val COVERAGE_SAMPLES: Map<String, String> = linkedMapOf(
      "text" to "一段字",
      "linebreak" to "上<br/>下",
      "bold" to "[b]粗[/b]",
      "italic" to "[i]斜[/i]",
      "underline" to "[u]下划线[/u]",
      "strike" to "[del]删除线[/del]",
      "color" to "[color=red]红[/color]",
      "size" to "[size=120%]大[/size]",
      "font" to "[font=宋体]宋体[/font]",
      "code" to "[code]const a = 1[/code]",
      "link" to "[url=https://example.test]站外[/url]",
      "userRef" to "[uid=123]某人[/uid]",
      "topicRef" to "[tid]45150945[/tid]",
      "floorRef" to "[pid=1,2,3]Reply[/pid]",
      "mention" to "[@某人]",
      "smiley" to "[s:ac:blink]",
      "quote" to "[quote]引用[/quote]",
      "image" to "[img]./mon_202608/07/a.jpg[/img]",
      "divider" to "======",
      "heading" to "===标题===",
      "align" to "[align=center]居中[/align]",
      "collapse" to "[collapse=提要]藏起来的话[/collapse]",
      "list" to "[list][*]甲[*]乙[/list]",
      "table" to "[table][tr][td]甲[/td][td]乙[/td][/tr][/table]",
      "box" to "[lessernuke]处罚说明[/lessernuke]",
      "dice" to "[dice]1d100[/dice]",
      "flash" to "[flash=video]./a.mp4[/flash]",
      "attach" to "[attach]./a.zip[/attach]",
      "album" to "[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]",
    )
  }
}
