package com.chasel.ng2n.data.board

import com.chasel.ng2n.data.settings.beijingDayKey
import com.chasel.ng2n.data.settings.withCheckedIn
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckInDecisionTest {

  private val noon = 1_787_371_200_000L

  @Test
  fun 没签过就发请求() {
    assertEquals(
      CheckInDecision.SEND,
      decideCheckIn(emptyMap(), uid = "1", nowMs = noon, pendingUid = null),
    )
  }

  @Test
  fun 本地记着今天签过就不发() {
    val days = withCheckedIn(emptyMap(), "1", noon)
    assertEquals(
      CheckInDecision.ALREADY_TODAY,
      decideCheckIn(days, uid = "1", nowMs = noon, pendingUid = null),
    )
  }

  @Test
  fun 昨天签的今天照发() {
    val days = withCheckedIn(emptyMap(), "1", noon - 24 * 3600_000L)
    assertEquals(
      CheckInDecision.SEND,
      decideCheckIn(days, uid = "1", nowMs = noon, pendingUid = null),
    )
  }

  @Test
  fun 签到日按账号分桶() {
    val days = withCheckedIn(emptyMap(), "1", noon)
    assertEquals(
      CheckInDecision.SEND,
      decideCheckIn(days, uid = "2", nowMs = noon, pendingUid = null),
    )
  }

  @Test
  fun 有请求在途就忽略这次点击() {
    assertEquals(
      CheckInDecision.IN_FLIGHT,
      decideCheckIn(emptyMap(), uid = "1", nowMs = noon, pendingUid = "1"),
    )
    assertEquals(
      CheckInDecision.IN_FLIGHT,
      decideCheckIn(emptyMap(), uid = "1", nowMs = noon, pendingUid = "99"),
    )
  }

  @Test
  fun 本地记账优先于在途判定() {
    val days = withCheckedIn(emptyMap(), "1", noon)
    assertEquals(
      CheckInDecision.ALREADY_TODAY,
      decideCheckIn(days, uid = "1", nowMs = noon, pendingUid = "1"),
    )
  }

  @Test
  fun 日界线按UTC加8而不是设备时区() {
    val justAfterMidnightBeijing = 1_787_329_800_000L
    assertEquals("2026-08-22", beijingDayKey(justAfterMidnightBeijing))
    val days = withCheckedIn(emptyMap(), "1", justAfterMidnightBeijing)
    assertEquals(
      CheckInDecision.ALREADY_TODAY,
      decideCheckIn(days, uid = "1", nowMs = noon, pendingUid = null),
    )
  }
}
