package com.chasel.ng2n.data.cache

const val TOPIC_CACHE_MAX_TOPICS = 100

const val TOPIC_CACHE_MAX_BYTES = 32L * 1024 * 1024

data class CachedPage(
  val tid: Long,
  val page: Int,
  val subject: String,
  val boardName: String? = null,
  val favCode: String? = null,
  val floors: Int,
  val totalPages: Int,
  val bytes: Long,
  val usedAt: Long,
)

data class CachedTopic(
  val tid: Long,
  val subject: String,
  val boardName: String? = null,
  val favCode: String? = null,
  val pages: List<Int>,
  val totalPages: Int,
  val floors: Int,
  val bytes: Long,
  val usedAt: Long,
)

fun summarizeCachedPages(pages: List<CachedPage>): List<CachedTopic> {
  val byTid = LinkedHashMap<Long, MutableList<CachedPage>>()
  for (page in pages) byTid.getOrPut(page.tid) { mutableListOf() }.add(page)

  val topics = ArrayList<CachedTopic>(byTid.size)
  for ((tid, bucket) in byTid) {
    val newestFirst = bucket.sortedByDescending { it.usedAt }
    val numbers = bucket.map { it.page }.distinct().sorted()
    val newest = newestFirst.firstOrNull() ?: continue

    topics += CachedTopic(
      tid = tid,
      subject = newestFirst.firstOrNull { it.subject != "" }?.subject ?: "",
      pages = numbers,
      totalPages = maxOf(newest.totalPages, numbers.lastOrNull() ?: 1),
      floors = newest.floors,
      bytes = bucket.sumOf { it.bytes },
      usedAt = newest.usedAt,
      boardName = newestFirst.firstOrNull { it.boardName != null }?.boardName,
      favCode = newestFirst.firstOrNull { it.favCode != null }?.favCode,
    )
  }
  return topics.sortedByDescending { it.usedAt }
}

fun planCacheEviction(
  topics: List<CachedTopic>,
  maxTopics: Int = TOPIC_CACHE_MAX_TOPICS,
  maxBytes: Long = TOPIC_CACHE_MAX_BYTES,
): List<Long> {
  val oldestFirst = topics.sortedBy { it.usedAt }
  var count = oldestFirst.size
  var bytes = oldestFirst.sumOf { it.bytes }

  val evicted = ArrayList<Long>()
  for (topic in oldestFirst) {
    if (count <= maxTopics && bytes <= maxBytes) break
    if (count <= 1) break
    evicted += topic.tid
    count -= 1
    bytes -= topic.bytes
  }
  return evicted
}

fun utf8ByteLength(text: String): Long {
  var bytes = 0L
  var index = 0
  while (index < text.length) {
    val code = text[index].code
    when {
      code < 0x80 -> bytes += 1
      code < 0x800 -> bytes += 2
      code in 0xD800..0xDBFF && index + 1 < text.length -> {
        val next = text[index + 1].code
        if (next in 0xDC00..0xDFFF) {
          bytes += 4
          index += 1
        } else {
          bytes += 3
        }
      }
      else -> bytes += 3
    }
    index += 1
  }
  return bytes
}

fun cacheTotalBytes(topics: List<CachedTopic>): Long = topics.sumOf { it.bytes }

private const val KB = 1024.0
private const val MB = 1024.0 * 1024.0
private const val GB = 1024.0 * 1024.0 * 1024.0

fun formatCacheSize(bytes: Long): String {
  val value = maxOf(0L, bytes)
  return when {
    value < KB -> "$value B"
    value < MB -> "${Math.round(value / KB)} KB"
    value < GB -> "${roundTo(value / MB, 1)} MB"
    else -> "${roundTo(value / GB, 2)} GB"
  }
}

private fun pageRanges(pages: List<Int>): List<IntArray> {
  val ranges = ArrayList<IntArray>()
  for (page in pages) {
    val last = ranges.lastOrNull()
    if (last != null && page == last[1] + 1) last[1] = page else ranges += intArrayOf(page, page)
  }
  return ranges
}

private const val MAX_RANGE_GROUPS = 3

fun cachePagesLabel(pages: List<Int>, floors: Int): String {
  if (pages.isEmpty()) return "空缓存"
  if (pages.size == 1) return "第 ${pages[0]} 页 · $floors 楼"

  val ranges = pageRanges(pages)
  val shown = ranges.take(MAX_RANGE_GROUPS)
    .joinToString("、") { if (it[0] == it[1]) "${it[0]}" else "${it[0]}–${it[1]}" }
  return if (ranges.size > MAX_RANGE_GROUPS) {
    "第 $shown 等 ${pages.size} 页"
  } else {
    "第 $shown 页"
  }
}

fun cachePagesLabel(topic: CachedTopic): String = cachePagesLabel(topic.pages, topic.floors)

private fun roundTo(value: Double, decimals: Int): String =
  String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
