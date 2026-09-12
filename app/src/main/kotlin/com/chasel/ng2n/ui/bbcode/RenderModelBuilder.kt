package com.chasel.ng2n.ui.bbcode

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.core.api.AttachmentUrlOptions
import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.DefaultAttachmentUrls
import com.chasel.ng2n.core.bbcode.AlbumNode
import com.chasel.ng2n.core.bbcode.Align
import com.chasel.ng2n.core.bbcode.AlignNode
import com.chasel.ng2n.core.bbcode.AttachNode
import com.chasel.ng2n.core.bbcode.BBCodeNode
import com.chasel.ng2n.core.bbcode.BoldNode
import com.chasel.ng2n.core.bbcode.BoxNode
import com.chasel.ng2n.core.bbcode.BoxVariant
import com.chasel.ng2n.core.bbcode.ChildBearing
import com.chasel.ng2n.core.bbcode.CodeNode
import com.chasel.ng2n.core.bbcode.CollapseNode
import com.chasel.ng2n.core.bbcode.ColorNode
import com.chasel.ng2n.core.bbcode.DiceNode
import com.chasel.ng2n.core.bbcode.DividerNode
import com.chasel.ng2n.core.bbcode.FlashMedia
import com.chasel.ng2n.core.bbcode.FlashNode
import com.chasel.ng2n.core.bbcode.FloorRefNode
import com.chasel.ng2n.core.bbcode.FontNode
import com.chasel.ng2n.core.bbcode.HeadingNode
import com.chasel.ng2n.core.bbcode.ImageNode
import com.chasel.ng2n.core.bbcode.ItalicNode
import com.chasel.ng2n.core.bbcode.LineBreakNode
import com.chasel.ng2n.core.bbcode.LinkNode
import com.chasel.ng2n.core.bbcode.ListNode
import com.chasel.ng2n.core.bbcode.MentionNode
import com.chasel.ng2n.core.bbcode.Punishment
import com.chasel.ng2n.core.bbcode.QuoteNode
import com.chasel.ng2n.core.bbcode.SizeNode
import com.chasel.ng2n.core.bbcode.SmileyNode
import com.chasel.ng2n.core.bbcode.StrikeNode
import com.chasel.ng2n.core.bbcode.TableNode
import com.chasel.ng2n.core.bbcode.TextNode
import com.chasel.ng2n.core.bbcode.TopicRefNode
import com.chasel.ng2n.core.bbcode.UnderlineNode
import com.chasel.ng2n.core.bbcode.UserRefNode
import com.chasel.ng2n.core.local.DiceOutcome
import com.chasel.ng2n.ui.theme.DEFAULT_BODY_FONT_SIZE
import com.chasel.ng2n.ui.theme.DEFAULT_BODY_LINE_HEIGHT
import com.chasel.ng2n.ui.theme.LightColors
import com.chasel.ng2n.ui.theme.MonoFontFamily
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class BBCodeRenderOptions(
  val attachBase: String,
  val postedAt: Long? = null,
  val dice: ImmutableList<DiceOutcome> = persistentListOf(),
  val colors: Ng2nColors = LightColors,
  val bodyFontSize: Float = DEFAULT_BODY_FONT_SIZE,
  val bodyLineHeight: Float = DEFAULT_BODY_LINE_HEIGHT,
  val attachmentUrls: AttachmentUrls = DefaultAttachmentUrls,
) {
  internal val attachOptions: AttachmentUrlOptions
    get() = AttachmentUrlOptions(base = attachBase, postedAt = postedAt)
}

object RenderModelBuilder {

  fun build(ast: List<BBCodeNode>, options: BBCodeRenderOptions): FloorRenderModel =
    Session(options).build(ast, Session.rootStyle(options))

  private class Session(private val options: BBCodeRenderOptions) {

    private var diceIndex = 0
    private var spoilerIndex = 0

    fun build(nodes: List<BBCodeNode>, style: BodyStyle): FloorRenderModel {
      val segments = ArrayList<RenderSegment>()
      for (segment in splitIntoSegments(nodes)) {
        when (segment) {
          is Segment.Inline -> segments.add(buildInline(segment.nodes, style))
          is Segment.Block -> segments.add(buildBlock(segment.node, style))
        }
      }
      return FloorRenderModel(segments.toImmutableList())
    }

