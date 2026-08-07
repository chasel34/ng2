import { describe, expect, it } from 'vitest'

import { parseBBCode } from './parse'

describe('parseBBCode 容错', () => {
  it('未知标签连同闭标签原样透传为文本', () => {
    expect(parseBBCode('前[randomblock]中[/randomblock]后')).toEqual([
      { type: 'text', value: '前[randomblock]中[/randomblock]后' },
    ])
  })

  it('未知标签内部的已知标签照常解析', () => {
    expect(parseBBCode('[style x]看[b]这[/b][/style]')).toEqual([
      { type: 'text', value: '[style x]看' },
      { type: 'bold', children: [{ type: 'text', value: '这' }] },
      { type: 'text', value: '[/style]' },
    ])
  })

  it('孤立的闭标签原样透传为文本', () => {
    expect(parseBBCode('文字[/b]尾')).toEqual([{ type: 'text', value: '文字[/b]尾' }])
  })

  it('未闭合标签降级:开标签变文本,内容原样并入上层', () => {
    expect(parseBBCode('[b]没关')).toEqual([{ type: 'text', value: '[b]没关' }])
  })

  it('交叉嵌套时只降级内层未闭合的那个', () => {
    expect(parseBBCode('[quote][b]交叉[/quote]')).toEqual([
      { type: 'quote', children: [{ type: 'text', value: '[b]交叉' }] },
    ])
  })

  it('闭标签跨越多层时,中间未闭合的层逐个降级', () => {
    expect(parseBBCode('[b][i][u]深[/b]')).toEqual([
      {
        type: 'bold',
        children: [{ type: 'text', value: '[i][u]深' }],
      },
    ])
  })

  it('超深嵌套不会撑爆调用栈,超出部分退化为文本', () => {
    const depth = 5000
    const source = `${'[b]'.repeat(depth)}底${'[/b]'.repeat(depth)}`
    const nodes = parseBBCode(source)
    expect(nodes[0]).toMatchObject({ type: 'bold' })
    expect(JSON.stringify(nodes)).toContain('底')
  })

  it('未闭合的 [code] 不吞掉外层的闭标签', () => {
    expect(parseBBCode('[quote][code]abc[/quote]正文[code]x[/code]')).toEqual([
      { type: 'quote', children: [{ type: 'text', value: '[code]abc' }] },
      { type: 'text', value: '正文' },
      { type: 'code', value: 'x' },
    ])
  })

  it('未闭合的 [img] 同样不越过外层闭标签', () => {
    expect(parseBBCode('[b][img]./a.jpg[/b]')).toEqual([
      { type: 'bold', children: [{ type: 'text', value: '[img]./a.jpg' }] },
    ])
  })

  it('方括号里的非标签内容原样保留', () => {
    expect(parseBBCode('[这是中文] [1] []')).toEqual([
      { type: 'text', value: '[这是中文] [1] []' },
    ])
  })
})
