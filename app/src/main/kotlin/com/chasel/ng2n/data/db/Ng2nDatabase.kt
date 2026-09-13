package com.chasel.ng2n.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
  entities = [
    BrowseHistoryEntity::class,
    TopicCacheEntity::class,
    NotificationReadEntity::class,
    BookmarkEntity::class,
  ],
  version = 2,
  exportSchema = true,
  autoMigrations = [
    AutoMigration(from = 1, to = 2),
  ],
)
abstract class Ng2nDatabase : RoomDatabase() {

  abstract fun browseHistoryDao(): BrowseHistoryDao

  abstract fun topicCacheDao(): TopicCacheDao

  abstract fun notificationReadDao(): NotificationReadDao

  abstract fun bookmarkDao(): BookmarkDao

  companion object {
    const val NAME = "ng2n.db"
  }
}
