import { describe, expect, it, vi } from 'vitest'

import { decodeGb18030 } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import {
  fetchBoardSearch,
  fetchTopicSearch,
  parseBoardSearch,
  parseUserSearchInput,
} from './search'
import { parseTopicList } from './topic-list'
import { fetchUserProfileByName } from './user-profile'

function fixtureTransport(name: ApiFixtureName) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: fixtureContentType(name),
      body: readFixtureBytes(name),
    }
  })
  return { transport, requests }
}

const fixtureData = (name: ApiFixtureName): unknown =>
  (JSON.parse(decodeGb18030(readFixtureBytes(name))) as { data: unknown }).data

describe('关键词编码（票 15 的核心差异，API 文档 §0.5）', () => {
  it('thread.php 的 key 按 UTF-8 编码，并保留 __inchst=UTF8 声明', async () => {
    const { transport, requests } = fixtureTransport('threadSearchKey')
    await fetchTopicSearch(createNgaFetcher({ transport }), { key: '炉石', page: 1 })

    const url = requests[0]!.url
    expect(url).toContain('/thread.php?')
    expect(url).toContain('key=%E7%82%89%E7%9F%B3')
    expect(url).toContain('__inchst=UTF8')
  })

  it('forum.php 的 key 按 GBK 编码，且不再声明 __inchst=UTF8', async () => {
    const { transport, requests } = fixtureTransport('forumSearchKey')
    await fetchBoardSearch(createNgaFetcher({ transport }), { key: '炉石' })

    const url = requests[0]!.url
    expect(url).toContain('/forum.php?')
    expect(url).toContain('key=%C2%AF%CA%AF')
    // GBK 参数在场时必须撤掉 UTF8 声明，否则服务端按 UTF-8 解 GBK 字节
    expect(url).not.toContain('__inchst')
  })

  it('同一个词两条路编码出来的字节不一样——这就是差异本身', async () => {
    const topics = fixtureTransport('threadSearchKey')
    await fetchTopicSearch(createNgaFetcher({ transport: topics.transport }), {
      key: '炉石',
      page: 1,
    })
    const boards = fixtureTransport('forumSearchKey')
    await fetchBoardSearch(createNgaFetcher({ transport: boards.transport }), { key: '炉石' })

    expect(topics.requests[0]!.url).not.toContain('%C2%AF%CA%AF')
    expect(boards.requests[0]!.url).not.toContain('%E7%82%89%E7%9F%B3')
  })
})

describe('fetchTopicSearch 的参数组合（本版 / 全站 / 含正文）', () => {
  const search = async (
    options: Partial<Parameters<typeof fetchTopicSearch>[1]>,
  ): Promise<string> => {
    const { transport, requests } = fixtureTransport('threadSearchKey')
    await fetchTopicSearch(createNgaFetcher({ transport }), {
      key: '炉石',
      page: 1,
      ...options,
    })
    return requests[0]!.url
  }

  it('全站：不带 fid/stid，也不带 content', async () => {
    const url = await search({})
    expect(url).not.toContain('fid=')
    expect(url).not.toContain('stid=')
    expect(url).not.toContain('content=')
  })

  it('本版：普通版块带 fid（负 fid 原样传）', async () => {
    const url = await search({ boardId: -7, kind: 'board' })
    expect(url).toContain('fid=-7')
    expect(url).not.toContain('stid=')
  })

  it('本版：合集带 stid 而不是 fid', async () => {
    const url = await search({ boardId: 31576766, kind: 'collection' })
    expect(url).toContain('stid=31576766')
    expect(url).not.toContain('fid=')
  })

  it('含正文：多一个 content=1，翻页参数照常', async () => {
    const url = await search({ boardId: -7, kind: 'board', searchContent: true, page: 3 })
    expect(url).toContain('content=1')
    expect(url).toContain('fid=-7')
    expect(url).toContain('page=3')
  })
})

describe('主题搜索结果（真实样本：全站搜「炉石」）', () => {
  it('复用 parseTopicList 解出整页，__ROWS 是有效总数', async () => {
    const { transport } = fixtureTransport('threadSearchKey')
    const list = await fetchTopicSearch(createNgaFetcher({ transport }), {
      key: '炉石',
      page: 1,
    })

    // 服务端下发 34 条，其中 4 条是 denied 提示行（下一条用例专门管它）
    expect(list.topics).toHaveLength(30)
    expect(list.totalRows).toBe(46020)
    expect(list.rowsPerPage).toBe(35)
    expect(list.topics[0]?.tid).toBe(47332920)
    expect(list.topics[0]?.subject).toContain('炉石')
    // content=1 实测也不带 __P：搜索结果统一是普通主题行
    expect(list.topics.every((topic) => topic.reply === undefined)).toBe(true)
  })

  it('标题反转义（真实样本：搜「第六感」，M2 遗留缺陷 1）', async () => {
    const { transport } = fixtureTransport('threadSearchSixthSense')
    const list = await fetchTopicSearch(createNgaFetcher({ transport }), { key: '第六感', page: 1 })

    expect(list.topics[0]?.tid).toBe(47334898)
    expect(list.topics[0]?.subject).toBe('<第六感>那个小孩能看到鬼魂，nga有人也能看到吗')
  })

  it('服务端提示行不进结果（真实样本：搜「第六感」，M2 遗留缺陷 3）', async () => {
    const { transport } = fixtureTransport('threadSearchSixthSense')
    const list = await fetchTopicSearch(createNgaFetcher({ transport }), { key: '第六感', page: 1 })

    // 服务端下发 34 条，其中 10 条是 denied 提示行
    expect(parseTopicList(fixtureData('threadSearchSixthSense')).topics).toHaveLength(34)
    expect(list.topics).toHaveLength(24)
    expect(list.topics.some((topic) => topic.denied)).toBe(false)
    expect(list.topics.every((topic) => topic.author !== '')).toBe(true)
    // 总条数是服务端给的命中数，不跟着过滤走——翻页判据要和它对齐
    expect(list.totalRows).toBe(165)
  })

  it('过期占位只在搜索里滤掉：同样的行在「我的回复」里要留着', () => {
    // thread-user-replies 末尾 8 条就是同款 denied 行，那条路径不经过搜索的过滤
    const replies = parseTopicList(fixtureData('threadUserReplies'))
    expect(replies.topics.filter((topic) => topic.denied).length).toBeGreaterThan(0)
  })

  it('没有结果 / 翻过头：假错误归一成空页，而不是抛错', async () => {
    const transport = vi.fn(
      async (): Promise<HttpResponse> => ({
        status: 200,
        // 这条向量手写成 UTF-8（真实响应是 GBK，见 threadUserRepliesEnd 的同款样本）
        contentType: 'text/javascript; charset=UTF-8',
        body: new TextEncoder().encode(
          '{"error":{"0":"2048:没有符合条件的结果"},"data":{"__MESSAGE":{"0":2048}},"time":1}',
        ),
      }),
    )
    const list = await fetchTopicSearch(createNgaFetcher({ transport }), { key: 'x', page: 99 })
    expect(list.topics).toEqual([])
  })
})

