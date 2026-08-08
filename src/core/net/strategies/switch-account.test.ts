import { describe, expect, it } from 'vitest'
import type { NgaCredentials } from '../auth'
import { createComboCache } from '../combo'
import { createNgaFetcher } from '../fetcher'
import type { HttpRequest, HttpResponse, HttpTransport } from '../transport'
import { createFormatRotationStrategy } from './format-rotation'
import { createSwitchAccountStrategy, nextCredentialsAfter } from './switch-account'

const utf8 = (text: string) => new TextEncoder().encode(text)

const OK = utf8('{"data":{"0":"ok"},"time":1}')
const BLOCKED = utf8('<html><body>403 Forbidden</body></html>')

const ALICE: NgaCredentials = { uid: '10000001', token: 'cid-a' }
const BOB: NgaCredentials = { uid: '10000002', token: 'cid-b' }

function fakeTransport(
  respond: (request: HttpRequest) => Partial<HttpResponse>,
): { transport: HttpTransport; requests: HttpRequest[] } {
  const requests: HttpRequest[] = []
  const transport: HttpTransport = (request) => {
    requests.push(request)
    const response = respond(request)
    return Promise.resolve({
      status: response.status ?? 200,
      contentType: response.contentType ?? 'text/javascript; charset=UTF-8',
      body: response.body ?? OK,
    })
  }
  return { transport, requests }
}

const uidOf = (request: HttpRequest) =>
  /ngaPassportUid=(\d+)/.exec(request.headers.Cookie ?? '')?.[1] ?? null

/** 只有 Bob 的 cookie 能拿到数据，Alice 一律被封。 */
const onlyBobWorks = () =>
  fakeTransport((request) =>
    uidOf(request) === BOB.uid
      ? { body: OK }
      : { status: 403, body: BLOCKED, contentType: 'text/html' },
  )

function chainFetcher(transport: HttpTransport, accounts: readonly NgaCredentials[]) {
  return createNgaFetcher({
    transport,
    comboCache: createComboCache(),
    getCredentials: () => accounts[0] ?? null,
    strategies: [
      createFormatRotationStrategy({ formats: ['json'], hosts: ['https://bbs.nga.cn'] }),
      createSwitchAccountStrategy({ listCredentials: () => accounts }),
    ],
  })
}

describe('nextCredentialsAfter', () => {
  it('取当前账号之后的下一个,循环', () => {
    expect(nextCredentialsAfter([ALICE, BOB], ALICE)).toEqual(BOB)
    expect(nextCredentialsAfter([ALICE, BOB], BOB)).toEqual(ALICE)
  })

  it('不足两个账号就没得换', () => {
    expect(nextCredentialsAfter([], null)).toBeNull()
    expect(nextCredentialsAfter([ALICE], ALICE)).toBeNull()
  })

  it('当前是游客(或刚退出登录)时从头一个开始', () => {
    expect(nextCredentialsAfter([ALICE, BOB], null)).toEqual(ALICE)
    expect(nextCredentialsAfter([ALICE, BOB], { uid: '999', token: 'x' })).toEqual(ALICE)
  })
})

describe('createSwitchAccountStrategy · 换账号重试', () => {
  it('多账号时取下一个账号的 cookie 重试,成了就用它的结果', async () => {
    const { transport, requests } = onlyBobWorks()

    const result = await chainFetcher(transport, [ALICE, BOB])({ path: 'thread.php' })

    expect(result.via).toBe('switch-account')
    expect(requests.map(uidOf)).toEqual([ALICE.uid, BOB.uid])
  })

  it('只有一个账号时这一档不启用,一次请求都不发', async () => {
    const { transport, requests } = onlyBobWorks()

    await expect(chainFetcher(transport, [ALICE])({ path: 'thread.php' })).rejects.toMatchObject({
      kind: 'parse',
    })

    // 只有 format-rotation 那一档发过请求,全是 Alice
    expect(requests.map(uidOf)).toEqual([ALICE.uid])
  })

  it('只试一次:换的那个账号也被封就交给链上后面的兜底,不再换第三个', async () => {
    const CAROL: NgaCredentials = { uid: '10000003', token: 'cid-c' }
    const { transport, requests } = fakeTransport(() => ({
      status: 403,
      body: BLOCKED,
      contentType: 'text/html',
    }))

    await expect(
      chainFetcher(transport, [ALICE, BOB, CAROL])({ path: 'thread.php' }),
    ).rejects.toMatchObject({ kind: 'parse' })

    expect(requests.map(uidOf)).toEqual([ALICE.uid, BOB.uid])
  })

  it('沿用缓存里的成功组合:这一档变的是身份,不是组合', async () => {
    const cache = createComboCache()
    cache.remember('thread.php', { format: 'jsonLite', host: 'https://ngabbs.com' })
    const { transport, requests } = onlyBobWorks()
    const fetchNga = createNgaFetcher({
      transport,
      comboCache: cache,
      getCredentials: () => ALICE,
      strategies: [createSwitchAccountStrategy({ listCredentials: () => [ALICE, BOB] })],
    })

    await fetchNga({ path: 'thread.php' })

    expect(requests).toHaveLength(1)
    expect(requests[0]!.url).toContain('https://ngabbs.com/thread.php')
    expect(requests[0]!.url).toContain('lite=js')
  })

  it('业务错误照样不重试:换个账号也还是同一个语义错误', async () => {
    const { transport, requests } = fakeTransport(() => ({
      body: utf8('{"error":{"0":"2048:找不到主题"}}'),
    }))

    await expect(
      chainFetcher(transport, [ALICE, BOB])({ path: 'read.php', query: { tid: 1 } }),
    ).rejects.toMatchObject({ kind: 'server' })

    expect(requests).toHaveLength(1)
  })
})
