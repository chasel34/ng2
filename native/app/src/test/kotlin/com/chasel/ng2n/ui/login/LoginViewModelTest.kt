package com.chasel.ng2n.ui.login

import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.account.FakePreferencesDataStore
import com.chasel.ng2n.data.account.FakeWebCookieVault
import com.chasel.ng2n.data.account.inMemoryAccountStore
import com.chasel.ng2n.data.account.testAccount
import com.chasel.ng2n.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * 登录收割的逻辑(票 15 验收①里不需要真人的那一半)。
 *
 * ⚠️ 用 `runCurrent()` 而不是 `advanceUntilIdle()`:轮询是个「永远还排着下一个 `delay`」的
 * 循环,`advanceUntilIdle` 会一直往前推虚拟时间、永不返回。
 *
 * 真登录要所有者在模拟器里输账号密码,不能自动化;能自动化的是**收割规则**:
 * 什么时候算登录成功、用户名怎么解、账号落哪、以及 WebView cookie 的两次清理。
 * `CookieManager` 这一侧换成 [FakeWebCookieVault],真实行为由 androidTest 的
 * `AndroidWebCookieVaultTest` 在设备上验。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  /**
   * 建出来的 VM 要在用例结束前把 `viewModelScope` 取消掉 —— 真实生命周期里
   * 这件事由 `onCleared()` 做。不取消的话轮询会一直往测试调度器里排下一个 `delay`,
   * `runTest` 收尾时的排空永远排不完(整条测试卡死,不是超时)。
   */
  private val models = mutableListOf<LoginViewModel>()

  private val uid = "67241234"
  private val cid = "Xa0123456789abcdefABCDEF0123456789abcdef"

  @BeforeTest
  fun setUp() {
    // viewModelScope 跑在 Dispatchers.Main 上,JVM 里得先给它一个
    Dispatchers.setMain(dispatcher)
  }

  @AfterTest
  fun tearDown() {
    for (model in models) model.viewModelScope.cancel()
    models.clear()
    Dispatchers.resetMain()
  }

  private fun viewModel(vault: FakeWebCookieVault, store: AccountStore) =
    LoginViewModel(store, vault, SettingsStore(FakePreferencesDataStore())).also { models += it }

  @Test
  fun `挂 WebView 之前先清掉上一个账号的 cookie`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault(cookie = "ngaPassportUid=1001; ngaPassportCid=$cid")
    val model = viewModel(vault, inMemoryAccountStore())

    runCurrent()

    assertEquals(1, vault.clearCount, "进场必须清一次,否则轮询会立刻捕获上一个账号")
    val state = model.state.value
    assertIs<LoginUiState.Ready>(state)
    assertEquals("https://bbs.nga.cn/nuke.php?__lib=login&__act=account&login", state.url)
  }

  @Test
  fun `两枚 passport cookie 齐了就落账号 并解出用户名`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault()
    val store = inMemoryAccountStore()
    val model = viewModel(vault, store)
    runCurrent()

    // 用户走完官方登录流程,cookie 出现在原生仓库里(阴阳师妄想 的 GBK 双重编码)
    vault.cookie =
      "ngaPassportUid=$uid; ngaPassportCid=$cid; " +
        "ngaPassportUrlencodedUname=%25D2%25F5%25D1%25F4%25CA%25A6%25CD%25FD%25CF%25EB"
    advanceTimeBy(COOKIE_POLL_MS)
    runCurrent()

    val account = store.accounts.first().accounts.single()
    assertEquals(uid, account.uid)
    assertEquals(cid, account.cid)
    assertEquals("阴阳师妄想", account.name)
    assertEquals(uid, store.accounts.first().currentUid, "登进来的立刻成为当前账号")
    assertEquals(LoginUiState.Captured("阴阳师妄想"), model.state.value)
  }

  @Test
  fun `收割成功后再清一次 WebView cookie —— 修 P1-03`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault()
    val model = viewModel(vault, inMemoryAccountStore())
    runCurrent()
    assertEquals(1, vault.clearCount)

    vault.cookie = "ngaPassportUid=$uid; ngaPassportCid=$cid"
    advanceTimeBy(COOKIE_POLL_MS)
    runCurrent()

    assertEquals(2, vault.clearCount, "凭证进了 AccountStore,WebView 里那份必须清掉")
    assertEquals("", vault.cookie)
    assertIs<LoginUiState.Captured>(model.state.value)
  }

  @Test
  fun `用户名 cookie 缺失或解不动时回落 UID 展示`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault()
    val store = inMemoryAccountStore()
    viewModel(vault, store)
    runCurrent()

    vault.cookie = "ngaPassportUid=$uid; ngaPassportCid=$cid; ngaPassportUrlencodedUname=%2"
    advanceTimeBy(COOKIE_POLL_MS)
    runCurrent()

    assertEquals("UID $uid", store.accounts.first().accounts.single().name)
  }

  @Test
  fun `登录前的占位 cookie 不会被误当成登录成功`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault()
    val store = inMemoryAccountStore()
    val model = viewModel(vault, store)
    runCurrent()

    // 页面还没登录时挂着的那些占位值
    vault.cookie = "ngaPassportUid=guest; ngaPassportCid=deleted; guestJs=1754600001"
    advanceTimeBy(COOKIE_POLL_MS * 6)
    runCurrent()

    assertEquals(emptyList(), store.accounts.first().accounts)
    assertIs<LoginUiState.Ready>(model.state.value)
    assertEquals(1, vault.clearCount, "没收割就不该有第二次清理")
  }

  @Test
  fun `轮询连着看到同一份 cookie 也只落一次账号`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault()
    val store = inMemoryAccountStore()
    viewModel(vault, store)
    runCurrent()

    vault.cookie = "ngaPassportUid=$uid; ngaPassportCid=$cid"
    advanceTimeBy(COOKIE_POLL_MS)
    runCurrent()
    // 收割那一下把 vault 清空了;就算页面又写回来,循环已经退出,不该再动账号
    vault.cookie = "ngaPassportUid=99999999; ngaPassportCid=$cid"
    advanceTimeBy(COOKIE_POLL_MS * 4)
    runCurrent()

    assertEquals(listOf(uid), store.accounts.first().accounts.map { it.uid })
    assertEquals(2, vault.clearCount)
  }

  @Test
  fun `同一个账号重登只刷新 cid 不会多出一条`() = runTest(dispatcher) {
    val vault = FakeWebCookieVault()
    val store = inMemoryAccountStore()
    store.upsert(testAccount(uid, cid = "cid-old"))
    viewModel(vault, store)
    runCurrent()

    vault.cookie = "ngaPassportUid=$uid; ngaPassportCid=$cid"
    advanceTimeBy(COOKIE_POLL_MS)
    runCurrent()

    val accounts = store.accounts.first().accounts
    assertEquals(1, accounts.size)
    assertEquals(cid, accounts.single().cid)
    assertNotEquals("cid-old", accounts.single().cid)
  }
}
