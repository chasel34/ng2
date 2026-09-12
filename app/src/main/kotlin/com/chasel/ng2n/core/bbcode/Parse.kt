package com.chasel.ng2n.core.bbcode

const val MAX_NESTING_DEPTH: Int = 64

private class Frame(val open: OpenTag) {
  val children: MutableList<ParseNode> = ArrayList()
}

fun parseBBCode(source: String): List<BBCodeNode> {
  if (source.isEmpty()) return emptyList()

  val lowerSource = source.asciiLowercase()
  val root = Frame(ROOT_TAG)
  val stack = ArrayList<Frame>()
  stack.add(root)

  val pending = StringBuilder()

  var atLineStart = true
  var index = 0

  while (index < source.length) {
    val breakLength = matchLineBreak(source, index)
    if (breakLength > 0) {
      flushText(pending, stack)
      pushNode(stack, LineBreakNode)
      index += breakLength
      atLineStart = true
      continue
    }

    if (atLineStart && source[index] == '=') {
      val line = lineAt(source, index)
      val ruled = matchHeadingOrDivider(line)
      if (ruled != null) {
        flushText(pending, stack)
        pushNode(stack, ruled)
        index += line.length
        continue
      }
    }

    if (source[index] != '[') {
      val start = index
      index++
      while (index < source.length && !isScanBoundary(source[index])) index++
      pending.append(source, start, index)
      atLineStart = false
      continue
    }

    val close = matchCloseTag(source, index)
    if (close != null && findFrame(stack, close.name) > 0) {
      flushText(pending, stack)
      closeFrames(stack, close.name)
      index += close.length
      continue
    }

    val smiley = SMILEY_TAG.matchAt(source, index)
    if (smiley != null) {
      flushText(pending, stack)
      pushNode(stack, SmileyNode(smiley.groupValues[1]))
      index += smiley.value.length
      continue
    }

    val mention = MENTION_TAG.matchAt(source, index)
    if (mention != null) {
      flushText(pending, stack)
      pushNode(stack, MentionNode(unescapeNgaText(mention.groupValues[1])))
      index += mention.value.length
      continue
    }

    val open = matchOpenTag(source, index)

    if (open != null && open.name == "dice" && (open.attrText ?: "").jsTrim().isNotEmpty()) {
      flushText(pending, stack)
      pushNode(stack, DiceNode(open.attrText!!.jsTrim()))
      index += open.length
      continue
    }

    if (open != null && isRawTag(open)) {
      val raw = readRawTag(source, lowerSource, index, open, stack)
      if (raw != null) {
        flushText(pending, stack)
        pushNode(stack, raw.node)
        index += raw.length
        continue
      }
    }

    if (open != null && CONTAINER_BUILDERS.containsKey(open.name) && stack.size <= MAX_NESTING_DEPTH) {
      flushText(pending, stack)
      if (open.name in SELF_CLOSING_TAGS && stack[stack.size - 1].open.name == open.name) {
        buildFrame(stack)
      }
      stack.add(Frame(open))
      index += open.length
      continue
    }

    val literal = open?.raw ?: close?.raw
    if (literal != null) {
      pending.append(literal)
      index += literal.length
      continue
    }

    pending.append(source[index])
    index++
    atLineStart = false
  }

  flushText(pending, stack)
  while (stack.size > 1) degradeFrame(stack)
  return normalize(root.children)
}

private val ROOT_TAG = OpenTag(name = "", value = null, attrs = null, attrText = null, raw = "", length = 0)

private val LINE_BREAK_TAG = Regex("""<br[$JS_SPACE_CLASS]*/?>""", RegexOption.IGNORE_CASE)
private val ANY_LINE_BREAK_TAG = LINE_BREAK_TAG
private val LINE_END = Regex("""\r\n|\r|\n|<br[$JS_SPACE_CLASS]*/?>""", RegexOption.IGNORE_CASE)
private val OPEN_TAG = Regex("""\[([a-zA-Z*@][a-zA-Z0-9_]*)(?:(=|[$JS_SPACE_CLASS]+)([^\]]*))?\]""")
private val CLOSE_TAG = Regex("""\[/([a-zA-Z*@][a-zA-Z0-9_]*)[$JS_SPACE_CLASS]*\]""")
private val MENTION_TAG = Regex("""\[@([^\[\]]+)\]""")
private val SMILEY_TAG = Regex("""\[s:([^\[\]]+)\]""")
private val ATTR = Regex(
  "([a-zA-Z][a-zA-Z0-9_-]*)[$JS_SPACE_CLASS]*=[$JS_SPACE_CLASS]*\"([^\"]*)\"" +
    "|([a-zA-Z][a-zA-Z0-9_-]*)[$JS_SPACE_CLASS]*=[$JS_SPACE_CLASS]*([^$JS_SPACE_CLASS]+)",
)
private val DIVIDER_LINE = Regex("""={4,}""")

