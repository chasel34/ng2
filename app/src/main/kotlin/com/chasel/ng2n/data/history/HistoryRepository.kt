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

@Singleton
class HistoryRepository @Inject constructor(
  private val dao: BrowseHistoryDao,
  @IoScope private val scope: CoroutineScope,
) {

  private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())

  val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

  private val throttle = ReadFloorThrottle()
  private val mutex = Mutex()
  private var flushJob: Job? = null
  private var warmed = false

  suspend fun warmUp() {
    mutex.withLock {
      if (warmed) return
      warmed = true
      state.value = dao.loadAll(HISTORY_LIMIT).map { it.toEntry() }
    }
  }

  fun peek(tid: Long): HistoryEntry? = state.value.firstOrNull { it.tid == tid }

  suspend fun recordVisit(visit: TopicVisit, nowSeconds: Long) {
    mutex.withLock { apply(upsertHistory(state.value, visit, nowSeconds)) }
  }

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

  suspend fun flushReadFloor(nowMs: Long = System.currentTimeMillis()) {
    flushJob?.cancel()
    flushJob = null
    val pending = synchronized(throttle) { throttle.flush(nowMs) } ?: return
    persistFloor(pending)
  }

  private suspend fun persistFloor(pending: PendingFloor) {
    mutex.withLock {
      val entries = state.value
      if (entries.none { it.tid == pending.tid }) {
        synchronized(throttle) { throttle.dropPending() }
        return
      }
      apply(advanceHistoryFloor(entries, pending.tid, pending.lou, nowSeconds()))
    }
  }

  suspend fun clear() {
    flushJob?.cancel()
    flushJob = null
    synchronized(throttle) { throttle.clear() }
    mutex.withLock {
      state.value = emptyList()
      dao.clear()
    }
  }

  private suspend fun apply(update: HistoryUpdate) {
    if (!update.changed) return
    state.value = update.entries
    val changed = update.entries.firstOrNull() ?: return
    dao.applyChange(changed.toEntity(), update.evictedTids)
  }

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
