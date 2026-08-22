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

/**
 * 设置项的取值与默认值 —— `src/core/local/settings.ts` 的直译。
 *
 * **纯 Kotlin,零 Android 依赖**:默认值表、滑杆的量化与格式化、坏存档的回落都在这里;
 * `SettingsStore` 只负责把 [AppSettings] 塞进 Preferences DataStore 再读回来。
 *
 * 不在这个数据类里的三组设置(RN 版同样分开住,照抄):
 * - 夜间模式档位 [ThemeMode] —— 它自己一个 key,「恢复默认」时一起回默认;
 * - `readPhpWindowsPhoneUa` / `webFallbackMode` —— 它们要被反封锁链**每次请求现取**,
 *   跟着请求层走比跟着设置表走更合适(见 [NetSettings])。
 */

/**
 * NGA 官方域名(API 文档 §0.1,第一个是默认域名)。
 *
 * 票 14 暂居于此:`core/net` 的常量表归票 03/06/07,它们落地后这里改成引用,
 * 不要两份各自维护。校验只用到「必须是表里的一个」。
 */
val NGA_HOSTS: List<String> = listOf(
  "https://bbs.nga.cn",
  "https://ngabbs.com",
  "https://bbs.ngacn.cc",
  "https://nga.178.com",
  "https://nga.donews.com",
)

val DEFAULT_NGA_HOST: String = NGA_HOSTS[0]

/** 正文图片按哪一档清晰度取(设计稿「图片加载策略」)。 */
enum class ImageQuality(val wire: String, val label: String) {
  ORIGINAL("original", "总是原图"),
  SMART("smart", "智能(Wi-Fi 原图 / 流量缩略图)"),
  THUMBNAIL("thumbnail", "总是缩略图"),
  ;

  companion object {
    fun fromWire(value: String?): ImageQuality? = entries.firstOrNull { it.wire == value }
  }
}

/**
 * 主题风格(设计稿「主题风格」对话框的前两项)。
 *
 * 第三项「夜间近黑」在设计稿里的副标题就写着「跟随夜间模式开关」—— 它不是第三套配色,
 * 而是夜间模式本身,所以不进这个枚举,选它等于把夜间模式打开。
 */
enum class ThemeStyle(val wire: String, val label: String) {
  INK("ink", "墨绿(NGA 经典)"),
  PLAIN("plain", "纯白"),
  ;

  companion object {
    fun fromWire(value: String?): ThemeStyle? = entries.firstOrNull { it.wire == value }
  }
}

/** 夜间模式档位。`SYSTEM` 表示跟随系统深浅色。 */
enum class ThemeMode(val wire: String) {
  SYSTEM("system"), LIGHT("light"), DARK("dark");

  companion object {
    fun fromWire(value: String?): ThemeMode? = entries.firstOrNull { it.wire == value }
  }
}

/** Web 反解档位(ADR-0002 / API 文档 §0.8 的四档)。 */
enum class WebFallbackMode(val wire: String) {
  DISABLED("disabled"), SECONDARY("secondary"), PRIMARY("primary"), ONLY("only");

  companion object {
    fun fromWire(value: String?): WebFallbackMode? = entries.firstOrNull { it.wire == value }
  }
}

/** 默认 Web 反解档位:排在换账号之后,原生接口全垮了才去反解网页版。 */
val DEFAULT_WEB_FALLBACK_MODE = WebFallbackMode.SECONDARY

/** 字体与头像大小(设计稿「字体和头像大小」屏的五根滑杆)。 */
data class AppearanceSettings(
  /** 主题列表的标题字号 */
  val listFontSize: Double = 17.0,
  /** 头像大小,百分比(100 = token 里的 42) */
  val avatarScale: Double = 100.0,
  /** 表情大小,百分比(150 = 现行的 24 高) */
  val smileyScale: Double = 150.0,
  /** 楼层正文字号 */
  val bodyFontSize: Double = 15.5,
  /** 楼层正文行高倍数 */
  val bodyLineHeight: Double = 1.68,
)

/**
 * 设置表。默认值照设计稿 `SWDEF`,只有三项按 RN 版的现状取值
 * (`solidBackground` 默认关、`keepScreenOn` 默认关、五根滑杆取 tokens 的真实档位)。
 */
