package com.chasel.ng2n.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.account.NgaAccount
import com.chasel.ng2n.data.account.WebCookieVault
import com.chasel.ng2n.data.account.decodeLoginUsername
import com.chasel.ng2n.data.account.extractLoginCookies
import com.chasel.ng2n.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

const val LOGIN_PATH = "nuke.php?__lib=login&__act=account&login"

const val COOKIE_POLL_MS = 500L

sealed interface LoginUiState {

  data object Preparing : LoginUiState

  data class Ready(val url: String) : LoginUiState

  data class Captured(val name: String) : LoginUiState
}

@HiltViewModel
class LoginViewModel @Inject constructor(
  private val store: AccountStore,
  private val cookies: WebCookieVault,
  private val settings: SettingsStore,
) : ViewModel() {

  private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Preparing)
  val state: StateFlow<LoginUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val host = settings.currentSettings().host
      cookies.clearAll()
      _state.value = LoginUiState.Ready("$host/$LOGIN_PATH")
      harvest(host)
    }
  }

  private suspend fun harvest(host: String) {
    while (viewModelScope.isActive) {
      delay(COOKIE_POLL_MS)
      val raw = runCatching { cookies.read(host) }.getOrNull() ?: continue
      val parsed = extractLoginCookies(raw) ?: continue

      val name = parsed.urlencodedUname?.let { decodeLoginUsername(it) } ?: "UID ${parsed.uid}"
      store.upsert(
        NgaAccount(
          uid = parsed.uid,
          cid = parsed.cid,
          name = name,
          loginAt = System.currentTimeMillis(),
        ),
      )
      cookies.clearAll()
      _state.value = LoginUiState.Captured(name)
      return
    }
  }
}
