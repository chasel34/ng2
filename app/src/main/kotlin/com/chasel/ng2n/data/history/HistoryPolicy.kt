package com.chasel.ng2n.data.history

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val HISTORY_LIMIT = 200

data class HistoryEntry(
  val tid: Long,
  val subject: String,
  val author: String? = null,
  val boardName: String? = null,
  val favCode: String? = null,
  val lastFloor: Int = 0,
  val maxFloor: Int = 0,
  /** 最近浏览时刻，Unix 秒时间戳。 */
  val updatedAt: Long,
)

data class TopicVisit(
  val tid: Long,
  val subject: String,
  val author: String? = null,
  val boardName: String? = null,
  val favCode: String? = null,
  val maxFloor: Int? = null,
)

data class HistoryUpdate(
  val entries: List<HistoryEntry>,
  val changed: Boolean,
  val evictedTids: List<Long> = emptyList(),
)

private fun unchanged(entries: List<HistoryEntry>) = HistoryUpdate(entries, changed = false)

fun upsertHistory(
  entries: List<HistoryEntry>,
  visit: TopicVisit,
  now: Long,
): HistoryUpdate {
  val existing = entries.firstOrNull { it.tid == visit.tid }

  val next = HistoryEntry(
    tid = visit.tid,
    subject = if (visit.subject != "") visit.subject else (existing?.subject ?: ""),
    lastFloor = existing?.lastFloor ?: 0,
    maxFloor = maxOf(existing?.maxFloor ?: 0, visit.maxFloor ?: 0),
    updatedAt = now,
    author = visit.author ?: existing?.author,
    boardName = visit.boardName ?: existing?.boardName,
    favCode = visit.favCode ?: existing?.favCode,
  )

  val kept = entries.filter { it.tid != visit.tid }
  val capped = listOf(next) + kept
  val evicted = capped.drop(HISTORY_LIMIT)
  return HistoryUpdate(
    entries = capped.take(HISTORY_LIMIT),
    changed = true,
    evictedTids = evicted.map { it.tid },
  )
}

fun advanceHistoryFloor(
  entries: List<HistoryEntry>,
  tid: Long,
  lou: Int,
  now: Long,
): HistoryUpdate {
  val existing = entries.firstOrNull { it.tid == tid }
  if (existing == null || lou <= existing.lastFloor) return unchanged(entries)

  val next = existing.copy(
    lastFloor = lou,
    maxFloor = maxOf(existing.maxFloor, lou),
    updatedAt = now,
  )
  return HistoryUpdate(
    entries = listOf(next) + entries.filter { it.tid != tid },
    changed = true,
  )
}

fun isHistoryFinished(lastFloor: Int, maxFloor: Int): Boolean = lastFloor >= maxFloor

fun historyProgressLabel(lastFloor: Int, maxFloor: Int): String = when {
  isHistoryFinished(lastFloor, maxFloor) -> "读完"
  lastFloor == 0 -> "读到主楼"
  else -> "读到 $lastFloor 楼"
}

fun pageOfFloor(lou: Int, rowsPerPage: Int): Int {
  val perPage = maxOf(1, rowsPerPage)
  return maxOf(0, lou) / perPage + 1
}

private const val MINUTE = 60L
private const val HOUR = 3600L

fun formatHistoryTime(
  updatedAt: Long,
  now: Long,
  zone: ZoneId = ZoneId.systemDefault(),
): String {
  val elapsed = now - updatedAt
  if (elapsed < MINUTE) return "刚刚"
  if (elapsed < HOUR) return "${elapsed / MINUTE} 分钟前"

  val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(updatedAt), zone)
  val today = LocalDate.ofInstant(Instant.ofEpochSecond(now), zone)
  val dayDiff = ChronoUnit.DAYS.between(time.toLocalDate(), today)
  return when {
    dayDiff <= 0L -> "今天 ${clockOf(time)}"
    dayDiff == 1L -> "昨天 ${clockOf(time)}"
    dayDiff == 2L -> "前天"
    else -> dateOf(time)
  }
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

private fun clockOf(time: LocalDateTime): String = "${pad2(time.hour)}:${pad2(time.minute)}"

private fun dateOf(time: LocalDateTime): String =
  "${time.year}-${pad2(time.monthValue)}-${pad2(time.dayOfMonth)}"
