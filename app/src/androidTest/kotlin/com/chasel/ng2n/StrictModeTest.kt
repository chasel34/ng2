package com.chasel.ng2n

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrictModeTest {

  private val maskPattern = Regex("mask=(-?\\d+)")

  @Test
  fun debug_变体的主线程装了_StrictMode_线程策略() {
    var thread = ""
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
