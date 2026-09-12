package com.chasel.ng2n.ui.common

import com.chasel.ng2n.ui.about.AboutKeys
import com.chasel.ng2n.ui.filters.FiltersKeys
import com.chasel.ng2n.ui.settings.FontSizeKeys
import com.chasel.ng2n.ui.settings.LabKeys
import com.chasel.ng2n.ui.settings.SettingsKeys

internal object ListKeys {
  const val SUB = "sub"

  const val TAIL = "tail"

  const val FOOTER = "footer"

  const val EMPTY = "empty"

  const val HINT = "hint"

  const val HEAD = "head"

  const val SUB_BOARDS = "sub-boards"

  const val HEADER = "header"

  const val INTRO = "intro"

  const val FOOTNOTE = "footnote"

  fun groupHead(kind: Any): String = "$HEAD/$kind"
}

internal val SCREEN_LAZY_KEYS: Map<String, List<String>> = mapOf(
  "about" to AboutKeys.all,
  "settings" to SettingsKeys.all,
  "lab" to LabKeys.all,
  "font-size" to FontSizeKeys.all,
  "filters" to FiltersKeys.all,
  "board" to listOf(ListKeys.HEAD, ListKeys.SUB_BOARDS, ListKeys.FOOTER),
  "history" to listOf(ListKeys.SUB, ListKeys.TAIL),
  "caches" to listOf(ListKeys.SUB, ListKeys.TAIL),
  "favorite-folders" to listOf(ListKeys.EMPTY, ListKeys.HINT),
  "notifications" to listOf(ListKeys.TAIL),
  "topic" to listOf(ListKeys.HEADER, ListKeys.FOOTER),
)

internal fun duplicateKeys(keys: List<Any>): List<Any> {
  val seen = HashSet<Any>()
  val duplicates = LinkedHashSet<Any>()
  for (key in keys) if (!seen.add(key)) duplicates.add(key)
  return duplicates.toList()
}
