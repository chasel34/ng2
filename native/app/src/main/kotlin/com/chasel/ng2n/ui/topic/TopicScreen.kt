package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.ChainKey
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.nav.WebKey
import com.chasel.ng2n.ui.Login
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.chasel.ng2n.core.api.TopicSource
import com.chasel.ng2n.core.local.filterMatchText
import com.chasel.ng2n.ui.bbcode.HotRepliesSection
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalTextScale
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/** 楼层流与横滑翻页请求的刷新率(Hz)。120Hz 屏上把这两面钉在满帧档。 */
private const val PAGER_FRAME_RATE = 120f

/** 未实现功能的统一提示文案(RN 侧 `NOT_AVAILABLE_MESSAGE`)。 */
const val NOT_AVAILABLE_MESSAGE: String = "本版本未开放"

/**
 * 主题详情屏(CONTEXT.md:主题里的楼层流)。
 *
 * 翻页有三个入口 —— 顶部页码条、跳页对话框、左右滑动 —— 它们都只改
 * [TopicViewModel.page],所以三者天然一致;每页的渲染成品按页码常驻在 ViewModel,
 * 翻回去不会再打一次 `read.php`。
 *
 * ## 与 RN 版的结构差别(都是**拆补丁**,不是加功能)
 *
 * RN 版为了压住 JS 单线程的首帧成本挂了三层分帧补丁,这里**全部拆掉**
 * (票 11 Comments 票外 1 的决策):
 *
 * - `CONTENT_MOUNT_DELAY_MS`(转场期只画顶栏 + loading,列表壳等横推停稳再挂)——
 *   拆。Compose 的首帧成本在 UI 线程上,不与网络/解析抢同一根线程,而建模整个在
 *   `Dispatchers.Default`;转场期本来就没有重活可挂。
 * - `chromeReady`(页码条 / FAB / 浮条等第 2 帧)—— 拆。同上。
 * - `progressive.tsx` 的段级/楼级分帧(单张楼层卡 ~5.5ms、整页一帧 40ms+)—— 拆。
 *   `LazyColumn` 本来就只组合视口内的项,一帧不会挂 20 张卡。
 *
 * **挂钩**:票 19 真机若量到「起手冻结 > 1 丢帧」或「快甩有断续」,再按需加回
 * ——加的顺序是先看 `LazyColumn` 的 item 是否过重(把长楼层按段切成多个 item),
 * 而不是把 RN 那套 rAF 分帧照搬过来(Compose 没有那个问题的成因)。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(key: TopicKey, nav: Navigator) {
  val vm = rememberTopicViewModel(key)
  val colors = LocalNg2nColors.current
  val textScale = LocalTextScale.current
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val settings = vm.settings

  // 建模要的样式:配色与字号在 composition 里才知道,灌给 ViewModel;变了整页在后台重建
  LaunchedEffect(colors, textScale, settings.showSignature) {
    vm.applyStyle(
      TopicRenderStyle(
        colors = colors,
        bodyFontSize = textScale.bodyFontSize,
        bodyLineHeight = textScale.bodyLineHeight,
        showSignature = settings.showSignature,
      ),
    )
  }

  // 退到后台也要把攒着的阅读进度落盘(RN 侧 AppState change 那一条)
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushReadFloor() }
  DisposableEffect(Unit) { onDispose { vm.flushReadFloor() } }

  val toast by vm.toast.collectAsStateWithLifecycle()
  LaunchedEffect(toast) {
    toast?.let {
      Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show()
      vm.consumeToast()
    }
  }
  val snackbar by vm.snackbar.collectAsStateWithLifecycle()
  val download by vm.cacheDownload.collectAsStateWithLifecycle()

  var jumpOpen by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  var floorMenu by remember { mutableStateOf<FloorRenderItem?>(null) }

  val notAvailable = remember(context) { { showNotAvailable(context) } }
  val webKey = remember(key, settings.host, vm.page, vm.currentModel?.subject) {
    topicWebKey(key, vm.page, settings.host, vm.currentModel?.subject)
  }

  val actions = remember(vm, nav, uriHandler, notAvailable) {
    FloorActions(
      onOpenImage = { floor, url ->
        val index = floor.images.indexOfFirst { it.url == url }
        // 签名档里的图不算「本楼图片」,反查不到就单开一张,
        // 别让查看器里冒出计数对不上的翻页
        val viewer = if (index >= 0) {
          ImageViewerKey(
            urls = floor.images.map { it.url },
            index = index,
            thumbnailUrls = floor.images.map { it.thumbnailUrl ?: it.url },
          )
        } else {
          ImageViewerKey(urls = listOf(url), index = 0)
        }
        nav.push(viewer)
      },
      onRecommend = { floor, action ->
        vm.recommend(floor, action) { vm.toast("登录后才能点赞点踩") }
      },
      onOpenMenu = { floorMenu = it },
      onOpenProfile = { floor ->
        // 资料屏归票 17,这里先 push 一个占位 key
        floor.profileUid?.let { nav.push(UserKey(uid = it, name = floor.displayName)) }
      },
      onOpenChain = { floor ->
        nav.push(ChainKey(tid = vm.tid, pid = floor.pid, fav = key.fav))
      },
      onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
      onOpenUser = { uid ->
        uid.toLongOrNull()?.let { nav.push(UserKey(uid = it)) }
      },
      onOpenTopic = { tid ->
        tid.toLongOrNull()?.let { nav.push(TopicKey(tid = it)) }
      },
      onOpenFloorRef = { args ->
        // `[pid=pid,tid,page]`:有 tid 就开那个主题的那一页,没有就在本帖定位
        val parts = args.split(",")
        val pid = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@FloorActions
        val tid = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: vm.tid
        val page = parts.getOrNull(2)?.trim()?.toIntOrNull()
        if (tid == vm.tid) {
          nav.push(ChainKey(tid = tid, pid = pid, fav = key.fav))
        } else {
          nav.push(TopicKey(tid = tid, page = page))
        }
      },
      onNotAvailable = notAvailable,
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(rootBackground(colors, settings.solidBackground)),
  ) {
    TopicTopBar(
      below = {
        PageBar(
          // 高亮认横滑松手那一刻就先切过去的目标页(对齐原生 pager 的 onPageSelected 时机);
          // 真正的数据/窗口挪动等停稳后的 onChange
          page = vm.pageInFlight ?: vm.page,
          totalPages = vm.totalPages,
          onPick = vm::goToPage,
          onJump = { jumpOpen = true },
        )
      },
    ) {
      TopBarButton(onClick = nav::pop, label = "返回", box = 46.dp) {
        BackArrowIcon(tint = colors.onTopbar)
      }
      TopBarTitle(
        text = key.title ?: vm.currentModel?.subject ?: "主题 ${key.tid}",
        modifier = Modifier.weight(1f),
      )
      // 「用网页版打开」= **站内**网页兜底屏(反封锁链链外第 6 步,票 22),
      // 与版块页同一条路(`ui/board/BoardScreen.kt`)。跳系统浏览器等于把这一屏的
      // cookie / UA 交给 Chrome 的 cookie 罐,登录态与反封锁的那套请求头全丢
      TopBarButton(
        onClick = { nav.push(webKey) },
        label = "用网页版打开",
      ) {
        GlobeIcon(tint = colors.onTopbar)
      }
      TopBarButton(onClick = { menuOpen = true }, label = "更多") {
        OverflowIcon(tint = colors.onTopbar)
      }
    }

    // 这一页不是原生接口直出的:要么是 Web 反解,要么是本机缓存还原 —— 都是反封锁链的
    // 兜底档(ADR-0002)。钉在页码条下面而不是跟着列表滚:它说的是「整页数据的来源」
    val source = vm.source
    if (source != null && source != TopicSource.NATIVE && !vm.sourceNoticeDismissed) {
      SourceNoticeBar(source = source, onRetry = vm::retryNative, onDismiss = vm::dismissSourceNotice)
    }
    if (download.tid == vm.tid) {
      CacheProgressBar(done = download.done, total = download.total, onStop = vm::cancelCacheDownload)
    }
    if (vm.onlyPid != null) OnlyFloorBar(onShowAll = vm::exitOnlyPid)

    Box(Modifier.weight(1f)) {
      TopicPager(vm = vm, actions = actions, nav = nav)

      // 「上次读到第 N 楼」浮层压在列表上方,不占布局。
      // 只看某一楼/只看此人期间楼号是过滤后的口径,跳过去会落错地方,一律不放
      val resumeFloor = vm.resumeFloor
      if (resumeFloor != null && vm.onlyPid == null) {
        LastReadBanner(
          floor = resumeFloor,
          visible = vm.resumeVisible,
          onAutoHide = vm::dismissResume,
          onJump = vm::jumpToResume,
          onClose = vm::dismissResume,
        )
      }
    }
  }

  TopicFab(
    leftHanded = settings.leftHanded,
    onRefresh = { vm.refresh() },
    onReply = notAvailable,
  )

  SnackbarHost(message = snackbar, dark = colors == com.chasel.ng2n.ui.theme.DarkColors) {
    vm.consumeSnackbar()
  }

  val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  OverflowMenu(
    open = menuOpen,
    items = topicMenuItems(
      vm = vm,
      onClose = { menuOpen = false },
      onJump = { jumpOpen = true },
      notAvailable = notAvailable,
      nav = nav,
    ),
    top = statusTop + 6.dp,
    leftHanded = settings.leftHanded,
    onClose = { menuOpen = false },
  )

  // 楼层菜单:弹出位置照设计稿 menuTop 的 300
  floorMenu?.let { floor ->
    OverflowMenu(
      open = true,
      items = floorMenuItems(
        vm = vm,
        floor = floor,
        onClose = { floorMenu = null },
        notAvailable = notAvailable,
      ),
      top = statusTop + 300.dp,
      leftHanded = settings.leftHanded,
      onClose = { floorMenu = null },
    )
  }

  SignatureDialog(state = vm.signatureDialog, onClose = vm::closeSignature)

  InputDialog(
    open = jumpOpen,
    title = "跳转到页码",
    hint = "共 ${vm.totalPages} 页 · 输入 1 – ${vm.totalPages}",
    confirmLabel = "跳转",
    initialValue = vm.page.toString(),
    onCancel = { jumpOpen = false },
    onConfirm = {
      jumpOpen = false
      vm.jumpTo(it)
    },
  )
}

/**
 * 横滑翻页。
 *
 * `HorizontalPager` + `beyondViewportPageCount = 1` 就是 RN 侧那套「相邻页预渲染」
 * 的原生对应物,而**速度连续的松手接管是免费的**(`research/inventory.md` §8:
 * RN 的收尾弹簧 stiffness 500 / damping 48 本来就是对拍原生 ViewPager 逐帧调出来的)。
 *
 * 两条纪律:
 *
 * 1. **翻页回调里不挂重渲染**:`targetPage`(松手定向那一刻就变)只喂页码条高亮,
 *    真正换数据等 `settledPage`;
 * 2. **子面板必须落在容器布局边界内**(`docs/perf-playbook.md` P1):这里全靠 Pager
 *    自己布局,没有「布局在外面再 transform 拉回来」的写法。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopicPager(vm: TopicViewModel, actions: FloorActions, nav: Navigator) {
  // pageCount 必须装得下当前页,否则 `PagerState` 会把 `currentPage` 钳回 0,
  // 紧接着 settledPage 把这一下回写成「用户翻到第 1 页」(票 20,见 [pagerPageCount])
  val pagerState = rememberPagerState(
    initialPage = (vm.page - 1).coerceAtLeast(0),
    pageCount = { pagerPageCount(vm.totalPages, vm.page) },
  )

  // 外部换页(页码条 / 跳页 / 自动翻页)→ 把 pager 挪过去
  LaunchedEffect(vm.page) {
    val target = (vm.page - 1).coerceIn(0, pagerPageCount(vm.totalPages, vm.page) - 1)
    if (pagerState.currentPage != target) pagerState.scrollToPage(target)
  }
  // 横滑松手 → 停稳后才换数据
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect { settled ->
      vm.goToPage(settled + 1)
    }
  }
  // 松手定向的那一刻页码条先切过去(对齐原生 pager 的 onPageSelected 时机)
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.targetPage }.collect { target ->
      vm.setPageInFlight(target + 1)
      // 横滑松手就定向了,「上次读到」浮条不等 commit,当场淡出
      if (target + 1 != vm.page) vm.dismissResume()
    }
  }

  HorizontalPager(
    state = pagerState,
    // 相邻页预渲染 —— 方案 A 的「无缝」靠它们真的画得出来
    beyondViewportPageCount = 1,
    snapPosition = SnapPosition.Start,
    // 滚动面投 120Hz(stack-2026-08 §12 ⑤:`Modifier.preferredFrameRate`,
    // Compose UI 1.12 起是正式 API)。窗口级还有 MainActivity 的 preferHighestRefreshRate 兜底
    modifier = Modifier.fillMaxSize().preferredFrameRate(PAGER_FRAME_RATE),
    key = { it },
  ) { index ->
    val page = index + 1
    // 相邻页在屏外也要把数据备好(RN 侧「下一页顺手预取、上一页只读缓存」)
    LaunchedEffect(page) { if (page == vm.page) vm.ensureLoaded(page) }
    TopicPageView(vm = vm, page = page, live = page == vm.page, actions = actions, nav = nav)
  }
}

/**
 * 一块翻页面板。
 *
 * `live` 为真就是屏幕正中那一页(完整接线);否则是相邻页的预览:只画楼层 ——
 * 提示条、热门回复、下拉刷新、自动翻页说的都是「你正在看的这一页」,
 * 跟着预览一起滑出来是错的。预览也不给滚:纵向滚动只属于主动页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicPageView(
  vm: TopicViewModel,
  page: Int,
  live: Boolean,
  actions: FloorActions,
  nav: Navigator,
) {
  val state = vm.pages[page]
  if (state == null || state is PageState.Loading) {
    // 画骨架而不是一个转圈:这块面板是跟着手指走的,转圈会让人以为「卡住了」
    PageSkeleton(page = page)
    return
  }
  if (state is PageState.Failed) {
    // 三个出路都要真的通:失败面板的兜底文案(`core/net/FetchDiagnostic.kt`)
    // 就在往「用网页版打开」和「重新登录账号」上引导,点不动等于教人点死钮(票 21)
    LoadFailed(
      error = state.error,
      onRetry = { vm.refresh(page) },
      onOpenWeb = { nav.push(topicWebKey(vm.key, page, vm.settings.host, vm.currentModel?.subject)) },
      onRelogin = { nav.push(Login) },
    )
    return
  }
  val model = (state as PageState.Loaded).model
  if (model.floors.isEmpty()) {
    EmptyPage(onRefresh = { vm.refresh(page) })
    return
  }

  val listState = rememberLazyListState()

  if (live) {
    // 阅读进度:哪些楼在屏上由列表报,记「看到过的最高楼层」(只前进)
    ReadingProgressReporter(vm = vm, listState = listState, model = model)
    // 「自动加载下一页」的到底判据
    EndReachedReporter(vm = vm, listState = listState, count = model.floors.size)
    // 待兑现的跳楼目标(带楼号进场 / 「回到那里」)
    LaunchedEffect(vm.scrollTarget, page) {
      val target = vm.scrollTarget ?: return@LaunchedEffect
      if (target.page != page) return@LaunchedEffect
      vm.consumeScrollTarget()
      // 头部有热门回复区时列表第 0 项是它,楼层要往后挪一格
      val offset = if (model.hotReplies.isNotEmpty()) 1 else 0
      listState.animateScrollToItem((target.index + offset).coerceAtLeast(0))
    }
    // 手指一拖就把「上次读到」浮层淡掉:它盖在楼层上,用户开始读了就该让路
    LaunchedEffect(listState) {
      snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
        if (scrolling) {
          vm.userScrolled = true
          vm.dismissResume()
        }
      }
    }
  }

  val content = @Composable {
    FloorList(
      vm = vm,
      model = model,
      listState = listState,
      live = live,
      actions = actions,
    )
  }

  if (live) {
    // 翻页时不该亮下拉转圈 —— 只有真正在刷新当前这一页时才亮
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state) { refreshing = false }
    PullToRefreshBox(
      isRefreshing = refreshing,
      onRefresh = {
        refreshing = true
        vm.refresh(page)
      },
      state = rememberPullToRefreshState(),
      modifier = Modifier.fillMaxSize(),
    ) { content() }
  } else {
    content()
  }
}

@Composable
private fun FloorList(
  vm: TopicViewModel,
  model: PageRenderModel,
  listState: LazyListState,
  live: Boolean,
  actions: FloorActions,
) {
  val settings = vm.settings
  val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

  LazyColumn(
    state = listState,
    userScrollEnabled = live,
    modifier = Modifier.fillMaxSize(),
  ) {
    if (live) {
      item(key = ListKeys.HEADER, contentType = "header") {
        Column {
          vm.onlyUser?.let { OnlyUserBar(name = it.name, onExit = vm::exitOnlyUser) }
          // 热门回复是服务端在主楼里标的,只有第 1 页拿得到
          if (model.hotReplies.isNotEmpty()) {
            HotRepliesSection(count = model.hotReplies.size) {
              model.hotReplies.forEach { floor ->
                FloorCard(
                  floor = floor,
                  mark = vm.markOf(floor),
                  chainDepth = vm.chainDepthOf(floor),
                  actions = actions,
                  showSignature = settings.showSignature,
                  imagesUnlocked = true,
                )
              }
            }
          }
        }
      }
    }

    items(
      items = model.floors,
      key = { it.pid },
      // 折叠行只有一行高、楼层卡动辄大半屏,混进同一个回收池会让列表反复重量
      contentType = { if (vm.blockedRuleOf(it) == null) "floor" else "blocked" },
    ) { floor ->
      val rule = vm.blockedRuleOf(floor)
      if (rule != null) {
        BlockedFloorRow(text = filterMatchText(rule), onExpand = { vm.expandFloor(floor.pid) })
      } else {
        FloorCard(
          floor = floor,
          mark = vm.markOf(floor),
          chainDepth = vm.chainDepthOf(floor),
          actions = actions,
          showSignature = settings.showSignature,
          imagesUnlocked = true,
        )
      }
    }

    // 设计稿在列表末尾留 90 给 FAB 让路
    item(key = ListKeys.FOOTER, contentType = "footer") {
      Box(Modifier.height(90.dp + bottomInset))
    }
  }
}

/**
 * 阅读进度上报。RN 侧是 `onViewableItemsChanged` + `itemVisiblePercentThreshold: 20`;
 * Compose 这边同一个口径:可见高度 ≥ 20% 的项才算「看到了」。
 *
 * 只前进的判断与 1s 节流批刷都在票 14 的 `ReadFloorThrottle` 里,这里只报数。
 */
