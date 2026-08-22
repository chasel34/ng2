package com.chasel.ng2n.core.local

import com.chasel.ng2n.core.bbcode.unescapeNgaText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **临时件:一个只认六种标签的极简 BBCode 抽取器。**
 *
 * 票 09(正式 `parseBBCode` + 29 种节点)与票 10 并行开工,合并前 `core/bbcode` 里
 * 还没有解析器,而 `dice` / `reply-chain` 两个 domain 的金样本 `input` 给的是**楼层
 * 正文原文**(README:「Kotlin 侧要先 `parseBBCode(text)` 再喂给被测函数」)。
 * 所以这里手搓一个够跑这两批 goldens 的最小抽取器,**只认**
 * `[dice]` / `[collapse]` / `[quote]` / `[b]` / `[pid]` / `[uid]` 与 `<br/>`,
 * 其余一律当字面文本。
 *
 * 节点的 JSON 形状照 `goldens/bbcode/coverage-*.json` 抄(票 09 的产物形状),
 * 所以 `stripQuoteMarkup` 的期望值能直接对上。
 *
 * TODO(票 11/13):票 09 合并后删掉本文件,改用正式 `parseBBCode` +
 * 一个 `BBCodeShape<BBCodeNode>` 适配器 + 一个 AST → [DiceScope] 抽取器。
 * **被测的 `core/local` 主源码不用改**——它们对节点类型是泛型的(见 `ReplyChain.kt` 文件头)。
 */
internal data class MiniNode(
  val type: String,
  val children: List<MiniNode> = emptyList(),
  /** `text` */
  val value: String? = null,
  /** `dice` */
  val expression: String? = null,
  /** `collapse` */
  val title: String? = null,
  /** `floorRef` */
  val args: List<String> = emptyList(),
  /** `floorRef` */
  val pid: String? = null,
  /** `userRef` */
  val uid: String? = null,
)

/** [BBCodeShape] 的实现:票 09 落地后换成 `BBCodeShape<BBCodeNode>`,被测函数不动。 */
internal object MiniShape : BBCodeShape<MiniNode> {
  override fun typeOf(node: MiniNode): String = node.type

  override fun childNodeLists(node: MiniNode): List<List<MiniNode>> =
    if (node.children.isEmpty()) emptyList() else listOf(node.children)

  override fun textValue(node: MiniNode): String? = node.value

  override fun floorRefArgs(node: MiniNode): List<String> = node.args

  override fun floorRefPid(node: MiniNode): String? = node.pid
}

/** 节点 → 金样本里的那个 JSON 形状(键序无所谓,对拍按映射比)。 */
internal fun MiniNode.toGoldenJson(): JsonElement = buildJsonObject {
  put("type", type)
  value?.let { put("value", it) }
  expression?.let { put("expression", it) }
  title?.let { put("title", it) }
  pid?.let { put("pid", it) }
  uid?.let { put("uid", it) }
  if (args.isNotEmpty()) put("args", buildJsonArray { args.forEach { put(it) } })
  if (type != "dice" && type != "text" && type != "linebreak") {
    put("children", JsonArray(children.map { it.toGoldenJson() }))
  }
}

internal fun List<MiniNode>.toGoldenJson(): JsonElement = JsonArray(map { it.toGoldenJson() })

/**
 * 从抽取出的节点树里取骰子作用域:本层的 `[dice]` 表达式按文档顺序,
 * 折叠块各成一个子作用域(顺序同上)。与 `Dice.kt` 里 [DiceScope] 的约定一致。
 */
internal fun diceScopeOf(nodes: List<MiniNode>): DiceScope {
  val expressions = ArrayList<String>()
  val collapses = ArrayList<DiceScope>()

  fun visit(list: List<MiniNode>) {
    for (node in list) {
      when (node.type) {
        "dice" -> expressions.add(node.expression.orEmpty())
        "collapse" -> collapses.add(diceScopeOf(node.children))
        else -> visit(node.children)
      }
    }
  }

  visit(nodes)
  return DiceScope(expressions, collapses)
}

// ---------------------------------------------------------------------------
// 抽取器本体
// ---------------------------------------------------------------------------

/** 内容当容器解析的标签。 */
private val CONTAINER_TAGS = mapOf(
  "quote" to "quote",
  "b" to "bold",
  "collapse" to "collapse",
)

internal fun parseMiniBBCode(source: String): List<MiniNode> {
  val cursor = Cursor(source)
  return parseNodes(cursor, null)
}

private class Cursor(val text: String) {
  var at = 0
}

private class OpenTag(val name: String, val value: String?, val attrText: String?)

