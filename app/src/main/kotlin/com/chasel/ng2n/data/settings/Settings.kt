package com.chasel.ng2n.data.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val NGA_HOSTS: List<String> = com.chasel.ng2n.core.net.NGA_HOSTS

val DEFAULT_NGA_HOST: String = com.chasel.ng2n.core.net.DEFAULT_NGA_HOST

enum class ImageQuality(val wire: String, val label: String) {
  ORIGINAL("original", "总是原图"),
  SMART("smart", "智能(Wi-Fi 原图 / 流量缩略图)"),
  THUMBNAIL("thumbnail", "总是缩略图"),
  ;

  companion object {
    fun fromWire(value: String?): ImageQuality? = entries.firstOrNull { it.wire == value }
  }
}

enum class ThemeStyle(val wire: String, val label: String) {
  INK("ink", "墨绿(NGA 经典)"),
  PLAIN("plain", "纯白"),
  ;

  companion object {
    fun fromWire(value: String?): ThemeStyle? = entries.firstOrNull { it.wire == value }
  }
}

enum class ThemeMode(val wire: String) {
  SYSTEM("system"), LIGHT("light"), DARK("dark");

  companion object {
    fun fromWire(value: String?): ThemeMode? = entries.firstOrNull { it.wire == value }
  }
}

typealias WebFallbackMode = com.chasel.ng2n.core.net.WebFallbackMode

val DEFAULT_WEB_FALLBACK_MODE: WebFallbackMode = com.chasel.ng2n.core.net.DEFAULT_WEB_FALLBACK_MODE

data class AppearanceSettings(
  val listFontSize: Double = 17.0,
  val avatarScale: Double = 100.0,
  val smileyScale: Double = 150.0,
  val bodyFontSize: Double = 15.5,
  val bodyLineHeight: Double = 1.68,
)

data class AppSettings(
  val host: String = DEFAULT_NGA_HOST,
  val leftHanded: Boolean = false,
  val solidBackground: Boolean = false,
  val sprayNotice: Boolean = true,
  val noticeSound: Boolean = true,
  val themeStyle: ThemeStyle = ThemeStyle.INK,
  val autoLoadNextPage: Boolean = true,
  val wifiOnlyImages: Boolean = true,
  val showSignature: Boolean = true,
  val imageQuality: ImageQuality = ImageQuality.SMART,
  val gestureBack: Boolean = true,
  val keepScreenOn: Boolean = false,
  val appearance: AppearanceSettings = AppearanceSettings(),
)

val DEFAULT_SETTINGS = AppSettings()

data class NetSettings(
  val readPhpWindowsPhoneUa: Boolean = true,
  val webFallbackMode: WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE,
)

const val AVATAR_BASE_SIZE = 42

const val SMILEY_BASE_HEIGHT = 16

data class SliderSpec(
  val key: String,
  val label: String,
  val min: Double,
  val max: Double,
  val step: Double,
  val decimals: Int,
  val unit: String,
  val get: (AppearanceSettings) -> Double,
  val set: (AppearanceSettings, Double) -> AppearanceSettings,
)

val APPEARANCE_SLIDERS: List<SliderSpec> = listOf(
  SliderSpec("listFontSize", "帖子列表字体大小", 12.0, 26.0, 1.0, 0, "",
    { it.listFontSize }, { s, v -> s.copy(listFontSize = v) }),
  SliderSpec("avatarScale", "头像大小", 60.0, 160.0, 4.0, 0, "%",
    { it.avatarScale }, { s, v -> s.copy(avatarScale = v) }),
  SliderSpec("smileyScale", "表情大小", 80.0, 220.0, 10.0, 0, "%",
    { it.smileyScale }, { s, v -> s.copy(smileyScale = v) }),
  SliderSpec("bodyFontSize", "帖子内字体大小", 12.0, 22.0, 0.5, 1, "",
    { it.bodyFontSize }, { s, v -> s.copy(bodyFontSize = v) }),
  SliderSpec("bodyLineHeight", "主题详情页行高", 1.3, 2.2, 0.02, 2, "",
    { it.bodyLineHeight }, { s, v -> s.copy(bodyLineHeight = v) }),
)

fun sliderSpec(key: String): SliderSpec =
  APPEARANCE_SLIDERS.firstOrNull { it.key == key } ?: error("没有这根滑杆:$key")

