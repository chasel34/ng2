import { describe, expect, it } from 'vitest'

import { decodeGb18030 } from '../net'
import { readFixtureBytes } from './__fixtures__'
import { mergeTopicPages, parseTopicList } from './topic-list'
import type { Topic, TopicList } from './types'

/** 真实抓包样本解出来的整页，多个用例共用。 */
const fixtureList = parseTopicList(
  JSON.parse(decodeGb18030(readFixtureBytes('threadListLounge'))).data,
)

const topicById = (tid: number): Topic => {
  const topic = fixtureList.topics.find((item) => item.tid === tid)
  if (!topic) throw new Error(`样本里没有 tid=${tid}`)
  return topic
}

/** 造一条只填了关心字段的 `__T` 记录。 */
function parseOne(raw: Record<string, unknown>): Topic {
  const list = parseTopicList({ __T: { 0: { subject: '标题', tid: 1, ...raw } } })
  const topic = list.topics[0]
  if (!topic) throw new Error('这条主题没解出来')
  return topic
}

describe('parseTopicList（真实样本）', () => {
  it('解出整页主题与版块信息', () => {
    expect(fixtureList.topics).toHaveLength(51)
    expect(fixtureList.board?.name).toBe('网事杂谈')
    expect(fixtureList.board?.id).toBe(-7)
  })

  it('按 __ROWS 与每页条数算总页数', () => {
    expect(fixtureList.rowsPerPage).toBe(35)
    expect(fixtureList.totalRows).toBe(10008547)
    expect(fixtureList.totalPages).toBe(285959)
  })

  it('子版块横条：普通版块与合集分得开', () => {
    expect(fixtureList.subBoards).toHaveLength(31)
    expect(fixtureList.subBoards[0]).toEqual({
      id: 300,
      kind: 'board',
      fid: 300,
      name: '网络游戏综合',
    })
    // key 带 t 前缀的是合集，取 stid
    expect(fixtureList.subBoards.find((board) => board.name === '期货交易')).toEqual({
      id: 44618580,
      kind: 'collection',
      stid: 44618580,
      name: '期货交易',
    })
  })

  it('版头 tid 从 __F.topped_topic 解出来（CONTEXT.md「版头」）', () => {
    expect(fixtureList.board?.head).toBe(3593852)
  })

  it('topped_topic 是 0 或空串时没有版头', () => {
    const noHead = parseTopicList({ __F: { fid: 7, name: '艾泽拉斯议事厅', topped_topic: '' } })
    expect(noHead.board?.head).toBeUndefined()
    const zeroHead = parseTopicList({ __F: { fid: 7, name: '艾泽拉斯议事厅', topped_topic: 0 } })
    expect(zeroHead.board?.head).toBeUndefined()
  })

  it('置顶主题的彩色标题来自 topic_misc', () => {
    const topped = topicById(44191387)
    expect(topped.titleStyle).toEqual({ color: 'red', bold: true, italic: false, underline: false })
    expect(topped.replies).toBe(529)
    expect(topped.lastPoster).toBe('igorsn')
  })

  it('匿名主题的作者名当场还原', () => {
    const anonymous = topicById(46186286)
    expect(anonymous.author).toBe('寅于佘甲连邹')
    expect(anonymous.anonymous).toBe(true)
    // 匿名帖的 authorid 也是那串 #anony_，不是数字
    expect(anonymous.authorId).toBeUndefined()
    expect(anonymous.parent).toEqual({ fid: -7955747, name: '晴风村' })
  })

  it('合集行标出来并指向合集，不当普通主题打开', () => {
    const collection = topicById(47206901)
    expect(collection.isCollection).toBe(true)
    expect(collection.locked).toBe(true)
    expect(collection.shortcut).toEqual({ kind: 'collection', id: 47206901 })
  })
})

