package com.chasel.ng2n.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ai_conversation")
data class AiConversationEntity(
  @PrimaryKey val id: String, val title: String, val kind: String, val tid: Long,
  val source: String, val createdAt: Long, val updatedAt: Long, val status: String,
  val readingSummary: String, val entryJson: String, val selectedPid: Long?,
  val draft: String = "", val deletedAt: Long? = null,
)

@Entity(tableName = "ai_message", primaryKeys = ["conversationId", "ordinal"], foreignKeys = [ForeignKey(
  entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class AiMessageEntity(val conversationId: String, val ordinal: Int, val payload: String)

@Entity(tableName = "ai_reading_range", primaryKeys = ["conversationId", "ordinal"], foreignKeys = [ForeignKey(
  entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class AiReadingRangeEntity(val conversationId: String, val ordinal: Int, val label: String, val done: Boolean)

@Entity(tableName = "ai_source", primaryKeys = ["conversationId", "sourceId"], foreignKeys = [ForeignKey(
  entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class AiSourceEntity(val conversationId: String, val sourceId: String, val tid: Long, val pid: Long,
  val floor: Long, val page: Int, val author: String, val postedAt: String, val readAt: Long, val contentHash: String)

@Entity(tableName = "ai_run", indices = [Index("conversationId")], foreignKeys = [ForeignKey(
  entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class AiRunEntity(@PrimaryKey val id: String, val conversationId: String, val status: String,
  val step: String, val startedAt: Long, val resumeFrom: String? = null)

@Entity(tableName = "ai_working_state", primaryKeys = ["conversationId", "kind", "key"], foreignKeys = [ForeignKey(
  entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class AiWorkingStateEntity(val conversationId: String, val kind: String, val key: String, val payload: String)

@Entity(tableName = "ai_usage")
data class AiUsageEntity(@PrimaryKey val requestId: String, val conversationId: String, val runId: String,
  val startedAt: Long, val status: String = "pending_verification")

@Dao
interface AiConversationDao {
  @Query("SELECT * FROM ai_conversation WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
  fun observe(): Flow<List<AiConversationEntity>>
  @Query("SELECT * FROM ai_conversation WHERE id = :id") suspend fun find(id: String): AiConversationEntity?
  @Upsert suspend fun put(value: AiConversationEntity)
  @Upsert suspend fun messages(values: List<AiMessageEntity>)
  @Upsert suspend fun ranges(values: List<AiReadingRangeEntity>)
  @Upsert suspend fun sources(values: List<AiSourceEntity>)
  @Upsert suspend fun run(value: AiRunEntity)
  @Upsert suspend fun work(value: AiWorkingStateEntity)
  @Insert suspend fun usage(value: AiUsageEntity)
  @Query("SELECT * FROM ai_message WHERE conversationId = :id ORDER BY ordinal") suspend fun messages(id: String): List<AiMessageEntity>
  @Query("SELECT * FROM ai_reading_range WHERE conversationId = :id ORDER BY ordinal") suspend fun ranges(id: String): List<AiReadingRangeEntity>
  @Query("SELECT * FROM ai_source WHERE conversationId = :id") suspend fun sources(id: String): List<AiSourceEntity>
  @Query("SELECT * FROM ai_run WHERE id = :id") suspend fun runById(id: String): AiRunEntity?
  @Query("SELECT * FROM ai_run WHERE conversationId = :id ORDER BY startedAt DESC, rowid DESC LIMIT 1") suspend fun latestRun(id: String): AiRunEntity?
  @Query("SELECT payload FROM ai_working_state WHERE conversationId = :id AND kind = :kind AND `key` = :key")
  suspend fun work(id: String, kind: String, key: String): String?
  @Query("UPDATE ai_run SET status = 'interrupted' WHERE status = 'running'") suspend fun interruptRuns()
  @Query("UPDATE ai_conversation SET status = '已中断' WHERE status = '生成中'") suspend fun interruptConversations()
  @Query("UPDATE ai_conversation SET deletedAt = :at WHERE id = :id") suspend fun markDeleted(id: String, at: Long?)
  @Query("DELETE FROM ai_conversation WHERE id = :id AND deletedAt IS NOT NULL") suspend fun confirmDelete(id: String)
  @Query("DELETE FROM ai_conversation WHERE deletedAt IS NOT NULL") suspend fun purgeDeleted()
  @Query("SELECT id FROM ai_conversation") suspend fun allIds(): List<String>
  @Query("DELETE FROM ai_conversation") suspend fun clear()
  @Query("SELECT * FROM ai_usage") suspend fun usageRecords(): List<AiUsageEntity>
  @Query("SELECT COUNT(*) FROM ai_usage") fun observeRequestCount(): Flow<Int>
  @Query("SELECT COUNT(*) FROM ai_usage") suspend fun usageCount(): Int
}
