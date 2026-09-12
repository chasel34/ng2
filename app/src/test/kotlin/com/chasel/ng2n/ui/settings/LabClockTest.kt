package com.chasel.ng2n.ui.settings

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 票 27 的回归:「本次运行的组合」里的钟点按**设备时区**走,不再写死 UTC。
 *
 * 票里的实测就是这一条 —— 设备 `Asia/Shanghai`、`adb shell date` 是 14:56,
 * 一次 14:50:55 发出的请求被渲染成 `06:50:55`。
 */
class LabClockTest {

  /** 2026-08-23T06:50:55Z —— 票 27 复现步骤里那一条 read.php。 */
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
    // 负时差那一侧也得对:UTC 的 06:50 在纽约是前一天没错,但钟点只看 HH-mm-ss
    assertEquals("02:50:55", runLogClock(at, ZoneId.of("America/New_York")))
  }

  @Test
  fun `钟点固定两位补零`() {
    // 2026-08-23T00-05-09Z → 上海 08:05:09,分与秒都得补零
    assertEquals("08:05:09", runLogClock(1_787_443_509_000L, ZoneId.of("Asia/Shanghai")))
  }
}
