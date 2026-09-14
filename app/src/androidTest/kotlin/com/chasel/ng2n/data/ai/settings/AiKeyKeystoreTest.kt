package com.chasel.ng2n.data.ai.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.data.account.AccountStoreLog
import com.chasel.ng2n.data.account.KeystoreCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class AiKeyKeystoreTest {
  @Test fun keySurvivesStoreRecreationAndMissingKeystoreKeyCanBeReplaced() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val alias = "${AiKeyStore.KEY_ALIAS}.test.${UUID.randomUUID()}"
    val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
    val androidKeys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    suspend fun withStore(block: suspend (AiKeyStore) -> Unit) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      try {
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        block(AiKeyStore(prefs, KeystoreCrypto(alias, AccountStoreLog.NONE)))
      } finally {
        scope.cancel()
        scope.coroutineContext[Job]!!.join()
      }
    }
    try {
      withStore { it.saveKey("sk-device-test-value") }
      assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("sk-device-test-value"))
      withStore {
        assertEquals("sk-device-test-value", it.readKey())
        assertEquals(AiKeyState.Saved, it.state.first())
      }
      androidKeys.deleteEntry(alias)
      withStore {
        assertEquals(AiKeyState.Unreadable, it.state.first())
        it.saveKey("sk-reentered")
        assertEquals("sk-reentered", it.readKey())
      }
    } finally {
      androidKeys.deleteEntry(alias)
      file.delete()
    }
  }
}
