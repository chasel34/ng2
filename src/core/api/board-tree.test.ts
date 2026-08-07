import { describe, expect, it } from 'vitest'

import { decodeGb18030 } from '../net/encoding/gb18030'
import { parseNgaJson } from '../net/envelope'
import { NgaError } from '../net/errors'
import { readFixtureBytes } from './__fixtures__'
import { parseBoardTree, pickActiveAnnouncement } from './board-tree'
import type { BoardTree } from './types'

/** 真实抓包样本解析一遍，后面大量断言复用同一棵树。 */
function fixtureTree(): BoardTree {
  const envelope = parseNgaJson(decodeGb18030(readFixtureBytes('homeCategory')))
  return parseBoardTree(envelope.root)
}

const allBoards = (tree: BoardTree) =>
  tree.categories.flatMap((category) => category.groups.flatMap((group) => group.boards))

/** 按 id 在整棵树里找第一个。 */
const findBoard = (tree: BoardTree, id: number) =>
  allBoards(tree).find((board) => board.id === id)

/** 限定在某个分类里找——样本里推荐版块与原分类会重复收录同一个 fid。 */
const findIn = (tree: BoardTree, categoryId: string, id: number) =>
  tree.categories
    .find((category) => category.id === categoryId)
    ?.groups.flatMap((group) => group.boards)
    .find((board) => board.id === id)

describe('parseBoardTree · 真实分类树样本', () => {
  it('分类按服务端顺序展开，推荐版块排在最前', () => {
    const tree = fixtureTree()
    expect(tree.categories.map((category) => category.name)).toEqual([
      '推荐版块',
      '网事杂谈',
      '魔兽世界',
      '特约专区',
      '手机游戏',
      '传统游戏',
      '网络游戏',
      '社区事务',
    ])
    expect(tree.categories[0]?.id).toBe('new')
  })

  it('分类 → 分组 → 版块三层都展开', () => {
    const tree = fixtureTree()
    const other = tree.categories.find((category) => category.id === 'other')
    expect(other?.groups.map((group) => group.name)).toEqual(['网事杂谈', 'IT软硬件', '二次元综合'])
    expect(other?.groups[0]?.boards.length).toBe(30)
    // 673 个版块，外加推荐版块分类重复收录的 4 个
    expect(allBoards(tree).length).toBe(673 + 4)
  })

  it('普通版块取 fid', () => {
    const board = findIn(fixtureTree(), 'wow', 7)
    expect(board).toMatchObject({
      id: 7,
      kind: 'board',
      fid: 7,
      name: '艾泽拉斯议事厅',
      info: '魔兽主讨论区',
    })
    expect(board?.stid).toBeUndefined()
  })

  it('合集取 stid 而不是 fid（stid 优先，CONTEXT.md「合集」）', () => {
    // 样本里「考研讨论」挂在 fid -7 下，真正要传给 thread.php 的是 stid
    const board = findBoard(fixtureTree(), 39827852)
    expect(board).toMatchObject({
      id: 39827852,
      kind: 'collection',
      fid: -7,
      stid: 39827852,
      name: '考研讨论',
    })
  })

  it('样本里 286 个合集，其余是普通版块', () => {
    const boards = allBoards(fixtureTree())
    const collections = boards.filter((board) => board.kind === 'collection')
    expect(collections.length).toBe(286)
    expect(collections.every((board) => board.id === board.stid)).toBe(true)
    expect(
      boards.filter((board) => board.kind === 'board').every((board) => board.id === board.fid),
    ).toBe(true)
  })
})

describe('parseBoardTree · 版块图标', () => {
  it('服务端图标清单里登记过的版块给出图标地址', () => {
    // f_px_l + id + f_sx_l，抓包当天实测 200
    expect(findBoard(fixtureTree(), 7)?.iconUrl).toBe(
      'https://img4.nga.cn/ngabbs/nga_classic/f/app/7.png',
    )
  })

  it('合集用自己的 stid 拼图标地址', () => {
    expect(findBoard(fixtureTree(), 11291877)?.iconUrl).toBe(
      'https://img4.nga.cn/ngabbs/nga_classic/f/app/11291877.png',
    )
  })

  it('没登记图标的版块不给地址（免得整屏打 404，UI 直接走首字占位）', () => {
    // 元梦之星 stid=38727491 不在图标清单里，实测 404
    expect(findBoard(fixtureTree(), 38727491)?.iconUrl).toBeUndefined()
  })

  it('样本里有图标的版块占比合理', () => {
    const boards = allBoards(fixtureTree())
    const withIcon = boards.filter((board) => board.iconUrl !== undefined)
    expect(withIcon.length).toBeGreaterThan(400)
    expect(withIcon.length).toBeLessThan(boards.length)
  })
})

