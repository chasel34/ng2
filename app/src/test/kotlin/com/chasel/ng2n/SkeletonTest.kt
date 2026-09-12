package com.chasel.ng2n

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonTest {

  @Test
  fun `kotlin-test 断言可用`() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun `coroutines-test 的 runTest 可用`() = runTest {
    val value = suspendingFour()
    assertEquals(4, value)
  }

  private suspend fun suspendingFour(): Int = 2 + 2
}
