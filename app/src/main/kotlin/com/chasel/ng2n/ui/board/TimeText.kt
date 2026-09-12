package com.chasel.ng2n.ui.board

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 二级列表(设计稿 simple-list:热帖 / 精华区)信息行右侧的时间文案 ——
 * 直译 RN 侧 `src/ui/time-text.ts`。
 *
 * 热帖用相对时间(设计稿样例:「刚刚 / 12 分钟前 / 1 小时前」),
 * 精华区用日期(设计稿样例:「2026-07-20」)。`now` 一律由调用方传入 ——
 * 热帖页拿榜单算出的时刻当基准,刷新前文案不跳动。
 */
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 秒级 unix 时间戳 → `YYYY-MM-DD`(设备时区)。 */
fun dateText(atSeconds: Long): String =
  DATE_FORMAT.format(Instant.ofEpochSecond(atSeconds).atZone(ZoneId.systemDefault()))

/** 秒级 unix 时间戳 → 相对时间。超过一天(热帖窗口外)退回日期。 */
fun relativeTimeText(atSeconds: Long, nowMs: Long): String {
  val elapsed = Math.floorDiv(nowMs, 1000L) - atSeconds
  return when {
    elapsed < 60 -> "刚刚"
    elapsed < 3600 -> "${elapsed / 60} 分钟前"
    elapsed < 24 * 3600 -> "${elapsed / 3600} 小时前"
    else -> dateText(atSeconds)
  }
}
