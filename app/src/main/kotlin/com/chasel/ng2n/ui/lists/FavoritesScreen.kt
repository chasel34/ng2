package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.favorites.pickFavoriteFolder
import com.chasel.ng2n.data.favorites.unfavoriteConfirmMessage
import com.chasel.ng2n.data.filters.filterTopics
import com.chasel.ng2n.ui.board.TopicRow
import com.chasel.ng2n.ui.board.buildTopicRows
import com.chasel.ng2n.ui.board.dateText
import com.chasel.ng2n.ui.common.ConfirmDialog
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingFooter
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.MenuItem
import com.chasel.ng2n.ui.common.OverflowMenu
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateAction
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.ListPullToRefreshBox
import com.chasel.ng2n.ui.common.rememberPagedFlingBehavior
import com.chasel.ng2n.ui.common.rememberShouldLoadNextPage
import com.chasel.ng2n.ui.common.rememberTailPlaceholders
import com.chasel.ng2n.ui.common.tailPlaceholders
import com.chasel.ng2n.ui.filters.rememberFilterRules
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.FavoriteFoldersKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalNg2nTitleColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val titleColors = LocalNg2nTitleColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = null,
  )
  val uid = accountsState?.let(::currentAccountOf)?.uid

  val folderBuckets by deps.topicFavorites.folderStates.collectAsStateWithLifecycle()
  val topicBuckets by deps.topicFavorites.topicStates.collectAsStateWithLifecycle()
  val foldersState = remember(folderBuckets, uid) { deps.topicFavorites.foldersOf(uid) }
  val folders = foldersState.folders

  var pickedFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
  var pickedFolderUid by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(accountsState) {
    if (accountsState != null && pickedFolderUid != uid) {
      pickedFolderId = null
      pickedFolderUid = uid
    }
  }
  var switcherOpen by remember { mutableStateOf(false) }

  val folder = remember(folders, pickedFolderId, pickedFolderUid, uid) {
    pickFavoriteFolder(folders, pickedFolderId.takeIf { pickedFolderUid == uid })
  }
  val state = remember(topicBuckets, uid, folder?.id) {
    deps.topicFavorites.topicsOf(uid, folder?.id)
  }

  LaunchedEffect(uid) { deps.topicFavorites.ensureFolders(uid) }
  LaunchedEffect(uid, folder?.id) { deps.topicFavorites.ensureTopics(uid, folder?.id) }

  val filterRules = rememberFilterRules()
  val rows = remember(state.topics, filterRules, colors, titleColors) {
    buildTopicRows(
      topics = filterTopics(filterRules, state.topics),
      colors = colors,
      titleColors = titleColors,
      simple = true,
      timeOf = { dateText(it.postedAt) },
    )
  }

  val openTopic: (Topic) -> Unit = { topic ->
    nav.push(TopicKey(tid = topic.tid, title = topic.subject, fav = topic.favCode))
  }

  var unfavoriting by remember { mutableStateOf<Topic?>(null) }
  var busy by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "已收藏的主题", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.FOLDER_SPECIAL,
        size = 22.dp,
        contentDescription = "收藏夹管理",
        onClick = { nav.push(FavoriteFoldersKey) },
      )
    }

    if (folder != null) {
      val switchable = folders.size > 1
      ListSubtitle(
        text = "${folder.name} · ${folder.count} 个主题" +
          if (switchable) " · 点此换收藏夹" else "",
        trailing = if (!switchable) {
          null
        } else {
          { AppIcon(icon = Ng2nIcon.EXPAND_MORE, tint = colors.meta, size = 16.dp) }
        },
        onClick = if (switchable) ({ switcherOpen = true }) else null,
      )
    }

    when {
      uid == null -> EmptyState(
        icon = Ng2nIcon.PERSON_ADD,
        text = "登录后才能看云端收藏夹",
        action = StateAction("去登录") { nav.push(com.chasel.ng2n.ui.Login) },
      )

      foldersState.loading && !foldersState.loaded -> LoadingState()

      !foldersState.loaded -> Column(Modifier.fillMaxSize()) {
        LoadFailedNotice(
          error = foldersState.error,
          onRetry = { scope.launch { deps.topicFavorites.reloadFolders(uid) } },
          variant = StateVariant.SCREEN,
        )
      }

      folder == null -> EmptyState(
        icon = Ng2nIcon.FOLDER,
        text = "还没有收藏夹",
        action = StateAction("去新建") { nav.push(FavoriteFoldersKey) },
      )

      state.loading && state.pages.isEmpty() -> LoadingState()

      rows.isEmpty() && state.error != null -> LoadFailedNotice(
        error = state.error,
        onRetry = { scope.launch { deps.topicFavorites.refreshTopics(uid, folder.id) } },
        variant = StateVariant.SCREEN,
      )

      rows.isEmpty() -> EmptyState(
        icon = if (state.topics.isEmpty()) Ng2nIcon.STAR else Ng2nIcon.FILTER_ALT,
        text = if (state.topics.isEmpty()) {
          "「${folder.name}」里还没有主题"
        } else {
          "「${folder.name}」里的主题都被屏蔽规则挡住了"
        },
        action = StateAction("刷新") {
          scope.launch { deps.topicFavorites.refreshTopics(uid, folder.id) }
        },
      )

      else -> {
        val listState = rememberLazyListState()
        val shouldLoadMore by rememberShouldLoadNextPage(listState, rows.size)
        LaunchedEffect(listState, state.hasNextPage, state.loadingNextPage) {
          snapshotFlow { shouldLoadMore }.collect {
            if (it) deps.topicFavorites.loadNextTopicPage(uid, folder.id)
          }
        }
        val flingBehavior = rememberPagedFlingBehavior(listState) {
          state.hasNextPage || state.loadingNextPage
        }
        val placeholders = rememberTailPlaceholders(listState, state.loadingNextPage)

        ListPullToRefreshBox(
          isRefreshing = state.refreshing && !state.loadingNextPage,
          onRefresh = { scope.launch { deps.topicFavorites.refreshTopics(uid, folder.id) } },
          modifier = Modifier.fillMaxSize(),
        ) {
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
            ) { index ->
              TopicRow(
                model = rows[index],
                onClick = openTopic,
                onLongClick = { topic -> unfavoriting = topic },
              )
            }
            tailPlaceholders(placeholders)
            item(key = ListKeys.FOOTER, contentType = "footer") {
              Column {
                if (!state.hasNextPage && !state.loadingNextPage) {
                  Text(
                    text = "长按一条可以把它从这个收藏夹里移出。",
                    modifier = Modifier.fillMaxWidth().padding(Spacing.row),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                      fontSize = Typo.listMeta.size,
                      lineHeight = Typo.listMeta.lineHeight,
                      color = colors.meta,
                    ),
                  )
                }
                if (state.loadingNextPage) {
                  LoadingFooter("正在载入第 ${state.pages.size + 1} 页…")
                }
                if (!state.loadingNextPage && state.error != null) {
                  Text(
                    text = failureText(state.error),
                    modifier = Modifier.fillMaxWidth().padding(Spacing.row),
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
    }
  }

  val removing = unfavoriting
  ConfirmDialog(
    open = removing != null && folder != null,
    title = "取消收藏",
    message = if (removing == null || folder == null) {
      null
    } else {
      unfavoriteConfirmMessage(removing.subject, folder.name)
    },
    confirmLabel = if (busy) "移出中…" else "取消收藏",
    destructive = true,
    onCancel = { unfavoriting = null },
    onConfirm = {
      val topic = removing ?: return@ConfirmDialog
      val currentUid = uid ?: return@ConfirmDialog
      val currentFolder = folder ?: return@ConfirmDialog
      busy = true
      scope.launch {
        val result = runCatching {
          deps.topicFavorites.unfavoriteTopic(
            uid = currentUid,
            tid = topic.tid,
            folderId = currentFolder.id,
          )
        }
        busy = false
        unfavoriting = null
        Snackbars.show(
          result.fold(
            onSuccess = { "已从「${currentFolder.name}」移出" },
            onFailure = { failureText(it) },
          ),
        )
      }
    },
  )

  OverflowMenu(
    open = switcherOpen,
    onDismiss = { switcherOpen = false },
    items = folders.map { item ->
      MenuItem(
        key = item.id.toString(),
        label = "${item.name}（${item.count}）",
        selected = item.id == folder?.id,
        onClick = {
          switcherOpen = false
          pickedFolderId = item.id
        },
      )
    },
  )
}
