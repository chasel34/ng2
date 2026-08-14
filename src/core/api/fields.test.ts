import { describe, expect, it } from 'vitest'

import { orderedEntries, orderedValues } from './fields'

describe('orderedEntries · NGA 的「数组」有两种下发形态', () => {
  it('字符串数字键的对象按数字升序（__output=8 的习惯，API 文档 §0.6）', () => {
    expect(orderedValues({ '10': 'j', '2': 'c', '0': 'a' })).toEqual(['a', 'c', 'j'])
  })

  it('真数组也当列表遍历（__output=11 的 __T 就是真数组）', () => {
    // 2026-08-14：`isRecord` 把数组排除在外，于是 __output=11 的整页主题静默变成 0 条，
    // 表现是「版块打不开」而不是「解析失败」——比报错更难查，所以单独钉一条用例。
    expect(orderedValues(['a', 'c', 'j'])).toEqual(['a', 'c', 'j'])
    expect(orderedEntries(['a', 'c'])).toEqual([
      ['0', 'a'],
      ['1', 'c'],
    ])
  })

  it('空数组和空对象一样给空列表，不是「解析失败」', () => {
    expect(orderedValues([])).toEqual([])
    expect(orderedValues({})).toEqual([])
  })

  it('非数字键排在数字键后面，保持原有顺序', () => {
    expect(orderedEntries({ b: 2, '1': 'x', a: 1, '0': 'y' })).toEqual([
      ['0', 'y'],
      ['1', 'x'],
      ['b', 2],
      ['a', 1],
    ])
  })

  it('既不是对象也不是数组时给空列表', () => {
    expect(orderedValues(null)).toEqual([])
    expect(orderedValues('abc')).toEqual([])
    expect(orderedValues(42)).toEqual([])
    expect(orderedValues(undefined)).toEqual([])
  })
})
