package com.chasel.ng2n.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.HomeAnnouncement
import com.chasel.ng2n.core.api.parseBoardIdInput
import com.chasel.ng2n.core.api.pickActiveAnnouncement
import com.chasel.ng2n.core.local.NgaLink
import com.chasel.ng2n.core.local.NgaLinkResult
import com.chasel.ng2n.core.local.parseNgaLink
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.board.CheckInOutcome
import com.chasel.ng2n.data.board.withIconsFrom
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ConfirmDialog
import com.chasel.ng2n.ui.common.InputDialog
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.NOT_AVAILABLE_MESSAGE
import com.chasel.ng2n.ui.common.SnackbarAction
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateAction
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.drawer.AppDrawerContent
import com.chasel.ng2n.ui.drawer.DrawerEntryKey
import com.chasel.ng2n.ui.drawer.DrawerHost
import com.chasel.ng2n.ui.accounts.AccountHeader
import com.chasel.ng2n.ui.accounts.AccountsViewModel
import com.chasel.ng2n.ui.drawer.rememberDrawerHostState
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.AboutKey
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.nav.BookmarksKey
import com.chasel.ng2n.ui.nav.CachesKey
import com.chasel.ng2n.ui.nav.FavoriteFoldersKey
import com.chasel.ng2n.ui.nav.FavoritesKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.toNavKey
import com.chasel.ng2n.ui.nav.NotificationsKey
import com.chasel.ng2n.ui.nav.SearchKey
import com.chasel.ng2n.ui.nav.SettingsKey
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.UserPostKind
import com.chasel.ng2n.ui.nav.UserPostsKey
import com.chasel.ng2n.ui.Accounts
import com.chasel.ng2n.ui.Login
import com.chasel.ng2n.ui.SKELETON_READY_TAG
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlin.math.floor
import kotlinx.coroutines.launch

private val TAB_BAR_HEIGHT = 44.dp
private const val GRID_COLUMNS = 3

private val TAB_SCROLL_LEAD = 56.dp

private val TAB_BAR_PADDING = 6.dp

