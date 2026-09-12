package com.chasel.ng2n.ui.bbcode

import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.bbcode.TableNode
import com.chasel.ng2n.core.bbcode.parseBBCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** 手工移植自 `src/ui/bbcode/table.test.ts`。 */
class TableLayoutTest {

  private fun tableOf(source: String): TableNode =
    parseBBCode(source).single() as? TableNode
      ?: throw AssertionError("这段 BBCode 解析出来不是表格:$source")

  @Test
  fun `按最宽的一行算列数`() {
    val table = tableOf(
      "[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][td]丁[/td][td]戊[/td][/tr][/table]",
    )
    assertEquals(3, tableColumnCount(table.rows))
  }

  @Test
  fun `colspan 算它自己占的列数`() {
    assertEquals(3, tableColumnCount(tableOf("[table][tr][td colspan=3]通栏[/td][/tr][/table]").rows))
  }

  @Test
  fun `一列就是一个固定列宽 colspan 拉通成连续几列`() {
    assertEquals(TABLE_COLUMN_WIDTH, tableCellWidth(1))
    assertEquals(TABLE_COLUMN_WIDTH * 3, tableCellWidth(3))
    assertEquals(108.dp, TABLE_COLUMN_WIDTH)
  }

  @Test
  fun `colspan 缺失或为零时当一列 不会算出零宽把格子挤没`() {
    assertEquals(TABLE_COLUMN_WIDTH, tableCellWidth(0))
  }

  @Test
  fun `补齐短行 免得最后一格右边缺一条竖线`() {
    val table = tableOf("[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][/tr][/table]")
    assertEquals(0, tablePaddingCells(table.rows[0], 2))
    assertEquals(1, tablePaddingCells(table.rows[1], 2))
  }
}
