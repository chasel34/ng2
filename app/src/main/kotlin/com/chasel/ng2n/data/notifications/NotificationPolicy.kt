package com.chasel.ng2n.data.notifications

/**
 * 通知的本地已读模型 —— `src/core/local/notifications.ts` 的直译。
 *
 * 服务端不提供逐条已读状态(API 文档 §9.1),本地维护:
 * - 稳定 ID `时间戳-类型-tid-pid` —— 同一条通知每次拉取都算出同一个 ID;
 * - **刷新只增不覆盖**:合并新一次拉取时,已认识的 ID 保留旧条目,只把没见过的插进来。
 *   已读集合是独立的一组 ID,合并根本不碰它,所以重复拉取天然不会重置已读。
 *
 * **纯 Kotlin,零 Android 依赖**;持久化(Room 的 `notification_read`)在
 * `NotificationReadRepository`。**通知条目本身不持久化** —— `get_all` 每次都返回近期全量,
 * 已读靠稳定 ID 对上号。
 */

/** 稳定 ID 的原料:四个数字缺一不可,缺的位置由解析层补 0。 */
data class NotificationIdParts(
  val timestamp: Long,
  val type: Int,
  val tid: Long,
  val pid: Long,
)

/** 稳定 ID `时间戳-类型-tid-pid`(spec §4,MNGA 同款口径)。 */
fun notificationId(parts: NotificationIdParts): String =
  "${parts.timestamp}-${parts.type}-${parts.tid}-${parts.pid}"

/** 已读模型只关心「有稳定 ID、可按时间排序」,不绑死接口层的具体形状。 */
interface NotificationLike {
  val id: String
  val timestamp: Long
}

/**
 * 把新一次拉取合并进已有条目:**只增不覆盖**。
 *
 * 已认识的 ID 保留旧条目(服务端偶尔会对同一条通知微调字段,覆盖会让「同一条」
 * 在两次刷新之间变脸);新条目插入后整体按时间降序。
 */
fun <T : NotificationLike> mergeNotifications(existing: List<T>, incoming: List<T>): List<T> {
  val seen = existing.mapTo(HashSet()) { it.id }
  val merged = ArrayList(existing)
  for (item in incoming) {
    if (!seen.add(item.id)) continue
    merged += item
  }
  // sortedByDescending 是稳定排序,与 TS 的 Array.prototype.sort 一致
  return merged.sortedByDescending { it.timestamp }
}

/** 这次拉取里哪些是没见过的新条目(未读角标要立刻反映它们)。 */
fun <T : NotificationLike> newNotifications(existing: List<T>, incoming: List<T>): List<T> {
  val seen = existing.mapTo(HashSet()) { it.id }
  return incoming.filter { it.id !in seen }
}

/** 未读数:条目里不在已读集合里的那些。 */
fun unreadCount(items: List<NotificationLike>, readIds: Set<String>): Int =
  items.count { it.id !in readIds }

/**
 * 标记一批 ID 为已读。返回新集合(不改入参);一个都没变时**返回原集合**,
 * 方便上层拿引用相等判断要不要写盘。
 */
fun markRead(readIds: Set<String>, ids: List<String>): Set<String> {
  val fresh = ids.filter { it !in readIds }
  if (fresh.isEmpty()) return readIds
  val next = LinkedHashSet(readIds)
  next.addAll(fresh)
  return next
}

/** 一组同类通知。 */
data class NotificationGroup<K, T>(val kind: K, val items: List<T>)

/** 按分类分组,组内保持传入顺序(调用方已按时间降序)。空组不出现。 */
fun <K, T> groupNotifications(
  items: List<T>,
  order: List<K>,
  kindOf: (T) -> K,
): List<NotificationGroup<K, T>> {
  val buckets = LinkedHashMap<K, MutableList<T>>()
  for (item in items) buckets.getOrPut(kindOf(item)) { mutableListOf() }.add(item)

  val known = order.toHashSet()
  val tail = buckets.keys.filter { it !in known }
  return (order + tail)
    .map { NotificationGroup(it, buckets[it] ?: emptyList()) }
    .filter { it.items.isNotEmpty() }
}
