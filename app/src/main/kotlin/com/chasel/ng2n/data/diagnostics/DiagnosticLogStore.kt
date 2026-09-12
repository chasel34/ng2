package com.chasel.ng2n.data.diagnostics

import androidx.datastore.core.DataStore
import com.chasel.ng2n.di.SettingsPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticLogStore @Inject constructor(
  @SettingsPreferences private val dataStore: DataStore<Preferences>,
) {

  val log: Flow<List<String>> = dataStore.data
    .catch { cause ->
      if (cause is IOException) emit(emptyPreferences()) else throw cause
    }
    .map { prefs -> decode(prefs[KEY]) }

  suspend fun currentLog(): List<String> = log.first()

  suspend fun exportText(): String = currentLog().joinToString("\n\n")

  suspend fun record(record: DiagnosticRecord) {
    pushRunLog(record)
    if (!worthPersisting(record)) return
    runCatching {
      dataStore.edit { prefs ->
        prefs[KEY] = JSON.encodeToString(appendDiagnosticLog(decode(prefs[KEY]), record))
      }
    }
  }

  suspend fun clear() {
    runCatching { dataStore.edit { it.remove(KEY) } }
  }

  data class RunLogEntry(
    val at: Long,
    val path: String,
    val params: Map<String, String>,
    val message: String,
    val ok: Boolean,
    val attempts: Int,
  )

  @Volatile
  private var runLogEntries: List<RunLogEntry> = emptyList()

  val runLog: List<RunLogEntry> get() = runLogEntries

  fun clearRunLog() {
    runLogEntries = emptyList()
  }

  @Synchronized
  private fun pushRunLog(record: DiagnosticRecord) {
    runLogEntries = (
      listOf(
        RunLogEntry(
          at = record.at,
          path = record.path,
          params = redactDiagnosticParams(record.params),
          message = record.message,
          ok = record.success != null,
          attempts = record.attempts.size,
        ),
      ) + runLogEntries
      ).take(RUN_LOG_LIMIT)
  }

  private val lastPersistedCombo = ConcurrentHashMap<String, String>()

  private fun worthPersisting(record: DiagnosticRecord): Boolean {
    val success = record.success ?: return true
    val combo = "${success.format} @ ${success.host}"
    val previous = lastPersistedCombo.put(record.path, combo)
    return record.attempts.size > 1 || (previous != null && previous != combo)
  }

  private fun decode(raw: String?): List<String> = runCatching {
    if (raw == null) emptyList() else JSON.decodeFromString<List<String>>(raw)
  }.getOrDefault(emptyList())

  companion object {
    private val KEY = stringPreferencesKey("diagnostics.log.v1")

    private const val RUN_LOG_LIMIT = 40

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
  }
}