private val HEADING_LINE = Regex("={3,}([^\\n\\r\\u2028\\u2029]+?)={3,}")

private fun isScanBoundary(char: Char): Boolean =
  char == '\n' || char == '\r' || char == '<' || char == '['

private fun matchLineBreak(source: String, index: Int): Int {
  val char = source[index]
  if (char == '\n') return 1
  if (char == '\r') return if (index + 1 < source.length && source[index + 1] == '\n') 2 else 1
  if (char != '<') return 0
  return LINE_BREAK_TAG.matchAt(source, index)?.value?.length ?: 0
}

private fun lineAt(source: String, index: Int): String {
  val end = LINE_END.find(source, index)
  return source.substring(index, end?.range?.first ?: source.length)
}

private fun matchHeadingOrDivider(line: String): BBCodeNode? {
  if (DIVIDER_LINE.matches(line)) return DividerNode
  val heading = HEADING_LINE.matchEntire(line) ?: return null
  val title = heading.groupValues[1]
  if (title.isJsBlank()) return null
  return HeadingNode(parseBBCode(title))
}

private fun matchOpenTag(source: String, index: Int): OpenTag? {
  val match = OPEN_TAG.matchAt(source, index) ?: return null
  val separator = match.groups[2]?.value
  val rest = match.groups[3]?.value ?: ""
  val text = match.value
  return OpenTag(
    name = match.groupValues[1].asciiLowercase(),
    value = if (separator == "=") rest else null,
    attrs = if (separator != null && separator != "=") parseAttrs(rest) else null,
    attrText = if (separator != null && separator != "=") rest else null,
    raw = text,
    length = text.length,
  )
}

private class CloseTag(val name: String, val raw: String, val length: Int)

private fun matchCloseTag(source: String, index: Int): CloseTag? {
  val match = CLOSE_TAG.matchAt(source, index) ?: return null
  return CloseTag(match.groupValues[1].asciiLowercase(), match.value, match.value.length)
}

private fun parseAttrs(input: String): Map<String, String> {
  val attrs = LinkedHashMap<String, String>()
  for (match in ATTR.findAll(input)) {
    val key = (match.groups[1] ?: match.groups[3])!!.value.asciiLowercase()
    attrs[key] = (match.groups[2] ?: match.groups[4])!!.value
  }
  return attrs
}

private class RawTag(val node: BBCodeNode, val length: Int)

private fun readRawTag(
  source: String,
  lowerSource: String,
  index: Int,
  open: OpenTag,
  stack: List<Frame>,
): RawTag? {
  val contentStart = index + open.length
  val closeIndex = lowerSource.indexOf("[/${open.name}]", contentStart)
  if (closeIndex < 0) return null
  if (closeIndex > enclosingCloseIndex(lowerSource, contentStart, stack)) return null
  val raw = source.substring(contentStart, closeIndex)
  val value = unescapeNgaText(raw.replace(ANY_LINE_BREAK_TAG, "\n"))
  return RawTag(
    node = RAW_BUILDERS.getValue(open.name)(open, value),
    length = closeIndex + open.name.length + 3 - index,
  )
}

private fun enclosingCloseIndex(lowerSource: String, from: Int, stack: List<Frame>): Int {
  var earliest = Int.MAX_VALUE
  for (i in 1 until stack.size) {
    val at = lowerSource.indexOf("[/${stack[i].open.name}]", from)
    if (at in 0 until earliest) earliest = at
  }
  return earliest
}

private fun findFrame(stack: List<Frame>, name: String): Int {
  for (i in stack.size - 1 downTo 1) {
    if (stack[i].open.name == name) return i
  }
  return 0
}

private fun closeFrames(stack: MutableList<Frame>, name: String) {
  val target = findFrame(stack, name)
  while (stack.size - 1 > target) degradeFrame(stack)
  buildFrame(stack)
}

private fun buildFrame(stack: MutableList<Frame>) {
  val frame = stack.removeAt(stack.size - 1)
  pushNode(stack, CONTAINER_BUILDERS.getValue(frame.open.name)(frame.open, frame.children))
}

private fun degradeFrame(stack: MutableList<Frame>) {
  if (stack[stack.size - 1].open.name in SELF_CLOSING_TAGS) {
    buildFrame(stack)
    return
  }
  val frame = stack.removeAt(stack.size - 1)
  pushNode(stack, TextNode(unescapeNgaText(frame.open.raw)))
  for (node in frame.children) pushNode(stack, node)
}

private fun pushNode(stack: List<Frame>, node: ParseNode) {
  stack[stack.size - 1].children.add(node)
}

private fun flushText(pending: StringBuilder, stack: List<Frame>) {
  if (pending.isEmpty()) return
  val value = unescapeNgaText(pending.toString())
  pending.setLength(0)
  if (value.isNotEmpty()) pushNode(stack, TextNode(value))
}
