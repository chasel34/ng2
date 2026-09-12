package com.chasel.ng2n.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
  entities = [
    BrowseHistoryEntity::class,
    TopicCacheEntity::class,
    NotificationReadEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class Ng2nDatabase : RoomDatabase() {

  abstract fun browseHistoryDao(): BrowseHistoryDao

  abstract fun topicCacheDao(): TopicCacheDao

  abstract fun notificationReadDao(): NotificationReadDao

  companion object {
    const val NAME = "ng2n.db"
  }
}
