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

/**
 * 渲染一段 BBCode 需要的、正文本身给不出的东西——全都来自楼层或它所在的那一页
 * (RN 侧原件 `src/ui/bbcode/options.ts`)。
 *
 * @property attachBase 附件图片基址,来自 `read.php` 的 `__GLOBAL._ATTACH_BASE_VIEW`
 *   (每页都可能变),已经过 `normalizeAttachBase`
 * @property postedAt 所在楼层的发帖时间(秒),`[noimg]` 的相对路径要靠它补 `mon_YYYYMM/DD/`
 * @property dice 骰子点数,**按文档顺序**排好的一串。RN 那边是按节点身份查
 *   `Map<DiceNode, DiceOutcome>`,Kotlin 的 data class 是结构相等,同一楼里两个
 *   `[dice]d100[/dice]` 会撞成同一个 key —— 而「写法一样、点数不同」正是要区分的场景。
 *   改成按顺序取第 n 个,与 NGA「一楼内所有 [dice] 共用一条随机流按文档顺序推进」
 *   的口径本来就是一回事。
 * @property colors 当前配色。`AnnotatedString` 里的链接色、次级色都要在建模期定死,
 *   所以配色是建模输入;换深浅色会重建模型(不在滚动路径上)。
 * @property bodyFontSize 正文字号(「帖子内字体大小」滑块)。`[size=150%]` 这类相对字号
 *   按它算——**不是按当前上下文的字号**,照抄 RN 版:引用块里的 `[size=150%]`
 *   同样以正文字号为基准。
 * @property bodyLineHeight 正文行高倍数
 * @property attachmentUrls 地址拼装(票 10 的地盘,见 `core/api/AttachmentUrls.kt`)
 */
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

/**
 * AST → 渲染成品。**纯 Kotlin,不碰 composition**,所以能在后台线程跑、能 JVM 单测。
 *
 * 与 RN 版的分工差别就在这一层:RN 是「渲染时递归 AST」,这里是「建模时递归一次,
 * 渲染时只贴」。anzong 那份研读报告里「顺」的第一条就是**滚动路径零计算**,
 * 它靠的是后台把 BBCode 转成 HTML 一次转完;我们做同一件事,载体换成原生文本栈。
 *
 * 用法(票 13):
 * ```
 * val model = withContext(Dispatchers.Default) {
 *   RenderModelBuilder.build(parseBBCode(floor.content), options)
 * }
 * BBCodeContent(model = model, callbacks = …)
 * ```
 */
object RenderModelBuilder {

  fun build(ast: List<BBCodeNode>, options: BBCodeRenderOptions): FloorRenderModel =
    Session(options).build(ast, Session.rootStyle(options))

  /**
   * 一次建模的可变状态。两个计数器都必须**按文档顺序**推进,所以整棵树共用一个 session:
   * 骰子按顺序对齐点数,防剧透段按顺序编号(渲染层用序号记「这一段翻开了没」)。
   */
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

    // -----------------------------------------------------------------------
    // 行内
    // -----------------------------------------------------------------------

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
      // 外层行内标签(`[b][img]…[/b]` 那种被升格成块的)贡献的样式在这里补回来,
      // 不然块级内容里的文字会丢掉粗体/颜色
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

      /** 推一层 span,把 children 写进去,再弹出来。 */
      fun span(span: SpanStyle?, children: List<BBCodeNode>) {
        if (span == null) {
          appendNodes(builder, children, style, smilies)
          return
        }
        builder.pushStyle(span)
        appendNodes(builder, children, style, smilies)
        builder.pop()
      }

