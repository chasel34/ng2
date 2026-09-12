package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.bbcode.TableRow
import kotlin.math.max

/**
 * `[table]` 的简化排版(ADR-0001;RN 侧原件 `src/ui/bbcode/table.ts`)。
 *
 * 放弃「按内容量列宽」:**每列一个固定宽度**,`colspan` 就是占几列,`rowspan` 直接忽略。
 * 整表包在横向滚动里——390 逻辑宽的手机上,五列的表格无论如何都放不下,硬压只会把字
 * 挤成一列一个字;能横向拖反而看得清,楼层卡片也不会被撑破。
 *
 * 这里只算数,不碰组件。
 */

/** 一列的固定宽度。三列刚好铺满 390 宽手机上楼层卡片的内容区。 */
val TABLE_COLUMN_WIDTH: Dp = 108.dp

/** 一行横跨几列。`colspan` 缺失或为 0 的格子按一列算。 */
private fun rowSpan(row: TableRow): Int = row.cells.sumOf { max(1, it.colspan) }

/** 表格有几列——按「哪一行横跨得最多」算。 */
fun tableColumnCount(rows: List<TableRow>): Int =
  rows.fold(0) { count, row -> max(count, rowSpan(row)) }

/** 一个格子占多宽。`colspan` 拉通成连续几列的宽度。 */
fun tableCellWidth(colspan: Int): Dp = TABLE_COLUMN_WIDTH * max(1, colspan)

/**
 * 行末补齐用的空格子数:NGA 的表格经常最后一行少写几个 `[td]`,
 * 不补的话最后一格右边会缺一条竖线,看着像表格裂了。
 */
fun tablePaddingCells(row: TableRow, columnCount: Int): Int = max(0, columnCount - rowSpan(row))
