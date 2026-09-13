package com.chasel.ng2n.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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

@Entity(tableName = "notification_read", primaryKeys = ["uid", "id"])
data class NotificationReadEntity(
  @ColumnInfo(name = "uid") val uid: String,
  @ColumnInfo(name = "id") val id: String,
  @ColumnInfo(name = "read_at") val readAt: Long,
)

@Entity(tableName = "bookmark", primaryKeys = ["tid", "pid"])
data class BookmarkEntity(
  @ColumnInfo(name = "tid") val tid: Long,
  @ColumnInfo(name = "pid") val pid: Long,
  @ColumnInfo(name = "lou") val lou: Long,
  @ColumnInfo(name = "author") val author: String,
  @ColumnInfo(name = "summary") val summary: String,
  @ColumnInfo(name = "note") val note: String?,
  @ColumnInfo(name = "subject") val subject: String,
  @ColumnInfo(name = "board_name") val boardName: String?,
  @ColumnInfo(name = "fav_code") val favCode: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

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
