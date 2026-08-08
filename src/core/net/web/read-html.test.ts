import { describe, expect, it } from 'vitest'

import { NET_FIXTURES, fixtureContentType, readFixtureBytes, type NetFixtureName } from '../__fixtures__'
import { decodeResponseBody } from '../encoding/decode-body'
import { NgaError } from '../errors'
import { isRecord } from '../is-record'
import { parseReadPageHtml } from './read-html'

/**
 * Web 反解的回归线（19 号票，ADR-0002）。
 *
 * 用的是**真实网页 HTML**（`__fixtures__/read-web-*`，与 `core/api/__fixtures__` 里
 * 同 tid 的 `__output=8` 样本同一批主题），断言按票面分四类：
 * 楼层元数据 / 用户表 / 分页 / msgcode 错误。
 *
 * 这里只验到「信封与 JSON 路线同构」为止——从信封到 `TopicDetail` 的那一段
 * 是 `core/api/topic-detail` 的用例在管，两边合起来才是完整的一条路。
 */

const html = (name: NetFixtureName) =>
  decodeResponseBody(readFixtureBytes(name), fixtureContentType(name))

const dataOf = (name: NetFixtureName): Record<string, unknown> => {
  const { data } = parseReadPageHtml(html(name))
  if (!isRecord(data)) throw new Error('反解结果没有 data')
  return data
}

const record = (value: unknown): Record<string, unknown> => {
  if (!isRecord(value)) throw new Error('不是对象')
  return value
}

describe('parseReadPageHtml · 楼层元数据', () => {
  const data = dataOf('readWebAnonymousHotReply')
  const rows = record(data.__R)

  it('楼层按页内序号排成 __R，键与 JSON 路线一致', () => {
    expect(Object.keys(rows)).toEqual(
      Array.from({ length: 19 }, (_, index) => String(index)),
    )
  })

  it('主楼:pid/楼号/匿名作者/时间/赞数/标题/发帖设备都反解得出来', () => {
    const main = record(rows['0'])
    expect(main.pid).toBe(0)
    expect(main.lou).toBe(0)
    // 匿名楼层的 authorid 是页内序号而不是 uid（API 文档 §3）
    expect(main.authorid).toBe('-1')
    expect(main.postdatetimestamp).toBe(1770802621)
    expect(main.postdate).toBe('2026-02-11 17:37')
    expect(main.score).toBeGreaterThan(0)
    expect(main.subject).toBe('天塌了，结婚四年，才知道老婆有精神分裂病史，并且复发')
    expect(main.from_client).toBe('8 Android')
  })

  it('正文原样取网页里那段 innerHTML:`<br/>` 与 `&amp;` 都留着,与 JSON 的 content 同口径', () => {
    // 同一页 __output=8 响应里这一楼的 content 是
    // `[新婚夜…] 信源 [url]https://…&amp;wfr=spider&amp;for=pc[/url]`——两边逐字相同
    const floor = record(rows['4'])
    expect(floor.content).toContain('[url]https://baijiahao.baidu.com/')
    expect(floor.content).toContain('&amp;wfr=spider')
    expect(record(rows['1']).content).toContain('<br/>')
  })

  it('热门回复挂在主楼上,楼号按 pid 从本页认回来', () => {
    const hot = record(record(rows['0']).hotreply)
    expect(Object.keys(hot)).toHaveLength(4)
    const first = record(hot['0'])
    expect(first.pid).toBe(857843067)
    // 这条热门回复就是本页第 5 楼；网页版没直接给楼号，是按 pid 对回来的
    expect(first.lou).toBe(5)
  })

  it('贴条挂在被贴的那一楼下面(不像 JSON 那样另占一条幽灵行)', () => {
    const comments = record(dataOf('readWebComment').__R)
    const withNotes = Object.values(comments)
      .map(record)
      .filter((row) => row.comment !== undefined)
    expect(withNotes.map((row) => row.lou)).toEqual([4])
    const note = record(record(withNotes[0]!.comment)['0'])
    expect(note.pid).toBe(824921555)
    expect(note.content).toContain('是恩基爱社区')
  })

  it('附件与编辑记录:`ubbcode.attach.load` 与 `loadAlertInfo` 补回 attachs / alterinfo', () => {
    const main = record(record(dataOf('readWebAttachments').__R)['0'])
    const attachs = record(main.attachs)
    expect(Object.keys(attachs)).toHaveLength(2)
    expect(record(attachs['0'])).toMatchObject({
      attachurl: 'mon_202608/07/c4Q58-hy2fZcT1kShs-12d.jpg',
      type: 'img',
      thumb: '56',
      size: '118',
    })
    expect(main.alterinfo).toContain('[E1786103835')
  })

  it('附件域名补上网页版省掉的那段路径(网页里只有域名,JSON 里带 /attachments)', () => {
    expect(record(dataOf('readWebAttachments').__GLOBAL)._ATTACH_BASE_VIEW).toBe(
      'img.nga.cn/attachments',
    )
  })
})

