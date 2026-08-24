package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.BoardSearchItem
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.UserProfile
import com.chasel.ng2n.data.account.EMPTY_ACCOUNTS
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.filters.filterTopics
import com.chasel.ng2n.data.search.SearchRepository
import com.chasel.ng2n.data.settings.EMPTY_SEARCH_HISTORY
import com.chasel.ng2n.data.settings.SearchBoardScope
import com.chasel.ng2n.data.settings.SearchHistoryEntry
import com.chasel.ng2n.data.settings.SearchTab
import com.chasel.ng2n.data.settings.addSearchHistory
import com.chasel.ng2n.data.settings.clearSearchHistory
import com.chasel.ng2n.data.settings.removeSearchHistory
import com.chasel.ng2n.ui.board.TopicRow
import com.chasel.ng2n.ui.board.buildTopicRows
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingFooter
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.rememberPagedFlingBehavior
import com.chasel.ng2n.ui.common.rememberShouldLoadNextPage
import com.chasel.ng2n.ui.common.rowClickable
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.filters.rememberFilterRules
import com.chasel.ng2n.ui.home.BoardIcon
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.SearchKey
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.nav.WebKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalNg2nTitleColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

/**
 * 搜索页 —— 直译 RN 侧 `src/app/search.tsx`(设计稿 `isSearch` 屏 1:1;
 * 三种结果列表是设计稿缺失页面,按现有设计语言延伸)。
 *
 * 从列表页进来带 `boardId`/`kind`/`boardName`,搜索选项里才有「当前板块」;
 * 首页进来只有「全部板块」。三个 tab 各自独立的搜索历史(带范围,持久化在
 * `SettingsStore` 的 `search/history`,与 RN 版同键同结构)。
 *
 * ## 与 RN 版的一处有意偏离:游客不给搜版块
 *
 * `forum.php?key=…` 对游客回的是「你必须先登录论坛」,RN 版没挡,于是游客点「搜板块」
 * 拿到的是一句看不懂的服务端错误。这一版按票 17a 的要求先自己挡住并把登录页递到手边
 * (与版块收藏、收藏夹那些入口同一套 [showLoginPrompt] 话术)。
 */
@Composable
fun SearchScreen(key: SearchKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current

  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = EMPTY_ACCOUNTS,
  )
  val signedIn = currentAccountOf(accountsState) != null

  /** 从列表页带过来的「当前板块」;首页进来是 null。 */
  val currentBoard = remember(key) {
    key.boardId?.let {
      SearchBoardScope(
        boardId = it,
        kind = if (key.kind == BoardKind.COLLECTION) "collection" else "board",
        name = key.boardName ?: "版块 $it",
      )
    }
  }

  var tab by remember { mutableStateOf(SearchTab.TOPICS) }
  var text by remember { mutableStateOf("") }
  /** 已提交的关键词;空串 = 还没搜,显示选项 + 历史 */
  var submitted by remember { mutableStateOf("") }
  // 从列表页进来默认搜当前板块(设计稿的「当前板块」默认选中);null = 全部板块
  var boardScope by remember { mutableStateOf(currentBoard) }
  var content by remember { mutableStateOf(false) }

  val history by deps.settings.searchHistory.collectAsStateWithLifecycle(
    initialValue = EMPTY_SEARCH_HISTORY,
  )

  fun submit(raw: String, scopeArg: SearchBoardScope? = boardScope, contentArg: Boolean = content) {
    val query = raw.trim()
    if (query.isEmpty()) return
    text = query
    boardScope = scopeArg
    content = contentArg
    submitted = query
    val entry = SearchHistoryEntry(
      query = query,
      scope = if (tab == SearchTab.TOPICS) scopeArg else null,
      content = if (tab == SearchTab.TOPICS && contentArg) true else null,
    )
    val currentTab = tab
    scope.launch { deps.settings.updateSearchHistory { addSearchHistory(it, currentTab, entry) } }
    keyboard?.hide()
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
      SearchField(
        value = text,
        placeholder = PLACEHOLDERS.getValue(tab),
        onValueChange = { text = it },
        onSubmit = { submit(text) },
        onClear = {
          text = ""
          submitted = ""
        },
      )
    }

    TabRow(
      current = tab,
      onSelect = { next ->
        // 游客不给搜版块:接口一律回「你必须先登录论坛」,先自己挡住
        if (next == SearchTab.BOARDS && !signedIn) {
          showLoginPrompt(nav, "登录后才能搜版块")
          return@TabRow
        }
        tab = next
        submitted = ""
      },
      boardsEnabled = signedIn,
    )

    when {
      submitted.isEmpty() -> SearchHome(
        tab = tab,
        entries = history.of(tab),
        currentBoard = currentBoard,
        scope = boardScope,
        content = content,
        onPickScope = { boardScope = it },
        onToggleContent = { content = !content },
        onReplay = { entry -> submit(entry.query, entry.scope, entry.content == true) },
        onRemove = { index ->
          val currentTab = tab
          scope.launch {
            deps.settings.updateSearchHistory { removeSearchHistory(it, currentTab, index) }
          }
        },
        onClearAll = {
          val currentTab = tab
          scope.launch {
            deps.settings.updateSearchHistory { clearSearchHistory(it, currentTab) }
            Snackbars.show("已清空搜索历史")
          }
        },
      )

      tab == SearchTab.TOPICS -> TopicResults(
        query = submitted,
        boardScope = boardScope,
        content = content,
        nav = nav,
      )

      tab == SearchTab.BOARDS -> BoardResults(query = submitted, nav = nav)

      else -> UserResult(query = submitted, nav = nav)
    }
  }
}

