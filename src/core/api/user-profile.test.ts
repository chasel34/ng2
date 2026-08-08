import { describe, expect, it, vi } from 'vitest'

import { decodeGb18030 } from '../net'
import { createNgaFetcher } from '../net/fetcher'
import type { HttpRequest, HttpResponse } from '../net/transport'
import { fixtureContentType, readFixtureBytes, type ApiFixtureName } from './__fixtures__'
import { fetchUserAvatar, fetchUserProfile, parseUserProfile } from './user-profile'

/** 用真实抓包字节应答的假传输层，顺带把请求录下来。 */
function fixtureTransport(name: ApiFixtureName, body?: string) {
  const requests: HttpRequest[] = []
  const transport = vi.fn(async (request: HttpRequest): Promise<HttpResponse> => {
    requests.push(request)
    return {
      status: 200,
      contentType: fixtureContentType(name),
      body: body === undefined ? readFixtureBytes(name) : new TextEncoder().encode(body),
    }
  })
  return { transport, requests }
}

const dataOf = (name: ApiFixtureName): unknown =>
  JSON.parse(decodeGb18030(readFixtureBytes(name))).data

describe('parseUserProfile（真实样本）', () => {
  const profile = parseUserProfile(dataOf('ucpUser'), { nowSeconds: 1786173048 })

  it('解出资料页 banner 与基础信息要用的那几项', () => {
    expect(profile?.uid).toBe(41417929)
    expect(profile?.name).toBe('BugenZhao')
    expect(profile?.group).toBe('学徒')
    expect(profile?.postCount).toBe(2277)
    expect(profile?.registeredAt).toBe(1495931441)
    expect(profile?.ipLocation).toBe('浙江省')
  })

  it('威望按 rvrc ÷ 10 换算', () => {
    // 样本里 rvrc / fame 都是 15
    expect(profile?.reputation).toBe(1.5)
  })

  it('头像与签名原样带出来（签名是 BBCode，渲染归 UI）', () => {
    expect(profile?.avatarUrl).toBe('https://img.nga.cn/avatars/2002/cc9/77f/002/41417929_0.jpg?57')
    expect(profile?.signature).toContain('[url]http://apple.co/3RwvgIe[/url]')
  })

  it('没给的字段就是没有，不拿 0 和空串冒充', () => {
    // 实测只有查自己时服务端才给 email / phone
    expect(profile?.email).toBeUndefined()
    expect(profile?.phone).toBeUndefined()
    expect(profile?.adminForums).toEqual([])
    expect(profile?.reputations).toEqual([])
  })

  it('没被封也没禁言时状态是已激活', () => {
    expect(profile?.status).toBe('active')
    expect(profile?.mutedUntil).toBeUndefined()
  })
})

describe('parseUserProfile（管理员样本）', () => {
  const profile = parseUserProfile(dataOf('ucpAdmin'), { nowSeconds: 1786173082 })

  it('adminForums 解成版面职务列表，fid 可以是负数', () => {
    expect(profile?.adminForums).toEqual([{ fid: -2, name: '阿瓦隆177号' }])
  })

  it('负威望照除不误', () => {
    expect(profile?.reputation).toBeCloseTo(-1110.9)
  })
})

describe('parseUserProfile（构造样本）', () => {
  const parse = (user: Record<string, unknown>, nowSeconds = 1_000_000) =>
    parseUserProfile({ 0: { uid: 1, username: '张三', ...user } }, { nowSeconds })

  it('data 里没有 user 时返回 undefined，判空交给调用方', () => {
    expect(parseUserProfile({})).toBeUndefined()
    expect(parseUserProfile({ 0: '' })).toBeUndefined()
    expect(parseUserProfile(undefined)).toBeUndefined()
    // uid 解不出来的记录也不算一份资料
    expect(parseUserProfile({ 0: { username: '张三' } })).toBeUndefined()
  })

  it('verified/yz 为 -1 = 被 nuke', () => {
    expect(parse({ verified: -1 })?.status).toBe('nuked')
    expect(parse({ yz: -1 })?.status).toBe('nuked')
    // 别的负值是另外的状态，不算封禁
    expect(parse({ yz: -5 })?.status).toBe('active')
  })

  it('muteTime 还没到期才算禁言', () => {
    expect(parse({ muteTime: 2_000_000 })?.status).toBe('muted')
    expect(parse({ muteTime: 2_000_000 })?.mutedUntil).toBe(2_000_000)
    // 过了期的禁言不再标注
    expect(parse({ muteTime: 999_999 })?.status).toBe('active')
    expect(parse({ muteTime: 0 })?.status).toBe('active')
  })

  it('被 nuke 压过禁言：已经封了就别再标禁言', () => {
    expect(parse({ verified: -1, muteTime: 2_000_000 })?.status).toBe('nuked')
  })

  it('「尚无记录」是服务端占位，不当成属地', () => {
    expect(parse({ ipLoc: '尚无记录' })?.ipLocation).toBeUndefined()
    expect(parse({ ipLoc: '河北省' })?.ipLocation).toBe('河北省')
  })

  it('声望两种形状都收，只有 fid 时退回 `版面 <fid>`', () => {
    expect(parse({ reputation: { '-7': 42, '650': -3 } })?.reputations).toEqual([
      { fid: -7, name: '版面 -7', value: 42 },
      { fid: 650, name: '版面 650', value: -3 },
    ])
    expect(parse({ reputation: { '650': { name: '原神', value: 12 } } })?.reputations).toEqual([
      { fid: 650, name: '原神', value: 12 },
    ])
  })

  it('注册时间为 0（老账号服务端没记）时不显示', () => {
    expect(parse({ regdate: 0 })?.registeredAt).toBeUndefined()
  })
})

