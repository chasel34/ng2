package com.chasel.ng2n.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.SubBoard
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.board.TopicListRepository
import com.chasel.ng2n.data.filters.filterTopics
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingFooter
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.MenuItem
import com.chasel.ng2n.ui.common.NOT_AVAILABLE_MESSAGE
import com.chasel.ng2n.ui.common.OverflowMenu
import com.chasel.ng2n.ui.common.SnackbarAction
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateAction
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.rememberListPullToRefreshState
import com.chasel.ng2n.ui.common.rowClickable
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.filters.rememberFilterRules
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.BoardFace
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.nav.HistoryKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.SearchKey
import com.chasel.ng2n.ui.nav.SubBoardsKey
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.WebKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalNg2nTitleColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

/**
 * 版块 / 合集的主题列表 —— 直译 RN 侧 `src/app/board/[id].tsx`。
 *
 * 键上的 `id` 是「合集传 stid、普通版块传 fid」的那一个数(**fid/stid 互斥且
 * stid 优先**,CONTEXT.md「合集」);`name` 是为了进页面立刻能画出顶栏标题,
 * 不用等 `thread.php` 回来 —— 服务端的版块名回来后**不覆盖**它(两者一致,
 * 覆盖只会让标题闪一下)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(key: BoardKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val titleColors = LocalNg2nTitleColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  var sort by remember { mutableStateOf(TopicSort.LAST_POST) }
  var menuOpen by remember { mutableStateOf(false) }

  // 24 小时热帖与精华区是同一个键的另外两档,由 `homeEntries` 分派到别的屏,
  // 走不到这里(见 `BoardFace`)
  val listKey = TopicListRepository.Key(boardId = key.id, kind = key.kind, sort = sort)
  val allStates by deps.topicLists.states.collectAsStateWithLifecycle()
  val state = allStates[listKey] ?: TopicListRepository.State()

  LaunchedEffect(listKey) { deps.topicLists.ensureFirstPage(listKey) }

  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = com.chasel.ng2n.data.account.EMPTY_ACCOUNTS,
  )
  val uid = currentAccountOf(accountsState)?.uid
  val favoriteStates by deps.boardFavorites.states.collectAsStateWithLifecycle()
  val favored = uid != null && favoriteStates[uid]?.boards.orEmpty().any { it.id == key.id }
  LaunchedEffect(uid) { deps.boardFavorites.ensureLoaded(uid) }

  val firstPage = state.pages.firstOrNull()
  val boardTitle = key.name ?: firstPage?.board?.name ?: "版块 ${key.id}"
  // 版头(CONTEXT.md):`__F.topped_topic` 带 tid 时在列表顶上给一条置顶入口
  val headTid = firstPage?.board?.head
  val subBoards = firstPage?.subBoards.orEmpty()

  // 屏蔽规则(票 29):命中的主题**整行不画**(楼层是折叠,列表是隐藏 —— 屏蔽规则页
  // 顶上那句说明写的就是这个)。判定与楼层流共用 `matchFilterRules`
  val filterRules = rememberFilterRules()

  // 彩色标题 → AnnotatedString:**一次构建**,滚动路径上零计算(anzong 四原则第一条)
  val rows = remember(state.topics, filterRules, colors, titleColors) {
    buildTopicRows(filterTopics(filterRules, state.topics), colors, titleColors)
  }

  val openBoard: (Board) -> Unit = { board ->
    nav.push(BoardKey(id = board.id, name = board.name, kind = board.kind))
  }
  val openTopic: (Topic) -> Unit = { topic ->
    val shortcut = topic.shortcut
    when {
      // 合集 / 版块镜像行不是讨论串,点开是另一个版块的主题列表(API 文档 §2 解析要点 3)
      shortcut != null ->
        openBoard(Board(id = shortcut.id, kind = shortcut.kind, name = topic.subject))
      // 活动主题指向站内活动页,不是 read.php —— 交给站内网页兜底屏(票 17)
      topic.jumpUrl != null -> nav.push(WebKey(url = topic.jumpUrl!!, title = topic.subject))
      else -> nav.push(TopicKey(tid = topic.tid, title = topic.subject, fav = topic.favCode))
    }
  }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      // 设计稿 isList 给标题标了 150 的截断宽度,右边三枚图标才排得开
      TopBarTitle(text = boardTitle, variant = TopBarTitleVariant.SUB, maxWidth = 150.dp)
      Spacer(Modifier.weight(1f))
      // 已收藏用 accent 点亮
      TopBarButton(
        icon = Ng2nIcon.STAR,
        size = 23.dp,
        contentDescription = if (favored) "取消收藏本版块" else "收藏本版块",
        tint = if (favored) colors.accent else null,
        onClick = {
          if (uid == null) {
            showLoginPrompt(nav, "登录后可把版块收藏到云端")
          } else {
            toggleFavorite(
              scope = scope,
              deps = deps,
              uid = uid,
              board = Board(
                id = key.id,
                kind = key.kind,
                fid = if (key.kind == BoardKind.COLLECTION) null else key.id,
                stid = if (key.kind == BoardKind.COLLECTION) key.id else null,
                name = boardTitle,
              ),
              favor = !favored,
            )
          }
        },
      )
      // 从列表页进搜索:带上当前版块,搜索选项里才有「当前板块」
      TopBarButton(
        icon = Ng2nIcon.SEARCH,
        size = 22.dp,
        contentDescription = "搜索",
        onClick = { nav.push(SearchKey(boardId = key.id, kind = key.kind, boardName = boardTitle)) },
      )
      TopBarButton(
        icon = Ng2nIcon.MORE_VERT,
        size = 22.dp,
        contentDescription = "更多",
        onClick = { menuOpen = true },
      )
    }

    Box(Modifier.fillMaxSize()) {
      TopicListBody(
        state = state,
        rows = rows,
        headTid = headTid,
        subBoards = subBoards,
        onOpenTopic = openTopic,
        onOpenBoard = openBoard,
        onOpenHead = { nav.push(TopicKey(tid = it, title = "版头")) },
        onRefresh = { scope.launch { deps.topicLists.refresh(listKey) } },
        onRetry = { scope.launch { deps.topicLists.retry(listKey) } },
        onLoadNext = { scope.launch { deps.topicLists.loadNextPage(listKey) } },
        onOpenWeb = {
          // 「用网页版打开」:站内网页兜底屏(票 17),不开系统浏览器。
          // 域名走设置里选的那个 —— 原生被封往往是整个域名被封
          val param = if (key.kind == BoardKind.COLLECTION) "stid" else "fid"
          scope.launch {
            val host = deps.settings.currentSettings().host
            nav.push(WebKey("$host/thread.php?$param=${key.id}", boardTitle))
          }
        },
        onRelogin = { nav.push(com.chasel.ng2n.ui.Login) },
      )

      // 发新帖不在 v1 范围内(spec §1),入口保留
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = Spacing.xl, bottom = 24.dp)
          .size(50.dp)
          .shadow(Elevation.level1, CircleShape)
          .clip(CircleShape)
          .background(colors.fab)
          .clickable(onClickLabel = "发新帖") { Snackbars.show(NOT_AVAILABLE_MESSAGE) },
        contentAlignment = Alignment.Center,
      ) {
        AppIcon(icon = Ng2nIcon.ADD, tint = colors.onFab, size = 27.dp)
      }
    }
  }

  OverflowMenu(
    open = menuOpen,
    onDismiss = { menuOpen = false },
    items = boardMenu(
      key = key,
      sort = sort,
      onClose = { menuOpen = false },
      onSort = { sort = it },
      nav = nav,
    ),
  )
}

private fun toggleFavorite(
  scope: kotlinx.coroutines.CoroutineScope,
  deps: com.chasel.ng2n.ui.AppDeps,
  uid: String,
  board: Board,
  favor: Boolean,
) {
  scope.launch {
    // 撤销就是反着做一次,失败一律回到「服务端怎么说就怎么显示」的话术
    runCatching {
      if (favor) deps.boardFavorites.add(uid, board) else deps.boardFavorites.remove(uid, board)
    }.fold(
      onSuccess = {
        Snackbars.show(
          if (favor) "已收藏到「我的收藏」" else "已取消收藏该版面",
          SnackbarAction("撤销") { toggleFavorite(scope, deps, uid, board, !favor) },
        )
      },
      onFailure = { Snackbars.show(failureText(it)) },
    )
  }
}

/**
 * 版块页的 kebab 菜单。顺序照设计稿 `MENUS.list`:24 小时热帖 → 浏览历史 →
 * 精华区 → 子版块 → 收藏夹,之后是一组互斥的排序选项。
 */
