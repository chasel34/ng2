package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.HOT_WINDOW_HOURS
import com.chasel.ng2n.core.local.aggregateHotTopics
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * 热帖的取数侧:并发拉版块前若干页主题列表(功能文档 §2.2「热门话题」——
 * **不是服务端 API**,服务端只有普通的 thread.php 分页)。
 * 直译 `src/core/api/hot-topics.ts`。
 *
 * 失败页容错:拉 5 页坏 1 页,榜单照出——被封时本来就是这种半死不活的状态,
 * 部分页失败只意味着榜单不完整,不该把整个功能变成一张错误页。
 * 全部页都失败才抛错(抛第一页的错,它最能代表「这个版块拉不动」)。
 *
 * 聚合排序/过滤是纯本地逻辑,在 `core/local/HotTopics.kt`(票 10)。
 */

/** 默认并发页数。功能文档 §2.2:客户端并发拉 5~10 页,取下界少打几枪(ADR-0002)。 */
const val DEFAULT_HOT_PAGES = 5

data class HotTopicPages(
  /** 成功拉回来的页,按页码升序(聚合对页序不敏感,但稳定输出便于测试与缓存比对) */
  val pages: List<TopicList>,
  /** 失败的页码(从 1 起),UI 用它提示「榜单不完整」 */
  val failedPages: List<Int>,
  /** 一共试了几页 */
  val pagesTried: Int,
)

/**
 * 并发拉前 [pages] 页。按最后回复排序(默认序):24 小时内有动静的主题
 * 都聚在最前几页,这正是热帖窗口要扫的那片。
 */
suspend fun fetchHotTopicPages(
  client: NgaClient,
  /** 版块 id:合集传 stid、普通版块传 fid(CONTEXT.md「合集」) */
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
            // 协程取消不是「这一页拉失败」,原样抛回去
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

/** 一次热帖聚合的结果:榜单 + 取数时的残缺情况。 */
data class HotTopics(
  val topics: List<Topic>,
  val failedPages: List<Int>,
  val pagesTried: Int,
)

/**
 * 拉页 + 聚合一步到位(功能文档 §2.2)。
 *
 * 窗口过滤看**发帖时间**而不是最后回复(被顶起来的老坟不进榜)、跨页按 tid 去重、
 * 排序「回复数降序 → 最后回复时间降序 → tid 升序」——判据全在票 10 的
 * `aggregateHotTopics` 里,这里只负责把几页喂给它。
 *
 * @param nowSeconds 当前时刻,秒级 unix 时间戳。**由调用方传入**:纯聚合不自己看表,
 *   同一份输入永远算出同一份榜单
 */
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
