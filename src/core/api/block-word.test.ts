import { describe, expect, it, vi } from 'vitest'

import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import {
  blockWordError,
  fetchBlockWords,
  officialFilterRules,
  parseBlockWords,
  serializeBlockWords,
  setBlockWords,
} from './block-word'

function jsonTransport(body: string) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: 'text/javascript; charset=UTF-8',
      body: new TextEncoder().encode(body),
    }
  })
  return { transport, requests }
}

/** 真实抓包形状（API 文档 §11.5）：data["0"] 是一段多行纯文本。 */
const SAMPLE = '1\\r\\n加密货币 私聊出\\r\\n42/gerraerd 907/Apprivorisor'

describe('parseBlockWords', () => {
  it('第 2 行是关键词、第 3 行是 uid/用户名 对', () => {
    expect(parseBlockWords({ '0': '1\r\n加密货币 私聊出\r\n42/gerraerd 907/Apprivorisor' })).toEqual({
      words: ['加密货币', '私聊出'],
      users: [
        { uid: 42, name: 'gerraerd' },
        { uid: 907, name: 'Apprivorisor' },
      ],
    })
  })

  it('从没设置过屏蔽词：行数不够、data 不是字符串，都折成空表而不是报错', () => {
    expect(parseBlockWords({ '0': '1' })).toEqual({ words: [], users: [] })
    expect(parseBlockWords({ '0': '' })).toEqual({ words: [], users: [] })
    expect(parseBlockWords({})).toEqual({ words: [], users: [] })
    expect(parseBlockWords(undefined)).toEqual({ words: [], users: [] })
  })

  it('用户项缺 uid 或名字里带斜杠都不至于解坏', () => {
    expect(parseBlockWords({ '0': '1\r\n\r\n某人 42/a/b' }).users).toEqual([
      { name: '某人' },
      { uid: 42, name: 'a/b' },
    ])
  })

  it('\\n 换行的响应也认（服务端换行符不保证是 \\r\\n）', () => {
    expect(parseBlockWords({ '0': '1\n关键词\n42/某人' })).toEqual({
      words: ['关键词'],
      users: [{ uid: 42, name: '某人' }],
    })
  })
})

describe('serializeBlockWords', () => {
  it('拼成 1\\r\\n<词>\\r\\n<用户>，词与用户各自空格分隔', () => {
    expect(
      serializeBlockWords({
        words: ['加密货币', '私聊出'],
        users: [{ uid: 42, name: 'gerraerd' }, { name: '无 uid 的老数据' }],
      }),
    ).toBe('1\r\n加密货币 私聊出\r\n42/gerraerd 无 uid 的老数据')
  })

  it('空表也保留三行结构：清空屏蔽词靠的就是写一张空表', () => {
    expect(serializeBlockWords({ words: [], users: [] })).toBe('1\r\n\r\n')
  })

  it('解出来再拼回去与原文一致（网页版写的表，我们改一条也不会破坏其余部分）', () => {
    const raw = '1\r\n加密货币 私聊出\r\n42/gerraerd 907/Apprivorisor'
    expect(serializeBlockWords(parseBlockWords({ '0': raw }))).toBe(raw)
  })
})

describe('fetchBlockWords', () => {
  it('打 nuke.php?__lib=ucp&__act=get_block_word，带 uid 与必需的 Referer', async () => {
    const { transport, requests } = jsonTransport(`{"data":{"0":"${SAMPLE}"}}`)

    const list = await fetchBlockWords(createNgaFetcher({ transport }), { uid: 42 })

    expect(requests[0]?.method).toBe('POST')
    expect(requests[0]?.url).toContain('__lib=ucp')
    expect(requests[0]?.url).toContain('__act=get_block_word')
    expect(requests[0]?.url).toContain('uid=42')
    // Referer 必须以当前 host 开头且带 uid（API 文档 §11.5）
    expect(requests[0]?.headers.Referer).toBe('https://bbs.nga.cn/nuke.php?func=ucp&uid=42')
    expect(list.words).toEqual(['加密货币', '私聊出'])
  })
})

describe('setBlockWords', () => {
  it('data 按 GBK percent-encode 进 query，且不再声明 __inchst=UTF8', async () => {
    const { transport, requests } = jsonTransport('{"data":{"0":"操作成功"}}')

    await setBlockWords(createNgaFetcher({ transport }), {
      uid: 42,
      list: { words: ['加密货币', '测试'], users: [{ uid: 42, name: '张三' }] },
    })

    const url = requests[0]?.url ?? ''
    expect(url).toContain('__act=set_block_word')
    // `1\r\n加密货币 测试\r\n42/张三` 的 GBK 编码；这一串对不上，网页版看到的就是乱码
    expect(url).toContain(
      'data=1%0D%0A%BC%D3%C3%DC%BB%F5%B1%D2%20%B2%E2%CA%D4%0D%0A42%2F%D5%C5%C8%FD',
    )
    // 带 GBK 参数时必须撤掉 UTF8 声明，否则服务端按 UTF-8 解这串字节
    expect(url).not.toContain('__inchst')
    expect(requests[0]?.headers.Referer).toBe('https://bbs.nga.cn/nuke.php?func=ucp&uid=42')
  })

  it('清空屏蔽表写的是空串的两行，不是「不传 data」', async () => {
    const { transport, requests } = jsonTransport('{"data":{"0":"操作成功"}}')

    await setBlockWords(createNgaFetcher({ transport }), {
      uid: 42,
      list: { words: [], users: [] },
    })

    expect(requests[0]?.url).toContain('data=1%0D%0A%0D%0A')
  })
})

describe('officialFilterRules', () => {
  it('云端表折成匹配器认的规则：用户在前、关键词一律非正则', () => {
    const rules = officialFilterRules({
      words: ['^\\[水\\]'],
      users: [{ uid: 42, name: 'gerraerd' }],
    })

    expect(rules.map((item) => item.kind)).toEqual(['user', 'keyword'])
    expect(rules.every((item) => item.origin === 'official')).toBe(true)
    expect(rules[0]?.uid).toBe(42)
    expect(rules[1]?.regex).toBe(false)
  })
})

describe('blockWordError', () => {
  it('空串与带空白的词都拦下（空格是表里的分隔符）', () => {
    expect(blockWordError('  ')).toBe('请输入要屏蔽的关键词')
    expect(blockWordError('内部 消息')).toContain('不能有空格')
    expect(blockWordError('内部消息')).toBeUndefined()
    expect(blockWordError('', '用户名')).toBe('请输入要屏蔽的用户名')
  })
})
