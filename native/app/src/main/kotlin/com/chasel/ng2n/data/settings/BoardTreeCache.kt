package com.chasel.ng2n.data.settings

/**
 * 版块分类树缓存的**存储壳** —— 24 小时 SWR 的判定 + 一份不透明的载荷。
 *
 * 树本身的数据类型(`BoardTree` / 分类 / 分组 / 版块)与合并策略(`mergeBoardTree`)
 * 归**票 16**(首页/版块)。票 14 只负责:载荷原样存、`fetchedAt` 存、
 * 「该刷新了吗」这一条判定。载荷定为 `String`(序列化后的 JSON),
 * 与 `topic_cache.payload` 同一个套路 —— 上层序列化,存储层不认识它的形状。
 *
 * RN 版对应 MMKV key `board-tree/v1`。
 */

/** 官方 Android v4 也是这个节流窗口(进版面时触发,24 小时最多一次)。 */
const val BOARD_TREE_TTL_MS = 24L * 60 * 60 * 1000

/** 缓存下来的一棵树。[payload] 是票 16 序列化出来的 JSON,这里不解释它。 */
data class CachedBoardTree(
  val payload: String,
  /** 上一次真正从服务端取到的时刻,毫秒时间戳 */
  val fetchedAt: Long,
)

/**
 * 缓存是否该刷新了。
 * **设备时钟往回跳(now < fetchedAt)一律按过期算**,否则缓存会卡死。
 */
fun isBoardTreeStale(fetchedAt: Long, now: Long, ttl: Long = BOARD_TREE_TTL_MS): Boolean {
  val elapsed = now - fetchedAt
  return elapsed < 0 || elapsed >= ttl
}

/** 已读公告只留最近若干条:公告条自带 id,关掉的老公告没必要一直攒着。 */
const val DISMISSED_ANNOUNCEMENTS_LIMIT = 20

/** 关掉一条公告。重复关同一条时挪到末尾,超限从头砍。 */
fun withDismissedAnnouncement(ids: List<String>, id: String): List<String> =
  (ids.filter { it != id } + id).takeLast(DISMISSED_ANNOUNCEMENTS_LIMIT)