@Composable
private fun ReadingProgressReporter(
  vm: TopicViewModel,
  listState: LazyListState,
  model: PageRenderModel,
) {
  LaunchedEffect(listState, model) {
    snapshotFlow {
      val info = listState.layoutInfo
      var maxLou = -1L
      for (item in info.visibleItemsInfo) {
        val key = item.key as? Long ?: continue
        val top = maxOf(item.offset, info.viewportStartOffset)
        val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
        val visible = (bottom - top).toFloat()
        if (item.size > 0 && visible / item.size >= VIEWABLE_THRESHOLD) {
          val floor = model.floors.firstOrNull { it.pid == key } ?: continue
          if (floor.lou > maxLou) maxLou = floor.lou
        }
      }
      maxLou
    }.collect { vm.reportVisibleFloor(it) }
  }
}

/** RN 侧 `viewabilityConfig.itemVisiblePercentThreshold: 20`。 */
private const val VIEWABLE_THRESHOLD = 0.20f

/** 「自动加载下一页」的到底判据(RN 侧 `onEndReachedThreshold: 0.4`)。 */
@Composable
private fun EndReachedReporter(vm: TopicViewModel, listState: LazyListState, count: Int) {
  val reached by remember(listState, count) {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
      val total = info.totalItemsCount
      // 末尾那一项(footer)进视口就算到底
      last.index >= total - 1
    }
  }
  LaunchedEffect(reached) { if (reached) vm.onReachedEnd() }
}