    private fun buildInline(nodes: List<BBCodeNode>, style: BodyStyle): TextSegment {
      val smilies = LinkedHashMap<String, SmileyPlacement>()
      val text = buildAnnotatedString(nodes, style, smilies)
      return TextSegment(
        text = text,
        smilies = smilies.values.toImmutableList(),
        fontSize = style.fontSize,
        lineHeight = style.lineHeight,
        color = style.color,
        textAlign = style.textAlign,
      )
    }

    private fun buildAnnotatedString(
      nodes: List<BBCodeNode>,
      style: BodyStyle,
      smilies: MutableMap<String, SmileyPlacement>,
    ): AnnotatedString {
      val builder = AnnotatedString.Builder()
      if (style.inherited != null) builder.pushStyle(style.inherited)
      appendNodes(builder, nodes, style, smilies)
      if (style.inherited != null) builder.pop()
      return builder.toAnnotatedString()
    }

    private fun appendNodes(
      builder: AnnotatedString.Builder,
      nodes: List<BBCodeNode>,
      style: BodyStyle,
      smilies: MutableMap<String, SmileyPlacement>,
    ) {
      for (node in nodes) appendNode(builder, node, style, smilies)
    }

    private fun appendNode(
      builder: AnnotatedString.Builder,
      node: BBCodeNode,
      style: BodyStyle,
      smilies: MutableMap<String, SmileyPlacement>,
    ) {
      val colors = options.colors

      fun span(span: SpanStyle?, children: List<BBCodeNode>) {
        if (span == null) {
          appendNodes(builder, children, style, smilies)
          return
        }
        builder.pushStyle(span)
        appendNodes(builder, children, style, smilies)
        builder.pop()
      }

      fun reference(tag: String, annotation: String, fallback: String, children: List<BBCodeNode>) {
        builder.pushStringAnnotation(tag, annotation)
        builder.pushStyle(linkSpan(colors))
        if (children.isEmpty()) builder.append(fallback) else {
          appendNodes(builder, children, style, smilies)
        }
        builder.pop()
        builder.pop()
      }

      when (node) {
        is TextNode -> builder.append(node.value)
        LineBreakNode -> builder.append('\n')
        is BoldNode -> span(SpanStyle(fontWeight = FontWeight.Bold), node.children)
        is ItalicNode -> span(SpanStyle(fontStyle = FontStyle.Italic), node.children)
        is UnderlineNode ->
          span(SpanStyle(textDecoration = TextDecoration.Underline), node.children)
        is StrikeNode ->
          span(SpanStyle(textDecoration = TextDecoration.LineThrough), node.children)

        is ColorNode -> {
          val color = resolveBBColor(node.value)
          if (isSpoilerColor(node.value)) {
            builder.pushStringAnnotation(BBCodeAnnotation.SPOILER, spoilerIndex.toString())
            spoilerIndex += 1
            span(if (color == null) null else SpanStyle(color = color), node.children)
            builder.pop()
          } else {
            span(if (color == null) null else SpanStyle(color = color), node.children)
          }
        }

        is SizeNode -> {
          val scale = resolveBBSizeScale(node.value)
          span(
            if (scale == null) null else SpanStyle(fontSize = (options.bodyFontSize * scale).sp),
            node.children,
          )
        }

        is FontNode -> span(null, node.children)

        is LinkNode -> {
          builder.pushStringAnnotation(BBCodeAnnotation.LINK, node.href)
          builder.pushStyle(linkSpan(colors))
          if (node.children.isEmpty()) builder.append(node.href) else {
            appendNodes(builder, node.children, style, smilies)
          }
          builder.pop()
          builder.pop()
        }

        is UserRefNode ->
          reference(BBCodeAnnotation.USER, node.uid, node.uid, node.children)
        is TopicRefNode ->
          reference(BBCodeAnnotation.TOPIC, node.tid, "#${node.tid}", node.children)
        is FloorRefNode -> reference(
          BBCodeAnnotation.FLOOR,
          node.args.ifEmpty { listOf(node.pid) }.joinToString(","),
          "#${node.pid}",
          node.children,
        )

        is MentionNode -> {
          builder.pushStringAnnotation(BBCodeAnnotation.MENTION, node.username)
          builder.pushStyle(linkSpan(colors))
          builder.append("@${node.username}")
          builder.pop()
          builder.pop()
        }

        is SmileyNode -> appendSmiley(builder, node, colors, smilies)

        is CodeNode -> {
          builder.pushStyle(SpanStyle(fontFamily = MonoFontFamily, color = colors.fg2))
          builder.append(node.value)
          builder.pop()
        }

        else -> if (node is ChildBearing) appendNodes(builder, node.children, style, smilies)
      }
    }

