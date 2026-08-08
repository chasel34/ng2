import { describe, expect, it, vi } from 'vitest'

import { decodeResponseBody, parseNgaJson } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import { fetchTopicDetail, parseTopicDetail } from './topic-detail'
import type { Floor, TopicDetail } from './types'

/**
 * 从抓包样本还原出 `data`，与设备上走的是同一条解码 + 清洗路径。
 * `context` 显式传，匿名 id 的前缀才是可断言的（生产里每次请求换一个）。
 */
function parseFixture(name: ApiFixtureName, context = 'ctx'): TopicDetail {
  const envelope = parseNgaJson(
    decodeResponseBody(readFixtureBytes(name), fixtureContentType(name)),
  )
  return parseTopicDetail(envelope.data, { context })
}

const floorAt = (detail: TopicDetail, lou: number): Floor => {
  const floor = detail.floors.find((item) => item.lou === lou)
  if (floor === undefined) throw new Error(`样本里没有第 ${lou} 楼`)
  return floor
}

describe('分页', () => {
  it('总页数按 __ROWS / 每页 20 楼向上取整', () => {
    const detail = parseFixture('readAnonymousHotReply')
    expect(detail.totalRows).toBe(242)
    expect(detail.rowsPerPage).toBe(20)
    expect(detail.totalPages).toBe(13)
    expect(detail.page).toBe(1)
  })

  it('不足一页也算一页', () => {
    expect(parseTopicDetail({ __ROWS: 3 }, { context: 'ctx' }).totalPages).toBe(1)
    expect(parseTopicDetail({ __ROWS: 0 }, { context: 'ctx' }).totalPages).toBe(1)
  })

  it('整除时不多出一页', () => {
    // 107 楼 → 6 页；20 楼 → 1 页（第 21 楼才翻页）
    expect(parseFixture('readAttachments').totalPages).toBe(6)
    expect(parseTopicDetail({ __ROWS: 20 }, { context: 'ctx' }).totalPages).toBe(1)
    expect(parseTopicDetail({ __ROWS: 21 }, { context: 'ctx' }).totalPages).toBe(2)
  })

  it('服务端自报的页大小优先于默认的 20', () => {
    const detail = parseTopicDetail(
      { __ROWS: 100, __R__ROWS_PAGE: 25 },
      { context: 'ctx' },
    )
    expect(detail.rowsPerPage).toBe(25)
    expect(detail.totalPages).toBe(4)
  })
})

describe('附件图片域名', () => {
  it('从响应的 __GLOBAL._ATTACH_BASE_VIEW 取，不硬编码', () => {
    expect(parseFixture('readAttachments').attachBase).toBe('https://img.nga.cn/attachments')
  })

  it('服务端换域名时跟着换', () => {
    const detail = parseTopicDetail(
      { __GLOBAL: { _ATTACH_BASE_VIEW: 'img9.example.test/att' } },
      { context: 'ctx' },
    )
    expect(detail.attachBase).toBe('https://img9.example.test/att')
  })

  it('附件的原图与缩略图地址都用这个基址拼出来', () => {
    const detail = parseFixture('readAttachments')
    const [first] = floorAt(detail, 0).attachments
    expect(first).toEqual({
      url: 'https://img.nga.cn/attachments/mon_202608/07/c4Q58-hy2fZcT1kShs-12d.jpg',
      thumbnailUrl:
        'https://img.nga.cn/attachments/mon_202608/07/c4Q58-hy2fZcT1kShs-12d.jpg.thumb.jpg',
      kind: 'img',
      name: 'c4Q58-hy2fZcT1kShs-12d.jpg',
      sizeKb: 118,
    })
    expect(floorAt(detail, 0).attachments).toHaveLength(2)
  })

  it('attachs 是空串（常态）时当没有附件', () => {
    const detail = parseFixture('readComment')
    expect(floorAt(detail, 1).attachments).toEqual([])
  })

  it('thumb 为 0 / "0" / 缺省时不拼缩略图地址（拼了就是 404）', () => {
    const withThumb = (thumb: unknown) =>
      parseTopicDetail(
        {
          __R: {
            '0': {
              lou: 0,
              content: '',
              attachs: { '0': { attachurl: 'mon_202608/07/a.jpg', type: 'img', thumb } },
            },
          },
        },
        { context: 'ctx' },
      ).floors[0]?.attachments[0]?.thumbnailUrl

    expect(withThumb(0)).toBeUndefined()
    expect(withThumb('0')).toBeUndefined()
    expect(withThumb('')).toBeUndefined()
    expect(withThumb(undefined)).toBeUndefined()
    expect(withThumb(56)).toContain('.thumb.jpg')
  })
})