/** 设计稿 isSearch 屏:tab 48 高;输入框 40 高、圆角 6。 */
private val TAB_HEIGHT = 48.dp
private val INPUT_HEIGHT = 40.dp
private val INPUT_RADIUS = 6.dp

private val PLACEHOLDERS: Map<SearchTab, String> = mapOf(
  SearchTab.TOPICS to "搜索主题",
  SearchTab.BOARDS to "搜索版块",
  SearchTab.USERS to "输入 UID 或用户名",
)

/** 「搜板块」沿用设计稿原字(CONTEXT.md「版块」:UI 文案可沿用设计稿)。 */
private val TAB_LABELS: List<Pair<SearchTab, String>> = listOf(
  SearchTab.TOPICS to "搜主题",
  SearchTab.BOARDS to "搜板块",
  SearchTab.USERS to "搜用户",
)

/** 顶栏里那条 40 高、圆角 6、surface 底的输入框(设计稿 isSearch)。 */
@Composable
private fun RowScope.SearchField(
  value: String,
  placeholder: String,
  onValueChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onClear: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }

  Row(
    modifier = Modifier
      .weight(1f)
      .padding(start = Spacing.xs, end = Spacing.md)
      .height(INPUT_HEIGHT)
      .clip(RoundedCornerShape(INPUT_RADIUS))
      .background(colors.surface)
      .padding(horizontal = Spacing.row),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      if (value.isEmpty()) {
        Text(
          text = placeholder,
          style = TextStyle(
            fontSize = Typo.drawerItem.size,
            lineHeight = Typo.drawerItem.lineHeight,
            color = colors.meta,
          ),
        )
      }
      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
          fontSize = Typo.drawerItem.size,
          lineHeight = Typo.drawerItem.lineHeight,
          color = colors.fg,
        ),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
      )
    }
    if (value.isNotEmpty()) {
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .clickable(onClickLabel = "清空关键词", onClick = onClear)
          .padding(4.dp),
      ) {
        AppIcon(icon = Ng2nIcon.CLOSE, tint = colors.meta, size = 18.dp)
      }
    }
  }
}

