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

/**
 * 账号相关单测共用的两个假件。
 *
 * `DataStore<Preferences>` 本身就是个接口,内存实现十几行 —— 比在单测里起一个真文件
 * DataStore 稳(不用管临时目录、也不会因为并发跑的别的用例互相踩)。
 * `Preferences` / `MutablePreferences` 是纯 Kotlin 类,JVM 上直接可用。
 */
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

/**
 * 直通版 [AccountCrypto]:只做 Base64,不加密。
 *
 * 真实装 [KeystoreCrypto] 要 `AndroidKeyStore` 与 `android.util.Base64`,
 * 在 JVM 单测里两样都是会抛的桩。加解密算法本身由 androidTest 的
 * `KeystoreCryptoTest` 在真机/模拟器上验;单测这边只需要「存得进、读得回」。
 */
class PassThroughCrypto : AccountCrypto {

  override fun encrypt(plaintext: ByteArray): String? =
    Base64.getEncoder().encodeToString(plaintext)

  override fun decrypt(blob: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(blob) }.getOrNull()
}

/** 内存版 [WebCookieVault]:记下被清了几次、被灌了什么。 */
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

/** 建一个跑在内存里的 [AccountStore](真类、真状态迁移,只有加密与落盘是假的)。 */
fun inMemoryAccountStore(): AccountStore =
  AccountStore(FakePreferencesDataStore(), PassThroughCrypto())

fun testAccount(
  uid: String,
  cid: String = "cid-$uid",
  name: String = "用户$uid",
  loginAt: Long = 1_754_000_000_000,
): NgaAccount = NgaAccount(uid = uid, cid = cid, name = name, loginAt = loginAt)
