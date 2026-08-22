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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题收藏夹(CONTEXT.md「收藏夹」——云端多夹,与版块收藏无关)。
 * 直译 RN 侧 `store/topic-favor.ts`。
 *
 * ## 修 P1-02:两份缓存都按 uid 分桶
 *
 * 收藏夹是**云端按账号分**的:夹 id 在两个账号之间没有任何关系。RN 版里
 * `FAVORITE_FOLDERS_QUERY_KEY = ['favorite-folders']` 与
 * `favoriteTopicsQueryKey(folderId) = ['favorite-topics', folderId]` **都不带 uid**,
 * 切号之后进收藏夹页看到的还是上一个账号的夹与夹内主题(直到 TanStack 重取回来才换),
 * 而「默认夹 id」这类值更是会拿旧账号的 id 去打新账号的接口。这一版从一开始就把 uid
 * 放进两个桶的 key 里,并且**游客态不发请求**(接口对游客一律回「你必须先登录论坛」)。
 *
 * 反向索引那一半(「主题在哪几个夹里」)票 14 已经按 uid 分了键
 * (`SettingsStore.topicFavorIndex(uid)`),这里只负责喂它。
 *
 * ## 写操作后一律重拉
 *
 * 计数与默认徽标**以服务端为准**(RN 版 11 票验收项):新建 / 重命名 / 设默认 / 删除
 * 之后必重拉夹列表,受影响的夹的主题列表连同已翻的页一起丢掉,下次进去从第一页重取。
 */
