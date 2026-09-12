package com.chasel.ng2n.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.account.AccountsState
import com.chasel.ng2n.data.account.EMPTY_ACCOUNTS
import com.chasel.ng2n.data.account.NgaAccount
import com.chasel.ng2n.data.account.WebCookieVault
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.account.cycleAccountUid
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 多账号状态(账号管理屏 + 抽屉账号头共用一份)。
 *
 * RN 版这一层是全局 zustand store(`src/store/accounts.ts`),抽屉、账号管理页、登录页
 * 读的是**同一个**实例。这里对应成**挂在 Activity 上的一个 ViewModel**:
 * `Ng2nApp()` 在 `NavDisplay` 外面 `hiltViewModel()` 一次,再传给各屏 ——
 * 每屏各拿一个的话,切号之后另一屏的账号头不会跟着变。
 *
 * ## 切号 / 登出的 cookie 纪律(修 P1-03)
 *
 * 每次账号集合发生变化都顺手清一次 WebView 的 cookie:
 * app 自己的请求根本不看那份(走票 06 的自管 jar),留着它只会让「网页那边现在是谁」
 * 无法回答 —— 审计 P1-03 的原话是「用户在 App 中退出后,WebView 仍可能保持登录」。
 * 网页兜底屏要登录态时用 [WebCookieVault.seed] 按当前账号现灌。
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
  private val store: AccountStore,
  private val cookies: WebCookieVault,
) : ViewModel() {

  val state: StateFlow<AccountsState> = store.accounts.stateIn(
    scope = viewModelScope,
    // 转屏/短暂离开不重订阅;RN 版的全局 store 是常驻的,这是最接近的语义
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = EMPTY_ACCOUNTS,
  )

  private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)

  /** 一次性提示(「已切换到 X」/「已退出 X」),照 RN 版的 `showToast` 时机。 */
  val toasts = _toasts.asSharedFlow()

  /** 切换当前账号。已经是当前账号就什么都不做(照 RN 版账号管理页的守卫)。 */
  fun switchTo(uid: String) {
    viewModelScope.launch { switchToNow(uid) }
  }

  /**
   * 抽屉账号头左右滑:+1 下一个、-1 上一个,循环;不足两个账号是 no-op。
   * 循环取的是 `cycleAccountUid`,与 RN 版同一份纯函数语义。
   */
  fun cycle(step: Int) {
    viewModelScope.launch {
      switchToNow(cycleAccountUid(store.accounts.first(), step) ?: return@launch)
    }
  }

  /** 退出某账号。退的是当前账号时落到剩余第一个;全退光即游客态。 */
  fun logout(uid: String) {
    viewModelScope.launch {
      val account = store.accounts.first().accounts.firstOrNull { it.uid == uid } ?: return@launch
      store.remove(uid)
      // 退出即清 WebView 那份(审计 P1-03:「用户在 App 中退出后,WebView 仍可能保持登录」)
      cookies.clearAll()
      _toasts.emit("已退出 ${account.name}")
    }
  }

  /**
   * 切号的实处。**读的是 `store` 的现值而不是 [state]**:
   * [state] 是 `WhileSubscribed` 的 UI 缓存,没有订阅者时它停在上一帧,
   * 拿它当判据会让「后台发起的切号」静默失效。
   */
  private suspend fun switchToNow(uid: String) {
    val snapshot = store.accounts.first()
    val account = snapshot.accounts.firstOrNull { it.uid == uid } ?: return
    if (snapshot.currentUid == uid) return
    store.switchTo(uid)
    // 切号也清:app 这边换人了,WebView 里还留着上一个账号的 cookie 就又成了第二个身份
    cookies.clearAll()
    _toasts.emit("已切换到 ${account.name}")
  }

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** 当前账号;游客态是 null。 */
fun AccountsState.current(): NgaAccount? = currentAccountOf(this)
