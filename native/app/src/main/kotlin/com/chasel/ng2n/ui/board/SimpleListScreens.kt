package com.chasel.ng2n.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicSort
import com.chasel.ng2n.core.local.HOT_WINDOW_HOURS
import com.chasel.ng2n.data.board.HotTopicsRepository
import com.chasel.ng2n.data.board.TopicListRepository
import com.chasel.ng2n.data.filters.filterTopics
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingFooter
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.NOT_AVAILABLE_MESSAGE
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateAction
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.rememberListPullToRefreshState
import com.chasel.ng2n.ui.filters.rememberFilterRules
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.BoardKey
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalNg2nTitleColors
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

/**
 * 设计稿 `isSimpleList` 那一档的副标题条:12px meta 色、surface-2 底、下面一条分隔线。
 */
@Composable
private fun SubtitleBar(parts: List<String>) {
  val colors = LocalNg2nColors.current
  Text(
    text = parts.joinToString(" · "),
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
}

/**
 * 24 小时热帖(CONTEXT.md「热帖」)—— 直译 RN 侧 `src/app/board/hot.tsx`。
 *
 * 并发拉版块前几页、**本地聚合**的榜单,不是服务端 API。UI 按设计稿 simple-list 屏:
 * 顶栏带刷新钮,行复用 [TopicRow] 的 simple 档(标题 16、右侧是相对时间)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotTopicsScreen(key: BoardKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val titleColors = LocalNg2nTitleColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val hotKey = HotTopicsRepository.Key(boardId = key.id, kind = key.kind)
  val all by deps.hotTopics.states.collectAsStateWithLifecycle()
  val state = all[hotKey] ?: HotTopicsRepository.State()
  LaunchedEffect(hotKey) { deps.hotTopics.ensureLoaded(hotKey) }

  // 榜单也是主题列表,一样过屏蔽规则(票 29):命中的整行不画
  val filterRules = rememberFilterRules()
  val rows = remember(state.topics, filterRules, colors, titleColors, state.fetchedAt) {
    buildTopicRows(filterTopics(filterRules, state.topics), colors, titleColors, simple = true) { topic ->
      relativeTimeText(topic.postedAt, state.fetchedAt)
    }
  }

  // 副标题条(设计稿 listSub):来源版块 + 排序口径;部分页失败时把话说在这儿
  val subtitle = buildList {
    key.name?.let { add(it) }
    add("近 $HOT_WINDOW_HOURS 小时 · 按回复数排序")
    if (state.failedPages.isNotEmpty()) {
      add("${state.pagesTried} 页里 ${state.failedPages.size} 页拉取失败,榜单不完整")
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
      TopBarTitle(text = "24 小时热帖", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.REFRESH,
        size = 22.dp,
        contentDescription = "刷新热帖榜",
        onClick = { scope.launch { deps.hotTopics.refresh(hotKey) } },
      )
    }
    SubtitleBar(subtitle)

    when {
      state.loading && state.fetchedAt == 0L -> LoadingState(Modifier.fillMaxSize())
      state.fetchedAt == 0L && state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
        LoadFailedNotice(
          error = state.error,
          onRetry = { scope.launch { deps.hotTopics.refresh(hotKey) } },
          variant = StateVariant.SCREEN,
        )
      }
      // 榜单有货、过完屏蔽规则空了,得说清是被自己的规则挡的,别当成「没有新主题」
      rows.isEmpty() -> EmptyState(
        icon = if (state.topics.isEmpty()) Ng2nIcon.LOCAL_FIRE_DEPARTMENT else Ng2nIcon.FILTER_ALT,
        text = if (state.topics.isEmpty()) {
          "近 $HOT_WINDOW_HOURS 小时没有新主题"
        } else {
          "榜单上的主题都被屏蔽规则挡住了"
        },
        action = StateAction("刷新") { scope.launch { deps.hotTopics.refresh(hotKey) } },
      )
      else -> PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { scope.launch { deps.hotTopics.refresh(hotKey) } },
        state = rememberListPullToRefreshState(),
        modifier = Modifier.fillMaxSize(),
      ) {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 26.dp),
        ) {
          items(
            count = rows.size,
            key = { index -> rows[index].topic.tid },
            contentType = { "topic" },
          ) { index ->
            // 榜单在聚合时已剔掉合集/镜像/外链行,进来的都是普通讨论串
            TopicRow(rows[index], onClick = { topic: Topic ->
              nav.push(TopicKey(tid = topic.tid, title = topic.subject, fav = topic.favCode))
            })
          }
        }
      }
    }
  }
}

/**
 * 精华区(功能文档 §2.2)—— 直译 RN 侧 `src/app/board/recommend.tsx`。
 *
 * `recommend=1` 的主题列表,服务端固定按发帖时间排。UI 按设计稿 simple-list 屏,
 * 行复用 [TopicRow] 的 simple 档(标题 16、右侧是发帖日期)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendScreen(key: BoardKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val titleColors = LocalNg2nTitleColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val listKey = TopicListRepository.Key(
    boardId = key.id,
    kind = key.kind,
    // 精华区固定 postdatedesc,sort 不参与请求
    sort = TopicSort.POST_DATE,
    recommend = true,
  )
  val all by deps.topicLists.states.collectAsStateWithLifecycle()
  val state = all[listKey] ?: TopicListRepository.State()
  LaunchedEffect(listKey) { deps.topicLists.ensureFirstPage(listKey) }

  // 精华区也是主题列表,不该漏网(票 29)
  val filterRules = rememberFilterRules()
  val rows = remember(state.topics, filterRules, colors, titleColors) {
    buildTopicRows(filterTopics(filterRules, state.topics), colors, titleColors, simple = true) {
      dateText(it.postedAt)
    }
  }

  // 副标题条(设计稿 listSub:「版面推荐 · 共 148 篇」,「版面」沿用设计稿原字)
  val totalRows = state.pages.firstOrNull()?.totalRows
  val subtitle = buildList {
    key.name?.let { add(it) }
    add("版面推荐")
    if (totalRows != null && totalRows > 0) add("共 $totalRows 篇")
  }

  val listState = rememberLazyListState()
  val shouldLoadMore by remember(listState, rows.size) {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
      last >= listState.layoutInfo.totalItemsCount - 6
    }
  }
  LaunchedEffect(listState, state.hasNextPage, state.loadingNextPage) {
    snapshotFlow { shouldLoadMore }.collect { if (it) deps.topicLists.loadNextPage(listKey) }
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
      TopBarTitle(text = "精华区", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      // 设计稿的「按版块筛选」是 toast 桩(research/inventory.md §2),入口保留
      TopBarButton(
        icon = Ng2nIcon.FILTER_ALT,
        size = 22.dp,
        contentDescription = "按版块筛选",
        onClick = { Snackbars.show(NOT_AVAILABLE_MESSAGE) },
      )
    }
    SubtitleBar(subtitle)

    when {
      state.loading && state.pages.isEmpty() -> LoadingState(Modifier.fillMaxSize())
      rows.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
        LoadFailedNotice(
          error = state.error,
          onRetry = { scope.launch { deps.topicLists.retry(listKey) } },
          variant = StateVariant.SCREEN,
        )
      }
      rows.isEmpty() -> EmptyState(
        icon = if (state.topics.isEmpty()) Ng2nIcon.ARTICLE else Ng2nIcon.FILTER_ALT,
        text = if (state.topics.isEmpty()) {
          "这个版块还没有精华主题"
        } else {
          "这一页的主题都被屏蔽规则挡住了"
        },
        action = StateAction("刷新") { scope.launch { deps.topicLists.refresh(listKey) } },
      )
      else -> PullToRefreshBox(
        isRefreshing = state.refreshing && !state.loadingNextPage,
        onRefresh = { scope.launch { deps.topicLists.refresh(listKey) } },
        state = rememberListPullToRefreshState(),
        modifier = Modifier.fillMaxSize(),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 26.dp),
        ) {
          items(
            count = rows.size,
            key = { index -> rows[index].topic.tid },
            contentType = { "topic" },
          ) { index ->
            TopicRow(rows[index], onClick = { topic: Topic ->
              // 与主题列表页同一套规则:快捷方式行开版块、活动行走站内网页兜底
              val shortcut = topic.shortcut
              val jumpUrl = topic.jumpUrl
              when {
                shortcut != null -> nav.push(
                  BoardKey(id = shortcut.id, name = topic.subject, kind = shortcut.kind),
                )
                jumpUrl != null -> nav.push(com.chasel.ng2n.ui.nav.WebKey(jumpUrl, topic.subject))
                else -> nav.push(
                  TopicKey(tid = topic.tid, title = topic.subject, fav = topic.favCode),
                )
              }
            })
          }
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
            }
          }
        }
      }
    }
  }
}
