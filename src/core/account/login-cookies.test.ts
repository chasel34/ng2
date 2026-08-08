import { describe, expect, it } from 'vitest'
import { extractLoginCookies, parseCookieString } from './login-cookies'

// 形状仿真实值(uid 8 位数字、cid 40 位字母数字),内容是编的——真 cookie 不进提交
const UID = '67241234'
const CID = 'Xa0123456789abcdefABCDEF0123456789abcdef'

describe('parseCookieString', () => {
  it('解析分号分隔的键值对并去掉两侧空白', () => {
    const jar = parseCookieString(`a=1; b=2 ;c= 3`)
    expect(jar.get('a')).toBe('1')
    expect(jar.get('b')).toBe('2')
    expect(jar.get('c')).toBe('3')
  })

  it('值里再出现 = 时不截断', () => {
    expect(parseCookieString('token=abc=def').get('token')).toBe('abc=def')
  })

  it('无 = 的碎片与空键直接跳过', () => {
    const jar = parseCookieString('junk; =orphan; ok=1')
    expect(jar.size).toBe(1)
    expect(jar.get('ok')).toBe('1')
  })
})

describe('extractLoginCookies · 登录完成的判定(API 文档 §0.2)', () => {
  it('uid+cid 都齐才算登录成功,并顺手带上用户名 cookie', () => {
    const cookie = `ngaPassportUid=${UID}; ngaPassportCid=${CID}; ngaPassportUrlencodedUname=%25D2%25F5`
    expect(extractLoginCookies(cookie)).toEqual({
      uid: UID,
      cid: CID,
      urlencodedUname: '%25D2%25F5',
    })
  })

  it('用户名 cookie 缺失时给 null,不影响凭证识别', () => {
    expect(extractLoginCookies(`ngaPassportUid=${UID}; ngaPassportCid=${CID}`)).toEqual({
      uid: UID,
      cid: CID,
      urlencodedUname: null,
    })
  })

  it('登录前的占位值不算:uid=guest、cid 短垃圾串、只有其一', () => {
    expect(extractLoginCookies(`ngaPassportUid=guest; ngaPassportCid=${CID}`)).toBeNull()
    expect(extractLoginCookies(`ngaPassportUid=${UID}; ngaPassportCid=deleted`)).toBeNull()
    expect(extractLoginCookies(`ngaPassportUid=${UID}`)).toBeNull()
    expect(extractLoginCookies(`ngaPassportCid=${CID}`)).toBeNull()
    expect(extractLoginCookies('')).toBeNull()
  })

  it('无关 cookie 一大堆也能认出目标两枚(轮询期间页面会攒各种统计 cookie)', () => {
    const cookie = `lastvisit=1754600000; guestJs=1754600001; ngaPassportUid=${UID}; bbsmisccookies=%7B%7D; ngaPassportCid=${CID}`
    expect(extractLoginCookies(cookie)?.uid).toBe(UID)
  })
})
