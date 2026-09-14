package com.chasel.ng2n.data.ai

import com.chasel.ng2n.data.db.*
import kotlinx.coroutines.flow.MutableStateFlow

class MemoryAiConversationStore : AiConversationStore {
  override val conversations = MutableStateFlow<List<AiConversationEntity>>(emptyList())
  val saved = mutableMapOf<String, SavedAiConversation>()
  val runs = mutableMapOf<String, AiRunEntity>()
  val working = mutableMapOf<Triple<String, String, String>, String>()
  var fail = false
  var failKind: String? = null
  var requests = 0
  override suspend fun initialize() = Unit
  override suspend fun save(conversation: AiConversationEntity, messages: List<AiMessageEntity>, ranges: List<AiReadingRangeEntity>, sources: List<AiSourceEntity>, run: AiRunEntity?) {
    if (fail) throw AiStorageException()
    run?.let { runs[it.id] = it }
    saved[conversation.id] = SavedAiConversation(conversation, messages, ranges, sources, run)
    conversations.value = saved.values.map { it.conversation }
  }
  override suspend fun runById(id: String) = runs[id]
  override suspend fun load(id: String) = saved[id]
  override suspend fun work(id: String, kind: String, key: String) = working[Triple(id, kind, key)]
  override suspend fun work(id: String, kind: String, key: String, payload: String) {
    if (fail || kind == failKind) throw AiStorageException()
    working[Triple(id, kind, key)] = payload
  }
  override suspend fun requestStarted(conversationId: String, runId: String) {
    if (fail) throw AiStorageException()
    requests++
  }
}
