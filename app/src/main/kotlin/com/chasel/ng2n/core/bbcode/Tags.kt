package com.chasel.ng2n.core.bbcode

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

private val RAW_WHEN_BARE_TAGS = setOf("url", "uid", "tid", "pid")

internal fun isRawTag(open: OpenTag): Boolean {
  if (!RAW_BUILDERS.containsKey(open.name)) return false
  return open.name !in RAW_WHEN_BARE_TAGS || open.value == null
}

internal val SELF_CLOSING_TAGS = setOf("*", "tr", "td")

private fun nukeBox(punishment: Punishment): ContainerBuilder =
  { _, children -> BoxNode(BoxVariant.LESSERNUKE, normalize(children), punishment) }

private fun toAlign(value: String?): Align = when (value) {
  "center" -> Align.CENTER
  "right" -> Align.RIGHT
  else -> Align.LEFT
}

private val ABSOLUTE_URL = Regex("""^(?:[a-zA-Z][a-zA-Z0-9+.\-]*:|//)""")

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

private fun trimEdges(nodes: List<ParseNode>): List<ParseNode> {
  var start = 0
  var end = nodes.size
  while (start < end && isBlank(nodes[start])) start++
  while (end > start && isBlank(nodes[end - 1])) end--
  return if (start == 0 && end == nodes.size) nodes else nodes.subList(start, end)
}

private fun isBlank(node: ParseNode): Boolean =
  node === LineBreakNode || (node is TextNode && node.value.isJsBlank())
