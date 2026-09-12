package com.chasel.ng2n.ui.board

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun dateText(atSeconds: Long): String =
  DATE_FORMAT.format(Instant.ofEpochSecond(atSeconds).atZone(ZoneId.systemDefault()))

fun relativeTimeText(atSeconds: Long, nowMs: Long): String {
  val elapsed = Math.floorDiv(nowMs, 1000L) - atSeconds
  return when {
    elapsed < 60 -> "刚刚"
    elapsed < 3600 -> "${elapsed / 60} 分钟前"
    elapsed < 24 * 3600 -> "${elapsed / 3600} 小时前"
    else -> dateText(atSeconds)
  }
}
