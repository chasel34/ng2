package com.chasel.ng2n.data.account

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class NgaAccount(
  val uid: String,
  val cid: String,
  val name: String,
  val loginAt: Long,
)

@Serializable
data class AccountsState(
  val accounts: List<NgaAccount> = emptyList(),
  val currentUid: String? = null,
)

val EMPTY_ACCOUNTS = AccountsState()

const val COOKIE_LIFETIME_DAYS = 30

private const val DAY_MS = 24L * 60 * 60 * 1000

fun cookieDaysLeft(loginAt: Long, now: Long): Int =
  ceil((loginAt + COOKIE_LIFETIME_DAYS * DAY_MS - now).toDouble() / DAY_MS).toInt()

fun formatCookieExpiry(loginAt: Long, now: Long): String {
  val days = cookieDaysLeft(loginAt, now)
  return if (days > 0) "$days 天后过期" else "已过期"
}

fun currentAccountOf(state: AccountsState): NgaAccount? =
  state.accounts.firstOrNull { it.uid == state.currentUid }

fun addAccount(state: AccountsState, account: NgaAccount): AccountsState {
  val exists = state.accounts.any { it.uid == account.uid }
  val accounts = if (exists) {
    state.accounts.map { if (it.uid == account.uid) account else it }
  } else {
    state.accounts + account
  }
  return AccountsState(accounts, account.uid)
}

fun switchAccount(state: AccountsState, uid: String): AccountsState {
  if (state.accounts.none { it.uid == uid }) return state
  return state.copy(currentUid = uid)
}

fun removeAccount(state: AccountsState, uid: String): AccountsState {
  val accounts = state.accounts.filter { it.uid != uid }
  val currentUid =
    if (state.currentUid == uid) accounts.firstOrNull()?.uid else state.currentUid
  return AccountsState(accounts, currentUid)
}

fun cycleAccountUid(state: AccountsState, step: Int): String? {
  val accounts = state.accounts
  if (accounts.size < 2) return null
  val index = accounts.indexOfFirst { it.uid == state.currentUid }
  val next = ((index + step) % accounts.size + accounts.size) % accounts.size
  return accounts[next].uid
}

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
