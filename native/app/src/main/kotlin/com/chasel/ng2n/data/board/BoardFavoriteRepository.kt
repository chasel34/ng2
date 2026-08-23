package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.addBoardFavorite
import com.chasel.ng2n.core.api.clearBoardFavorites
import com.chasel.ng2n.core.api.fetchBoardFavorites
import com.chasel.ng2n.core.api.removeBoardFavorite
import com.chasel.ng2n.core.net.NgaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 版块收藏(CONTEXT.md「版块收藏」——云端收藏的**版块**列表,与主题收藏夹无关)。
 * 直译 RN 侧 `store/board-favor.ts`。
 *
 * ## 按 uid 分桶
 *
 * 收藏是账号级数据,切换账号后不能拿别人的列表充数(**P1-02 的另一半**:票 14 把
 * 收藏反向索引按 uid 分了键,RN 版漏的是 TanStack Query 的 key —— 这里从一开始就分)。
 * 游客态不发请求(接口报「你必须先登录论坛」),列表恒空。
 *
 * ## 全部写操作乐观更新
 *
 * 设计稿的星标是「点了立刻变」,等一个来回的网络太钝;失败回滚并把服务端的话
 * 抛给调用方去 toast,成功后再重拉与服务端对齐(手动输 id 添加的版块,名字与
 * 合集身份要靠重拉列表才拿得到)。
 */
