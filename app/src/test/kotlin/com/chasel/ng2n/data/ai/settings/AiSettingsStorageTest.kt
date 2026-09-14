package com.chasel.ng2n.data.ai.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.chasel.ng2n.data.account.AccountCrypto
import com.chasel.ng2n.data.account.FakePreferencesDataStore
import com.chasel.ng2n.data.diagnostics.DiagnosticRecord
import com.chasel.ng2n.data.diagnostics.diagnosticSummary
import com.chasel.ng2n.data.diagnostics.formatDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiSettingsStorageTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `encrypted key and budget settings survive closing and reopening datastore`() = runTest {
    val keyFile = temporary.newFolder().resolve("keys.preferences_pb")
    val settingsFile = temporary.newFolder().resolve("settings.preferences_pb")
    val crypto = TestCrypto()
    val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val keys = PreferenceDataStoreFactory.create(scope = firstScope) { keyFile }
    val prefs = PreferenceDataStoreFactory.create(scope = firstScope) { settingsFile }
    val key = "sk-private-key-for-round-trip"
    AiKeyStore(keys, crypto).saveKey(key)
    AiSettingsStore(prefs).apply {
      setAllowance(AnalysisAllowance.LONG)
      setDailyLimit(123)
      setDailyEnabled(false)
    }
    firstScope.cancel()
    firstScope.coroutineContext[Job]!!.join()
    assertFalse(keyFile.readBytes().toString(Charsets.UTF_8).contains(key))
    assertFalse(settingsFile.readBytes().toString(Charsets.UTF_8).contains(key))

    val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val reopened = AiKeyStore(PreferenceDataStoreFactory.create(scope = secondScope) { keyFile }, crypto)
      val settings = AiSettingsStore(PreferenceDataStoreFactory.create(scope = secondScope) { settingsFile })
      assertEquals(key, reopened.readKey())
      assertEquals(AiKeyState.Saved, reopened.state.first())
      assertEquals(AiSettings(AnalysisAllowance.LONG, false, 123), settings.settings.first())
      settings.setDailyEnabled(true)
      assertTrue(settings.settings.first().dailyEnabled)
    } finally {
      secondScope.cancel()
      secondScope.coroutineContext[Job]!!.join()
    }
  }

  @Test fun `unreadable ciphertext requests replacement and encryption failure preserves old key`() = runTest {
    val prefs = FakePreferencesDataStore()
    val crypto = TestCrypto()
    val store = AiKeyStore(prefs, crypto)
    store.saveKey("sk-original")
    val original = prefs.data.first()[AiKeyStore.KEY]
    crypto.failEncrypt = true
    assertFailsWith<IOException> { store.saveKey("sk-new") }
    assertEquals(original, prefs.data.first()[AiKeyStore.KEY])
    assertEquals("sk-original", store.readKey())
    prefs.edit { it[AiKeyStore.KEY] = "corrupted" }
    assertEquals(AiKeyState.Unreadable, store.state.first())
    assertFailsWith<IOException> { store.readKey() }
    crypto.failEncrypt = false
    store.saveKey("sk-replacement")
    assertEquals(AiKeyState.Saved, store.state.first())
    assertEquals("sk-replacement", store.readKey())
  }

  @Test fun `original key observer recovers after read IOException and successful save`() = runTest {
    val backing = FakePreferencesDataStore()
    var reads = 0
    val prefs = object : DataStore<Preferences> by backing {
      override val data = flow {
        reads += 1
        if (reads == 1) throw IOException("temporary read failure")
        emitAll(backing.data)
      }
    }
    val crypto = TestCrypto()
    val store = AiKeyStore(prefs, crypto)
    val observed = Channel<AiKeyState>(Channel.UNLIMITED)
    val observer = backgroundScope.launch { store.state.collect { observed.send(it) } }
    assertEquals(AiKeyState.Unreadable, observed.receive())
    assertTrue(observer.isActive)
    crypto.failEncrypt = true
    assertFailsWith<IOException> { store.saveKey("sk-not-saved") }
    assertTrue(observed.tryReceive().isFailure)
    assertEquals(1, reads)
    crypto.failEncrypt = false
    store.saveKey("sk-recovered")
    assertEquals(AiKeyState.Saved, observed.receive())
    assertTrue(observer.isActive)
    assertEquals("sk-recovered", store.readKey())
    observer.cancel()
    observer.join()
  }

  @Test fun `key observation propagates cancellation without unreadable fallback`() = runTest {
    val backing = FakePreferencesDataStore()
    val cancelled = CancellationException("cancelled read")
    val prefs = object : DataStore<Preferences> by backing {
      override val data = flow<Preferences> { throw cancelled }
    }
    val store = AiKeyStore(prefs, TestCrypto())
    val failure = assertFailsWith<CancellationException> { store.state.first() }
    assertEquals(cancelled.message, failure.message)
  }

  @Test fun `daily limit requires positive exact cents and preserves limit while disabled`() = runTest {
    listOf("", "0", "-1", "NaN", "Infinity", "1e3", "0.001", "99999999999999999999").forEach {
      assertEquals(null, parseDailyLimitFen(it))
    }
    assertEquals(1L, parseDailyLimitFen("0.01"))
    assertEquals(123L, parseDailyLimitFen("1.23"))
    val store = AiSettingsStore(FakePreferencesDataStore())
    assertFailsWith<IllegalArgumentException> { store.setDailyEnabled(true) }
    assertFailsWith<IllegalArgumentException> { store.setDailyLimit(0) }
    for (allowance in AnalysisAllowance.entries) {
      store.setAllowance(allowance)
      assertEquals(allowance, store.settings.first().allowance)
    }
  }

  @Test fun `key and all ai setting values are redacted from diagnostics`() {
    val params = mapOf(
      AiKeyStore.KEY.name to "sk-secret-example",
      "apiKey" to "sk-other-example",
      "Authorization" to "Bearer private-value",
      AiSettingsStore.Keys.ALLOWANCE.name to "long-value",
      AiSettingsStore.Keys.DAILY_ENABLED.name to "enabled-value",
      AiSettingsStore.Keys.DAILY_LIMIT_FEN.name to "1234567",
    )
    val record = DiagnosticRecord(0, "ai-settings", params, "配置更新")
    val output = formatDiagnostic(record) + diagnosticSummary(record)
    params.values.forEach { assertFalse(output.contains(it)) }
    assertNotNull(Regex("<redacted>").find(output))
  }
}

private class TestCrypto : AccountCrypto {
  private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  var failEncrypt = false
  override fun encrypt(plaintext: ByteArray): String? {
    if (failEncrypt) return null
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext))
  }
  override fun decrypt(blob: String): ByteArray? = runCatching {
    val bytes = Base64.getDecoder().decode(blob)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
    cipher.doFinal(bytes, 12, bytes.size - 12)
  }.getOrNull()
}
