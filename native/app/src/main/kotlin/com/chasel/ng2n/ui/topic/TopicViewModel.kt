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

/**
 * 主题详情屏的全部状态与动作。
 *
 * ## 三条纪律
 *
 * 1. **滚动路径零计算**:楼层的渲染成品([PageRenderModel])在数据到达时由
 *    [TopicRepository] 在后台一次建好;屏蔽命中、回复链层数也是后台算好的**表**,
 *    楼层卡只做查表。
 * 2. **页级常驻**:[pages] 是「页码 → 渲染成品」,翻页不丢已建好的模型,
 *    离开这一屏(ViewModel 随 Nav3 条目一起 clear)才释放。
 * 3. **翻页回调里不挂重渲染**:`HorizontalPager` 的 `targetPage` 只喂页码条高亮,
 *    真正换数据等停稳(RN 侧「onPageSelected 时机不挂重渲染」那条老账)。
 */
class TopicViewModel(
  val key: TopicKey,
  private val deps: TopicDeps,
  /** 后台算屏蔽命中 / 回复链层数 / 签名建模的调度器;单测换成测试调度器。 */
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  val tid: Long = key.tid

  // -------------------------------------------------------------------------
  // 屏级状态
  // -------------------------------------------------------------------------

  /** 屏上正看着的页码。三个入口(页码条 / 跳页 / 横滑)都只改它。 */
  var page by mutableStateOf(initialPage())
    private set

  /**
   * 只看某一楼(「我的回复」/ pid 深链)。**服务端不提供 pid → 页码的换算**
   * (实测 `read.php` 带 pid 只会把那一楼单独捞出来,`__PAGE` 恒为 1、`lou` 被重编为 0),
   * 所以落地方式就是 NGA 自己那套「只看该楼」。
   */
  var onlyPid by mutableStateOf(key.pid?.takeIf { it > 0 })
    private set

  /** 只看此人(服务端 authorid 过滤,翻页天然保持)。 */
  var onlyUser by mutableStateOf<OnlyUser?>(null)
    private set

  /** 页级渲染成品。key 是**请求的页码**(不是响应的 `__PAGE`)。 */
  val pages: SnapshotStateMap<Int, PageState> = mutableStateMapOf()

  var totalPages by mutableStateOf(1)
    private set

  /**
   * 横滑松手已定向、动画还没停稳的目标页(页码条高亮先行一步用)。
   * [page] 追上来就清账 —— commit 被吞的极端情况最多错到下一次翻页,不值得再挂个超时。
   */
  var pageInFlight by mutableStateOf<Int?>(null)
    private set

  /** 数据源提示条被关掉了没(设计稿 fallbackBar 带关闭钮)。 */
  var sourceNoticeDismissed by mutableStateOf(false)
    private set

  /** 被屏蔽规则折起来、又被用户手动点开的楼层;**只活在这次停留里**。 */
  val unfolded: SnapshotStateMap<Long, Unit> = mutableStateMapOf()

  /** pid → 命中的屏蔽规则。**在数据层一次算完**(修 P3-05),不在滚动路径上。 */
  var blockedFloors by mutableStateOf<Map<Long, FilterRule>>(emptyMap())
    private set

  /** pid → 回复链层数。引用块上「查看对话链(N 层)」的 N 从它来。 */
  var chainDepths by mutableStateOf<Map<Long, Int>>(emptyMap())
    private set

  /** 本会话的赞踩标记(服务端不下发「我赞过没有」,状态只能从本会话的操作里长出来)。 */
  val recommendMarks: SnapshotStateMap<Long, RecommendMark> = mutableStateMapOf()

  var settings by mutableStateOf(DEFAULT_SETTINGS)
    private set

  /** 建模的样式输入。由屏幕在配色/字号确定后灌进来;变了整页模型在后台重建。 */
  var style by mutableStateOf<TopicRenderStyle?>(null)
    private set

  /** 「上次读到第 N 楼」。进场那一刻读一次,之后的滚动不会改它。 */
  var resumeFloor by mutableStateOf<Long?>(null)
    private set

  /**
   * 提示条是不是已经收走了。**单向**:一旦为真,这次停留里就不再回到 false ——
   * 「消失」的语义是「我知道了」,不该因为翻回原来那页、或者退出只看此人就又冒出来。
   */
  var resumeDismissed by mutableStateOf(false)
    private set

  /** 待兑现的滚动目标(带楼号进场 / 「回到那里」)。屏幕消费完调 [consumeScrollTarget]。 */
  var scrollTarget by mutableStateOf<ScrollTarget?>(null)
    private set

  var signatureDialog by mutableStateOf<SignatureDialogState?>(null)
    private set

  /** 本页内用户是否亲手滚动过 —— 「自动加载下一页」只认真手指滚出来的到底。 */
  var userScrolled by mutableStateOf(false)

  private val snackbarState = MutableStateFlow<SnackbarMessage?>(null)
  val snackbar: StateFlow<SnackbarMessage?> = snackbarState.asStateFlow()

  private val toastState = MutableStateFlow<ToastMessage?>(null)
  val toast: StateFlow<ToastMessage?> = toastState.asStateFlow()

  /** 整帖缓存进度(仓库是单例,所以退出再进来还看得见)。 */
  val cacheDownload: StateFlow<CacheDownloadState> = deps.repository.cacheDownload

  // -------------------------------------------------------------------------
  // 内部状态
  // -------------------------------------------------------------------------

  private val loading = HashSet<Int>()
  private val recommendPending = HashSet<Long>()

  private var localRules: List<FilterRule> = emptyList()
  private var officialRules: List<FilterRule> = emptyList()
  private var blockJob: Job? = null
  private var chainJob: Job? = null

  /** 进场就要定位到的楼号(回复链的「在原帖中查看」);兑现完清掉。 */
  private var pendingFloor: Long? = key.floor?.takeIf { it >= 0 }

  /** 退出过滤时回到进入前的那一页。 */
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
      // 官方屏蔽词是账号级云端数据(API 文档 §11.5);游客拿不到,不发请求。
      // 失败也不吭声:本地规则照常生效,不该因为一次 ucp 失败把楼层流卡住
      val uid = deps.credentials.current()?.uid ?: return@launch
      val list = runCatching { fetchBlockWords(deps.client, uid) }.getOrDefault(EMPTY_BLOCK_WORDS)
      officialRules = officialFilterRules(list)
      recomputeBlocked()
    }
    viewModelScope.launch {
      // 进场那一刻的存档楼层。主楼都没读过(lastFloor 0)就不打扰
      deps.history.warmUp()
      val entry = deps.history.peek(tid)
      if (entry != null && entry.lastFloor >= 1) resumeFloor = entry.lastFloor.toLong()
    }
  }

  // -------------------------------------------------------------------------
  // 拉页
  // -------------------------------------------------------------------------

  /** 屏幕在配色/字号确定后调;首次调用触发第一发请求,之后变了就整页重建。 */
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
    // 只带楼号没带页码时按固定的每页 20 楼估算(read.php 的口径,API 文档 §3);
    // 真实 rowsPerPage 回来后 [redeemPendingFloor] 会再核对一次
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

  /** 没有就拉;已经有了(或正在拉)就什么都不做。 */
  fun ensureLoaded(target: Int) {
    if (target < 1) return
    if (pages[target] is PageState.Loaded || target in loading) return
    load(target, refresh = false, style = style ?: return)
  }

  /** 显式刷新(下拉刷新 / FAB 的刷新 / 提示条的重试原生):无视 2 分钟新鲜期。 */
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
    // **下一页顺手预取,上一页只读缓存**:上一页几乎总是刚看过的那一页,缓存里现成;
    // 为「说不定会往回翻」再打一发 read.php 不划算(ADR-0002,少打一发就少一分风险)。
    // 预取还要等当前页真的落地才发,不然一进屏就是两个并发请求。
    if (target == page && target < model.totalPages) ensureLoaded(target + 1)
  }

  // -------------------------------------------------------------------------
  // 翻页
  // -------------------------------------------------------------------------

  /**
   * 横滑松手定向那一刻先把页码条切过去(对齐原生 pager 的 onPageSelected 时机)。
   * **这里绝不能挂重渲染**:定向发生在动画还在跑的时候(RN 侧那条老账)。
   */
  fun setPageInFlight(target: Int) {
    pageInFlight = target.takeIf { it != page }
  }

  /** 页码条、跳页、横滑三个入口都收敛到这里 —— 页码规则只有一套([clampPage])。 */
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

  /** 往回翻只读缓存;缓存里没有就画骨架,不为「可能会往回翻」再打一发 `read.php`。 */
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

  /** 跳页对话框:**不夹逼**,输了个 999 该说超范围而不是默默跳到最后一页。 */
  fun jumpTo(input: String) {
    val target = parseJumpTarget(input, totalPages)
    if (target == null) {
      toast("请输入 1 – $totalPages 之间的页码")
      return
    }
    goToPage(target)
  }

  /**
   * 「自动加载下一页」(设置开关)。翻页中不触发,不然一口气能把好几页跳过去;
   * 只认用户亲手滚出来的到底,程序化滚动(跳楼落到页尾)不算。
   */
  fun onReachedEnd() {
    if (!settings.autoLoadNextPage) return
    if (!userScrolled) return
    if (page >= totalPages) return
    if (pages[page] !is PageState.Loaded) return
    goToPage(page + 1)
  }

  // -------------------------------------------------------------------------
  // 过滤视图
  // -------------------------------------------------------------------------

  /** 进入只看此人。匿名用户没有数字 uid,服务端过滤不了。 */
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

  /** 退出过滤恢复全楼,回到进入前那一页。 */
  fun exitOnlyUser() {
    onlyUser = null
    resetPagesForFilterChange(pageBeforeFilter)
  }

  /** 「看全部」:清掉只看该楼,回到整帖第 1 页。 */
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

  /**
   * 只看某一楼 / 只看此人期间**不记阅读进度**:屏上只有一楼时 `totalRows` 是 1,
   * 照记会把这个主题的历史楼数覆盖成 0;只看此人期间楼号与总数同样是过滤后的口径。
   */
  val progressPaused: Boolean get() = onlyPid != null || onlyUser != null

  // -------------------------------------------------------------------------
  // 屏蔽规则(修 P3-05:一次算完,不在滚动路径上)
  // -------------------------------------------------------------------------

  /**
   * 重算「哪些楼被折起来」。
   *
   * **RN 版是在 `renderItem` 里现算的**(`useFloorFilter` → `matchFilterRules`),
   * 也就是每次列表回收、每次屏级重渲染都跑一遍用户正则 —— 审计 P3-05 的落点。
   * 这里挪到数据层:规则表或页数据变了才算一次,而且整个在 `Dispatchers.Default` 上,
   * 楼层卡只做一次 `blockedFloors[pid]` 查表。
   */
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

  /** 这一楼是不是被屏蔽规则挡下的(点开过就不再折)。 */
  fun blockedRuleOf(floor: FloorRenderItem): FilterRule? =
    if (unfolded.containsKey(floor.pid)) null else blockedFloors[floor.pid]

  /**
   * 楼层菜单「屏蔽此人」:加一条本地用户规则,加完这一楼当场折起来。
   * 撤销就是把刚加的那条删掉 —— 规则 id 是内容算出来的,删的一定是这一条,
   * 不会误伤用户早先加过的同名规则以外的东西。
   */
  fun blockAuthor(floor: FloorRenderItem) {
    val name = floor.user?.name ?: "该用户"
    val rule = createFilterRule(
      FilterRuleInput(kind = FilterRuleKind.USER, value = name, uid = floor.profileUid),
      System.currentTimeMillis() / 1000,
    )
    viewModelScope.launch {
      deps.settings.updateFilterRules { current ->
        upsertFilterRule(current.mapNotNull { it.toMatchRule() }, rule).map { it.toStoredRule() }
      }
      snackbarState.value = SnackbarMessage(
        text = "已屏蔽 $name,其发言将折叠",
        actionLabel = "撤销",
        action = {
          viewModelScope.launch {
            deps.settings.updateFilterRules { current ->
              removeFilterRule(current.mapNotNull { it.toMatchRule() }, rule.id)
                .map { it.toStoredRule() }
            }
          }
        },
      )
    }
  }

  // -------------------------------------------------------------------------
  // 回复链索引
  // -------------------------------------------------------------------------

  /**
   * quote 关系索引:扫描本帖**已加载**的所有整页建索引,引用块上
   * 「查看对话链(N 层)」的 N 就从它来。
   *
   * RN 侧刻意延后 1.5s 起跑,因为 `buildQuoteIndex` 要把已加载楼层的 BBCode
   * **重新解析一遍**(JS 线程一口气 20–40ms,正撞上楼层分帧流入,2026-08-21 真机
   * 抓到内容流入中段 24–44ms 的停顿)。这里不需要那条补丁:引用早在建模时就抽好了
   * ([FloorRenderItem.quoteRefs]),这一步只是拼一张 Map,而且整个在
   * `Dispatchers.Default` 上 —— 不占主线程,也就没有让路的必要。
   */
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

  // -------------------------------------------------------------------------
  // 阅读进度与浏览历史
  // -------------------------------------------------------------------------

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

  /**
   * 滚动时上报屏上最高的楼层号(只前进;1s 节流批刷在票 14 的
   * `HistoryRepository` / `ReadFloorThrottle` 里)。这个函数不 suspend、不碰磁盘 ——
   * 它就落在滚动的那一帧上。
   */
  fun reportVisibleFloor(maxLou: Long) {
    if (progressPaused || maxLou < 0) return
    deps.history.recordReadFloor(tid, maxLou.toInt())
  }

  /**
   * 把攒着的阅读进度写下去。退到后台、离开这一屏各兜一次 ——
   * 最后 1 秒读到的楼层不能跟着页面一起丢。没有待落盘的东西时是纯 no-op。
   */
  fun flushReadFloor() {
    // 用 app 级 scope 而不是 viewModelScope:onCleared 时后者已经取消了
    deps.scope.launch { deps.history.flushReadFloor() }
  }

  fun dismissResume() {
    resumeDismissed = true
  }

  /** 「回到那里」:提示条随即消失 + toast「已跳转到第 N 楼」。 */
  fun jumpToResume() {
    val floor = resumeFloor ?: return
    val model = currentModel ?: return
    dismissResume()
    pendingFloor = floor
    toast("已跳转到第 $floor 楼")
    val target = pageOfFloor(floor.toInt(), model.rowsPerPage)
    if (target == page) redeemPendingFloor() else goToPage(target)
  }

  /**
   * 目标页的数据到位后给出滚动目标。
   *
   * 翻页期间屏上可能还是旧页,所以必须核对 `model.page` —— 不然会拿旧页的
   * 楼层号错滚一通(RN 侧同一条注释)。
   */
  private fun redeemPendingFloor() {
    val floor = pendingFloor ?: return
    val model = (pages[page] as? PageState.Loaded)?.model ?: return
    if (model.page != pageOfFloor(floor.toInt(), model.rowsPerPage)) return
    pendingFloor = null
    // 有楼层被删时 lou 有空洞,目标楼可能不在了:落到它后面最近的一楼
    val index = model.floors.indexOfFirst { it.lou >= floor }
    scrollTarget = ScrollTarget(
      page = page,
      index = if (index >= 0) index else (model.floors.size - 1).coerceAtLeast(0),
    )
  }

  fun consumeScrollTarget() {
    scrollTarget = null
  }

  // -------------------------------------------------------------------------
  // 赞踩
  // -------------------------------------------------------------------------

  /**
   * 赞/踩一层。乐观更新:点下去先按预测迁移变色变数,请求回来再用服务端 delta 校正;
   * 失败回滚到点击前。同一楼层的请求在途时按第二次是 no-op —— NGA 的赞踩没有幂等。
   *
   * 不吐 toast:变色 + 计数本身就是反馈(RN 侧同款决定)。
   */
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
        // 最终状态以服务端 delta 为准 —— 预测错了(比如别处已赞过)这里会拧回来
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

  // -------------------------------------------------------------------------
  // 缓存
  // -------------------------------------------------------------------------

  /**
   * 「缓存本页」。浏览过的页本来就自动缓存了([TopicRepository] 的 deferSnapshot),
   * 所以这一下通常只是确认一句,不必再打一次 `read.php` —— ADR-0002 的封号风险
   * 值得为一次「已经做过的事」省下来。
   */
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

  /** 「缓存整帖」:从第 1 页顺序拉到尾页。进度条与「停止」在页码条下面。 */
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

  // -------------------------------------------------------------------------
  // 数据来源提示条
  // -------------------------------------------------------------------------

  /**
   * 「重试原生」(设计稿 fallbackBar 的动作):Web 反解出来的这一页是兜底,
   * 用户想看原生渲染时再试一次。先忘掉 `read.php` 上次试通的组合 ——
   * 不清的话下一次还是从那个已经不灵的组合开局,等于白点一下。
   */
  fun retryNative() {
    deps.repository.forgetReadPhpCombo()
    sourceNoticeDismissed = false
    refresh()
  }

  fun dismissSourceNotice() {
    sourceNoticeDismissed = true
  }

  /** 这一页的来源;`NATIVE` 不出提示条。 */
  val source: TopicSource? get() = currentModel?.source

  // -------------------------------------------------------------------------
  // 查看签名
  // -------------------------------------------------------------------------

  /** 「查看签名」:点菜单那一刻就把用户定格下来,翻页不影响已开的弹窗。 */
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

  // -------------------------------------------------------------------------
  // 杂项
  // -------------------------------------------------------------------------

  val currentModel: PageRenderModel? get() = (pages[page] as? PageState.Loaded)?.model

  /** 提示条该不该在场。数据没到位时先不放出来(那会儿还在骨架,「回到那里」也点不动)。 */
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
    /** `read.php` 固定每页 20 楼(API 文档 §3),只带楼号进场时按它估页码。 */
    const val DEFAULT_ROWS_PER_PAGE = 20
  }
}

/** 一页的三种状态。 */
@Immutable
sealed interface PageState {
  data object Loading : PageState

  data class Loaded(val model: PageRenderModel) : PageState

  /** 反封锁链(ADR-0002)全档跑完还是没拿到数据 */
  data class Failed(val error: Throwable) : PageState
}

@Immutable
data class OnlyUser(val uid: Long, val name: String)

/** 待兑现的滚动目标(带楼号进场 / 「回到那里」)。 */
@Immutable
data class ScrollTarget(val page: Int, val index: Int)

@Immutable
data class SignatureDialogState(val user: FloorUser, val model: FloorRenderModel?)

@Immutable
data class SnackbarMessage(val text: String, val actionLabel: String?, val action: (() -> Unit)?)

@Immutable
data class ToastMessage(val text: String)
