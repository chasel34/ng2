package com.chasel.ng2n.data.favorites

import com.chasel.ng2n.core.api.FavoriteFolder
import com.chasel.ng2n.core.api.Topic
import com.chasel.ng2n.core.api.TopicList
import com.chasel.ng2n.core.api.addTopicFavorite
import com.chasel.ng2n.core.api.createFavoriteFolder
import com.chasel.ng2n.core.api.deleteFavoriteFolder
import com.chasel.ng2n.core.api.fetchFavoriteFolders
import com.chasel.ng2n.core.api.fetchFavoriteTopics
import com.chasel.ng2n.core.api.mergeTopicPages
import com.chasel.ng2n.core.api.modifyFavoriteFolder
import com.chasel.ng2n.core.api.removeTopicFavorite
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.settings.FavoriteChange
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.settings.TopicFavorIndex
import com.chasel.ng2n.data.settings.applyFavoriteChange
import com.chasel.ng2n.data.settings.foldersOfTopic
import com.chasel.ng2n.data.settings.pruneFolders
import com.chasel.ng2n.data.settings.seedFolderTopics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopicFavoriteRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
) {

  data class FoldersState(
    val loading: Boolean = false,
    val folders: List<FavoriteFolder> = emptyList(),
    val error: Throwable? = null,
    val loaded: Boolean = false,
  )

  data class TopicsKey(val uid: String, val folderId: Long)

  data class TopicsState(
    val loading: Boolean = true,
    val pages: List<TopicList> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val error: Throwable? = null,
    val loadingNextPage: Boolean = false,
    val refreshing: Boolean = false,
    val hasNextPage: Boolean = true,
  )

  private val folderBuckets = MutableStateFlow<Map<String, FoldersState>>(emptyMap())
  val folderStates: StateFlow<Map<String, FoldersState>> = folderBuckets.asStateFlow()

  private val topicBuckets = MutableStateFlow<Map<TopicsKey, TopicsState>>(emptyMap())
  val topicStates: StateFlow<Map<TopicsKey, TopicsState>> = topicBuckets.asStateFlow()

  private val folderLock = Mutex()
  private val topicLocks = HashMap<TopicsKey, Mutex>()

  @Synchronized
  private fun lockOf(key: TopicsKey): Mutex = topicLocks.getOrPut(key) { Mutex() }

  fun foldersOf(uid: String?): FoldersState =
    if (uid == null) GUEST_FOLDERS else folderBuckets.value[uid] ?: FoldersState()

  fun topicsOf(uid: String?, folderId: Long?): TopicsState =
    if (uid == null || folderId == null) {
      TopicsState(loading = false)
    } else {
      topicBuckets.value[TopicsKey(uid, folderId)] ?: TopicsState()
    }

  fun folderIdsOfTopic(uid: String?, tid: Long): Flow<List<Int>> =
    settings.topicFavorIndex(uid).map { foldersOfTopic(it, tid) }

  suspend fun ensureFolders(uid: String?) {
    if (uid == null) return
    if (folderBuckets.value[uid]?.loaded == true) return
    reloadFolders(uid)
  }

  suspend fun reloadFolders(uid: String?) {
    if (uid == null) return
    folderLock.withLock { reloadFoldersLocked(uid) }
  }

  private suspend fun reloadFoldersLocked(uid: String) = withContext(Dispatchers.IO) {
    putFolders(uid) { it.copy(loading = true, error = null) }
    try {
      val folders = fetchFavoriteFolders(client)
      putFolders(uid) { FoldersState(loading = false, folders = folders, loaded = true) }
    } catch (cancelled: CancellationException) {
      putFolders(uid) { it.copy(loading = false) }
      throw cancelled
    } catch (error: Throwable) {
      putFolders(uid) { it.copy(loading = false, error = error) }
    }
  }

  private fun putFolders(uid: String, transform: (FoldersState) -> FoldersState) {
    val current = folderBuckets.value[uid] ?: FoldersState()
    folderBuckets.value = folderBuckets.value + (uid to transform(current))
  }

  suspend fun ensureTopics(uid: String?, folderId: Long?) {
    if (uid == null || folderId == null) return
    val key = TopicsKey(uid, folderId)
    if (topicBuckets.value[key]?.pages?.isNotEmpty() == true) return
    lockOf(key).withLock {
      if (topicBuckets.value[key]?.pages?.isNotEmpty() == true) return@withLock
      putTopics(key) { it.copy(loading = true, error = null) }
      fetchTopicsInto(key, page = 1, replace = true)
    }
  }

  suspend fun refreshTopics(uid: String?, folderId: Long?) {
    if (uid == null || folderId == null) return
    val key = TopicsKey(uid, folderId)
    lockOf(key).withLock {
      putTopics(key) { it.copy(refreshing = true, error = null) }
      fetchTopicsInto(key, page = 1, replace = true)
      putTopics(key) { it.copy(refreshing = false) }
    }
  }

  suspend fun loadNextTopicPage(uid: String?, folderId: Long?) {
    if (uid == null || folderId == null) return
    val key = TopicsKey(uid, folderId)
    val current = topicBuckets.value[key] ?: TopicsState()
    if (!current.hasNextPage || current.loadingNextPage || current.loading) return
    lockOf(key).withLock {
      val now = topicBuckets.value[key] ?: TopicsState()
      if (!now.hasNextPage || now.loadingNextPage) return@withLock
      putTopics(key) { it.copy(loadingNextPage = true, error = null) }
      fetchTopicsInto(key, page = now.pages.size + 1, replace = false)
      putTopics(key) { it.copy(loadingNextPage = false) }
    }
  }

  private suspend fun fetchTopicsInto(key: TopicsKey, page: Int, replace: Boolean) = withContext(Dispatchers.IO) {
    try {
      val fetched = fetchFavoriteTopics(client, folderId = key.folderId, page = page)
      putTopics(key) { state ->
        val pages = if (replace) listOf(fetched) else state.pages + fetched
        state.copy(
          loading = false,
          pages = pages,
          topics = mergeTopicPages(pages),
          error = null,
          hasNextPage = hasNextPage(pages),
        )
      }
      settings.updateTopicFavorIndex(key.uid) { index ->
        seedFolderTopics(
          index = index,
          folderId = key.folderId.toInt(),
          tids = fetched.topics.map { it.tid },
          complete = page == 1 && fetched.totalPages <= 1,
        )
      }
    } catch (cancelled: CancellationException) {
      putTopics(key) { it.copy(loading = false, loadingNextPage = false, refreshing = false) }
      throw cancelled
    } catch (error: Throwable) {
      putTopics(key) { it.copy(loading = false, error = error) }
    }
  }

  private fun putTopics(key: TopicsKey, transform: (TopicsState) -> TopicsState) {
    val current = topicBuckets.value[key] ?: TopicsState()
    topicBuckets.value = topicBuckets.value + (key to transform(current))
  }

  private suspend fun afterFolderChange(uid: String, vararg folderIds: Long) {
    for (folderId in folderIds) {
      topicBuckets.value = topicBuckets.value - TopicsKey(uid, folderId)
    }
    reloadFoldersLocked(uid)
  }

  suspend fun createFolder(uid: String, name: String, asDefault: Boolean = false): Long? =
    withContext(Dispatchers.IO) {
      folderLock.withLock {
        val id = createFavoriteFolder(client, name = name, asDefault = asDefault)
        afterFolderChange(uid)
        id
      }
    }

  suspend fun modifyFolder(uid: String, folderId: Long, name: String, asDefault: Boolean = false) {
    withContext(Dispatchers.IO) {
      folderLock.withLock {
        modifyFavoriteFolder(client, folderId = folderId, name = name, asDefault = asDefault)
        afterFolderChange(uid, folderId)
      }
    }
  }

  suspend fun deleteFolder(uid: String, folderId: Long) {
    withContext(Dispatchers.IO) {
      folderLock.withLock {
        deleteFavoriteFolder(client, folderId = folderId)
        afterFolderChange(uid, folderId)
        val alive = folderBuckets.value[uid]?.folders.orEmpty().map { it.id.toInt() }
        settings.updateTopicFavorIndex(uid) { pruneFolders(it, alive) }
      }
    }
  }

  suspend fun applyTopicFavorites(
    uid: String,
    tid: Long,
    added: List<Long>,
    removed: List<Long>,
  ) {
    withContext(Dispatchers.IO) {
      try {
        for (folderId in added) {
          addTopicFavorite(client, tid = tid, folderId = folderId)
          applyChange(uid, FavoriteChange(tid, folderId.toInt(), favored = true))
        }
        for (folderId in removed) {
          removeTopicFavorite(client, tid = tid, folderId = folderId)
          applyChange(uid, FavoriteChange(tid, folderId.toInt(), favored = false))
        }
      } finally {
        folderLock.withLock { afterFolderChange(uid, *(added + removed).toLongArray()) }
      }
    }
  }

  suspend fun unfavoriteTopic(uid: String, tid: Long, folderId: Long) {
    try {
      applyTopicFavorites(uid, tid, added = emptyList(), removed = listOf(folderId))
    } finally {
      refreshTopics(uid, folderId)
    }
  }

  private suspend fun applyChange(uid: String, change: FavoriteChange) {
    settings.updateTopicFavorIndex(uid) { applyFavoriteChange(it, change) }
  }

  suspend fun currentIndex(uid: String?): TopicFavorIndex = settings.currentTopicFavorIndex(uid)

  private companion object {
    val GUEST_FOLDERS = FoldersState(loading = false, loaded = true)

    fun hasNextPage(pages: List<TopicList>): Boolean {
      val last = pages.lastOrNull() ?: return true
      if (last.topics.isEmpty()) return false
      return pages.size + 1 <= last.totalPages
    }
  }
}

const val FAVORITE_FOLDER_LIMIT = 20

fun pickFavoriteFolder(
  folders: List<FavoriteFolder>,
  pickedId: Long?,
): FavoriteFolder? =
  folders.firstOrNull { it.id == pickedId }
    ?: folders.firstOrNull { it.isDefault }
    ?: folders.firstOrNull()
