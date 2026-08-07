import { describe, expect, it } from 'vitest'

import { parseBBCode } from './parse'

describe('parseBBCode 链接', () => {
  it('[url]地址[/url] 把内容当地址,children 留空', () => {
    expect(parseBBCode('[url]https://bbs.nga.cn/read.php?tid=1[/url]')).toEqual([
      { type: 'link', href: 'https://bbs.nga.cn/read.php?tid=1', children: [] },
    ])
  })

  it('[url] 内容不解析标签,但要解实体', () => {
    expect(parseBBCode('[url]https://x.com/?a=1&amp;b=[b][/url]')).toEqual([
      { type: 'link', href: 'https://x.com/?a=1&b=[b]', children: [] },
    ])
  })

  it('[url=地址]文字[/url] 的文字照常解析', () => {
    expect(parseBBCode('[url=https://x.com]看[b]这[/b][/url]')).toEqual([
      {
        type: 'link',
        href: 'https://x.com',
        children: [
          { type: 'text', value: '看' },
          { type: 'bold', children: [{ type: 'text', value: '这' }] },
        ],
      },
    ])
  })
})

describe('parseBBCode 用户与主题引用', () => {
  it('[uid]123[/uid] 从内容取 uid', () => {
    expect(parseBBCode('[uid]41417929[/uid]')).toEqual([
      { type: 'userRef', uid: '41417929', children: [] },
    ])
  })

  it('[uid=123]名字[/uid] 从参数取 uid,内容作显示名', () => {
    expect(parseBBCode('[uid=41417929]张三[/uid]')).toEqual([
      { type: 'userRef', uid: '41417929', children: [{ type: 'text', value: '张三' }] },
    ])
  })

  it('[tid] 两种形态', () => {
    expect(parseBBCode('[tid]45150945[/tid]')).toEqual([
      { type: 'topicRef', tid: '45150945', children: [] },
    ])
    expect(parseBBCode('[tid=45150945]标题[/tid]')).toEqual([
      { type: 'topicRef', tid: '45150945', children: [{ type: 'text', value: '标题' }] },
    ])
  })

  it('[pid=a,b,c] 取第一个参数作 pid,其余留在 args', () => {
    expect(parseBBCode('[pid=123,456,1]Reply[/pid]')).toEqual([
      {
        type: 'floorRef',
        pid: '123',
        args: ['123', '456', '1'],
        children: [{ type: 'text', value: 'Reply' }],
      },
    ])
  })

  it('[pid]123[/pid] 从内容取 pid', () => {
    expect(parseBBCode('[pid]123[/pid]')).toEqual([
      { type: 'floorRef', pid: '123', args: ['123'], children: [] },
    ])
  })
})

describe('parseBBCode @提及', () => {
  it('[@用户名] 是自闭合的提及节点', () => {
    expect(parseBBCode('回复 [@小明] 你好')).toEqual([
      { type: 'text', value: '回复 ' },
      { type: 'mention', username: '小明' },
      { type: 'text', value: ' 你好' },
    ])
  })

  it('用户名里的实体照常解码', () => {
    expect(parseBBCode('[@a&amp;b]')).toEqual([{ type: 'mention', username: 'a&b' }])
  })

  it('[@]名字[/@] 形式也认', () => {
    expect(parseBBCode('[@]小红[/@]')).toEqual([{ type: 'mention', username: '小红' }])
  })

  it('空的 [@] 原样透传', () => {
    expect(parseBBCode('[@]')).toEqual([{ type: 'text', value: '[@]' }])
  })
})