@Singleton
class BoardFavoriteRepository @Inject constructor(
  private val client: NgaClient,
) {

  data class FavoritesState(
    val loading: Boolean = false,
    val boards: List<Board> = emptyList(),
    val error: Throwable? = null,
    /** 上一次真正从服务端取到的时刻;0 = 还没取过 */
    val fetchedAt: Long = 0,
  )

  private val buckets = MutableStateFlow<Map<String, FavoritesState>>(emptyMap())
  val states: StateFlow<Map<String, FavoritesState>> = buckets.asStateFlow()

  private val lock = Mutex()

  fun stateOf(uid: String?): FavoritesState =
    if (uid == null) GUEST else buckets.value[uid] ?: FavoritesState()

  private fun put(uid: String, transform: (FavoritesState) -> FavoritesState) {
    val current = buckets.value[uid] ?: FavoritesState()
    buckets.value = buckets.value + (uid to transform(current))
  }

  /**
   * 保证列表是新的。列表页的星标每次进版块都要问一次「收了没」,不缓存就是每开一个
   * 版块多打一次接口(ADR-0002:能少打就少打);改动都走重拉,不靠这个 TTL 保新。
   */
  suspend fun ensureLoaded(uid: String?, now: Long = System.currentTimeMillis()) {
    if (uid == null) return
    val current = buckets.value[uid]
    if (current != null && current.fetchedAt != 0L && now - current.fetchedAt < STALE_MS) return
    if (current?.loading == true) return
    reload(uid, now)
  }

  suspend fun reload(uid: String?, now: Long = System.currentTimeMillis()) {
    if (uid == null) return
    // 网络这一段一律切到 IO(票 35):调用方常常是 `LaunchedEffect` / `viewModelScope`,
    // 那是 `AndroidUiDispatcher`(主线程)—— 请求链不许跑在那上面。仓库层保证,不靠调用方。
    withContext(Dispatchers.IO) {
      put(uid) { it.copy(loading = true) }
      try {
        val boards = fetchBoardFavorites(client)
        put(uid) { FavoritesState(loading = false, boards = boards, error = null, fetchedAt = now) }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        put(uid) { it.copy(loading = false) }
        throw cancelled
      } catch (error: Throwable) {
        put(uid) { it.copy(loading = false, error = error) }
      }
    }
  }

  /** 收藏(乐观插到最前——服务端就是新收藏在前的顺序)。 */
  suspend fun add(uid: String, board: Board) = mutate(uid) { previous ->
    put(uid) { it.copy(boards = listOf(board) + it.boards.filter { kept -> kept.id != board.id }) }
    runOrRollback(uid, previous) { addBoardFavorite(client, board.id) }
  }

  /** 取消收藏(乐观移除)。 */
  suspend fun remove(uid: String, board: Board) = mutate(uid) { previous ->
    put(uid) { it.copy(boards = it.boards.filter { kept -> kept.id != board.id }) }
    runOrRollback(uid, previous) { removeBoardFavorite(client, board.id) }
  }

  /**
   * 清空,返回删掉的列表给「撤销」用。
   * 服务端没有批量接口,`clearBoardFavorites` 里是**串行逐删**——收藏一般就十来个,
   * 不值得为它冒被风控的险(ADR-0002 的克制原则,这条是 RN 版唯一的一处克制)。
   */
  suspend fun clear(uid: String): List<Board> = withContext(Dispatchers.IO) {
    val previous = stateOf(uid).boards
    put(uid) { it.copy(boards = emptyList()) }
    try {
      val removed = clearBoardFavorites(client)
      reload(uid)
      removed
    } catch (error: Throwable) {
      put(uid) { it.copy(boards = previous) }
      reload(uid)
      throw error
    }
  }

  /** 撤销清空:逐个收回来。顺序反着加,服务端「新收藏在前」正好还原原顺序。 */
  suspend fun restore(uid: String, boards: List<Board>) = mutate(uid) { previous ->
    put(uid) { it.copy(boards = boards) }
    runOrRollback(uid, previous) {
      for (board in boards.reversed()) addBoardFavorite(client, board.id)
    }
  }

  /**
   * 「添加版面 ID」:先按输入乐观收藏,成功后从重拉的列表里找回这个版块 ——
   * 输入到底是 fid 还是 stid(合集)由服务端识别,列表条目带 stid 就是合集,
   * 名字也以服务端为准。找不到(理论上不会)就退回占位对象。
   */
  suspend fun addById(uid: String, id: Long, provisional: Board): Board {
    add(uid, provisional)
    return stateOf(uid).boards.firstOrNull { it.id == id || it.fid == id || it.stid == id }
      ?: provisional
  }

  private suspend fun mutate(uid: String, block: suspend (List<Board>) -> Unit) {
    withContext(Dispatchers.IO) {
      lock.withLock {
        val previous = stateOf(uid).boards
        block(previous)
      }
    }
  }

  private suspend fun runOrRollback(uid: String, previous: List<Board>, action: suspend () -> Unit) {
    try {
      action()
    } catch (error: Throwable) {
      put(uid) { it.copy(boards = previous) }
      throw error
    } finally {
      // 与服务端对齐:手输 id 加的版块,真名与合集身份只有重拉才知道
      runCatching { reload(uid) }
    }
  }

  private companion object {
    val GUEST = FavoritesState()

    /** RN 侧 `staleTime: 5 * 60 * 1000`。 */
    const val STALE_MS = 5L * 60 * 1000
  }
}

/**
 * 云端收藏列表的图标按 id 从分类树认领。
 *
 * 收藏接口(`forum_favor2`)只给 id/name/info,原样画出来整屏都是「斜纹圆底 + 首字」
 * 的占位;而分类树里同一个 id 的版块带着地址(`other.forum_icon_list` 拼的那个)。
 * 认不到的(手输 id 加的冷门版块、树里没有的合集)保持占位,下次分类树更新后自动补上。
 */
fun List<Board>.withIconsFrom(icons: Map<Long, String>): List<Board> {
  if (icons.isEmpty()) return this
  var patched = false
  val next = map { board ->
    if (board.iconUrl != null) return@map board
    val url = icons[board.id] ?: return@map board
    patched = true
    board.copy(iconUrl = url)
  }
  // 一个都没补到就把原列表还回去,省掉一次无谓的引用变化
  return if (patched) next else this
}
