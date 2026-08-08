import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import {
  addBoardFavorite,
  clearBoardFavorites,
  fetchBoardFavorites,
  parseBoardFavorites,
  parseBoardIdInput,
  removeBoardFavorite,
} from './board-favor'

/** 依次用真实抓包字节应答的假传输层,顺带把请求录下来。 */
function fixtureTransport(...names: ApiFixtureName[]) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    const name = names[Math.min(requests.length, names.length) - 1]
    if (name === undefined) throw new Error('fixture 队列空了')
    return {
      status: 200,
      contentType: fixtureContentType(name),
      body: readFixtureBytes(name),
    }
  })
  return { transport, requests }
}

describe('fetchBoardFavorites', () => {
  it('打的是 nuke.php?__lib=forum_favor2&__act=forum_favor,form 带 action=get', async () => {
    const { transport, requests } = fixtureTransport('forumFavorList')

    await fetchBoardFavorites(createNgaFetcher({ transport }))

    expect(requests).toHaveLength(1)
    const [request] = requests
    expect(request?.method).toBe('POST')
    expect(request?.url).toContain('/nuke.php?')
    expect(request?.url).toContain('__lib=forum_favor2')
    expect(request?.url).toContain('__act=forum_favor')
    expect(request?.body).toContain('action=get')
  })

  it('GBK 响应解析成版块列表,合集与负 fid 版块都认得,服务端顺序原样保留', async () => {
    const { transport } = fixtureTransport('forumFavorList')

    const boards = await fetchBoardFavorites(createNgaFetcher({ transport }))

    expect(boards).toEqual([
      { id: 31576766, kind: 'collection', stid: 31576766, name: '联运网页游戏' },
      { id: -7, kind: 'board', fid: -7, name: '网事杂谈' },
    ])
  })

  it('空收藏(data 是 {})返回空数组而不是报错', async () => {
    const { transport } = fixtureTransport('forumFavorEmpty')

    await expect(fetchBoardFavorites(createNgaFetcher({ transport }))).resolves.toEqual([])
  })
})

describe('parseBoardFavorites', () => {
  it('坏条目跳过、整体不炸;info 空串当没有', () => {
    const boards = parseBoardFavorites({
      '0': {
        '0': { id: -7, fid: -7, name: '网事杂谈', info: '' },
        '1': { id: 9, fid: 9 }, // 缺 name
        '2': '不是对象',
        '3': { id: 0, fid: 0, name: '全是 0' },
        '4': { id: 459, fid: 459, name: '手机综合讨论', info: '手机' },
      },
    })

    expect(boards).toEqual([
      { id: -7, kind: 'board', fid: -7, name: '网事杂谈' },
      { id: 459, kind: 'board', fid: 459, name: '手机综合讨论', info: '手机' },
    ])
  })

  it('顶层不是对象一律空数组', () => {
    expect(parseBoardFavorites(undefined)).toEqual([])
    expect(parseBoardFavorites('x')).toEqual([])
  })
})

describe('addBoardFavorite / removeBoardFavorite', () => {
  it('add 走 form action=add&fid=<id>,「操作成功」不抛', async () => {
    const { transport, requests } = fixtureTransport('forumFavorWriteOk')

    await addBoardFavorite(createNgaFetcher({ transport }), -7)

    expect(requests[0]?.body).toContain('action=add')
    expect(requests[0]?.body).toContain('fid=-7')
  })

  it('合集也把 stid 当 fid 传(实测服务端自己识别)', async () => {
    const { transport, requests } = fixtureTransport('forumFavorWriteOk')

    await addBoardFavorite(createNgaFetcher({ transport }), 31576766)

    expect(requests[0]?.body).toContain('fid=31576766')
  })

  it('重复收藏的 server 错误「你已经收藏了这个版面」吞掉当成功', async () => {
    const { transport } = fixtureTransport('forumFavorAlready')

    await expect(addBoardFavorite(createNgaFetcher({ transport }), -7)).resolves.toBeUndefined()
  })

  it('del 走 form action=del&fid=<id>', async () => {
    const { transport, requests } = fixtureTransport('forumFavorWriteOk')

    await removeBoardFavorite(createNgaFetcher({ transport }), -7)

    expect(requests[0]?.body).toContain('action=del')
    expect(requests[0]?.body).toContain('fid=-7')
  })
})

describe('clearBoardFavorites', () => {
  it('先 get 再逐个 del,返回删掉的列表供撤销', async () => {
    const { transport, requests } = fixtureTransport(
      'forumFavorList',
      'forumFavorWriteOk',
      'forumFavorWriteOk',
    )

    const removed = await clearBoardFavorites(createNgaFetcher({ transport }))

    expect(removed.map((board) => board.id)).toEqual([31576766, -7])
    expect(requests).toHaveLength(3)
    expect(requests[0]?.body).toContain('action=get')
    expect(requests[1]?.body).toContain('action=del')
    expect(requests[1]?.body).toContain('fid=31576766')
    expect(requests[2]?.body).toContain('action=del')
    expect(requests[2]?.body).toContain('fid=-7')
  })

  it('空收藏时一次 del 都不发', async () => {
    const { transport, requests } = fixtureTransport('forumFavorEmpty')

    await expect(clearBoardFavorites(createNgaFetcher({ transport }))).resolves.toEqual([])
    expect(requests).toHaveLength(1)
  })
})

describe('parseBoardIdInput', () => {
  it('十进制整数照收,fid 可以是负数', () => {
    expect(parseBoardIdInput('459')).toBe(459)
    expect(parseBoardIdInput(' -7 ')).toBe(-7)
    expect(parseBoardIdInput('31576766')).toBe(31576766)
  })

  it('0、空串、非数字一律不认', () => {
    expect(parseBoardIdInput('0')).toBeUndefined()
    expect(parseBoardIdInput('')).toBeUndefined()
    expect(parseBoardIdInput('-7bcf72')).toBeUndefined()
    expect(parseBoardIdInput('7.5')).toBeUndefined()
    expect(parseBoardIdInput('abc')).toBeUndefined()
  })
})
