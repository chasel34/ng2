package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.ChainKey
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.ChainNode
import com.chasel.ng2n.core.local.DiceSeed
import com.chasel.ng2n.core.local.QuoteIndexFloor
import com.chasel.ng2n.core.local.buildQuoteIndex
import com.chasel.ng2n.core.local.buildReplyChain
import com.chasel.ng2n.core.local.extractQuoteRefs
import com.chasel.ng2n.core.local.stripQuoteMarkup
import com.chasel.ng2n.data.history.pageOfFloor
import com.chasel.ng2n.data.settings.DEFAULT_SETTINGS
import com.chasel.ng2n.ui.bbcode.BBCodeNodeShape
import com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.RenderModelBuilder
import com.chasel.ng2n.ui.bbcode.resolveFloorDice
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回复链屏(CONTEXT.md「回复链」;设计稿 isChain)。直译 `src/app/chain.tsx`。
 *
 * 从详情页某楼的引用块进来:`tid` + `pid`(展开起点)+ 可选 `fav`。
 * 已加载楼层直接从 [TopicRepository] 的进程级缓存搬(详情页翻过的页都在);
 * 链上引用了还没加载的楼时,按引用标记里的页码把那一页懒加载回来 ——
 * 加载失败或定位不到的节点降级成占位卡,**不阻塞整条链**。
 */