@Singleton
class TopicFavoriteRepository @Inject constructor(
  private val client: NgaClient,
  private val settings: SettingsStore,
) {

  data class FoldersState(
    val loading: Boolean = false,
    val folders: List<FavoriteFolder> = emptyList(),
    val error: Throwable? = null,
    /** 请求真的回来过(「一个夹都没有」要能与「还没拉」区分开) */
    val loaded: Boolean = false,
  )

  /** 夹内主题的桶键。**uid 在这里**——修 P1-02 的一半。 */
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

  /** 游客态恒为空态(不发请求)。 */
  fun foldersOf(uid: String?): FoldersState =
    if (uid == null) GUEST_FOLDERS else folderBuckets.value[uid] ?: FoldersState()

  fun topicsOf(uid: String?, folderId: Long?): TopicsState =
    if (uid == null || folderId == null) {
      TopicsState(loading = false)
    } else {
      topicBuckets.value[TopicsKey(uid, folderId)] ?: TopicsState()
    }

  /** 本机已知的、这个主题所属的收藏夹 id(多选对话框拿它当初始勾选)。 */
  fun folderIdsOfTopic(uid: String?, tid: Long): Flow<List<Int>> =
    settings.topicFavorIndex(uid).map { foldersOfTopic(it, tid) }

  // ---------------------------------------------------------------- 夹列表

  /** 进屏时调。**幂等**:已经拉回来过就什么都不做。 */
  suspend fun ensureFolders(uid: String?) {
    if (uid == null) return
    if (folderBuckets.value[uid]?.loaded == true) return
    reloadFolders(uid)
  }

  suspend fun reloadFolders(uid: String?) {
    if (uid == null) return
    folderLock.withLock { reloadFoldersLocked(uid) }
  }

  private suspend fun reloadFoldersLocked(uid: String) {
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

  // ---------------------------------------------------------------- 夹内主题

  /** 进屏 / 换夹时调。幂等。 */
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

  /**
   * 下拉刷新:**先把已翻的页砍到只剩第一页,再重取**(与主题列表同一条理由:
   * 直接全量重取会把翻过的每一页都重打一遍,ADR-0002)。
   */
  suspend fun refreshTopics(uid: String?, folderId: Long?) {
    if (uid == null || folderId == null) return
    val key = TopicsKey(uid, folderId)
    lockOf(key).withLock {
      putTopics(key) { it.copy(refreshing = true, error = null) }
      fetchTopicsInto(key, page = 1, replace = true)
      putTopics(key) { it.copy(refreshing = false) }
    }
  }

  /** 无限滚动的下一页。 */
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

  private suspend fun fetchTopicsInto(key: TopicsKey, page: Int, replace: Boolean) {
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
      // 翻到的每一页顺手喂给归属索引:用户逛过的收藏夹,下次开多选对话框就勾得准。
      // 整个夹就这一页时才敢反过来清本机记错的归属
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

  // ---------------------------------------------------------------- 夹的增删改

  /**
   * 收藏夹增删改共用的善后:夹列表必重拉(计数与默认徽标一律以服务端为准),
   * 受影响的那几个夹的主题列表连同已翻的页一起丢掉,下次进去从第一页重取。
   */
  private suspend fun afterFolderChange(uid: String, vararg folderIds: Long) {
    for (folderId in folderIds) {
      topicBuckets.value = topicBuckets.value - TopicsKey(uid, folderId)
    }
    reloadFoldersLocked(uid)
  }

  /** 新建收藏夹,返回新夹 id(服务端没给就是 null,调用方反正读重拉的列表)。 */
  suspend fun createFolder(uid: String, name: String, asDefault: Boolean = false): Long? =
    folderLock.withLock {
      val id = createFavoriteFolder(client, name = name, asDefault = asDefault)
      afterFolderChange(uid)
      id
    }

  /** 重命名 / 设为默认(服务端是同一个 `modify_folder`,`name` 必传)。 */
  suspend fun modifyFolder(uid: String, folderId: Long, name: String, asDefault: Boolean = false) {
    folderLock.withLock {
      modifyFavoriteFolder(client, folderId = folderId, name = name, asDefault = asDefault)
      afterFolderChange(uid, folderId)
    }
  }

  /** 删除收藏夹。夹里的收藏一并没了,所以本机索引也要把这个夹摘干净。 */
  suspend fun deleteFolder(uid: String, folderId: Long) {
    folderLock.withLock {
      deleteFavoriteFolder(client, folderId = folderId)
      afterFolderChange(uid, folderId)
      // 以重拉回来的服务端列表为准,把已经不存在的夹从索引里摘掉
      val alive = folderBuckets.value[uid]?.folders.orEmpty().map { it.id.toInt() }
      settings.updateTopicFavorIndex(uid) { pruneFolders(it, alive) }
    }
  }

  /**
   * 多选对话框点「完成」:把勾选的差异逐个落到服务端。
   *
   * **逐个串行发**,不并发:一次勾三四个夹就是三四个 `nuke.php`,并发打过去
   * 正撞在 NGA 封第三方客户端的枪口上(ADR-0002)。每成功一个就更新一次本机索引 ——
   * 中途失败时,已经做成的那几个不会被回滚掉。
   */
  suspend fun applyTopicFavorites(
    uid: String,
    tid: Long,
    added: List<Long>,
    removed: List<Long>,
  ) {
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
      // 成功失败都要善后:失败也可能是做了一半,夹里的计数已经变了
      folderLock.withLock { afterFolderChange(uid, *(added + removed).toLongArray()) }
    }
  }

  private suspend fun applyChange(uid: String, change: FavoriteChange) {
    settings.updateTopicFavorIndex(uid) { applyFavoriteChange(it, change) }
  }

  /** 当前索引的快照(多选对话框算差集要用)。 */
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

/** 服务端的收藏夹上限(设计稿「新建收藏夹」对话框的提示语)。 */
const val FAVORITE_FOLDER_LIMIT = 20

/**
 * 进页落在哪个夹上:没手动选过就落在默认夹;服务端没标默认(老账号)时退到第一个夹。
 * 纯函数,单测直接跑。
 */
fun pickFavoriteFolder(
  folders: List<FavoriteFolder>,
  pickedId: Long?,
): FavoriteFolder? =
  folders.firstOrNull { it.id == pickedId }
    ?: folders.firstOrNull { it.isDefault }
    ?: folders.firstOrNull()