private fun parseNodes(cursor: Cursor, closer: String?): List<MiniNode> {
  val nodes = ArrayList<MiniNode>()
  val buffer = StringBuilder()

  fun flush() {
    if (buffer.isEmpty()) return
    nodes.add(MiniNode(type = "text", value = unescapeNgaText(buffer.toString())))
    buffer.setLength(0)
  }

  while (cursor.at < cursor.text.length) {
    val char = cursor.text[cursor.at]

    if (char == '<' && cursor.text.startsWith("<br", cursor.at, ignoreCase = true)) {
      val end = cursor.text.indexOf('>', cursor.at)
      val tag = if (end == -1) null else cursor.text.substring(cursor.at, end + 1).lowercase()
      if (tag != null && (tag == "<br>" || tag == "<br/>" || tag == "<br />")) {
        flush()
        nodes.add(MiniNode(type = "linebreak"))
        cursor.at = end + 1
        continue
      }
    }

    if (char != '[') {
      buffer.append(char)
      cursor.at++
      continue
    }

    val end = cursor.text.indexOf(']', cursor.at)
    if (end == -1) {
      buffer.append(char)
      cursor.at++
      continue
    }
    val inner = cursor.text.substring(cursor.at + 1, end)

    if (inner.startsWith("/")) {
      if (closer != null && inner.substring(1).lowercase() == closer) {
        cursor.at = end + 1
        flush()
        return nodes
      }
      buffer.append(cursor.text, cursor.at, end + 1)
      cursor.at = end + 1
      continue
    }

    val open = splitOpenTag(inner)
    if (open == null) {
      buffer.append(char)
      cursor.at++
      continue
    }

    val node = buildNode(open, cursor, end)
    if (node == null) {
      buffer.append(char)
      cursor.at++
      continue
    }
    flush()
    nodes.add(node)
  }

  flush()
  return nodes
}

private fun splitOpenTag(inner: String): OpenTag? {
  if (inner.isEmpty()) return null
  val equals = inner.indexOf('=')
  val space = inner.indexOf(' ')
  return when {
    equals != -1 && (space == -1 || equals < space) ->
      OpenTag(inner.substring(0, equals).lowercase(), inner.substring(equals + 1), null)

    space != -1 -> OpenTag(inner.substring(0, space).lowercase(), null, inner.substring(space + 1))
    else -> OpenTag(inner.lowercase(), null, null)
  }
}

/** 认得的标签给节点,认不得的给 `null`(调用方把它当字面文本)。 */
private fun buildNode(open: OpenTag, cursor: Cursor, tagEnd: Int): MiniNode? {
  when (open.name) {
    // `[dice XdY]` 没有闭标签,表达式藏在属性位
    "dice" -> {
      if (!open.attrText.isNullOrBlank()) {
        cursor.at = tagEnd + 1
        return MiniNode(type = "dice", expression = open.attrText.trim())
      }
      cursor.at = tagEnd + 1
      val raw = readRaw(cursor, "dice")
      return MiniNode(type = "dice", expression = raw.trim())
    }

    "pid", "uid" -> {
      cursor.at = tagEnd + 1
      if (open.value == null) {
        // 裸写法内容才是原文(`RAW_WHEN_BARE_TAGS`)
        val raw = readRaw(cursor, open.name).trim()
        return if (open.name == "pid") {
          MiniNode(type = "floorRef", pid = raw, args = listOf(raw))
        } else {
          MiniNode(type = "userRef", uid = raw)
        }
      }
      val children = parseNodes(cursor, open.name)
      return if (open.name == "pid") {
        val args = open.value.split(",")
        MiniNode(type = "floorRef", pid = args.firstOrNull().orEmpty(), args = args, children = children)
      } else {
        MiniNode(type = "userRef", uid = open.value, children = children)
      }
    }

    in CONTAINER_TAGS.keys -> {
      cursor.at = tagEnd + 1
      val children = parseNodes(cursor, open.name)
      val type = CONTAINER_TAGS.getValue(open.name)
      return if (type == "collapse") {
        MiniNode(type = type, title = open.value, children = children)
      } else {
        MiniNode(type = type, children = children)
      }
    }

    else -> return null
  }
}

/** 读到 `[/name]` 为止的原文(不解析里面的标签),顺带吃掉闭标签。 */
private fun readRaw(cursor: Cursor, name: String): String {
  val close = "[/$name]"
  val end = cursor.text.indexOf(close, cursor.at, ignoreCase = true)
  if (end == -1) {
    val rest = cursor.text.substring(cursor.at)
    cursor.at = cursor.text.length
    return rest
  }
  val raw = cursor.text.substring(cursor.at, end)
  cursor.at = end + close.length
  return raw
}
