import { describe, expect, it } from 'vitest'

import { parseBBCode } from './parse'

describe('parseBBCode 骰子', () => {
  it('[dice]1d100[/dice] 保留表达式', () => {
    expect(parseBBCode('[dice]1d100[/dice]')).toEqual([{ type: 'dice', expression: '1d100' }])
  })

  it('[dice XdY] 空格参数形式也认', () => {
    expect(parseBBCode('[dice 2d6]')).toEqual([{ type: 'dice', expression: '2d6' }])
  })

  it('解析器不复算结果,只给出表达式', () => {
    const [node] = parseBBCode('[dice]1d100[/dice]')
    expect(Object.keys(node!)).toEqual(['type', 'expression'])
  })
})

describe('parseBBCode 特殊容器', () => {
  it.each(['lessernuke', 'hip', 'item'])('[%s] 解析成同名 box 节点', (variant) => {
    expect(parseBBCode(`[${variant}]内容[/${variant}]`)).toEqual([
      { type: 'box', variant, children: [{ type: 'text', value: '内容' }] },
    ])
  })

  it('box 内部的标签照常解析', () => {
    expect(parseBBCode('[lessernuke][b]警告[/b][/lessernuke]')).toEqual([
      {
        type: 'box',
        variant: 'lessernuke',
        children: [{ type: 'bold', children: [{ type: 'text', value: '警告' }] }],
      },
    ])
  })
})

describe('parseBBCode stripbr', () => {
  it('去掉内部换行,内容并入上层', () => {
    expect(parseBBCode('前[stripbr]a<br/>b[/stripbr]后')).toEqual([
      { type: 'text', value: '前ab后' },
    ])
  })

  it('只去自己这一层的换行,不动嵌套标签内部的结构', () => {
    expect(parseBBCode('[stripbr]a<br/>[b]c<br/>d[/b][/stripbr]')).toEqual([
      { type: 'text', value: 'a' },
      {
        type: 'bold',
        children: [
          { type: 'text', value: 'c' },
          { type: 'linebreak' },
          { type: 'text', value: 'd' },
        ],
      },
    ])
  })
})
