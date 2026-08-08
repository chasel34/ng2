import { describe, expect, it, vi } from 'vitest'

import { decodeGb18030 } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes } from './__fixtures__'
import {
  clearNotificationFeed,
  fetchNotificationFeed,
  notificationKind,
  parseNotificationFeed,
} from './notifications'

/**
 * 条目级样本按 API 文档 §9.1 + MNGA/Android 两份研报的口径构造——
 * 测试账号(.env.local)的真实 get_all 响应是空的(见 fixture notiGetAllEmpty),
 * 抓不到带数据的样本;字段口径三处文档一致,风险可控。
 */
const replyToTopic = {
  '0': 1,
  '1': 60123456,
  '2': '留白，嗯',
  '5': '体感消费不一直这样吗？',
  '6': 44191387,
  '7': 812345678,
  '8': 812340000,
  '9': 1786100000,
  '10': 3,
}

const mentionInReply = {
  '0': '8', // 服务端偶尔把数字写成字符串,一并收下
  '1': '60234567',
  '2': '海豚音一号',
  '5': '显卡又开始涨价了',
  '6': '46186286',
  '7': '812350001',
  '9': '1786090000',
}

const newMessage = {
  '0': 10,
  '2': '版务组',
  '5': '关于你举报的楼层',
  '9': 1786080000,
}

describe('parseNotificationFeed（文档口径向量）', () => {
  it('三个容器合并解出,按时间戳降序', () => {
    const feed = parseNotificationFeed({
      '0': {
        unread: 2,
        '0': { '0': mentionInReply, '1': replyToTopic },
        '1': [newMessage],
        '2': '',
      },
    })
    expect(feed.items.map((item) => item.id)).toEqual([
      '1786100000-1-44191387-812345678',
      '1786090000-8-46186286-812350001',
      '1786080000-10-0-0',
    ])
    expect(feed.serverUnread).toBe(2)
  })

  it('回复类条目字段齐全,稳定 ID 是 时间戳-类型-tid-pid', () => {
    const feed = parseNotificationFeed({ '0': { '0': [replyToTopic] } })
    expect(feed.items[0]).toEqual({
      id: '1786100000-1-44191387-812345678',
      type: 1,
      kind: 'reply',
      userId: 60123456,
      userName: '留白，嗯',
      subject: '体感消费不一直这样吗？',
      tid: 44191387,
      pid: 812345678,
      myPid: 812340000,
      timestamp: 1786100000,
      page: 3,
    })
  })

  it('字符串数字字段照收;没给页码当第 1 页', () => {
    const feed = parseNotificationFeed({ '0': { '0': [mentionInReply] } })
    expect(feed.items[0]).toMatchObject({
      type: 8,
      kind: 'mention',
      tid: 46186286,
      pid: 812350001,
      page: 1,
    })
  })

  it('短信类条目没有 tid/pid,用 0 占位仍能生成稳定 ID', () => {
    const feed = parseNotificationFeed({ '0': { '1': [newMessage] } })
    expect(feed.items[0]).toMatchObject({
      kind: 'message',
      tid: 0,
      pid: 0,
      userName: '版务组',
      id: '1786080000-10-0-0',
    })
  })

  it('缺类型码或时间戳的条目跳过,坏条目不带崩整份', () => {
    const feed = parseNotificationFeed({
      '0': { '0': [{ '2': '没类型' }, { '0': 1, '2': '没时间戳' }, replyToTopic, 42, null] },
    })
    expect(feed.items).toHaveLength(1)
  })

  it('类型码归类;认不出的进 other', () => {
    expect(notificationKind(2)).toBe('reply')
    expect(notificationKind(3)).toBe('comment')
    expect(notificationKind(4)).toBe('comment')
    expect(notificationKind(7)).toBe('mention')
    expect(notificationKind(11)).toBe('message')
    expect(notificationKind(17)).toBe('rating')
    expect(notificationKind(99)).toBe('other')
  })
})

describe('parseNotificationFeed（真实空账号样本）', () => {
  it('data["0"] 是空串时解成空列表', () => {
    const root = JSON.parse(decodeGb18030(readFixtureBytes('notiGetAllEmpty'))) as {
      data: unknown
    }
    const feed = parseNotificationFeed(root.data)
    expect(feed.items).toEqual([])
    expect(feed.serverUnread).toBeUndefined()
  })
})

/** 用固定字节应答的假传输层,顺带把请求录下来。 */
function fixtureTransport() {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: fixtureContentType('notiGetAllEmpty'),
      body: readFixtureBytes('notiGetAllEmpty'),
    }
  })
  return { transport, requests }
}

describe('fetchNotificationFeed / clearNotificationFeed', () => {
  it('get_all 打对端点,空账号解出空列表', async () => {
    const { transport, requests } = fixtureTransport()
    const feed = await fetchNotificationFeed(createNgaFetcher({ transport }))
    expect(feed.items).toEqual([])
    const url = requests[0]?.url ?? ''
    expect(url).toContain('/nuke.php?')
    expect(url).toContain('__lib=noti')
    expect(url).toContain('__act=get_all')
  })

  it('清空走 raw=3 的 del(API 文档 §9.2)', async () => {
    const { transport, requests } = fixtureTransport()
    await clearNotificationFeed(createNgaFetcher({ transport }))
    const url = requests[0]?.url ?? ''
    expect(url).toContain('__lib=noti')
    expect(url).toContain('raw=3')
    expect(url).toContain('__act=del')
  })
})
