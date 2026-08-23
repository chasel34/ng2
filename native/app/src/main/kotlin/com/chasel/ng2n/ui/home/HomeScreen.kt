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
import com.chasel.ng2n.ui.nav.CachesKey
import com.chasel.ng2n.ui.nav.FavoriteFoldersKey
import com.chasel.ng2n.ui.nav.FavoritesKey
import com.chasel.ng2n.ui.nav.Navigator
// 票 17c:「由 URL 读取」与系统深链共用同一份映射,真相源在 ui/nav/DeepLinkKeys.kt
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

/** 设计稿:tab 44 高、版块宫格三列。 */
private val TAB_BAR_HEIGHT = 44.dp
private const val GRID_COLUMNS = 3

/** 横滑换分类时把选中的那格滚进视野,左边留出这么多,免得它永远贴在最左边。 */
private val TAB_SCROLL_LEAD = 56.dp

/**
 * tab 条内容的左右留白(RN 侧 `tabBar.paddingHorizontal: 6`,跟着内容一起滚)。
 *
 * 下划线是画在 tab 条**外层** Box 上的,而每格量到的 x 是它在 Row **内容**里的位置
 * (不含这一档 padding),所以画的时候要补回来。
 */
private val TAB_BAR_PADDING = 6.dp

/**
 * 首页 —— 直译 RN 侧 `src/app/index.tsx`。
 *
 * 分类 tab 横滑 pager + 版块宫格 + 版头公告 + 抽屉宿主。
 * 版块树走 24h SWR(票 14 的存储壳 + 票 16 的 `loadBoardTree`)。
 */
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
    initialValue = com.chasel.ng2n.data.account.EMPTY_ACCOUNTS,
  )
  val current = currentAccountOf(accountsState)
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
  // 通知只在前台轮(RN 版同);离开首页就停,票 17 的通知屏自己再 start 一次
  androidx.compose.runtime.DisposableEffect(Unit) {
    deps.notifications.start()
    onDispose { deps.notifications.stop() }
  }

  val drawer = rememberDrawerHostState()
  var addBoardOpen by remember { mutableStateOf(false) }
  var clearOpen by remember { mutableStateOf(false) }
  var urlOpen by remember { mutableStateOf(false) }
  // 「由 URL 读取」解不开时框里那行红字;null = 还没错过
  var urlError by remember { mutableStateOf<String?>(null) }

  val tree = treeState.tree
  val categories = remember(tree) {
    // 分类树没回来就不插合成 tab:否则 categories 永远非空,下面的错误分支再也走不到
    if (tree == null) {
      emptyList()
    } else {
      listOf(BoardCategory(FAVORITES_CATEGORY_ID, "我的收藏")) + tree.categories
    }
  }

  val announcement = remember(tree, dismissed) {
    // 先滤掉关过的再挑生效中的那条:否则关掉第一条之后,后面几条永远轮不到
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

  /** 空收藏时那条说明:游客给登录出口,拉失败给统一错误块,其余就是「还没收藏」。 */
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

  /**
   * 行数组按分类 id 缓存。只有内容真变了(分类树、公告、收藏)才整个换掉;
   * 仍然按需建:最大的分类(手机游戏)摊开三百多格,面板没轮到它就不白烧。
   */
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

  // 默认停在「我的收藏」(设计稿的 tab 0);游客那一栏只有登录引导,
  // 拿它当首屏等于把整个首页开成空的,所以游客直接落到第一个服务端分类
  val defaultIndex = if (signedIn) 0 else if (categories.size > 1) 1 else 0
  val pagerState = rememberPagerState(initialPage = defaultIndex) { categories.size }
  // 分类树是异步来的:等它到位再把游客的起始页挪到第 1 个服务端分类
  var initialised by remember { mutableStateOf(false) }
  LaunchedEffect(categories.size, signedIn) {
    if (!initialised && categories.isNotEmpty()) {
      initialised = true
      if (pagerState.currentPage != defaultIndex) pagerState.scrollToPage(defaultIndex)
    }
  }

  DrawerHost(
    state = drawer,
    drawerContent = {
      AppDrawerContent(
        // 票 15 的账号头:游客态显登录、已登录显头像昵称、左右滑循环切号
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
        // 原先右边还有个「更多」kebab,条目全部并进了左侧抽屉(它在每一屏都拉得出来,
        // 不必先退回首页),顶栏只留搜索
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
            // 首帧内容可用的锚点:第一页(可见那页)带上就够
            tagged = page == pagerState.currentPage,
          )
        }
      }
    }
  }

  // 抽屉的三个对话框归宿主页面(设计稿:关抽屉 → 弹框)
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
        // 先按「普通版块」乐观显示;是不是合集、真名叫什么,以重拉回来的列表为准
        val provisional = Board(
          id = boardId,
          kind = BoardKind.BOARD,
          fid = boardId,
          name = "版块 $boardId",
        )
        scope.launch {
          runCatching { deps.boardFavorites.addById(uid, boardId, provisional) }.fold(
            onSuccess = { board ->
              // 设计稿的文案是「已添加版面到我的收藏」;这里带上服务端给的名字,
              // 手输 id 时才看得出到底收到了哪个版块(尤其 stid 解析成合集的时候)
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
    // 改了链接就把上一次的红字撤了,别让它挂到下一次点「打开」
    onValueChange = { urlError = null },
    onCancel = { urlOpen = false },
    onConfirm = { text ->
      // 粘进来的链接就地解析:解得开才跳,解不开留在框里说明哪儿不对——
      // 关掉框再弹 toast 的话,想改那一行还得重新粘一次
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
                // 撤销 = 逐个收回来,再失败就只能说一声了
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
  // 本地记录已是今天、或上一次还在途,都不该再打接口(重复点击就落在这两支)
  CheckInOutcome.InFlight -> null
  CheckInOutcome.AlreadyToday -> "今天已经签到过了"
  is CheckInOutcome.CheckedIn ->
    if (outcome.result.alreadyCheckedIn) "今天已经签到过了" else outcome.result.message ?: "签到成功"
}

/**
 * 抽屉条目的落点。**要弹对话框的条目由宿主页面接管**(设计稿:先关抽屉再弹框);
 * 签到不关抽屉 —— 签完那行就地变成「今天已签到」,关掉再弹提示反而看不见结果。
 */
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
      // 「我的主题/我的回复」查的是当前账号,游客态没有 uid 可查
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
    // 短消息整块不在 v1(spec §1),入口保留走「本版本未开放」
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

/**
 * 分类 tab 条。下划线钉在两格 tab 几何量的插值上,**进度走到哪儿画到哪儿** ——
 * 与内容同帧,不等重组落地(RN 侧那份注释里栽过的坑:9 个 onLayout 各自读改写
 * 同一个共享值会互相覆盖,所以几何先攒进一个数组再整体赋值)。
 */
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

  // 横滑换了分类之后,选中的那一格可能在 tab 条视野外
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
            // 票 41:几何要量**整格**(含 paddingHorizontal),下划线是整格宽不是文字宽。
            // onGloballyPositioned 报的是它右边那截修饰符链的坐标 —— 挂在 padding
            // 后面量到的是内容框(RN 那份是 tab 容器的 onLayout,含内距),所以放最前。
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
    // 设计稿用的是 inset box-shadow,不占布局;所以下划线单画一条,位置与宽度由
    // pager 的**连续页位**驱动(跟手,不等重组落地)
    Canvas(
      Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .height(3.dp),
    ) {
      val tabs = layouts.value
      if (tabs.isEmpty()) return@Canvas
      // 头尾越界(边缘阻尼拖出去的那点)夹回来
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
      // macrobenchmark 的首帧锚点(票 19):可见那一页带上就够
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
    // 版块名长了要么折行要么打省略号,不能在半路被裁掉(「网事杂谈」→「网事杂」)
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