describe('匿名楼层', () => {
  it('匿名作者的 id 带请求级前缀，不同请求之间不串号', () => {
    const first = parseFixture('readAnonymousHotReply', 'req-1')
    const second = parseFixture('readAnonymousHotReply', 'req-2')

    expect(floorAt(first, 0).authorKey).toBe('req-1,-1')
    expect(floorAt(second, 0).authorKey).toBe('req-2,-1')
    // 两次请求的匿名 key 集合完全不相交——否则两页的 -1 会被当成同一个人
    const keysOf = (detail: TopicDetail) =>
      Object.values(detail.users)
        .filter((user) => user.anonymous)
        .map((user) => user.key)
    expect(keysOf(first).length).toBeGreaterThan(0)
    expect(keysOf(first).some((key) => keysOf(second).includes(key))).toBe(false)
  })

  it('实名作者的 key 就是 uid，不加前缀', () => {
    const detail = parseFixture('readAnonymousHotReply', 'req-1')
    expect(floorAt(detail, 1).authorKey).toBe('66313282')
  })

  it('authorId 留的是服务端原值——骰子种子要它，匿名楼层就是页内序号', () => {
    const detail = parseFixture('readAnonymousHotReply', 'req-1')
    expect(floorAt(detail, 0).authorId).toBe(-1)
    expect(floorAt(detail, 1).authorId).toBe(66313282)
  })

  it('每个楼层的 authorKey 都能在用户表里查到', () => {
    const detail = parseFixture('readAnonymousHotReply')
    for (const floor of [...detail.floors, ...detail.hotReplies]) {
      expect(detail.users[floor.authorKey]).toBeDefined()
    }
  })

  it('匿名作者名本地还原成六字假名', () => {
    const detail = parseFixture('readAnonymousHotReply')
    const author = detail.users[floorAt(detail, 0).authorKey]
    expect(author?.anonymous).toBe(true)
    expect(author?.name).toBe('寅于佘甲连邹')
    expect(author?.uid).toBeUndefined()
  })
})