private fun boardMenu(
  key: BoardKey,
  sort: TopicSort,
  onClose: () -> Unit,
  onSort: (TopicSort) -> Unit,
  nav: Navigator,
): List<MenuItem> {
  val entries = buildList {
    add(
      MenuItem("hot", "24 小时热帖") {
        onClose()
        nav.push(key.copy(face = BoardFace.HOT))
      },
    )
    add(MenuItem("history", "浏览历史") { onClose(); nav.push(HistoryKey) })
    add(
      MenuItem("recommend", "精华区") {
        onClose()
        nav.push(key.copy(face = BoardFace.RECOMMEND))
      },
    )
    add(
      MenuItem("sub-boards", "子版块") {
        onClose()
        nav.push(SubBoardsKey(id = key.id, name = key.name, kind = key.kind))
      },
    )
    add(
      MenuItem("favorites", "收藏夹") {
        onClose()
        nav.push(com.chasel.ng2n.ui.nav.FavoritesKey)
      },
    )
  }
  // 设计稿的列表菜单没画排序(它在 MNGA 里是设置项),按现有菜单样式延伸一组互斥选项
  val sorts = listOf(
    TopicSort.LAST_POST to "按最后回复排序",
    TopicSort.POST_DATE to "按发帖时间排序",
  ).mapIndexed { index, (value, label) ->
    MenuItem(
      key = value.name,
      label = label,
      gapBefore = index == 0,
      selected = sort == value,
      onClick = {
        onClose()
        onSort(value)
      },
    )
  }
  return entries + sorts
}