describe('parseTopicList（字段容错）', () => {
  it('真实 tid 看 quote_from，原 tid 只是引用来源', () => {
    expect(parseOne({ tid: 100, quote_from: 200 }).tid).toBe(200)
    expect(parseOne({ tid: 100, quote_from: 0 }).tid).toBe(100)
    expect(parseOne({ tid: 100, quote_from: '0' }).tid).toBe(100)
    expect(parseOne({ tid: 100, quote_from: '200' }).tid).toBe(200)
  })

  it('fav 码从 tpcurl 里抠出来', () => {
    expect(parseOne({ tpcurl: '/read.php?tid=11915941&fav=c7cf9a59' }).favCode).toBe('c7cf9a59')
    expect(parseOne({ tpcurl: '/read.php?tid=11915941' }).favCode).toBeUndefined()
    expect(parseOne({}).favCode).toBeUndefined()
  })

  it('tid 缺失时退回 tpcurl 里的那个', () => {
    expect(parseOne({ tid: undefined, tpcurl: '/read.php?tid=11915941&fav=c7cf9a59' }).tid).toBe(
      11915941,
    )
  })

  it('parent 是对象时照常解', () => {
    expect(parseOne({ parent: { 0: 275, 2: '父版面名' } }).parent).toEqual({
      fid: 275,
      name: '父版面名',
    })
  })

  it('parent 是字符串化 JSON 时也解得开（2024-04 服务端改过类型）', () => {
    expect(parseOne({ parent: '{"0":275,"1":32871539,"2":"父版面名"}' }).parent).toEqual({
      fid: 275,
      stid: 32871539,
      name: '父版面名',
    })
  })

  it('parent 是别的类型或缺名字时当没有', () => {
    expect(parseOne({ parent: '不是 JSON' }).parent).toBeUndefined()
    expect(parseOne({ parent: 275 }).parent).toBeUndefined()
    expect(parseOne({ parent: { 0: 275 } }).parent).toBeUndefined()
    expect(parseOne({}).parent).toBeUndefined()
  })

  it('type 位掩码拆成锁定 / 附件 / 合集 / 版块镜像', () => {
    expect(parseOne({ type: 1024 }).locked).toBe(true)
    expect(parseOne({ type: 8192 }).hasAttachment).toBe(true)
    expect(parseOne({ type: 33792 })).toMatchObject({ locked: true, isCollection: true })
    expect(parseOne({ type: 0 })).toMatchObject({
      locked: false,
      hasAttachment: false,
      isCollection: false,
      isBoardMirror: false,
    })
  })

  it('最后回复人也做匿名还原，和作者名一个待遇', () => {
    expect(parseOne({ lastposter: '#anony_00000000000000000000000000000000' }).lastPoster).toBe(
      '甲王王甲王王',
    )
  })

  it('外链主题（活动页）的 jumpurl 留着，点了要走浏览器', () => {
    // 真实抓包（fid=650 的签到活动帖）
    expect(
      parseOne({ jumpurl: 'https://bbs.nga.cn/misc/event/20260701genshin/index.html' }).jumpUrl,
    ).toBe('https://bbs.nga.cn/misc/event/20260701genshin/index.html')
    expect(parseOne({}).jumpUrl).toBeUndefined()
  })

  it('版块镜像行跳的是 topic_misc 里的子版块 fid', () => {
    const mirror = parseOne({ type: 2097152, topic_misc: 'AwAAA0MBAAAAIA', fid: 635 })
    expect(mirror.isBoardMirror).toBe(true)
    expect(mirror.shortcut).toEqual({ kind: 'board', id: 835 })
    // 掩码与 stid/fid 同在一个串里，样式照样解得出
    expect(mirror.titleStyle.bold).toBe(true)
  })

  it('topic_misc 解不出子版块时退回 topic_misc_var', () => {
    const mirror = parseOne({ type: 2097152, topic_misc: '', topic_misc_var: { 3: 835 }, fid: 635 })
    expect(mirror.shortcut).toEqual({ kind: 'board', id: 835 })
  })

  it('数字字段是字符串照样收，坏了就给缺省值', () => {
    expect(parseOne({ replies: '52', postdate: '1786112241' })).toMatchObject({
      replies: 52,
      postedAt: 1786112241,
    })
    expect(parseOne({ replies: null, postdate: {} })).toMatchObject({ replies: 0, postedAt: 0 })
  })

  it('没标题的主题给个占位，不整条丢掉', () => {
    expect(parseOne({ subject: '' }).subject).toBe('无标题')
  })

  it('标题按正文那套反转义——服务端连 subject 一起转义了（M2 遗留缺陷 1）', () => {
    // 2026-08-08 搜「第六感」的首条结果
    expect(
      parseOne({ subject: '&lt;第六感&gt;那个小孩能看到鬼魂，nga有人也能看到吗' }).subject,
    ).toBe('<第六感>那个小孩能看到鬼魂，nga有人也能看到吗')
    // 同日 fid=-7 精华区第 3 条
    expect(parseOne({ subject: '光荣正版《大航海时代：传说》1周年&#39;魔力印度&#39;新版本上线！' }).subject).toBe(
      "光荣正版《大航海时代：传说》1周年'魔力印度'新版本上线！",
    )
    // 双重转义的也吃得下（emoji 就是这么下发的），与 core/bbcode 同一条两轮解码
    expect(parseOne({ subject: '&amp;#55357;&amp;#56836; 笑' }).subject).toBe('😄 笑')
  })

  it('整份响应烂掉时给空页而不是抛', () => {
    expect(parseTopicList(undefined).topics).toEqual([])
    expect(parseTopicList({ __T: '不是对象' }).topics).toEqual([])
    expect(parseTopicList({}).totalPages).toBe(1)
  })
})

describe('mergeTopicPages', () => {
  const page = (...tids: number[]): TopicList =>
    parseTopicList({ __T: Object.fromEntries(tids.map((tid, index) => [index, { tid }])) })

  it('按页顺序接起来', () => {
    expect(mergeTopicPages([page(1, 2), page(3, 4)]).map((topic) => topic.tid)).toEqual([1, 2, 3, 4])
  })

  it('置顶主题与镜像行每页都回来，只留第一次出现的那条', () => {
    // 实测 fid=-7 的第 1、2 页有 20 条重叠
    expect(mergeTopicPages([page(1, 2, 3), page(1, 2, 4)]).map((topic) => topic.tid)).toEqual([
      1, 2, 3, 4,
    ])
  })

  it('没有页时给空数组', () => {
    expect(mergeTopicPages([])).toEqual([])
  })
})
