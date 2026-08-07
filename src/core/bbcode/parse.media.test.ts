import { describe, expect, it } from 'vitest'

import { resolveSmiley } from '@/core/smilies'

import { parseBBCode } from './parse'
import type { SmileyNode } from './types'

describe('parseBBCode 图片', () => {
  it('绝对地址的 [img] 直出', () => {
    expect(parseBBCode('[img]https://img.nga.cn/attachments/a.jpg[/img]')).toEqual([
      {
        type: 'image',
        variant: 'img',
        src: 'https://img.nga.cn/attachments/a.jpg',
        needsAttachBase: false,
      },
    ])
  })

  it('./ 开头的相对路径剥掉 ./ 并标记为待拼接附件域名', () => {
    expect(parseBBCode('[img]./mon_202608/07/abc.jpg[/img]')).toEqual([
      {
        type: 'image',
        variant: 'img',
        src: 'mon_202608/07/abc.jpg',
        needsAttachBase: true,
      },
    ])
  })

  it('AST 里不出现任何写死的域名', () => {
    const [node] = parseBBCode('[img]./a.jpg[/img]')
    expect(JSON.stringify(node)).not.toMatch(/nga|178|http/i)
  })

  it('地址两边的空白与换行被清掉', () => {
    expect(parseBBCode('[img]<br/> ./a.jpg <br/>[/img]')).toEqual([
      { type: 'image', variant: 'img', src: 'a.jpg', needsAttachBase: true },
    ])
  })

  it('[noimg] 单独成一类,路径同样待拼接', () => {
    expect(parseBBCode('[noimg]./07/x.jpg[/noimg]')).toEqual([
      { type: 'image', variant: 'noimg', src: '07/x.jpg', needsAttachBase: true },
    ])
  })

  it('不带 ./ 的裸文件名也是待拼接——[noimg] 的实际形态就是裸文件名', () => {
    expect(parseBBCode('[noimg]12345_abc.jpg[/noimg]')).toEqual([
      { type: 'image', variant: 'noimg', src: '12345_abc.jpg', needsAttachBase: true },
    ])
  })

  it('协议相对地址 // 开头当绝对地址,不拼附件域名', () => {
    expect(parseBBCode('[img]//img.nga.cn/a.jpg[/img]')).toEqual([
      { type: 'image', variant: 'img', src: '//img.nga.cn/a.jpg', needsAttachBase: false },
    ])
  })
})

describe('parseBBCode 附件与相册', () => {
  it('[attach] 相对路径同样待拼接', () => {
    expect(parseBBCode('[attach]./mon_202608/07/f.zip[/attach]')).toEqual([
      { type: 'attach', src: 'mon_202608/07/f.zip', needsAttachBase: true },
    ])
  })

  it('[album] 原样保留内容', () => {
    expect(parseBBCode('[album]12345[/album]')).toEqual([{ type: 'album', value: '12345' }])
  })
})

describe('parseBBCode flash 媒体', () => {
  it.each([
    ['[flash=video]https://v.cn/a.mp4[/flash]', 'video'],
    ['[flash=audio]https://v.cn/a.mp3[/flash]', 'audio'],
    ['[flash]https://v.cn/a.swf[/flash]', 'flash'],
  ])('%s 的媒体类型是 %s', (source, media) => {
    expect(parseBBCode(source)).toMatchObject([{ type: 'flash', media }])
  })

  it('flash 的相对路径也标记待拼接', () => {
    expect(parseBBCode('[flash=video]./mon_202608/07/a.mp4[/flash]')).toEqual([
      { type: 'flash', media: 'video', src: 'mon_202608/07/a.mp4', needsAttachBase: true },
    ])
  })
})

describe('parseBBCode 表情', () => {
  // 分类/名称怎么切由 src/core/smilies 的 resolveSmiley 说了算(它照抄官方 JS),
  // 解析器只负责原样捞出 `[s:` 与 `]` 之间的内容。
  it('[s:分类:名称] 原样捞出内容', () => {
    expect(parseBBCode('哈[s:ac:笑]哈')).toEqual([
      { type: 'text', value: '哈' },
      { type: 'smiley', code: 'ac:笑' },
      { type: 'text', value: '哈' },
    ])
  })

  it('[s:数字] 默认套同样只捞内容', () => {
    expect(parseBBCode('[s:14]')).toEqual([{ type: 'smiley', code: '14' }])
  })

  it('名称里的下划线等符号照收', () => {
    expect(parseBBCode('[s:pst:凯露_哭]')).toEqual([{ type: 'smiley', code: 'pst:凯露_哭' }])
  })

  it('残缺的表情标签原样透传', () => {
    expect(parseBBCode('[s:]')).toEqual([{ type: 'text', value: '[s:]' }])
  })
})

describe('parseBBCode 表情与 resolveSmiley 的接缝', () => {
  // 解析器只切出 `code`,分类/名称与兜底全归 src/core/smilies。这里锁住两边的接口:
  // 查不到时 resolveSmiley 还原出的原文,必须和楼层里那段 BBCode 一模一样。
  it.each(['[s:ac:根本不存在]', '[s:未知分类:名字]', '[s:99999]'])(
    '%s 查不到时能原样还原',
    (raw) => {
      const [node] = parseBBCode(raw)
      expect(node).toMatchObject({ type: 'smiley' })
      expect(resolveSmiley((node as SmileyNode).code)).toEqual({ kind: 'unresolved', raw })
    },
  )

  it('切出的 code 能命中映射表', () => {
    const [node] = parseBBCode('[s:ac:goodjob]')
    expect(resolveSmiley((node as SmileyNode).code).kind).not.toBe('unresolved')
  })
})
