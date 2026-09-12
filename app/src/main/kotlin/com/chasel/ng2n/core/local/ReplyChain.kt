package com.chasel.ng2n.core.local

interface BBCodeShape<N> {
  fun typeOf(node: N): String

  fun childNodeLists(node: N): List<List<N>>

  fun textValue(node: N): String?

  fun floorRefArgs(node: N): List<String>

  fun floorRefPid(node: N): String?
}

private const val TYPE_TEXT = "text"
private const val TYPE_QUOTE = "quote"
private const val TYPE_BOLD = "bold"
private const val TYPE_FLOOR_REF = "floorRef"
private const val TYPE_LINEBREAK = "linebreak"

data class QuoteRef(
  val pid: Long,
  val tid: Long? = null,
  val page: Long? = null,
)

data class QuoteIndexFloor(
  val pid: Long,
  val lou: Long? = null,
  val refs: List<QuoteRef> = emptyList(),
)

data class QuoteIndex(
  val quotes: Map<Long, List<QuoteRef>>,
  val quotedBy: Map<Long, List<Long>>,
  val loaded: Set<Long>,
)

enum class ChainRole(val wire: String) {
  UPSTREAM("upstream"),
  CURRENT("current"),
  DOWNSTREAM("downstream"),
}

data class ChainNode(
  val pid: Long,
  val role: ChainRole,
  val loaded: Boolean,
  val ref: QuoteRef? = null,
)

private fun parseIntArg(raw: String?): Long? {
  if (raw == null) return null
  val value = jsNumber(raw)
  if (!value.isFinite() || value != kotlin.math.floor(value) || value <= 0.0) return null
  return value.toLong()
}

private fun <N> refOfFloorRefNode(node: N, shape: BBCodeShape<N>): QuoteRef? {
  val args = shape.floorRefArgs(node)
  val pid = parseIntArg(args.getOrNull(0) ?: shape.floorRefPid(node)) ?: return null
  return QuoteRef(
    pid = pid,
    tid = parseIntArg(args.getOrNull(1)),
    page = parseIntArg(args.getOrNull(2)),
  )
}

private fun <N> firstFloorRef(nodes: List<N>, shape: BBCodeShape<N>): QuoteRef? {
  for (node in nodes) {
    val type = shape.typeOf(node)
    if (type == TYPE_FLOOR_REF) {
      val ref = refOfFloorRefNode(node, shape)
      if (ref != null) return ref
      continue
    }
    if (type == TYPE_QUOTE) continue
    for (children in shape.childNodeLists(node)) {
      val ref = firstFloorRef(children, shape)
      if (ref != null) return ref
    }
  }
  return null
}

fun <N> quoteRefOf(node: N, shape: BBCodeShape<N>): QuoteRef? =
  firstFloorRef(shape.childNodeLists(node).flatten(), shape)

private fun <N> firstText(nodes: List<N>, shape: BBCodeShape<N>): String? {
  for (child in nodes) {
    if (shape.typeOf(child) == TYPE_TEXT) return shape.textValue(child)
    for (children in shape.childNodeLists(child)) {
      val value = firstText(children, shape)
      if (value != null) return value
    }
  }
  return null
}

fun <N> isReplyHeaderNode(node: N, shape: BBCodeShape<N>): Boolean {
  if (shape.typeOf(node) != TYPE_BOLD) return false
  val text = firstText(shape.childNodeLists(node).flatten(), shape) ?: return false
  return text.jsTrimStart().startsWith("Reply to")
}

fun <N> replyHeaderRefOf(node: N, shape: BBCodeShape<N>): QuoteRef? =
  if (isReplyHeaderNode(node, shape)) firstFloorRef(shape.childNodeLists(node).flatten(), shape) else null

fun <N> extractQuoteRefs(nodes: List<N>, shape: BBCodeShape<N>): List<QuoteRef> {
  val refs = ArrayList<QuoteRef>()

  fun walk(list: List<N>) {
    for (node in list) {
      if (shape.typeOf(node) == TYPE_QUOTE) {
        quoteRefOf(node, shape)?.let { refs.add(it) }
        continue
      }
      if (isReplyHeaderNode(node, shape)) {
        replyHeaderRefOf(node, shape)?.let { refs.add(it) }
        continue
      }
      for (children in shape.childNodeLists(node)) walk(children)
    }
  }

  walk(nodes)
  return refs
}

fun buildQuoteIndex(floors: List<QuoteIndexFloor>, tid: Long? = null): QuoteIndex {
  val quotes = LinkedHashMap<Long, List<QuoteRef>>()
  val quotedBy = LinkedHashMap<Long, MutableList<Long>>()
  val loaded = LinkedHashSet<Long>()
  val louOf = HashMap<Long, Long>()

  for (floor in floors) {
    if (!loaded.add(floor.pid)) continue
    floor.lou?.let { louOf[floor.pid] = it }

    val seen = HashSet<Long>()
    val refs = floor.refs.filter { ref ->
      when {
        ref.pid == floor.pid -> false
        tid != null && ref.tid != null && ref.tid != tid -> false
        else -> seen.add(ref.pid)
      }
    }
    if (refs.isEmpty()) continue

    quotes[floor.pid] = refs
    for (ref in refs) {
      quotedBy.getOrPut(ref.pid) { ArrayList() }.add(floor.pid)
    }
  }

  for (list in quotedBy.values) {
    list.sortWith(compareBy<Long> { louOf[it] ?: Long.MAX_VALUE }.thenBy { it })
  }

  return QuoteIndex(quotes, quotedBy.mapValues { (_, list) -> list.toList() }, loaded)
}

fun buildReplyChain(index: QuoteIndex, startPid: Long): List<ChainNode> {
  val visited = hashSetOf(startPid)

  val upstream = ArrayDeque<ChainNode>()
  var cursor = startPid
  while (true) {
    val ref = index.quotes[cursor]?.firstOrNull() ?: break
    if (!visited.add(ref.pid)) break
    val loaded = ref.pid in index.loaded
    upstream.addFirst(ChainNode(ref.pid, ChainRole.UPSTREAM, loaded, ref))
    if (!loaded) break
    cursor = ref.pid
  }

  val downstream = ArrayList<ChainNode>()
  cursor = startPid
  while (true) {
    val quoter = index.quotedBy[cursor]?.firstOrNull { it !in visited } ?: break
    visited.add(quoter)
    downstream.add(ChainNode(quoter, ChainRole.DOWNSTREAM, quoter in index.loaded))
    cursor = quoter
  }

  return buildList {
    addAll(upstream)
    add(ChainNode(startPid, ChainRole.CURRENT, startPid in index.loaded))
    addAll(downstream)
  }
}

fun chainDepthOf(index: QuoteIndex, pid: Long): Int = buildReplyChain(index, pid).size

fun <N> stripQuoteMarkup(nodes: List<N>, shape: BBCodeShape<N>): List<N> {
  val stripped = nodes.filter { shape.typeOf(it) != TYPE_QUOTE && !isReplyHeaderNode(it, shape) }
  var start = 0
  while (start < stripped.size && shape.typeOf(stripped[start]) == TYPE_LINEBREAK) start++
  return stripped.subList(start, stripped.size).toList()
}
