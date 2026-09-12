package com.chasel.ng2n.ui.common

import com.chasel.ng2n.ui.about.AboutKeys
import com.chasel.ng2n.ui.filters.FiltersKeys
import com.chasel.ng2n.ui.settings.FontSizeKeys
import com.chasel.ng2n.ui.settings.LabKeys
import com.chasel.ng2n.ui.settings.SettingsKeys

/**
 * 列表屏里那些**不是数据行**的项(小标题条、页脚、空态、分组头)共用的 key。
 *
 * 出处是票 28:关于屏把「免责声明」那一行的 key 和页脚那段免责文案写成了同一个
 * `disclaimer`,Compose 在首次布局就抛 `Key "disclaimer" was already used`,
 * 进程当场没了 —— 重复 key 不是警告,是必崩。两处各写各的字面量时人眼看不出撞车,
 * 摊成常量 + [SCREEN_LAZY_KEYS] 的单测才拦得住。
 *
 * **数据行的 key 不放这里**:它们是 tid / pid / 规则 id 这类从数据里来的值。
 * 与本文件的字面量撞车只有一种情况 —— 数据行的 key 也是字符串、而且可能正好等于这些
 * 字面量(云端屏蔽词就是:用户可以把「hint」加成屏蔽词),那一侧一律带前缀,
 * 见 `FiltersKeys.word`。
 */
internal object ListKeys {
  /** 列表顶上那条灰字小标题(「本机记录 · 保留最近 200 条」这种) */
  const val SUB = "sub"

  /** 列表末尾的留白(导航栏 inset) */
  const val TAIL = "tail"

  /** 分页列表末尾那块「正在载入第 N 页 / 这一页失败了」 */
  const val FOOTER = "footer"

  /** 铺在列表里(而不是整屏)的空态 */
  const val EMPTY = "empty"

  /** 列表里的一段说明文字 */
  const val HINT = "hint"

  /** 版块页顶上的版头行 */
  const val HEAD = "head"

  /** 版块页顶上的子版块横条 */
  const val SUB_BOARDS = "sub-boards"

  /** 列表顶上的整块表头(主题详情的主楼卡片这种) */
  const val HEADER = "header"

  /** 回复链屏顶上那段说明 */
  const val INTRO = "intro"

  /** 子版块屏末尾的脚注 */
  const val FOOTNOTE = "footnote"

  /** 通知屏的分组头。带 `head/` 前缀,与通知条目的稳定 id 不可能相等 */
  fun groupHead(kind: Any): String = "$HEAD/$kind"
}

/**
 * 各屏 Lazy 列表的**静态 key 清单**,给 `ScreenListKeysTest` 逐屏查重。
 *
 * 只收「同一个 Lazy 列表里有两个及以上静态 key」的屏 —— 只有一个静态 key 的屏
 * (搜索结果 / 收藏 / 热帖 / 精华区的 `footer`、子版块屏的 `footnote`、回复链的 `intro`)
 * 自己跟自己撞不了,而它们的数据行 key 是 tid / pid(数字),与字符串 key 也撞不上。
 *
 * 加/改屏上的项时连着这份清单一起改 —— 清单与屏对不上时单测拦不住,真机会崩。
 */
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

/** 一张 key 表里重复出现的那些 key(空列表 = 没重复)。 */
internal fun duplicateKeys(keys: List<Any>): List<Any> {
  val seen = HashSet<Any>()
  val duplicates = LinkedHashSet<Any>()
  for (key in keys) if (!seen.add(key)) duplicates.add(key)
  return duplicates.toList()
}