/** 设计稿:tab 行在正文区(bg 底),选中项 fg 色 + 底部 3px 指示条。 */
@Composable
private fun TabRow(current: SearchTab, onSelect: (SearchTab) -> Unit, boardsEnabled: Boolean) {
  val colors = LocalNg2nColors.current
  Row(
    Modifier.fillMaxWidth().drawBehind {
      val y = size.height - 1f
      drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
    },
  ) {
    TAB_LABELS.forEach { (value, label) ->
      val selected = value == current
      // 游客态的「搜板块」画成灰的(点了给登录提示,不是死的)
      val dimmed = value == SearchTab.BOARDS && !boardsEnabled
      Box(
        modifier = Modifier
          .weight(1f)
          .height(TAB_HEIGHT)
          .clickable(onClickLabel = label) { onSelect(value) },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          style = TextStyle(
            fontSize = Typo.menuItem.size,
            lineHeight = Typo.menuItem.lineHeight,
            color = if (selected && !dimmed) colors.fg else colors.meta,
          ),
        )
        if (selected) {
          Box(
            Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .height(3.dp)
              .background(colors.fg),
          )
        }
      }
    }
  }
}

/** 选项 + 各 tab 独立的搜索历史(还没提交关键词时的正文)。 */
@Composable
private fun SearchHome(
  tab: SearchTab,
  entries: List<SearchHistoryEntry>,
  currentBoard: SearchBoardScope?,
  scope: SearchBoardScope?,
  content: Boolean,
  onPickScope: (SearchBoardScope?) -> Unit,
  onToggleContent: () -> Unit,
  onReplay: (SearchHistoryEntry) -> Unit,
  onRemove: (Int) -> Unit,
  onClearAll: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    if (tab == SearchTab.TOPICS) {
      SectionTitle("搜索选项")
      Row(
        modifier = Modifier
          .padding(horizontal = Spacing.lg)
          .padding(bottom = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        if (currentBoard != null) {
          OptionChip(
            label = "当前板块",
            on = scope?.boardId == currentBoard.boardId,
            radio = true,
          ) { onPickScope(currentBoard) }
        }
        OptionChip(label = "全部板块", on = scope == null, radio = true) { onPickScope(null) }
        OptionChip(label = "包括正文", on = content, radio = false) { onToggleContent() }
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(end = Spacing.lg),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.weight(1f)) { SectionTitle("搜索历史", bottomPadding = Spacing.sm) }
      if (entries.isNotEmpty()) {
        Text(
          text = "清空",
          modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .clickable(onClickLabel = "清空搜索历史", onClick = onClearAll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
          style = TextStyle(
            fontSize = Typo.listMeta.size,
            lineHeight = Typo.listMeta.lineHeight,
            color = colors.meta,
          ),
        )
      }
    }

    if (entries.isEmpty()) {
      Text(
        text = "还没有搜索记录",
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          color = colors.meta,
        ),
      )
    }
    entries.forEachIndexed { index, entry ->
      HistoryRow(
        entry = entry,
        scopeLabel = historyScopeLabel(tab, entry),
        onClick = { onReplay(entry) },
        onRemove = { onRemove(index) },
      )
    }
    ListTail()
  }
}

/** 历史条目右侧的范围标注(设计稿 h.scope 那一格)。 */
internal fun historyScopeLabel(tab: SearchTab, entry: SearchHistoryEntry): String = when (tab) {
  SearchTab.BOARDS -> "版块"
  SearchTab.USERS -> "用户"
  SearchTab.TOPICS -> buildList {
    add(entry.scope?.name ?: "全部板块")
    if (entry.content == true) add("包括正文")
  }.joinToString(" · ")
}

@Composable
private fun SectionTitle(text: String, bottomPadding: androidx.compose.ui.unit.Dp = 10.dp) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier
      .padding(top = Spacing.lg, bottom = bottomPadding)
      .padding(horizontal = Spacing.lg),
    style = TextStyle(
      fontSize = Typo.searchSection.size,
      lineHeight = Typo.searchSection.lineHeight,
      fontWeight = FontWeight.SemiBold,
      color = colors.fg,
    ),
  )
}