describe('用户对象', () => {
  it('威望是服务端 rvrc 除以 10', () => {
    const detail = parseFixture('readAnonymousHotReply')
    // 两袖清风徐阁老：rvrc -30、postnum 4370、memberid 42 → __GROUPS 里是「警告等级1」
    const user = detail.users['66313282']
    expect(user?.reputation).toBe(-3)
    expect(user?.postCount).toBe(4370)
    expect(user?.level).toBe('警告等级1')
  })

  it('__U 里混着的 __GROUPS / __MEDALS / __REPUTATIONS 三张附表不是用户', () => {
    const detail = parseFixture('readAnonymousHotReply')
    expect(Object.keys(detail.users).some((key) => key.startsWith('__'))).toBe(false)
    expect(detail.users['__GROUPS']).toBeUndefined()
  })

  it('头像取 avatar 字段；它是 JSON 串时抠出第一个 http URL', () => {
    const detail = parseFixture('readAnonymousHotReply')
    expect(detail.users['66313282']?.avatarUrl).toBe(
      'https://img.nga.cn/avatars/2002/c42/f3d/003/66313282_0.jpg?27',
    )
    // 空串等于没头像，UI 走首字占位
    expect(detail.users['63485408']?.avatarUrl).toBeUndefined()

    const jsonAvatar = parseTopicDetail(
      {
        __U: {
          '7': { uid: 7, username: 'x', avatar: '{"0":"https:\\/\\/img.nga.cn\\/a\\/7.jpg","1":""}' },
        },
      },
      { context: 'ctx' },
    )
    expect(jsonAvatar.users['7']?.avatarUrl).toBe('https://img.nga.cn/a/7.jpg')
  })

  it('buffs 含 105 / 117 = 禁言中，其它 buff 不算', () => {
    const withBuffs = (buffs: unknown) =>
      parseTopicDetail({ __U: { '7': { uid: 7, username: 'x', buffs } } }, { context: 'ctx' })
        .users['7']?.muted

    expect(withBuffs({ '105': { '2': 105 } })).toBe(true)
    expect(withBuffs({ '117': { '2': 117 } })).toBe(true)
    expect(withBuffs({ '103': { '2': 103 } })).toBe(false)
    expect(withBuffs('')).toBe(false)
    // 样本里的匿名用户挂的是 103（匿名 buff），不是禁言
    expect(parseFixture('readAnonymousHotReply').users['ctx,-1']?.muted).toBe(false)
  })

  it('签名取 signature/sign 字段（BBCode 原文），空串 = 没设置', () => {
    const detail = parseFixture('readAttachments')
    expect(detail.users['41482387']?.signature).toContain('本人所有发言')
    // 签名字段是空串的用户不带 signature 键
    expect(detail.users['65690642']).toBeDefined()
    expect(detail.users['65690642']?.signature).toBeUndefined()

    const signOnly = parseTopicDetail(
      { __U: { '7': { uid: 7, username: 'x', sign: '[b]旧字段[/b]' } } },
      { context: 'ctx' },
    )
    expect(signOnly.users['7']?.signature).toBe('[b]旧字段[/b]')
  })

  it('yz 为 -1 = 被 nuke；其它负值不算', () => {
    const nuked = (yz: number) =>
      parseTopicDetail({ __U: { '7': { uid: 7, username: 'x', yz } } }, { context: 'ctx' })
        .users['7']?.nuked
    expect(nuked(-1)).toBe(true)
    expect(nuked(4)).toBe(false)
    // 样本里有个 yz = -5 的用户，那不是 nuke
    expect(parseFixture('readAnonymousHotReply').users['67089404']?.nuked).toBe(false)
  })
})

describe('楼层', () => {
  it('主楼是第 0 楼，带主题标题与正文原文', () => {
    const detail = parseFixture('readAttachments')
    const first = floorAt(detail, 0)
    expect(first.lou).toBe(0)
    expect(detail.subject).toBe(detail.subject.trim())
    expect(detail.subject.length).toBeGreaterThan(0)
    expect(first.content).toContain('[img]')
    expect(detail.boardName).toBe('消费电子 IT新闻')
  })

  it('楼层按楼号升序，不重复', () => {
    const detail = parseFixture('readComment')
    const lous = detail.floors.map((floor) => floor.lou)
    expect(lous).toEqual([...lous].sort((a, b) => a - b))
    expect(new Set(lous).size).toBe(lous.length)
  })

  it('贴条挂在被贴的楼层下，且不在楼层流里单独占一行', () => {
    const detail = parseFixture('readComment')
    // 第 4 楼被贴了一条；服务端同时在 __R 里塞了一条只有 subject 的幽灵行（pid 相同）
    expect(floorAt(detail, 4).notes).toHaveLength(1)
    expect(floorAt(detail, 4).notes[0]?.pid).toBe(824921555)
    expect(detail.floors.some((floor) => floor.pid === 824921555)).toBe(false)
    // __R 有 19 条，滤掉幽灵行剩 18 个楼层（服务端的 __R__ROWS 把幽灵行也数进去了，
    // 所以那个字段不能当楼层数用）
    expect(detail.floors).toHaveLength(18)
  })

  it('贴条作者也进用户表', () => {
    const detail = parseFixture('readComment')
    const note = floorAt(detail, 4).notes[0]
    expect(note).toBeDefined()
    expect(detail.users[note!.authorKey]?.name).toBe('gerraerd')
  })

  it('alterinfo 非空 = 被编辑过', () => {
    const detail = parseFixture('readComment')
    expect(floorAt(detail, 0).edited).toBe(true)
    expect(floorAt(detail, 1).edited).toBe(false)
  })

  it('from_client 认出发帖设备，认不出的归 other', () => {
    expect(floorAt(parseFixture('readComment'), 1).client).toBe('android')
    expect(floorAt(parseFixture('readComment'), 0).client).toBe('ios')
    // "31 /" —— 有编号没名字，认不出来
    expect(floorAt(parseFixture('readAttachments'), 0).client).toBe('other')
  })

  it('主楼作者标成楼主，匿名主楼按匿名串认人', () => {
    const detail = parseFixture('readAnonymousHotReply')
    expect(floorAt(detail, 0).isStarter).toBe(true)
    expect(floorAt(detail, 1).isStarter).toBe(false)

    // 匿名主楼那一页里，楼主又发了一楼（用户表的 -1/-2 是同一串 #anony_）
    expect(detail.floors.filter((floor) => floor.isStarter)).toHaveLength(2)

    // 楼主实名时按 uid 认：44191387 的楼主是 gerraerd(205511)
    const named = parseFixture('readComment')
    expect(floorAt(named, 0).isStarter).toBe(true)
    expect(named.users['205511']?.name).toBe('gerraerd')
    // 楼主贴的那条贴条也算楼主
    expect(floorAt(named, 4).notes[0]?.isStarter).toBe(true)
  })

  it('赞数与发帖时间原样带出', () => {
    const floor = floorAt(parseFixture('readAnonymousHotReply'), 0)
    expect(floor.score).toBe(15)
    expect(floor.postedAt).toBe(1770802621)
    expect(floor.postedAtText).toBe('2026-02-11 17:37')
  })
})

