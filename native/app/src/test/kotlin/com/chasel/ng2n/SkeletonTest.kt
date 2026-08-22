package com.chasel.ng2n

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 骨架冒烟:证明 JVM 单测这条路(JUnit4 + kotlin-test + coroutines-test)是通的。
 * 后续票的真单测放在同一源集,金样本放 `src/test/resources/goldens/<domain>/`。
 */
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
