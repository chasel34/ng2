package com.chasel.ng2n

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkeletonInstrumentationTest {

  @Test
  fun 被测应用的包名跟着构建档走() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
  }
}
