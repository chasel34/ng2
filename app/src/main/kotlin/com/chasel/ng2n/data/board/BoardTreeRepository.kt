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

/**
 * 首页的版块分类树。
 *
 * 冷启动先用 DataStore 里的缓存渲染,再由 [loadBoardTree] 按 24 小时节流决定要不要
 * 联网静默更新;断网且有缓存时不报错,结果里带 [BoardTreeUiState.staleError] 供 UI 提示。
 *
 * **不做同步读**(修 P2-04):DataStore 只有 suspend/Flow,首屏那一帧是 loading,
 * 缓存回来立刻替换。RN 版是在模块初始化里同步 parse 100KB JSON。
 */
@Singleton
class BoardTreeRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
  @IoScope private val scope: CoroutineScope,
) {

  data class BoardTreeUiState(
    val loading: Boolean = true,
    val tree: BoardTree? = null,
    /** 一个都没拿到时的失败(首页整屏走错误态) */
    val error: Throwable? = null,
    /** 拿到的是缓存、而这次联网更新失败了 */
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

  /** 首页进来时调。**幂等**:重进首页(返回、切 tab)不该再打一次接口。 */
  fun ensureLoaded() {
    if (started) return
    started = true
    load(force = false)
  }

  /** 用户主动重试 / 下拉刷新:跳过 TTL。 */
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

  /** 已关掉的公告 id。 */
  val dismissedAnnouncements: Flow<List<String>> get() = settings.dismissedAnnouncements

  fun dismissAnnouncement(id: String) {
    scope.launch { settings.dismissAnnouncement(id) }
  }

  private companion object {
    /** 缓存载荷是我们自己写自己读的,但仍当外部输入:老版本 app 写下的树可能少字段。 */
    val JSON = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      encodeDefaults = true
    }
  }
}
