package com.chasel.ng2n.data.board

import com.chasel.ng2n.core.api.Board
import com.chasel.ng2n.core.api.BoardCategory
import com.chasel.ng2n.core.api.BoardGroup
import com.chasel.ng2n.core.api.BoardTree
import com.chasel.ng2n.data.settings.BOARD_TREE_TTL_MS
import com.chasel.ng2n.data.settings.isBoardTreeStale

/**
 * 分类树的本地缓存起底与 24 小时节流 —— 直译 `src/core/api/board-tree-cache.ts`。
 *
 * 票 14 已经落了**存储壳**(`data/settings/BoardTreeCache.kt`:一份不透明载荷 +
 * `fetchedAt` + [isBoardTreeStale]),那张票的 KDoc 明写「树的数据类型与合并策略归票 16」。
 * 这里补上另一半:合并策略与那条取数次序。
 *
 * 写成**纯 suspend 函数 + 两个接缝**(存储、取数)而不是塞进仓库:
 * 这条次序有四个分支,每个分支都有账(见下),JVM 单测要能逐条钉住。
 */

/** 缓存里那棵树 + 取到它的时刻。 */
data class BoardTreeSnapshot(val tree: BoardTree, val fetchedAt: Long)

/** 缓存读写口子。读失败当没缓存处理,别让存储层的毛病拖垮首页。 */
interface BoardTreeStore {
  suspend fun read(): BoardTreeSnapshot?
  suspend fun write(snapshot: BoardTreeSnapshot)
}

enum class BoardTreeSource { CACHE, NETWORK }

data class BoardTreeLoadResult(
  val tree: BoardTree,
  val fetchedAt: Long,
  val source: BoardTreeSource,
  /** 有缓存兜底时,这次静默失败的原因;调用方可以拿去提示「显示的是离线数据」 */
  val error: Throwable? = null,
)

private fun mergeBoard(cached: Board?, fresh: Board): Board {
  if (cached == null) return fresh
  // 结构以服务端为准,只在服务端这次没给字段时用缓存值补齐
  return fresh.copy(
    info = fresh.info ?: cached.info,
    iconUrl = fresh.iconUrl ?: cached.iconUrl,
  )
}

private fun indexBoards(tree: BoardTree): Map<Long, Board> {
  val index = LinkedHashMap<Long, Board>()
  for (category in tree.categories) {
    for (group in category.groups) {
      for (board in group.boards) {
        if (!index.containsKey(board.id)) index[board.id] = board
      }
    }
  }
  return index
}

/**
 * 把新拉到的树合进缓存:**分类/分组/版块的组成一律以服务端为准**(下线的版块要跟着消失),
 * 只有单个版块上服务端这次没下发的字段(副标题、图标)才回落到缓存值。
 */
fun mergeBoardTree(cached: BoardTree, fresh: BoardTree): BoardTree {
  val previous = indexBoards(cached)
  val categories: List<BoardCategory> = fresh.categories.map { category ->
    val groups: List<BoardGroup> = category.groups.map { group ->
      group.copy(boards = group.boards.map { mergeBoard(previous[it.id], it) })
    }
    category.copy(groups = groups)
  }
  return BoardTree(categories = categories, announcements = fresh.announcements)
}

/**
 * 取分类树,按下面的次序:
 *
 * 1. 缓存还在 24 小时内 → 直接用,**一个请求都不发**;
 * 2. 否则拉线上,成功就增量合并写回;
 * 3. 拉失败但有缓存 → 静默用缓存,失败原因随结果带出去;
 * 4. 拉失败又没缓存 → 抛出去,首页显示错误态。
 *
 * @param now 毫秒时间戳,**由调用方传入**(纯函数不看表,才测得动)
 */
suspend fun loadBoardTree(
  store: BoardTreeStore,
  fetchTree: suspend () -> BoardTree,
  now: Long,
  ttl: Long = BOARD_TREE_TTL_MS,
  /** 强制联网(下拉刷新):跳过 TTL 那一步,但缓存兜底那两条照旧 */
  force: Boolean = false,
): BoardTreeLoadResult {
  val cached = runCatching { store.read() }.getOrNull()

  if (!force && cached != null && !isBoardTreeStale(cached.fetchedAt, now, ttl)) {
    return BoardTreeLoadResult(cached.tree, cached.fetchedAt, BoardTreeSource.CACHE)
  }

  return try {
    val fetched = fetchTree()
    val next = BoardTreeSnapshot(
      tree = if (cached == null) fetched else mergeBoardTree(cached.tree, fetched),
      fetchedAt = now,
    )
    runCatching { store.write(next) }
    BoardTreeLoadResult(next.tree, next.fetchedAt, BoardTreeSource.NETWORK)
  } catch (cancelled: kotlinx.coroutines.CancellationException) {
    // 协程取消不是「拉失败」,不该被缓存兜底吞掉(票 07 在热帖并发那儿踩的同一条)
    throw cancelled
  } catch (error: Throwable) {
    if (cached == null) throw error
    BoardTreeLoadResult(cached.tree, cached.fetchedAt, BoardTreeSource.CACHE, error)
  }
}
