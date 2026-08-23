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

/**
 * 一页详情的请求参数。**每一项都进缓存 key**:
 *
 * - `fav`:带 fav 与不带 fav 请求的是**不同的东西**(隐藏/过期主题只有带码才拿得到),
 *   不区分的话两者会互相命中缓存,从收藏进来的隐藏帖会命中一份空数据;
 * - `pid` / `authorId`:只看某一楼、只看某人的第 N 页与全楼的第 N 页完全是两份数据。
 */
data class TopicPageParams(
  val tid: Long,
  /** 从 1 起 */
  val page: Int,
  val favCode: String? = null,
  /** 只看某一楼(API 文档 §3) */
  val pid: Long? = null,
  /** 只看某人:服务端按 uid 过滤楼层,分页随之重排 */
  val authorId: Long? = null,
) {
  /**
   * 过滤视图(只看该楼 / 只看此人)。楼号与分页都是过滤后的口径:
   * **不进整帖缓存、不进回复链索引、不写阅读进度**。
   */
  val filtered: Boolean get() = pid != null || authorId != null
}

/** 「缓存整帖」跑完之后的结果(RN 侧 `CacheDownloadOutcome`)。 */
sealed interface CacheDownloadOutcome {
  data class Done(val cached: Int) : CacheDownloadOutcome

  /** 用户按了停止;[cached] 是已经存下的页数 */
  data class Cancelled(val cached: Int) : CacheDownloadOutcome

  /** 某一页拉失败就停手(接着打大概率是被封了),已存的页保留 */
  data class Failed(val cached: Int, val message: String) : CacheDownloadOutcome

  /** 上一趟还没跑完 */
  data object Busy : CacheDownloadOutcome
}

/** 整帖缓存的进度条要的东西;空闲时 [tid] 为 null。 */
data class CacheDownloadState(
  val tid: Long? = null,
  val done: Int = 0,
  val total: Int = 0,
)

/**
 * 主题详情的仓库。
 *
 * ## 职责
 *
 * 1. 拉一页(反封锁链在 [NgaClient] 里,这一层只管参数与缓存);
 * 2. **原始 [TopicDetail] 的进程级缓存**(RN 侧 TanStack Query 的对应物,TTL 2 分钟);
 * 3. 后台把一页转成 [PageRenderModel](anzong 四原则第一条,见 [TopicPageBuilder]);
 * 4. 「缓存整帖」的限速下载器(进度 + 停止)。
 *
 * ## 为什么原始 detail 也要缓存 2 分钟
 *
 * 全局默认是「每次进屏都重打」,对 `read.php` 这条路太激进:退回列表再点进同一个帖、
 * 从回复链页返回、深链来回跳 —— 都是几秒内的事,内容不可能变,却各打一发 NGA
 * (ADR-0002:被封是常态,少打一发就少一分风险)。2 分钟这一档照抄 RN 版
 * `TOPIC_DETAIL_STALE_MS` 的取值与理由。**显式刷新([refresh] = true)无视它**。
 *
 * 缓存的是 `TopicDetail` 而不是 [PageRenderModel]:后者按配色/字号烤过,
 * 换夜间模式就整份作废,而且几百页全留着会把内存吃光。页级渲染成品归 ViewModel
 * 持有(票面:「离开屏幕才释放」)。
 */
