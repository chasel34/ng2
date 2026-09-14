package com.chasel.ng2n.data.ai

import androidx.room.withTransaction
import com.chasel.ng2n.data.db.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

interface AiConversationStore {
  val conversations: Flow<List<AiConversationEntity>>
  suspend fun initialize()
  suspend fun save(conversation: AiConversationEntity, messages: List<AiMessageEntity>, ranges: List<AiReadingRangeEntity>, sources: List<AiSourceEntity>, run: AiRunEntity?)
  suspend fun runById(id: String): AiRunEntity?
  suspend fun load(id: String): SavedAiConversation?
  suspend fun work(id: String, kind: String, key: String): String?
  suspend fun work(id: String, kind: String, key: String, payload: String)
  suspend fun requestStarted(conversationId: String, runId: String)
}

data class SavedAiConversation(val conversation: AiConversationEntity, val messages: List<AiMessageEntity>,
  val ranges: List<AiReadingRangeEntity>, val sources: List<AiSourceEntity>, val run: AiRunEntity?)
class AiStorageException(cause: Throwable? = null) : Exception("对话未能保存", cause)

@Singleton
class RoomAiConversationStore @Inject constructor(private val db: Ng2nDatabase) : AiConversationStore {
  private val dao get() = db.aiConversationDao()
  private val lock = Mutex()
  private var initialized = false
  private val deleted = mutableSetOf<String>()
  override val conversations = dao.observe()
  val requestCount = dao.observeRequestCount()
  override suspend fun initialize() = lock.withLock {
    if (!initialized) {
      guarded { db.withTransaction { dao.interruptRuns(); dao.interruptConversations(); dao.purgeDeleted() } }
      initialized = true
    }
  }
  override suspend fun save(conversation: AiConversationEntity, messages: List<AiMessageEntity>, ranges: List<AiReadingRangeEntity>, sources: List<AiSourceEntity>, run: AiRunEntity?) = lock.withLock { guarded {
    db.withTransaction {
      if (conversation.id in deleted) throw AiStorageException()
      val old = dao.find(conversation.id)
      if (old?.deletedAt != null) throw AiStorageException()
      dao.put(conversation); dao.messages(messages); dao.ranges(ranges); dao.sources(sources)
      run?.let { dao.run(it) }
      Unit
    }
  } }
  override suspend fun runById(id: String) = guarded { dao.runById(id) }
  override suspend fun load(id: String): SavedAiConversation? = guarded {
    db.withTransaction {
      dao.find(id)?.takeIf { it.deletedAt == null }?.let {
        SavedAiConversation(it, dao.messages(id), dao.ranges(id), dao.sources(id), dao.latestRun(id))
      }
    }
  }
  override suspend fun work(id: String, kind: String, key: String): String? = guarded { dao.work(id, kind, key) }
  override suspend fun work(id: String, kind: String, key: String, payload: String) = guarded {
    db.withTransaction {
      check(dao.find(id)?.deletedAt == null && dao.find(id) != null)
      dao.work(AiWorkingStateEntity(id, kind, key, payload))
    }
  }
  override suspend fun requestStarted(conversationId: String, runId: String) = guarded {
    db.withTransaction {
      check(dao.find(conversationId)?.deletedAt == null && dao.find(conversationId) != null)
      dao.usage(AiUsageEntity(java.util.UUID.randomUUID().toString(), conversationId, runId, System.currentTimeMillis()))
    }
  }
  suspend fun delete(id: String) = lock.withLock { guarded { dao.markDeleted(id, System.currentTimeMillis()); deleted.add(id); Unit } }
  suspend fun undo(id: String) = lock.withLock { guarded { dao.markDeleted(id, null); deleted.remove(id); Unit } }
  suspend fun confirmDelete(id: String) = lock.withLock { guarded { dao.confirmDelete(id) } }
  suspend fun clear() = lock.withLock { guarded {
    val added = mutableSetOf<String>()
    try {
      db.withTransaction {
        added.addAll(dao.allIds().filterNot { it in deleted })
        deleted.addAll(added)
        dao.clear()
      }
    } catch (e: Throwable) {
      deleted.removeAll(added)
      throw e
    }
  } }
  private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: AiStorageException) { throw e }
    catch (e: Exception) { throw AiStorageException(e) }
}
