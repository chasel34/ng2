package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.data.topic.TopicRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.data.bookmarks.BookmarkRepository
import com.chasel.ng2n.data.bookmarks.FakeBookmarkDao
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.db.BrowseHistoryDao
import com.chasel.ng2n.data.db.BrowseHistoryEntity
import com.chasel.ng2n.data.db.TopicCacheDao
import com.chasel.ng2n.data.db.TopicCacheEntity
import com.chasel.ng2n.data.db.TopicCacheMeta
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeBrowseHistoryDao : BrowseHistoryDao {
  private val rows = LinkedHashMap<Long, BrowseHistoryEntity>()
  private val state = MutableStateFlow<List<BrowseHistoryEntity>>(emptyList())

  var writes: Int = 0
    private set

  override fun observe(limit: Int): Flow<List<BrowseHistoryEntity>> = state.map { it.take(limit) }

  override suspend fun loadAll(limit: Int): List<BrowseHistoryEntity> =
    rows.values.sortedByDescending { it.updatedAt }.take(limit)

  override suspend fun loadAll(): List<BrowseHistoryEntity> =
    rows.values.sortedByDescending { it.updatedAt }

  override suspend fun find(tid: Long): BrowseHistoryEntity? = rows[tid]

  override suspend fun upsert(entry: BrowseHistoryEntity) {
    writes += 1
    rows[entry.tid] = entry
    state.value = rows.values.sortedByDescending { it.updatedAt }
  }

  override suspend fun deleteByTids(tids: List<Long>) {
    tids.forEach { rows.remove(it) }
    state.value = rows.values.sortedByDescending { it.updatedAt }
  }

  override suspend fun clear() {
    rows.clear()
    state.value = emptyList()
  }
}

class FakeTopicCacheDao : TopicCacheDao {
  private val rows = LinkedHashMap<Pair<Long, Int>, TopicCacheEntity>()
  private val state = MutableStateFlow<List<TopicCacheMeta>>(emptyList())

  override fun observeMeta(): Flow<List<TopicCacheMeta>> = state

  override suspend fun loadMeta(): List<TopicCacheMeta> = rows.values.map { it.toMeta() }

  override suspend fun readPayload(tid: Long, page: Int): String? = rows[tid to page]?.payload

  override suspend fun upsert(entry: TopicCacheEntity) {
    rows[entry.tid to entry.page] = entry
    state.value = rows.values.map { it.toMeta() }
  }

  override suspend fun touch(tid: Long, usedAt: Long) {
    for ((key, value) in rows.toList()) {
      if (key.first == tid) rows[key] = value.copy(usedAt = usedAt)
    }
    state.value = rows.values.map { it.toMeta() }
  }

  override suspend fun deleteTopics(tids: List<Long>) {
    rows.keys.filter { it.first in tids }.forEach { rows.remove(it) }
    state.value = rows.values.map { it.toMeta() }
  }

  override suspend fun clear() {
    rows.clear()
    state.value = emptyList()
  }

  private fun TopicCacheEntity.toMeta() = TopicCacheMeta(
    tid = tid,
    page = page,
    subject = subject,
    boardName = boardName,
    favCode = favCode,
    floors = floors,
    totalPages = totalPages,
    bytes = bytes,
    usedAt = usedAt,
  )
}

class InMemoryPreferences : DataStore<Preferences> {
  private val state = MutableStateFlow(emptyPreferences())
  override val data: Flow<Preferences> = state

  override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
    val next = transform(state.value)
    state.value = next
    return next
  }
}

class FakeCredentials(private var credential: Credential? = null) : CredentialSource {
  override suspend fun current(): Credential? = credential
  override suspend fun all(): List<Credential> = listOfNotNull(credential)
  fun signIn(uid: String) {
    credential = Credential(uid = uid, token = "cid-$uid")
  }
}

class FakeTopicDeps(
  client: NgaClient,
  scope: CoroutineScope,
  compute: CoroutineDispatcher = Dispatchers.Unconfined,
  val historyDao: FakeBrowseHistoryDao = FakeBrowseHistoryDao(),
  val bookmarkDao: FakeBookmarkDao = FakeBookmarkDao(),
  val cacheDao: FakeTopicCacheDao = FakeTopicCacheDao(),
  val credentials: FakeCredentials = FakeCredentials(),
  val snapshotSink: FakeSnapshotSink = FakeSnapshotSink(),
) {
  val settingsStore = SettingsStore(InMemoryPreferences())
  val repository = TopicRepository(
    client = client,
    cachePayloads = snapshotSink,
    scope = scope,
    io = scope.testDispatcher(),
  )
  val history = HistoryRepository(dao = historyDao, bookmarks = bookmarkDao, scope = scope)
  val bookmarks = BookmarkRepository(bookmarkDao)
  val topicCache = TopicCacheRepository(dao = cacheDao)

  val deps = TopicDeps(
    client = client,
    repository = repository,
    pageLoader = TopicPageLoader(repository, compute),
    history = history,
    bookmarks = bookmarks,
    topicCache = topicCache,
    settings = settingsStore,
    credentials = credentials,
    attachmentUrls = TopicFixtures.URLS,
    scope = scope,
  )
}
