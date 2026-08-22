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

/**
 * 账号管理屏 / 抽屉账号头背后的那个 ViewModel:切号、循环切号、登出。
 *
 * 票 15 验收③的另一半在这里:**登出与切号都会清 WebView 的 cookie**
 * (审计 P1-03 的整改建议原文:「至少在退出时清理 cookie」)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  /** 用例结束前把 VM 的 scope 取消掉 —— 真实生命周期里这件事由 `onCleared()` 做。 */
  private val models = mutableListOf<AccountsViewModel>()

  @BeforeTest
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterTest
  fun tearDown() {
    for (model in models) model.viewModelScope.cancel()
    models.clear()
    Dispatchers.resetMain()
  }

  private fun viewModel(store: AccountStore, vault: FakeWebCookieVault): AccountsViewModel =
    AccountsViewModel(store, vault).also { models += it }

  private suspend fun storeWith(vararg uids: String): AccountStore {
    val store = inMemoryAccountStore()
    for (uid in uids) store.upsert(testAccount(uid))
    return store
  }

  @Test
  fun `切号后当前账号与凭证一起换`() = runTest(dispatcher) {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault()
    val model = viewModel(store, vault)

    model.switchTo("1001")
    advanceUntilIdle()

    assertEquals("1001", store.accounts.first().currentUid)
    assertEquals(Credential("1001", "cid-1001"), store.current())
  }

  @Test
  fun `切号会清掉 WebView 里上一个账号的 cookie`() = runTest(dispatcher) {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault(cookie = "ngaPassportUid=1002; ngaPassportCid=cid-1002")
    val model = viewModel(store, vault)

    model.switchTo("1001")
    advanceUntilIdle()

    assertEquals(1, vault.clearCount)
    assertEquals("", vault.cookie, "网页那边不能还留着上一个账号的身份")
  }

  @Test
  fun `切到已经是当前的账号什么都不做`() = runTest(dispatcher) {
    val store = storeWith("1001", "1002")
    val vault = FakeWebCookieVault()
    val model = viewModel(store, vault)

    model.switchTo("1002") // 1002 本来就是当前账号
    advanceUntilIdle()

    assertEquals(0, vault.clearCount)
  }

  @Test
  fun `切到不存在的 uid 是空操作 —— 防御过期的 UI 事件`() = runTest(dispatcher) {
    val store = storeWith("1001", "1002")
    val model = viewModel(store, FakeWebCookieVault())

    model.switchTo("9999")
    advanceUntilIdle()

    assertEquals("1002", store.accounts.first().currentUid)
  }

  @Test
  fun `左右滑循环切号 到头绕回`() = runTest(dispatcher) {
    val store = storeWith("1001", "1002", "1003")
    val model = viewModel(store, FakeWebCookieVault())
    // 1003 是最后登进来的,也是当前账号

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
  fun `只有一个账号时左右滑是空操作`() = runTest(dispatcher) {
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
  fun `登出当前账号后落到剩余第一个 并清 WebView cookie`() = runTest(dispatcher) {
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
  fun `全退光即游客态 凭证也没了`() = runTest(dispatcher) {
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
  fun `提示文案与 RN 版一致`() = runTest(dispatcher) {
    val store = storeWith("1001", "1002")
    val model = viewModel(store, FakeWebCookieVault())
    val messages = mutableListOf<String>()
    // 收集器用 Unconfined:`toasts` 是没有 replay 的 SharedFlow,
    // 在 StandardTestDispatcher 上收会慢一拍(最后一条要等下一轮调度才到手)
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
