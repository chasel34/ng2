package com.chasel.ng2n.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun startup() = rule.collect(packageName = TARGET_PACKAGE) {
    pressHome()
    startActivityAndWait()
    val started = System.currentTimeMillis()

    device.wait(Until.hasObject(By.res("ng2n-skeleton-ready")), 15_000)
    device.waitForIdle()

    val board = device.findObject(By.clickable(true).hasChild(By.textContains("杂谈")))
      ?: device.findObject(By.textContains("杂谈"))
    board?.click()
    device.wait(Until.hasObject(By.textContains("版头")), 10_000)
    device.waitForIdle()

    repeat(2) {
      device.swipe(
        device.displayWidth / 2,
        device.displayHeight * 3 / 4,
        device.displayWidth / 2,
        device.displayHeight / 4,
        8,
      )
      device.waitForIdle()
    }

    if (!device.hasObject(By.textContains("楼]"))) {
      val rows = device.findObjects(By.clickable(true))
      rows.getOrNull(rows.size / 2)?.click()
    }
    device.wait(Until.hasObject(By.textContains("楼]")), 10_000)
    device.waitForIdle()
    device.swipe(
      device.displayWidth / 2,
      device.displayHeight * 3 / 4,
      device.displayWidth / 2,
      device.displayHeight / 4,
      8,
    )
    device.waitForIdle()

    // 留足 ART 首次落盘时间，避免采到仅含启动路径的 profile。
    val remaining = MIN_DWELL_MS - (System.currentTimeMillis() - started)
    if (remaining > 0) Thread.sleep(remaining)
  }

  private companion object {
    const val MIN_DWELL_MS = 12_000L
  }
}
