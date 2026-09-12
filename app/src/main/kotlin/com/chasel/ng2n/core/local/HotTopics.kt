package com.chasel.ng2n.core.local

/** postedAt 与 lastPostAt 均为 Unix 秒时间戳。 */
interface HotTopicCandidate {
  val tid: Long
  val replies: Long
  val postedAt: Long
  val lastPostAt: Long

  val shortcut: Any? get() = null

  val jumpUrl: String? get() = null
}

const val HOT_WINDOW_HOURS = 24

fun <T : HotTopicCandidate> aggregateHotTopics(
  pages: List<List<T>>,
  now: Long,
  windowHours: Int = HOT_WINDOW_HOURS,
): List<T> {
  val windowSeconds = windowHours * 3600L
  val earliest = now - windowSeconds

  val seen = HashSet<Long>()
  val picked = ArrayList<T>()
  for (page in pages) {
    for (topic in page) {
      if (!seen.add(topic.tid)) continue
      if (topic.shortcut != null || topic.jumpUrl != null) continue
      if (topic.postedAt < earliest) continue
      picked.add(topic)
    }
  }

  return picked.sortedWith(
    compareByDescending<T> { it.replies }
      .thenByDescending { it.lastPostAt }
      .thenBy { it.tid },
  )
}
