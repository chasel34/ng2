package com.chasel.ng2n.data.account

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStoreTest {

  @Test
  fun `登录落账号后 CredentialSource 立刻给出这个账号的凭证`() = runTest {
    val store = inMemoryAccountStore()
    assertNull(store.current(), "起手是游客态")

    store.upsert(testAccount("1001"))

    assertEquals(Credential("1001", "cid-1001"), store.current())
  }

  @Test
  fun `切号之后下一次读凭证就是新账号 —— 每请求现读`() = runTest {
    val store = inMemoryAccountStore()
    store.upsert(testAccount("1001"))
    store.upsert(testAccount("1002"))

    val source: CredentialSource = store
    assertEquals(Credential("1002", "cid-1002"), source.current())

    store.switchTo("1001")

    assertEquals(Credential("1001", "cid-1001"), source.current())
  }

  @Test
  fun `退出当前账号后凭证落到剩余第一个 全退光即游客态`() = runTest {
    val store = inMemoryAccountStore()
    store.upsert(testAccount("1001"))
    store.upsert(testAccount("1002"))

    store.remove("1002")
    assertEquals(Credential("1001", "cid-1001"), store.current())

    store.remove("1001")
    assertNull(store.current(), "全退光 = 游客态,不发任何认证信息")
    assertEquals(emptyList(), store.all())
  }

  @Test
  fun `换账号那一档拿到的是全部账号 顺序同账号管理页`() = runTest {
    val store = inMemoryAccountStore()
    store.upsert(testAccount("1001"))
    store.upsert(testAccount("1002"))
    store.upsert(testAccount("1003"))
    store.switchTo("1002")

    assertEquals(listOf("1001", "1002", "1003"), store.all().map { it.uid })
  }

  @Test
  fun `cycle 循环切号 不足两个账号是空操作`() = runTest {
    val store = inMemoryAccountStore()
    store.upsert(testAccount("1001"))
    store.cycle(1)
    assertEquals("1001", store.accounts.first().currentUid)

    store.upsert(testAccount("1002"))
    store.upsert(testAccount("1003"))
    store.switchTo("1002")

    store.cycle(1)
    assertEquals("1003", store.accounts.first().currentUid)
    store.cycle(1)
    assertEquals("1001", store.accounts.first().currentUid, "到头绕回")
    store.cycle(-1)
    assertEquals("1003", store.accounts.first().currentUid)
  }

  @Test
  fun `落盘往返 —— 写进去的表读回来一模一样`() = runTest {
    val dataStore = FakePreferencesDataStore()
    val crypto = PassThroughCrypto()
    val first = AccountStore(dataStore, crypto)
    first.upsert(testAccount("1001"))
    first.upsert(testAccount("1002"))
    first.switchTo("1001")

    val reborn = AccountStore(dataStore, crypto)
    val state = reborn.accounts.first()

    assertEquals(listOf("1001", "1002"), state.accounts.map { it.uid })
    assertEquals("1001", state.currentUid)
    assertEquals(Credential("1001", "cid-1001"), reborn.current())
  }

  @Test
  fun `存档解不开就退回游客态 绝不抛`() = runTest {
    val store = AccountStore(
      FakePreferencesDataStore(),
      object : AccountCrypto {
        override fun encrypt(plaintext: ByteArray): String? = "看起来像密文其实解不开"
        override fun decrypt(blob: String): ByteArray? = null
      },
    )
    store.upsert(testAccount("1001"))

    assertEquals(EMPTY_ACCOUNTS, store.accounts.first())
    assertNull(store.current())
  }

  @Test
  fun `currentUid 这条流只在当前账号真的换了的时候才吐`() = runTest {
    val store = inMemoryAccountStore()
    val seen = mutableListOf<String?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      store.currentUid.toList(seen)
    }

    store.upsert(testAccount("1001"))
    store.upsert(testAccount("1002"))
    store.switchTo("1001")
    store.upsert(testAccount("1001", cid = "cid-refreshed"))

    assertEquals(listOf(null, "1001", "1002", "1001"), seen)
  }

  @Test
  fun `游客态下 all 是空表 —— 反封锁链换账号那一档没得换`() = runTest {
    val store = inMemoryAccountStore()
    assertTrue(store.all().isEmpty())
  }

  @Test
  fun `解不开的存档不会被下一次写入覆盖掉 而是挪去留证键`() = runTest {
    val dataStore = FakePreferencesDataStore(
      mutablePreferencesOf(stringPreferencesKey("accounts.v1") to CIPHERTEXT),
    )
    val store = AccountStore(dataStore, undecryptableCrypto())

    assertEquals(EMPTY_ACCOUNTS, store.accounts.first())
    assertNull(store.current())

    store.upsert(testAccount("1001"))

    val prefs = dataStore.data.first()
    assertEquals(
      CIPHERTEXT,
      prefs[stringPreferencesKey(AccountStore.KEY_UNREADABLE_NAME)],
      "读不出的原密文必须留证,不能被悄悄丢掉",
    )
    assertNotNull(prefs[stringPreferencesKey("accounts.v1")], "新表照样落盘")
  }

  @Test
  fun `存档读不出时会告警 而不是一声不响`() = runTest {
    val warnings = mutableListOf<String>()
    val dataStore = FakePreferencesDataStore(
      mutablePreferencesOf(stringPreferencesKey("accounts.v1") to CIPHERTEXT),
    )
    val store = AccountStore(
      dataStore,
      undecryptableCrypto(),
      { message, _ -> warnings += message },
    )

    store.accounts.first()
    store.upsert(testAccount("1001"))

    assertTrue(warnings.size >= 2, "读一条、写一条,都要有:$warnings")
    assertTrue(warnings.all { "读不出" in it })
  }

  @Test
  fun `加密失败时盘上旧存档保持不变 不再被删掉`() = runTest {
    val dataStore = FakePreferencesDataStore()
    val good = PassThroughCrypto()
    AccountStore(dataStore, good).upsert(testAccount("1001"))
    val onDisk = dataStore.data.first()[stringPreferencesKey("accounts.v1")]
    assertNotNull(onDisk)

    val flaky = object : AccountCrypto {
      override fun encrypt(plaintext: ByteArray): String? = null
      override fun decrypt(blob: String): ByteArray? = good.decrypt(blob)
    }
    val store = AccountStore(dataStore, flaky)
    store.upsert(testAccount("1002"))

    assertEquals(
      onDisk,
      dataStore.data.first()[stringPreferencesKey("accounts.v1")],
      "写不进就保持原样,重启后回到上一次成功落盘的账号,而不是游客态",
    )
    assertEquals(Credential("1001", "cid-1001"), AccountStore(dataStore, good).current())
  }

  @Test
  fun `加密失败但目标是空表时照样清干净`() = runTest {
    val dataStore = FakePreferencesDataStore()
    val good = PassThroughCrypto()
    AccountStore(dataStore, good).upsert(testAccount("1001"))

    val flaky = object : AccountCrypto {
      override fun encrypt(plaintext: ByteArray): String? = null
      override fun decrypt(blob: String): ByteArray? = good.decrypt(blob)
    }
    AccountStore(dataStore, flaky).remove("1001")

    assertNull(dataStore.data.first()[stringPreferencesKey("accounts.v1")])
    assertNull(AccountStore(dataStore, good).current())
  }

  private companion object {

    const val CIPHERTEXT = "这串是解不开的密文"

    fun undecryptableCrypto(): AccountCrypto = object : AccountCrypto {
      private val passThrough = PassThroughCrypto()
      override fun encrypt(plaintext: ByteArray): String? = passThrough.encrypt(plaintext)
      override fun decrypt(blob: String): ByteArray? =
        if (blob == CIPHERTEXT) null else passThrough.decrypt(blob)
    }
  }
}
