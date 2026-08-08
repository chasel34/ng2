import { describe, expect, it } from 'vitest'

import { elementIdOf, findCalls, innerHtmlOf, parseObjectLiterals } from './html-scan'

/**
 * 扫描器的边界用例。整页反解的回归线在 `read-html.test.ts`（真实抓包），
 * 这里钉的是那份注释里说「所以不能用正则」的那几种情形——**都出自用户内容**，
 * 一旦踩中，表现是整页反解莫名其妙地少几楼或串行。
 */

describe('findCalls', () => {
  it('实参里的括号与引号不算数,按顶层逗号切', () => {
    const calls = findCalls("x.f( 1,$('a'),')',\"(\",null )", 'x.f(')
    expect(calls).toHaveLength(1)
    expect(calls[0]!.args).toEqual([
      { kind: 'number', value: 1 },
      { kind: 'expression', text: "$('a')" },
      { kind: 'string', value: ')' },
      { kind: 'string', value: '(' },
      { kind: 'null' },
    ])
  })

  it('转义引号不会提前收尾', () => {
    const calls = findCalls("x.f('it\\'s )', 2)", 'x.f(')
    expect(calls[0]!.args[0]).toEqual({ kind: 'string', value: "it's )" })
    expect(calls[0]!.args[1]).toEqual({ kind: 'number', value: 2 })
  })

  it('多处调用按出现顺序全找出来', () => {
    expect(findCalls('a.f(1) b.g(2) a.f(3)', 'a.f(').map((call) => call.args[0])).toEqual([
      { kind: 'number', value: 1 },
      { kind: 'number', value: 3 },
    ])
  })
})

describe('elementIdOf', () => {
  it("认 $('id') 这一种,别的实参一律 undefined", () => {
    expect(elementIdOf({ kind: 'expression', text: "$('postcontent7')" })).toBe('postcontent7')
    expect(elementIdOf({ kind: 'null' })).toBeUndefined()
    expect(elementIdOf({ kind: 'string', value: 'postcontent7' })).toBeUndefined()
  })
})

describe('innerHtmlOf', () => {
  it('同名标签嵌套时找到对的那个收尾', () => {
    const html = "<div><p id='c'>前<span>里</span>后</p></div>"
    expect(innerHtmlOf(html, 'c')).toBe('前<span>里</span>后')
    expect(innerHtmlOf("<span id='c'>a<span>b</span>c</span>d", 'c')).toBe('a<span>b</span>c')
  })

  it('自闭合标签不加深度(正文里的换行全是 `<br/>`)', () => {
    expect(innerHtmlOf("<p id='c'>一<br/>二</p>", 'c')).toBe('一<br/>二')
  })

  it('实体不解码:与 JSON 接口的 content 同口径', () => {
    expect(innerHtmlOf("<p id='c'>a&amp;b&lt;c</p>", 'c')).toBe('a&amp;b&lt;c')
  })

  it('id 前缀相同的元素不会张冠李戴', () => {
    const html = "<p id='postcontent1'>一</p><p id='postcontent12'>十二</p>"
    expect(innerHtmlOf(html, 'postcontent1')).toBe('一')
    expect(innerHtmlOf(html, 'postcontent12')).toBe('十二')
  })

  it('空元素与找不到的 id', () => {
    expect(innerHtmlOf("<h3 id='c'></h3>", 'c')).toBe('')
    expect(innerHtmlOf("<h3 id='c'></h3>", 'nope')).toBeUndefined()
  })
})

describe('parseObjectLiterals', () => {
  it('键不带引号的 JS 对象数组(附件表就是这个形态)', () => {
    expect(parseObjectLiterals("[{aid:'',url:'a.jpg',thumb:'56'},{url:'b.jpg',size:101}]")).toEqual([
      { aid: '', url: 'a.jpg', thumb: '56' },
      { url: 'b.jpg', size: '101' },
    ])
  })
})
