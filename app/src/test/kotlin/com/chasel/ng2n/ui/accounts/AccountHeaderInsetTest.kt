package com.chasel.ng2n.ui.accounts

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountHeaderInsetTest {

  @Test
  fun `没有状态栏时只剩设计稿那 22`() {
    assertEquals(22.dp, accountHeaderTopPadding(0.dp))
    assertEquals(ACCOUNT_HEADER_TOP_GAP, accountHeaderTopPadding(0.dp))
  }

  @Test
  fun `状态栏高度整段加在设计稿留白之上`() {
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
