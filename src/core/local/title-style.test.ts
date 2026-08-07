import { describe, expect, it } from 'vitest'

import { PLAIN_TITLE_STYLE, decodeTitleStyle, parseTopicMisc } from './title-style'

describe('parseTopicMisc', () => {
  it('拆 base64（无 padding）的 TLV：1 字节 type + 4 字节大端', () => {
    // 真实抓包：fid=650 的版块镜像行，03=子版块 fid 835、01=字体掩码 32（粗体）
    expect(parseTopicMisc('AwAAA0MBAAAAIA')).toEqual({ mask: 32, sfid: 835 })
  })

  it('认得 type=2 的 stid', () => {
    expect(parseTopicMisc('AgH1lHMBAAAACA')).toEqual({ mask: 8, stid: 32871539 })
  })

  it('只有 stid、没有字体记录时不给 mask', () => {
    expect(parseTopicMisc('AgH1lHM')).toEqual({ stid: 32871539 })
  })

  it('空串 / 非字符串 / 解不开的 base64 一律当没写', () => {
    expect(parseTopicMisc('')).toEqual({})
    expect(parseTopicMisc(undefined)).toEqual({})
    expect(parseTopicMisc(42)).toEqual({})
    expect(parseTopicMisc('!!!!')).toEqual({})
  })

  it('官方对以 ~ / ~1 结尾的值直接跳过', () => {
    expect(parseTopicMisc('AwAAA0MBAAAAIA~')).toEqual({})
    expect(parseTopicMisc('AwAAA0MBAAAAIA~1')).toEqual({})
  })
})

describe('decodeTitleStyle', () => {
  it('topic_misc 的掩码解成颜色 + 字重', () => {
    // 真实抓包：置顶主题 topic_misc=AQAAACE → 掩码 0x21 = 红 + 粗
    expect(decodeTitleStyle({ topicMisc: 'AQAAACE' })).toEqual({
      color: 'red',
      bold: true,
      italic: false,
      underline: false,
    })
  })

  it('掩码里的未知高位不影响识别', () => {
    // 真实抓包：活动帖 topic_misc=AQQAACE → 0x04000021，高位 0x04000000 没有定义
    expect(decodeTitleStyle({ topicMisc: 'AQQAACE' })).toEqual({
      color: 'red',
      bold: true,
      italic: false,
      underline: false,
    })
  })

  it('titlefont 是同一套掩码，数字与字符串都收', () => {
    expect(decodeTitleStyle({ titlefont: 2 })).toEqual({
      color: 'blue',
      bold: false,
      italic: false,
      underline: false,
    })
    expect(decodeTitleStyle({ titlefont: '196' })).toEqual({
      // 196 = 4(绿) + 64(斜) + 128(下划线)
      color: 'green',
      bold: false,
      italic: true,
      underline: true,
    })
  })

  it('颜色位同时点亮时按官方的 红>蓝>绿>橙>银 取第一个', () => {
    expect(decodeTitleStyle({ titlefont: 1 | 2 | 4 | 8 | 16 }).color).toBe('red')
    expect(decodeTitleStyle({ titlefont: 2 | 4 | 8 | 16 }).color).toBe('blue')
    expect(decodeTitleStyle({ titlefont: 4 | 8 | 16 }).color).toBe('green')
    expect(decodeTitleStyle({ titlefont: 8 | 16 }).color).toBe('orange')
    expect(decodeTitleStyle({ titlefont: 16 }).color).toBe('silver')
  })

  it('两个来源都在时以 topic_misc 为准', () => {
    // topic_misc 是新字段，Android 的 titlefont 有时是空串
    expect(decodeTitleStyle({ topicMisc: 'AQAAACE', titlefont: 2 }).color).toBe('red')
    expect(decodeTitleStyle({ topicMisc: 'AgH1lHM', titlefont: 2 }).color).toBe('blue')
  })

  it('什么都没有就是普通标题', () => {
    expect(decodeTitleStyle({})).toEqual(PLAIN_TITLE_STYLE)
    expect(decodeTitleStyle({ titlefont: '', topicMisc: '' })).toEqual(PLAIN_TITLE_STYLE)
    expect(decodeTitleStyle({ titlefont: 0 })).toEqual(PLAIN_TITLE_STYLE)
  })
})
