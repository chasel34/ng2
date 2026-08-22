package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.fetchHotTopics
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个版块的 24 小时热帖(CONTEXT.md「热帖」—— **纯本地聚合,不是服务端 API**)。
 * 直译 RN 侧 `store/hot-topics.ts`。
 *
 * 取数(并发拉前 5 页、失败页容错)在票 07 的 `core/api/HotTopics.kt`,
 * 聚合(24h 窗口过滤 + 回复数排序)在票 10 的 `core/local/HotTopics.kt`;
 * 这里只负责把两段接起来,并在聚合时把「当前时间」灌进纯函数。
 *
 * 一次刷新打 5 个 `thread.php`(ADR-0002 的枪口),所以 5 分钟内切页面回来不重打。
 */
@Singleton
class HotTopicsRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class Key(val boardId: Long, val kind: BoardKind)

  data class State(
    val loading: Boolean = true,
    val topics: List<Topic> = emptyList(),
    /** 拉失败的页码,非空时 UI 提示「榜单不完整」 */
    val failedPages: List<Int> = emptyList(),
    val pagesTried: Int = 0,
    /** 榜单算出来的时刻(毫秒),相对时间以它为基准,刷新前不跳动 */
    val fetchedAt: Long = 0,
    val error: Throwable? = null,
    val refreshing: Boolean = false,
  )

  private val entries = MutableStateFlow<Map<Key, State>>(emptyMap())
  val states: StateFlow<Map<Key, State>> = entries.asStateFlow()

  fun stateOf(key: Key): State = entries.value[key] ?: State()

  private fun put(key: Key, transform: (State) -> State) {
    entries.value = entries.value + (key to transform(entries.value[key] ?: State()))
  }

  suspend fun ensureLoaded(key: Key, now: Long = System.currentTimeMillis()) {
    val current = entries.value[key]
    if (current != null && current.fetchedAt != 0L && now - current.fetchedAt < STALE_MS) return
    load(key, now, refreshing = false)
  }

  suspend fun refresh(key: Key, now: Long = System.currentTimeMillis()) =
    load(key, now, refreshing = true)

  private suspend fun load(key: Key, now: Long, refreshing: Boolean) {
    put(key) { it.copy(loading = it.fetchedAt == 0L, refreshing = refreshing, error = null) }
    try {
      val result = fetchHotTopics(
        client = client,
        boardId = key.boardId,
        kind = key.kind,
        // 聚合看的是秒级时间戳(与 thread.php 的 postdate 同口径)
        nowSeconds = Math.floorDiv(now, 1000L),
      )
      put(key) {
        State(
          loading = false,
          topics = result.topics,
          failedPages = result.failedPages,
          pagesTried = result.pagesTried,
          fetchedAt = now,
          error = null,
          refreshing = false,
        )
      }
    } catch (cancelled: CancellationException) {
      put(key) { it.copy(loading = false, refreshing = false) }
      throw cancelled
    } catch (error: Throwable) {
      put(key) { it.copy(loading = false, refreshing = false, error = error) }
    }
  }

  private companion object {
    /** RN 侧 `staleTime: 5 * 60 * 1000`。 */
    const val STALE_MS = 5L * 60 * 1000
  }
}
