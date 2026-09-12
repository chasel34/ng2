package com.chasel.ng2n.data

import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageBootstrap @Inject constructor(
  private val history: HistoryRepository,
  private val topicCache: TopicCacheRepository,
  @IoScope private val scope: CoroutineScope,
) {

  fun start() {
    scope.launch { runCatching { history.warmUp() } }
    scope.launch { runCatching { topicCache.warmUp() } }
  }
}
