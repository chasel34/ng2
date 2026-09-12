package com.chasel.ng2n.core.local

/**
 * 回复链:从楼层正文的引用标记里建立 quote 关系索引,再从任意一楼沿上下游展开成一条链。
 * 直译 `src/core/local/reply-chain.ts`。
 *
 * NGA 的引用有两种写法,都以 `[pid=<pid>,<tid>,<page>]Reply[/pid]` 标记被引用楼:
 *
 * - 引用:`[quote][pid=…]Reply[/pid] [b]Post by [uid=…]名字[/uid] (时间):[/b]…[/quote]`
 * - 回复:`[b]Reply to [pid=…]Reply[/pid] Post by (…)[/b]`(没有 quote 容器)
 *
 * 两种都算一条「本楼 → 被引用楼」的边。
 *
 * ## 与票 09 的接缝
 *
 * TS 版直接吃 `BBCodeNode[]`。票 09 的 AST 还没合并,而这几个函数触碰到的 AST 表面
 * 其实只有五件事(节点类型名、子节点列表、text 的值、floorRef 的 args 与 pid),
 * 于是抠成 [BBCodeShape] 这个最小适配器,函数对节点类型 `N` 泛型。
 * 票 11/13 接正式解析器时,写一个 `object : BBCodeShape<BBCodeNode>` 即可,
 * **函数本身不用动**(`stripQuoteMarkup` 返回的仍是原节点)。
 *
 * [buildQuoteIndex] 是唯一一处有意偏离:TS 版在里面调 `parseBBCode(floor.content)`,
 * 这里改成收**已经抽好的引用**([QuoteIndexFloor.refs] = `extractQuoteRefs(parseBBCode(content))`),
 * 免得把解析器拖进这一层。
 */

/**
 * 票 09 AST 的最小适配器:回复链只需要看这几样。
 *
 * `type` 用的就是 AST 节点的 `type` 串(`"text"` / `"quote"` / `"bold"` /
 * `"floorRef"` / `"linebreak"` / …),与金样本里的一致。
 */
interface BBCodeShape<N> {
  /** 节点类型名。 */
  fun typeOf(node: N): String

  /** 节点的全部子节点列表(表格那类有多组,普通容器只有一组;叶子节点是空)。 */
  fun childNodeLists(node: N): List<List<N>>

  /** `text` 节点的文字;其它节点给 `null`。 */
  fun textValue(node: N): String?

  /** `floorRef`(`[pid=a,b,c]`)的位置参数;其它节点给空表。 */
  fun floorRefArgs(node: N): List<String>

  /** `floorRef` 的 `pid` 属性(`[pid=123]` 这种老写法);其它节点给 `null`。 */
  fun floorRefPid(node: N): String?
}

private const val TYPE_TEXT = "text"
private const val TYPE_QUOTE = "quote"
private const val TYPE_BOLD = "bold"
private const val TYPE_FLOOR_REF = "floorRef"
private const val TYPE_LINEBREAK = "linebreak"

/** 一条引用指向的楼层。tid/页码来自 `[pid=a,b,c]` 的后两个参数,老写法可能缺。 */
data class QuoteRef(
  val pid: Long,
  val tid: Long? = null,
  /** 被引用楼在原帖里的页码(发引用那一刻的口径,每页 20 楼固定,基本可信) */
  val page: Long? = null,
)

/**
 * 建索引只需要每楼这三样。
 *
 * @param lou 楼号,用来给下游排序;贴条那类没有真实楼号的可不给
 * @param refs 正文里的引用,= `extractQuoteRefs(parseBBCode(content), shape)`
 */
data class QuoteIndexFloor(
  val pid: Long,
  val lou: Long? = null,
  val refs: List<QuoteRef> = emptyList(),
)

/** 已加载楼层的 quote 关系索引。 */
data class QuoteIndex(
  /** pid → 它引用了谁(按正文里出现的顺序;第一条视作主引用) */
  val quotes: Map<Long, List<QuoteRef>>,
  /** pid → 谁引用了它(按楼号从小到大) */
  val quotedBy: Map<Long, List<Long>>,
  /** 已加载楼层的 pid 集合;链上不在这里的节点要懒加载 */
  val loaded: Set<Long>,
)

enum class ChainRole(val wire: String) {
  UPSTREAM("upstream"),
  CURRENT("current"),
  DOWNSTREAM("downstream"),
}

/** 回复链上的一个节点。[ref] 只在未加载时有用——懒加载靠它定位页码。 */
data class ChainNode(
  val pid: Long,
  val role: ChainRole,
  /** false = 这一楼不在已加载集合里,要按 `ref.page` 懒加载(失败则降级占位) */
  val loaded: Boolean,
  val ref: QuoteRef? = null,
)

/** `Number(raw.trim())` 后要求「是整数且 > 0」;空参、坏参一律 `null`。 */
private fun parseIntArg(raw: String?): Long? {
  if (raw == null) return null
  val value = jsNumber(raw)
  if (!value.isFinite() || value != kotlin.math.floor(value) || value <= 0.0) return null
  return value.toLong()
}

