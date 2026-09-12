package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.bbcode.TableRow
import kotlin.math.max

val TABLE_COLUMN_WIDTH: Dp = 108.dp

private fun rowSpan(row: TableRow): Int = row.cells.sumOf { max(1, it.colspan) }

fun tableColumnCount(rows: List<TableRow>): Int =
  rows.fold(0) { count, row -> max(count, rowSpan(row)) }

fun tableCellWidth(colspan: Int): Dp = TABLE_COLUMN_WIDTH * max(1, colspan)

fun tablePaddingCells(row: TableRow, columnCount: Int): Int = max(0, columnCount - rowSpan(row))
