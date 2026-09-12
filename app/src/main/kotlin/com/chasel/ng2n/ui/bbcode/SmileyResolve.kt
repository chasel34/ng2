package com.chasel.ng2n.ui.bbcode

private const val DEFAULT_CATEGORY = "0"

const val SMILEY_ASSET_BASE: String = "file:///android_asset/smilies"

sealed interface ResolvedSmiley {
  sealed interface Known : ResolvedSmiley {
    val category: String
    val label: String
    val name: String
    val file: String
  }

  data class Bundled(
    override val category: String,
    override val label: String,
    override val name: String,
    override val file: String,
  ) : Known {
    val assetUrl: String get() = "$SMILEY_ASSET_BASE/$file"
  }

  data class Remote(
    override val category: String,
    override val label: String,
    override val name: String,
    override val file: String,
  ) : Known {
    val remoteUrl: String get() = "$SMILEY_BASE_URL/$file"
  }

  data class Unresolved(val raw: String) : ResolvedSmiley
}

private val SMILEY_INDEX: Map<String, Pair<String, Map<String, String>>> =
  SMILEY_CATEGORIES.associate { category ->
    category.key to (category.label to category.entries.toMap())
  }

private val DIGITS = Regex("""^\d+$""")

fun resolveSmiley(
  code: String,
  bundledFiles: Set<String> = BUNDLED_SMILEY_FILES,
): ResolvedSmiley {
  val unresolved = ResolvedSmiley.Unresolved("[s:$code]")

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
