package com.chasel.ng2n.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.chasel.ng2n.core.net.Credential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

class FakePreferencesDataStore(
  initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

  private val state = MutableStateFlow(initial)
  private val mutex = Mutex()

  override val data: Flow<Preferences> = state

  override suspend fun updateData(
    transform: suspend (t: Preferences) -> Preferences,
  ): Preferences = mutex.withLock {
    val next = transform(state.value)
    state.value = next
    next
  }
}

class PassThroughCrypto : AccountCrypto {

  override fun encrypt(plaintext: ByteArray): String? =
    Base64.getEncoder().encodeToString(plaintext)

  override fun decrypt(blob: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(blob) }.getOrNull()
}

class FakeWebCookieVault(var cookie: String = "") : WebCookieVault {

  var clearCount: Int = 0
    private set

  val seeded: MutableList<Pair<String, Credential?>> = mutableListOf()

  override suspend fun read(url: String): String = cookie

  override suspend fun clearAll(): Boolean {
    clearCount += 1
    val hadSomething = cookie.isNotEmpty()
    cookie = ""
    return hadSomething
  }

  override suspend fun seed(url: String, credential: Credential?) {
    clearAll()
    seeded += url to credential
    if (credential != null) {
      cookie = "ngaPassportUid=${credential.uid}; ngaPassportCid=${credential.token}"
    }
  }
}

fun inMemoryAccountStore(): AccountStore =
  AccountStore(FakePreferencesDataStore(), PassThroughCrypto())

fun testAccount(
  uid: String,
  cid: String = "cid-$uid",
  name: String = "用户$uid",
  loginAt: Long = 1_754_000_000_000,
): NgaAccount = NgaAccount(uid = uid, cid = cid, name = name, loginAt = loginAt)
