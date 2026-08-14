import { describe, expect, it } from 'vitest'
import {
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_ROTATION_FORMATS,
  createComboCache,
  enumerateCombos,
  formatParamsOf,
  interfaceKeyOf,
  isRotatableFormat,
} from './combo'
import type { FetchCombo } from './combo'

const ids = (combos: readonly FetchCombo[]) => combos.map((c) => `${c.format}@${c.host}`)

describe('interfaceKeyOf · 成功组合按「接口」缓存', () => {
  it('光有 path 的接口就用 path', () => {
    expect(interfaceKeyOf({ path: 'thread.php', query: { fid: 650 } })).toBe('thread.php')
  })

  it('nuke.php 底下按 __lib/__act 细分——被封的粒度是接口不是脚本文件', () => {
    const noti = interfaceKeyOf({ path: 'nuke.php', query: { __lib: 'noti', __act: 'get_all' } })
    const ucp = interfaceKeyOf({ path: 'nuke.php', query: { __lib: 'ucp', __act: 'get' } })

    expect(noti).toBe('nuke.php?__lib=noti&__act=get_all')
    expect(ucp).not.toBe(noti)
  })

  it('同一接口的业务参数不进 key:tid 换了不该重新试探', () => {
    expect(interfaceKeyOf({ path: 'read.php', query: { tid: 1 } })).toBe(
      interfaceKeyOf({ path: 'read.php', query: { tid: 2 } }),
    )
  })
})

describe('enumerateCombos · 格式 × 域名', () => {
  const formats = ['json', 'jsonLite'] as const
  const hosts = ['https://a', 'https://b'] as const

  it('域名外层、格式内层:换格式比换域名便宜,先在同一台上换', () => {
    const combos = enumerateCombos({ formats, hosts, maxAttempts: 10 })

    expect(ids(combos)).toEqual([
      'json@https://a',
      'jsonLite@https://a',
      'json@https://b',
      'jsonLite@https://b',
    ])
  })

  it('缓存里的成功组合排第一,且不会再出现第二次', () => {
    const combos = enumerateCombos({
      formats,
      hosts,
      preferred: { format: 'jsonLite', host: 'https://b' },
      maxAttempts: 10,
    })

    expect(ids(combos)[0]).toBe('jsonLite@https://b')
    expect(ids(combos)).toHaveLength(4)
  })

  it('调用方指定的组合排在轮换前面,但不独占——它照样可能被封', () => {
    const combos = enumerateCombos({
      formats,
      hosts,
      requested: { format: 'jsonVerbose', host: 'https://c' },
      maxAttempts: 10,
    })

    expect(ids(combos)[0]).toBe('jsonVerbose@https://c')
    expect(ids(combos)).toHaveLength(5)
  })

  it('上限截断,免得让用户等十几个来回', () => {
    expect(enumerateCombos({ formats, hosts, maxAttempts: 3 })).toHaveLength(3)
    // 上限再离谱也要发一次,否则这一档等于不存在
    expect(enumerateCombos({ formats, hosts, maxAttempts: 0 })).toHaveLength(1)
  })

  it('点名要 XML/HTML 时不轮换:那条路线上还没有解析器(19 号票)', () => {
    const combos = enumerateCombos({
      formats,
      hosts,
      requested: { format: 'xml', host: 'https://a' },
      maxAttempts: 10,
    })

    expect(ids(combos)).toEqual(['xml@https://a'])
  })

  it('不可解析的格式不进轮换', () => {
    expect(isRotatableFormat('json')).toBe(true)
    expect(isRotatableFormat('xml')).toBe(false)
    expect(isRotatableFormat('html')).toBe(false)

    const combos = enumerateCombos({
      formats: ['json', 'xml'],
      hosts: ['https://a'],
      maxAttempts: 10,
    })
    expect(ids(combos)).toEqual(['json@https://a'])
  })
})

