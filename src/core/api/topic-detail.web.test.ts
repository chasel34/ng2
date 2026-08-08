import { describe, expect, it } from 'vitest'

import { decodeResponseBody, parseNgaJson, parseReadPageHtml } from '../net'
import {
  fixtureContentType as netFixtureContentType,
  readFixtureBytes as readNetFixtureBytes,
  type NetFixtureName,
} from '../net/__fixtures__'
import { createNgaFetcher } from '../net/fetcher'
import { createWebFallbackStrategy } from '../net/strategies/web-fallback'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import { fetchTopicDetail, parseTopicDetail } from './topic-detail'
import type { Floor, TopicDetail } from './types'

/**
 * Web 反解与原生接口的对拍（19 号票的验收项「反解数据走同一渲染管线，
 * 楼层显示与正常接口一致」）。
 *
 * 两份样本是**同一个主题的同一页**：`read-web-anonymous-hotreply` 是网页 HTML，
 * `read-anonymous-hotreply` 是同一页的 `__output=8`。抓的时刻差了一天，
 * 所以赞数、用户的发帖数这类会涨的字段不比，比的是身份与内容。
 */

const SAMPLE_TID = 46186286

/** 网页 HTML 走 Web 反解那条路。 */
function fromWeb(name: NetFixtureName): TopicDetail {
  const envelope = parseReadPageHtml(
    decodeResponseBody(readNetFixtureBytes(name), netFixtureContentType(name)),
  )
  return parseTopicDetail(envelope.data, { context: 'ctx', source: 'web' })
}

/** 同一页的 JSON 走原生那条路。 */
function fromNative(name: ApiFixtureName): TopicDetail {
  const envelope = parseNgaJson(
    decodeResponseBody(readFixtureBytes(name), fixtureContentType(name)),
  )
  return parseTopicDetail(envelope.data, { context: 'ctx' })
}

/** 比得起的那些字段：身份、内容、时间、附件——都是不随时间变的。 */
const identityOf = (floor: Floor) => ({
  pid: floor.pid,
  lou: floor.lou,
  authorKey: floor.authorKey,
  isStarter: floor.isStarter,
  content: floor.content,
  subject: floor.subject,
  postedAt: floor.postedAt,
  postedAtText: floor.postedAtText,
  client: floor.client,
  attachments: floor.attachments,
})

describe('Web 反解 × 原生接口 对拍', () => {
  const web = fromWeb('readWebAnonymousHotReply')
  const native = fromNative('readAnonymousHotReply')

  it('主题元数据一致', () => {
    expect(web.tid).toBe(SAMPLE_TID)
    expect(web.subject).toBe(native.subject)
    expect(web.boardName).toBe(native.boardName)
    expect(web.page).toBe(native.page)
    expect(web.rowsPerPage).toBe(native.rowsPerPage)
    expect(web.attachBase).toBe(native.attachBase)
  })

  it('楼层流逐条同构：楼号、作者、正文、时间、附件都对得上', () => {
    expect(web.floors.map(identityOf)).toEqual(native.floors.map(identityOf))
  })

  it('热门回复同样对得上（含网页版没直接给、按 pid 认回来的楼号）', () => {
    // 唯一比不了的是发帖设备：网页版给贴条与热门回复的那一位实参恒为 null，
    // 楼层本身照给（上一条用例连 client 一起比过了）
    const withoutClient = ({ client, ...rest }: ReturnType<typeof identityOf>) => rest
    expect(web.hotReplies.map(identityOf).map(withoutClient)).toEqual(
      native.hotReplies.map(identityOf).map(withoutClient),
    )
  })

  it('用户表：同一批 key，名字、头像、匿名标记一致', () => {
    expect(Object.keys(web.users).sort()).toEqual(Object.keys(native.users).sort())
    for (const [key, user] of Object.entries(web.users)) {
      const same = native.users[key]!
      expect({ name: user.name, anonymous: user.anonymous, avatarUrl: user.avatarUrl }).toEqual({
        name: same.name,
        anonymous: same.anonymous,
        avatarUrl: same.avatarUrl,
      })
    }
  })

  it('贴条与附件这两条路也走通了下游（另外两份样本）', () => {
    const comment = fromWeb('readWebComment')
    expect(comment.floors.find((floor) => floor.notes.length > 0)?.lou).toBe(4)

    const attachments = fromWeb('readWebAttachments')
    const main = attachments.floors[0]!
    expect(main.attachments.map((item) => item.kind)).toEqual(['img', 'img'])
    expect(main.edited).toBe(true)
  })
})

describe('fetchTopicDetail · 数据来源标记', () => {
  const webPage = () => ({
    status: 200,
    contentType: netFixtureContentType('readWebAnonymousHotReply'),
    body: readNetFixtureBytes('readWebAnonymousHotReply'),
  })

  it('Web 反解档出的结果打上 source=web，详情页据此出提示条', async () => {
    const fetchNga = createNgaFetcher({
      transport: () => Promise.resolve(webPage()),
      strategies: [createWebFallbackStrategy({ placement: 'primary', getMode: () => 'only' })],
    })

    const detail = await fetchTopicDetail(fetchNga, { tid: SAMPLE_TID, page: 1 })

    expect(detail.source).toBe('web')
    expect(detail.floors).toHaveLength(19)
  })

  it('原生那条路仍然是 source=native', async () => {
    const fetchNga = createNgaFetcher({
      transport: () =>
        Promise.resolve({
          status: 200,
          contentType: fixtureContentType('readAnonymousHotReply'),
          body: readFixtureBytes('readAnonymousHotReply'),
        }),
    })

    const detail = await fetchTopicDetail(fetchNga, { tid: SAMPLE_TID, page: 1 })

    expect(detail.source).toBe('native')
  })
})