/**
 * 列表正文。三种「没内容」是三回事,说成同一句话时用户会以为版块是空的
 * (2026-08-13:被限流时全站版块都显示「这个版块还没有主题」,连我们自己都查了半天)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicListBody(
  state: TopicListRepository.State,
  rows: List<TopicRowModel>,
  headTid: Long?,
  subBoards: List<SubBoard>,
  onOpenTopic: (Topic) -> Unit,
  onOpenBoard: (Board) -> Unit,
  onOpenHead: (Long) -> Unit,
  onRefresh: () -> Unit,
  onRetry: () -> Unit,
  onLoadNext: () -> Unit,
  onOpenWeb: () -> Unit,
  onRelogin: () -> Unit,
) {
  val colors = LocalNg2nColors.current

  if (state.loading && state.pages.isEmpty()) {
    LoadingState(Modifier.fillMaxSize())
    return
  }

  val error = state.error
  if (rows.isEmpty() && error != null) {
    // 解析不了 / 没有兜底可用 = 多半是被拦了,给足三个出路(重试 / 网页版 / 重登)
    val blocked = error is NgaError &&
      (error.kind == NgaErrorKind.PARSE || error.kind == NgaErrorKind.UNAVAILABLE)
    Column(
      Modifier.fillMaxSize().padding(Spacing.xl),
      verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      LoadFailedNotice(error = error, onRetry = onRetry)
      if (blocked) {
        WideButton("用网页版打开", onOpenWeb)
        WideButton("重新登录", onRelogin)
      }
    }
    return
  }

  if (rows.isEmpty()) {
    val listStructure = state.pages.firstOrNull()?.listStructure
    // 拉回来了、被自己的屏蔽规则挡光了:得说清是规则挡的,别当成「这个版块没帖子」
    val allFiltered = state.topics.isNotEmpty()
    if (allFiltered) {
      EmptyState(
        icon = Ng2nIcon.FILTER_ALT,
        text = "这一页的主题都被屏蔽规则挡住了",
        action = StateAction("刷新", onRetry),
      )
      return
    }
    // 服务端连「主题列表」这个结构都没给:这不是空版块。正常情况下 core/api 已经把它
    // 变成错误了,这里是最后一道防线——别再让「没拿到」和「没帖子」共用一句话
    if (listStructure == false) {
      EmptyState(
        icon = Ng2nIcon.CLOUD_OFF,
        text = "没能拿到这个版块的主题列表\n多半是被论坛限流或拦下了",
        action = StateAction("重试", onRetry),
      )
    } else {
      EmptyState(
        icon = Ng2nIcon.ARTICLE,
        text = "这个版块还没有主题",
        action = StateAction("刷新", onRetry),
      )
    }
    return
  }

  val listState = rememberLazyListState()
  // 无限滚动:剩不到一屏就拉下一页(RN 侧 `onEndReachedThreshold={0.6}` 的对应物)
  val shouldLoadMore by remember(listState, rows.size) {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
      last >= listState.layoutInfo.totalItemsCount - 6
    }
  }
  LaunchedEffect(listState, state.hasNextPage, state.loadingNextPage) {
    snapshotFlow { shouldLoadMore }.collect { if (it) onLoadNext() }
  }

  PullToRefreshBox(
    // 翻下一页时不要亮:不然底部转圈会连带把顶部也拽出来
    isRefreshing = state.refreshing && !state.loadingNextPage,
    onRefresh = onRefresh,
    state = rememberListPullToRefreshState(),
    modifier = Modifier.fillMaxSize(),
  ) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 70.dp),
    ) {
      if (headTid != null) {
        item(key = ListKeys.HEAD, contentType = "head") { HeadRow(onClick = { onOpenHead(headTid) }) }
      }
      if (subBoards.isNotEmpty()) {
        item(key = ListKeys.SUB_BOARDS, contentType = "sub-boards") {
          SubBoardBar(subBoards, onOpenBoard)
        }
      }
      items(
        count = rows.size,
        // key/contentType:回收池按类型分,行不会复用到形状完全不同的项上
        key = { index -> rows[index].topic.tid },
        contentType = { "topic" },
      ) { index ->
        TopicRow(rows[index], onOpenTopic)
      }
      item(key = ListKeys.FOOTER, contentType = "footer") {
        Column {
          if (state.loadingNextPage) LoadingFooter("正在载入第 ${state.pages.size + 1} 页…")
          if (!state.loadingNextPage && error != null) {
            Text(
              text = failureText(error),
              modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
              textAlign = TextAlign.Center,
              style = TextStyle(
                fontSize = Typo.listMeta.size,
                lineHeight = Typo.listMeta.lineHeight,
                color = colors.meta,
              ),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun WideButton(label: String, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(46.dp)
      .clip(RoundedCornerShape(Radius.button))
      .border(1.dp, colors.divider, RoundedCornerShape(Radius.button))
      .clickable(onClickLabel = label, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = TextStyle(fontSize = Typo.tab.size, fontWeight = FontWeight.SemiBold, color = colors.fg),
    )
  }
}

/** 版头置顶入口。设计稿没画这屏,按公告条的设计语言延伸(surface-2 底 + 分隔线)。 */
@Composable
private fun HeadRow(onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .rowClickable(onClickLabel = "打开版头", onClick = onClick)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = Spacing.md, horizontal = Spacing.row),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    AppIcon(icon = Ng2nIcon.PUSH_PIN, tint = colors.accent, size = 16.dp)
    Text(
      text = "版头",
      modifier = Modifier.weight(1f),
      style = TextStyle(
        fontSize = Typo.notice.size,
        lineHeight = Typo.notice.lineHeight,
        color = colors.fg2,
      ),
    )
    AppIcon(icon = Ng2nIcon.CHEVRON_RIGHT, tint = colors.meta, size = 18.dp)
  }
}

/** 子版块横条(设计稿:列表顶部一排可横滚的 tag)。 */
@Composable
private fun SubBoardBar(boards: List<SubBoard>, onClick: (Board) -> Unit) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .horizontalScroll(rememberScrollState())
      .padding(vertical = Spacing.md, horizontal = Spacing.row),
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    // key 用 kind+id:合集与版块各自编号(stid vs fid),只用 id 有撞车的可能
    boards.forEach { subBoard ->
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(Radius.sm))
          .background(colors.surface2)
          .border(1.dp, colors.divider, RoundedCornerShape(Radius.sm))
          .clickable(onClickLabel = subBoard.name) { onClick(subBoard.toBoard()) }
          .padding(vertical = 6.dp, horizontal = 13.dp),
      ) {
        Text(
          text = subBoard.name,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = TextStyle(
            fontSize = Typo.listMeta.size,
            lineHeight = Typo.listMeta.lineHeight,
            color = colors.fg2,
          ),
        )
      }
    }
  }
}