describe('parseBoardTree · 公告', () => {
  it('解析 appcolumn_notis', () => {
    expect(fixtureTree().announcements).toEqual([
      {
        id: '1726212669-0',
        title: 'NGA APP评分功能上线',
        url: 'https://ngabbs.com/read.php?tid=41651147',
        startAt: 1726213200,
        endAt: 1726558800,
      },
    ])
  })

  it('pickActiveAnnouncement 只挑展示窗口内的那条', () => {
    const list = [
      { id: 'a', title: '过期', startAt: 100, endAt: 200 },
      { id: 'b', title: '生效中', startAt: 300, endAt: 500 },
      { id: 'c', title: '还没开始', startAt: 900 },
    ]
    expect(pickActiveAnnouncement(list, 400_000)?.id).toBe('b')
    expect(pickActiveAnnouncement(list, 250_000)).toBeUndefined()
    expect(pickActiveAnnouncement(list, 1_000_000)?.id).toBe('c')
  })

  it('没有窗口字段的公告一直有效', () => {
    const list = [{ id: 'a', title: '常驻' }]
    expect(pickActiveAnnouncement(list, 0)?.id).toBe('a')
  })
})

describe('parseBoardTree · 类型容错', () => {
  const wrap = (data: unknown, other?: unknown) => ({ data, other })

  it('顶层没有 data 时抛 parse 错误，好让调用方留用旧缓存', () => {
    expect(() => parseBoardTree({ error: { 0: '未登录' } })).toThrow(NgaError)
    expect(() => parseBoardTree('nonsense')).toThrow(NgaError)
  })

  it('一个版块都解析不出来时也抛 parse 错误', () => {
    expect(() => parseBoardTree(wrap({ 0: { _id: 'x', name: '空分类', groups: {} } }))).toThrow(
      NgaError,
    )
  })

  it('坏条目被跳过，好条目照常解析', () => {
    const tree = parseBoardTree(
      wrap({
        0: {
          _id: 'x',
          name: '分类',
          groups: {
            0: {
              name: '组',
              id: 1,
              forums: {
                0: { fid: 1, name: '好版块' },
                1: 'not an object',
                2: { fid: 2 }, // 没名字
                3: { name: '没有 id' },
                4: { fid: 'abc', name: 'id 不是数字' },
                5: { fid: 3, name: 6666 }, // 名字不是字符串
              },
            },
            1: 'not a group',
          },
        },
        1: 'not a category',
      }),
    )
    expect(allBoards(tree).map((board) => board.name)).toEqual(['好版块'])
  })

  it('stid 为 0 表示「不是合集」，不能当成合集的 id', () => {
    const tree = parseBoardTree(
      wrap({
        0: {
          _id: 'x',
          name: '分类',
          groups: {
            0: {
              name: '组',
              forums: {
                0: { fid: 650, stid: 0, name: '普通版块' },
                1: { fid: 0, name: 'fid 也不能是 0' },
              },
            },
          },
        },
      }),
    )
    expect(allBoards(tree)).toEqual([{ id: 650, kind: 'board', fid: 650, name: '普通版块' }])
  })

  it('数字键当数组用，顺序按数字大小而不是字典序', () => {
    const forums: Record<string, unknown> = {}
    for (let i = 0; i < 12; i += 1) forums[String(i)] = { fid: i + 1, name: `版块${i}` }
    const tree = parseBoardTree(
      wrap({ 0: { _id: 'x', name: '分类', groups: { 0: { name: '组', forums } } } }),
    )
    expect(allBoards(tree).map((board) => board.fid)).toEqual([
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
    ])
  })

  it('info 为空串时不落进结果', () => {
    const tree = parseBoardTree(
      wrap({
        0: { _id: 'x', name: '分类', groups: { 0: { name: '组', forums: { 0: { fid: 1, name: 'A', info: '' } } } } },
      }),
    )
    expect(allBoards(tree)[0]?.info).toBeUndefined()
  })

  it('图标清单缺失或格式不对时，所有版块都没有图标而不是崩掉', () => {
    const tree = parseBoardTree(
      wrap(
        { 0: { _id: 'x', name: '分类', groups: { 0: { name: '组', forums: { 0: { fid: 1, name: 'A' } } } } } },
        { forum_icon_list: 'garbage' },
      ),
    )
    expect(allBoards(tree)[0]?.iconUrl).toBeUndefined()
  })

  it('没有 _id 的分类用序号兜底，重名分组也各自成组', () => {
    const tree = parseBoardTree(
      wrap({
        0: { name: '分类', groups: { 0: { name: '组', forums: { 0: { fid: 1, name: 'A' } } } } },
      }),
    )
    expect(tree.categories[0]?.id).toBe('category-0')
    expect(tree.categories[0]?.groups[0]?.id).toBe('category-0-group-0')
  })
})
