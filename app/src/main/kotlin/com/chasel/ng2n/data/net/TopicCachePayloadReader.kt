package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.api.TopicPageSnapshot
import com.chasel.ng2n.core.net.strategies.TopicCacheKey
import com.chasel.ng2n.core.net.strategies.TopicCacheReader
import com.chasel.ng2n.data.cache.CachedPageSnapshot
import com.chasel.ng2n.data.cache.TopicCacheRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 帖子缓存载荷的**两向接缝** —— 接票 14 的 `topic_cache` 表。
 *
 * - 读:反封锁链最后一档([TopicCacheReader]);
 * - 写:详情页拿到一页后交出的快照([TopicPageSnapshot],票 07 的
 *   `fetchTopicDetail(onSnapshot = …)` 产出)。
 *
 * 读到会顺手把 `used_at` 推到现在(LRU 说的是「最久未**用**」),那在仓库里做。
 *
 * 载荷本身不在这里解释:策略拿到文本后走 `parseNgaJson`,与在线那条路完全同一段代码,
 * 信封天然同构([com.chasel.ng2n.core.net.strategies.serializeEnvelope] 是它的反面)。
 * 这个类只做「core 的快照 ↔ Room 的行」这一次搬运——core 层不认识 Room,
 * 两边各有一份同形状的快照类型,字段一一对应。
 */
@Singleton
class TopicCachePayloadReader @Inject constructor(
  private val repository: TopicCacheRepository,
) : TopicCacheReader {

  override suspend fun read(key: TopicCacheKey): String? =
    repository.readPayload(tid = key.tid, page = key.page)

  /**
   * 写一页缓存。仓库自己会吞掉磁盘异常并按 LRU 淘汰——
   * 缓存写不进去不该连累用户正在读的这一页。
   */
  suspend fun save(snapshot: TopicPageSnapshot) {
    repository.savePage(snapshot.toStorage())
  }
}

/** core 的快照 → 存储层的行。字段一一对应,改一边就要改另一边。 */
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