/** 搜索选项的一枚:单选二枚 + 勾选一枚(设计稿 searchOpts)。只对搜主题生效。 */
@Composable
private fun OptionChip(label: String, on: Boolean, radio: Boolean, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  val icon = when {
    radio && on -> Ng2nIcon.RADIO_BUTTON_CHECKED
    radio -> Ng2nIcon.RADIO_BUTTON_UNCHECKED
    on -> Ng2nIcon.CHECK_BOX
    else -> Ng2nIcon.CHECK_BOX_OUTLINE_BLANK
  }
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(Radius.xs))
      .clickable(onClickLabel = label, onClick = onClick)
      .padding(start = 4.dp, top = 4.dp, end = Spacing.sm, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    AppIcon(icon = icon, tint = if (on) colors.primary else colors.fg2, size = 23.dp)
    Text(
      text = label,
      style = TextStyle(
        fontSize = Typo.dialogListItem.size,
        lineHeight = Typo.dialogListItem.lineHeight,
        color = colors.fg2,
      ),
    )
  }
}

/** 一条搜索历史(设计稿:13 内距、gap 13、下分隔线;行尾一枚删除叉)。 */
@Composable
private fun HistoryRow(
  entry: SearchHistoryEntry,
  scopeLabel: String,
  onClick: () -> Unit,
  onRemove: () -> Unit,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = entry.query, onClick = onClick)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = 13.dp, horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    AppIcon(icon = Ng2nIcon.HISTORY, tint = colors.meta, size = 19.dp)
    Text(
      text = entry.query,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
      style = TextStyle(
        fontSize = Typo.dialogListItem.size,
        lineHeight = Typo.dialogListItem.lineHeight,
        color = colors.fg,
      ),
    )
    Text(
      text = scopeLabel,
      style = TextStyle(
        fontSize = Typo.meta.size,
        lineHeight = Typo.meta.lineHeight,
        color = colors.meta,
      ),
    )
    Box(
      modifier = Modifier
        .clip(CircleShape)
        .clickable(onClickLabel = "删除搜索历史「${entry.query}」", onClick = onRemove)
        .padding(4.dp),
    ) {
      AppIcon(icon = Ng2nIcon.CLOSE, tint = colors.meta, size = 18.dp)
    }
  }
}

