package com.chasel.ng2n.core.bbcode

/**
 * 标签清单 = 功能文档 §2.9 的两边并集。直译 `src/core/bbcode/tags.ts`。
 * 加一个新标签只需要动这个文件:内容要当正文解析的进 [CONTAINER_BUILDERS],
 * 内容是原文的进 [RAW_BUILDERS]。
 *
 * **不支持**(整段降级成纯文本,靠解析器的「未知标签原样透传」兜底):
 * `pre` / `hide` / `spoiler` / `randomblock` / `email`。站上的防剧透实际是
 * `[color=white]`,不走 `spoiler`。投票也不在这里 —— `Floor.vote` 字段是
 * `~` 分隔的 kv,独立解析、兄弟节点渲染(票 10)。
 */

/** 把 frame 的内容折成一个 AST 节点(或只在解析期存在的中间节点)。 */
internal typealias ContainerBuilder = (OpenTag, List<ParseNode>) -> ParseNode

internal val CONTAINER_BUILDERS: Map<String, ContainerBuilder> = buildMap {
  put("b") { _, children -> BoldNode(normalize(children)) }
  put("i") { _, children -> ItalicNode(normalize(children)) }
  put("u") { _, children -> UnderlineNode(normalize(children)) }
  put("del") { _, children -> StrikeNode(normalize(children)) }
  put("color") { open, children -> ColorNode(open.value ?: "", normalize(children)) }
  put("size") { open, children -> SizeNode(open.value ?: "", normalize(children)) }
  put("font") { open, children -> FontNode(open.value ?: "", normalize(children)) }

  put("quote") { _, children -> QuoteNode(normalize(children)) }
  put("collapse") { open, children ->
    // 无参 `[collapse]` 与空参 `[collapse=]` 都算「没标题」——title 字段整个缺席
    CollapseNode(normalize(children), open.value?.takeIf { it.isNotEmpty() })
  }

  put("align") { open, children -> AlignNode(toAlign(open.value), normalize(children)) }
  put("l") { _, children -> AlignNode(Align.LEFT, normalize(children)) }
  put("r") { _, children -> AlignNode(Align.RIGHT, normalize(children)) }
  put("h") { _, children -> HeadingNode(normalize(children)) }

  put("list") { open, children ->
    ListNode(
      ordered = open.value != null && open.value.isNotEmpty(),
      items = children.filterIsInstance<ListItemNode>()
        .map { item -> normalize(trimEdges(item.children)) },
    )
  }
  put("*") { _, children -> ListItemNode(children) }

  put("table") { _, children ->
    TableNode(children.filterIsInstance<TableRowFrame>().map(::toTableRow))
  }
  put("tr") { _, children -> TableRowFrame(children) }
  put("td") { open, children ->
    TableCellFrame(
      colspan = toSpan(open.attrs?.get("colspan")),
      rowspan = toSpan(open.attrs?.get("rowspan")),
      width = open.attrs?.get("width"),
      children = children,
    )
  }

  put("url") { open, children -> LinkNode(open.value ?: "", normalize(children)) }
  put("uid") { open, children -> UserRefNode(open.value ?: "", normalize(children)) }
  put("tid") { open, children -> TopicRefNode(open.value ?: "", normalize(children)) }
  put("pid") { open, children ->
    val args = (open.value ?: "").split(',')
    FloorRefNode(pid = args[0], args = args, children = normalize(children))
  }
  put("@") { _, children -> MentionNode(plainText(normalize(children))) }

  // 官方把处罚种类写在标签名末尾那一位数字上,`[lessernuke]` 与 `[lessernuke1]` 等价
  put("lessernuke", nukeBox(Punishment.POST))
  put("lessernuke1", nukeBox(Punishment.POST))
  put("lessernuke2", nukeBox(Punishment.TOPIC))
  put("lessernuke3", nukeBox(Punishment.LOCKED))
  put("hip") { _, children -> BoxNode(BoxVariant.HIP, normalize(children)) }
  put("item") { _, children -> BoxNode(BoxVariant.ITEM, normalize(children)) }
  put("stripbr") { _, children ->
    FragmentNode(children.filter { it !== LineBreakNode })
  }
}

/** 把标签内的原文折成节点。用于内容不解析标签的那些标签。 */
internal typealias RawBuilder = (OpenTag, String) -> BBCodeNode