describe('createComboCache', () => {
  it('记住、读回、清掉', () => {
    const cache = createComboCache()
    const combo: FetchCombo = { format: 'jsonLite', host: 'https://a' }

    expect(cache.get('read.php')).toBeUndefined()
    cache.remember('read.php', combo)
    expect(cache.get('read.php')).toEqual(combo)
    expect(cache.get('thread.php')).toBeUndefined()
    cache.forget('read.php')
    expect(cache.get('read.php')).toBeUndefined()
  })

  it('条目有保质期：过期后当没记过，重新从默认组合试探', () => {
    // 「全组合都失败才清缓存」这一条出口不够用：组合半通不通（能解析但没有业务数据）时
    // 缓存永远清不掉，唯一复位手段变成杀进程（2026-08-13「版块全空」排查）
    let clock = 0
    const cache = createComboCache({ ttlMs: 1000, now: () => clock })
    cache.remember('thread.php', { format: 'json', host: 'https://a' })

    clock = 999
    expect(cache.get('thread.php')).toEqual({ format: 'json', host: 'https://a' })
    clock = 1000
    expect(cache.get('thread.php')).toBeUndefined()
  })

  it('entries 给出当前记着的全部组合（实验室页的「本次运行的组合」）', () => {
    let clock = 0
    const cache = createComboCache({ ttlMs: 1000, now: () => clock })
    cache.remember('thread.php', { format: 'json', host: 'https://a' })
    clock = 500
    cache.remember('read.php', { format: 'jsonLite', host: 'https://b' })

    expect(cache.entries()).toEqual([
      ['thread.php', { combo: { format: 'json', host: 'https://a' }, at: 0 }],
      ['read.php', { combo: { format: 'jsonLite', host: 'https://b' }, at: 500 }],
    ])

    // 过期的不列出来
    clock = 1200
    expect(cache.entries().map(([key]) => key)).toEqual(['read.php'])
  })
})

describe('formatParamsOf · 诊断日志里要看得出实际发的是什么', () => {
  it('给出格式档位对应的 query 参数', () => {
    expect(formatParamsOf('json')).toBe('__output=8')
    expect(formatParamsOf('jsonLite')).toBe('lite=js')
    expect(formatParamsOf('html')).toBe('(无格式参数)')
  })
})

describe('默认轮换表 · 冗余来自「不共用同一段服务端代码」', () => {
  it('jsonVerbose 在表里,而且排在 jsonLite 前面', () => {
    // fid=414 的教训:`json`(__output=8) 与 `jsonLite`(lite=js) 是**同一份字节**,
    // 只差一层 `window.script_muti_get_var_store=` 包装。服务端把坏字节写进那份响应时,
    // 这两档一起完蛋,换几个域名都一样。`jsonVerbose`(__output=11) 是另一个序列化器,
    // 必须在耗掉一整轮域名之前就试到它。
    expect(DEFAULT_ROTATION_FORMATS).toContain('jsonVerbose')
    expect(DEFAULT_ROTATION_FORMATS.indexOf('jsonVerbose')).toBeLessThan(
      DEFAULT_ROTATION_FORMATS.indexOf('jsonLite'),
    )
  })

  it('默认上限够第一个域名试满所有格式,还剩得下第二、三个域名', () => {
    const combos = enumerateCombos({
      formats: DEFAULT_ROTATION_FORMATS,
      hosts: ['https://a', 'https://b', 'https://c'],
      maxAttempts: DEFAULT_MAX_ATTEMPTS,
    })
    const hostsTried = new Set(combos.map((c) => c.host))

    // 第一个域名把三个格式都试到(坏字节靠换格式救)
    expect(ids(combos).slice(0, 3)).toEqual([
      'json@https://a',
      'jsonVerbose@https://a',
      'jsonLite@https://a',
    ])
    // 同时保住三域名覆盖(被封靠换域名救),两者都不能丢
    expect(hostsTried.size).toBe(3)
  })
})
