import { describe, expect, it } from 'vitest'

import { parseBBCode } from './parse'
import type { BBCodeNode } from './types'
import { childNodeLists } from './walk'

const only = (source: string): BBCodeNode => {
  const [node] = parseBBCode(source)
  expect(node).toBeDefined()
  return node!
}

/** 走一遍整棵树，把文字接起来——遍历漏了哪种容器，这里就会少一段。 */
function flatten(nodes: readonly BBCodeNode[]): string {
  return nodes
    .map((node) => (node.type === 'text' ? node.value : flatten(childNodeLists(node).flat())))
    .join('')
}

describe('childNodeLists', () => {
  it('普通容器给的是 children', () => {
    expect(childNodeLists(only('[b]粗[/b]'))).toEqual([[{ type: 'text', value: '粗' }]])
  })

  it('列表给的是每一项', () => {
    expect(childNodeLists(only('[list][*]甲[*]乙[/list]'))).toEqual([
      [{ type: 'text', value: '甲' }],
      [{ type: 'text', value: '乙' }],
    ])
  })

  it('表格给的是每个格子，按行铺平', () => {
    const lists = childNodeLists(
      only('[table][tr][td]甲[/td][td]乙[/td][/tr][tr][td]丙[/td][/tr][/table]'),
    )
    expect(lists.map((list) => flatten(list))).toEqual(['甲', '乙', '丙'])
  })

  it('叶子节点没有子节点', () => {
    expect(childNodeLists(only('就是一段字'))).toEqual([])
    expect(childNodeLists(only('[dice]1d100[/dice]'))).toEqual([])
    expect(childNodeLists(only('[img]./a.jpg[/img]'))).toEqual([])
  })

  it('拿它走整棵树，各种容器里的字一个都不漏', () => {
    const source =
      '开头[b]粗[/b][quote]引用[/quote][collapse=提要]折叠[/collapse]' +
      '[list][*]甲[/list][table][tr][td]格子[/td][/tr][/table][align=center]居中[/align]'
    expect(flatten(parseBBCode(source))).toBe('开头粗引用折叠甲格子居中')
  })
})