/**
 * FAB 及其展开菜单(设计稿 isArticle 256 / 261 行:动作列走 omup `.18s`,
 * FAB 自己的 `add` 转 45° 变成 `×`,`.2s`)。
 *
 * 左手模式下 FAB 与动作列整体镜像到左下角。
 */
@Composable
private fun TopicFab(leftHanded: Boolean, onRefresh: () -> Unit, onReply: () -> Unit) {
  val colors = LocalNg2nColors.current
  var open by remember { mutableStateOf(false) }
  val rise = remember { Animatable(0f) }
  val spin = remember { Animatable(0f) }
  LaunchedEffect(open) {
    spin.animateTo(if (open) 1f else 0f, tween(200))
  }
  LaunchedEffect(open) {
    rise.animateTo(if (open) 1f else 0f, tween(180))
  }
  val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

  Box(Modifier.fillMaxSize()) {
    if (open) {
      Column(
        modifier = Modifier
          .align(if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd)
          .padding(start = 22.dp, end = 22.dp, bottom = 96.dp + bottomInset)
          .graphicsLayer {
            alpha = rise.value
            translationY = (1f - rise.value) * 42f
          },
        horizontalAlignment = if (leftHanded) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // 回帖是 v1 排除项(spec §一.2),入口保留
        FabItem(label = "回复") {
          open = false
          onReply()
        }
        FabItem(label = "刷新") {
          open = false
          onRefresh()
        }
      }
    }
    Box(
      modifier = Modifier
        .align(if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd)
        .padding(start = Spacing.xl, end = Spacing.xl, bottom = 24.dp + bottomInset)
        .size(50.dp)
        .clip(CircleShape)
        .background(colors.fab)
        .clickable { open = !open }
        .semantics { contentDescription = if (open) "收起操作" else "展开操作" },
      contentAlignment = Alignment.Center,
    ) {
      // 设计稿是同一枚 add 转 45° 变成 ×,不是换字形
      Box(Modifier.graphicsLayer { rotationZ = spin.value * 45f }) {
        PlusIcon(tint = colors.onFab)
      }
    }
  }
}

