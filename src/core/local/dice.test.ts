import { describe, expect, it } from 'vitest'

import { parseBBCode } from '../bbcode'
import { formatDiceTerms, resolveDice, type DiceSeed } from './dice'

/**
 * 期望值有两个来源，都不是本实现自己算出来的：
 *
 * 1. **站上真帖对拍**（`真实骰子帖` 那一组）——从 NGA 上抓的原始楼层 BBCode，
 *    点数取自**楼主自己在后一楼写下的网页版结果**，见每条 fixture 的注释。
 * 2. 其余用例的期望值来自 NGA 官方 `js_bbscode_core.js` 里 `[dice]` 那段代码本身
 *    （`ubbcode.sRand.rnd` + 展开正则），照原样跑一遍取的输出。
 */

const SEED: DiceSeed = { authorId: 41417929, tid: 45150945, pid: 800000000 }

function outcomes(source: string, seed: DiceSeed = SEED) {
  return [...resolveDice(parseBBCode(source), seed).values()]
}

/** 一串骰子的点数，按文档顺序。 */
function values(source: string, seed: DiceSeed = SEED): number[] {
  return outcomes(source, seed).flatMap((outcome) =>
    outcome.terms.filter((term) => term.kind === 'roll').map((term) => term.value),
  )
}

describe('resolveDice 与站上真实骰子帖对拍', () => {
  it('一颗 d100：楼主在下一楼写「74的现实偏离度」', () => {
    // https://bbs.nga.cn/read.php?tid=46868034 第 8 楼，第 15 楼报出结果
    const seed = { authorId: 65352962, tid: 46868034, pid: 869683556 }
    expect(values('[dice]d100[/dice]', seed)).toEqual([74])
  })

  it('同一楼四颗 d6 共用一条数列：楼主报「空手道3 脑神经6 本领3 术2」', () => {
    // https://bbs.nga.cn/read.php?tid=46162468 第 5 楼，第 7 楼报出结果；
    // 第四项楼主写的是「术(最终结果-3)」，5-3=2，第 8 楼的「3632」是同一组数。
    const seed = { authorId: 60423359, tid: 46162468, pid: 857425480 }
    const source =
      '空手道<br/>[dice]d6[/dice]<br/>脑神经<br/>[dice]d6[/dice]<br/>' +
      '本领<br/>[dice]d6[/dice]<br/>术(最终结果-3)<br/>[dice]d6[/dice]'
    expect(values(source, seed)).toEqual([3, 6, 3, 5])
  })

  it('一颗 d13：下一楼的「竟然是火遁」对应第 1 项「火」', () => {
    // 同帖第 7 楼，第 11、12 楼报出结果
    const seed = { authorId: 60423359, tid: 46162468, pid: 857425573 }
    expect(values('[dice]d13[/dice]', seed)).toEqual([1])
  })

  it('带常数项的 1+1d7：楼主在下一楼写「3块大陆感觉有点少」', () => {
    // https://bbs.nga.cn/read.php?tid=46868034 第 15 楼，第 17 楼报出结果
    const seed = { authorId: 65352962, tid: 46868034, pid: 869684145 }
    const source = '有几个大陆？<br/>[dice]1+1d7[/dice]<br/>几个大洋？<br/>[dice]1+1d7[/dice]'
    expect(outcomes(source, seed).map((outcome) => outcome.sum)).toEqual([3, 3])
  })

  it('引用别人的骰子代码会得到另一组点数（种子含本楼 pid）', () => {
    // 官方帮助原话：「引用他人的投骰代码会得到不同结果」
    const source = '[dice]d6[/dice]'
    const original = values(source, { authorId: 60423359, tid: 46162468, pid: 857425480 })
    const quoted = values(source, { authorId: 66807492, tid: 46162468, pid: 857425600 })
    expect(original).toEqual([3])
    expect(quoted).not.toEqual(original)
  })
})