describe('热门回复', () => {
  it('从主楼的 hotreply 解出来，独立成一区', () => {
    const detail = parseFixture('readAnonymousHotReply')
    expect(detail.hotReplies.map((floor) => floor.pid)).toEqual([
      857843067, 857843681, 857843919, 857842785,
    ])
    // 热门回复本身也是楼层流里的楼层，两边不互相排除
    expect(detail.hotReplies[3]?.lou).toBe(1)
    expect(detail.floors.some((floor) => floor.pid === 857842785)).toBe(true)
  })

  it('没有热门回复时是空数组', () => {
    expect(parseFixture('readComment').hotReplies).toEqual([])
  })
})

describe('容错', () => {
  it('data 不是对象也不抛', () => {
    for (const bad of [undefined, null, 'x', 42, []]) {
      const detail = parseTopicDetail(bad, { context: 'ctx' })
      expect(detail.floors).toEqual([])
      expect(detail.totalPages).toBe(1)
    }
  })

  it('单个坏楼层不带崩整页', () => {
    const detail = parseTopicDetail(
      { __R: { '0': null, '1': { pid: 1, lou: 1, content: '在', authorid: 7 } } },
      { context: 'ctx' },
    )
    expect(detail.floors).toHaveLength(1)
    expect(detail.floors[0]?.pid).toBe(1)
  })

  it('解析结果可 JSON 往返（要进帖子缓存）', () => {
    const detail = parseFixture('readComment')
    expect(JSON.parse(JSON.stringify(detail))).toEqual(detail)
  })
})

describe('fetchTopicDetail 的请求参数', () => {
  async function requestOf(options: Parameters<typeof fetchTopicDetail>[1]) {
    const requests: HttpRequest[] = []
    const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
      requests.push(request)
      return {
        status: 200,
        contentType: fixtureContentType('readComment'),
        body: readFixtureBytes('readComment'),
      }
    })
    await fetchTopicDetail(createNgaFetcher({ transport }), options)
    const first = requests[0]
    if (!first) throw new Error('一条请求都没发出去')
    return first
  }

  it('只看某人（12 票）：带 authorid，翻页时照带——过滤跨页保持', async () => {
    const request = await requestOf({ tid: 44191387, page: 3, authorId: 205511 })
    expect(request.url).toContain('authorid=205511')
    expect(request.url).toContain('page=3')
  })

  it('不过滤时不带 authorid', async () => {
    const request = await requestOf({ tid: 44191387, page: 1 })
    expect(request.url).not.toContain('authorid=')
  })
})