    private fun appendSmiley(
      builder: AnnotatedString.Builder,
      node: SmileyNode,
      colors: Ng2nColors,
      smilies: MutableMap<String, SmileyPlacement>,
    ) {
      when (val smiley = resolveSmiley(node.code)) {
        is ResolvedSmiley.Unresolved -> {
          builder.pushStyle(SpanStyle(color = colors.meta))
          builder.append(smiley.raw)
          builder.pop()
        }
        is ResolvedSmiley.Bundled -> {
          val size = BUNDLED_SMILEY_SIZES[smiley.file]
          val aspect = if (size == null || size.height == 0) 1f else {
            size.width.toFloat() / size.height.toFloat()
          }
          smilies.getOrPut(smiley.file) {
            SmileyPlacement(id = smiley.file, url = smiley.assetUrl, aspect = aspect)
          }
          builder.appendInlineContent(smiley.file, "[s:${node.code}]")
        }
        is ResolvedSmiley.Remote -> {
          smilies.getOrPut(smiley.file) {
            SmileyPlacement(id = smiley.file, url = smiley.remoteUrl, aspect = 1f)
          }
          builder.appendInlineContent(smiley.file, "[s:${node.code}]")
        }
      }
    }

    private fun buildBlock(node: BBCodeNode, style: BodyStyle): RenderSegment {
      val colors = options.colors
      val urls = options.attachmentUrls
      val attach = options.attachOptions

      if (isReplyHeaderNode(node) && node is BoldNode) {
        return QuoteSegment(
          body = build(node.children, style.asQuote(colors)),
          chain = replyHeaderRefOf(node),
        )
      }

      return when (node) {
        is QuoteNode -> QuoteSegment(
          body = build(node.children, style.asQuote(colors)),
          chain = quoteRefOf(node),
        )

        is ImageNode -> imageSegment(urls.attachmentUrl(node, attach))

        DividerNode -> DividerSegment

        is HeadingNode -> HeadingSegment(
          build(node.children, style.asHeading(colors)),
        )

        is AlignNode -> AlignSegment(
          align = node.align,
          body = build(node.children, style.withAlign(node.align)),
        )

        is CollapseNode -> CollapseSegment(
          title = node.title ?: "折叠的内容",
          body = build(node.children, style),
        )

        is BoxNode -> BoxSegment(
          variant = node.variant,
          notice = if (node.variant == BoxVariant.LESSERNUKE) {
            PUNISHMENT_NOTICES.getValue(node.punishment ?: Punishment.POST)
          } else {
            null
          },
          body = build(node.children, style),
        )

        is ListNode -> ListSegment(
          ordered = node.ordered,
          items = node.items.map { build(it, style) }.toImmutableList(),
        )

        is TableNode -> tableSegment(node, style, colors)

        is DiceNode -> DiceSegment(
          expression = node.expression,
          outcome = options.dice.getOrNull(diceIndex++),
        )

        is FlashNode -> {
          val url = urls.attachmentUrl(node, attach)
          MediaSegment(url = url, label = MEDIA_LABELS.getValue(node.media), fileName = fileNameOf(url))
        }

        is AttachNode -> {
          val url = urls.attachmentUrl(node, attach)
          AttachSegment(url = url, fileName = fileNameOf(url))
        }

        is AlbumNode -> AlbumSegment(
          images = albumImageUrls(node.value, attach, urls).map(::imageSegment).toImmutableList(),
        )

        else -> if (node is ChildBearing) {
          GroupSegment(build(node.children, style.inheriting(inlineSpanOf(node))))
        } else {
          GroupSegment(FloorRenderModel(persistentListOf()))
        }
      }
    }

