package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.nav.TopicKey
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.core.api.ATTACH_BASE_FALLBACK
import com.chasel.ng2n.core.api.EMPTY_BLOCK_WORDS
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.RecommendAction
import com.chasel.ng2n.core.api.RecommendMark
import com.chasel.ng2n.core.api.TopicSource
import com.chasel.ng2n.core.api.expectedRecommendDelta
import com.chasel.ng2n.core.api.fetchBlockWords
import com.chasel.ng2n.core.api.nextRecommendState
import com.chasel.ng2n.core.api.officialFilterRules
import com.chasel.ng2n.core.api.postRecommend
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.local.FilterRule
import com.chasel.ng2n.core.local.FilterRuleInput
import com.chasel.ng2n.core.local.FilterRuleKind
import com.chasel.ng2n.core.local.FilterSubject
import com.chasel.ng2n.core.local.QuoteIndexFloor
import com.chasel.ng2n.core.local.buildQuoteIndex
import com.chasel.ng2n.core.local.chainDepthOf
import com.chasel.ng2n.core.local.createFilterRule
import com.chasel.ng2n.core.local.matchFilterRules
import com.chasel.ng2n.core.local.removeFilterRule
import com.chasel.ng2n.core.local.upsertFilterRule
import com.chasel.ng2n.data.history.TopicVisit
import com.chasel.ng2n.data.history.pageOfFloor
import com.chasel.ng2n.data.settings.DEFAULT_SETTINGS
import com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.RenderModelBuilder
import com.chasel.ng2n.ui.bbcode.signatureRenderOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TopicViewModel(
  val key: TopicKey,
  private val deps: TopicDeps,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  val tid: Long = key.tid

  var page by mutableStateOf(initialPage())
    private set

  var onlyPid by mutableStateOf(key.pid?.takeIf { it > 0 })
    private set

  var onlyUser by mutableStateOf<OnlyUser?>(null)
    private set

  val pages: SnapshotStateMap<Int, PageState> = mutableStateMapOf()

  var totalPages by mutableStateOf(page)
    private set

  var pageInFlight by mutableStateOf<Int?>(null)
    private set

  var sourceNoticeDismissed by mutableStateOf(false)
    private set

  val unfolded: SnapshotStateMap<Long, Unit> = mutableStateMapOf()

  var blockedFloors by mutableStateOf<Map<Long, FilterRule>>(emptyMap())
    private set

  var chainDepths by mutableStateOf<Map<Long, Int>>(emptyMap())
    private set

  val recommendMarks: SnapshotStateMap<Long, RecommendMark> = mutableStateMapOf()

  var settings by mutableStateOf(DEFAULT_SETTINGS)
    private set

  var style by mutableStateOf<TopicRenderStyle?>(null)
    private set

  var resumeFloor by mutableStateOf<Long?>(null)
    private set

  var resumeDismissed by mutableStateOf(false)
    private set

  var scrollTarget by mutableStateOf<ScrollTarget?>(null)
    private set

  var signatureDialog by mutableStateOf<SignatureDialogState?>(null)
    private set

  var userScrolled by mutableStateOf(false)

  private val snackbarState = MutableStateFlow<SnackbarMessage?>(null)
  val snackbar: StateFlow<SnackbarMessage?> = snackbarState.asStateFlow()

  private val toastState = MutableStateFlow<ToastMessage?>(null)
  val toast: StateFlow<ToastMessage?> = toastState.asStateFlow()

  val cacheDownload: StateFlow<CacheDownloadState> = deps.repository.cacheDownload

  private val loading = HashSet<Int>()
  private val recommendPending = HashSet<Long>()

  private var localRules: List<FilterRule> = emptyList()
  private var officialRules: List<FilterRule> = emptyList()
  private var blockJob: Job? = null
  private var chainJob: Job? = null

  private var pendingFloor: Long? = key.floor?.takeIf { it >= 0 }

  private var pageBeforeFilter = 1

  init {
    viewModelScope.launch {
      deps.settings.settings.collect { next ->
        val rebuild = style != null && (
          next.appearance.bodyFontSize != settings.appearance.bodyFontSize ||
            next.appearance.bodyLineHeight != settings.appearance.bodyLineHeight ||
            next.showSignature != settings.showSignature
          )
        settings = next
        if (rebuild) rebuildAllPages()
      }
    }
    viewModelScope.launch {
      deps.settings.localFilterRules.collect { stored ->
        localRules = stored.mapNotNull { it.toMatchRule() }
        recomputeBlocked()
      }
    }
    viewModelScope.launch {
      val uid = deps.credentials.current()?.uid ?: return@launch
      val list = runCatching { fetchBlockWords(deps.client, uid) }.getOrDefault(EMPTY_BLOCK_WORDS)
      officialRules = officialFilterRules(list)
      recomputeBlocked()
    }
    viewModelScope.launch {
      deps.history.warmUp()
      val entry = deps.history.peek(tid)
      if (entry == null || entry.lastFloor < 1) return@launch
      if (key.floor == entry.lastFloor.toLong()) return@launch
      resumeFloor = entry.lastFloor.toLong()
    }
  }

  fun applyStyle(next: TopicRenderStyle) {
    if (style == next) return
    val first = style == null
    style = next
    if (first) ensureLoaded(page) else rebuildAllPages()
  }

  private fun rebuildAllPages() {
    val current = style ?: return
    val loaded = pages.keys.toList()
    pages.clear()
    loading.clear()
    for (target in loaded) load(target, refresh = false, style = current)
    if (page !in loaded) ensureLoaded(page)
  }

  private fun initialPage(): Int {
    key.page?.takeIf { it > 0 }?.let { return it }
    key.floor?.takeIf { it >= 0 }?.let { return pageOfFloor(it.toInt(), DEFAULT_ROWS_PER_PAGE) }
    return 1
  }

  fun paramsFor(target: Int): TopicPageParams = TopicPageParams(
    tid = tid,
    page = target,
    favCode = key.fav,
    pid = onlyPid,
    authorId = onlyUser?.uid,
  )

  fun ensureLoaded(target: Int) {
    if (target < 1) return
    if (pages[target] is PageState.Loaded || target in loading) return
    load(target, refresh = false, style = style ?: return)
  }

  fun refresh(target: Int = page) {
    load(target, refresh = true, style = style ?: return)
  }

  private fun load(target: Int, refresh: Boolean, style: TopicRenderStyle) {
    if (target in loading) return
    loading.add(target)
    if (pages[target] !is PageState.Loaded) pages[target] = PageState.Loading
    viewModelScope.launch {
      try {
        val model = deps.repository.loadPage(
          params = paramsFor(target),
          style = style,
          urls = deps.attachmentUrls,
          refresh = refresh,
        )
        pages[target] = PageState.Loaded(model)
        onPageLoaded(target, model)
      } catch (cause: CancellationException) {
        throw cause
      } catch (cause: Exception) {
        pages[target] = PageState.Failed(cause)
      } finally {
        loading.remove(target)
      }
    }
  }

  private fun onPageLoaded(target: Int, model: PageRenderModel) {
    totalPages = model.totalPages
    recomputeBlocked()
    rebuildChainIndex()
    recordVisit(model)
    redeemPendingFloor()
    if (target == page && target < model.totalPages) ensureLoaded(target + 1)
  }

  fun setPageInFlight(target: Int) {
    pageInFlight = target.takeIf { it != page }
  }

  fun goToPage(next: Int) {
    val clamped = clampPage(next, totalPages)
    pageInFlight = null
    if (clamped == page) return
    page = clamped
    userScrolled = false
    ensureLoaded(clamped)
    if (clamped > 1) prefetchFromCache(clamped - 1)
    redeemPendingFloor()
  }

  private fun prefetchFromCache(target: Int) {
    val style = style ?: return
    if (pages[target] is PageState.Loaded || target in loading) return
    viewModelScope.launch {
      val cached = deps.repository.cachedDetail(paramsFor(target)) ?: return@launch
      val model = withContext(compute) {
        TopicPageBuilder.build(cached, tid, style, deps.attachmentUrls)
      }
      if (pages[target] !is PageState.Loaded) pages[target] = PageState.Loaded(model)
    }
  }

  fun jumpTo(input: String) {
    val target = parseJumpTarget(input, totalPages)
    if (target == null) {
      toast("请输入 1 – $totalPages 之间的页码")
      return
    }
    goToPage(target)
  }

  fun onReachedEnd() {
    if (!settings.autoLoadNextPage) return
    if (!userScrolled) return
    if (page >= totalPages) return
    if (pages[page] !is PageState.Loaded) return
    goToPage(page + 1)
  }

  fun enterOnlyUser(floor: FloorRenderItem) {
    val uid = floor.profileUid
    if (uid == null) {
      toast("匿名用户无法只看")
      return
    }
    pageBeforeFilter = page
    onlyUser = OnlyUser(uid = uid, name = floor.displayName)
    resetPagesForFilterChange(1)
  }

  fun exitOnlyUser() {
    onlyUser = null
    resetPagesForFilterChange(pageBeforeFilter)
  }

  fun exitOnlyPid() {
    onlyPid = null
    resetPagesForFilterChange(1)
  }

  private fun resetPagesForFilterChange(target: Int) {
    pages.clear()
    loading.clear()
    totalPages = 1
    page = target
    userScrolled = false
    ensureLoaded(target)
  }

  val progressPaused: Boolean get() = onlyPid != null || onlyUser != null

  private fun recomputeBlocked() {
    val rules = localRules + officialRules
    blockJob?.cancel()
    if (rules.isEmpty()) {
      blockedFloors = emptyMap()
      return
    }
    val snapshot = pages.values.filterIsInstance<PageState.Loaded>().map { it.model }
    blockJob = viewModelScope.launch {
      val next = withContext(compute) {
        val result = HashMap<Long, FilterRule>()
        for (model in snapshot) {
          for (floor in model.floors + model.hotReplies) {
            val rule = matchFilterRules(
              rules,
              FilterSubject(
                author = floor.user?.name,
                authorId = floor.user?.uid,
                title = floor.subject,
                content = floor.content,
              ),
            )
            if (rule != null) result[floor.pid] = rule
          }
        }
        result
      }
      blockedFloors = next
    }
  }

  fun expandFloor(pid: Long) {
    unfolded[pid] = Unit
  }

  fun blockedRuleOf(floor: FloorRenderItem): FilterRule? =
    if (unfolded.containsKey(floor.pid)) null else blockedFloors[floor.pid]

  fun blockAuthor(floor: FloorRenderItem) {
    val name = floor.user?.name ?: "该用户"
    val rule = createFilterRule(
      FilterRuleInput(kind = FilterRuleKind.USER, value = name, uid = floor.profileUid),
      System.currentTimeMillis() / 1000,
    )
    deps.scope.launch {
      deps.settings.updateFilterRules { current ->
        upsertFilterRule(current.mapNotNull { it.toMatchRule() }, rule).map { it.toStoredRule() }
      }
      snackbarState.value = SnackbarMessage(
        text = "已屏蔽 $name,其发言将折叠",
        actionLabel = "撤销",
        action = { undoBlockAuthor(rule.id) },
      )
    }
  }

  fun undoBlockAuthor(ruleId: String) {
    deps.scope.launch {
      deps.settings.updateFilterRules { current ->
        removeFilterRule(current.mapNotNull { it.toMatchRule() }, ruleId).map { it.toStoredRule() }
      }
    }
  }

  private fun rebuildChainIndex() {
    chainJob?.cancel()
    val snapshot = pages.values.filterIsInstance<PageState.Loaded>().map { it.model }
    if (snapshot.isEmpty()) return
    chainJob = viewModelScope.launch {
      val depths = withContext(compute) {
        val floors = LinkedHashMap<Long, QuoteIndexFloor>()
        for (model in snapshot) {
          for (floor in model.floors + model.hotReplies) {
            floors.getOrPut(floor.pid) {
              QuoteIndexFloor(pid = floor.pid, lou = floor.lou, refs = floor.quoteRefs)
            }
          }
        }
        val index = buildQuoteIndex(floors.values.toList(), tid)
        floors.keys.associateWith { chainDepthOf(index, it) }
      }
      chainDepths = depths
    }
  }

  fun chainDepthOf(floor: FloorRenderItem): Int = chainDepths[floor.pid] ?: 0

  private fun recordVisit(model: PageRenderModel) {
    if (progressPaused) return
    viewModelScope.launch {
      deps.history.recordVisit(
        TopicVisit(
          tid = tid,
          subject = model.subject,
          author = model.starterName,
          boardName = model.boardName,
          favCode = key.fav,
          maxFloor = maxOf(0, (model.totalRows - 1).toInt()),
        ),
        System.currentTimeMillis() / 1000,
      )
    }
  }

  fun reportVisibleFloor(maxLou: Long) {
    if (progressPaused || maxLou < 0) return
    deps.history.recordReadFloor(tid, maxLou.toInt())
  }

  fun flushReadFloor() {
    deps.scope.launch { deps.history.flushReadFloor() }
  }

  fun dismissResume() {
    resumeDismissed = true
  }

  fun jumpToResume() {
    val floor = resumeFloor ?: return
    val model = currentModel ?: return
    dismissResume()
    pendingFloor = floor
    toast("已跳转到第 $floor 楼")
    val target = pageOfFloor(floor.toInt(), model.rowsPerPage)
    if (target == page) redeemPendingFloor() else goToPage(target)
  }

  private fun redeemPendingFloor() {
    val floor = pendingFloor ?: return
    val model = (pages[page] as? PageState.Loaded)?.model ?: return
    val index = floorScrollIndex(
      floorLous = model.floors.map { it.lou },
      targetFloor = floor,
      page = model.page,
      rowsPerPage = model.rowsPerPage,
    ) ?: return
    pendingFloor = null
    scrollTarget = ScrollTarget(page = page, listIndex = index)
  }

  fun consumeScrollTarget() {
    scrollTarget = null
  }

  fun recommend(floor: FloorRenderItem, action: RecommendAction, onNeedLogin: () -> Unit) {
    val pid = floor.recommendPid
    viewModelScope.launch {
      if (deps.credentials.current() == null) {
        onNeedLogin()
        return@launch
      }
      if (!recommendPending.add(pid)) return@launch
      val before = recommendMarks[pid] ?: RecommendMark()
      recommendMarks[pid] = RecommendMark(
        state = nextRecommendState(before.state, action),
        scoreDelta = before.scoreDelta + expectedRecommendDelta(before.state, action),
      )
      try {
        val result = postRecommend(deps.client, tid, pid, action)
        recommendMarks[pid] = RecommendMark(
          state = result.state,
          scoreDelta = before.scoreDelta + result.delta,
        )
      } catch (cause: CancellationException) {
        throw cause
      } catch (cause: Exception) {
        recommendMarks[pid] = before
        toast(cause.message ?: "操作失败,稍后再试")
      } finally {
        recommendPending.remove(pid)
      }
    }
  }

  fun markOf(floor: FloorRenderItem): RecommendMark? = recommendMarks[floor.recommendPid]

  fun cacheCurrentPage(onOpenCaches: () -> Unit) {
    if (currentModel == null) {
      toast("这一页还没加载出来")
      return
    }
    viewModelScope.launch {
      deps.topicCache.warmUp()
      if (deps.topicCache.isPageCached(tid, page)) {
        snackbarState.value = SnackbarMessage("本页已缓存,可离线阅读", "查看", onOpenCaches)
        return@launch
      }
      report(deps.repository.cacheTopicPages(tid, listOf(page), key.fav), onOpenCaches)
    }
  }

  fun cacheWholeTopic(onOpenCaches: () -> Unit) {
    if (currentModel == null) {
      toast("这一页还没加载出来")
      return
    }
    val pageList = (1..totalPages).toList()
    viewModelScope.launch {
      report(deps.repository.cacheTopicPages(tid, pageList, key.fav), onOpenCaches)
    }
  }

  fun cancelCacheDownload() = deps.repository.cancelCacheDownload()

  private fun report(outcome: CacheDownloadOutcome, onOpenCaches: () -> Unit) {
    when (outcome) {
      is CacheDownloadOutcome.Busy -> toast("已经有主题在缓存了,等它跑完再来")
      is CacheDownloadOutcome.Failed ->
        toast("缓存中断:${outcome.message}(已存 ${outcome.cached} 页)")
      is CacheDownloadOutcome.Cancelled -> toast("已停止缓存,已存 ${outcome.cached} 页")
      is CacheDownloadOutcome.Done ->
        snackbarState.value =
          SnackbarMessage("已缓存 ${outcome.cached} 页,可离线阅读", "查看", onOpenCaches)
    }
  }

  fun retryNative() {
    deps.repository.forgetReadPhpCombo()
    sourceNoticeDismissed = false
    refresh()
  }

  fun dismissSourceNotice() {
    sourceNoticeDismissed = true
  }

  val source: TopicSource? get() = currentModel?.source

  fun openSignature(floor: FloorRenderItem) {
    val user = floor.user ?: return
    val style = style ?: return
    signatureDialog = SignatureDialogState(user = user, model = null)
    val signature = user.signature
    if (signature.isNullOrEmpty()) return
    val attachBase = currentModel?.attachBase ?: ATTACH_BASE_FALLBACK
    viewModelScope.launch {
      val model = withContext(compute) {
        RenderModelBuilder.build(
          parseBBCode(signature),
          signatureRenderOptions(
            BBCodeRenderOptions(
              attachBase = attachBase,
              colors = style.colors,
              bodyFontSize = style.bodyFontSize,
              bodyLineHeight = style.bodyLineHeight,
              attachmentUrls = deps.attachmentUrls,
            ),
          ),
        )
      }
      if (signatureDialog?.user?.key == user.key) {
        signatureDialog = SignatureDialogState(user = user, model = model)
      }
    }
  }

  fun closeSignature() {
    signatureDialog = null
  }

  val currentModel: PageRenderModel? get() = (pages[page] as? PageState.Loaded)?.model

  val resumeVisible: Boolean
    get() = resumeFloor != null && !resumeDismissed && !progressPaused && currentModel != null

  fun toast(text: String) {
    toastState.value = ToastMessage(text)
  }

  fun consumeToast() {
    toastState.value = null
  }

  fun consumeSnackbar() {
    snackbarState.value = null
  }

  override fun onCleared() {
    flushReadFloor()
    super.onCleared()
  }

  companion object {
    const val DEFAULT_ROWS_PER_PAGE = 20
  }
}

@Immutable
sealed interface PageState {
  data object Loading : PageState

  data class Loaded(val model: PageRenderModel) : PageState

  data class Failed(val error: Throwable) : PageState
}

@Immutable
data class OnlyUser(val uid: Long, val name: String)

@Immutable
data class ScrollTarget(val page: Int, val listIndex: Int)

@Immutable
data class SignatureDialogState(val user: FloorUser, val model: FloorRenderModel?)

@Immutable
data class SnackbarMessage(val text: String, val actionLabel: String?, val action: (() -> Unit)?)

@Immutable
data class ToastMessage(val text: String)
