package com.chasel.ng2n

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** instrumentation 冒烟:证明 androidTest 源集能编译、能在设备上跑。 */
@RunWith(AndroidJUnit4::class)
class SkeletonInstrumentationTest {

  @Test
  fun 被测应用的包名是_ng2n() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.chasel.ng2.n", context.packageName)
  }
}
