package com.chasel.ng2n.data.history

import com.chasel.ng2n.data.db.BrowseHistoryDao
import com.chasel.ng2n.data.db.BrowseHistoryEntity
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览历史与阅读进度的仓库。
 *
 * 内存里的 List 是**唯一事实来源**,Room 只是它的落盘影子 —— 读永远走 [entries],不查库
 * (RN 版 `store/history.ts` 的同款结构)。规则(LRU / 去重 / 只前进)在
 * [HistoryPolicy][upsertHistory],节流状态机在 [ReadFloorThrottle],这里只做接线。
 *
 * ## 修 P2-04:冷启动零同步磁盘 IO
 *
 * [warmUp] 是**显式的 bootstrap**,由调用方在后台协程里发起;
 * 首屏不等待 —— [entries] 一上来就是空 List,库读完了再补上。
 * RN 版是在模块初始化时同步 `openDatabaseSync` + 全表扫描(审计 P2-04 的证据行)。
 */
@Singleton
class HistoryRepository @Inject constructor(
  private val dao: BrowseHistoryDao,
  /** 全 app 一个的 IO scope(见 `di/DataModule.kt`)。 */
  @IoScope private val scope: CoroutineScope,
) {

  private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())

  /** 历史页订阅这个。已按最近浏览排好序。 */
  val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

  private val throttle = ReadFloorThrottle()
  private val mutex = Mutex()
  private var flushJob: Job? = null
  private var warmed = false

  /**
   * 把库里的行灌进内存。**只在后台协程里调**,首屏不等它。
   * 重复调用是 no-op —— 冷启动路径上谁先到谁做。
   */
  suspend fun warmUp() {
    mutex.withLock {
      if (warmed) return
      warmed = true
      state.value = dao.loadAll(HISTORY_LIMIT).map { it.toEntry() }
    }
  }

  /** 同步读一条(详情页进场时取「上次读到」)。读的是内存,不查库。 */
  fun peek(tid: Long): HistoryEntry? = state.value.firstOrNull { it.tid == tid }

  /** 进入主题(或翻页拿到新一页)时登记:去重、挪到最前、刷新元数据与时间。 */
  suspend fun recordVisit(visit: TopicVisit, nowSeconds: Long) {
    mutex.withLock { apply(upsertHistory(state.value, visit, nowSeconds)) }
  }

  /**
   * 滚动时上报看到的楼层号。**1s 节流批刷**:楼层没前进是纯 no-op,
   * 前进了也先记在内存里,距上次落盘不足 1s 就挂个尾巴协程等会儿再写。
   *
   * 这个函数本身不 suspend、不碰磁盘 —— 它就落在滚动的那一帧上。
   */
  fun recordReadFloor(tid: Long, lou: Int, nowMs: Long = System.currentTimeMillis()) {
    val decision = synchronized(throttle) { throttle.record(tid, lou, nowMs) }
    decision.flush?.let { pending -> scope.launch { persistFloor(pending) } }
    decision.scheduleInMs?.let { delayMs ->
      flushJob?.cancel()
      flushJob = scope.launch {
        delay(delayMs)
        flushReadFloor()
      }
    }
  }

  /**
   * 把攒着的阅读进度写下去。**退出详情页、退到后台时必须调一次** ——
   * 不然最后 1 秒读到的楼层会跟着页面一起丢。没有待落盘的东西时是纯 no-op。
   */
  suspend fun flushReadFloor(nowMs: Long = System.currentTimeMillis()) {
    flushJob?.cancel()
    flushJob = null
    val pending = synchronized(throttle) { throttle.flush(nowMs) } ?: return
    persistFloor(pending)
  }

  private suspend fun persistFloor(pending: PendingFloor) {
    mutex.withLock {
      val entries = state.value
      // 条目得先由 recordVisit 建好,不然 policy 会原样丢弃这次上报。
      // 那种情况下水位线也要一起丢,否则同一楼层再报进来会被「只前进」拦掉
      if (entries.none { it.tid == pending.tid }) {
        synchronized(throttle) { throttle.dropPending() }
        return
      }
      apply(advanceHistoryFloor(entries, pending.tid, pending.lou, nowSeconds()))
    }
  }

  /** 清空浏览历史(历史页右上角 delete_sweep)。 */
  suspend fun clear() {
    flushJob?.cancel()
    flushJob = null
    // 攒着的进度要丢掉而不是落盘:清完再写回去等于把删掉的条目又变出来
    synchronized(throttle) { throttle.clear() }
    mutex.withLock {
      state.value = emptyList()
      dao.clear()
    }
  }

  private suspend fun apply(update: HistoryUpdate) {
    if (!update.changed) return
    state.value = update.entries
    // 每次变更只动一条,而且 upsert 和进度前进都会把它挪到最前 —— 只写第一行,不用全表重写
    val changed = update.entries.firstOrNull() ?: return
    dao.applyChange(changed.toEntity(), update.evictedTids)
  }

  /** 库里的行(不经内存)直接订阅 —— 给不想等 [warmUp] 的场合。 */
  fun observeFromDb(): Flow<List<BrowseHistoryEntity>> = dao.observe(HISTORY_LIMIT)

  private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}

internal fun BrowseHistoryEntity.toEntry(): HistoryEntry = HistoryEntry(
  tid = tid,
  subject = subject,
  author = author,
  boardName = boardName,
  favCode = favCode,
  lastFloor = lastFloor,
  maxFloor = maxFloor,
  updatedAt = updatedAt,
)

internal fun HistoryEntry.toEntity(): BrowseHistoryEntity = BrowseHistoryEntity(
  tid = tid,
  subject = subject,
  author = author,
  boardName = boardName,
  favCode = favCode,
  lastFloor = lastFloor,
  maxFloor = maxFloor,
  updatedAt = updatedAt,
)
