package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.api.TopicPageSnapshot
import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.data.cache.CachedPageSnapshot
import com.chasel.ng2n.data.cache.TopicCacheRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopicCachePayloadReader @Inject constructor(
  private val repository: TopicCacheRepository,
) : TopicCacheReader {

  override suspend fun read(key: TopicCacheKey): String? =
    repository.readPayload(tid = key.tid, page = key.page)

  suspend fun save(snapshot: TopicPageSnapshot) {
    repository.savePage(snapshot.toStorage())
  }
}

internal fun TopicPageSnapshot.toStorage(): CachedPageSnapshot = CachedPageSnapshot(
  tid = tid,
  page = page,
  subject = subject,
  boardName = boardName,
  favCode = favCode,
  floors = floors,
  totalPages = totalPages,
  payload = payload,
)
