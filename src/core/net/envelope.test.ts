import { describe, expect, it } from 'vitest'
import { readFixtureBytes } from './__fixtures__'
import { decodeGb18030 } from './encoding/gb18030'
import { parseNgaJson } from './envelope'
import { NgaError, extractServerError, isFakeError } from './errors'

const fixtureText = (name: Parameters<typeof readFixtureBytes>[0]) =>
  decodeGb18030(readFixtureBytes(name))

describe('isFakeError · 假错误白名单（API 文档 §0.7）', () => {
  it.each(['完毕', '没找到', '没有符合条件的结果', '今天已经签到', '找不到用户'])(
    '%s 视为成功',
    (message) => {
      expect(isFakeError(message)).toBe(true)
    },
  )

  it('白名单按子串匹配：发贴完毕 / 操作完毕', () => {
    expect(isFakeError('发贴完毕')).toBe(true)
    expect(isFakeError('操作完毕，正在跳转')).toBe(true)
  })

  it('真错误不在白名单里', () => {
    expect(isFakeError('找不到主题')).toBe(false)
    expect(isFakeError('未登录')).toBe(false)
    expect(isFakeError('您没有权限进行此操作')).toBe(false)
  })
})

describe('extractServerError', () => {
  it('无 code 时补 ?', () => {
    expect(extractServerError({ error: { '0': '未登录' } })).toEqual({ code: '?', message: '未登录' })
  })

  it('取 error.code', () => {
    expect(extractServerError({ error: { code: 403, '0': '找不到主题' } })).toEqual({
      code: 403,
      message: '找不到主题',
    })
  })

  it('多条信息用；连起来', () => {
    expect(extractServerError({ error: { '0': 'a', '1': 'b', '2': '' } })).toEqual({
      code: '?',
      message: 'a；b',
    })
  })

  it('没有 error 就是 null', () => {
    expect(extractServerError({ data: {}, time: 1 })).toBeNull()
    expect(extractServerError('not an object')).toBeNull()
  })
})

describe('parseNgaJson', () => {
  it('取出 data 与 time', () => {
    const envelope = parseNgaJson('{"data":{"0":"ok"},"time":1786111705}')
    expect(envelope.data).toEqual({ '0': 'ok' })
    expect(envelope.time).toBe(1786111705)
    expect(envelope.fakeError).toBeUndefined()
  })

  it('顶层既没有 data 也没有 error 时默认报解析错（可重试，链会换下一个组合）', () => {
    // 以前这里把顶层当 data，于是任何一个陌生 JSON 都成了「合法的空数据」，
    // 一路走到 UI 变成「这个版块还没有主题」（2026-08-13，「版块全空」排查）
    try {
      parseNgaJson('{"code":0,"msg":"","result":[]}', 'direct')
      expect.unreachable('应当抛错')
    } catch (error) {
      expect(error).toBeInstanceOf(NgaError)
      expect((error as NgaError).kind).toBe('parse')
      expect((error as NgaError).retryable).toBe(true)
      expect((error as NgaError).via).toBe('direct')
    }
  })

  it('调用方显式声明 bare 时，顶层才当 data', () => {
    const envelope = parseNgaJson('{"code":0,"msg":"","result":[]}', undefined, 'bare')
    expect(envelope.data).toEqual({ code: 0, msg: '', result: [] })
  })

  it('有 data 壳的响应不受 bare 影响', () => {
    expect(parseNgaJson('{"data":{"0":"ok"}}', undefined, 'bare').data).toEqual({ '0': 'ok' })
  })

  it('真错误抛 server 错误，且不重试', () => {
    const text = fixtureText('readThreadNotFound')
    expect(() => parseNgaJson(text)).toThrowError(NgaError)
    try {
      parseNgaJson(text, 'direct')
      expect.unreachable('应当抛错')
    } catch (error) {
      expect(error).toBeInstanceOf(NgaError)
      const ngaError = error as NgaError
      expect(ngaError.kind).toBe('server')
      expect(ngaError.message).toContain('找不到主题')
      expect(ngaError.retryable).toBe(false)
      expect(ngaError.via).toBe('direct')
    }
  })

  it('假错误当成功返回，但把错误信息留给调用方判空', () => {
    const envelope = parseNgaJson(fixtureText('ucpNotFound'))
    expect(envelope.fakeError?.message).toBe('找不到用户')
    expect(envelope.data).toBeUndefined()
  })

  it('解析失败抛 parse 错误，且可重试（解析失败 ≈ 被封）', () => {
    try {
      parseNgaJson('<html>你被封了</html>')
      expect.unreachable('应当抛错')
    } catch (error) {
      const ngaError = error as NgaError
      expect(ngaError.kind).toBe('parse')
      expect(ngaError.retryable).toBe(true)
    }
  })

  it('空响应也算 parse 错误', () => {
    expect(() => parseNgaJson('')).toThrowError(/响应为空/)
  })

  it('真实抓包：通知接口拿到 data', () => {
    const envelope = parseNgaJson(fixtureText('notiEmpty'))
    expect(envelope.data).toEqual({ '0': '' })
  })

  it('真实抓包：用户资料', () => {
    const envelope = parseNgaJson(fixtureText('ucpUser'))
    const user = (envelope.data as Record<string, Record<string, unknown>>)['0']!
    expect(user.uid).toBe(41417929)
    expect(user.username).toBe('BugenZhao')
  })

  it('真实抓包：主题列表（GBK + 未声明 charset 的那条）', () => {
    const envelope = parseNgaJson(fixtureText('threadList'))
    const data = envelope.data as Record<string, Record<string, Record<string, unknown>>>
    expect(data.__F!.name).toBe('原神')
    expect(Object.keys(data.__T!).length).toBeGreaterThan(10)
  })

  it('真实抓包：read.php 的 lite=js 前缀被剥掉后能解析', () => {
    const envelope = parseNgaJson(fixtureText('readThread'))
    const data = envelope.data as Record<string, unknown>
    expect(data.__R).toBeDefined()
  })
})