@Composable
private fun FabItem(label: String, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .height(44.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(colors.menu)
      .clickable(onClick = onClick)
      .padding(horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    if (label == "刷新") RefreshIcon(tint = colors.primary) else ReplyIcon(tint = colors.primary)
    Text(label, fontSize = Typo.notice.size, color = colors.fg, fontWeight = FontWeight.Normal)
  }
}

/**
 * 顶栏「更多」菜单,条目与顺序照设计稿 `MENUS.article`。
 * 「缓存整帖」是设计稿没画的一条(票面要求),挨着「缓存本页」放。
 */
private fun topicMenuItems(
  vm: TopicViewModel,
  onClose: () -> Unit,
  onJump: () -> Unit,
  notAvailable: () -> Unit,
  nav: Navigator,
): List<MenuItem> {
  // 点哪一条都先收起菜单,免得动作做完了菜单还盖在上面
  fun pick(run: () -> Unit): () -> Unit = {
    onClose()
    run()
  }
  return listOf(
    MenuItem("jump", "跳页", onClick = pick(onJump)),
    MenuItem("copy", "复制链接", onClick = pick(notAvailable)),
    MenuItem("favor", "收藏本帖", onClick = pick(notAvailable)),
    MenuItem("cache-page", "缓存本页", onClick = pick { vm.cacheCurrentPage { } }),
    MenuItem("cache-topic", "缓存整帖", onClick = pick { vm.cacheWholeTopic { } }),
    MenuItem("share", "分享", onClick = pick(notAvailable)),
    MenuItem("theme", "夜间模式", gapBefore = true, onClick = pick(notAvailable)),
  )
}

/**
 * 楼层菜单,条目与顺序照设计稿 `MENUS.floor`(分组线在「只看此人」前)。
 *
 * 设计稿里还有「支持/反对」两条,这里不放:同一张卡片上方就是 👍/👎 两个钮,
 * 打的是同一个动作 —— 菜单只留卡片上没有的入口。
 */
private fun floorMenuItems(
  vm: TopicViewModel,
  floor: FloorRenderItem,
  onClose: () -> Unit,
  notAvailable: () -> Unit,
): List<MenuItem> {
  fun pick(run: () -> Unit): () -> Unit = {
    onClose()
    run()
  }
  return listOf(
    MenuItem("note", "贴条", onClick = pick(notAvailable)),
    MenuItem("report", "举报", onClick = pick(notAvailable)),
    MenuItem("sign", "查看签名", onClick = pick { vm.openSignature(floor) }),
    MenuItem("favor", "收藏", onClick = pick(notAvailable)),
    MenuItem("only-user", "只看此人", gapBefore = true, onClick = pick { vm.enterOnlyUser(floor) }),
    MenuItem("block", "屏蔽此人", onClick = pick { vm.blockAuthor(floor) }),
  )
}

/** 「用网页版打开」的网页地址。域名走设置里选的那个 —— 原生被封往往是整个域名被封。 */
internal fun webUrlOf(tid: Long, page: Int, favCode: String?, host: String): String {
  val fav = if (favCode == null) "" else "&fav=$favCode"
  return "$host/read.php?tid=$tid&page=$page$fav"
}

/**
 * 「用网页版打开」落到的**站内**兜底屏(票 21 / 票 22)。
 *
 * 顶栏那颗地球钮与失败面板上的同名按钮说的是同一件事,所以只有这一处在造键:
 * 两边各写一遍,迟早会像票 22 那样一边进站内、一边跳系统浏览器。
 *
 * 标题优先用键上带的(列表页点进来时就有),没有再退到这一帖真正的标题。
 */
internal fun topicWebKey(key: TopicKey, page: Int, host: String, subject: String? = null): WebKey =
  WebKey(
    url = webUrlOf(key.tid, page, key.fav, host),
    title = key.title ?: subject,
  )

private fun showNotAvailable(context: Context) {
  Toast.makeText(context, NOT_AVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
}