describe('fetchUserProfile', () => {
  it('必带 Referer，且以当前 host 开头（否则服务端拒绝）', async () => {
    const { transport, requests } = fixtureTransport('ucpUser')

    await fetchUserProfile(createNgaFetcher({ transport }), { uid: 41417929 })

    expect(requests[0]?.headers.Referer).toBe('https://bbs.nga.cn/nuke.php?func=ucp')
  })

  it('Referer 跟着换域名走，不写死', async () => {
    const { transport, requests } = fixtureTransport('ucpUser')

    await fetchUserProfile(createNgaFetcher({ transport, host: 'https://ngabbs.com' }), {
      uid: 41417929,
    })

    expect(requests[0]?.headers.Referer).toBe('https://ngabbs.com/nuke.php?func=ucp')
  })

  it('打的是 ucp get，uid 原样带上', async () => {
    const { transport, requests } = fixtureTransport('ucpUser')

    await fetchUserProfile(createNgaFetcher({ transport }), { uid: 41417929 })

    const url = requests[0]?.url ?? ''
    expect(url).toContain('/nuke.php?')
    expect(url).toContain('__lib=ucp')
    expect(url).toContain('__act=get')
    expect(url).toContain('uid=41417929')
  })

  it('GBK 响应一路解码、清洗、解析成一份资料', async () => {
    const { transport } = fixtureTransport('ucpUser')

    const profile = await fetchUserProfile(createNgaFetcher({ transport }), { uid: 41417929 })

    expect(profile.name).toBe('BugenZhao')
    expect(profile.reputation).toBe(1.5)
  })

  it('「找不到用户」在假错误白名单里，要靠 data 为空认出来并报原话', async () => {
    const { transport } = fixtureTransport('ucpMissing')

    await expect(
      fetchUserProfile(createNgaFetcher({ transport }), { uid: 999999999 }),
    ).rejects.toThrow('找不到用户')
  })

  it('data 在、user 是空的：和「查无此人」分开报，排障时看得出差别', async () => {
    const { transport } = fixtureTransport('ucpUser', '{"data":{},"time":1}')

    await expect(
      fetchUserProfile(createNgaFetcher({ transport }), { uid: 41417929 }),
    ).rejects.toThrow('资料响应里没有用户')
  })

  it('传得进 AbortSignal', async () => {
    const { transport, requests } = fixtureTransport('ucpUser')
    const controller = new AbortController()

    await fetchUserProfile(createNgaFetcher({ transport }), {
      uid: 41417929,
      signal: controller.signal,
    })

    expect(requests[0]?.signal).toBe(controller.signal)
  })
})

describe('fetchUserAvatar', () => {
  it('头像补充查询：URL 直接躺在 data["0"] 上', async () => {
    const { transport, requests } = fixtureTransport('ucpAvatar')

    const url = await fetchUserAvatar(createNgaFetcher({ transport }), { uid: 41417929 })

    expect(url).toBe('https://img.nga.cn/avatars/2002/cc9/77f/002/41417929_0.jpg?57')
    expect(requests[0]?.url).toContain('__act=get_avatar')
    expect(requests[0]?.headers.Referer).toBe('https://bbs.nga.cn/nuke.php?func=ucp')
  })

  it('拿不到就是 undefined——UI 还有首字占位兜底，不该为一张头像报错', async () => {
    const { transport } = fixtureTransport('ucpAvatar', '{"data":{"0":""},"time":1}')

    await expect(
      fetchUserAvatar(createNgaFetcher({ transport }), { uid: 1 }),
    ).resolves.toBeUndefined()
  })
})
