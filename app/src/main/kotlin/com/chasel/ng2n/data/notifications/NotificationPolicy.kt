package com.chasel.ng2n.data.notifications

data class NotificationIdParts(
  val timestamp: Long,
  val type: Int,
  val tid: Long,
  val pid: Long,
)

fun notificationId(parts: NotificationIdParts): String =
  "${parts.timestamp}-${parts.type}-${parts.tid}-${parts.pid}"

interface NotificationLike {
  val id: String
  val timestamp: Long
}

fun <T : NotificationLike> mergeNotifications(existing: List<T>, incoming: List<T>): List<T> {
  val seen = existing.mapTo(HashSet()) { it.id }
  val merged = ArrayList(existing)
  for (item in incoming) {
    if (!seen.add(item.id)) continue
    merged += item
  }
  return merged.sortedByDescending { it.timestamp }
}

fun <T : NotificationLike> newNotifications(existing: List<T>, incoming: List<T>): List<T> {
  val seen = existing.mapTo(HashSet()) { it.id }
  return incoming.filter { it.id !in seen }
}

fun unreadCount(items: List<NotificationLike>, readIds: Set<String>): Int =
  items.count { it.id !in readIds }

fun markRead(readIds: Set<String>, ids: List<String>): Set<String> {
  val fresh = ids.filter { it !in readIds }
  if (fresh.isEmpty()) return readIds
  val next = LinkedHashSet(readIds)
  next.addAll(fresh)
  return next
}

data class NotificationGroup<K, T>(val kind: K, val items: List<T>)

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
