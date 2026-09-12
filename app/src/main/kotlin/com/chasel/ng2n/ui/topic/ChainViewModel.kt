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

class ChainViewModel(
  val key: ChainKey,
  private val deps: TopicDeps,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  private val pages: SnapshotStateMap<Int, TopicDetail> = mutableStateMapOf()
  private val failedPages = mutableStateListOf<Int>()

  var loadingPage by mutableStateOf<Int?>(null)
    private set

  var chain by mutableStateOf<List<ChainNode>>(emptyList())
    private set

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

  fun retryPage(page: Int) {
    failedPages.remove(page)
    maybeLoadNext()
  }

  fun isLoading(page: Int?): Boolean =
    page != null && !pages.containsKey(page) && !failedPages.contains(page)

  fun isPageLoaded(page: Int?): Boolean = page != null && pages.containsKey(page)

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

  val startLou: Long? get() = entries[key.pid]?.lou

  private fun buildChain(details: List<TopicDetail>, style: TopicRenderStyle): BuiltChain {
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
    val nodes = stripQuoteMarkup(parseBBCode(floor.content), BBCodeNodeShape)
    val dice = resolveFloorDice(nodes, DiceSeed(floor.authorId, key.tid, floor.pid))
    val model = RenderModelBuilder.build(
      nodes,
      BBCodeRenderOptions(
        attachBase = detail.attachBase,
        postedAt = floor.postedAt,
        dice = dice.toImmutableList(),
        colors = style.colors,
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

@Immutable
data class ChainEntry(
  val pid: Long,
  val lou: Long,
  val rowsPerPage: Int,
  val name: String,
  val avatarUrl: String?,
  val avatarColor: androidx.compose.ui.graphics.Color,
  val avatarInitial: String,
  val postedAtText: String,
  val score: Long,
  val body: FloorRenderModel,
)