@Composable
fun HomeScreen(
  nav: Navigator,
  accounts: AccountsViewModel,
  onOpenDevMenu: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current

  val treeState by deps.boardTree.uiState.collectAsStateWithLifecycle()
  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = null,
  )
  val current = accountsState?.let(::currentAccountOf)
  val uid = current?.uid
  val signedIn = uid != null

  val favoriteStates by deps.boardFavorites.states.collectAsStateWithLifecycle()
  val favorites = if (uid == null) null else favoriteStates[uid]
  val dismissed by deps.boardTree.dismissedAnnouncements.collectAsStateWithLifecycle(emptyList())
  val unread by deps.notifications.unread.collectAsStateWithLifecycle()
  val checkInDays by deps.checkIn.days.collectAsStateWithLifecycle(emptyMap())
  val checkInPending by deps.checkIn.pendingUid.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { deps.boardTree.ensureLoaded() }
  LaunchedEffect(uid) { deps.boardFavorites.ensureLoaded(uid) }
  androidx.compose.runtime.DisposableEffect(Unit) {
    deps.notifications.start()
    onDispose { deps.notifications.stop() }
  }

  val drawer = rememberDrawerHostState()
  var addBoardOpen by remember { mutableStateOf(false) }
  var clearOpen by remember { mutableStateOf(false) }
  var urlOpen by remember { mutableStateOf(false) }
  var urlError by remember { mutableStateOf<String?>(null) }

  val tree = treeState.tree
  val categories = remember(tree) {
    if (tree == null) {
      emptyList()
    } else {
      listOf(BoardCategory(FAVORITES_CATEGORY_ID, "我的收藏")) + tree.categories
    }
  }

  val announcement = remember(tree, dismissed) {
    val available = (tree?.announcements ?: emptyList()).filter { it.id !in dismissed }
    pickActiveAnnouncement(available, System.currentTimeMillis())
      ?: BUILTIN_ANNOUNCEMENT.takeIf { it.id !in dismissed }
  }

  val icons = remember(tree) {
    val index = HashMap<Long, String>()
    tree?.categories?.forEach { category ->
      category.groups.forEach { group ->
        group.boards.forEach { board -> board.iconUrl?.let { index.putIfAbsent(board.id, it) } }
      }
    }
    index
  }
  val favoriteBoards = remember(favorites?.boards, icons) {
    (favorites?.boards ?: emptyList()).withIconsFrom(icons)
  }

  val openBoard: (Board) -> Unit = { board ->
    nav.push(BoardKey(id = board.id, name = board.name, kind = board.kind))
  }

  val placeholder: HomeRow = remember(signedIn, favorites) {
    when {
      !signedIn -> HomeRow.Notice(
        key = "notice/guest",
        icon = Ng2nIcon.PERSON_ADD,
        text = "登录后可查看云端收藏的版块",
        actionLabel = "去登录",
      )
      favorites?.loading == true -> HomeRow.Notice("notice/loading", Ng2nIcon.STAR, "正在载入我的收藏…")
      favorites?.error != null -> HomeRow.Failure("notice/error", favorites.error)
      else -> HomeRow.Notice(
        key = "notice/empty",
        icon = Ng2nIcon.STAR,
        text = "还没有收藏版块。进版块后点顶栏的星标,或用抽屉里的「添加版面 ID」。",
      )
    }
  }

  val rowsCache = remember(categories, announcement, favoriteBoards, placeholder) {
    HashMap<String, List<HomeRow>>()
  }
  val rowsFor: (BoardCategory) -> List<HomeRow> = { category ->
    rowsCache.getOrPut(category.id) {
      if (category.id == FAVORITES_CATEGORY_ID) {
        buildFavoriteRows(announcement, favoriteBoards, placeholder)
      } else {
        buildHomeRows(category, announcement)
      }
    }
  }

  val defaultIndex = if (signedIn) 0 else if (categories.size > 1) 1 else 0
  val pagerState = rememberPagerState(initialPage = defaultIndex) { categories.size }
  var initialised by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(categories.size, accountsState) {
    if (!initialised && accountsState != null && categories.isNotEmpty()) {
      initialised = true
      if (pagerState.currentPage != defaultIndex) pagerState.scrollToPage(defaultIndex)
    }
  }

  DrawerHost(
    state = drawer,
    drawerContent = {
      AppDrawerContent(
        accountHeader = {
          AccountHeader(
            viewModel = accounts,
            onOpenAccounts = {
              drawer.close(scope)
              nav.push(Accounts)
            },
            onLogin = {
              drawer.close(scope)
              nav.push(Login)
            },
          )
        },
        checkInStatus = when {
          uid == null -> null
          checkInDays[uid] == com.chasel.ng2n.data.settings.beijingDayKey(System.currentTimeMillis()) ->
            "今天已签到"
          checkInPending == uid -> "签到中…"
          else -> "今天还没签"
        },
        unread = unread,
        onAboutLongPress = {
          drawer.close(scope)
          onOpenDevMenu()
        },
        onEntry = { key ->
          handleDrawerEntry(
            key = key,
            nav = nav,
            uid = uid,
            closeDrawer = { drawer.close(scope) },
            openAddBoard = { addBoardOpen = true },
            openClearFavorites = {
              if (favoriteBoards.isEmpty()) {
                Snackbars.show("还没有收藏任何版块")
              } else {
                clearOpen = true
              }
            },
            openFromUrl = {
              urlError = null
              urlOpen = true
            },
            checkInNow = {
              scope.launch {
                runCatching { deps.checkIn.checkInNow(it) }.fold(
                  onSuccess = { outcome -> Snackbars.show(checkInMessage(outcome) ?: return@fold) },
                  onFailure = { error -> Snackbars.show(failureText(error)) },
                )
              }
            },
          )
        },
      )
    },
    modifier = modifier,
  ) {
    Column(Modifier.fillMaxSize().background(colors.bg)) {
      TopBar(
        below = {
          CategoryTabs(
            categories = categories,
            pagerState = pagerState,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
          )
        },
      ) {
        TopBarButton(
          icon = Ng2nIcon.MENU,
          size = 24.dp,
          box = 46.dp,
          contentDescription = "打开抽屉",
          onClick = { drawer.open(scope) },
        )
        TopBarTitle(text = "NG2")
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        TopBarButton(
          icon = Ng2nIcon.SEARCH,
          size = 23.dp,
          contentDescription = "搜索",
          onClick = { nav.push(SearchKey()) },
        )
      }

      when {
        treeState.loading && tree == null -> LoadingState(Modifier.fillMaxSize())
        categories.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
          LoadFailedNotice(
            error = treeState.error,
            onRetry = { deps.boardTree.refresh() },
            variant = StateVariant.SCREEN,
          )
        }
        else -> HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxSize(),
          beyondViewportPageCount = 1,
          key = { index -> categories[index].id },
        ) { page ->
          CategoryPage(
            rows = rowsFor(categories[page]),
            onOpenBoard = openBoard,
            onDismiss = { deps.boardTree.dismissAnnouncement(it) },
            onNoticeAction = { nav.push(Login) },
            onRetryFavorites = { scope.launch { deps.boardFavorites.reload(uid) } },
            tagged = page == pagerState.currentPage,
          )
        }
      }
    }
  }

  InputDialog(
    open = addBoardOpen,
    title = "添加版面 ID",
    hint = "填 fid 或合集 stid,例如 459、-7",
    confirmLabel = "添加",
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
    onCancel = { addBoardOpen = false },
    onConfirm = { text ->
      addBoardOpen = false
      val boardId = parseBoardIdInput(text)
      if (boardId == null) {
        Snackbars.show("版面 ID 只能是整数,例如 459 或 -7")
      } else if (uid != null) {
        val provisional = Board(
          id = boardId,
          kind = BoardKind.BOARD,
          fid = boardId,
          name = "版块 $boardId",
        )
        scope.launch {
          runCatching { deps.boardFavorites.addById(uid, boardId, provisional) }.fold(
            onSuccess = { board ->
              Snackbars.show(
                "已添加「${board.name}」到我的收藏",
                SnackbarAction("打开") { openBoard(board) },
              )
            },
            onFailure = { Snackbars.show(failureText(it)) },
          )
        }
      }
    },
  )

  InputDialog(
    open = urlOpen,
    title = "由 URL 读取",
    hint = "支持 read.php / thread.php 链接",
    error = urlError,
    confirmLabel = "打开",
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
    onValueChange = { urlError = null },
    onCancel = { urlOpen = false },
    onConfirm = { text ->
      when (val result = parseNgaLink(text)) {
        is NgaLinkResult.Failed -> urlError = result.reason.message
        is NgaLinkResult.Ok -> {
          urlOpen = false
          urlError = null
          nav.push(result.link.toNavKey())
        }
      }
    },
  )

  ConfirmDialog(
    open = clearOpen,
    title = "清空我的收藏",
    message = "将取消收藏全部 ${favoriteBoards.size} 个版块。清空后可以撤销。",
    confirmLabel = "清空",
    destructive = true,
    onCancel = { clearOpen = false },
    onConfirm = {
      clearOpen = false
      if (uid != null) {
        scope.launch {
          runCatching { deps.boardFavorites.clear(uid) }.fold(
            onSuccess = { removed ->
              Snackbars.show(
                "已清空我的收藏",
                SnackbarAction("撤销") {
                  scope.launch {
                    runCatching { deps.boardFavorites.restore(uid, removed) }
                      .onFailure { Snackbars.show(failureText(it)) }
                  }
                },
              )
            },
            onFailure = { Snackbars.show(failureText(it)) },
          )
        }
      }
    },
  )
}

