package com.chasel.ng2n.data.cache

/**
 * 帖子缓存的核心模型 —— `src/core/local/topic-cache.ts` 的直译。
 *
 * **纯 Kotlin,零 Android 依赖**:淘汰口径与展示文案在这里,Room 读写归
 * `TopicCacheRepository`。票 14 的验收项①「缓存上限/驱逐/节流语义单测与 RN 版一致」
 * 靠的就是这条 —— DAO 只做薄层,判断全在这个文件里,JVM 单测直接跑。
 *
 * 存储的粒度是**一页**(`read.php` 一次请求就是一页),但用户看到与操作的粒度是
 * **一个主题**:「我的缓存」列出的是主题,删也是整主题删,LRU 淘汰同样按主题整体走 ——
 * 只淘汰某个主题的第 3 页、留下 1、2、4 页,离线读到中间会突然断掉。
 */

/** 缓存最多留多少个主题。按主题数而不是页数:一个 200 页的长帖也只占一格。 */
const val TOPIC_CACHE_MAX_TOPICS = 100

/**
 * 缓存总字节数上限。存的是文本信封(正文 BBCode + 用户表),一页大约 30–120 KB,
 * 32 MB 够放几百页;图片走 Coil 自己的磁盘缓存,**不算在这里**。
 */
const val TOPIC_CACHE_MAX_BYTES = 32L * 1024 * 1024

/** 缓存里的一页(不含正文本身,payload 只在 Room 里)。 */
data class CachedPage(
  val tid: Long,
  /** 从 1 起 */
  val page: Int,
  val subject: String,
  val boardName: String? = null,
  /** fav 码,离线打开隐藏/过期主题时要带回去 */
  val favCode: String? = null,
  /** 这一页存了多少楼,「第 1 页 · 40 楼」那一格用 */
  val floors: Int,
  /** 写入时该主题共有多少页 */
  val totalPages: Int,
  /** payload 的字节数 */
  val bytes: Long,
  /** 最近一次写入或读取的时间(秒)。LRU 淘汰与列表排序都按它 */
  val usedAt: Long,
)

/** 「我的缓存」列表里的一行:同一主题的所有页聚合成一条。 */
data class CachedTopic(
  val tid: Long,
  val subject: String,
  val boardName: String? = null,
  val favCode: String? = null,
  /** 已缓存的页码,升序 */
  val pages: List<Int>,
  /** 主题总页数(以最近写入的那一页为准),用来说「缓存了 3/12 页」 */
  val totalPages: Int,
  /** 只缓存了一页时展示的楼数 */
  val floors: Int,
  val bytes: Long,
  val usedAt: Long,
)

/**
 * 把页级记录聚合成主题级列表,按最近使用倒序(= 「我的缓存」页的展示顺序)。
 *
 * 元数据(标题/版块名/fav 码/总页数)取**最近写入的那一页**的值:标题会被改、
 * 总页数会随新回复涨,新的那份更可信;但新的那份缺席时保留旧值 ——
 * 从第 2 页开始缓存的主题,版块名要靠更早的记录补。
 */
fun summarizeCachedPages(pages: List<CachedPage>): List<CachedTopic> {
  val byTid = LinkedHashMap<Long, MutableList<CachedPage>>()
  for (page in pages) byTid.getOrPut(page.tid) { mutableListOf() }.add(page)

  val topics = ArrayList<CachedTopic>(byTid.size)
  for ((tid, bucket) in byTid) {
    // 新的在前:元数据按这个顺序「先到先得」。sortedByDescending 是稳定排序,
    // 与 TS 的 Array.prototype.sort 一致(同 usedAt 时保留插入顺序)。
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

/**
 * 超限时该淘汰哪些主题(返回 tid,调用方连带删掉它的所有页)。
 *
 * 两条上限任一超了就从最久未用的开始淘汰,直到两条都满足。**最近使用的那个主题
 * 永远留着**:用户刚缓存完一个大帖、结果它自己把自己挤没了才是真的费解 ——
 * 单主题就超过字节上限时宁可暂时超额一点。
 */
fun planCacheEviction(
  topics: List<CachedTopic>,
  maxTopics: Int = TOPIC_CACHE_MAX_TOPICS,
  maxBytes: Long = TOPIC_CACHE_MAX_BYTES,
): List<Long> {
  // 最久未用的排前面:淘汰就从这一头开始吃
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

/**
 * 一段文本存成 UTF-8 有多少字节。
 *
 * 不用 `text.toByteArray(Charsets.UTF_8).size`:那要为每一页正文额外分配一整个字节数组,
 * 而这里只想要个数字。**并且口径不同** —— JVM 的编码器把落单代理项换成 `?`(1 字节),
 * 而 RN 版(与 `TextEncoder`)按 U+FFFD 算 3 字节。这里照抄 RN 版:代理对按整个码点
 * 算 4 字节,落单的代理项(坏字符串)按 3 字节算。
 */
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

/** 缓存总占用,「已占用 42.6 MB」那句副标题用。 */
fun cacheTotalBytes(topics: List<CachedTopic>): Long = topics.sumOf { it.bytes }

private const val KB = 1024.0
private const val MB = 1024.0 * 1024.0
private const val GB = 1024.0 * 1024.0 * 1024.0

/** 设计稿缓存页的大小口径:「1.2 MB」「0.9 MB」。不足 1 MB 的给整数 KB。 */
fun formatCacheSize(bytes: Long): String {
  val value = maxOf(0L, bytes)
  return when {
    value < KB -> "$value B"
    value < MB -> "${Math.round(value / KB)} KB"
    value < GB -> "${roundTo(value / MB, 1)} MB"
    else -> "${roundTo(value / GB, 2)} GB"
  }
}

/** 连续页码压成 `[起, 止]` 区间。 */
private fun pageRanges(pages: List<Int>): List<IntArray> {
  val ranges = ArrayList<IntArray>()
  for (page in pages) {
    val last = ranges.lastOrNull()
    if (last != null && page == last[1] + 1) last[1] = page else ranges += intArrayOf(page, page)
  }
  return ranges
}

/** 区间多到摆不下时只列前两段,剩下的用「等 N 页」收尾。 */
private const val MAX_RANGE_GROUPS = 3

/**
 * 设计稿缓存行的页范围一格:「第 1 页 · 40 楼」「第 1–3 页」。
 *
 * 只缓存了一页时顺带报楼数(那时页范围本身没什么信息量);
 * 缓存的页不连续(跳着手动缓存过几页)时按区间列出来。
 */
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

/** `cachePagesLabel` 的主题重载。 */
fun cachePagesLabel(topic: CachedTopic): String = cachePagesLabel(topic.pages, topic.floors)

/**
 * 固定小数位的字符串。用 `String.format` 会跟着 Locale 走(阿拉伯语区会出阿拉伯数字),
 * 这里的口径是设计稿写死的,所以自己按 ROOT 拼。
 */
private fun roundTo(value: Double, decimals: Int): String =
  String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
