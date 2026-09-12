package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.BoardTree
import com.chasel.ng2n.core.api.fetchBoardTree
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.settings.CachedBoardTree
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoardTreeRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
  @IoScope private val scope: CoroutineScope,
) {

  data class BoardTreeUiState(
    val loading: Boolean = true,
    val tree: BoardTree? = null,
    val error: Throwable? = null,
    val staleError: Throwable? = null,
    val refreshing: Boolean = false,
  )

  private val state = MutableStateFlow(BoardTreeUiState())
  val uiState: StateFlow<BoardTreeUiState> = state.asStateFlow()

  private val lock = Mutex()
  private var inFlight: Job? = null
  private var started = false

  private val store = object : BoardTreeStore {
    override suspend fun read(): BoardTreeSnapshot? {
      val cached = settings.boardTree.first() ?: return null
      val tree = runCatching { JSON.decodeFromString<BoardTree>(cached.payload) }.getOrNull()
        ?: return null
      return BoardTreeSnapshot(tree, cached.fetchedAt)
    }

    override suspend fun write(snapshot: BoardTreeSnapshot) {
      settings.saveBoardTree(
        CachedBoardTree(JSON.encodeToString(snapshot.tree), snapshot.fetchedAt),
      )
    }
  }

  fun ensureLoaded() {
    if (started) return
    started = true
    load(force = false)
  }

  fun refresh() {
    load(force = true)
  }

  private fun load(force: Boolean) {
    if (inFlight?.isActive == true) return
    inFlight = scope.launch {
      lock.withLock {
        state.value = state.value.copy(loading = state.value.tree == null, refreshing = force)
        try {
          val result = loadBoardTree(
            store = store,
            fetchTree = { fetchBoardTree(client) },
            now = System.currentTimeMillis(),
            force = force,
          )
          state.value = BoardTreeUiState(
            loading = false,
            tree = result.tree,
            error = null,
            staleError = result.error,
            refreshing = false,
          )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          state.value = state.value.copy(
            loading = false,
            refreshing = false,
            error = if (state.value.tree == null) error else state.value.error,
            staleError = if (state.value.tree == null) null else error,
          )
        }
      }
    }
  }

  val dismissedAnnouncements: Flow<List<String>> get() = settings.dismissedAnnouncements

  fun dismissAnnouncement(id: String) {
    scope.launch { settings.dismissAnnouncement(id) }
  }

  private companion object {
    val JSON = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      encodeDefaults = true
    }
  }
}