    private fun imageSegment(url: String): ImageSegment {
      val thumbnail = options.attachmentUrls.thumbnailUrl(url, options.attachBase)
      return ImageSegment(url = url, thumbnailUrl = if (thumbnail == url) null else thumbnail)
    }

    private fun tableSegment(node: TableNode, style: BodyStyle, colors: Ng2nColors): TableSegment {
      val columnCount = tableColumnCount(node.rows)
      val cellStyle = style.asTableCell(colors)
      return TableSegment(
        rows = node.rows.map { row ->
          TableRowModel(
            cells = row.cells.map { cell ->
              TableCellModel(
                width = tableCellWidth(cell.colspan),
                body = build(cell.children, cellStyle),
              )
            }.toImmutableList(),
            paddingCells = tablePaddingCells(row, columnCount),
          )
        }.toImmutableList(),
      )
    }

    companion object {
      fun rootStyle(options: BBCodeRenderOptions): BodyStyle = BodyStyle(
        fontSize = options.bodyFontSize.sp,
        lineHeight = (options.bodyFontSize * options.bodyLineHeight).sp,
        color = options.colors.fg,
      )
    }
  }
}

@Immutable
internal data class BodyStyle(
  val fontSize: TextUnit,
  val lineHeight: TextUnit,
  val color: Color,
  val textAlign: TextAlign? = null,
  val inherited: SpanStyle? = null,
) {
  fun asQuote(colors: Ng2nColors): BodyStyle = copy(
    fontSize = Typo.quoteBody.size,
    lineHeight = Typo.quoteBody.lineHeight,
    color = colors.fg2,
  )

  fun asTableCell(colors: Ng2nColors): BodyStyle = copy(
    fontSize = Typo.quoteBody.size,
    lineHeight = Typo.quoteBody.lineHeight,
    color = colors.fg,
  )

  fun asHeading(colors: Ng2nColors): BodyStyle = copy(
    fontSize = Typo.section.size,
    lineHeight = Typo.section.lineHeight,
    color = colors.fg,
    inherited = (inherited ?: SpanStyle()).merge(SpanStyle(fontWeight = FontWeight.Bold)),
  )

  fun withAlign(align: Align): BodyStyle = copy(
    textAlign = when (align) {
      Align.LEFT -> null
      Align.CENTER -> TextAlign.Center
      Align.RIGHT -> TextAlign.Right
    },
  )

  fun inheriting(span: SpanStyle?): BodyStyle =
    if (span == null) this else copy(inherited = (inherited ?: SpanStyle()).merge(span))
}

private fun linkSpan(colors: Ng2nColors) =
  SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)

private fun inlineSpanOf(node: BBCodeNode): SpanStyle? = when (node) {
  is BoldNode -> SpanStyle(fontWeight = FontWeight.Bold)
  is ItalicNode -> SpanStyle(fontStyle = FontStyle.Italic)
  is UnderlineNode -> SpanStyle(textDecoration = TextDecoration.Underline)
  is StrikeNode -> SpanStyle(textDecoration = TextDecoration.LineThrough)
  is ColorNode -> resolveBBColor(node.value)?.let { SpanStyle(color = it) }
  else -> null
}

private val PUNISHMENT_NOTICES: Map<Punishment, String> = mapOf(
  Punishment.POST to "用户因此帖中的发言被处罚",
  Punishment.TOPIC to "用户在主题中被处罚",
  Punishment.LOCKED to "被锁定账号发布的内容无法查看",
)

private val MEDIA_LABELS: Map<FlashMedia, String> = mapOf(
  FlashMedia.VIDEO to "视频",
  FlashMedia.AUDIO to "音频",
  FlashMedia.FLASH to "动画",
)

internal fun fileNameOf(uri: String): String {
  val path = uri.takeWhile { it != '?' && it != '#' }
  return path.substring(path.lastIndexOf('/') + 1).ifEmpty { uri }
}
