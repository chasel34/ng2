package com.chasel.ng2n.data.history

data class PendingFloor(val tid: Long, val lou: Int)

data class FloorRecord(
  val flush: PendingFloor? = null,
  val scheduleInMs: Long? = null,
)

class ReadFloorThrottle(private val intervalMs: Long = FLOOR_FLUSH_INTERVAL_MS) {

  private var pending: PendingFloor? = null
  private var dirty = false
  private var lastFlushAt = 0L
  private var timerArmed = false

  fun record(tid: Long, lou: Int, nowMs: Long): FloorRecord {
    var flushed: PendingFloor? = null

    val current = pending
    if (current != null && current.tid != tid) flushed = flush(nowMs)

    val after = pending
    if (after != null && after.tid == tid && lou <= after.lou) {
      return FloorRecord(flush = flushed)
    }

    pending = PendingFloor(tid, lou)
    dirty = true

    val elapsed = nowMs - lastFlushAt
    if (elapsed >= intervalMs) {
      return FloorRecord(flush = flush(nowMs) ?: flushed)
    }
    if (timerArmed) return FloorRecord(flush = flushed)
    timerArmed = true
    return FloorRecord(flush = flushed, scheduleInMs = intervalMs - elapsed)
  }

  fun flush(nowMs: Long): PendingFloor? {
    timerArmed = false
    val current = pending
    if (current == null || !dirty) return null
    lastFlushAt = nowMs
    dirty = false
    return current
  }

  fun dropPending() {
    pending = null
    dirty = false
    timerArmed = false
  }

  fun clear() = dropPending()

  fun isTimerArmed(): Boolean = timerArmed

  companion object {
    const val FLOOR_FLUSH_INTERVAL_MS = 1000L
  }
}
