package com.chasel.ng2n.data.account

import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AccountStore` 的状态迁移 —— 票 15 验收②③里「切号 / 登出之后凭证跟着变」那一半。
 *
 * 用内存 DataStore + 直通 crypto([inMemoryAccountStore]):真类、真状态迁移、真落盘往返,
 * 只有「加密」和「写文件」是假的。
 */
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
    store.upsert(testAccount("1002")) // 登进来的立刻成为当前账号

    val source: CredentialSource = store
    assertEquals(Credential("1002", "cid-1002"), source.current())

    store.switchTo("1001")

    // 关键断言:没有任何缓存/快照挡在中间,下一发请求拿到的就是新身份
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

    // 冷启动:同一份存档换一个 store 实例读
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
        // 密钥被系统清掉(改锁屏、恢复出厂、备份还原)的那一档
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
    store.upsert(testAccount("1002")) // 登进来的立刻成为当前账号
    store.switchTo("1001")
    // 同 uid 重登只刷新 cid,当前账号还是 1001 —— 下游(按 uid 分键的缓存)不该被惊动
    store.upsert(testAccount("1001", cid = "cid-refreshed"))

    assertEquals(listOf(null, "1001", "1002", "1001"), seen)
  }

  @Test
  fun `游客态下 all 是空表 —— 反封锁链换账号那一档没得换`() = runTest {
    val store = inMemoryAccountStore()
    assertTrue(store.all().isEmpty())
  }
}
