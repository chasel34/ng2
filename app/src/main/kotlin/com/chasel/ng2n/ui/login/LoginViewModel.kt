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

/** 登录页(API 文档 §0.2),两端一致,WebView 打开。 */
const val LOGIN_PATH = "nuke.php?__lib=login&__act=account&login"

/**
 * 抓 cookie 走原生 `CookieManager` 轮询(每 0.5s,MNGA 的节奏),而不是页内
 * `document.cookie`:`ngaPassportCid` 是 HttpOnly,页面 JS 根本看不到(真机实测
 * 2026-08-08,`document.cookie` 里只有 uid 和 uname)。
 */
const val COOKIE_POLL_MS = 500L

/** 登录屏的状态机。 */
sealed interface LoginUiState {

  /** 还在清上一个账号的 cookie —— **清完才挂 WebView**,见 [LoginViewModel]。 */
  data object Preparing : LoginUiState

  /** 可以挂 WebView 了。[url] 是当前域名下的官方登录页。 */
  data class Ready(val url: String) : LoginUiState

  /** 收割成功,账号已落盘。屏上该弹一句提示然后退场。 */
  data class Captured(val name: String) : LoginUiState
}

/**
 * WebView 登录 —— `src/app/login.tsx` 的移植。
 *
 * ## 流程(节奏照抄 RN 版)
 *
 * 1. **挂 WebView 之前**先 [WebCookieVault.clearAll]:不清的话,添加第二个账号时
 *    轮询会在页面还没登录时就「捕获」到上一个账号的 cookie(RN 版原注释);
 * 2. 加载 `<当前域名>/nuke.php?__lib=login&__act=account&login`;
 * 3. 每 [COOKIE_POLL_MS] 读一次原生 cookie 仓库,`ngaPassportUid` + `ngaPassportCid`
 *    两者齐(且形状对得上,排掉 `guest` 这类占位)才算登录成功;
 * 4. 用户名从 `ngaPassportUrlencodedUname` 解(GBK 双重 URLDecode),解不动就回落
 *    `UID <uid>`;
 * 5. 落 `AccountStore` 并**立刻成为当前账号**,然后**再清一次 WebView cookie**。
 *
 * ## 第 5 步那次清理是对 RN 版的有意偏离(修 P1-03)
 *
 * RN 版只在进场清,收割完把 cookie 留在 `CookieManager` 里 —— 那份残留正是审计 P1-03
 * 说的第二个身份来源(app 退出了、WebView 还登着;切了账号、网页兜底还是上一个)。
 * 这一版收割完就清干净:凭证已经在 `AccountStore` 里了,app 的请求走自管 CookieJar,
 * 没有任何一条路径需要 WebView 那份。网页兜底要登录态时按当前账号现灌
 * ([WebCookieVault.seed])。
 *
 * ## 只处理第一次
 *
 * 轮询会连着看到同一份 cookie,收割成功后循环就退出([LoginUiState.Captured] 是终态),
 * 免得重复落账号 / 重复退场(RN 版是 `captured` ref 那个守卫)。
 */
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
      // 登录页跟着设置里的当前域名走(RN 版写死 DEFAULT_NGA_HOST —— 那个域名被墙时
      // 登录页根本打不开,而设置里换域名本来就是给这种时候用的)
      val host = settings.currentSettings().host
      cookies.clearAll()
      _state.value = LoginUiState.Ready("$host/$LOGIN_PATH")
      harvest(host)
    }
  }

  /** 轮询原生 cookie 仓库直到收割成功。 */
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
          // cookie 的真实 expires 拿不到,30 天有效期按登录时刻本地推算(见 `Accounts.kt`)
          loginAt = System.currentTimeMillis(),
        ),
      )
      // 凭证已落盘,WebView 里那份没人再需要 —— 清掉,别留第二个身份来源(修 P1-03)
      cookies.clearAll()
      _state.value = LoginUiState.Captured(name)
      return
    }
  }
}
