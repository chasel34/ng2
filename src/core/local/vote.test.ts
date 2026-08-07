import { describe, expect, it } from 'vitest'

import { isVoteClosed, parseVote, voteSharePercent } from './vote'

/**
 * `REAL_VOTE` 抓自站上真帖，题目「美国高通和华为是竞争对手,这个竞争你希望哪边赢？」
 * （https://bbs.nga.cn/read.php?tid=47331456，2026-08-08 的快照）。
 * 其余用例按 NGA 官方 `js_read.js` 的 `commonui.vote` / `voteFormat` / `voteFormatGroup`
 * 里各字段的用法拼出来。
 */
const REAL_VOTE =
  '208133~华为~208134~美国高通~max_select~1~end~1793891155~_208133~123,0,138~_208134~15,0,0'

const TID = 47331456

describe('parseVote', () => {
  it('单选投票：拆出选项、票数与参与人数', () => {
    expect(parseVote(REAL_VOTE, { tid: TID })).toEqual({
      kind: 'vote',
      multiple: false,
      maxSelect: 1,
      voters: 138,
      totalVotes: 138,
      endAt: 1793891155,
      resultAfterVote: false,
      resultAfterEnd: false,
      groups: [
        {
          votes: 138,
          options: [
            { id: '208133', title: '华为', votes: 123, points: 0, chosen: false },
            { id: '208134', title: '美国高通', votes: 15, points: 0, chosen: false },
          ],
        },
      ],
    })
  })

  it('max_select 大于 1 是多选', () => {
    const vote = parseVote('1~甲~2~乙~3~丙~max_select~2~_1~5,0,9~_2~3,0,0~_3~1,0,0', { tid: TID })
    expect(vote?.multiple).toBe(true)
    expect(vote?.maxSelect).toBe(2)
  })

  it('缺 max_select 时按单选算', () => {
    expect(parseVote('1~甲~2~乙~_1~1,0,1~_2~0,0,0', { tid: TID })?.maxSelect).toBe(1)
  })

  it('done 标出当前账号投过的选项', () => {
    const vote = parseVote('1~甲~2~乙~3~丙~max_select~2~done~1,3~_1~5,0,9~_2~3,0,0~_3~1,0,0', {
      tid: TID,
    })
    expect(vote?.groups[0]?.options.map((option) => option.chosen)).toEqual([true, false, true])
  })

  it('opt 位掩码转成两个「看结果的条件」', () => {
    const options = { tid: TID }
    const base = '1~甲~_1~1,0,1'
    expect(parseVote(`${base}~opt~1`, options)?.resultAfterVote).toBe(true)
    expect(parseVote(`${base}~opt~1`, options)?.resultAfterEnd).toBe(false)
    expect(parseVote(`${base}~opt~3`, options)?.resultAfterEnd).toBe(true)
  })

  it('priv 的 r数字_ 前缀换成人看得懂的门槛说明（门槛数值在下划线之后）', () => {
    expect(parseVote('1~甲~_1~1,0,1~priv~r-10_20', { tid: TID })?.requirement).toBe(
      '需要达到20版块声望以上',
    )
  })

  it('=== 开头的选项是分组分隔行，后面的选项自成一组', () => {
    const vote = parseVote(
      '1~甲~2~乙~3~===第二组===~4~丙~_1~5,0,9~_2~3,0,0~_3~0,0,0~_4~2,0,0',
      { tid: TID },
    )
    expect(vote?.groups).toEqual([
      {
        votes: 8,
        options: [
          { id: '1', title: '甲', votes: 5, points: 0, chosen: false },
          { id: '2', title: '乙', votes: 3, points: 0, chosen: false },
        ],
      },
      {
        title: '===第二组===',
        votes: 2,
        options: [{ id: '4', title: '丙', votes: 2, points: 0, chosen: false }],
      },
    ])
  })

  it('分组语法只对新帖生效，老帖的 === 是普通选项', () => {
    const vote = parseVote('1~甲~2~===第二组===~_1~5,0,9~_2~3,0,0', { tid: 38056407 })
    expect(vote?.groups).toHaveLength(1)
    expect(vote?.groups[0]?.options.map((option) => option.title)).toEqual(['甲', '===第二组==='])
  })

  it('type 认出投注、评分与问答', () => {
    const base = '1~甲~_1~4,20,9'
    expect(parseVote(`${base}~type~1`, { tid: TID })?.kind).toBe('bet')
    expect(parseVote(`${base}~type~4`, { tid: TID })?.kind).toBe('qa')
  })

  it('评分类带上分数区间，第二个数是总分', () => {
    const vote = parseVote('1~画面~_1~4,18,4~type~2~min~1~max~5', { tid: TID })
    expect(vote?.scoreRange).toEqual({ min: 1, max: 5 })
    expect(vote?.groups[0]?.options[0]?.points).toBe(18)
  })

  it('空串、切不出一对、只有配置项没有选项时都没有投票', () => {
    expect(parseVote('', { tid: TID })).toBeUndefined()
    expect(parseVote('0', { tid: TID })).toBeUndefined()
    expect(parseVote('max_select~1~end~0', { tid: TID })).toBeUndefined()
  })

  it('末尾落单的一段直接丢掉，前面解出来的选项照常用', () => {
    expect(parseVote('1~甲~_1~3,0,3~max_select', { tid: TID })?.groups[0]?.options).toEqual([
      { id: '1', title: '甲', votes: 3, points: 0, chosen: false },
    ])
  })

  it('计数缺失的选项按 0 票算，不让整个投票塌掉', () => {
    const vote = parseVote('1~甲~2~乙~_1~7,0,7', { tid: TID })
    expect(vote?.groups[0]?.options.map((option) => option.votes)).toEqual([7, 0])
    expect(vote?.totalVotes).toBe(7)
  })
})

describe('isVoteClosed', () => {
  const vote = parseVote(REAL_VOTE, { tid: TID })!

  it('过了截止时间才算已结算', () => {
    expect(isVoteClosed(vote, 1793891155)).toBe(false)
    expect(isVoteClosed(vote, 1793891156)).toBe(true)
  })

  it('没有截止时间的投票永远开着', () => {
    const endless = parseVote('1~甲~_1~1,0,1', { tid: TID })!
    expect(isVoteClosed(endless, Number.MAX_SAFE_INTEGER)).toBe(false)
  })
})

describe('voteSharePercent', () => {
  it('与网页版一样只保留一位小数，且向下截断', () => {
    expect(voteSharePercent(123, 138)).toBe(89.1)
    expect(voteSharePercent(1, 3)).toBe(33.3)
    expect(voteSharePercent(0, 0)).toBe(0)
  })
})
