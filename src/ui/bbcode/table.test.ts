import { describe, expect, it } from 'vitest';

import { parseBBCode, type TableNode } from '@/core/bbcode';

import { TABLE_COLUMN_WIDTH, tableCellWidth, tableColumnCount, tablePaddingCells } from './table';

function tableOf(source: string): TableNode {
  const [node] = parseBBCode(source);
  if (node?.type !== 'table') throw new Error('这段 BBCode 解析出来不是表格');
  return node;
}

describe('tableColumnCount', () => {
  it('按最宽的一行算列数', () => {
    const table = tableOf(
      '[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][td]丁[/td][td]戊[/td][/tr][/table]',
    );
    expect(tableColumnCount(table.rows)).toBe(3);
  });

  it('colspan 算它自己占的列数', () => {
    const table = tableOf('[table][tr][td colspan=3]通栏[/td][/tr][/table]');
    expect(tableColumnCount(table.rows)).toBe(3);
  });
});

describe('tableCellWidth', () => {
  it('一列就是一个固定列宽，colspan 拉通成连续几列', () => {
    expect(tableCellWidth(1)).toBe(TABLE_COLUMN_WIDTH);
    expect(tableCellWidth(3)).toBe(TABLE_COLUMN_WIDTH * 3);
  });

  it('colspan 缺失或为 0 时当一列，不会算出 0 宽把格子挤没', () => {
    expect(tableCellWidth(0)).toBe(TABLE_COLUMN_WIDTH);
  });
});

describe('tablePaddingCells', () => {
  it('补齐短行，免得最后一格右边缺一条竖线', () => {
    const table = tableOf('[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][/tr][/table]');
    expect(tablePaddingCells(table.rows[0]!, 2)).toBe(0);
    expect(tablePaddingCells(table.rows[1]!, 2)).toBe(1);
  });
});
