package com.chasel.ng2n.data.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 1s 节流批刷的语义 —— `src/store/history.ts` 的 `recordReadFloor` / `flushReadFloor`
 * 那套状态机(RN 版没有单测,这几条是照着实现逐分支写的,票 14 验收项①)。
 */
class ReadFloorThrottleTest {

  private val throttle = ReadFloorThrottle()

  @Test
  fun `第一次上报立刻落盘 距上次落盘已经超过一个间隔`() {
    val result = throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    assertEquals(PendingFloor(7, 3), result.flush)
    assertNull(result.scheduleInMs)
  }

  @Test
  fun `一秒之内的第二次上报不落盘 只挂尾巴定时器`() {
    throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    val result = throttle.record(tid = 7, lou = 9, nowMs = 10_400)
    assertNull(result.flush)
    assertEquals(600, result.scheduleInMs)
  }

  @Test
  fun `尾巴只挂一次 后续上报不重复排定时器`() {
    throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    throttle.record(tid = 7, lou = 9, nowMs = 10_400)
    val third = throttle.record(tid = 7, lou = 12, nowMs = 10_600)
    assertNull(third.flush)
    assertNull(third.scheduleInMs)
  }

  @Test
  fun `楼层没前进是纯 no-op`() {
    throttle.record(tid = 7, lou = 9, nowMs = 10_000)
    val back = throttle.record(tid = 7, lou = 4, nowMs = 10_200)
    assertNull(back.flush)
    assertNull(back.scheduleInMs)
    val same = throttle.record(tid = 7, lou = 9, nowMs = 10_300)
    assertNull(same.flush)
    assertNull(same.scheduleInMs)
  }

  @Test
  fun `尾巴到点后 flush 交出攒着的那条`() {
    throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    throttle.record(tid = 7, lou = 9, nowMs = 10_400)
    assertEquals(PendingFloor(7, 9), throttle.flush(11_000))
    // 已经落过了,再 flush 是 no-op
    assertNull(throttle.flush(11_100))
  }

  @Test
  fun `换主题先把上一条落定 新的这条挂尾巴`() {
    throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    throttle.record(tid = 7, lou = 9, nowMs = 10_200)   // 攒着没落
    val switched = throttle.record(tid = 8, lou = 1, nowMs = 10_400)
    assertEquals(PendingFloor(7, 9), switched.flush)
    assertEquals(1000, switched.scheduleInMs)
  }

  @Test
  fun `隔了一个间隔之后的上报又能立刻落盘`() {
    throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    val later = throttle.record(tid = 7, lou = 9, nowMs = 11_500)
    assertEquals(PendingFloor(7, 9), later.flush)
    assertNull(later.scheduleInMs)
  }

  @Test
  fun `dropPending 之后同一楼层能再报一次 条目没建好时要靠它`() {
    throttle.record(tid = 7, lou = 9, nowMs = 10_000)
    throttle.dropPending()
    val again = throttle.record(tid = 7, lou = 9, nowMs = 10_100)
    // 水位线被丢掉了,所以「只前进」不会把这次拦掉
    assertNull(again.flush)
    assertEquals(900, again.scheduleInMs)
  }

  @Test
  fun `clear 把攒着的进度丢掉而不是落盘`() {
    throttle.record(tid = 7, lou = 3, nowMs = 10_000)
    throttle.record(tid = 7, lou = 9, nowMs = 10_200)
    throttle.clear()
    assertNull(throttle.flush(12_000))
  }
}
