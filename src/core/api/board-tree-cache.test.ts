import { describe, expect, it, vi } from 'vitest'

import { NgaError } from '../net/errors'
import {
  BOARD_TREE_TTL_MS,
  isBoardTreeStale,
  loadBoardTree,
  mergeBoardTree,
  type BoardTreeStore,
  type CachedBoardTree,
} from './board-tree-cache'
import type { Board, BoardTree } from './types'

const board = (id: number, name: string, extra: Partial<Board> = {}): Board => ({
  id,
  kind: 'board',
  fid: id,
  name,
  ...extra,
})

const tree = (boards: Board[]): BoardTree => ({
  categories: [{ id: 'other', name: '网事杂谈', groups: [{ id: 'g', name: '组', boards }] }],
  announcements: [],
})

/** 内存假实现，顺带记录写了几次。 */
function fakeStore(initial: CachedBoardTree | null = null) {
  let value = initial
  const writes: CachedBoardTree[] = []
  const store: BoardTreeStore = {
    read: () => value,
    write: (next) => {
      value = next
      writes.push(next)
    },
  }
  return { store, writes, current: () => value }
}

describe('isBoardTreeStale · 24 小时节流', () => {
  const t0 = 1_700_000_000_000

  it('未到 24 小时不算过期', () => {
    expect(isBoardTreeStale(t0, t0 + BOARD_TREE_TTL_MS - 1)).toBe(false)
  })

  it('刚好满 24 小时就算过期', () => {
    expect(isBoardTreeStale(t0, t0 + BOARD_TREE_TTL_MS)).toBe(true)
  })

  it('设备时钟往回跳时按过期处理，免得缓存永远刷不动', () => {
    expect(isBoardTreeStale(t0, t0 - 1000)).toBe(true)
  })
})

describe('mergeBoardTree · 静默增量更新', () => {
  it('结构以服务端为准：新增的收下、下线的丢掉', () => {
    const cached = tree([board(1, '甲'), board(2, '乙')])
    const fresh = tree([board(1, '甲'), board(3, '丙')])
    expect(mergeBoardTree(cached, fresh).categories[0]?.groups[0]?.boards.map((b) => b.id)).toEqual([
      1, 3,
    ])
  })

  it('服务端这次没给的 info / 图标用缓存里的补上', () => {
    const cached = tree([board(1, '甲', { info: '副标题', iconUrl: 'https://x/1.png' })])
    const fresh = tree([board(1, '甲改名')])
    const merged = mergeBoardTree(cached, fresh).categories[0]?.groups[0]?.boards[0]
    expect(merged).toMatchObject({ name: '甲改名', info: '副标题', iconUrl: 'https://x/1.png' })
  })

  it('服务端给了新值就用新值', () => {
    const cached = tree([board(1, '甲', { info: '旧', iconUrl: 'https://x/old.png' })])
    const fresh = tree([board(1, '甲', { info: '新', iconUrl: 'https://x/new.png' })])
    const merged = mergeBoardTree(cached, fresh).categories[0]?.groups[0]?.boards[0]
    expect(merged).toMatchObject({ info: '新', iconUrl: 'https://x/new.png' })
  })

  it('公告以服务端为准', () => {
    const cached: BoardTree = { ...tree([board(1, '甲')]), announcements: [{ id: 'a', title: '旧' }] }
    const fresh: BoardTree = { ...tree([board(1, '甲')]), announcements: [{ id: 'b', title: '新' }] }
    expect(mergeBoardTree(cached, fresh).announcements).toEqual([{ id: 'b', title: '新' }])
  })
})

describe('loadBoardTree', () => {
  const t0 = 1_700_000_000_000
  const cached = tree([board(1, '缓存里的版块')])
  const fresh = tree([board(2, '服务端的版块')])

  it('冷启动没缓存时拉线上，并写回缓存', async () => {
    const { store, current } = fakeStore()
    const fetchTree = vi.fn().mockResolvedValue(fresh)

    const result = await loadBoardTree({ store, fetchTree, now: t0 })

    expect(result.source).toBe('network')
    expect(result.tree).toEqual(fresh)
    expect(current()).toEqual({ tree: fresh, fetchedAt: t0 })
  })

  it('缓存还新鲜时一个请求都不发（24 小时节流）', async () => {
    const { store } = fakeStore({ tree: cached, fetchedAt: t0 })
    const fetchTree = vi.fn()

    const result = await loadBoardTree({ store, fetchTree, now: t0 + 1000 })

    expect(fetchTree).not.toHaveBeenCalled()
    expect(result).toMatchObject({ source: 'cache', tree: cached, fetchedAt: t0 })
  })

  it('缓存过期时静默增量更新', async () => {
    const { store, current } = fakeStore({ tree: cached, fetchedAt: t0 })
    const now = t0 + BOARD_TREE_TTL_MS
    const fetchTree = vi.fn().mockResolvedValue(fresh)

    const result = await loadBoardTree({ store, fetchTree, now })

    expect(result.source).toBe('network')
    expect(result.tree.categories[0]?.groups[0]?.boards.map((b) => b.id)).toEqual([2])
    expect(current()?.fetchedAt).toBe(now)
  })

  it('无网但有缓存：用缓存渲染，失败原因带出去而不是抛', async () => {
    const { store, writes } = fakeStore({ tree: cached, fetchedAt: t0 })
    const offline = new NgaError({ kind: 'network', message: '断网了' })
    const fetchTree = vi.fn().mockRejectedValue(offline)

    const result = await loadBoardTree({ store, fetchTree, now: t0 + BOARD_TREE_TTL_MS })

    expect(result).toMatchObject({ source: 'cache', tree: cached, error: offline })
    expect(writes).toEqual([])
  })

  it('无网且没缓存：抛出去，首页只能显示错误态', async () => {
    const { store } = fakeStore()
    const offline = new NgaError({ kind: 'network', message: '断网了' })

    await expect(loadBoardTree({ store, fetchTree: () => Promise.reject(offline) })).rejects.toBe(
      offline,
    )
  })

  it('缓存写坏了（read 抛异常）也不影响拉线上', async () => {
    const store: BoardTreeStore = {
      read: () => {
        throw new Error('MMKV 读失败')
      },
      write: () => {},
    }

    const result = await loadBoardTree({ store, fetchTree: () => Promise.resolve(fresh), now: t0 })

    expect(result).toMatchObject({ source: 'network', tree: fresh })
  })
})
