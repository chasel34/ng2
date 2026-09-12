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

/**
 * 帖子缓存的仓库(反封锁链倒数第二环的缓存档,ADR-0002)。
 *
 * 内存里**只留元数据**(标题/页码/字节数/时间),**正文永远留在库里**:
 * 一页十几万字符,几百页全灌进内存会把它吃光。所以这里与 [HistoryRepository] 相反 ——
 * 那边内存是唯一事实来源,这边库才是。
 *
 * [payload] 的形状(序列化后的信封)归票 04/09,存储层只当一段文本存;
 * [TopicCacheEntity.bytes] 是它的 UTF-8 长度,**图片字节不计入预算**。
 *
 * ## 修 P2-04
 *
 * [warmUp] 是显式 bootstrap,后台协程里跑;首屏不等。
 * RN 版是在模块初始化时同步开库 + 扫元数据(审计 P2-04 的第一条证据)。
 */
@Singleton
class TopicCacheRepository @Inject constructor(
  private val dao: TopicCacheDao,
) {

  private val pagesState = MutableStateFlow<List<CachedPage>>(emptyList())
  private val topicsState = MutableStateFlow<List<CachedTopic>>(emptyList())

  /** 全部已缓存页的元数据(不含正文)。 */
  val pages: StateFlow<List<CachedPage>> = pagesState.asStateFlow()

  /** 「我的缓存」列表(按主题聚合、最近使用倒序)。 */
  val topics: StateFlow<List<CachedTopic>> = topicsState.asStateFlow()

  private val mutex = Mutex()
  private var warmed = false

  /** 把元数据灌进内存。只在后台协程里调,首屏不等它。 */
  suspend fun warmUp() {
    mutex.withLock {
      if (warmed) return
      warmed = true
      setPages(dao.loadMeta().map { it.toCachedPage() })
    }
  }

  /** 某一页在不在缓存里(详情页「缓存本页」判断要不要真去拉一次)。 */
  fun isPageCached(tid: Long, page: Int): Boolean =
    pagesState.value.any { it.tid == tid && it.page == page }

  /** 一个主题缓存了哪些页(「缓存整帖」跳过已有的页)。 */
  fun cachedPagesOf(tid: Long): List<Int> =
    topicsState.value.firstOrNull { it.tid == tid }?.pages ?: emptyList()

  /**
   * 反封锁链缓存档的读口。
   *
   * 读到就顺手把 `used_at` 推到现在:LRU 说的是「最久未**用**」,
   * 只按写入时间淘汰的话,天天离线翻的那个帖会先被新缓存挤掉。
   */
  suspend fun readPayload(tid: Long, page: Int): String? {
    val payload = dao.readPayload(tid, page) ?: return null
    touch(tid)
    return payload
  }

  /**
   * 写一页缓存(浏览成功即自动写)。写完按 LRU 淘汰到上限内。
   *
   * **吞掉自己的异常**:磁盘满、库被锁……缓存写不进去不该连累用户正在读的这一页。
   */
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
      // 协程取消不是「写失败」,原样抛回去,别把它吞成一条排障记录
      throw cause
    } catch (cause: RuntimeException) {
      // 磁盘满、库被锁……缓存写不进去不该把异常抛回正在渲染的详情页(RN 版同款吞法)
      lastWriteFailure = cause
    }
  }

  /** 最近一次写缓存失败的原因。只给排障看,没有 UI 消费它。 */
  @Volatile
  var lastWriteFailure: Throwable? = null
    private set

  /** 把某个主题的全部页标成「刚用过」,免得正在离线读的帖被 LRU 挤掉。 */
  suspend fun touch(tid: Long, nowSeconds: Long = nowSec()) {
    dao.touch(tid, nowSeconds)
    mutex.withLock {
      setPages(pagesState.value.map { if (it.tid == tid) it.copy(usedAt = nowSeconds) else it })
    }
  }

  /** 删掉一个主题的全部缓存页(缓存页行尾的删除钮)。 */
  suspend fun deleteTopic(tid: Long) {
    dao.deleteTopics(listOf(tid))
    mutex.withLock { setPages(pagesState.value.filterNot { it.tid == tid }) }
  }

  /** 清空全部缓存(缓存页右上角 delete_sweep)。 */
  suspend fun clear() {
    dao.clear()
    mutex.withLock { setPages(emptyList()) }
  }

  /** 库里的元数据直接订阅 —— 给不想等 [warmUp] 的场合。 */
  fun observeMetaFromDb(): Flow<List<TopicCacheMeta>> = dao.observeMeta()

  private fun setPages(next: List<CachedPage>) {
    pagesState.value = next
    topicsState.value = summarizeCachedPages(next)
  }
}

/** 一页缓存的写入快照。`payload` 由票 04 的信封序列化产出。 */
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
