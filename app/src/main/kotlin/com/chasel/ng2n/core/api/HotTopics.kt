package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.HOT_WINDOW_HOURS
import com.chasel.ng2n.core.local.aggregateHotTopics
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

const val DEFAULT_HOT_PAGES = 5

data class HotTopicPages(
  val pages: List<TopicList>,
  val failedPages: List<Int>,
  val pagesTried: Int,
)

suspend fun fetchHotTopicPages(
  client: NgaClient,
  boardId: Long,
  kind: BoardKind,
  pages: Int = DEFAULT_HOT_PAGES,
): HotTopicPages {
  val pagesTried = maxOf(1, pages)

  val settled = supervisorScope {
    (1..pagesTried)
      .map { page ->
        async {
          try {
            Result.success(fetchTopicList(client, boardId = boardId, kind = kind, page = page))
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Throwable) {
            Result.failure(error)
          }
        }
      }
      .map { it.await() }
  }

  val ok = ArrayList<TopicList>()
  val failed = ArrayList<Int>()
  settled.forEachIndexed { index, outcome ->
    val value = outcome.getOrNull()
    if (value != null) ok += value else failed += index + 1
  }

  if (ok.isEmpty()) {
    throw settled.firstOrNull()?.exceptionOrNull() ?: IllegalStateException("热帖:一页都没拉回来")
  }

  return HotTopicPages(pages = ok, failedPages = failed, pagesTried = pagesTried)
}

data class HotTopics(
  val topics: List<Topic>,
  val failedPages: List<Int>,
  val pagesTried: Int,
)

suspend fun fetchHotTopics(
  client: NgaClient,
  boardId: Long,
  kind: BoardKind,
  nowSeconds: Long,
  pages: Int = DEFAULT_HOT_PAGES,
  windowHours: Int = HOT_WINDOW_HOURS,
): HotTopics {
  val fetched = fetchHotTopicPages(client, boardId = boardId, kind = kind, pages = pages)
  return HotTopics(
    topics = aggregateHotTopics(
      pages = fetched.pages.map { it.topics },
      now = nowSeconds,
      windowHours = windowHours,
    ),
    failedPages = fetched.failedPages,
    pagesTried = fetched.pagesTried,
  )
}