/** 主题结果:`thread.php` 无限滚动,复用主题列表行(设计稿 isList 的两行布局)。 */
@Composable
private fun TopicResults(
  query: String,
  boardScope: SearchBoardScope?,
  content: Boolean,
  nav: Navigator,
) {
  val colors = LocalNg2nColors.current
  val titleColors = LocalNg2nTitleColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val searchKey = remember(query, boardScope, content) {
    SearchRepository.TopicKey(
      query = query,
      boardId = boardScope?.boardId,
      kind = if (boardScope?.kind == "collection") BoardKind.COLLECTION else BoardKind.BOARD,
      content = content,
    )
  }
  val buckets by deps.search.topicStates.collectAsStateWithLifecycle()
  val state = remember(buckets, searchKey) { deps.search.topicStateOf(searchKey) }

  LaunchedEffect(searchKey) { deps.search.ensureTopicPage(searchKey) }

  // 搜索结果也是主题列表(票 29 的「顺带」:RN 版这一屏没接,这一版接上 —— 屏蔽规则
  // 页承诺的是「命中的主题在列表里隐藏」,没说除了搜索)
  val filterRules = rememberFilterRules()
  val rows = remember(state.topics, filterRules, colors, titleColors) {
    buildTopicRows(filterTopics(filterRules, state.topics), colors, titleColors)
  }

  val openTopic: (Topic) -> Unit = { topic ->
    val shortcut = topic.shortcut
    when {
      // 合集 / 版块镜像行点开的是另一个版块的列表(API 文档 §2 解析要点 3)
      shortcut != null ->
        nav.push(BoardKey(id = shortcut.id, name = topic.subject, kind = shortcut.kind))
      topic.jumpUrl != null -> nav.push(WebKey(url = topic.jumpUrl!!, title = topic.subject))
      else -> nav.push(TopicKey(tid = topic.tid, title = topic.subject, fav = topic.favCode))
    }
  }

  if (state.loading && state.pages.isEmpty()) {
    LoadingState()
    return
  }
  if (rows.isEmpty()) {
    // 搜到了、被自己的屏蔽规则挡光了:别说成「没搜到」
    val allFiltered = state.topics.isNotEmpty()
    SearchOutcome(
      error = state.error,
      emptyIcon = if (allFiltered) Ng2nIcon.FILTER_ALT else Ng2nIcon.SEARCH,
      emptyText = if (allFiltered) {
        "找到的主题都被屏蔽规则挡住了"
      } else {
        "没有找到与「$query」相关的主题"
      },
      onRetry = { scope.launch { deps.search.retryTopics(searchKey) } },
    )
    return
  }

  val listState = rememberLazyListState()
  // 票 57:按距离而不是按项数拉下一页
  val shouldLoadMore by rememberShouldLoadNextPage(listState, rows.size)
  LaunchedEffect(listState, state.hasNextPage, state.loadingNextPage) {
    snapshotFlow { shouldLoadMore }.collect {
      if (it) deps.search.loadNextTopicPage(searchKey)
    }
  }
  val flingBehavior = rememberPagedFlingBehavior(listState) {
    state.hasNextPage || state.loadingNextPage
  }

  Column(Modifier.fillMaxSize()) {
    // 结果统计条:设计稿缺失页面,按二级列表的副标题条(listSub)延伸
    ListSubtitle(
      buildString {
        append(boardScope?.name ?: "全部板块")
        if (content) append(" · 包括正文")
        append(" · 约 ${state.totalRows} 条结果")
      },
    )
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = LIST_TAIL_HEIGHT),
      flingBehavior = flingBehavior,
    ) {
      items(
        count = rows.size,
        key = { index -> rows[index].topic.tid },
        contentType = { "topic" },
      ) { index -> TopicRow(rows[index], openTopic) }
      item(key = ListKeys.FOOTER, contentType = "footer") {
        Column {
          if (state.loadingNextPage) LoadingFooter("正在载入第 ${state.pages.size + 1} 页…")
          if (!state.loadingNextPage && state.error != null) {
            FooterText(failureText(state.error))
          }
          if (!state.hasNextPage) FooterText("没有更多了")
        }
      }
    }
  }
}

@Composable
private fun FooterText(text: String) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
    textAlign = TextAlign.Center,
    style = TextStyle(
      fontSize = Typo.listMeta.size,
      lineHeight = Typo.listMeta.lineHeight,
      color = colors.meta,
    ),
  )
}

/** 版块结果:可进入、可收藏(设计稿缺失页面,行样式按首页宫格图标 + 列表行延伸)。 */
@Composable
private fun BoardResults(query: String, nav: Navigator) {
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val buckets by deps.search.boardStates.collectAsStateWithLifecycle()
  val state = remember(buckets, query) { deps.search.boardStateOf(query) }
  LaunchedEffect(query) { deps.search.ensureBoards(query) }

  if (state.loading && !state.loaded) {
    LoadingState()
    return
  }
  if (state.items.isEmpty()) {
    SearchOutcome(
      error = state.error,
      emptyIcon = Ng2nIcon.SEARCH,
      emptyText = "没有找到与「$query」相关的版块",
      onRetry = { scope.launch { deps.search.reloadBoards(query) } },
    )
    return
  }

  Column(Modifier.fillMaxSize()) {
    ListSubtitle("找到 ${state.items.size} 个版块")
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = LIST_TAIL_HEIGHT),
    ) {
      items(
        count = state.items.size,
        key = { index -> "${state.items[index].board.kind}/${state.items[index].board.id}" },
        contentType = { "board" },
      ) { index -> BoardResultRow(item = state.items[index], nav = nav) }
    }
  }
}