private fun checkInMessage(outcome: CheckInOutcome): String? = when (outcome) {
  CheckInOutcome.InFlight -> null
  CheckInOutcome.AlreadyToday -> "今天已经签到过了"
  is CheckInOutcome.CheckedIn ->
    if (outcome.result.alreadyCheckedIn) "今天已经签到过了" else outcome.result.message ?: "签到成功"
}

private fun handleDrawerEntry(
  key: DrawerEntryKey,
  nav: Navigator,
  uid: String?,
  closeDrawer: () -> Unit,
  openAddBoard: () -> Unit,
  openClearFavorites: () -> Unit,
  openFromUrl: () -> Unit,
  checkInNow: (String) -> Unit,
) {
  when (key) {
    DrawerEntryKey.LOGIN -> {
      closeDrawer()
      nav.push(Login)
    }
    DrawerEntryKey.CHECK_IN -> {
      if (uid == null) {
        closeDrawer()
        showLoginPrompt(nav, "登录后才能签到")
      } else {
        checkInNow(uid)
      }
    }
    DrawerEntryKey.ADD_BOARD -> {
      closeDrawer()
      if (uid == null) showLoginPrompt(nav, "登录后可把版块收藏到云端") else openAddBoard()
    }
    DrawerEntryKey.FROM_URL -> {
      closeDrawer()
      openFromUrl()
    }
    DrawerEntryKey.FAVORITES -> {
      closeDrawer()
      nav.push(FavoritesKey)
    }
    DrawerEntryKey.FAVORITE_FOLDERS -> {
      closeDrawer()
      nav.push(FavoriteFoldersKey)
    }
    DrawerEntryKey.CLEAR_FAVORITES -> {
      closeDrawer()
      if (uid == null) showLoginPrompt(nav, "登录后可管理云端收藏的版块") else openClearFavorites()
    }
    DrawerEntryKey.MY_TOPICS, DrawerEntryKey.MY_REPLIES -> {
      closeDrawer()
      val numeric = uid?.toLongOrNull()
      if (numeric == null) {
        showLoginPrompt(nav, "登录后才能看自己的主题与回复")
      } else {
        nav.push(
          UserPostsKey(
            uid = numeric,
            kind = if (key == DrawerEntryKey.MY_TOPICS) {
              UserPostKind.TOPICS
            } else {
              UserPostKind.REPLIES
            },
          ),
        )
      }
    }
    DrawerEntryKey.CACHES -> {
      closeDrawer()
      nav.push(CachesKey)
    }
    DrawerEntryKey.BOOKMARKS -> {
      closeDrawer()
      nav.push(BookmarksKey)
    }
    DrawerEntryKey.MESSAGES -> Snackbars.show(NOT_AVAILABLE_MESSAGE)
    DrawerEntryKey.NOTIFICATIONS -> {
      closeDrawer()
      nav.push(NotificationsKey)
    }
    DrawerEntryKey.SETTINGS -> {
      closeDrawer()
      nav.push(SettingsKey)
    }
    DrawerEntryKey.ABOUT -> {
      closeDrawer()
      nav.push(AboutKey)
    }
  }
}

