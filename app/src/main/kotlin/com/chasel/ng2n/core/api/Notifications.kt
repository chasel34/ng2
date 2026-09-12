package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private fun notificationId(timestamp: Long, type: Int, tid: Long, pid: Long): String =
  "$timestamp-$type-$tid-$pid"

fun notificationKind(type: Int): NotificationKind = when (type) {
  1, 2 -> NotificationKind.REPLY
  3, 4 -> NotificationKind.COMMENT
  7, 8 -> NotificationKind.MENTION
  10, 11 -> NotificationKind.MESSAGE
  17 -> NotificationKind.RATING
  else -> NotificationKind.OTHER
}

private const val UNTITLED = "无标题"

private fun parseNotification(raw: JsonElement?): NgaNotification? {
  if (raw !is JsonObject) return null
  val type = int(raw, "0")?.toInt() ?: return null
  val timestamp = int(raw, "9") ?: return null

  val tid = nonZero(int(raw, "6")) ?: 0
  val pid = nonZero(int(raw, "7")) ?: 0

  return NgaNotification(
    id = notificationId(timestamp, type, tid, pid),
    type = type,
    kind = notificationKind(type),
    userId = nonZero(int(raw, "1")),
    userName = str(raw, "2") ?: "匿名用户",
    subject = str(raw, "5") ?: UNTITLED,
    tid = tid,
    pid = pid,
    myPid = nonZero(int(raw, "8")),
    timestamp = timestamp,
    page = (nonZero(int(raw, "10")) ?: 1L).toInt(),
  )
}

fun parseNotificationFeed(data: JsonElement?): NotificationFeed {
  val root = data as? JsonObject ?: JsonObject(emptyMap())
  val box = root["0"] as? JsonObject ?: JsonObject(emptyMap())

  val items = ArrayList<NgaNotification>()
  for (key in listOf("0", "1", "2")) {
    for (raw in orderedValues(box[key])) {
      val item = parseNotification(raw) ?: continue
      items += item
    }
  }
  val sorted = items.sortedByDescending { it.timestamp }

  return NotificationFeed(items = sorted, serverUnread = int(box, "unread"))
}

suspend fun fetchNotificationFeed(client: NgaClient): NotificationFeed {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "noti", "__act" to "get_all"),
      validate = ::rejectNonNotificationFeed,
    ),
  )

  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "通知响应里没有 data", via = result.via)
  }
  return parseNotificationFeed(data)
}

suspend fun clearNotificationFeed(client: NgaClient) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.WRITE,
      query = queryOf("__lib" to "noti", "raw" to 3, "__act" to "del"),
    ),
  )
}
