package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.api.AttachmentUrls
import com.chasel.ng2n.core.api.TopicDetail
import com.chasel.ng2n.core.api.fetchTopicDetail
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.api.TopicPageSnapshot
import com.chasel.ng2n.data.net.TopicCachePayloadReader
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

data class TopicPageParams(
  val tid: Long,
  val page: Int,
  val favCode: String? = null,
  val pid: Long? = null,
  val authorId: Long? = null,
) {
  val filtered: Boolean get() = pid != null || authorId != null
}

sealed interface CacheDownloadOutcome {
  data class Done(val cached: Int) : CacheDownloadOutcome

  data class Cancelled(val cached: Int) : CacheDownloadOutcome

  data class Failed(val cached: Int, val message: String) : CacheDownloadOutcome

  data object Busy : CacheDownloadOutcome
}

data class CacheDownloadState(
  val tid: Long? = null,
  val done: Int = 0,
  val total: Int = 0,
)

@Singleton
class TopicRepository @Inject constructor(
  private val client: NgaClient,
  private val cachePayloads: TopicSnapshotSink,
  @IoScope private val scope: CoroutineScope,
  @ComputeDispatcher private val compute: CoroutineDispatcher,
  // 请求装配也需离开主线程；测试中与调用方共用虚拟时间调度器。
  @IoDispatcher private val io: CoroutineDispatcher,
) {

  private val details = LinkedHashMap<TopicPageParams, CachedDetail>()
  private val detailsLock = Mutex()

  private val downloadState = MutableStateFlow(CacheDownloadState())

  val cacheDownload: StateFlow<CacheDownloadState> = downloadState.asStateFlow()

  private val downloadLock = Mutex()
  private var downloadCancelled = false

  suspend fun loadPage(
    params: TopicPageParams,
    style: TopicRenderStyle,
    urls: AttachmentUrls,
    refresh: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
  ): PageRenderModel {
    val detail = loadDetail(params, refresh, nowMs)
    return withContext(compute) {
      TopicPageBuilder.build(detail, params.tid, style, urls)
    }
  }

  suspend fun cachedDetail(
    params: TopicPageParams,
    nowMs: Long = System.currentTimeMillis(),
  ): TopicDetail? = detailsLock.withLock {
    details[params]?.takeIf { nowMs - it.atMs < TOPIC_DETAIL_STALE_MS }?.detail
  }

  suspend fun loadDetail(
    params: TopicPageParams,
    refresh: Boolean = false,
    nowMs: Long = System.currentTimeMillis(),
  ): TopicDetail {
    if (!refresh) {
      val cached = detailsLock.withLock { details[params] }
      if (cached != null && nowMs - cached.atMs < TOPIC_DETAIL_STALE_MS) return cached.detail
    }

    val detail = withContext(io) {
      fetchTopicDetail(
        client = client,
        tid = params.tid,
        page = params.page,
        favCode = params.favCode,
        pid = params.pid,
        authorId = params.authorId,
        deferSnapshot = { createSnapshot ->
          scope.launch {
            delay(FOREGROUND_CACHE_DELAY_MS)
            runCatching { cachePayloads.save(createSnapshot()) }
          }
        },
      )
    }

    detailsLock.withLock { remember(params, detail, nowMs) }
    return detail
  }

  suspend fun loadedPages(tid: Long, favCode: String?): List<TopicDetail> =
    detailsLock.withLock {
      details.entries
        .filter { (key, _) ->
          key.tid == tid && key.favCode == favCode && key.pid == null && key.authorId == null
        }
        .map { it.value.detail }
        .sortedBy { it.page }
    }

  suspend fun remember(params: TopicPageParams, detail: TopicDetail) {
    detailsLock.withLock { remember(params, detail, System.currentTimeMillis()) }
  }

  private fun remember(params: TopicPageParams, detail: TopicDetail, nowMs: Long) {
    details.remove(params)
    details[params] = CachedDetail(detail, nowMs)
    while (details.size > DETAIL_CACHE_CAPACITY) {
      val oldest = details.keys.firstOrNull() ?: break
      details.remove(oldest)
    }
  }

  fun forgetReadPhpCombo() = client.forgetSuccessfulCombo("read.php")

  fun cancelCacheDownload() {
    downloadCancelled = true
  }

  suspend fun cacheTopicPages(
    tid: Long,
    pages: List<Int>,
    favCode: String? = null,
    intervalMs: Long = PAGE_INTERVAL_MS,
  ): CacheDownloadOutcome {
    if (pages.isEmpty()) return CacheDownloadOutcome.Done(0)
    if (!downloadLock.tryLock()) return CacheDownloadOutcome.Busy

    downloadCancelled = false
    downloadState.value = CacheDownloadState(tid = tid, done = 0, total = pages.size)
    var cached = 0
    try {
      for ((index, page) in pages.withIndex()) {
        if (downloadCancelled) return CacheDownloadOutcome.Cancelled(cached)
        if (index > 0) {
          delay(intervalMs)
          if (downloadCancelled) return CacheDownloadOutcome.Cancelled(cached)
        }

        try {
          var snapshot: TopicPageSnapshot? = null
          withContext(io) {
            fetchTopicDetail(
              client = client,
              tid = tid,
              page = page,
              favCode = favCode,
              onSnapshot = { snapshot = it },
            )
          }
          snapshot?.let { runCatching { cachePayloads.save(it) } }
        } catch (cause: CancellationException) {
          throw cause
        } catch (cause: Exception) {
          if (downloadCancelled) return CacheDownloadOutcome.Cancelled(cached)
          return CacheDownloadOutcome.Failed(cached, cause.message ?: "缓存失败")
        }

        cached += 1
        downloadState.value = downloadState.value.copy(done = cached)
      }
      return CacheDownloadOutcome.Done(cached)
    } finally {
      downloadState.value = CacheDownloadState()
      downloadCancelled = false
      downloadLock.unlock()
    }
  }

  private class CachedDetail(val detail: TopicDetail, val atMs: Long)

  companion object {
    const val TOPIC_DETAIL_STALE_MS = 2 * 60_000L

    const val PAGE_INTERVAL_MS = 800L

    const val FOREGROUND_CACHE_DELAY_MS = 320L

    const val DETAIL_CACHE_CAPACITY = 40
  }
}

fun interface TopicSnapshotSink {
  suspend fun save(snapshot: TopicPageSnapshot)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComputeDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object TopicRepositoryModule {

  @Provides
  @Singleton
  fun provideTopicSnapshotSink(reader: TopicCachePayloadReader): TopicSnapshotSink =
    TopicSnapshotSink { snapshot -> reader.save(snapshot) }

  @Provides
  @ComputeDispatcher
  fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default

  @Provides
  @IoDispatcher
  fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
