package com.chasel.ng2n.data.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import ai.koog.agents.snapshot.providers.PersistenceUtils
import ai.koog.prompt.message.Message
import com.chasel.ng2n.core.ai.TopicContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AiRunPersistence(val store: AiConversationStore, val conversationId: String, val runId: String,
  val resumeFrom: String? = null) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<AiRunPersistence>
  @Volatile private var failure: AiStorageException? = null
  val failed: Boolean get() = failure != null
  suspend fun <T> guard(block: suspend () -> T): T {
    failure?.let { throw it }
    return try { block() } catch (e: CancellationException) { throw e }
      catch (e: AiStorageException) { failure = e; throw e }
  }
  suspend fun recordSkills(version: String) = guard {
    store.work(conversationId, "skills", runId, version)
  }

  suspend fun beforeRequest() = guard { store.requestStarted(conversationId, runId) }
  suspend fun saveContext(context: TopicContext) = guard {
    store.work(conversationId, "context", "current", Json.encodeToString(context))
  }
  suspend fun priorWork(kind: String, key: String? = null): String? = guard {
    var candidate: String? = runId
    val visited = mutableSetOf<String>()
    while (candidate != null && visited.add(candidate)) {
      store.work(conversationId, if (key == null) kind else "$kind:$candidate", key ?: candidate)?.let { return@guard it }
      candidate = if (candidate == runId) resumeFrom else store.runById(candidate)?.takeIf { it.conversationId == conversationId }?.resumeFrom
    }
    null
  }
  val checkpoints = object : PersistenceStorageProvider<Unit> {
    private val json = PersistenceUtils.defaultCheckpointJson
    override suspend fun getCheckpoints(sessionId: String, filter: Unit?): List<AgentCheckpointData> =
      listOfNotNull(getLatestCheckpoint(sessionId, filter))
    override suspend fun getLatestCheckpoint(sessionId: String, filter: Unit?): AgentCheckpointData? = guard {
      priorWork("checkpoint")?.let { json.decodeFromString<AgentCheckpointData>(it) }
    }
    override suspend fun saveCheckpoint(sessionId: String, agentCheckpointData: AgentCheckpointData) = guard {
      store.work(conversationId, "checkpoint", runId, json.encodeToString(agentCheckpointData))
    }
  }
  val chatMemory = object : ChatHistoryProvider {
    override suspend fun load(conversationId: String): List<Message> = guard {
      store.work(this@AiRunPersistence.conversationId, "chat_memory", "current")?.let { Json.decodeFromString<List<Message>>(it) }.orEmpty()
    }
    override suspend fun store(conversationId: String, messages: List<Message>) = guard {
      store.work(this@AiRunPersistence.conversationId, "chat_memory", "current", Json.encodeToString(messages))
    }
  }
}
