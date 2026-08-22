package com.chasel.ng2n.core.bbcode

/**
 * 把楼层 BBCode 原文解析成 AST。直译 `src/core/bbcode/parse.ts`。
 *
 * 单遍扫描 + 显式帧栈,**任何输入都返回节点数组,永不抛异常**:
 * - 未知标签(以及找不到开标签的闭标签)原样透传成文本;
 * - EOF 时还没闭合的标签,开标签文本原样保留、子节点上提到父级——一个字都不丢;
 * - 嵌套深度上限 [MAX_NESTING_DEPTH],超出的开标签一律当普通文本。
 *
 * 实体解码走 `unescapeNgaText`(NGA 的**双重**转义 + 按 UTF-16 码元解十进制实体 +
 * 孤立代理清洗),正文里的裸 HTML 除 `<br/>` 一律是字面文本。
 */

/**
 * 嵌套深度上限。正文来自服务端,谁也不保证不会拿到几千层嵌套的畸形内容,
 * 而 AST 的归一化是递归的——超过这个深度的开标签一律当普通文本。
 * 真实楼层的引用套引用撑死十来层,64 层留足了余量。
 */
const val MAX_NESTING_DEPTH: Int = 64

private class Frame(val open: OpenTag) {
  val children: MutableList<ParseNode> = ArrayList()
}

fun parseBBCode(source: String): List<BBCodeNode> {
  if (source.isEmpty()) return emptyList()

  /** 找闭标签时按小写比对,预先算一次,免得每个标签都把全文重新小写一遍。 */
  val lowerSource = source.asciiLowercase()
  val root = Frame(ROOT_TAG)
  val stack = ArrayList<Frame>()
  stack.add(root)

  /** 尚未落成节点的原始文本,攒着是为了让相邻文本自然合并。 */
  val pending = StringBuilder()

  /** 只有普通文本会打断「行首」,标签本身不算,免得 `[quote]===标题===` 认不出来。 */
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
      // 先吃掉当前这个字符(它已经不可能再当行首 `=` 用了),再把后面一整段
      // 「肯定不是标签、也不是换行」的字符一次性并进 pending:逐字符 append
      // 在几万字的长正文上是热点(TS 侧靠字符串 += 的引擎优化蒙混过去)。
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

    // `[dice XdY]` 没有闭标签,表达式藏在属性位。
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
      // `[*]` 之间没有闭标签,遇到下一个就把上一个收掉。
      if (open.name in SELF_CLOSING_TAGS && stack[stack.size - 1].open.name == open.name) {
        buildFrame(stack)
      }
      stack.add(Frame(open))
      index += open.length
      continue
    }

    // 未知标签(以及找不到对应开标签的闭标签)原样透传成文本。
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

// 一律从指定下标起锚定匹配(`Regex.matchAt` = TS 的 sticky `y` 标志):
// 全文可能上万字,每个标签都切一次子串会退化成 O(n²)。
//
// `\s` 一律换成 [JS_SPACE_CLASS] —— Java 正则的 `\s` 只有六个 ASCII 字符,
// 而 NGA 正文里 `&nbsp;`(U+00A0)与全角空格遍地都是,见 `JsCompat.kt` 的说明。
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

// JS 的 `.` 不匹配 U+000A / U+000D / U+2028 / U+2029(Java 的 `.` 还额外排除 U+0085,
// 所以这里写成显式字符类而不是 `.`)。
private val HEADING_LINE = Regex("={3,}([^\\n\\r\\u2028\\u2029]+?)={3,}")

/** 主循环里「一段普通文本」的终止字符:换行三兄弟的起手 + 标签起手。 */
private fun isScanBoundary(char: Char): Boolean =
  char == '\n' || char == '\r' || char == '<' || char == '['

private fun matchLineBreak(source: String, index: Int): Int {
  val char = source[index]
  if (char == '\n') return 1
  if (char == '\r') return if (index + 1 < source.length && source[index + 1] == '\n') 2 else 1
  if (char != '<') return 0
  return LINE_BREAK_TAG.matchAt(source, index)?.value?.length ?: 0
}

/** 取从 index 到本行结尾(不含换行)的文本。 */
private fun lineAt(source: String, index: Int): String {
  val end = LINE_END.find(source, index)
  return source.substring(index, end?.range?.first ?: source.length)
}

/** `======` 是分割线,`===标题===` 是标题,两者都要独占一行。 */
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

/**
 * 读一个内容不解析标签的标签(`[code]`、`[img]` 等),返回节点与整段消耗的长度。
 *
 * 找不到自己的闭标签,或者外层某个标签的闭标签来得更早,就返回 null——
 * 否则 `[quote][code]abc[/quote]` 会把 `[/quote]` 连同后面的正文一起吞掉。
 */
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

/** 栈上任一未闭合标签的闭标签,最早出现在哪里。 */
private fun enclosingCloseIndex(lowerSource: String, from: Int, stack: List<Frame>): Int {
  var earliest = Int.MAX_VALUE
  for (i in 1 until stack.size) {
    val at = lowerSource.indexOf("[/${stack[i].open.name}]", from)
    if (at in 0 until earliest) earliest = at
  }
  return earliest
}

/** 返回栈内最靠近栈顶的同名 frame 下标;0 表示没找到(0 是 root)。 */
private fun findFrame(stack: List<Frame>, name: String): Int {
  for (i in stack.size - 1 downTo 1) {
    if (stack[i].open.name == name) return i
  }
  return 0
}

/** 闭合到指定标签为止;跨过的未闭合 frame 按原样降级成文本。 */
private fun closeFrames(stack: MutableList<Frame>, name: String) {
  val target = findFrame(stack, name)
  while (stack.size - 1 > target) degradeFrame(stack)
  buildFrame(stack)
}

private fun buildFrame(stack: MutableList<Frame>) {
  val frame = stack.removeAt(stack.size - 1)
  pushNode(stack, CONTAINER_BUILDERS.getValue(frame.open.name)(frame.open, frame.children))
}

/** 未闭合标签:开标签文本原样保留,内容直接并入父节点,一个字都不丢。 */
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
