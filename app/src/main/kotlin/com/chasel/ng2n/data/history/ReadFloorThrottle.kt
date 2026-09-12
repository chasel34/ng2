package com.chasel.ng2n.data.history

/**
 * 阅读进度落盘的 1s 节流 —— `src/store/history.ts` 的 `recordReadFloor` /
 * `flushReadFloor` 那套状态机的直译,抽成**纯 Kotlin**好单测(票 14 验收项①)。
 *
 * 为什么要节流:慢拖一秒能报十几次楼层,而写库落在滚动的那一帧上
 * (RN 版 M4 性能走查:详情页慢拖 54% janky frames)。语义没变 ——
 * 仍然只前进、仍然一定落盘,只是不在手指还按着的时候写。
 *
 * 这个类**不碰时间也不碰定时器**:`now` 由调用方注入,「该挂尾巴定时器了」以
 * [FloorRecord.scheduleInMs] 的形式回给调用方。真正的协程定时器在 `HistoryRepository`。
 */

/** 攒着待落盘的一条进度。 */
data class PendingFloor(val tid: Long, val lou: Int)

/**
 * 一次上报之后要做的事。
 *
 * @param flush 需要**立刻**落盘的那一条(可能是上一个主题攒着的那条);null = 不用落
 * @param scheduleInMs 需要挂尾巴定时器的延迟(ms);null = 不用挂
 *
 * 两者可以同时非空:换主题时先把上一条落定(把水位线推到现在),
 * 新的这条距上次落盘不足 1s,于是挂个尾巴。
 */
data class FloorRecord(
  val flush: PendingFloor? = null,
  val scheduleInMs: Long? = null,
)

class ReadFloorThrottle(private val intervalMs: Long = FLOOR_FLUSH_INTERVAL_MS) {

  /**
   * 还没落盘的阅读进度。`dirty` 为 false 表示已经落过盘了,
   * 水位线还留着是为了拦住重复上报。
   */
  private var pending: PendingFloor? = null
  private var dirty = false
  private var lastFlushAt = 0L
  private var timerArmed = false

  /**
   * 滚动时上报看到的楼层号。楼层没前进时是纯 no-op;前进了也只记在内存里,
   * 距上次落盘不足 [intervalMs] 就让调用方挂个尾巴定时器等会儿再写。
   */
  fun record(tid: Long, lou: Int, nowMs: Long): FloorRecord {
    var flushed: PendingFloor? = null

    // 换主题(自动加载下一页不换,但从缓存/深链跳到别的帖会)先把上一条落定
    val current = pending
    if (current != null && current.tid != tid) flushed = flush(nowMs)

    // 只前进:回头翻前几楼不该反复触发落盘(HistoryPolicy 也会再拦一次)
    val after = pending
    if (after != null && after.tid == tid && lou <= after.lou) {
      return FloorRecord(flush = flushed)
    }

    pending = PendingFloor(tid, lou)
    dirty = true

    val elapsed = nowMs - lastFlushAt
    if (elapsed >= intervalMs) {
      // 上一条已经落过了才可能走到这:此时 flushed 必为 null,不会丢掉一次落盘
      return FloorRecord(flush = flush(nowMs) ?: flushed)
    }
    // 手指停在半路就不动了也得落盘,所以尾巴这一发不能省
    if (timerArmed) return FloorRecord(flush = flushed)
    timerArmed = true
    return FloorRecord(flush = flushed, scheduleInMs = intervalMs - elapsed)
  }

  /**
   * 把内存里攒着的阅读进度交出来。退出详情页、退到后台时必须调一次 ——
   * 不然最后 1 秒读到的楼层会跟着页面一起丢。没有待落盘的东西时返回 null。
   */
  fun flush(nowMs: Long): PendingFloor? {
    timerArmed = false
    val current = pending
    if (current == null || !dirty) return null
    lastFlushAt = nowMs
    dirty = false
    return current
  }

  /**
   * 条目还没由 `upsertHistory` 建好,这次上报被 core 层丢弃了 ——
   * 水位线也要一起丢,否则同一楼层再报进来会被上面的「只前进」拦掉。
   */
  fun dropPending() {
    pending = null
    dirty = false
    timerArmed = false
  }

  /** 清空历史时用:攒着的进度要丢掉而不是落盘,不然删掉的条目又被写回来。 */
  fun clear() = dropPending()

  /** 尾巴定时器还挂着吗(调用方据此避免重复起协程)。 */
  fun isTimerArmed(): Boolean = timerArmed

  companion object {
    /** 阅读进度落盘的最小间隔。慢拖一秒能报十几次,这一档把磁盘写压到每秒一次。 */
    const val FLOOR_FLUSH_INTERVAL_MS = 1000L
  }
}
