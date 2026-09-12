package com.chasel.ng2n.ui.bbcode

/**
 * `[s:...]` 的解析(RN 侧原件 `src/core/smilies/resolve.ts`)。
 *
 * 分类与名称的切法照抄 NGA 官方 `js_bbscode_core.js` 的 `[smile]` 分支:
 * 纯数字走默认套;否则按 `:` 切开取前两段当「分类」「名称」,分类为空退回默认套。
 *
 * 三级兜底:随包图片 → CDN 远程 URL → 原文。
 */

/** 默认套的 key。官方表里它就叫 `0`,`[s:数字]` 查的是它。 */
private const val DEFAULT_CATEGORY = "0"

/** 随包图片在 assets 里的目录。Coil 认 `file:///android_asset/…` 这个 scheme。 */
const val SMILEY_ASSET_BASE: String = "file:///android_asset/smilies"

sealed interface ResolvedSmiley {
  /** 命中映射表(不论图片随没随包)。 */
  sealed interface Known : ResolvedSmiley {
    val category: String
    val label: String
    val name: String
    val file: String
  }

  /** 图片已随包,走 assets。 */
  data class Bundled(
    override val category: String,
    override val label: String,
    override val name: String,
    override val file: String,
  ) : Known {
    val assetUrl: String get() = "$SMILEY_ASSET_BASE/$file"
  }

  /** 表里有但图片没随包(官方新加的、或下载缺失),走 CDN。 */
  data class Remote(
    override val category: String,
    override val label: String,
    override val name: String,
    override val file: String,
  ) : Known {
    val remoteUrl: String get() = "$SMILEY_BASE_URL/$file"
  }

  /** 映射表里查不到,原样显示 BBCode 文本。 */
  data class Unresolved(val raw: String) : ResolvedSmiley
}

/** key → (label, 名称 → 文件名)。建一次,查表 O(1)。 */
private val SMILEY_INDEX: Map<String, Pair<String, Map<String, String>>> =
  SMILEY_CATEGORIES.associate { category ->
    category.key to (category.label to category.entries.toMap())
  }

private val DIGITS = Regex("""^\d+$""")

/**
 * 解析 `[s:` 与 `]` 之间的原文。
 *
 * @param code 如 `ac:笑`、`123`
 * @param bundledFiles 判定「图片已随包」的文件名集合;换一份只为让单测能构造
 *   「表里有、包里没有」的回退场景。
 */
fun resolveSmiley(
  code: String,
  bundledFiles: Set<String> = BUNDLED_SMILEY_FILES,
): ResolvedSmiley {
  val unresolved = ResolvedSmiley.Unresolved("[s:$code]")

  // 官方用 `parseInt(code,10)` 判定数字套,所以 `[s:0]` 落不进来(取值为假)。
  val asNumber = DIGITS.matches(code) && (code.toLongOrNull() ?: 0L) > 0L
  val parts = code.split(':')
  val categoryKey = if (asNumber) DEFAULT_CATEGORY else parts[0].ifEmpty { DEFAULT_CATEGORY }
  val name = if (asNumber) code else parts.getOrElse(1) { "" }

  val category = SMILEY_INDEX[categoryKey] ?: return unresolved
  val file = if (name.isEmpty()) null else category.second[name]
  if (file == null) return unresolved

  val (label, _) = category
  return if (file in bundledFiles) {
    ResolvedSmiley.Bundled(category = categoryKey, label = label, name = name, file = file)
  } else {
    ResolvedSmiley.Remote(category = categoryKey, label = label, name = name, file = file)
  }
}
