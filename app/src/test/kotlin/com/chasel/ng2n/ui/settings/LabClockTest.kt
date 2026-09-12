package com.chasel.ng2n.ui.settings

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LabClockTest {

  private val at = 1_787_467_855_000L

  @Test
  fun `钟点按传入时区渲染`() {
    assertEquals("14:50:55", runLogClock(at, ZoneId.of("Asia/Shanghai")))
  }

  @Test
  fun `同一时刻在 UTC 下是票里那个早八小时的旧值`() {
    assertEquals("06:50:55", runLogClock(at, ZoneId.of("UTC")))
  }

  @Test
  fun `不给时区就用设备时区`() {
    assertEquals(runLogClock(at, ZoneId.systemDefault()), runLogClock(at))
  }

  @Test
  fun `西半球时区同样跟着设备走`() {
    assertEquals("02:50:55", runLogClock(at, ZoneId.of("America/New_York")))
  }

  @Test
  fun `钟点固定两位补零`() {
    assertEquals("08:05:09", runLogClock(1_787_443_509_000L, ZoneId.of("Asia/Shanghai")))
  }
}
