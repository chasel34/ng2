import { describe, expect, it } from 'vitest'

import {
  EMPTY_TOPIC_FAVOR_INDEX,
  applyFavoriteChange,
  diffFolderSelection,
  foldersOfTopic,
  parseTopicFavorIndex,
  pruneFolders,
  seedFolderTopics,
} from './topic-favor-index'

describe('applyFavoriteChange', () => {
  it('收藏到多个夹后两个夹都记着（一个主题可同时归属多夹）', () => {
    let index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, {
      tid: 45150945,
      folderId: 7,
      favored: true,
    })
    index = applyFavoriteChange(index, { tid: 45150945, folderId: 3, favored: true })

    expect(foldersOfTopic(index, 45150945)).toEqual([3, 7])
  })

  it('取消一个夹不动其他夹（11 票验收项）', () => {
    let index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, { folderId: 7, tids: [1] })
    index = seedFolderTopics(index, { folderId: 3, tids: [1] })
    index = applyFavoriteChange(index, { tid: 1, folderId: 7, favored: false })

    expect(foldersOfTopic(index, 1)).toEqual([3])
  })

  it('重复收藏同一个夹不会记两条', () => {
    let index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, { tid: 1, folderId: 7, favored: true })
    index = applyFavoriteChange(index, { tid: 1, folderId: 7, favored: true })

    expect(foldersOfTopic(index, 1)).toEqual([7])
  })

  it('最后一个夹取消掉后整条记录消失，不留空数组', () => {
    let index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, { tid: 1, folderId: 7, favored: true })
    index = applyFavoriteChange(index, { tid: 1, folderId: 7, favored: false })

    expect(index).toEqual({})
  })

  it('没记录过的主题每次拿到同一个空数组（当 selector 用时快照要稳）', () => {
    expect(foldersOfTopic(EMPTY_TOPIC_FAVOR_INDEX, 1)).toBe(foldersOfTopic({ '2': [7] }, 1))
  })

  it('取消一个从没记过的夹是空操作', () => {
    expect(applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, { tid: 1, folderId: 7, favored: false })).toEqual(
      {},
    )
  })
})

describe('seedFolderTopics', () => {
  it('把一页列表里的 tid 都记进这个夹', () => {
    const index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, { folderId: 7, tids: [11, 22, 22] })

    expect(foldersOfTopic(index, 11)).toEqual([7])
    expect(foldersOfTopic(index, 22)).toEqual([7])
  })

  it('只翻了一页时不敢清记录：没出现的主题可能在后面几页', () => {
    let index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, { tid: 99, folderId: 7, favored: true })
    index = seedFolderTopics(index, { folderId: 7, tids: [11] })

    expect(foldersOfTopic(index, 99)).toEqual([7])
  })

  it('整个夹就这一页时，清掉本机记错的归属', () => {
    let index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, { tid: 99, folderId: 7, favored: true })
    index = applyFavoriteChange(index, { tid: 99, folderId: 3, favored: true })
    index = seedFolderTopics(index, { folderId: 7, tids: [11], complete: true })

    // 7 号夹的全集里没有 99，所以只摘掉 7；99 在 3 号夹里的归属不受影响
    expect(foldersOfTopic(index, 99)).toEqual([3])
    expect(foldersOfTopic(index, 11)).toEqual([7])
  })

  it('空夹（complete 且一条都没有）把这个夹的记录清干净', () => {
    let index = applyFavoriteChange(EMPTY_TOPIC_FAVOR_INDEX, { tid: 99, folderId: 7, favored: true })
    index = seedFolderTopics(index, { folderId: 7, tids: [], complete: true })

    expect(index).toEqual({})
  })
})

describe('pruneFolders', () => {
  it('删掉的夹留下的归属一并清掉', () => {
    let index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, { folderId: 7, tids: [1, 2] })
    index = seedFolderTopics(index, { folderId: 3, tids: [1] })
    index = pruneFolders(index, [3])

    expect(foldersOfTopic(index, 1)).toEqual([3])
    // 2 只在被删的 7 号夹里，整条记录就没了
    expect(index).toEqual({ '1': [3] })
  })

  it('没有夹被删时原样返回', () => {
    const index = seedFolderTopics(EMPTY_TOPIC_FAVOR_INDEX, { folderId: 7, tids: [1] })
    expect(pruneFolders(index, [3, 7])).toBe(index)
  })
})

describe('diffFolderSelection', () => {
  it('只算改动过的夹，没动的不发请求', () => {
    expect(diffFolderSelection([3, 7], [7, 9])).toEqual({ added: [9], removed: [3] })
  })

  it('一个都没改时两边都是空', () => {
    expect(diffFolderSelection([3, 7], [7, 3])).toEqual({ added: [], removed: [] })
  })

  it('从没收藏过到勾了两个夹', () => {
    expect(diffFolderSelection([], [7, 3])).toEqual({ added: [3, 7], removed: [] })
  })

  it('全部取消', () => {
    expect(diffFolderSelection([3, 7], [])).toEqual({ added: [], removed: [3, 7] })
  })
})

describe('parseTopicFavorIndex', () => {
  it('读回落盘的索引', () => {
    expect(parseTopicFavorIndex({ '1': [7, 3, 3] })).toEqual({ '1': [3, 7] })
  })

  it('坏条目跳过，整体不炸', () => {
    expect(
      parseTopicFavorIndex({
        '1': [7],
        '0': [7],
        abc: [7],
        '2': '不是数组',
        '3': ['不是数字'],
        '4': [],
      }),
    ).toEqual({ '1': [7] })
  })

  it('不是对象时给一份空索引', () => {
    expect(parseTopicFavorIndex(undefined)).toEqual({})
    expect(parseTopicFavorIndex([1, 2])).toEqual({})
    expect(parseTopicFavorIndex(null)).toEqual({})
  })
})
