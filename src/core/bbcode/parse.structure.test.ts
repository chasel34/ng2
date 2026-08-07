import { describe, expect, it } from 'vitest'

import { parseBBCode } from './parse'

describe('parseBBCode 引用与代码', () => {
  it('解析 [quote] 并保留内部结构', () => {
    expect(parseBBCode('[quote][b]Post by 张三[/b]内容[/quote]')).toEqual([
      {
        type: 'quote',
        children: [
          { type: 'bold', children: [{ type: 'text', value: 'Post by 张三' }] },
          { type: 'text', value: '内容' },
        ],
      },
    ])
  })

  it('[code] 内部不解析标签,只做实体解码', () => {
    expect(parseBBCode('[code]if (a &lt; b) [b]x[/b][/code]')).toEqual([
      { type: 'code', value: 'if (a < b) [b]x[/b]' },
    ])
  })

  it('[code] 内的 <br/> 还原成换行字符', () => {
    expect(parseBBCode('[code]a<br/>b[/code]')).toEqual([{ type: 'code', value: 'a\nb' }])
  })
})

describe('parseBBCode 折叠块', () => {
  it('[collapse] 无标题', () => {
    expect(parseBBCode('[collapse]内容[/collapse]')).toEqual([
      { type: 'collapse', children: [{ type: 'text', value: '内容' }] },
    ])
  })

  it('[collapse=标题] 带标题', () => {
    expect(parseBBCode('[collapse=剧透]内容[/collapse]')).toEqual([
      { type: 'collapse', title: '剧透', children: [{ type: 'text', value: '内容' }] },
    ])
  })
})

describe('parseBBCode 列表', () => {
  it('[list] 按 [*] 切分出条目', () => {
    expect(parseBBCode('[list][*]一[*]二[/list]')).toEqual([
      {
        type: 'list',
        ordered: false,
        items: [[{ type: 'text', value: '一' }], [{ type: 'text', value: '二' }]],
      },
    ])
  })

  it('[list=1] 是有序列表', () => {
    expect(parseBBCode('[list=1][*]一[/list]')).toEqual([
      { type: 'list', ordered: true, items: [[{ type: 'text', value: '一' }]] },
    ])
  })

  it('丢弃条目之间的换行与空白', () => {
    expect(parseBBCode('[list]\n[*]一\n[*]二\n[/list]')).toEqual([
      {
        type: 'list',
        ordered: false,
        items: [[{ type: 'text', value: '一' }], [{ type: 'text', value: '二' }]],
      },
    ])
  })
})

describe('parseBBCode 表格', () => {
  it('解析 tr/td 并给出 colspan、rowspan 默认值', () => {
    expect(parseBBCode('[table][tr][td]a[/td][td]b[/td][/tr][/table]')).toEqual([
      {
        type: 'table',
        rows: [
          {
            cells: [
              { colspan: 1, rowspan: 1, children: [{ type: 'text', value: 'a' }] },
              { colspan: 1, rowspan: 1, children: [{ type: 'text', value: 'b' }] },
            ],
          },
        ],
      },
    ])
  })

  it('解析 td 上的 colspan/rowspan/width 属性', () => {
    expect(parseBBCode('[table][tr][td colspan=2 rowspan=3 width=100]a[/td][/tr][/table]')).toEqual([
      {
        type: 'table',
        rows: [
          {
            cells: [
              { colspan: 2, rowspan: 3, width: '100', children: [{ type: 'text', value: 'a' }] },
            ],
          },
        ],
      },
    ])
  })

  it('丢弃 table/tr 里游离的空白与换行', () => {
    expect(parseBBCode('[table]\n[tr]\n[td]a[/td]\n[/tr]\n[/table]')).toEqual([
      {
        type: 'table',
        rows: [{ cells: [{ colspan: 1, rowspan: 1, children: [{ type: 'text', value: 'a' }] }] }],
      },
    ])
  })
})

describe('parseBBCode 对齐、标题与分割线', () => {
  it.each([
    ['[align=center]中[/align]', 'center'],
    ['[align=right]右[/align]', 'right'],
    ['[align]默认[/align]', 'left'],
    ['[l]左[/l]', 'left'],
    ['[r]右[/r]', 'right'],
  ])('%s 对齐为 %s', (source, align) => {
    const [node] = parseBBCode(source)
    expect(node).toMatchObject({ type: 'align', align })
  })

  it('[h] 是标题', () => {
    expect(parseBBCode('[h]小标题[/h]')).toEqual([
      { type: 'heading', children: [{ type: 'text', value: '小标题' }] },
    ])
  })

  it('===标题=== 在行首解析为标题', () => {
    expect(parseBBCode('===开场===<br/>正文')).toEqual([
      { type: 'heading', children: [{ type: 'text', value: '开场' }] },
      { type: 'linebreak' },
      { type: 'text', value: '正文' },
    ])
  })

  it('===标题=== 内部的标签照常解析', () => {
    expect(parseBBCode('===第[b]一[/b]章===')).toEqual([
      {
        type: 'heading',
        children: [
          { type: 'text', value: '第' },
          { type: 'bold', children: [{ type: 'text', value: '一' }] },
          { type: 'text', value: '章' },
        ],
      },
    ])
  })

  it('独占一行的 ====== 是分割线', () => {
    expect(parseBBCode('上<br/>======<br/>下')).toEqual([
      { type: 'text', value: '上' },
      { type: 'linebreak' },
      { type: 'divider' },
      { type: 'linebreak' },
      { type: 'text', value: '下' },
    ])
  })

  it('行中间的等号不当标题或分割线', () => {
    expect(parseBBCode('a===b=== c======')).toEqual([{ type: 'text', value: 'a===b=== c======' }])
  })
})
