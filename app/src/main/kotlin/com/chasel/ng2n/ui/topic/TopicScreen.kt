package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.Motion
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
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
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
import com.chasel.ng2n.data.account.AccountsState
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.ui.bbcode.HotRepliesSection
import com.chasel.ng2n.ui.common.ListPullToRefreshBox
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.favorites.FavoriteFolderDialog
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalTextScale
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val PAGER_FRAME_RATE = 120f

private val TOPIC_TITLE_MAX_WIDTH = 190.dp

const val NOT_AVAILABLE_MESSAGE: String = "本版本未开放"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(key: TopicKey, nav: Navigator) {
  val vm = rememberTopicViewModel(key)
  val colors = LocalNg2nColors.current
  val textScale = LocalTextScale.current
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val deps = rememberAppDeps()
  val settings = vm.settings

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

  var favorOpen by remember { mutableStateOf(false) }
  val accountsState: AccountsState? by deps.accounts.accounts
    .collectAsStateWithLifecycle(initialValue = null)
  val signedIn = accountsState?.let { currentAccountOf(it) != null }
  val openFavor = {
    if (signedIn == false) showLoginPrompt(nav, "登录后才能收藏") else favorOpen = true
  }

  val notAvailable = remember(context) { { showNotAvailable(context) } }
  val webKey = remember(key, settings.host, vm.page, vm.currentModel?.subject) {
    topicWebKey(key, vm.page, settings.host, vm.currentModel?.subject)
  }

  val actions = remember(vm, nav, uriHandler, notAvailable) {
    FloorActions(
      onOpenImage = { floor, url ->
        val index = floor.images.indexOfFirst { it.url == url }
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
        maxWidth = TOPIC_TITLE_MAX_WIDTH,
      )
      Spacer(Modifier.weight(1f))
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
      onFavor = openFavor,
      notAvailable = notAvailable,
      nav = nav,
    ),
    top = statusTop + 6.dp,
    leftHanded = settings.leftHanded,
    onClose = { menuOpen = false },
  )

  floorMenu?.let { floor ->
    OverflowMenu(
      open = true,
      items = floorMenuItems(
        vm = vm,
        floor = floor,
        onClose = { floorMenu = null },
        onFavor = openFavor,
        notAvailable = notAvailable,
      ),
      top = statusTop + 300.dp,
      leftHanded = settings.leftHanded,
      onClose = { floorMenu = null },
    )
  }

  SignatureDialog(state = vm.signatureDialog, onClose = vm::closeSignature)

  FavoriteFolderDialog(open = favorOpen, tid = vm.tid, onClose = { favorOpen = false })

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopicPager(vm: TopicViewModel, actions: FloorActions, nav: Navigator) {
  val pagerState = rememberPagerState(
    initialPage = (vm.page - 1).coerceAtLeast(0),
    pageCount = { pagerPageCount(vm.totalPages, vm.page) },
  )

  var shownPage by remember { mutableIntStateOf(vm.page) }
  LaunchedEffect(vm.page) {
    val target = (vm.page - 1).coerceIn(0, pagerPageCount(vm.totalPages, vm.page) - 1)
    val move = pageTurnFor(
      fromPage = shownPage,
      toPage = target + 1,
      pagerPage = pagerState.currentPage + 1,
    )
    shownPage = target + 1
    when (move) {
      PageTurn.NONE -> Unit
      PageTurn.ANIMATE -> withContext(FullMotion) {
        pagerState.animateScrollToPage(
          page = target,
          animationSpec = tween(Motion.DURATION_PANEL, easing = Motion.easeDecelerate),
        )
      }
      PageTurn.JUMP -> pagerState.scrollToPage(target)
    }
  }
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect { settled ->
      vm.goToPage(settled + 1)
    }
  }
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.targetPage }.collect { target ->
      vm.setPageInFlight(target + 1)
      if (target + 1 != vm.page) vm.dismissResume()
    }
  }

  HorizontalPager(
    state = pagerState,
    beyondViewportPageCount = 1,
    snapPosition = SnapPosition.Start,
    modifier = Modifier.fillMaxSize().preferredFrameRate(PAGER_FRAME_RATE),
    key = { it },
  ) { index ->
    val page = index + 1
    LaunchedEffect(page) { if (page == vm.page) vm.ensureLoaded(page) }
    TopicPageView(vm = vm, page = page, live = page == vm.page, actions = actions, nav = nav)
  }
}

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
    TopicLoadingScreen(page = page)
    return
  }
  if (state is PageState.Failed) {
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
    ReadingProgressReporter(vm = vm, listState = listState, model = model)
    EndReachedReporter(vm = vm, listState = listState, count = model.floors.size)
    LaunchedEffect(listState, page) {
      snapshotFlow { vm.scrollTarget }.collect { target ->
        if (target == null || target.page != page) return@collect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > target.listIndex }
        vm.consumeScrollTarget()
        listState.scrollToItem(target.listIndex)
      }
    }
    LaunchedEffect(listState) {
      listState.interactionSource.interactions.collect { interaction ->
        if (interaction is DragInteraction.Start) {
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
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state) { refreshing = false }
    ListPullToRefreshBox(
      isRefreshing = refreshing,
      onRefresh = {
        refreshing = true
        vm.refresh(page)
      },
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

    item(key = ListKeys.FOOTER, contentType = "footer") {
      Box(Modifier.height(90.dp + bottomInset))
    }
  }
}

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

private const val VIEWABLE_THRESHOLD = 0.20f

@Composable
private fun EndReachedReporter(vm: TopicViewModel, listState: LazyListState, count: Int) {
  val reached by remember(listState, count) {
    derivedStateOf {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
      shouldTurnPageAtEnd(
        lastVisibleIndex = last.index,
        totalItemsCount = info.totalItemsCount,
        scrolling = listState.isScrollInProgress,
      )
    }
  }
  LaunchedEffect(reached) { if (reached) vm.onReachedEnd() }
}

fun shouldTurnPageAtEnd(lastVisibleIndex: Int, totalItemsCount: Int, scrolling: Boolean): Boolean =
  !scrolling && totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1

fun shouldAnimatePageTurn(fromPage: Int, toPage: Int): Boolean = abs(toPage - fromPage) == 1

enum class PageTurn {
  NONE,

  ANIMATE,

  JUMP,
}

fun pageTurnFor(fromPage: Int, toPage: Int, pagerPage: Int): PageTurn = when {
  pagerPage == toPage -> PageTurn.NONE
  shouldAnimatePageTurn(fromPage, toPage) -> PageTurn.ANIMATE
  else -> PageTurn.JUMP
}

private object FullMotion : MotionDurationScale {
  override val scaleFactor: Float get() = 1f
}

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

private fun topicMenuItems(
  vm: TopicViewModel,
  onClose: () -> Unit,
  onJump: () -> Unit,
  onFavor: () -> Unit,
  notAvailable: () -> Unit,
  nav: Navigator,
): List<MenuItem> {
  fun pick(run: () -> Unit): () -> Unit = {
    onClose()
    run()
  }
  return listOf(
    MenuItem("jump", "跳页", onClick = pick(onJump)),
    MenuItem("copy", "复制链接", onClick = pick(notAvailable)),
    MenuItem("favor", "收藏本帖", onClick = pick(onFavor)),
    MenuItem("cache-page", "缓存本页", onClick = pick { vm.cacheCurrentPage { } }),
    MenuItem("cache-topic", "缓存整帖", onClick = pick { vm.cacheWholeTopic { } }),
    MenuItem("share", "分享", onClick = pick(notAvailable)),
    MenuItem("theme", "夜间模式", gapBefore = true, onClick = pick(notAvailable)),
  )
}

private fun floorMenuItems(
  vm: TopicViewModel,
  floor: FloorRenderItem,
  onClose: () -> Unit,
  onFavor: () -> Unit,
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
    MenuItem("favor", "收藏", onClick = pick(onFavor)),
    MenuItem("only-user", "只看此人", gapBefore = true, onClick = pick { vm.enterOnlyUser(floor) }),
    MenuItem("block", "屏蔽此人", onClick = pick { vm.blockAuthor(floor) }),
  )
}

internal fun webUrlOf(tid: Long, page: Int, favCode: String?, host: String): String {
  val fav = if (favCode == null) "" else "&fav=$favCode"
  return "$host/read.php?tid=$tid&page=$page$fav"
}

internal fun topicWebKey(key: TopicKey, page: Int, host: String, subject: String? = null): WebKey =
  WebKey(
    url = webUrlOf(key.tid, page, key.fav, host),
    title = key.title ?: subject,
  )

private fun showNotAvailable(context: Context) {
  Toast.makeText(context, NOT_AVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
}