describe('版块搜索结果（真实样本：搜「炉石」）', () => {
  const items = parseBoardSearch(fixtureData('forumSearchKey'))

  it('解出全部条目，顺序照服务端的 relevance 排序', () => {
    expect(items).toHaveLength(100)
    expect(items[0]?.board).toMatchObject({ id: 422, kind: 'board', fid: 422, name: '炉石传说' })
    // stid 为 0 的普通版块不能被当成合集
    expect(items[0]?.board.stid).toBeUndefined()
  })

  it('合集条目 stid 优先：id 取 stid，宿主版块的 fid 保留', () => {
    const collection = items.find((item) => item.board.kind === 'collection')
    expect(collection).toBeDefined()
    expect(collection?.board.stid).toBeDefined()
    expect(collection?.board.id).toBe(collection?.board.stid)
    // 合集行的 fid 是宿主版块（跳转要用 stid，不能拿 fid 当身份）
    expect(collection?.board.fid).not.toBe(collection?.board.id)
  })

  it('上级版块名解出来做来源标注', () => {
    expect(items[0]?.parentName).toBe('暴雪游戏')
  })

  it('fetchBoardSearch 走完整链路拿到同样的结果', async () => {
    const { transport } = fixtureTransport('forumSearchKey')
    const fetched = await fetchBoardSearch(createNgaFetcher({ transport }), { key: '炉石' })
    expect(fetched).toEqual(items)
  })

  it('没找到版面（真实样本）：假错误 + 只剩 __MESSAGE 的 data，解出空数组', async () => {
    const { transport } = fixtureTransport('forumSearchNone')
    const fetched = await fetchBoardSearch(createNgaFetcher({ transport }), { key: '不存在' })
    expect(fetched).toEqual([])
  })
})

describe('parseUserSearchInput（纯数字按 uid，否则按用户名）', () => {
  it('纯数字是 uid', () => {
    expect(parseUserSearchInput('41417929')).toEqual({ kind: 'uid', uid: 41417929 })
    expect(parseUserSearchInput('  42  ')).toEqual({ kind: 'uid', uid: 42 })
  })

  it('带任何非数字就按用户名查——NGA 用户名可以带数字', () => {
    expect(parseUserSearchInput('BugenZhao')).toEqual({ kind: 'username', username: 'BugenZhao' })
    expect(parseUserSearchInput('冷面比面筋好吃')).toEqual({
      kind: 'username',
      username: '冷面比面筋好吃',
    })
    expect(parseUserSearchInput('user123')).toEqual({ kind: 'username', username: 'user123' })
  })

  it('空输入与非法 uid 返回 undefined / 按名字兜底', () => {
    expect(parseUserSearchInput('')).toBeUndefined()
    expect(parseUserSearchInput('   ')).toBeUndefined()
    // 大到不安全的整数不能当 uid 传出去，按名字查还有机会命中
    expect(parseUserSearchInput('99999999999999999999')).toEqual({
      kind: 'username',
      username: '99999999999999999999',
    })
  })
})

describe('fetchUserProfileByName', () => {
  it('走 ucp 资料接口，username 按 UTF-8 进 query，带上必需的 Referer', async () => {
    const { transport, requests } = fixtureTransport('ucpUser')
    const profile = await fetchUserProfileByName(createNgaFetcher({ transport }), {
      username: '冷面比面筋好吃',
    })

    const url = requests[0]!.url
    expect(url).toContain('__lib=ucp')
    expect(url).toContain('__act=get')
    expect(url).toContain(`username=${encodeURIComponent('冷面比面筋好吃')}`)
    expect(url).not.toContain('uid=')
    expect(requests[0]!.headers.Referer).toContain('nuke.php?func=ucp')
    // 响应解析与按 uid 查同一条路
    expect(profile.uid).toBe(41417929)
    expect(profile.name).toBe('BugenZhao')
  })

  it('查无此人（假错误「找不到用户」）报成 server 错误', async () => {
    const { transport } = fixtureTransport('ucpMissing')
    await expect(
      fetchUserProfileByName(createNgaFetcher({ transport }), { username: '不存在的人' }),
    ).rejects.toThrow('找不到用户')
  })
})
