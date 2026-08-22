package com.chasel.ng2n.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 的三张表 —— RN 版 `ng2.db` / `notifications.db` 的直译。
 *
 * ## 「换结构就换表名」
 *
 * RN 版没有迁移机制(无 `PRAGMA user_version`,只有 `CREATE TABLE IF NOT EXISTS`),
 * 明文策略是**「换结构就换 key/表名,老数据作废」**(6 处注释重复)。这一版照抄:
 * [Ng2nDatabase] 用 `fallbackToDestructiveMigration`,表名带 `_v1` 尾巴由
 * `@Entity(tableName=…)` 控制;真要改结构就把表名换成 `_v2`,老表随下一次
 * destructive 重建一起消失。缓存类数据丢了自己会长回来,历史丢了也只是历史。
 */

/**
 * 浏览历史 **+ 阅读进度**(同一条记录,不拆两张表)。
 *
 * 200 条上限与「只前进」的进度语义在 `data/history/HistoryPolicy.kt`;
 * 1s 节流批刷在 `data/history/ReadFloorThrottle.kt`。
 * `updated_at` 上有索引:历史页永远按它倒序取前 200。
 */
@Entity(
  tableName = "browse_history",
  indices = [Index(value = ["updated_at"], orders = [Index.Order.DESC])],
)
data class BrowseHistoryEntity(
  @PrimaryKey
  @ColumnInfo(name = "tid") val tid: Long,
  @ColumnInfo(name = "subject") val subject: String,
  @ColumnInfo(name = "author") val author: String?,
  @ColumnInfo(name = "board_name") val boardName: String?,
  @ColumnInfo(name = "fav_code") val favCode: String?,
  @ColumnInfo(name = "last_floor") val lastFloor: Int,
  @ColumnInfo(name = "max_floor") val maxFloor: Int,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * 帖子缓存,一行一页((tid, page) 主键)。
 *
 * [payload] 是**序列化后的信封文本**(JSON 字符串)——信封与 AST 的类型归票 04/09,
 * 存储层不认识它的形状,只当一段文本存。[bytes] 是它的 UTF-8 长度,
 * 预算与 LRU 靠它算(`data/cache/TopicCachePolicy.kt`),**图片字节不计入**
 * (图片走 Coil 自己的磁盘缓存)。
 *
 * `used_at` 上有索引:LRU 淘汰与「我的缓存」列表都按它排。
 */
@Entity(
  tableName = "topic_cache",
  primaryKeys = ["tid", "page"],
  indices = [Index(value = ["used_at"], orders = [Index.Order.DESC])],
)
data class TopicCacheEntity(
  @ColumnInfo(name = "tid") val tid: Long,
  @ColumnInfo(name = "page") val page: Int,
  @ColumnInfo(name = "subject") val subject: String,
  @ColumnInfo(name = "board_name") val boardName: String?,
  @ColumnInfo(name = "fav_code") val favCode: String?,
  @ColumnInfo(name = "floors") val floors: Int,
  @ColumnInfo(name = "total_pages") val totalPages: Int,
  @ColumnInfo(name = "bytes") val bytes: Long,
  @ColumnInfo(name = "payload") val payload: String,
  @ColumnInfo(name = "used_at") val usedAt: Long,
)

/**
 * 通知已读,按 (uid, id) 分桶 —— 切号后各看各的已读,互不污染。
 *
 * `id` 是客户端合成的稳定 ID `${ts}-${type}-${tid}-${pid}`
 * (`data/notifications/NotificationPolicy.kt` 的 `notificationId`)。
 * **通知条目本身不持久化**:`get_all` 每次都返回近期全量,已读靠稳定 ID 对上号。
 */
@Entity(tableName = "notification_read", primaryKeys = ["uid", "id"])
data class NotificationReadEntity(
  @ColumnInfo(name = "uid") val uid: String,
  @ColumnInfo(name = "id") val id: String,
  @ColumnInfo(name = "read_at") val readAt: Long,
)

/** 只读元数据投影:`payload` 一列刻意不选,它可能有几十 MB。 */
data class TopicCacheMeta(
  @ColumnInfo(name = "tid") val tid: Long,
  @ColumnInfo(name = "page") val page: Int,
  @ColumnInfo(name = "subject") val subject: String,
  @ColumnInfo(name = "board_name") val boardName: String?,
  @ColumnInfo(name = "fav_code") val favCode: String?,
  @ColumnInfo(name = "floors") val floors: Int,
  @ColumnInfo(name = "total_pages") val totalPages: Int,
  @ColumnInfo(name = "bytes") val bytes: Long,
  @ColumnInfo(name = "used_at") val usedAt: Long,
)