@Composable
private fun CategoryTabs(
  categories: List<BoardCategory>,
  pagerState: androidx.compose.foundation.pager.PagerState,
  onSelect: (Int) -> Unit,
) {
  val colors = LocalNg2nColors.current
  val density = LocalDensity.current
  val scroll = rememberScrollState()
  val layouts = remember(categories) { mutableStateOf(emptyList<TabLayout>()) }
  val raw = remember(categories) { arrayOfNulls<TabLayout>(categories.size) }
  val lead = with(density) { TAB_SCROLL_LEAD.roundToPx() }

  LaunchedEffect(pagerState, layouts.value) {
    snapshotFlow { pagerState.currentPage }.collect { index ->
      val x = layouts.value.getOrNull(index)?.x ?: return@collect
      scroll.animateScrollTo((x - lead).coerceAtLeast(0))
    }
  }

  Box(Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT)) {
    Row(Modifier.horizontalScroll(scroll).padding(horizontal = TAB_BAR_PADDING)) {
      categories.forEachIndexed { index, category ->
        val selected = pagerState.currentPage == index
        Box(
          modifier = Modifier
            .onGloballyPositioned { coordinates ->
              raw[index] = TabLayout(
                x = coordinates.positionInParent().x.toInt(),
                width = coordinates.size.width,
              )
              layouts.value = raw.filterNotNull()
            }
            .height(TAB_BAR_HEIGHT)
            .clickable(onClickLabel = category.name) { onSelect(index) }
            .padding(horizontal = Spacing.lg),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = category.name,
            style = TextStyle(
              fontSize = Typo.tab.size,
              lineHeight = Typo.tab.lineHeight,
              fontWeight = FontWeight.SemiBold,
              color = colors.onTopbar.copy(alpha = if (selected) 1f else 0.62f),
            ),
          )
        }
      }
    }
    Canvas(
      Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .height(3.dp),
    ) {
      val tabs = layouts.value
      if (tabs.isEmpty()) return@Canvas
      val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, (tabs.size - 1).toFloat())
      val index = floor(position).toInt()
      val fraction = position - index
      val from = tabs.getOrNull(index) ?: return@Canvas
      val to = tabs.getOrNull(index + 1) ?: from
      val x = TAB_BAR_PADDING.toPx() + from.x + (to.x - from.x) * fraction - scroll.value
      val width = from.width + (to.width - from.width) * fraction
      drawRect(color = colors.onTopbar, topLeft = Offset(x, 0f), size = Size(width, size.height))
    }
  }
}

