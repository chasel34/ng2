package com.chasel.ng2n.core.local

/**
 * 热帖聚合——**不是服务端 API**。直译 `src/core/local/hot-topics.ts`。
 *
 * 客户端并发拉版块前若干页主题列表,在本地按 24 小时窗口过滤、按回复数排序
 * (功能文档 §2.2「热门话题」)。这里只做纯聚合:拉页与容错在 core/api,
 * 本函数拿到「已经拉回来的那几页」算榜单。
 *
 * 时间一律由调用方传入(秒级 unix 时间戳),函数里不取当前时间——
 * 同一份输入永远算出同一份榜单,才测得动。
 */

/**
 * 聚合需要看的最小字段集(`Topic` 的子集;泛型保真:传 `Topic` 进来出去还是 `Topic`)。
 * 秒级 unix 时间戳,与 `thread.php` 的 `postdate`/`lastpost` 一致。
 */
interface HotTopicCandidate {
  val tid: Long
  val replies: Long
  val postedAt: Long
  val lastPostAt: Long

  /** 合集/版块镜像行:不是讨论串,回复数没有可比性,聚合时剔掉。`null` = 这一行没有该字段 */
  val shortcut: Any? get() = null

  /** 外链活动主题:点开不是 read.php,不进榜 */
  val jumpUrl: String? get() = null
}

/** 默认窗口:24 小时(spec §4「热帖仅 24h 档」)。 */
const val HOT_WINDOW_HOURS = 24

/**
 * 把几页主题聚成热帖榜。
 *
 * - **窗口过滤看发帖时间**([HotTopicCandidate.postedAt]):榜单回答的是「过去 24 小时里
 *   冒出来的哪些新主题最热」,按最后回复过滤的话十年老坟顶一下也会进榜;
 * - 跨页按 tid 去重(置顶主题每页都会再回来一次,同 `mergeTopicPages` 的原因);
 * - 排序:回复数降序 → 最后回复时间降序 → tid 升序,最后一档是为了让结果确定。
 *
 * @param now 当前时刻,秒级 unix 时间戳。由调用方传入,纯函数不自己看表
 * @param windowHours 时间窗口小时数,默认 24(spec §4:热帖仅 24h 档,留参数位是为了
 *   单测与将来加档)
 */
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
      // postedAt 缺省解析成 0,会被窗口自然挡掉;postedAt 在未来的(服务端时钟漂移)照收
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
