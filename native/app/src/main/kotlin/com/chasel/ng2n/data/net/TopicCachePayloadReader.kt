package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.data.cache.TopicCacheRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反封锁链最后一档的读口 —— 接票 14 的 `topic_cache` 表。
 *
 * **只读**:写缓存是详情页浏览成功后的事(票 13),不在链上。
 * 读到会顺手把 `used_at` 推到现在(LRU 说的是「最久未**用**」),那在仓库里做。
 *
 * 载荷的反序列化不在这里:策略拿到文本后走 `parseNgaJson`,与在线那条路完全同一段代码,
 * 信封天然同构(payload 的形状归票 07 / 13)。
 */
@Singleton
class TopicCachePayloadReader @Inject constructor(
  private val repository: TopicCacheRepository,
) : TopicCacheReader {

  override suspend fun read(key: TopicCacheKey): String? =
    repository.readPayload(tid = key.tid, page = key.page)
}
