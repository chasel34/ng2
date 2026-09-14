package com.chasel.ng2n.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chasel.ng2n.data.ai.RoomAiConversationStore
import com.chasel.ng2n.data.ai.TopicAgentRuntime
import com.chasel.ng2n.data.ai.settings.AiKeyStore
import com.chasel.ng2n.ui.topic.TopicDeps
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiSessions @Inject constructor(private val deps: TopicDeps, private val keys: AiKeyStore,
  private val runtime: TopicAgentRuntime, val store: RoomAiConversationStore, val budgets: com.chasel.ng2n.data.ai.AiBudgetStore) {
  private val sessions = mutableMapOf<String, TopicAiViewModel>()
  fun create() = TopicAiViewModel(deps, keys, runtime, store = store, budgets = budgets, taskScope = kotlinx.coroutines.CoroutineScope(deps.scope.coroutineContext + kotlinx.coroutines.Dispatchers.Main.immediate),
    register = { id, vm -> synchronized(sessions) { sessions[id] = vm } })
  fun open(id: String): TopicAiViewModel = synchronized(sessions) {
    sessions[id]?.also { it.position(com.chasel.ng2n.core.ai.AiSheetPosition.FULL) }
      ?: create().also { sessions[id] = it; it.load(id) }
  }
  fun stop(id: String) { synchronized(sessions) { sessions[id] }?.stop() }
  fun forget(id: String) { synchronized(sessions) { sessions.remove(id) }?.discard() }
  fun discardAll() { synchronized(sessions) { sessions.keys.toList() }.forEach(::forget) }
  fun stopAll() { synchronized(sessions) { sessions.values.toList() }.forEach { it.stop() } }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiSessionsEntryPoint { fun aiSessions(): AiSessions }

@Composable
fun rememberAiSessions(): AiSessions {
  val context = LocalContext.current
  return remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, AiSessionsEntryPoint::class.java).aiSessions() }
}