internal val RAW_BUILDERS: Map<String, RawBuilder> = buildMap {
  put("code") { _, value -> CodeNode(value) }
  put("img") { _, value -> toAttachmentRef(value).let { ImageNode(it.first, it.second, ImageVariant.IMG) } }
  put("noimg") { _, value -> toAttachmentRef(value).let { ImageNode(it.first, it.second, ImageVariant.NOIMG) } }
  put("attach") { _, value -> toAttachmentRef(value).let { AttachNode(it.first, it.second) } }
  put("album") { _, value -> AlbumNode(value.jsTrim()) }
  put("flash") { open, value ->
    toAttachmentRef(value).let { FlashNode(it.first, it.second, toMedia(open.value)) }
  }
  put("dice") { _, value -> DiceNode(value.jsTrim()) }
  put("url") { _, value -> LinkNode(value.jsTrim(), emptyList()) }
  put("uid") { _, value -> UserRefNode(value.jsTrim(), emptyList()) }
  put("tid") { _, value -> TopicRefNode(value.jsTrim(), emptyList()) }
  put("pid") { _, value -> FloorRefNode(value.jsTrim(), listOf(value.jsTrim()), emptyList()) }
}

/**
 * 不带 `=` 参数时内容才是原文:`[url]地址[/url]` 的内容是地址,而 `[url=地址]文字[/url]`
 * 的文字要当正文解析。其余 [RAW_BUILDERS] 里的标签内容永远是原文。
 */
private val RAW_WHEN_BARE_TAGS = setOf("url", "uid", "tid", "pid")

internal fun isRawTag(open: OpenTag): Boolean {
  if (!RAW_BUILDERS.containsKey(open.name)) return false
  return open.name !in RAW_WHEN_BARE_TAGS || open.value == null
}

/**
 * 收尾时可以安全自闭合的结构性标签——它们只在父标签里有意义,缺闭标签属于 NGA 常态,
 * 不该像普通标签那样降级成文本。
 */
internal val SELF_CLOSING_TAGS = setOf("*", "tr", "td")

private fun nukeBox(punishment: Punishment): ContainerBuilder =
  { _, children -> BoxNode(BoxVariant.LESSERNUKE, normalize(children), punishment) }

private fun toAlign(value: String?): Align = when (value) {
  "center" -> Align.CENTER
  "right" -> Align.RIGHT
  else -> Align.LEFT
}

/** 带协议或以 `//` 开头的才是绝对地址;其余(含裸文件名)都要拼附件域名。 */
private val ABSOLUTE_URL = Regex("""^(?:[a-zA-Z][a-zA-Z0-9+.\-]*:|//)""")

/** → `(src, needsAttachBase)`。 */
private fun toAttachmentRef(value: String): Pair<String, Boolean> {
  val trimmed = value.jsTrim()
  if (ABSOLUTE_URL.containsMatchIn(trimmed)) return trimmed to false
  val src = if (trimmed.startsWith("./")) trimmed.substring(2) else trimmed
  return src to src.isNotEmpty()
}

private fun toMedia(value: String?): FlashMedia = when (value) {
  "video" -> FlashMedia.VIDEO
  "audio" -> FlashMedia.AUDIO
  else -> FlashMedia.FLASH
}

private fun toSpan(value: String?): Int {
  val span = jsParseInt(value) ?: return 1
  return if (span > 0) span else 1
}

private fun toTableRow(row: TableRowFrame): TableRow = TableRow(
  row.children.filterIsInstance<TableCellFrame>().map { cell ->
    TableCell(
      colspan = cell.colspan,
      rowspan = cell.rowspan,
      children = normalize(trimEdges(cell.children)),
      width = cell.width,
    )
  },
)

/** 丢掉首尾游离的换行与纯空白文本——NGA 的结构标签之间到处是这种缩进。 */
private fun trimEdges(nodes: List<ParseNode>): List<ParseNode> {
  var start = 0
  var end = nodes.size
  while (start < end && isBlank(nodes[start])) start++
  while (end > start && isBlank(nodes[end - 1])) end--
  return if (start == 0 && end == nodes.size) nodes else nodes.subList(start, end)
}

private fun isBlank(node: ParseNode): Boolean =
  node === LineBreakNode || (node is TextNode && node.value.isJsBlank())
