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

/**
 * 诊断日志的两份去处 —— RN 版 `src/store/diagnostics.ts` 的直译。
 *
 * 1. **落盘日志**(MMKV `diagnostics.log.v1` → 这里的 [KEY]):50 条上限,
 *    实验室页「导出诊断日志」消费它。**写入前一律脱敏**([formatDiagnostic] 收口)。
 * 2. **本次运行的请求落点表**([runLog]):**只活在内存里**,进程一死就没了 ——
 *    它要回答的正是「这个进程现在挂在哪个组合上」。
 *    不落盘是有意的:每次请求都读一遍整份日志再序列化写回去,是个 O(日志长度) 的
 *    磁盘写,请求热路径上不该有这个。
 */
@Singleton
class DiagnosticLogStore @Inject constructor(
  @SettingsPreferences private val dataStore: DataStore<Preferences>,
) {

  /** 最新的在最后。实验室页的「导出诊断日志」拿它拼文件。 */
  val log: Flow<List<String>> = dataStore.data
    .catch { cause ->
      // 日志本身坏了不该拖垮请求链,当空的重新攒
      if (cause is IOException) emit(emptyPreferences()) else throw cause
    }
    .map { prefs -> decode(prefs[KEY]) }

  suspend fun currentLog(): List<String> = log.first()

  /** 导出用的整份文本。 */
  suspend fun exportText(): String = currentLog().joinToString("\n\n")

  /**
   * 记一条。**成功的请求不全落** —— 全落会把导出日志冲垮(一屏能几十条),
   * 只留值得看的那些:组合换了(反封锁链真的动了)、或者试了不止一次。
   * 稳态下的成功一条都不写。
   */
  suspend fun record(record: DiagnosticRecord) {
    pushRunLog(record)
    if (!worthPersisting(record)) return
    runCatching {
      dataStore.edit { prefs ->
        prefs[KEY] = JSON.encodeToString(appendDiagnosticLog(decode(prefs[KEY]), record))
      }
    }
    // 写不进就算了:这是排障用的旁路,不能影响主流程
  }

  suspend fun clear() {
    runCatching { dataStore.edit { it.remove(KEY) } }
  }

  // ---------------------------------------------------------------- 本次运行(内存)

  /** 本次运行的一条请求落点。 */
  data class RunLogEntry(
    val at: Long,
    val path: String,
    /** **已脱敏**的参数 */
    val params: Map<String, String>,
    /** 成功时是落点摘要,失败时是最终错误 */
    val message: String,
    val ok: Boolean,
    /** 这一次链上真发出去了几次 HTTP */
    val attempts: Int,
  )

  @Volatile
  private var runLogEntries: List<RunLogEntry> = emptyList()

  /** 最新的在前。实验室页的「本次运行的请求」拿它渲染。 */
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
    val success = record.success ?: return true // 失败一律留
    val combo = "${success.format} @ ${success.host}"
    // 先记上再判:早退会让水位线停在旧值,下一条同组合的成功又被当成「换组合了」
    val previous = lastPersistedCombo.put(record.path, combo)
    return record.attempts.size > 1 || (previous != null && previous != combo)
  }

  private fun decode(raw: String?): List<String> = runCatching {
    if (raw == null) emptyList() else JSON.decodeFromString<List<String>>(raw)
  }.getOrDefault(emptyList())

  companion object {
    /** 换了存储结构就换 key,老日志自然作废,不用写迁移。 */
    private val KEY = stringPreferencesKey("diagnostics.log.v1")

    private const val RUN_LOG_LIMIT = 40

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
  }
}
