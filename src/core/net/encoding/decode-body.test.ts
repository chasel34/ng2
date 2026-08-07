import { describe, expect, it } from 'vitest'
import { fixtureContentType, readFixtureBytes } from '../__fixtures__'
import { decodeResponseBody, parseCharset } from './decode-body'

const utf8 = (text: string) => new TextEncoder().encode(text)

describe('parseCharset', () => {
  it('抠出声明的 charset 并小写', () => {
    expect(parseCharset('text/javascript; charset=GBK')).toBe('gbk')
    expect(parseCharset('text/html;charset="utf-8"')).toBe('utf-8')
  })

  it('没声明就是 null', () => {
    expect(parseCharset('text/html')).toBeNull()
    expect(parseCharset(null)).toBeNull()
    expect(parseCharset(undefined)).toBeNull()
  })
})

describe('decodeResponseBody', () => {
  it('声明 GBK 就按 GBK 解', () => {
    const bytes = new Uint8Array([0xd4, 0xad, 0xc9, 0xf1])
    expect(decodeResponseBody(bytes, 'text/javascript; charset=GBK')).toBe('原神')
  })

  it('声明 UTF-8 就按 UTF-8 解', () => {
    expect(decodeResponseBody(utf8('原神'), 'application/json; charset=utf-8')).toBe('原神')
  })

  it('没声明 charset 时按内容判：GBK 正文', () => {
    const bytes = new Uint8Array([0xd4, 0xad, 0xc9, 0xf1])
    expect(decodeResponseBody(bytes, 'text/html')).toBe('原神')
  })

  it('没声明 charset 时按内容判：UTF-8 正文', () => {
    expect(decodeResponseBody(utf8('原神'), 'text/html')).toBe('原神')
  })

  it('剥 BOM', () => {
    expect(decodeResponseBody(utf8('﻿{"a":1}'), 'application/json')).toBe('{"a":1}')
  })

  it('真实抓包：thread.php 没声明 charset，body 是 GBK', () => {
    const name = 'threadList'
    expect(fixtureContentType(name)).toBe('text/html')
    const text = decodeResponseBody(readFixtureBytes(name), fixtureContentType(name))
    expect(text).toContain('"name":"原神"')
    expect(text).not.toContain('�')
  })

  it('真实抓包：read.php 声明了 GBK', () => {
    const name = 'readThread'
    const text = decodeResponseBody(readFixtureBytes(name), fixtureContentType(name))
    expect(text.startsWith('window.script_muti_get_var_store=')).toBe(true)
    expect(text).not.toContain('�')
  })
})