@Singleton
class TopicRepository @Inject constructor(
  private val client: NgaClient,
  private val cachePayloads: TopicSnapshotSink,
  @IoScope private val scope: CoroutineScope,
  /**
   * 建模跑在哪。生产是 [Dispatchers.Default](CPU 密集:解析 + 组装 AnnotatedString),
   * 单测换成测试调度器 —— 不换的话 `advanceUntilIdle()` 管不到真线程池里的活。
   */
  @ComputeDispatcher private val compute: CoroutineDispatcher,
  /**
   * 请求跑在哪。生产是 [Dispatchers.IO];**不能跟着调用方走**——[TopicViewModel] /
   * [ChainViewModel] 一律 `viewModelScope.launch`(= `Main.immediate`),不切上下文的话
   * `fetchTopicDetail` 的前半段(组装请求、取 UA、清洗响应)就落在主线程上,详情页每翻
   * 一页卡一次首帧(票 37;票 35 的仓库层是同一条口径)。单测换成测试调度器 ——
   * 不换的话 `advanceUntilIdle()` 管不到真线程池里的活。
   */
  @IoDispatcher private val io: CoroutineDispatcher,
) {

  private val details = LinkedHashMap<TopicPageParams, CachedDetail>()
  private val detailsLock = Mutex()

  private val downloadState = MutableStateFlow(CacheDownloadState())

  /** 整帖缓存的进度(单例,所以退出详情页再进来还看得见)。 */
  val cacheDownload: StateFlow<CacheDownloadState> = downloadState.asStateFlow()

  private val downloadLock = Mutex()
  private var downloadCancelled = false

  // -------------------------------------------------------------------------
  // 拉页 + 建模
  // -------------------------------------------------------------------------

  /**
   * 拉一页并转成渲染成品。
   *
   * 建模整个在 `Dispatchers.Default` 上:`parseBBCode` + 骰子复算 + 投票解析 +
   * `AnnotatedString` 组装都在这里跑完,调用方拿到的是**可以直接贴的成品**。
   */
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

  /** 缓存里现成的那一页原始数据;没有(或已过期)返回 null,**不发请求**。 */
  suspend fun cachedDetail(
    params: TopicPageParams,
    nowMs: Long = System.currentTimeMillis(),
  ): TopicDetail? = detailsLock.withLock {
    details[params]?.takeIf { nowMs - it.atMs < TOPIC_DETAIL_STALE_MS }?.detail
  }

  /** 拉一页原始数据(缓存命中就不发请求)。 */
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
        // 浏览过的整帖页顺手写进 Room(过滤视图 `topicCacheKeyOf` 会挡掉)。
        // 延后一拍:页面转场只有 220ms,序列化整页 + 写库不必跟首帧抢 CPU
        // (RN 侧 `deferCachedPage` 的同一条理由,那边是为了不跟 Fabric 提交抢帧)
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

  /**
   * 这个主题已经进了缓存的**完整页**(回复链的「本帖已加载楼层」口径)。
   *
   * 只认 pid/authorId 都空的整页 —— 只看该楼/只看某人是过滤视图,楼号与分页
   * 都是过滤后的口径,混进 quote 索引会指错楼。fav 码要对上:带码与不带码
   * 拿到的可能根本不是同一份数据。
   */
  suspend fun loadedPages(tid: Long, favCode: String?): List<TopicDetail> =
    detailsLock.withLock {
      details.entries
        .filter { (key, _) ->
          key.tid == tid && key.favCode == favCode && key.pid == null && key.authorId == null
        }
        .map { it.value.detail }
        .sortedBy { it.page }
    }

  /** 把一页原始数据塞进缓存(回复链懒加载完顺手让详情页也能命中)。 */
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

  /**
   * 「重试原生」:先忘掉 `read.php` 上次试通的组合 —— 不清的话下一次还是从那个
   * 已经不灵的组合开局,等于白点一下。
   */
  fun forgetReadPhpCombo() = client.forgetSuccessfulCombo("read.php")

  // -------------------------------------------------------------------------
  // 缓存整帖(限速 + 可中断)
  // -------------------------------------------------------------------------

  /** 中断正在跑的整帖缓存。 */
  fun cancelCacheDownload() {
    downloadCancelled = true
  }

  /**
   * 顺序缓存指定的几页。同一时刻只允许一趟(第二次调用直接回 [CacheDownloadOutcome.Busy])
   * —— 两趟并行就是两倍的 `read.php` 频次。
   *
   * 两件事必须做到(ADR-0002):**限速**,整帖就是几十上百次 `read.php`,
   * 连着打是最容易被封的行为;**可中断**,用户随时能停,停了已经缓存的页照样留着。
   */
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
        // 第一页立刻发:「缓存本页」就该是按下去马上有反应
        if (index > 0) {
          delay(intervalMs)
          if (downloadCancelled) return CacheDownloadOutcome.Cancelled(cached)
        }

        try {
          var snapshot: TopicPageSnapshot? = null
          // 请求下 IO,**限速与进度留在调用方上下文**(票 37):`delay` 的节拍与
          // `downloadState` 的更新次序不该跟着换线程
          withContext(io) {
            fetchTopicDetail(
              client = client,
              tid = tid,
              page = page,
              favCode = favCode,
              // 后台批量下载**就地写盘**(不像前台那样延后一拍):这一趟本来就是慢活,
              // 而且顺序确定 —— 丢给别的 scope 的话「跑完了没」与「存完了没」会脱节
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
    /** 一页详情在缓存里算「新鲜」多久(RN 侧 `TOPIC_DETAIL_STALE_MS`)。 */
    const val TOPIC_DETAIL_STALE_MS = 2 * 60_000L

    /** 两页之间的间隔。整帖缓存是本 app 唯一会连续打 `read.php` 的地方,宁可慢。 */
    const val PAGE_INTERVAL_MS = 800L

    /**
     * 前台自动缓存的延后量(RN 侧 `FOREGROUND_CACHE_DELAY_MS`)。
     * 转场只有 220ms,序列化整页不必跟首帧抢 CPU。
     */
    const val FOREGROUND_CACHE_DELAY_MS = 320L

    /**
     * 原始 detail 缓存的容量。一页十几万字符,几百页全留着会把内存吃光;
     * 而它服务的场景(来回进出、回复链取已加载页)只需要最近这些。
     */
    const val DETAIL_CACHE_CAPACITY = 40
  }
}

/**
 * 「把这一页存下来」的去处。
 *
 * 抠成接口只为一件事:**单测能塞个假的**。生产实现就是票 14 的两向接缝
 * [TopicCachePayloadReader](它已经有同名同签名的 `save`),由下面的模块绑上去 ——
 * 票 07 / 14 的文件一个字没动。
 */
fun interface TopicSnapshotSink {
  suspend fun save(snapshot: TopicPageSnapshot)
}

/** 建模用的 CPU 调度器。抠成 qualifier 只为单测能换掉它。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComputeDispatcher

/** 发请求用的 IO 调度器。同上,抠成 qualifier 只为单测能换掉它(票 37)。 */
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