fun clampSlider(spec: SliderSpec, value: Double): Double {
  if (value.isNaN() || value.isInfinite()) return spec.get(DEFAULT_SETTINGS.appearance)
  val steps = Math.round((value - spec.min) / spec.step)
  val snapped = spec.min + steps * spec.step
  val bounded = min(spec.max, max(spec.min, snapped))
  return roundToDecimals(bounded, spec.decimals)
}

fun sliderRatio(spec: SliderSpec, value: Double): Double =
  min(1.0, max(0.0, (value - spec.min) / (spec.max - spec.min)))

fun sliderValueAt(spec: SliderSpec, ratio: Double): Double =
  clampSlider(spec, spec.min + ratio * (spec.max - spec.min))

fun formatSliderValue(spec: SliderSpec, value: Double): String =
  String.format(Locale.ROOT, "%.${spec.decimals}f", value) + spec.unit

fun avatarSizeOf(scale: Double): Int = (AVATAR_BASE_SIZE * scale / 100.0).roundToInt()

fun smileyHeightOf(scale: Double): Int = (SMILEY_BASE_HEIGHT * scale / 100.0).roundToInt()

private fun roundToDecimals(value: Double, decimals: Int): Double =
  String.format(Locale.ROOT, "%.${decimals}f", value).toDouble()

private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean =
  (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fallback

private fun JsonObject.stringOrNull(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun parseSettings(raw: JsonElement?): AppSettings {
  val obj = raw as? JsonObject ?: return DEFAULT_SETTINGS
  return AppSettings(
    host = obj.stringOrNull("host")?.takeIf { it in NGA_HOSTS } ?: DEFAULT_SETTINGS.host,
    leftHanded = obj.boolOr("leftHanded", DEFAULT_SETTINGS.leftHanded),
    solidBackground = obj.boolOr("solidBackground", DEFAULT_SETTINGS.solidBackground),
    sprayNotice = obj.boolOr("sprayNotice", DEFAULT_SETTINGS.sprayNotice),
    noticeSound = obj.boolOr("noticeSound", DEFAULT_SETTINGS.noticeSound),
    themeStyle = ThemeStyle.fromWire(obj.stringOrNull("themeStyle")) ?: DEFAULT_SETTINGS.themeStyle,
    autoLoadNextPage = obj.boolOr("autoLoadNextPage", DEFAULT_SETTINGS.autoLoadNextPage),
    wifiOnlyImages = obj.boolOr("wifiOnlyImages", DEFAULT_SETTINGS.wifiOnlyImages),
    showSignature = obj.boolOr("showSignature", DEFAULT_SETTINGS.showSignature),
    imageQuality = ImageQuality.fromWire(obj.stringOrNull("imageQuality"))
      ?: DEFAULT_SETTINGS.imageQuality,
    gestureBack = obj.boolOr("gestureBack", DEFAULT_SETTINGS.gestureBack),
    keepScreenOn = obj.boolOr("keepScreenOn", DEFAULT_SETTINGS.keepScreenOn),
    appearance = parseAppearance(obj["appearance"]),
  )
}

fun parseAppearance(raw: JsonElement?): AppearanceSettings {
  val obj = raw as? JsonObject ?: return DEFAULT_SETTINGS.appearance
  var next = DEFAULT_SETTINGS.appearance
  for (spec in APPEARANCE_SLIDERS) {
    val value = (obj[spec.key] as? JsonPrimitive)
      ?.takeIf { !it.isString }
      ?.doubleOrNull
      ?: continue
    next = spec.set(next, clampSlider(spec, value))
  }
  return next
}

fun AppSettings.toJson(): JsonObject = JsonObject(
  mapOf(
    "host" to JsonPrimitive(host),
    "leftHanded" to JsonPrimitive(leftHanded),
    "solidBackground" to JsonPrimitive(solidBackground),
    "sprayNotice" to JsonPrimitive(sprayNotice),
    "noticeSound" to JsonPrimitive(noticeSound),
    "themeStyle" to JsonPrimitive(themeStyle.wire),
    "autoLoadNextPage" to JsonPrimitive(autoLoadNextPage),
    "wifiOnlyImages" to JsonPrimitive(wifiOnlyImages),
    "showSignature" to JsonPrimitive(showSignature),
    "imageQuality" to JsonPrimitive(imageQuality.wire),
    "gestureBack" to JsonPrimitive(gestureBack),
    "keepScreenOn" to JsonPrimitive(keepScreenOn),
    "appearance" to JsonObject(
      APPEARANCE_SLIDERS.associate { it.key to JsonPrimitive(it.get(appearance)) },
    ),
  ),
)
