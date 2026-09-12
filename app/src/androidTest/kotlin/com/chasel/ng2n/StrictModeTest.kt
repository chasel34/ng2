package com.chasel.ng2n

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **票 14 验收项②的一半**:证明 debug 变体上 StrictMode 真的装上了。
 *
 * 「冷启动 logcat 没有 StrictMode 违规」这句话,只有在 StrictMode 确实**开着**的前提下
 * 才算证据 —— policy 忘了装的话 logcat 当然也是干净的。所以这里在主线程上把
 * `ThreadPolicy` 读回来,断言掩码非零(即 `detectDiskReads/Writes/Network` 那几档生效)。
 *
 * 另一半(真跑一次冷启动、logcat 无违规)是手工步骤,命令与结果记在票 14 的 Comments。
 */
@RunWith(AndroidJUnit4::class)
class StrictModeTest {

  /** `ThreadPolicy.toString()` 是 `[StrictMode.ThreadPolicy; mask=<int>]`。 */
  private val maskPattern = Regex("mask=(-?\\d+)")

  @Test
  fun debug_变体的主线程装了_StrictMode_线程策略() {
    var thread = ""
    // ThreadPolicy 是**每线程**的,而它是在 Application#onCreate(主线程)装上的 ——
    // 必须回主线程去读,instrumentation 线程上读到的是默认策略
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      thread = StrictMode.getThreadPolicy().toString()
    }
    val mask = maskPattern.find(thread)?.groupValues?.get(1)?.toIntOrNull()
    assertNotEquals("主线程 ThreadPolicy 掩码不该是 0:$thread", 0, mask)
  }

  @Test
  fun debug_变体装了_StrictMode_虚拟机策略() {
    val vm = StrictMode.getVmPolicy().toString()
    val mask = maskPattern.find(vm)?.groupValues?.get(1)?.toIntOrNull()
    assertNotEquals("VmPolicy 掩码不该是 0:$vm", 0, mask)
  }
}