private data class TabLayout(val x: Int, val width: Int)

@Composable
private fun CategoryPage(
  rows: List<HomeRow>,
  onOpenBoard: (Board) -> Unit,
  onDismiss: (String) -> Unit,
  onNoticeAction: () -> Unit,
  onRetryFavorites: () -> Unit,
  tagged: Boolean,
) {
  val gridState = rememberLazyGridState()
  LazyVerticalGrid(
    columns = GridCells.Fixed(GRID_COLUMNS),
    state = gridState,
    modifier = Modifier
      .fillMaxSize()
      .then(
        if (tagged) Modifier.semantics { contentDescription = SKELETON_READY_TAG } else Modifier,
      ),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp),
  ) {
    items(
      count = rows.size,
      key = { index -> rows[index].key },
      span = { index -> if (rows[index] is HomeRow.Cell) GridItemSpan(1) else GridItemSpan(GRID_COLUMNS) },
      contentType = { index -> rows[index]::class },
    ) { index ->
      when (val row = rows[index]) {
        is HomeRow.Announcement -> AnnouncementRow(row.announcement, onDismiss)
        is HomeRow.Group -> GroupHeader(row)
        is HomeRow.Cell -> BoardCell(row.board, onOpenBoard)
        is HomeRow.Notice -> EmptyState(
          icon = row.icon,
          text = row.text,
          action = row.actionLabel?.let { StateAction(it, onNoticeAction) },
          variant = StateVariant.INLINE,
        )
        is HomeRow.Failure -> LoadFailedNotice(row.error, onRetryFavorites)
      }
    }
  }
}

@Composable
private fun AnnouncementRow(announcement: HomeAnnouncement, onDismiss: (String) -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .padding(vertical = Spacing.row, horizontal = Spacing.page),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    AppIcon(icon = Ng2nIcon.CAMPAIGN, tint = colors.accent, size = 19.dp)
    Text(
      text = announcement.title,
      modifier = Modifier.weight(1f),
      style = TextStyle(
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        color = colors.fg2,
      ),
    )
    Box(
      Modifier
        .size(24.dp)
        .clip(CircleShape)
        .clickable(onClickLabel = "关闭公告") { onDismiss(announcement.id) },
      contentAlignment = Alignment.Center,
    ) {
      AppIcon(icon = Ng2nIcon.CLOSE, tint = colors.meta, size = 17.dp)
    }
  }
}

@Composable
private fun GroupHeader(row: HomeRow.Group) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = Spacing.lg, bottom = Spacing.xs)
      .padding(horizontal = Spacing.page),
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .size(17.dp)
        .border(1.dp, colors.accent, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = row.initial,
        style = TextStyle(
          fontSize = Typo.badge.size,
          lineHeight = Typo.badge.lineHeight,
          fontWeight = FontWeight.Bold,
          color = colors.accent,
        ),
      )
    }
    Text(
      text = row.name,
      style = TextStyle(
        fontSize = Typo.section.size,
        lineHeight = Typo.section.lineHeight,
        color = colors.fg,
      ),
    )
  }
}

@Composable
private fun BoardCell(board: Board, onOpen: (Board) -> Unit) {
  val colors = LocalNg2nColors.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = board.name) { onOpen(board) }
      .padding(top = Spacing.sm, bottom = 10.dp)
      .padding(horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    BoardIcon(board, Modifier.padding(bottom = Spacing.sm))
    Text(
      text = board.name,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
      style = TextStyle(
        fontSize = Typo.gridLabel.size,
        lineHeight = Typo.gridLabel.lineHeight,
        color = colors.fg,
      ),
    )
  }
}
