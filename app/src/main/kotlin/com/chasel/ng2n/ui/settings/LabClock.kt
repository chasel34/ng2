package com.chasel.ng2n.ui.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun runLogClock(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
  CLOCK.withZone(zone).format(Instant.ofEpochMilli(atMillis))
