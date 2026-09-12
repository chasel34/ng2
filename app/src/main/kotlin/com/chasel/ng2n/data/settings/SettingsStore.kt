package com.chasel.ng2n.data.settings

import androidx.datastore.core.DataStore
import com.chasel.ng2n.di.SettingsPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsStore @Inject constructor(
  @SettingsPreferences private val dataStore: DataStore<Preferences>,
) {

  object Keys {
    val SETTINGS = stringPreferencesKey("settings.v1")
    val THEME_MODE = stringPreferencesKey("settings.themeMode.v1")
    val READ_PHP_WP_UA = booleanPreferencesKey("net.readPhpWindowsPhoneUa.v1")
    val WEB_FALLBACK_MODE = stringPreferencesKey("net.webFallbackMode.v1")
    val LOCAL_FILTER_RULES = stringPreferencesKey("filters/local-rules")
    val SEARCH_HISTORY = stringPreferencesKey("search/history")
    val BOARD_TREE = stringPreferencesKey("board-tree/v1")
    val BOARD_TREE_FETCHED_AT = stringPreferencesKey("board-tree/v1/fetchedAt")
    val DISMISSED_ANNOUNCEMENTS = stringPreferencesKey("announcements/dismissed")
    val CHECK_IN_DAYS = stringPreferencesKey("check-in.days.v1")
  }

  private fun favorIndexKey(uid: String) = stringPreferencesKey("topic-favor-index/v1/$uid")

  private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
    if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
    else throw cause
  }

  val settings: Flow<AppSettings> = preferences.map { prefs ->
    parseSettings(prefs[Keys.SETTINGS].toJsonOrNull())
  }

  suspend fun currentSettings(): AppSettings = settings.first()

  suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
    dataStore.edit { prefs ->
      val current = parseSettings(prefs[Keys.SETTINGS].toJsonOrNull())
      prefs[Keys.SETTINGS] = JSON.encodeToString(JsonElement.serializer(), transform(current).toJson())
    }
  }

  suspend fun setAppearance(spec: SliderSpec, value: Double) {
    updateSettings { it.copy(appearance = spec.set(it.appearance, clampSlider(spec, value))) }
  }

  suspend fun resetAll() {
    dataStore.edit { prefs ->
      prefs.remove(Keys.SETTINGS)
      prefs.remove(Keys.THEME_MODE)
      prefs.remove(Keys.READ_PHP_WP_UA)
      prefs.remove(Keys.WEB_FALLBACK_MODE)
    }
  }

  val themeMode: Flow<ThemeMode> = preferences.map { prefs ->
    ThemeMode.fromWire(prefs[Keys.THEME_MODE]) ?: ThemeMode.SYSTEM
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    dataStore.edit { it[Keys.THEME_MODE] = mode.wire }
  }

  val netSettings: Flow<NetSettings> = preferences.map { prefs ->
    NetSettings(
      readPhpWindowsPhoneUa = prefs[Keys.READ_PHP_WP_UA] ?: true,
      webFallbackMode = WebFallbackMode.fromWire(prefs[Keys.WEB_FALLBACK_MODE])
        ?: DEFAULT_WEB_FALLBACK_MODE,
    )
  }

  suspend fun currentNetSettings(): NetSettings = netSettings.first()

  suspend fun setReadPhpWindowsPhoneUa(enabled: Boolean) {
    dataStore.edit { it[Keys.READ_PHP_WP_UA] = enabled }
  }

  suspend fun setWebFallbackMode(mode: WebFallbackMode) {
    dataStore.edit { it[Keys.WEB_FALLBACK_MODE] = mode.wire }
  }

  val localFilterRules: Flow<List<FilterRule>> = preferences.map { prefs ->
    sanitizeFilterRules(decodeList(prefs[Keys.LOCAL_FILTER_RULES]))
  }

  suspend fun updateFilterRules(transform: (List<FilterRule>) -> List<FilterRule>) {
    dataStore.edit { prefs ->
      val current = sanitizeFilterRules(decodeList<FilterRule>(prefs[Keys.LOCAL_FILTER_RULES]))
      prefs[Keys.LOCAL_FILTER_RULES] = JSON.encodeToString(transform(current))
    }
  }

  val searchHistory: Flow<SearchHistory> = preferences.map { prefs ->
    sanitizeSearchHistory(decode(prefs[Keys.SEARCH_HISTORY]) ?: EMPTY_SEARCH_HISTORY)
  }

  suspend fun updateSearchHistory(transform: (SearchHistory) -> SearchHistory) {
    dataStore.edit { prefs ->
      val current = sanitizeSearchHistory(
        decode<SearchHistory>(prefs[Keys.SEARCH_HISTORY]) ?: EMPTY_SEARCH_HISTORY,
      )
      prefs[Keys.SEARCH_HISTORY] = JSON.encodeToString(transform(current))
    }
  }

  val boardTree: Flow<CachedBoardTree?> = preferences.map { prefs ->
    val payload = prefs[Keys.BOARD_TREE] ?: return@map null
    val fetchedAt = prefs[Keys.BOARD_TREE_FETCHED_AT]?.toLongOrNull() ?: return@map null
    CachedBoardTree(payload, fetchedAt)
  }

  suspend fun saveBoardTree(cached: CachedBoardTree) {
    dataStore.edit { prefs ->
      prefs[Keys.BOARD_TREE] = cached.payload
      prefs[Keys.BOARD_TREE_FETCHED_AT] = cached.fetchedAt.toString()
    }
  }

  val dismissedAnnouncements: Flow<List<String>> = preferences.map { prefs ->
    decodeList<String>(prefs[Keys.DISMISSED_ANNOUNCEMENTS]).takeLast(DISMISSED_ANNOUNCEMENTS_LIMIT)
  }

  suspend fun dismissAnnouncement(id: String) {
    dataStore.edit { prefs ->
      val current = decodeList<String>(prefs[Keys.DISMISSED_ANNOUNCEMENTS])
      prefs[Keys.DISMISSED_ANNOUNCEMENTS] =
        JSON.encodeToString(withDismissedAnnouncement(current, id))
    }
  }

  val checkInDays: Flow<CheckInDays> = preferences.map { prefs ->
    sanitizeCheckInDays(decode<Map<String, String>>(prefs[Keys.CHECK_IN_DAYS]) ?: emptyMap())
  }

  suspend fun currentCheckInDays(): CheckInDays = checkInDays.first()

  suspend fun markCheckedIn(uid: String, nowMs: Long) {
    dataStore.edit { prefs ->
      val current =
        sanitizeCheckInDays(decode<Map<String, String>>(prefs[Keys.CHECK_IN_DAYS]) ?: emptyMap())
      prefs[Keys.CHECK_IN_DAYS] = JSON.encodeToString(withCheckedIn(current, uid, nowMs))
    }
  }

  fun topicFavorIndex(uid: String?): Flow<TopicFavorIndex> =
    if (uid == null) kotlinx.coroutines.flow.flowOf(EMPTY_TOPIC_FAVOR_INDEX)
    else preferences.map { prefs -> parseTopicFavorIndex(prefs[favorIndexKey(uid)].toJsonOrNull()) }

  suspend fun currentTopicFavorIndex(uid: String?): TopicFavorIndex = topicFavorIndex(uid).first()

  suspend fun updateTopicFavorIndex(
    uid: String?,
    transform: (TopicFavorIndex) -> TopicFavorIndex,
  ) {
    if (uid == null) return
    val key = favorIndexKey(uid)
    dataStore.edit { prefs ->
      val current = parseTopicFavorIndex(prefs[key].toJsonOrNull())
      prefs[key] = JSON.encodeToString(JsonElement.serializer(), transform(current).toJson())
    }
  }

  companion object {
    const val FILE_NAME = "ng2n-settings"

    val JSON = Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
      explicitNulls = false
    }
  }
}

internal fun String?.toJsonOrNull(): JsonElement? = runCatching {
  if (this == null) null else SettingsStore.JSON.parseToJsonElement(this)
}.getOrNull()

internal inline fun <reified T> decode(raw: String?): T? = runCatching {
  if (raw == null) null else SettingsStore.JSON.decodeFromString<T>(raw)
}.getOrNull()

internal inline fun <reified T> decodeList(raw: String?): List<T> =
  decode<List<T>>(raw) ?: emptyList()
