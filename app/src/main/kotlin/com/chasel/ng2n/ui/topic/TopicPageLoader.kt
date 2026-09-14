package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.data.topic.TopicPageParams
import com.chasel.ng2n.data.topic.TopicRepository
import com.chasel.ng2n.di.ComputeDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopicPageLoader @Inject constructor(
  private val repository: TopicRepository,
  @ComputeDispatcher private val compute: CoroutineDispatcher,
) {
  suspend fun loadPage(
    params: TopicPageParams,
    style: TopicRenderStyle,
    urls: AttachmentUrls,
    refresh: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
  ): PageRenderModel {
    val detail = repository.loadDetail(params, refresh, nowMs)
    val sources = repository.loadedPages(params.tid, params.favCode)
    return withContext(compute) {
      TopicPageBuilder.build(detail, params.tid, style, urls, sources)
    }
  }

  suspend fun loadReplyPreviews(
    params: TopicPageParams,
    style: TopicRenderStyle,
    urls: AttachmentUrls,
  ): PageRenderModel? {
    val detail = repository.cachedDetail(params) ?: return null
    val refs = withContext(compute) { replyHeaderRefs(detail) }
      .filter { it.tid == null || it.tid == params.tid }
    if (refs.isEmpty()) return null
    val sources = (repository.loadedPages(params.tid, params.favCode) + detail).toMutableList()
    val attempted = HashSet<TopicPageParams>()
    for (ref in refs) {
      if (sources.any { source -> (source.floors + source.hotReplies).any { it.pid == ref.pid } }) continue
      val request = TopicPageParams(
        tid = params.tid,
        page = ref.page?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 1,
        favCode = params.favCode,
        pid = if (ref.page == null) ref.pid else null,
      )
      if (!attempted.add(request)) continue
      try {
        sources.add(repository.loadDetail(request))
      } catch (cause: CancellationException) {
        throw cause
      } catch (_: Exception) {
        // 原文预览失败不影响当前页正文及楼层链接。
      }
    }
    return withContext(compute) {
      TopicPageBuilder.build(detail, params.tid, style, urls, sources)
    }
  }
}
