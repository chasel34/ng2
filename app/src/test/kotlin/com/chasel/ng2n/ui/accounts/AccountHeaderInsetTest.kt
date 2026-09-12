package com.chasel.ng2n.ui.accounts

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 票 38:抽屉账号头的安全区。
 *
 * RN 侧 `src/ui/app-drawer.tsx:192` 是 `paddingTop: insets.top + 22`;
 * 原生这边漏了 `insets.top` 那一段,头像与状态栏时间并排、整块头矮一个状态栏
 * (对照图上是 159dp vs 206dp,差值就是那台机器的 47dp 状态栏)。
 */
class AccountHeaderInsetTest {

  @Test
  fun `没有状态栏时只剩设计稿那 22`() {
    assertEquals(22.dp, accountHeaderTopPadding(0.dp))
    assertEquals(ACCOUNT_HEADER_TOP_GAP, accountHeaderTopPadding(0.dp))
  }

  @Test
  fun `状态栏高度整段加在设计稿留白之上`() {
    // 对照图那台机器的状态栏是 47dp:206 − 159 = 47,正是漏掉的这一段
    assertEquals(69.dp, accountHeaderTopPadding(47.dp))
    assertEquals(24.dp + 22.dp, accountHeaderTopPadding(24.dp))
  }

  @Test
  fun `状态栏越高账号头越高`() {
    val short = accountHeaderTopPadding(24.dp)
    val tall = accountHeaderTopPadding(47.dp)
    assertTrue(tall > short)
    assertEquals(23.dp, tall - short)
  }
}