data class AppSettings(
  /** 请求用的 NGA 域名,必须是 [NGA_HOSTS] 里的一个 */
  val host: String = DEFAULT_NGA_HOST,
  /** 左手模式:FAB 与菜单移到左侧 */
  val leftHanded: Boolean = false,
  /** 主题列表与详情页用纯色底(surface)而不是奶油底(bg) */
  val solidBackground: Boolean = false,
  /** 被喷提示:关掉后通知不再轮询,抽屉也不显示未读角标 */
  val sprayNotice: Boolean = true,
  /** 提示声音。没有声音钩子可接,只存值 */
  val noticeSound: Boolean = true,
  val themeStyle: ThemeStyle = ThemeStyle.INK,
  /** 滚到底自动翻下一页 */
  val autoLoadNextPage: Boolean = true,
  /** 移动网络下不自动拉图,正文图与附件都折成「点击显示」 */
  val wifiOnlyImages: Boolean = true,
  /** 楼层里显示签名档 */
  val showSignature: Boolean = true,
  val imageQuality: ImageQuality = ImageQuality.SMART,
  /** 从左边缘右滑返回上一页 */
  val gestureBack: Boolean = true,
  /** 读帖时屏幕常亮 */
  val keepScreenOn: Boolean = false,
  val appearance: AppearanceSettings = AppearanceSettings(),
)

val DEFAULT_SETTINGS = AppSettings()

/** 网络层的两个开关。反封锁链每次请求现取,所以与设置表分开住。 */
data class NetSettings(
  /**
   * `read.php` 改用 Windows Phone UA(ADR-0002:MNGA 强制用它,实测更不容易被封)。
   * **默认开** —— RN 版 07 票起就一直用这一档并已过真机验收。
   */
  val readPhpWindowsPhoneUa: Boolean = true,
  val webFallbackMode: WebFallbackMode = DEFAULT_WEB_FALLBACK_MODE,
)

/** 头像百分比的基准值。 */
const val AVATAR_BASE_SIZE = 42

/** 表情百分比的基准值:150% = 现行的 24 高。 */
const val SMILEY_BASE_HEIGHT = 16

/** 一根滑杆的量程与步长。 */
data class SliderSpec(
  val key: String,
  val label: String,
  val min: Double,
  val max: Double,
  val step: Double,
  /** 显示与量化保留的小数位;整数档为 0 */
  val decimals: Int,
  /** 值旁边的单位后缀,没有就留空 */
  val unit: String,
  val get: (AppearanceSettings) -> Double,
  val set: (AppearanceSettings, Double) -> AppearanceSettings,
)

/** 五根滑杆的量程与步长照抄设计稿 `T.fontSliders`。 */
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

/**
 * 量化到步长并夹进量程。浮点步长(行高 0.02)必须按小数位收尾,
 * 否则会攒出 1.7000000000000002。
 */
fun clampSlider(spec: SliderSpec, value: Double): Double {
  if (value.isNaN() || value.isInfinite()) return spec.get(DEFAULT_SETTINGS.appearance)
  val steps = Math.round((value - spec.min) / spec.step)
  val snapped = spec.min + steps * spec.step
  val bounded = min(spec.max, max(spec.min, snapped))
  return roundToDecimals(bounded, spec.decimals)
}

/** 滑杆填充比例 0–1(轨道与气泡的位置都按它算)。 */
fun sliderRatio(spec: SliderSpec, value: Double): Double =
  min(1.0, max(0.0, (value - spec.min) / (spec.max - spec.min)))

/** 把 0–1 的落点换回设置值(拖动手势报的就是比例)。 */
fun sliderValueAt(spec: SliderSpec, ratio: Double): Double =
  clampSlider(spec, spec.min + ratio * (spec.max - spec.min))

/** 气泡里显示的值,整数档不带小数点。 */
fun formatSliderValue(spec: SliderSpec, value: Double): String =
  String.format(Locale.ROOT, "%.${spec.decimals}f", value) + spec.unit

/** 百分比档换算成实际像素。 */
fun avatarSizeOf(scale: Double): Int = (AVATAR_BASE_SIZE * scale / 100.0).roundToInt()

fun smileyHeightOf(scale: Double): Int = (SMILEY_BASE_HEIGHT * scale / 100.0).roundToInt()

/** `Number(value.toFixed(decimals))` 的等价物:按小数位收尾再变回 Double。 */
private fun roundToDecimals(value: Double, decimals: Int): Double =
  String.format(Locale.ROOT, "%.${decimals}f", value).toDouble()

private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean =
  (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fallback

private fun JsonObject.stringOrNull(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 把存档还原成设置表。**逐项回落**而不是整份作废:加了新设置项的版本读旧存档时,
 * 老项该留着;某一项被写坏了也只丢那一项。
 *
 * 这条是从 RN 版原样搬过来的规矩,并且是票 14 的验收面之一 ——
 * 「设置逐字段容错解析」在 research/inventory.md §7 里被单独点名。
 */
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

/** 存档形态。字段名与 RN 版 `settings.v1` 一致(结构变了就换 key,不写迁移)。 */
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
