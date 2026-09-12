package com.chasel.ng2n.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.di.AccountPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountStore @Inject constructor(
  @AccountPreferences private val dataStore: DataStore<Preferences>,
  private val crypto: AccountCrypto,
  private val log: AccountStoreLog = AccountStoreLog.NONE,
) : CredentialSource {

  private val warnedUnreadable = AtomicBoolean(false)

  val accounts: Flow<AccountsState> = dataStore.data
    .catch { cause ->
      if (cause is IOException) emit(emptyPreferences()) else throw cause
    }
    .map { prefs ->
      when (val stored = readStored(prefs[KEY])) {
        is Stored.Readable -> stored.state
        is Stored.Unreadable -> {
          if (warnedUnreadable.compareAndSet(false, true)) {
            log.warn(
              "账号存档读不出(${stored.reason}),本次按游客态起;密文原样留在盘上,没有清除",
              null,
            )
          }
          EMPTY_ACCOUNTS
        }
      }
    }

  val currentUid: Flow<String?> = accounts.map { it.currentUid }.distinctUntilChanged()

  suspend fun currentAccount(): NgaAccount? = currentAccountOf(accounts.first())

  override suspend fun current(): Credential? =
    currentAccount()?.let { Credential(uid = it.uid, token = it.cid) }

  override suspend fun all(): List<Credential> =
    accounts.first().accounts.map { Credential(uid = it.uid, token = it.cid) }

  suspend fun upsert(account: NgaAccount) = mutate { addAccount(it, account) }

  suspend fun switchTo(uid: String) = mutate { switchAccount(it, uid) }

  suspend fun remove(uid: String) = mutate { removeAccount(it, uid) }

  suspend fun cycle(step: Int) {
    val next = cycleAccountUid(accounts.first(), step) ?: return
    switchTo(next)
  }

  private suspend fun mutate(transform: (AccountsState) -> AccountsState) {
    // 读取、变换和加密写回必须在同一事务内，避免并发操作覆盖账号表。
    dataStore.edit { prefs ->
      val stored = readStored(prefs[KEY])
      val base = when (stored) {
        is Stored.Readable -> stored.state
        is Stored.Unreadable -> {
          prefs[KEY_UNREADABLE] = stored.blob
          log.warn(
            "账号存档读不出(${stored.reason}),这次写入从空表起;" +
              "原密文已挪到 $KEY_UNREADABLE_NAME 留证,未丢弃",
            null,
          )
          EMPTY_ACCOUNTS
        }
      }
      val next = transform(base)
      val blob = crypto.encrypt(JSON.encodeToString(next).toByteArray(Charsets.UTF_8))
      when {
        blob != null -> prefs[KEY] = blob
        next.accounts.isEmpty() -> prefs.remove(KEY)
        else -> log.warn("账号表写不进(加密失败),盘上旧存档保持不变", null)
      }
    }
  }

  private sealed interface Stored {

    data class Readable(val state: AccountsState) : Stored

    data class Unreadable(val blob: String, val reason: String) : Stored
  }

  private fun readStored(blob: String?): Stored {
    if (blob == null) return Stored.Readable(EMPTY_ACCOUNTS)
    val plain = crypto.decrypt(blob)
      ?: return Stored.Unreadable(blob, "密文解不开(密钥丢失或密文被改)")
    val parsed = runCatching {
      JSON.decodeFromString<AccountsState>(plain.toString(Charsets.UTF_8))
    }.getOrNull() ?: return Stored.Unreadable(blob, "解出来不是合法的账号表 JSON")
    return Stored.Readable(sanitizeAccounts(parsed))
  }

  companion object {
    const val FILE_NAME = "ng2n-accounts"

    fun withKeystore(dataStore: DataStore<Preferences>): AccountStore =
      AccountStore(dataStore, KeystoreCrypto(), AndroidAccountStoreLog())

    private val KEY = stringPreferencesKey("accounts.v1")

    const val KEY_UNREADABLE_NAME = "accounts.v1.unreadable"
    private val KEY_UNREADABLE = stringPreferencesKey(KEY_UNREADABLE_NAME)

    private val JSON = Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
      explicitNulls = false
    }
  }
}