      /** 站内引用那几个:链接样式 + 一条 annotation;标签没写内容就拿参数当内容。 */
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
          // 防剧透:`[color=white]` 在奶油/近黑底上都读不出来,是 NGA 上藏答案的写法。
          // 白字照画(与 RN 版逐像素一致),另外钉一条 annotation —— 渲染层据此
          // 让人点一下把它翻出来(RN 版没有这个入口,见票 11 Comments 的「有意偏离」)。
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
          // 基准是用户设的正文字号,不是当前上下文字号——否则把正文调大之后,
          // 引用块里的 `[size=150%]` 反而比正文小(RN 版同)。
          //
          // **与 RN 版的一处偏离**:RN 同时放大 lineHeight,Compose 的行高是段落属性
          // (ParagraphStyle),没法只作用在一段 span 上。行高跟着整段走,
          // 大字在密排行里会略挤——见票 11 Comments。
          val scale = resolveBBSizeScale(node.value)
          span(
            if (scale == null) null else SpanStyle(fontSize = (options.bodyFontSize * scale).sp),
            node.children,
          )
        }

        // 字体名基本是 Windows 字体,Android 上没有;按票 09 的约定只渲染内容
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
          // 参数原样带走:回复链(票 10/13)要用后面的 tid 与页码
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

        // 剩下的全是块级类型(`Segments.kt` 的那张表),按理走不到这里——
        // 真走到了说明两张表不同步,那也宁可把内容原样吐出来,不能吞字。
        else -> if (node is ChildBearing) appendNodes(builder, node.children, style, smilies)
      }
    }

    /**
     * `[s:分类:名称]`。查表三级兜底:随包图片 → CDN 远程 URL → 原文
     * ([resolveSmiley] 已经把三种情况分好,这里只负责放占位)。
     */
    private fun appendSmiley(
      builder: AnnotatedString.Builder,
      node: SmileyNode,
      colors: Ng2nColors,
      smilies: MutableMap<String, SmileyPlacement>,
    ) {
      when (val smiley = resolveSmiley(node.code)) {
        is ResolvedSmiley.Unresolved -> {
          // 查不到的表情原样显示原文,用次级色标出来「这不是正文」
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
          // alternateText 是无障碍朗读与「复制正文」时的替身,写回原文最诚实
          builder.appendInlineContent(smiley.file, "[s:${node.code}]")
        }
        is ResolvedSmiley.Remote -> {
          // 表里有但图片没随包(官方新加的):拿不到原始尺寸,按正方形占位,与 RN 版同
          smilies.getOrPut(smiley.file) {
            SmileyPlacement(id = smiley.file, url = smiley.remoteUrl, aspect = 1f)
          }
          builder.appendInlineContent(smiley.file, "[s:${node.code}]")
        }
      }
    }

    // -----------------------------------------------------------------------
    // 块级
    // -----------------------------------------------------------------------

    private fun buildBlock(node: BBCodeNode, style: BodyStyle): RenderSegment {
      val colors = options.colors
      val urls = options.attachmentUrls
      val attach = options.attachOptions

      // 引用的另一种写法:快速回复给正文开头塞一段
      // `[b]Reply to [pid=…]Reply[/pid] Post by 谁 (时间)[/b]`,没有 [quote] 容器。
      // 只按节点类型分派的话它就落进 `[b]` 分支,变成正文顶上一行加粗英文,
      // 跟这一楼自己说的话糊成一片——它跟引用块是一回事,画成同一张卡片。
      if (isReplyHeaderNode(node) && node is BoldNode) {
        return QuoteSegment(
          body = build(node.children, style.asQuote(colors)),
          chain = replyHeaderRefOf(node),
        )
      }

      return when (node) {
        is QuoteNode -> QuoteSegment(
          // 引用块里那句「Post by 谁 (时间)」是服务端塞在 BBCode 里的,原样渲染就够,
          // 不另外合成一行标题——合成的话作者名会重复出现两遍
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

        // 剩下的都是「裹着块级内容的行内标签」(见 splitIntoSegments):
        // 递归展开内容,并把这一层的文字样式往下带,行内部分的粗体/颜色/字号不丢。
        else -> if (node is ChildBearing) {
          GroupSegment(build(node.children, style.inheriting(inlineSpanOf(node))))
        } else {
          // 走不到:非 ChildBearing 的块级类型上面已经全部枚举过
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

/**
 * 一层正文的底样式。建模时逐层往下带,落到每个 [TextSegment] 上。
 *
 * @property inherited 「行内标签裹着块级内容」时,外层标签贡献的 span
 *   (`[b][img]…[/b]` 里那个 `[b]`)。RN 那边是渲染时把 style 往下递,这里在建模期压进去。
 */
@Immutable
internal data class BodyStyle(
  val fontSize: TextUnit,
  val lineHeight: TextUnit,
  val color: Color,
  val textAlign: TextAlign? = null,
  val inherited: SpanStyle? = null,
) {
  /** 引用块里的正文比楼层正文小一档、颜色压到次级(RN 侧 `styles.quoteText`)。 */
  fun asQuote(colors: Ng2nColors): BodyStyle = copy(
    fontSize = Typo.quoteBody.size,
    lineHeight = Typo.quoteBody.lineHeight,
    color = colors.fg2,
  )

  /** 表格里的字比正文小一档,不然固定列宽装不下几个字(RN 侧 `styles.tableText`)。 */
  fun asTableCell(colors: Ng2nColors): BodyStyle = copy(
    fontSize = Typo.quoteBody.size,
    lineHeight = Typo.quoteBody.lineHeight,
    color = colors.fg,
  )

  /** `[h]`:一条带下划线的小标题(RN 侧 `styles.headingText`)。 */
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

/** 链接那一档:主题色 + 下划线(RN 侧 `styles.link`)。 */
private fun linkSpan(colors: Ng2nColors) =
  SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)

/** 一个行内容器节点自己贡献的文字样式(往块级内容里递的那份;RN 侧 `inlineStyleOf`)。 */
private fun inlineSpanOf(node: BBCodeNode): SpanStyle? = when (node) {
  is BoldNode -> SpanStyle(fontWeight = FontWeight.Bold)
  is ItalicNode -> SpanStyle(fontStyle = FontStyle.Italic)
  is UnderlineNode -> SpanStyle(textDecoration = TextDecoration.Underline)
  is StrikeNode -> SpanStyle(textDecoration = TextDecoration.LineThrough)
  is ColorNode -> resolveBBColor(node.value)?.let { SpanStyle(color = it) }
  else -> null
}

/** 官方 `ubbcode.lesserNuke` 的三句提示语,按标签名末尾那位数字挑。 */
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

/** 卡片副标题用的文件名。查询串和路径都去掉,剩下的就是人能认的那截。 */
internal fun fileNameOf(uri: String): String {
  val path = uri.takeWhile { it != '?' && it != '#' }
  return path.substring(path.lastIndexOf('/') + 1).ifEmpty { uri }
}
