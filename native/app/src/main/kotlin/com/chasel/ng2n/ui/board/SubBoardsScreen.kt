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

/**
 * 子版块订阅 / 屏蔽 —— 直译 RN 侧 `src/app/board/sub-boards.tsx`。
 *
 * 数据**不另打接口**:子版块随主题列表的 `__F.sub_forums` 一起下来,这里用**同一个
 * key** 读版块页已经拉过的第一页(ADR-0002:能少打就少打)。所以排序也要取默认那一档,
 * 否则 key 对不上会再拉一次。
 *
 * 设计稿没画这一屏,按列表页的行样式延伸:一行一个子版块,点行进它的主题列表,
 * 右边那颗按钮切订阅/屏蔽。
 */
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
  // 操作要带父版块的 fid;合集没有 fid 时退回键上的 id
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
      // 设计稿把版块名并进顶栏标题(「子板块 · 网事杂谈」),没有副标题条。
      // (设计稿写的是「子板块」,CONTEXT.md 的词条是「子版块」,按术语表来)
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
          // key 用 kind+id:合集与版块各自编号(stid vs fid),只用 id 有撞车的可能
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
                    // 失败时本地状态已经回滚,只把服务端的话说出来
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

/** 一行子版块:左边名字与副标题,右边订阅开关。 */
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

    // 服务端不让改的(attributes 太小)只显示状态,不给按钮
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

    /*
     * 三态(票 07 修掉的「白名单误报」):白名单是「已订阅」的唯一证据,没命中只说明
     * 我们认不出来,**不等于被屏蔽**。RN 版原行为是把 UNKNOWN 一律画成「已屏蔽」,
     * 实测「网络游戏综合」从没被屏蔽过却显示已屏蔽。
     */
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
