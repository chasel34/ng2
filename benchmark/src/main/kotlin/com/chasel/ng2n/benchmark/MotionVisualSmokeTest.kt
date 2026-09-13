package com.chasel.ng2n.benchmark

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test

class MotionVisualSmokeTest {
  @Test
  fun pageAndMenuOnInstalledRelease() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val launch = device.executeShellCommand(
      "am start -W -n com.chasel.ng2/com.chasel.ng2n.MainActivity " +
        "-a android.intent.action.VIEW -d https://bbs.nga.cn/thread.php?fid=-7",
    )
    assertNotNull(launch, device.wait(Until.findObject(By.desc("搜索")), 15_000))
    assertNotNull(device.wait(Until.findObject(By.desc("更多")), 15_000))
    device.waitForIdle()
    SystemClock.sleep(1200)
    repeat(2) {
      device.findObject(By.desc("更多")).click()
      assertNotNull(device.wait(Until.findObject(By.text("浏览历史")), 3000))
      SystemClock.sleep(800)
      device.pressBack()
      device.wait(Until.gone(By.text("浏览历史")), 3000)
      SystemClock.sleep(800)
    }
    device.findObject(By.desc("更多")).click()
    val history = device.wait(Until.findObject(By.text("浏览历史")), 3000)
    assertNotNull(history)
    SystemClock.sleep(800)
    history.click()
    assertNotNull(device.wait(Until.findObject(By.text("浏览历史")), 3000))
    SystemClock.sleep(1000)
    device.pressBack()
    assertNotNull(device.wait(Until.findObject(By.desc("更多")), 3000))
    SystemClock.sleep(1000)
    device.findObject(By.desc("更多")).click()
    assertNotNull(device.wait(Until.findObject(By.text("书签")), 3000))
    SystemClock.sleep(800)
    device.findObject(By.text("书签")).click()
    SystemClock.sleep(1000)
    device.pressBack()
    assertNotNull(device.wait(Until.findObject(By.desc("更多")), 3000))
    SystemClock.sleep(800)
  }
}
