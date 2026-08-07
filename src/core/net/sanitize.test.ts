import { describe, expect, it } from 'vitest'
import { readFixtureBytes } from './__fixtures__'
import { decodeGb18030 } from './encoding/gb18030'
import { sanitizeNgaJson } from './sanitize'

const parse = (raw: string) => JSON.parse(sanitizeNgaJson(raw)) as unknown

describe('sanitizeNgaJson · API 文档 §0.6 的清洗步骤', () => {
  it('1. 剥 window.script_muti_get_var_store= 前缀', () => {
    expect(sanitizeNgaJson('window.script_muti_get_var_store={"data":1}')).toBe('{"data":1}')
  })

  it('1b. lite=htmljs 的 HTML 外壳只留 script 里的那段', () => {
    const raw =
      '<html><body><script>window.script_muti_get_var_store={"data":{"__MESSAGE":{"0":0}}};</script></body></html>'
    expect(parse(raw)).toEqual({ data: { __MESSAGE: { '0': 0 } } })
  })

  it('2. 截断 /*error fill content 尾巴', () => {
    const raw = '{"data":{"0":"ok"}}/*error fill content 这里全是垃圾'
    expect(parse(raw)).toEqual({ data: { '0': 'ok' } })
  })

  it('3. 去 /*$js$*/ 注释标记', () => {
    expect(parse('{"data":/*$js$*/{"0":1}}')).toEqual({ data: { '0': 1 } })
  })

  it('4. 修非法数字：前导 + 与前导 0 都转成字符串', () => {
    expect(parse('{"content":+123,"subject":+45,"author":0678}')).toEqual({
      content: '+123',
      subject: '+45',
      author: '0678',
    })
    expect(parse('{"a":1,"content":0123}')).toEqual({ a: 1, content: '0123' })
  })

  it('4b. 合法数字不动', () => {
    expect(parse('{"content":123,"subject":0,"author":-4}')).toEqual({
      content: 123,
      subject: 0,
      author: -4,
    })
  })

  it('5. 删坏字段 alterinfo（部分页面打不开的原因）', () => {
    expect(parse('{"pid":1,"alterinfo":"[已编辑 2 次] ","lou":3}')).toEqual({ pid: 1, lou: 3 })
  })

  it('6. 整数 key 加引号', () => {
    expect(parse('{0:"a",1:{2:"b"}}')).toEqual({ '0': 'a', '1': { '2': 'b' } })
    expect(parse('{"x":1, 12:"y"}')).toEqual({ x: 1, '12': 'y' })
  })

  it('6b. 字符串里长得像整数 key 的内容不能被改', () => {
    // 正文里出现 `{12:` 时用裸正则会把用户内容改坏，反而解析失败 → 被误判成被封
    const raw = '{"content":"看这段代码 {12:34} 还有 ,56: 这种"}'
    expect(parse(raw)).toEqual({ content: '看这段代码 {12:34} 还有 ,56: 这种' })
  })

  it('7. 字符串内的裸控制字符转义', () => {
    const raw = '{"content":"第一行\n第二行\ttab"}'
    expect(parse(raw)).toEqual({ content: '第一行\n第二行\ttab' })
  })

  it('8. 去掉赋值残留的外层括号与结尾分号', () => {
    expect(sanitizeNgaJson('window.script_muti_get_var_store=({"data":1});')).toBe('{"data":1}')
  })

  it('全部步骤叠一起也能洗成合法 JSON', () => {
    const raw =
      'window.script_muti_get_var_store=({"data":{0:{"pid":9,"alterinfo":"[edited 2] ",' +
      '"content":+7,"subject":012,"note":"裸控制符"}}});/*error fill content xxx'
    expect(parse(raw)).toEqual({
      data: { '0': { pid: 9, content: '+7', subject: '012', note: '裸控制符' } },
    })
  })
})

describe('sanitizeNgaJson · 真实抓包样本', () => {
  const cases = [
    ['notiEmpty', 'notiEmpty'],
    ['threadList', 'threadList'],
    ['readThread', 'readThread'],
    ['ucpUser', 'ucpUser'],
    ['ucpNotFound', 'ucpNotFound'],
    ['readThreadNotFound', 'readThreadNotFound'],
  ] as const

  it.each(cases)('%s 洗完是合法 JSON', (name) => {
    const text = decodeGb18030(readFixtureBytes(name))
    expect(() => JSON.parse(sanitizeNgaJson(text)) as unknown).not.toThrow()
  })

  it('read.php 的 lite=js 前缀被剥掉', () => {
    const text = decodeGb18030(readFixtureBytes('readThread'))
    expect(text.startsWith('window.script_muti_get_var_store=')).toBe(true)
    expect(sanitizeNgaJson(text).startsWith('{')).toBe(true)
  })
})
