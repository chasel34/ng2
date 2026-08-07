import type { Board, BoardCategory, BoardGroup, BoardTree } from './types'

/**
 * 分类树的本地缓存起底与 24 小时节流。
 *
 * 首页要做到：冷启动无网时用缓存直接渲染，有网时静默增量更新。
 * 这里只写策略，不碰存储实现——`BoardTreeStore` 由 `src/store` 用 MMKV 落地，
 * core 保持零 RN 依赖。
 */

/** 官方 Android v4 也是这个节流窗口（研究报告 B2：进版面时触发，24 小时最多一次）。 */
export const BOARD_TREE_TTL_MS = 24 * 60 * 60 * 1000

export interface CachedBoardTree {
  readonly tree: BoardTree
  /** 上一次真正从服务端取到的时刻，毫秒时间戳 */
  readonly fetchedAt: number
}

/** 缓存读写口子。读失败当没缓存处理，别让存储层的毛病拖垮首页。 */
export interface BoardTreeStore {
  read: () => CachedBoardTree | null
  write: (value: CachedBoardTree) => void
}

/** 缓存是否该刷新了。设备时钟往回跳（now < fetchedAt）一律按过期算，否则缓存会卡死。 */
export function isBoardTreeStale(fetchedAt: number, now: number, ttl = BOARD_TREE_TTL_MS): boolean {
  const elapsed = now - fetchedAt
  return elapsed < 0 || elapsed >= ttl
}

function mergeBoard(cached: Board | undefined, fresh: Board): Board {
  if (!cached) return fresh
  // 结构以服务端为准，只在服务端这次没给字段时用缓存值补齐
  const info = fresh.info ?? cached.info
  const iconUrl = fresh.iconUrl ?? cached.iconUrl
  return {
    ...fresh,
    ...(info === undefined ? {} : { info }),
    ...(iconUrl === undefined ? {} : { iconUrl }),
  }
}

function indexBoards(tree: BoardTree): Map<number, Board> {
  const index = new Map<number, Board>()
  for (const category of tree.categories) {
    for (const group of category.groups) {
      for (const board of group.boards) {
        if (!index.has(board.id)) index.set(board.id, board)
      }
    }
  }
  return index
}

/**
 * 把新拉到的树合进缓存：**分类/分组/版块的组成一律以服务端为准**（下线的版块要跟着消失），
 * 只有单个版块上服务端这次没下发的字段（副标题、图标）才回落到缓存值。
 */
export function mergeBoardTree(cached: BoardTree, fresh: BoardTree): BoardTree {
  const previous = indexBoards(cached)
  const categories: BoardCategory[] = fresh.categories.map((category) => {
    const groups: BoardGroup[] = category.groups.map((group) => ({
      ...group,
      boards: group.boards.map((board) => mergeBoard(previous.get(board.id), board)),
    }))
    return { ...category, groups }
  })
  return { categories, announcements: fresh.announcements }
}

export interface BoardTreeLoadResult extends CachedBoardTree {
  readonly source: 'cache' | 'network'
  /** 有缓存兜底时，这次静默失败的原因；调用方可以拿去提示「显示的是离线数据」 */
  readonly error?: unknown
}

export interface LoadBoardTreeOptions {
  readonly store: BoardTreeStore
  readonly fetchTree: () => Promise<BoardTree>
  /** 毫秒时间戳，默认取当前时间 */
  readonly now?: number
  readonly ttl?: number
}

function readCache(store: BoardTreeStore): CachedBoardTree | null {
  try {
    return store.read()
  } catch {
    return null
  }
}

/**
 * 取分类树，按下面的次序：
 *
 * 1. 缓存还在 24 小时内 → 直接用，一个请求都不发；
 * 2. 否则拉线上，成功就增量合并写回；
 * 3. 拉失败但有缓存 → 静默用缓存，失败原因随结果带出去；
 * 4. 拉失败又没缓存 → 抛出去，首页显示错误态。
 */
export async function loadBoardTree(options: LoadBoardTreeOptions): Promise<BoardTreeLoadResult> {
  const { store, fetchTree, ttl } = options
  const now = options.now ?? Date.now()
  const cached = readCache(store)

  if (cached && !isBoardTreeStale(cached.fetchedAt, now, ttl)) {
    return { ...cached, source: 'cache' }
  }

  try {
    const fetched = await fetchTree()
    const next: CachedBoardTree = {
      tree: cached ? mergeBoardTree(cached.tree, fetched) : fetched,
      fetchedAt: now,
    }
    store.write(next)
    return { ...next, source: 'network' }
  } catch (error) {
    if (!cached) throw error
    return { ...cached, source: 'cache', error }
  }
}
