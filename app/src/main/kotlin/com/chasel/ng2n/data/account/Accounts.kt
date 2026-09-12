package com.chasel.ng2n.data.account

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * 多账号模型 —— `src/core/account/accounts.ts` 的直译(`accounts.v1` 的语义)。
 *
 * 纯 Kotlin;加密落盘在 [AccountStore]。
 * 「可同时登录多个,有且仅有一个当前账号」;**每请求现读、切号下个请求生效**
 * (`CredentialSource` 的契约)。
 */

@Serializable
data class NgaAccount(
  val uid: String,
  /** 会话凭证(Cookie `ngaPassportCid`)。**只在加密后的载荷里出现** */
  val cid: String,
  /** 展示名;用户名 cookie 抓不到时回落为 `UID <uid>` */
  val name: String,
  /** 登录时刻(ms since epoch),cookie 过期天数按它推算 */
  val loginAt: Long,
)

@Serializable
data class AccountsState(
  val accounts: List<NgaAccount> = emptyList(),
  /** 当前账号 uid;null 即游客态 */
  val currentUid: String? = null,
)

val EMPTY_ACCOUNTS = AccountsState()

/**
 * NGA passport cookie 的有效期。`Set-Cookie` 的真实 expires 拿不到,
 * 按 passport 的 30 天惯例从登录时刻本地推算,**只作账号管理页的展示**
 * (客户端假设值,不强制)。
 */
const val COOKIE_LIFETIME_DAYS = 30

private const val DAY_MS = 24L * 60 * 60 * 1000

/** 距 cookie 过期还剩几天(向上取整;刚登录 = 30,已过期 ≤ 0)。 */
fun cookieDaysLeft(loginAt: Long, now: Long): Int =
  ceil((loginAt + COOKIE_LIFETIME_DAYS * DAY_MS - now).toDouble() / DAY_MS).toInt()

/** 账号管理页的过期文案。 */
fun formatCookieExpiry(loginAt: Long, now: Long): String {
  val days = cookieDaysLeft(loginAt, now)
  return if (days > 0) "$days 天后过期" else "已过期"
}

fun currentAccountOf(state: AccountsState): NgaAccount? =
  state.accounts.firstOrNull { it.uid == state.currentUid }

/**
 * 登录成功后落账号:同 uid 重登**就地刷新**(新 cid、新 loginAt,位置不动),
 * 新账号追加到末尾;登录进来的账号立刻成为当前账号。
 */
fun addAccount(state: AccountsState, account: NgaAccount): AccountsState {
  val exists = state.accounts.any { it.uid == account.uid }
  val accounts = if (exists) {
    state.accounts.map { if (it.uid == account.uid) account else it }
  } else {
    state.accounts + account
  }
  return AccountsState(accounts, account.uid)
}

/** 切换当前账号;uid 不在列表里就原样返回(防御过期的 UI 事件)。 */
fun switchAccount(state: AccountsState, uid: String): AccountsState {
  if (state.accounts.none { it.uid == uid }) return state
  return state.copy(currentUid = uid)
}

/** 退出某账号。退的是当前账号时落到剩余第一个;全退光即游客态。 */
fun removeAccount(state: AccountsState, uid: String): AccountsState {
  val accounts = state.accounts.filter { it.uid != uid }
  val currentUid =
    if (state.currentUid == uid) accounts.firstOrNull()?.uid else state.currentUid
  return AccountsState(accounts, currentUid)
}

/**
 * 抽屉头部左右滑动的「下一个/上一个」账号 uid(循环取);
 * 不足两个账号没得切,返回 null。
 */
fun cycleAccountUid(state: AccountsState, step: Int): String? {
  val accounts = state.accounts
  if (accounts.size < 2) return null
  val index = accounts.indexOfFirst { it.uid == state.currentUid }
  val next = ((index + step) % accounts.size + accounts.size) % accounts.size
  return accounts[next].uid
}

/**
 * 落盘的账号表读回来。这份 JSON 可能出自旧版本 app,一律当外部输入校验:
 * **坏账号剔除、currentUid 不在表里就落到第一个、整串坏掉退回空表**(等价游客态),
 * 绝不抛。
 */
fun sanitizeAccounts(state: AccountsState): AccountsState {
  val seen = LinkedHashSet<String>()
  val accounts = ArrayList<NgaAccount>()
  for (account in state.accounts) {
    if (account.uid == "" || account.cid == "") continue
    if (!seen.add(account.uid)) continue
    accounts += account
  }
  val stored = state.currentUid
  val currentUid = if (stored != null && stored in seen) stored else accounts.firstOrNull()?.uid
  return AccountsState(accounts, currentUid)
}
