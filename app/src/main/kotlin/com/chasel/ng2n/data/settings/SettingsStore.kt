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

/**
 * 承接 RN 版 MMKV(id `ng2`)**全部键**的 Preferences DataStore。
 *
 * ## 键名一一对照(RN → 这里)
 *
 * | MMKV key | 这里 | 内容 |
 * |---|---|---|
 * | `settings.v1` | [Keys.SETTINGS] | 整张设置表(一条 JSON) |
 * | `settings.themeMode.v1` | [Keys.THEME_MODE] | 夜间模式档位 |
 * | `net.readPhpWindowsPhoneUa.v1` | [Keys.READ_PHP_WP_UA] | read.php 的 UA 档位 |
 * | `net.webFallbackMode.v1` | [Keys.WEB_FALLBACK_MODE] | Web 反解四档 |
 * | `filters/local-rules` | [Keys.LOCAL_FILTER_RULES] | 本地屏蔽规则 |
 * | `search/history` | [Keys.SEARCH_HISTORY] | 三 tab 各 20 条搜索历史 |
 * | `board-tree/v1` | [Keys.BOARD_TREE] / [Keys.BOARD_TREE_FETCHED_AT] | 版块树缓存(24h SWR) |
 * | `announcements/dismissed` | [Keys.DISMISSED_ANNOUNCEMENTS] | 已读公告 |
 * | `check-in.days.v1` | [Keys.CHECK_IN_DAYS] | 签到日(uid → UTC+8 日期) |
 * | `diagnostics.log.v1` | 见 `DiagnosticLogStore` | 诊断日志 50 条(**已脱敏**) |
 * | `topic-favor-index/v1/<uid>` | [favorIndexKey] | **按 uid 分键**的收藏反向索引 |
 * | `bbcode/image-sizes.v1` | —— | **不在这里**:图片尺寸缓存归**票 12**,它自己落 JSON 文件 |
 *
 * ## 换结构就换 key
 *
 * 键名带版本尾巴,改结构就换成 `.v2`,老键留在文件里没人读,下次「恢复默认」清掉。
 * 不写迁移(照抄 RN 版明文策略)。
 *
 * ## 逐字段容错
 *
 * 每个 accessor 都在自己的 `try` 里:某一项被写坏了只丢那一项,不整份作废。
 * 整张设置表更是逐字段回落([parseSettings])—— 加了新设置项的版本读旧存档时老项要留着。
 *
 * ## 修 P2-04
 *
 * DataStore 天生只有 suspend / Flow,**没有同步读的口子**;
 * 冷启动路径上想同步读磁盘也读不着。
 */
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

  /**
   * 收藏反向索引**按 uid 分键**(修 P1-02 的存储侧)。
   * 收藏夹是云端按账号分的,两个账号的夹 id 混在一起会串;游客态没有收藏夹。
   */
  private fun favorIndexKey(uid: String) = stringPreferencesKey("topic-favor-index/v1/$uid")

  /**
   * 底层 Preferences 流。**IOException 折成空 Preferences** ——
   * 存档坏了/磁盘读不了不该让 app 卡在启动那一步(审计 P2-04 的「缺少降级」)。
   */
  private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
    if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
    else throw cause
  }

  // ---------------------------------------------------------------- 整张设置表

  /** 整张设置表的订阅口。 */
  val settings: Flow<AppSettings> = preferences.map { prefs ->
    parseSettings(prefs[Keys.SETTINGS].toJsonOrNull())
  }

  suspend fun currentSettings(): AppSettings = settings.first()

  /** 改一项。设置项之间互不影响,所以统一走一个入口而不是十几个 setter。 */
  suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
    dataStore.edit { prefs ->
      val current = parseSettings(prefs[Keys.SETTINGS].toJsonOrNull())
      prefs[Keys.SETTINGS] = JSON.encodeToString(JsonElement.serializer(), transform(current).toJson())
    }
  }

  /** 改一根滑杆(值会先量化到步长)。 */
  suspend fun setAppearance(spec: SliderSpec, value: Double) {
    updateSettings { it.copy(appearance = spec.set(it.appearance, clampSlider(spec, value))) }
  }

  /**
   * 恢复默认:设置表 + 夜间模式 + 网络层那两项一起回默认。
   * **不动**历史/缓存/账号 —— 那三样各有各的清除入口。
   */
  suspend fun resetAll() {
    dataStore.edit { prefs ->
      prefs.remove(Keys.SETTINGS)
      prefs.remove(Keys.THEME_MODE)
      prefs.remove(Keys.READ_PHP_WP_UA)
      prefs.remove(Keys.WEB_FALLBACK_MODE)
    }
  }

  // ---------------------------------------------------------------- 夜间模式

  val themeMode: Flow<ThemeMode> = preferences.map { prefs ->
    ThemeMode.fromWire(prefs[Keys.THEME_MODE]) ?: ThemeMode.SYSTEM
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    dataStore.edit { it[Keys.THEME_MODE] = mode.wire }
  }

  // ---------------------------------------------------------------- 网络开关

  /**
   * 反封锁链每次请求现取的两个开关。
   * `readPhpWindowsPhoneUa` **默认开**(ADR-0002:MNGA 强制用它,实测更不容易被封)。
   */
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

  // ---------------------------------------------------------------- 本地屏蔽规则

  val localFilterRules: Flow<List<FilterRule>> = preferences.map { prefs ->
    sanitizeFilterRules(decodeList(prefs[Keys.LOCAL_FILTER_RULES]))
  }

  suspend fun updateFilterRules(transform: (List<FilterRule>) -> List<FilterRule>) {
    dataStore.edit { prefs ->
      val current = sanitizeFilterRules(decodeList<FilterRule>(prefs[Keys.LOCAL_FILTER_RULES]))
      prefs[Keys.LOCAL_FILTER_RULES] = JSON.encodeToString(transform(current))
    }
  }

  // ---------------------------------------------------------------- 搜索历史

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

  // ---------------------------------------------------------------- 版块树缓存(24h SWR)

  /**
   * 版块树缓存。载荷是票 16 序列化出来的 JSON,这里不解释它的形状 ——
   * 只管存、只管判 [isBoardTreeStale]。
   */
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

  // ---------------------------------------------------------------- 已读公告

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

  // ---------------------------------------------------------------- 签到日期

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

  // ---------------------------------------------------------------- 收藏反向索引(按 uid)

  /** 游客态没有收藏夹,索引恒为空。 */
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
    /** DataStore 文件名(`<files>/datastore/ng2n-settings.preferences_pb`)。 */
    const val FILE_NAME = "ng2n-settings"

    /**
     * 存档一律当外部输入:未知字段忽略、宽松语法。
     * 坏值的回落是逐字段做的([parseSettings] / `sanitize*`),不是靠这里。
     */
    val JSON = Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
      explicitNulls = false
    }
  }
}

/** 字符串 → JsonElement;不是合法 JSON 就当没存过(**坏值不抛**)。 */
internal fun String?.toJsonOrNull(): JsonElement? = runCatching {
  if (this == null) null else SettingsStore.JSON.parseToJsonElement(this)
}.getOrNull()

internal inline fun <reified T> decode(raw: String?): T? = runCatching {
  if (raw == null) null else SettingsStore.JSON.decodeFromString<T>(raw)
}.getOrNull()

internal inline fun <reified T> decodeList(raw: String?): List<T> =
  decode<List<T>>(raw) ?: emptyList()
