import { describe, expect, it } from 'vitest'

import { parseBBCode } from './parse'

describe('parseBBCode 纯文本', () => {
  it('把纯文本解析成单个文本节点', () => {
    expect(parseBBCode('你好世界')).toEqual([{ type: 'text', value: '你好世界' }])
  })

  it('空输入解析成空数组', () => {
    expect(parseBBCode('')).toEqual([])
  })

  it('对文本做两轮实体解码', () => {
    expect(parseBBCode('&amp;#55357;&amp;#56836; &lt;tag&gt;')).toEqual([
      { type: 'text', value: '😄 <tag>' },
    ])
  })

  it('把 <br/> 与换行统一成换行节点', () => {
    expect(parseBBCode('a<br/>b<br />c<br>d\ne\r\nf')).toEqual([
      { type: 'text', value: 'a' },
      { type: 'linebreak' },
      { type: 'text', value: 'b' },
      { type: 'linebreak' },
      { type: 'text', value: 'c' },
      { type: 'linebreak' },
      { type: 'text', value: 'd' },
      { type: 'linebreak' },
      { type: 'text', value: 'e' },
      { type: 'linebreak' },
      { type: 'text', value: 'f' },
    ])
  })
})

describe('parseBBCode 文字样式', () => {
  it.each([
    ['b', 'bold'],
    ['i', 'italic'],
    ['u', 'underline'],
    ['del', 'strike'],
  ])('把 [%s] 解析成 %s 节点', (tag, type) => {
    expect(parseBBCode(`[${tag}]文字[/${tag}]`)).toEqual([
      { type, children: [{ type: 'text', value: '文字' }] },
    ])
  })

  it('标签名大小写不敏感', () => {
    expect(parseBBCode('[B]粗[/B]')).toEqual([
      { type: 'bold', children: [{ type: 'text', value: '粗' }] },
    ])
  })

  it('解析 [color=] 并原样保留色值', () => {
    expect(parseBBCode('[color=crimson]红[/color]')).toEqual([
      { type: 'color', value: 'crimson', children: [{ type: 'text', value: '红' }] },
    ])
  })

  it('解析 [size=] 并原样保留百分比', () => {
    expect(parseBBCode('[size=120%]大[/size]')).toEqual([
      { type: 'size', value: '120%', children: [{ type: 'text', value: '大' }] },
    ])
  })

  it('解析 [font=] 并保留字体名', () => {
    expect(parseBBCode('[font=宋体]字[/font]')).toEqual([
      { type: 'font', value: '宋体', children: [{ type: 'text', value: '字' }] },
    ])
  })

  it('支持样式标签互相嵌套', () => {
    expect(parseBBCode('[b]粗[i]又斜[/i][/b]')).toEqual([
      {
        type: 'bold',
        children: [
          { type: 'text', value: '粗' },
          { type: 'italic', children: [{ type: 'text', value: '又斜' }] },
        ],
      },
    ])
  })
})
