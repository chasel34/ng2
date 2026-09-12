package com.chasel.ng2n.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PullGateTest {

  private val slop = 24f

  @Test
  fun `开场向下越过 slop - 放行`() {
    assertEquals(PullGate.ALLOW, pullGateDecision(dy = 25f, slop = slop))
  }

  @Test
  fun `开场向上越过 slop - 整把封禁`() {
    assertEquals(PullGate.BLOCK, pullGateDecision(dy = -25f, slop = slop))
  }

  @Test
  fun `slop 之内 - 未定`() {
    assertEquals(PullGate.UNDECIDED, pullGateDecision(dy = 0f, slop = slop))
    assertEquals(PullGate.UNDECIDED, pullGateDecision(dy = 24f, slop = slop))
    assertEquals(PullGate.UNDECIDED, pullGateDecision(dy = -24f, slop = slop))
  }

  @Test
  fun `真机日志里的回勾笔画 - 开场 dy 为负,封禁先于回勾发生`() {
    assertEquals(PullGate.BLOCK, pullGateDecision(dy = -22f - slop, slop = slop))
  }
}