/** `[pid=a,b,c]` 节点 → 引用。pid 非正整数(空参、坏参)一律不算引用。 */
private fun <N> refOfFloorRefNode(node: N, shape: BBCodeShape<N>): QuoteRef? {
  val args = shape.floorRefArgs(node)
  val pid = parseIntArg(args.getOrNull(0) ?: shape.floorRefPid(node)) ?: return null
  return QuoteRef(
    pid = pid,
    tid = parseIntArg(args.getOrNull(1)),
    page = parseIntArg(args.getOrNull(2)),
  )
}

/**
 * 一段节点里的第一个 `[pid]` 引用。**不进嵌套的 quote**——
 * 引用块里再套一层引用块时,内层的 `[pid]` 是被引用楼自己的引用关系,
 * 算到本楼头上会把祖孙关系错接成父子。
 */
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

/** 一个引用块指向哪一楼。渲染层用它决定「查看对话链」入口画不画。 */
fun <N> quoteRefOf(node: N, shape: BBCodeShape<N>): QuoteRef? =
  firstFloorRef(shape.childNodeLists(node).flatten(), shape)

/** `[b]Reply to [pid=…]…[/b]` 的回复头:粗体、第一段文字以 `Reply to` 开头。 */
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

/**
 * 一个节点是不是 `[b]Reply to [pid=…]…[/b]` 回复头。
 *
 * 渲染层拿它把回复头画成引用卡片:这种写法没有 `[quote]` 容器,只按节点类型分派的话
 * 它就是正文顶上突兀的一行加粗英文,跟楼主自己说的话糊在一起。
 */
fun <N> isReplyHeaderNode(node: N, shape: BBCodeShape<N>): Boolean {
  if (shape.typeOf(node) != TYPE_BOLD) return false
  val text = firstText(shape.childNodeLists(node).flatten(), shape) ?: return false
  return text.jsTrimStart().startsWith("Reply to")
}

/**
 * 回复头指向哪一楼。不是回复头、或头里认不出 `[pid]`(手打的)时返回 `null`——
 * 跟 [quoteRefOf] 一样,渲染层用它决定「查看对话链」入口画不画。
 */
fun <N> replyHeaderRefOf(node: N, shape: BBCodeShape<N>): QuoteRef? =
  if (isReplyHeaderNode(node, shape)) firstFloorRef(shape.childNodeLists(node).flatten(), shape) else null

/**
 * 一楼正文里的全部引用:每个引用块取第一个 `[pid]`,回复头同理。
 * 正文里随手贴的楼层链接(不在这两种容器里)不算引用——那是提及,不是回复关系。
 */
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

/**
 * 扫描已加载楼层建 quote 关系索引。
 *
 * - 同一楼引用同一目标多次只记一条;引用自己不记(自环没有意义,还会把链搅成死结)。
 * - 跨帖引用([QuoteRef.tid] 与本帖不同)不进索引:它不是本帖楼层,链到不了。
 * - 楼层重复(同一楼在多页数据里都出现)以先到的为准。
 *
 * @param tid 本帖 tid。给了就把指向别的主题的引用(跨帖引用)排除在链外
 */
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

  // 下游按楼号排:一楼被多人引用时,链沿最早的那条回复走下去
  // (没有楼号的排最后,与 TS 侧 `louOf.get(a) ?? Infinity` 同义)
  for (list in quotedBy.values) {
    list.sortWith(compareBy<Long> { louOf[it] ?: Long.MAX_VALUE }.thenBy { it })
  }

  return QuoteIndex(quotes, quotedBy.mapValues { (_, list) -> list.toList() }, loaded)
}

/**
 * 从 [startPid] 展开回复链:上游沿「它引用了谁」走到头,下游沿「谁引用了它」走到头。
 *
 * - 一楼引用多楼时上游只沿第一条(主引用)走;一楼被多楼引用时下游沿最早的回复走——
 *   链是一条对话线,不是整棵树。
 * - 上游走到未加载的楼就停(那楼引用了谁只有加载后才知道),节点带着 `ref` 留给懒加载。
 * - 环引用(A 引 B、B 引 A)靠 visited 集合掐断,不会死循环。
 */
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

/** 「查看对话链(N 层)」的 N:从这一楼可追溯的链长(含它自己与未加载但可定位的楼)。 */
fun chainDepthOf(index: QuoteIndex, pid: Long): Int = buildReplyChain(index, pid).size

/**
 * 把正文里的引用容器剥掉,留下这一楼自己说的话——回复链卡片用:
 * 链上一楼的上一层就画在它上面,卡片里再展开引用块只是同一段话出现两遍。
 * 剥的就是建索引认的那两种容器:顶层的 `[quote]` 与 `[b]Reply to …[/b]` 回复头。
 */
fun <N> stripQuoteMarkup(nodes: List<N>, shape: BBCodeShape<N>): List<N> {
  val stripped = nodes.filter { shape.typeOf(it) != TYPE_QUOTE && !isReplyHeaderNode(it, shape) }
  // 剥完开头常剩一两个空行(引用块与正文之间的换行),顺手掐掉
  var start = 0
  while (start < stripped.size && shape.typeOf(stripped[start]) == TYPE_LINEBREAK) start++
  return stripped.subList(start, stripped.size).toList()
}
