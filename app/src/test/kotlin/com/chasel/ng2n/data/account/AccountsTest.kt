package com.chasel.ng2n.data.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountsTest {

  private val two = AccountsState(
    accounts = listOf(testAccount("1001"), testAccount("1002")),
    currentUid = "1001",
  )

  @Test
  fun `新账号追加到末尾并立即成为当前账号`() {
    val state = addAccount(EMPTY_ACCOUNTS, testAccount("1001"))
    assertEquals(listOf("1001"), state.accounts.map { it.uid })
    assertEquals("1001", state.currentUid)

    val next = addAccount(state, testAccount("1002"))
    assertEquals(listOf("1001", "1002"), next.accounts.map { it.uid })
    assertEquals("1002", next.currentUid)
  }

  @Test
  fun `同 uid 重登就地刷新 cid 与 loginAt 位置不动`() {
    val relogin = testAccount("1001", cid = "cid-new", loginAt = 1_754_100_000_000)
    val state = addAccount(two, relogin)
    assertEquals(listOf("1001", "1002"), state.accounts.map { it.uid })
    assertEquals(relogin, state.accounts[0])
    assertEquals("1001", state.currentUid)
  }

  @Test
  fun `切换只动 currentUid uid 不在表里原样返回`() {
    assertEquals("1002", switchAccount(two, "1002").currentUid)
    assertSame(two, switchAccount(two, "9999"))
  }

  @Test
  fun `退出当前账号落到剩余第一个 全退光即游客态`() {
    val one = removeAccount(two, "1001")
    assertEquals(listOf("1002"), one.accounts.map { it.uid })
    assertEquals("1002", one.currentUid)

    val none = removeAccount(one, "1002")
    assertEquals(EMPTY_ACCOUNTS, none)
    assertNull(none.currentUid)
  }

  @Test
  fun `退出非当前账号不影响当前账号`() {
    assertEquals("1001", removeAccount(two, "1002").currentUid)
  }

  @Test
  fun `currentAccountOf 取当前账号 游客态是 null`() {
    assertEquals("1001", currentAccountOf(two)?.uid)
    assertNull(currentAccountOf(EMPTY_ACCOUNTS))
  }

  private val three = AccountsState(
    accounts = listOf(testAccount("1001"), testAccount("1002"), testAccount("1003")),
    currentUid = "1002",
  )

  @Test
  fun `前后循环取 到头绕回`() {
    assertEquals("1003", cycleAccountUid(three, 1))
    assertEquals("1001", cycleAccountUid(three, -1))
    assertEquals("1001", cycleAccountUid(three.copy(currentUid = "1003"), 1))
    assertEquals("1003", cycleAccountUid(three.copy(currentUid = "1001"), -1))
  }

  @Test
  fun `不足两个账号没得切`() {
    assertNull(cycleAccountUid(EMPTY_ACCOUNTS, 1))
    assertNull(
      cycleAccountUid(
        AccountsState(accounts = listOf(testAccount("1001")), currentUid = "1001"),
        1,
      ),
    )
  }

  private val day = 24L * 60 * 60 * 1000
  private val loginAt = 1_754_000_000_000

  @Test
  fun `刚登录 30 天 过一天半剩 29`() {
    assertEquals(30, cookieDaysLeft(loginAt, loginAt))
    assertEquals(29, cookieDaysLeft(loginAt, loginAt + day * 3 / 2))
    assertEquals(1, cookieDaysLeft(loginAt, loginAt + day * 59 / 2))
  }

  @Test
  fun `过了 30 天算已过期`() {
    assertTrue(cookieDaysLeft(loginAt, loginAt + 31 * day) <= 0)
    assertEquals("已过期", formatCookieExpiry(loginAt, loginAt + 31 * day))
    assertEquals("30 天后过期", formatCookieExpiry(loginAt, loginAt))
  }

  @Test
  fun `坏账号剔除 重复 uid 去重 currentUid 失效时落到第一个`() {
    val state = sanitizeAccounts(
      AccountsState(
        accounts = listOf(
          testAccount("1001"),
          testAccount("", cid = "no-uid"),
          testAccount("1002", cid = ""),
          testAccount("1001", cid = "dup"),
          testAccount("1003"),
        ),
        currentUid = "9999",
      ),
    )
    assertEquals(listOf("1001", "1003"), state.accounts.map { it.uid })
    assertEquals("cid-1001", state.accounts[0].cid)
    assertEquals("1001", state.currentUid)
  }
}
