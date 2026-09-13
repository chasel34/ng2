package com.chasel.ng2n.data

import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.db.Ng2nDatabase
import com.chasel.ng2n.data.history.HistoryRepository
import com.chasel.ng2n.di.IoScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageBootstrap @Inject constructor(
  private val db: Ng2nDatabase,
  private val history: HistoryRepository,
  private val topicCache: TopicCacheRepository,
  @IoScope private val scope: CoroutineScope,
) {

  fun start() {
    scope.launch {
      open()
      launch { runCatching { history.warmUp() } }
      launch { runCatching { topicCache.warmUp() } }
    }
  }

  /**
   * 主动打开数据库，让缺失迁移、版本倒退这类结构错误在启动时就抛出，
   * 而不是等首次写入时才在别的协程里崩。
   */
  fun open() {
    db.openHelper.writableDatabase
  }
}
