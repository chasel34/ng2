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

@HiltViewModel
class AccountsViewModel @Inject constructor(
  private val store: AccountStore,
  private val cookies: WebCookieVault,
) : ViewModel() {

  val state: StateFlow<AccountsState> = store.accounts.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = EMPTY_ACCOUNTS,
  )

  private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)

  val toasts = _toasts.asSharedFlow()

  fun switchTo(uid: String) {
    viewModelScope.launch { switchToNow(uid) }
  }

  fun cycle(step: Int) {
    viewModelScope.launch {
      switchToNow(cycleAccountUid(store.accounts.first(), step) ?: return@launch)
    }
  }

  fun logout(uid: String) {
    viewModelScope.launch {
      val account = store.accounts.first().accounts.firstOrNull { it.uid == uid } ?: return@launch
      store.remove(uid)
      cookies.clearAll()
      _toasts.emit("已退出 ${account.name}")
    }
  }

  private suspend fun switchToNow(uid: String) {
    val snapshot = store.accounts.first()
    val account = snapshot.accounts.firstOrNull { it.uid == uid } ?: return
    if (snapshot.currentUid == uid) return
    store.switchTo(uid)
    cookies.clearAll()
    _toasts.emit("已切换到 ${account.name}")
  }

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}

fun AccountsState.current(): NgaAccount? = currentAccountOf(this)
