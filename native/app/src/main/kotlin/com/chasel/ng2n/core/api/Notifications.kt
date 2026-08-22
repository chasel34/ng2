package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 通知(CONTEXT.md「通知」)的接口解析(API 文档 §9)。直译 `src/core/api/notifications.ts`。
 *
 * `nuke.php?__lib=noti&__act=get_all` 一次拉全:`data["0"]` 下有三个容器
 * `"0"`(回复/@/贴条类)、`"1"`(短信类)、`"2"`,每条通知是数字下标对象。
 * 服务端**不提供逐条已读状态**——已读模型在 `data/notifications/NotificationPolicy.kt`
 * (票 14),这里只负责把响应解成条目并配上稳定 ID。
 *
 * 空账号的真实响应是 `{"data":{"0":""}}`——容器整个退化成空串,
 * 所以每一层都要先问「是不是对象」,空串就是空列表。
 */

/**
 * 稳定 ID `时间戳-类型-tid-pid`(spec §4,MNGA 同款口径)。
 *
 * ⚠️ 与 `data/notifications/NotificationPolicy.kt` 的 `notificationId` 是**同一个口径**,
 * 改一处必须改另一处。没有共用是因为分层:core 不能 import data,而那份已读模型
 * (票 14)住在 data 层。已记进票 07 的「发现的票外问题」——它本该在 `core/local`。
 */
private fun notificationId(timestamp: Long, type: Int, tid: Long, pid: Long): String =
  "$timestamp-$type-$tid-$pid"

/**
 * 类型码 → 分类(API 文档 §9.1 的枚举表)。
 * 未认识的类型码归入 [NotificationKind.OTHER]——展示总比悄悄丢掉好。
 */
fun notificationKind(type: Int): NotificationKind = when (type) {
  1, 2 -> NotificationKind.REPLY
  3, 4 -> NotificationKind.COMMENT
  7, 8 -> NotificationKind.MENTION
  10, 11 -> NotificationKind.MESSAGE
  17 -> NotificationKind.RATING
  else -> NotificationKind.OTHER
}

/** 没标题的通知占位,与主题列表的口径一致。 */
private const val UNTITLED = "无标题"

/**
 * 解一条通知。类型码与时间戳是稳定 ID 的原料,缺了这两个的条目没法去重,
 * 只能跳过;其余字段短信类通知本来就没有(Android 研报 §10.1),全部给缺省值。
 */
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
    // 对方帖子所在页码,服务端不给就当第 1 页
    page = (nonZero(int(raw, "10")) ?: 1L).toInt(),
  )
}

/**
 * 解整份通知列表。传响应的 `data`。
 *
 * 三个容器统一走同一个条目解析,分类看条目自己的类型码而不是所在容器——
 * 短信类通知(10/11)在哪个容器里出现都归 [NotificationKind.MESSAGE]。
 */
fun parseNotificationFeed(data: JsonElement?): NotificationFeed {
  val root = data as? JsonObject ?: JsonObject(emptyMap())
  val box = root["0"] as? JsonObject ?: JsonObject(emptyMap())

  val items = ArrayList<NgaNotification>()
  for (key in listOf("0", "1", "2")) {
    // 容器既可能是数组也可能是数字下标对象,空账号下还会是空串——orderedValues 三种都吃
    for (raw in orderedValues(box[key])) {
      val item = parseNotification(raw) ?: continue
      items += item
    }
  }
  // 新的在前:服务端顺序不可靠,按时间戳降序稳定输出(sortedByDescending 是稳定排序,
  // 与 TS 的 Array.prototype.sort 一致)
  val sorted = items.sortedByDescending { it.timestamp }

  return NotificationFeed(items = sorted, serverUnread = int(box, "unread"))
}

/** 拉全部通知(`POST nuke.php?__lib=noti&__act=get_all`,API 文档 §9.1)。 */
suspend fun fetchNotificationFeed(client: NgaClient): NotificationFeed {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "noti", "__act" to "get_all"),
    ),
  )

  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(NgaErrorKind.PARSE, "通知响应里没有 data", via = result.via)
  }
  return parseNotificationFeed(data)
}

/**
 * 服务端一键清空(`POST nuke.php?__lib=noti&raw=3&__act=del`,API 文档 §9.2)。
 * 响应没有可用的数据,不抛错即成功。
 */
suspend fun clearNotificationFeed(client: NgaClient) {
  client.execute(
    NgaRequest(
      path = "nuke.php",
      // 写操作:禁入格式轮换与换账号,失败不重放(修 P1-01)
      operation = Operation.WRITE,
      query = queryOf("__lib" to "noti", "raw" to 3, "__act" to "del"),
    ),
  )
}
