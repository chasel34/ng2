package com.chasel.ng2n.data.cache

import com.chasel.ng2n.data.db.TopicCacheDao
import com.chasel.ng2n.data.db.TopicCacheEntity
import com.chasel.ng2n.data.db.TopicCacheMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopicCacheRepository @Inject constructor(
  private val dao: TopicCacheDao,
) {

  private val pagesState = MutableStateFlow<List<CachedPage>>(emptyList())
  private val topicsState = MutableStateFlow<List<CachedTopic>>(emptyList())

  val pages: StateFlow<List<CachedPage>> = pagesState.asStateFlow()

  val topics: StateFlow<List<CachedTopic>> = topicsState.asStateFlow()

  private val mutex = Mutex()
  private var warmed = false

  suspend fun warmUp() {
    mutex.withLock {
      if (warmed) return
      warmed = true
      setPages(dao.loadMeta().map { it.toCachedPage() })
    }
  }

  fun isPageCached(tid: Long, page: Int): Boolean =
    pagesState.value.any { it.tid == tid && it.page == page }

  fun cachedPagesOf(tid: Long): List<Int> =
    topicsState.value.firstOrNull { it.tid == tid }?.pages ?: emptyList()

  suspend fun readPayload(tid: Long, page: Int): String? {
    val payload = dao.readPayload(tid, page) ?: return null
    touch(tid)
    return payload
  }

  suspend fun savePage(snapshot: CachedPageSnapshot, nowSeconds: Long = nowSec()) {
    try {
      val entry = CachedPage(
        tid = snapshot.tid,
        page = snapshot.page,
        subject = snapshot.subject,
        boardName = snapshot.boardName,
        favCode = snapshot.favCode,
        floors = snapshot.floors,
        totalPages = snapshot.totalPages,
        bytes = utf8ByteLength(snapshot.payload),
        usedAt = nowSeconds,
      )
      mutex.withLock {
        val kept = pagesState.value.filterNot { it.tid == entry.tid && it.page == entry.page }
        val next = kept + entry
        val evicted = planCacheEviction(summarizeCachedPages(next))
        dao.upsertAndEvict(entry.toEntity(snapshot.payload), evicted)
        val dropped = evicted.toSet()
        setPages(next.filterNot { it.tid in dropped })
      }
    } catch (cause: CancellationException) {
      throw cause
    } catch (cause: RuntimeException) {
      lastWriteFailure = cause
    }
  }

  @Volatile
  var lastWriteFailure: Throwable? = null
    private set

  suspend fun touch(tid: Long, nowSeconds: Long = nowSec()) {
    dao.touch(tid, nowSeconds)
    mutex.withLock {
      setPages(pagesState.value.map { if (it.tid == tid) it.copy(usedAt = nowSeconds) else it })
    }
  }

  suspend fun deleteTopic(tid: Long) {
    dao.deleteTopics(listOf(tid))
    mutex.withLock { setPages(pagesState.value.filterNot { it.tid == tid }) }
  }

  suspend fun clear() {
    dao.clear()
    mutex.withLock { setPages(emptyList()) }
  }

  fun observeMetaFromDb(): Flow<List<TopicCacheMeta>> = dao.observeMeta()

  private fun setPages(next: List<CachedPage>) {
    pagesState.value = next
    topicsState.value = summarizeCachedPages(next)
  }
}

data class CachedPageSnapshot(
  val tid: Long,
  val page: Int,
  val subject: String,
  val boardName: String? = null,
  val favCode: String? = null,
  val floors: Int,
  val totalPages: Int,
  val payload: String,
)

internal fun TopicCacheMeta.toCachedPage(): CachedPage = CachedPage(
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

internal fun CachedPage.toEntity(payload: String): TopicCacheEntity = TopicCacheEntity(
  tid = tid,
  page = page,
  subject = subject,
  boardName = boardName,
  favCode = favCode,
  floors = floors,
  totalPages = totalPages,
  bytes = bytes,
  payload = payload,
  usedAt = usedAt,
)

private fun nowSec(): Long = System.currentTimeMillis() / 1000
