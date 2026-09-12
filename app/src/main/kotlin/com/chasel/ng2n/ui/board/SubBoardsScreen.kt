package com.chasel.ng2n.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.SubBoard
import com.chasel.ng2n.core.api.SubBoardAction
import com.chasel.ng2n.core.api.SubBoardSubscription
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.board.TopicListRepository
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.rowClickable
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.SubBoardsKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

@Composable
fun SubBoardsScreen(key: SubBoardsKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val listKey = TopicListRepository.Key(
    boardId = key.id,
    kind = key.kind,
    sort = TopicSort.LAST_POST,
  )
  val all by deps.topicLists.states.collectAsStateWithLifecycle()
  val state = all[listKey] ?: TopicListRepository.State()
  LaunchedEffect(listKey) { deps.topicLists.ensureFirstPage(listKey) }

  val firstPage = state.pages.firstOrNull()
  val subBoards = firstPage?.subBoards.orEmpty()
  val parentFid = firstPage?.board?.fid ?: key.id

  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = com.chasel.ng2n.data.account.EMPTY_ACCOUNTS,
  )
  val uid = currentAccountOf(accountsState)?.uid
  val overrides by deps.subBoards.overrideFlow.collectAsStateWithLifecycle()
  val inFlight by deps.subBoards.inFlightFlow.collectAsStateWithLifecycle()

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(
        text = "子版块 · ${key.name ?: "版块 ${key.id}"}",
        variant = TopBarTitleVariant.SUB,
      )
    }

    when {
      state.loading && state.pages.isEmpty() -> LoadingState(Modifier.fillMaxSize())
      state.pages.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
        LoadFailedNotice(
          error = state.error,
          onRetry = { scope.launch { deps.topicLists.retry(listKey) } },
          variant = StateVariant.SCREEN,
        )
      }
      subBoards.isEmpty() -> EmptyState(icon = Ng2nIcon.ACCOUNT_TREE, text = "这个版块没有子版块")
      else -> LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 26.dp),
      ) {
        items(
          count = subBoards.size,
          key = { index -> "${subBoards[index].kind}/${subBoards[index].id}" },
          contentType = { "sub-board" },
        ) { index ->
          val subBoard = subBoards[index]
          SubBoardRow(
            subBoard = subBoard,
            state = deps.subBoards.stateOf(uid, subBoard, overrides),
            pending = uid != null && deps.subBoards.keyOf(uid, subBoard) in inFlight,
            onOpen = {
              nav.push(BoardKey(id = subBoard.id, name = subBoard.name, kind = subBoard.kind))
            },
            onToggle = { subscribe ->
              if (uid == null) {
                showLoginPrompt(nav, "登录后才能订阅或屏蔽子版块")
              } else {
                val action = if (subscribe) SubBoardAction.SUBSCRIBE else SubBoardAction.BLOCK
                val verb = if (subscribe) "订阅" else "屏蔽"
                scope.launch {
                  runCatching {
                    deps.subBoards.toggle(uid, subBoard, parentFid, action)
                  }.fold(
                    onSuccess = { Snackbars.show("已$verb「${subBoard.name}」") },
                    onFailure = { Snackbars.show(failureText(it)) },
                  )
                }
              }
            },
          )
        }
        item(key = ListKeys.FOOTNOTE) {
          Text(
            text = "订阅后这个子版块的主题会出现在版块列表里,屏蔽则不再出现。",
            modifier = Modifier.padding(Spacing.lg),
            style = TextStyle(
              fontSize = Typo.note.size,
              lineHeight = Typo.note.lineHeight,
              color = colors.meta,
            ),
          )
        }
      }
    }
  }
}

@Composable
private fun SubBoardRow(
  subBoard: SubBoard,
  state: com.chasel.ng2n.core.api.SubBoardState,
  pending: Boolean,
  onOpen: () -> Unit,
  onToggle: (Boolean) -> Unit,
) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = subBoard.name, onClick = onOpen)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = Spacing.row, horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.row),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = subBoard.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
          fontSize = Typo.drawerItem.size,
          lineHeight = Typo.drawerItem.lineHeight,
          color = colors.fg,
        ),
      )
      val info = subBoard.info
      if (info != null) {
        Text(
          text = info,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 3.dp),
          style = TextStyle(
            fontSize = Typo.listSubtitle.size,
            lineHeight = Typo.listSubtitle.lineHeight,
            color = colors.meta,
          ),
        )
      }
    }

    if (!state.filterable) {
      Text(
        text = "不可更改",
        style = TextStyle(
          fontSize = Typo.listMeta.size,
          lineHeight = Typo.listMeta.lineHeight,
          color = colors.meta,
        ),
      )
      return@Row
    }

    val label = when {
      pending -> "处理中"
      state.subscription == SubBoardSubscription.SUBSCRIBED -> "已订阅"
      state.subscription == SubBoardSubscription.BLOCKED -> "已屏蔽"
      else -> "未知"
    }
    val filled = state.subscription == SubBoardSubscription.SUBSCRIBED
    Box(
      modifier = Modifier
        .height(32.dp)
        .clip(RoundedCornerShape(Radius.pill))
        .then(if (filled) Modifier.background(colors.primary) else Modifier)
        .border(1.dp, colors.primary, RoundedCornerShape(Radius.pill))
        .clickable(enabled = !pending, onClickLabel = label) { onToggle(!filled) }
        .padding(horizontal = 13.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        style = TextStyle(
          fontSize = Typo.listMeta.size,
          lineHeight = Typo.listMeta.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = if (filled) colors.onPrimary else colors.primary,
        ),
      )
    }
  }
}
