package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.local.jsNumber
import com.chasel.ng2n.data.history.pageOfFloor

private const val WINDOW_RADIUS = 4

fun clampPage(page: Int, totalPages: Int): Int {
  val total = maxOf(1, totalPages)
  return minOf(maxOf(1, page), total)
}

fun visiblePages(page: Int, totalPages: Int): List<Int> {
  val total = maxOf(1, totalPages)
  val current = clampPage(page, total)
  val window = sortedSetOf(1, total)
  for (value in (current - WINDOW_RADIUS)..(current + WINDOW_RADIUS)) {
    if (value in 1..total) window.add(value)
  }
  return window.toList()
}

fun parseJumpTarget(input: String, totalPages: Int): Int? {
  val value = jsNumber(input.trim())
  if (!value.isFinite() || value != kotlin.math.floor(value)) return null
  val page = value.toInt()
  return if (page >= 1 && page <= maxOf(1, totalPages)) page else null
}

fun pagerPageCount(totalPages: Int, page: Int): Int = maxOf(1, totalPages, page)

const val TOPIC_LIST_HEADER_ROWS: Int = 1

fun floorScrollIndex(
  floorLous: List<Long>,
  targetFloor: Long,
  page: Int,
  rowsPerPage: Int,
  headerRows: Int = TOPIC_LIST_HEADER_ROWS,
): Int? {
  if (targetFloor < 0 || floorLous.isEmpty()) return null
  if (pageOfFloor(targetFloor.toInt(), rowsPerPage) != page) return null
  val hit = floorLous.indexOfFirst { it >= targetFloor }
  val row = if (hit >= 0) hit else floorLous.lastIndex
  return row + maxOf(0, headerRows)
}