describe('parseReadPageHtml · 用户表', () => {
  const data = dataOf('readWebAnonymousHotReply')
  const users = record(data.__U)

  it('实名用户带 uid/用户名/头像/发帖数,与 JSON 的 __U 同构', () => {
    expect(record(users['66313282'])).toMatchObject({
      uid: 66313282,
      username: '两袖清风徐阁老',
      postnum: 4370,
      memberid: 42,
    })
  })

  it('匿名槽位与 __GROUPS 等附表照收(下游按同一批键名取)', () => {
    expect(record(users['-1']).username).toMatch(/^#anony_/)
    expect(record(record(users.__GROUPS)['42'])['0']).toBe('警告等级1')
    expect(users.__MEDALS).toBeDefined()
  })

  it('主题元数据:tid/标题/版块名/楼主', () => {
    const topic = record(data.__T)
    expect(topic.tid).toBe(46186286)
    expect(topic.subject).toBe('天塌了，结婚四年，才知道老婆有精神分裂病史，并且复发')
    // 匿名楼主:setDefault 给的是页内序号 -3,认人只能靠主楼作者那一串 #anony_
    expect(topic.authorid).toBe(-3)
    expect(topic.author).toMatch(/^#anony_/)
    expect(record(data.__F).name).toBe('晴风村')
  })

  it('实名楼主从用户表里取回名字', () => {
    const topic = record(dataOf('readWebAttachments').__T)
    expect(topic.authorid).toBe(37374391)
    expect(topic.author).toBe('平雪飞')
  })
})

describe('parseReadPageHtml · 分页', () => {
  it('当前页 / 每页楼数 / 总楼数都对得上 JSON 路线', () => {
    const data = dataOf('readWebAnonymousHotReply')
    expect(data.__PAGE).toBe(1)
    expect(data.__R__ROWS_PAGE).toBe(20)
    // setDefault 给的 replies=283 → __ROWS=284，正是同一时刻 __output=8 响应里的值
    expect(data.__ROWS).toBe(284)
    expect(Math.ceil(284 / 20)).toBe(15)
  })

  it('另一份样本的总楼数同样等于 replies + 1', () => {
    expect(dataOf('readWebAttachments').__ROWS).toBe(107)
  })
})

describe('parseReadPageHtml · 错误', () => {
  it('msgcode 注释标记 → 服务端语义错误,文案与 JSON 的 error.0 一模一样', () => {
    expect(() => parseReadPageHtml(html('readWebNotFound'), 'web-fallback')).toThrowError(
      expect.objectContaining({
        kind: 'server',
        code: '2048',
        message: '2048:找不到主题',
        via: 'web-fallback',
        // 语义错误换几次策略还是这个结果,链要当场收手
        retryable: false,
      }) as unknown as Error,
    )
  })

  it('一楼都没反解出来算被封(可重试),链继续往下走', () => {
    let thrown: unknown
    try {
      parseReadPageHtml('<html><body>nothing here</body></html>', 'web-fallback')
    } catch (error) {
      thrown = error
    }
    expect(thrown).toBeInstanceOf(NgaError)
    expect(thrown).toMatchObject({ kind: 'parse', retryable: true })
  })
})

describe('__fixtures__', () => {
  it('网页样本里没有留下抓包账号的身份', () => {
    for (const name of ['readWebAnonymousHotReply', 'readWebComment', 'readWebAttachments'] as const) {
      const text = html(name)
      expect(NET_FIXTURES[name].file).toMatch(/^read-web-/)
      expect(text).toContain("__CURRENT_UID = parseInt('10000001'")
      expect(text).not.toMatch(/ngaPassportCid/i)
    }
  })
})
