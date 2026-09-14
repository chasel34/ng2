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
    AiConversationEntity::class, AiMessageEntity::class, AiReadingRangeEntity::class,
    AiSourceEntity::class, AiRunEntity::class, AiWorkingStateEntity::class, AiUsageEntity::class, com.chasel.ng2n.data.ai.AiBudgetEntity::class,
  ],
  version = 4,
  exportSchema = true,
  autoMigrations = [
    AutoMigration(from = 1, to = 2),
    AutoMigration(from = 2, to = 3),
    AutoMigration(from = 3, to = 4),
  ],
)
abstract class Ng2nDatabase : RoomDatabase() {

  abstract fun aiBudgetDao(): com.chasel.ng2n.data.ai.AiBudgetDao

  abstract fun browseHistoryDao(): BrowseHistoryDao

  abstract fun topicCacheDao(): TopicCacheDao

  abstract fun notificationReadDao(): NotificationReadDao

  abstract fun aiConversationDao(): AiConversationDao

  abstract fun bookmarkDao(): BookmarkDao

  companion object {
    const val NAME = "ng2n.db"
  }
}