@Composable
private fun BoardResultRow(item: BoardSearchItem, nav: Navigator) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val board = item.board
  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = EMPTY_ACCOUNTS,
  )
  val uid = currentAccountOf(accountsState)?.uid
  val favoriteStates by deps.boardFavorites.states.collectAsStateWithLifecycle()
  val favored = uid != null && favoriteStates[uid]?.boards.orEmpty().any { it.id == board.id }

  val meta = listOfNotNull(item.parentName, board.info).joinToString(" · ")

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = board.name) {
        nav.push(BoardKey(id = board.id, name = board.name, kind = board.kind))
      }
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = Spacing.md)
      .padding(start = Spacing.lg, end = Spacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    BoardIcon(board)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = board.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
          fontSize = Typo.listTitle.size,
          lineHeight = Typo.listTitle.lineHeight,
          color = colors.fg,
        ),
      )
      if (meta.isNotEmpty()) {
        Text(
          text = meta,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = TextStyle(
            fontSize = Typo.listMeta.size,
            lineHeight = Typo.listMeta.lineHeight,
            color = colors.meta,
          ),
        )
      }
    }
    // 与列表页顶栏星标同一套话术:点了立刻变,失败按服务端的话说
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .clickable(onClickLabel = if (favored) "取消收藏${board.name}" else "收藏${board.name}") {
          if (uid == null) {
            showLoginPrompt(nav, "登录后可把版块收藏到云端")
          } else {
            scope.launch {
              runCatching {
                if (favored) {
                  deps.boardFavorites.remove(uid, board)
                } else {
                  deps.boardFavorites.add(uid, board)
                }
              }.fold(
                onSuccess = {
                  Snackbars.show(if (favored) "已取消收藏该版面" else "已收藏到「我的收藏」")
                },
                onFailure = { Snackbars.show(failureText(it)) },
              )
            }
          }
        },
      contentAlignment = Alignment.Center,
    ) {
      AppIcon(
        icon = Ng2nIcon.STAR,
        tint = if (favored) colors.accent else colors.meta,
        size = 22.dp,
      )
    }
  }
}

/** 用户结果:一条资料卡,点击进资料页(设计稿缺失页面,按通知条目的头像行延伸)。 */
@Composable
private fun UserResult(query: String, nav: Navigator) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val buckets by deps.search.userStates.collectAsStateWithLifecycle()
  val state = remember(buckets, query) { deps.search.userStateOf(query) }
  LaunchedEffect(query) { deps.search.ensureUser(query) }

  val profile: UserProfile? = state.profile
  if (state.loading && profile == null && state.error == null) {
    LoadingState()
    return
  }
  if (profile == null) {
    SearchOutcome(
      error = state.error,
      emptyIcon = Ng2nIcon.PERSON,
      emptyText = "没有找到用户「$query」",
      onRetry = { scope.launch { deps.search.reloadUser(query) } },
    )
    return
  }

  val meta = buildList {
    add("UID ${profile.uid}")
    profile.group?.let { add(it) }
    add("发帖 ${profile.postCount}")
  }.joinToString(" · ")

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = profile.name) {
        nav.push(UserKey(uid = profile.uid, name = profile.name))
      }
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = Spacing.row, horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    // 远程头像归票 12 的图片管线;这里先用与楼层同款的纯色圆底占位
    InitialAvatar(
      name = profile.name,
      colorKey = profile.uid.toString(),
      size = 42.dp,
      fontSize = Typo.avatarInitial.size,
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = profile.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
          fontSize = Typo.listTitle.size,
          lineHeight = Typo.listTitle.lineHeight,
          color = colors.fg,
        ),
      )
      Text(
        text = meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
          fontSize = Typo.listMeta.size,
          lineHeight = Typo.listMeta.lineHeight,
          color = colors.meta,
        ),
      )
    }
    AppIcon(icon = Ng2nIcon.CHEVRON_RIGHT, tint = colors.meta, size = 20.dp)
  }
  Spacer(Modifier.height(LIST_TAIL_HEIGHT))
}

/** 空结果与拉取失败分开说(与主题列表页同一套话术)。 */
@Composable
private fun SearchOutcome(
  error: Throwable?,
  emptyIcon: Ng2nIcon,
  emptyText: String,
  onRetry: () -> Unit,
) {
  if (error != null) {
    LoadFailedNotice(error = error, onRetry = onRetry, variant = StateVariant.SCREEN)
  } else {
    EmptyState(icon = emptyIcon, text = emptyText)
  }
}
