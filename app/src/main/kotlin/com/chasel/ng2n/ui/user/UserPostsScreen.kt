package com.chasel.ng2n.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.UserPostKind
import com.chasel.ng2n.data.user.UserPostsRepository
import com.chasel.ng2n.ui.bbcode.plainTextOf
import com.chasel.ng2n.ui.board.TopicRow
import com.chasel.ng2n.ui.board.buildTopicRows
import com.chasel.ng2n.ui.board.dateText
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingFooter
import com.chasel.ng2n.ui.common.LoadingState
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
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.nav.UserPostsKey
import com.chasel.ng2n.ui.nav.UserPostKind as NavUserPostKind
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalNg2nTitleColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

internal fun titleOf(kind: UserPostKind): String =
  if (kind == UserPostKind.TOPICS) "我的主题" else "我的回复"

internal fun emptyTextOf(kind: UserPostKind): String =
  if (kind == UserPostKind.TOPICS) "还没有发过主题" else "还没有回过帖"

internal fun replyTimeText(topic: Topic): String {
  val postedAt = topic.reply?.postedAt ?: 0L
  return if (postedAt == 0L) "—" else dateText(postedAt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPostsScreen(key: UserPostsKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val titleColors = LocalNg2nTitleColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val kind = if (key.kind == NavUserPostKind.TOPICS) UserPostKind.TOPICS else UserPostKind.REPLIES
  val postsKey = UserPostsRepository.Key(uid = key.uid, kind = kind)
  val all by deps.userPosts.states.collectAsStateWithLifecycle()
  val state = all[postsKey] ?: UserPostsRepository.State()
  LaunchedEffect(postsKey) { deps.userPosts.ensureFirstPage(postsKey) }

  val openTopic: (Topic) -> Unit = { topic ->
    if (topic.denied) {
      Snackbars.show(topic.subject)
    } else {
      nav.push(
        TopicKey(
          tid = topic.tid,
          title = topic.subject,
          fav = topic.favCode,
          pid = topic.reply?.pid,
        ),
      )
    }
  }

  val topicRows = remember(state.topics, colors, titleColors, kind) {
    if (kind == UserPostKind.REPLIES) {
      emptyList()
    } else {
      buildTopicRows(state.topics, colors, titleColors, simple = true) { dateText(it.postedAt) }
    }
  }

  val listState = rememberLazyListState()
  val shouldLoadMore by rememberShouldLoadNextPage(listState, state.topics.size)
  LaunchedEffect(listState, state.hasNextPage, state.loadingNextPage) {
    snapshotFlow { shouldLoadMore }.collect { if (it) deps.userPosts.loadNextPage(postsKey) }
  }
  val flingBehavior = rememberPagedFlingBehavior(listState) {
    state.hasNextPage || state.loadingNextPage
  }
  val placeholders = rememberTailPlaceholders(listState, state.loadingNextPage)

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = titleOf(kind), variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.PERSON,
        size = 22.dp,
        contentDescription = "查看资料",
        onClick = { nav.push(UserKey(uid = key.uid, name = key.name)) },
      )
    }

    Text(
      text = if (key.name == null) "UID ${key.uid}" else "${key.name}(${key.uid})",
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surface2)
        .drawBehind {
          val y = size.height - 1f
          drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
        }
        .padding(vertical = 11.dp, horizontal = Spacing.lg),
      style = TextStyle(
        fontSize = Typo.listSubtitle.size,
        lineHeight = Typo.listSubtitle.lineHeight,
        color = colors.meta,
      ),
    )

    when {
      state.loading && state.pages.isEmpty() -> LoadingState(Modifier.fillMaxSize())
      state.topics.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
        LoadFailedNotice(
          error = state.error,
          onRetry = { scope.launch { deps.userPosts.retry(postsKey) } },
          variant = StateVariant.SCREEN,
        )
      }
      state.topics.isEmpty() -> EmptyState(
        icon = Ng2nIcon.ARTICLE,
        text = emptyTextOf(kind),
        action = StateAction("刷新") { scope.launch { deps.userPosts.refresh(postsKey) } },
      )
      else -> ListPullToRefreshBox(
        isRefreshing = state.refreshing && !state.loadingNextPage,
        onRefresh = { scope.launch { deps.userPosts.refresh(postsKey) } },
        modifier = Modifier.fillMaxSize(),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 26.dp),
          flingBehavior = flingBehavior,
        ) {
          items(
            count = state.topics.size,
            key = { index ->
              val topic = state.topics[index]
              topic.reply?.let { "p${it.pid}" } ?: "t${topic.tid}"
            },
            contentType = { if (kind == UserPostKind.REPLIES) "reply" else "topic" },
          ) { index ->
            if (kind == UserPostKind.REPLIES) {
              ReplyRow(
                topic = state.topics[index],
                time = replyTimeText(state.topics[index]),
                onClick = openTopic,
              )
            } else {
              TopicRow(topicRows[index], onClick = openTopic)
            }
          }
          tailPlaceholders(placeholders)
          item(key = ListKeys.FOOTER, contentType = "footer") {
            Column {
              if (state.loadingNextPage) LoadingFooter("正在载入第 ${state.pages.size + 1} 页…")
              val error = state.error
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
              if (!state.hasNextPage && !state.loadingNextPage) {
                Text(
                  text = "没有更多了",
                  modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                  textAlign = TextAlign.Center,
                  style = TextStyle(
                    fontSize = Typo.listMeta.size,
                    lineHeight = Typo.listMeta.lineHeight,
                    color = colors.meta,
                  ),
                )
              }
              Spacer(Modifier.height(26.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
fun ReplyRow(topic: Topic, time: String, onClick: (Topic) -> Unit, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val denied = topic.denied
  val excerpt = remember(topic) {
    val text = topic.reply?.content?.let(::plainTextOf).orEmpty()
    text.ifEmpty { "(空回复)" }
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClickLabel = topic.subject) { onClick(topic) }
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = Spacing.row, bottom = Spacing.md)
      .padding(horizontal = Spacing.lg),
  ) {
    Text(
      text = excerpt,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = TextStyle(
        fontSize = Typo.listTitle.size,
        lineHeight = Typo.listTitle.lineHeight,
        color = colors.fg,
      ),
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      AppIcon(
        icon = if (denied) Ng2nIcon.BLOCK else Ng2nIcon.ARTICLE,
        tint = if (denied) colors.meta else colors.tag,
        size = 14.dp,
      )
      Text(
        text = topic.subject,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
        style = TextStyle(
          fontSize = Typo.listMeta.size,
          lineHeight = Typo.listMeta.lineHeight,
          color = if (denied) colors.meta else colors.link,
        ),
      )
      Text(
        text = time,
        style = TextStyle(
          fontSize = Typo.listMeta.size,
          lineHeight = Typo.listMeta.lineHeight,
          color = colors.meta,
        ),
      )
    }
  }
}
