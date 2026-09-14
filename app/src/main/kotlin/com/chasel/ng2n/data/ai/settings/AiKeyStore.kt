package com.chasel.ng2n.data.ai.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chasel.ng2n.data.account.AccountCrypto
import com.chasel.ng2n.di.AiKeyCrypto
import com.chasel.ng2n.di.AiKeyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AiKeyState {
  data object Missing : AiKeyState
  data object Saved : AiKeyState
  data object Unreadable : AiKeyState
}

@Singleton
class AiKeyStore @Inject constructor(
  @AiKeyPreferences private val dataStore: DataStore<Preferences>,
  @AiKeyCrypto private val crypto: AccountCrypto,
) {
  private val readRevision = MutableStateFlow(0L)

  val state = readRevision.transform {
    emitAll(dataStore.data.map { prefs ->
      val blob = prefs[KEY] ?: return@map AiKeyState.Missing
      if (decode(blob) == null) AiKeyState.Unreadable else AiKeyState.Saved
    }.catch { cause ->
      if (cause is IOException) emit(AiKeyState.Unreadable) else throw cause
    })
  }.flowOn(Dispatchers.IO)

  suspend fun readKey(): String? = withContext(Dispatchers.IO) {
    val blob = dataStore.data.first()[KEY] ?: return@withContext null
    decode(blob) ?: throw IOException("模型密钥无法解密，请重新输入")
  }

  suspend fun saveKey(value: String) = withContext(Dispatchers.IO) {
    val key = value.trim()
    require(key.isNotEmpty() && key.none { it.isWhitespace() || it.isISOControl() })
    val bytes = key.toByteArray(Charsets.UTF_8)
    val blob = try {
      crypto.encrypt(bytes) ?: throw IOException("模型密钥加密失败")
    } finally {
      bytes.fill(0)
    }
    dataStore.edit { it[KEY] = blob }
    readRevision.update { it + 1 }
  }

  private fun decode(blob: String): String? {
    val bytes = crypto.decrypt(blob) ?: return null
    return try {
      bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    } finally {
      bytes.fill(0)
    }
  }

  companion object {
    const val FILE_NAME = "ng2n-ai-keys"
    const val KEY_ALIAS = "ng2n.ai.keys.v1"
    val KEY = stringPreferencesKey("ai.deepseek.apiKey.v1")
  }
}