class ChainViewModel(
  val key: ChainKey,
  private val deps: TopicDeps,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  /** 已加载页。进场时从仓库缓存搬一份,懒加载的页往里补。 */
  private val pages: SnapshotStateMap<Int, TopicDetail> = mutableStateMapOf()
  private val failedPages = mutableStateListOf<Int>()

  var loadingPage by mutableStateOf<Int?>(null)
    private set

  var chain by mutableStateOf<List<ChainNode>>(emptyList())
    private set

  /** pid → 这一楼在链上要画的东西;缺席就是「没加载出来」,画降级占位卡。 */
  var entries by mutableStateOf<Map<Long, ChainEntry>>(emptyMap())
    private set

  var settings by mutableStateOf(DEFAULT_SETTINGS)
    private set

  private var style: TopicRenderStyle? = null

  init {
    viewModelScope.launch {
      deps.settings.settings.collect { settings = it }
    }
  }

  fun applyStyle(next: TopicRenderStyle) {
    if (style == next) return
    style = next
    viewModelScope.launch {
      for (detail in deps.repository.loadedPages(key.tid, key.fav)) pages[detail.page] = detail
      rebuild()
    }
  }

  /** 链上第一个「未加载但带页码」的节点 —— 页到位 → 索引重建 → 链自己长长。 */
  private fun wantedPage(): Int? = chain.firstOrNull { node ->
    !node.loaded &&
      node.ref?.page != null &&
      !pages.containsKey(node.ref!!.page!!.toInt()) &&
      !failedPages.contains(node.ref!!.page!!.toInt())
  }?.ref?.page?.toInt()

  private fun rebuild() {
    val style = style ?: return
    val snapshot = pages.values.sortedBy { it.page }
    viewModelScope.launch {
      val built = withContext(compute) { buildChain(snapshot, style) }
      chain = built.chain
      entries = built.entries
    }.invokeOnCompletion { maybeLoadNext() }
  }

  private fun maybeLoadNext() {
    if (loadingPage != null) return
    val page = wantedPage() ?: return
    loadingPage = page
    viewModelScope.launch {
      try {
        val detail = deps.repository.loadDetail(TopicPageParams(key.tid, page, key.fav))
        // 按**请求的**页码登记而不是响应的 `__PAGE`:超范围的页码服务端会钳到末页,
        // 按响应登记的话这个页码永远补不上,懒加载会原地打转
        pages[page] = detail
      } catch (cause: CancellationException) {
        throw cause
      } catch (_: Exception) {
        failedPages.add(page)
      } finally {
        loadingPage = null
        rebuild()
      }
    }
  }

  /** 「重试」:把那一页从失败集合里拿掉,懒加载会再试一次。 */
  fun retryPage(page: Int) {
    failedPages.remove(page)
    maybeLoadNext()
  }

  fun isLoading(page: Int?): Boolean =
    page != null && !pages.containsKey(page) && !failedPages.contains(page)

  fun isPageLoaded(page: Int?): Boolean = page != null && pages.containsKey(page)

  /** 「在原帖中查看」:回详情页那一页并定位那一楼。 */
  fun openInTopicKey(node: ChainNode): TopicKey? {
    val entry = entries[node.pid]
    return when {
      entry != null -> TopicKey(
        tid = key.tid,
        page = pageOfFloor(entry.lou.toInt(), entry.rowsPerPage),
        floor = entry.lou,
        fav = key.fav,
      )
      node.ref?.page != null -> TopicKey(tid = key.tid, page = node.ref!!.page!!.toInt(), fav = key.fav)
      else -> null
    }
  }

  /** 顶栏那句「从第 N 楼展开」。起点楼还没加载出来时不显示。 */
  val startLou: Long? get() = entries[key.pid]?.lou

  private fun buildChain(details: List<TopicDetail>, style: TopicRenderStyle): BuiltChain {
    // 全部已加载楼层(含热门回复)合成一张表,quote 索引按它建。
    // 匿名用户的 key 带请求级前缀(API 文档 §3),跨页合并用户表不会串号
    val byPid = LinkedHashMap<Long, ChainSource>()
    for (detail in details) {
      for (floor in detail.floors + detail.hotReplies) {
        if (byPid.containsKey(floor.pid)) continue
        byPid[floor.pid] = ChainSource(floor, detail)
      }
    }
    val index = buildQuoteIndex(
      byPid.values.map { source ->
        QuoteIndexFloor(
          pid = source.floor.pid,
          lou = source.floor.lou,
          refs = extractQuoteRefs(parseBBCode(source.floor.content), BBCodeNodeShape),
        )
      },
      key.tid,
    )
    val nodes = buildReplyChain(index, key.pid)

    val built = LinkedHashMap<Long, ChainEntry>()
    for (node in nodes) {
      val source = byPid[node.pid] ?: continue
      built[node.pid] = renderEntry(source, style)
    }
    return BuiltChain(nodes, built)
  }

  private fun renderEntry(source: ChainSource, style: TopicRenderStyle): ChainEntry {
    val floor = source.floor
    val detail = source.detail
    val user = detail.users[floor.authorKey]
    // 正文剥掉引用容器 —— 上一层就画在这张卡上面,不必重复
    val nodes = stripQuoteMarkup(parseBBCode(floor.content), BBCodeNodeShape)
    val dice = resolveFloorDice(nodes, DiceSeed(floor.authorId, key.tid, floor.pid))
    val model = RenderModelBuilder.build(
      nodes,
      BBCodeRenderOptions(
        attachBase = detail.attachBase,
        postedAt = floor.postedAt,
        dice = dice.toImmutableList(),
        colors = style.colors,
        // 链卡正文比楼层正文小一档(设计稿 chainBody)
        bodyFontSize = Typo.quoteBody.size.value,
        bodyLineHeight = Typo.quoteBody.lineHeight.value / Typo.quoteBody.size.value,
        attachmentUrls = deps.attachmentUrls,
      ),
    )
    val name = user?.name ?: "未知用户"
    return ChainEntry(
      pid = floor.pid,
      lou = floor.lou,
      rowsPerPage = detail.rowsPerPage,
      name = name,
      avatarUrl = user?.avatarUrl,
      avatarColor = avatarColorFor(user?.key ?: name),
      avatarInitial = initialOf(name),
      postedAtText = floor.postedAtText,
      score = floor.score,
      body = model,
    )
  }

  private class ChainSource(val floor: com.chasel.ng2n.core.api.Floor, val detail: TopicDetail)

  private class BuiltChain(val chain: List<ChainNode>, val entries: Map<Long, ChainEntry>)
}

/** 链上一张已加载的卡要画的东西(全部后台建好)。 */
@Immutable
data class ChainEntry(
  val pid: Long,
  val lou: Long,
  /** 「在原帖中查看」要按它换算页码 */
  val rowsPerPage: Int,
  val name: String,
  val avatarUrl: String?,
  val avatarColor: androidx.compose.ui.graphics.Color,
  val avatarInitial: String,
  val postedAtText: String,
  val score: Long,
  val body: FloorRenderModel,
)