describe('resolveDice 表达式', () => {
  it('XdY 投 X 颗 Y 面骰，点数按数列依次取', () => {
    expect(outcomes('[dice]2d6+3[/dice]')).toEqual([
      {
        expression: '2d6+3',
        terms: [
          { kind: 'roll', faces: 6, value: 4 },
          { kind: 'roll', faces: 6, value: 6 },
          { kind: 'constant', value: 3 },
        ],
        sum: 13,
      },
    ])
  })

  it('常数项写在前面也认', () => {
    expect(outcomes('[dice]20+1d80[/dice]')).toEqual([
      {
        expression: '20+1d80',
        terms: [
          { kind: 'constant', value: 20 },
          { kind: 'roll', faces: 80, value: 48 },
        ],
        sum: 68,
      },
    ])
  })

  it('省略颗数的 dY 当一颗', () => {
    expect(outcomes('[dice]d20[/dice]')).toEqual([
      { expression: 'd20', terms: [{ kind: 'roll', faces: 20, value: 12 }], sum: 12 },
    ])
  })

  it('[dice XdY] 空格参数形式走同一条路', () => {
    expect(values('[dice d20]')).toEqual([12])
  })

  it('纯数字表达式不投骰，原样当常数', () => {
    expect(outcomes('[dice]5[/dice]')).toEqual([
      { expression: '5', terms: [{ kind: 'constant', value: 5 }], sum: 5 },
    ])
  })

  it('超过 10 颗或面数超过 100000 时没有结果（网页版显示 OUT OF LIMIT）', () => {
    expect(outcomes('[dice]11d6[/dice]')[0]).toEqual({ expression: '11d6', terms: [] })
    expect(outcomes('[dice]2d100001[/dice]')[0]).toEqual({ expression: '2d100001', terms: [] })
    // 正好 100000 面是放行的
    expect(values('[dice]1d100000[/dice]')).toEqual([59453])
  })
})

describe('resolveDice 数列共享', () => {
  it('一个楼层里的多颗骰子共用一条数列，不各自从头开始', () => {
    const shared = values('[dice]d6[/dice][dice]d6[/dice][dice]d6[/dice]')
    const alone = values('[dice]d6[/dice]')
    expect(shared[0]).toBe(alone[0])
    expect(shared.slice(1)).not.toEqual([alone[0], alone[0]])
  })

  it('嵌在引用块、加粗、表格里的骰子也在同一条数列上，按文档顺序', () => {
    const flat = values('[dice]d6[/dice][dice]d6[/dice][dice]d6[/dice]')
    const nested = values('[quote][dice]d6[/dice][/quote][b][dice]d6[/dice][/b][dice]d6[/dice]')
    expect(nested).toEqual(flat)
  })

  it('同一楼层里两个写法相同的骰子各有各的点数', () => {
    const nodes = parseBBCode('[dice]d100[/dice][dice]d100[/dice]')
    const table = resolveDice(nodes, SEED)
    expect(table.size).toBe(2)
    expect([...table.values()].map((outcome) => outcome.sum)).toEqual([60, 90])
  })

  it('折叠块里的骰子换一条数列——外层投过就接着外层的种子走', () => {
    // 官方帮助原话：「将[dice]代码移入或移出折叠块……随机数结果会发生改变」
    const inside = values('[collapse=提要][dice]d100[/dice][/collapse]')
    const outside = values('[dice]d100[/dice]')
    expect(inside).not.toEqual(outside)
    // 外层先投一颗时，折叠块从外层推进后的种子接着走
    const [, second] = values('[dice]d100[/dice][collapse][dice]d100[/dice][/collapse]')
    expect(second).toBe(values('[dice]d100[/dice][dice]d100[/dice]')[1])
  })
})

describe('formatDiceTerms', () => {
  it('展开式与网页版同格式', () => {
    const [outcome] = outcomes('[dice]20+2d6[/dice]')
    expect(formatDiceTerms(outcome!.terms)).toBe('20+d6(4)+d6(6)')
  })
})
