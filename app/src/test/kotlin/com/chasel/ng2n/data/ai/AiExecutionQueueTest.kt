package com.chasel.ng2n.data.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiExecutionQueueTest {
  @Test fun queuedConversationsWaitInOrderAndCancelledWaitersNeverExecute() = runTest {
    val queue = AiExecutionQueue()
    val executed = mutableListOf<String>()
    val positions = mutableListOf<Int>()
    queue.enter("one") {}
    val two = launch { queue.enter("two") { positions += it }; executed += "two" }
    val three = launch { queue.enter("three") {}; executed += "three" }
    runCurrent()
    assertTrue(executed.isEmpty())
    assertEquals(listOf(1), positions.distinct())
    two.cancelAndJoin()
    queue.leave("one")
    runCurrent()
    assertEquals(listOf("three"), executed)
    three.join()
    queue.leave("three")
  }
}
