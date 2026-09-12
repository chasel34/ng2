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
  fun 被测应用的包名跟着构建档走() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // androidTest 默认打在 debug 档上(com.chasel.ng2.dev);跟 BuildConfig 对拍,不写死
    assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
  }
}
