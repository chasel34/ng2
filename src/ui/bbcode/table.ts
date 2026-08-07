import type { TableRow } from '@/core/bbcode';

/**
 * `[table]` 的简化排版(ADR-0001)。
 *
 * RN 没有 `<table>`,也没有 grid 的自动列宽,所以这里放弃「按内容量列宽」:
 * **每列一个固定宽度**,`colspan` 就是占几列,`rowspan` 直接忽略。
 * 整表包在横向 ScrollView 里——390 逻辑宽的手机上,五列的表格无论如何都放不下,
 * 硬压只会把字挤成一列一个字;能横向拖反而看得清,楼层卡片也不会被撑破。
 *
 * 这里只算数,不碰组件。
 */

/** 一列的固定宽度。三列刚好铺满 390 宽手机上楼层卡片的内容区。 */
export const TABLE_COLUMN_WIDTH = 108;

/** 一行横跨几列。`colspan` 缺失或为 0 的格子按一列算。 */
const rowSpan = (row: TableRow): number =>
  row.cells.reduce((sum, cell) => sum + Math.max(1, cell.colspan), 0);

/** 表格有几列——按「哪一行横跨得最多」算。 */
export function tableColumnCount(rows: readonly TableRow[]): number {
  return rows.reduce((count, row) => Math.max(count, rowSpan(row)), 0);
}

/** 一个格子占多宽。`colspan` 拉通成连续几列的宽度。 */
export function tableCellWidth(colspan: number): number {
  return Math.max(1, colspan) * TABLE_COLUMN_WIDTH;
}

/**
 * 行末补齐用的空格子数:NGA 的表格经常最后一行少写几个 `[td]`,
 * 不补的话最后一格右边会缺一条竖线,看着像表格裂了。
 */
export function tablePaddingCells(row: TableRow, columnCount: number): number {
  return Math.max(0, columnCount - rowSpan(row));
}
