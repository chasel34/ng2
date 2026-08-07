import { describe, expect, it } from 'vitest'

import { parseBBCode } from './index'

/**
 * 整段楼层正文的对拍。样例照 NGA `read.php` 返回的 `content` 字段形态手写:
 * 引用块里嵌 pid/uid、正文混排表情与相对路径图片、全程 `<br/>` 换行、实体双重转义。
 */
describe('parseBBCode 整段楼层', () => {
  const content =
    '[quote][pid=123456,45150945,1]Reply[/pid] [b]Post by [uid=41417929]张三[/uid] (2026-08-07 12:00):[/b]<br/>' +
    '原话&lt;不要断章取义&gt;[/quote]' +
    '同意[s:ac:goodjob]<br/>' +
    '[img]./mon_202608/07/-abcdefg.jpg[/img]<br/>' +
    '详见 [url=https://bbs.nga.cn/read.php?tid=45150945]这帖[/url] 和 [@李四]<br/>' +
    '&amp;#55357;&amp;#56836;'

  it('解析出预期的顶层节点序列', () => {
    expect(parseBBCode(content).map((node) => node.type)).toEqual([
      'quote',
      'text',
      'smiley',
      'linebreak',
      'image',
      'linebreak',
      'text',
      'link',
      'text',
      'mention',
      'linebreak',
      'text',
    ])
  })

  it('引用块内保留被引用的楼层与作者', () => {
    const [quote] = parseBBCode(content)
    expect(quote).toMatchObject({
      type: 'quote',
      children: [
        { type: 'floorRef', pid: '123456', args: ['123456', '45150945', '1'] },
        { type: 'text', value: ' ' },
        {
          type: 'bold',
          children: [
            { type: 'text', value: 'Post by ' },
            { type: 'userRef', uid: '41417929', children: [{ type: 'text', value: '张三' }] },
            { type: 'text', value: ' (2026-08-07 12:00):' },
          ],
        },
        { type: 'linebreak' },
        { type: 'text', value: '原话<不要断章取义>' },
      ],
    })
  })

  it('图片标成待拼接附件域名,末尾 emoji 完成两轮解码', () => {
    const nodes = parseBBCode(content)
    expect(nodes.find((node) => node.type === 'image')).toEqual({
      type: 'image',
      variant: 'img',
      src: 'mon_202608/07/-abcdefg.jpg',
      needsAttachBase: true,
    })
    expect(nodes[nodes.length - 1]).toEqual({ type: 'text', value: '😄' })
  })

  it('AST 可以直接 JSON 往返,便于写进帖子缓存', () => {
    const ast = parseBBCode(content)
    expect(JSON.parse(JSON.stringify(ast))).toEqual(ast)
  })
})
