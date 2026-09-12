package com.chasel.ng2n.ui.accounts

import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.account.FakeWebCookieVault
import com.chasel.ng2n.data.account.inMemoryAccountStore
import com.chasel.ng2n.data.account.testAccount
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  private val models = mutableListOf<AccountsViewModel>()

  @BeforeTest
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun accountsTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
    try {
      body()
    } finally {
      for (model in models) model.viewModelScope.cancel()
      models.clear()
    }
  }

  private fun viewModel(store: AccountStore, vault: FakeWebCookieVault): AccountsViewModel =
    AccountsViewModel(store, vault).also { models += it }

  private suspend fun storeWith(vararg uids: String): AccountStore {
    val store = inMemoryAccountStore()
    for (uid in uids) store.upsert(testAccount(uid))
    return store
  }

  @Test
  fun `切号后当前账号与凭证一起换`() = accountsTest {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault()
    val model = viewModel(store, vault)

    model.switchTo("1001")
    advanceUntilIdle()

    assertEquals("1001", store.accounts.first().currentUid)
    assertEquals(Credential("1001", "cid-1001"), store.current())
  }

  @Test
  fun `切号会清掉 WebView 里上一个账号的 cookie`() = accountsTest {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault(cookie = "ngaPassportUid=1002; ngaPassportCid=cid-1002")
    val model = viewModel(store, vault)

    model.switchTo("1001")
    advanceUntilIdle()

    assertEquals(1, vault.clearCount)
    assertEquals("", vault.cookie, "网页那边不能还留着上一个账号的身份")
  }

  @Test
  fun `切到已经是当前的账号什么都不做`() = accountsTest {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault()
    val model = viewModel(store, vault)

    model.switchTo("1002")
    advanceUntilIdle()

    assertEquals(0, vault.clearCount)
  }

  @Test
  fun `切到不存在的 uid 是空操作 —— 防御过期的 UI 事件`() = accountsTest {
    val store = storeWith("1001", "1002")
    val model = viewModel(store, FakeWebCookieVault())

    model.switchTo("9999")
    advanceUntilIdle()

    assertEquals("1002", store.accounts.first().currentUid)
  }

  @Test
  fun `左右滑循环切号 到头绕回`() = accountsTest {
    val store = storeWith("1001", "1002", "1003")
    val model = viewModel(store, FakeWebCookieVault())

    model.cycle(1)
    advanceUntilIdle()
    assertEquals("1001", store.accounts.first().currentUid, "到头绕回第一个")

    model.cycle(-1)
    advanceUntilIdle()
    assertEquals("1003", store.accounts.first().currentUid)

    model.cycle(-1)
    advanceUntilIdle()
    assertEquals("1002", store.accounts.first().currentUid)
  }

  @Test
  fun `只有一个账号时左右滑是空操作`() = accountsTest {
    val store = storeWith("1001")
    val vault = FakeWebCookieVault()
    val model = viewModel(store, vault)

    model.cycle(1)
    model.cycle(-1)
    advanceUntilIdle()

    assertEquals("1001", store.accounts.first().currentUid)
    assertEquals(0, vault.clearCount)
  }

  @Test
  fun `登出当前账号后落到剩余第一个 并清 WebView cookie`() = accountsTest {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault(cookie = "ngaPassportUid=1002; ngaPassportCid=cid-1002")
    val model = viewModel(store, vault)

    model.logout("1002")
    advanceUntilIdle()

    assertEquals(listOf("1001"), store.accounts.first().accounts.map { it.uid })
    assertEquals("1001", store.accounts.first().currentUid)
    assertEquals(1, vault.clearCount)
    assertEquals("", vault.cookie)
  }

  @Test
  fun `全退光即游客态 凭证也没了`() = accountsTest {
    val store = storeWith("1001")
    val vault = FakeWebCookieVault(cookie = "ngaPassportUid=1001; ngaPassportCid=cid-1001")
    val model = viewModel(store, vault)

    model.logout("1001")
    advanceUntilIdle()

    assertEquals(emptyList(), store.accounts.first().accounts)
    assertNull(store.accounts.first().currentUid)
    assertNull(store.current(), "游客态:不发任何认证信息")
    assertEquals("", vault.cookie)
  }

  @Test
  fun `提示文案与 RN 版一致`() = accountsTest {
    val store = storeWith("1001", "1002")
    val model = viewModel(store, FakeWebCookieVault())
    val messages = mutableListOf<String>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      model.toasts.toList(messages)
    }

    model.switchTo("1001")
    advanceUntilIdle()
    model.logout("1001")
    advanceUntilIdle()

    assertEquals(listOf("已切换到 用户1001", "已退出 用户1001"), messages)
  }
}
