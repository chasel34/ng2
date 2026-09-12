package com.chasel.ng2n.data.settings

import java.time.Instant
import java.time.ZoneOffset

private val BEIJING = ZoneOffset.ofHours(8)

typealias CheckInDays = Map<String, String>

val EMPTY_CHECK_IN_DAYS: CheckInDays = emptyMap()

fun beijingDayKey(nowMs: Long): String =
  Instant.ofEpochMilli(nowMs).atOffset(BEIJING).toLocalDate().toString()

fun isCheckedInOn(days: CheckInDays, uid: String, nowMs: Long): Boolean =
  days[uid] == beijingDayKey(nowMs)

fun withCheckedIn(days: CheckInDays, uid: String, nowMs: Long): CheckInDays =
  days + (uid to beijingDayKey(nowMs))

private val DAY_KEY_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

fun sanitizeCheckInDays(days: Map<String, String>): CheckInDays =
  days.filterValues { DAY_KEY_PATTERN.matches(it) }
