package com.chasel.ng2n.data.ai.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chasel.ng2n.di.SettingsPreferences
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

enum class AnalysisAllowance(val wire: String, val label: String) {
  SHORT("short", "短问答"),
  DEFAULT("default", "默认"),
  LONG("long", "长楼与个人分析"),
  HIGHER("higher", "更高"),
}

data class AiSettings(
  val allowance: AnalysisAllowance = AnalysisAllowance.DEFAULT,
  val dailyEnabled: Boolean = false,
  val dailyLimitFen: Long? = null,
)

fun parseDailyLimitFen(text: String): Long? = runCatching {
  require(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(text.trim()))
  BigDecimal(text.trim()).movePointRight(2).longValueExact().also { require(it > 0) }
}.getOrNull()

fun formatCny(fen: Long): String = "¥" + BigDecimal.valueOf(fen, 2).toPlainString()

@Singleton
class AiSettingsStore @Inject constructor(
  @SettingsPreferences private val dataStore: DataStore<Preferences>,
) {
  object Keys {
    val ALLOWANCE = stringPreferencesKey("ai.analysisAllowance.v1")
    val DAILY_ENABLED = booleanPreferencesKey("ai.dailyEnabled.v1")
    val DAILY_LIMIT_FEN = longPreferencesKey("ai.dailyLimitFen.v1")
  }

  val settings = dataStore.data.map { prefs ->
    val limit = prefs[Keys.DAILY_LIMIT_FEN]?.takeIf { it > 0 }
    AiSettings(
      allowance = AnalysisAllowance.entries.find { it.wire == prefs[Keys.ALLOWANCE] }
        ?: AnalysisAllowance.DEFAULT,
      dailyEnabled = prefs[Keys.DAILY_ENABLED] == true && limit != null,
      dailyLimitFen = limit,
    )
  }

  suspend fun setAllowance(value: AnalysisAllowance) {
    dataStore.edit { it[Keys.ALLOWANCE] = value.wire }
  }

  suspend fun setDailyEnabled(enabled: Boolean) {
    dataStore.edit {
      require(!enabled || (it[Keys.DAILY_LIMIT_FEN] ?: 0) > 0)
      it[Keys.DAILY_ENABLED] = enabled
    }
  }

  suspend fun setDailyLimit(fen: Long) {
    require(fen > 0)
    dataStore.edit {
      it[Keys.DAILY_LIMIT_FEN] = fen
      it[Keys.DAILY_ENABLED] = true
    }
  }
}
