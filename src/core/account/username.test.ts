import { describe, expect, it } from 'vitest'
import { gbkEncodeURIComponent } from '../net/encoding/gb18030'
import { decodeLoginUsername } from './username'

/** 服务端的编法:URLEncode(URLEncode(name, GBK), GBK)。外层输入已是 ASCII,等价于 encodeURIComponent。 */
function doubleEncode(name: string): string {
  return encodeURIComponent(gbkEncodeURIComponent(name))
}

describe('decodeLoginUsername · GBK 双重 URLDecode(API 文档 §0.2)', () => {
  it('中文用户名:硬编码向量(阴阳师妄想 的 GBK 双重编码)', () => {
    // 阴=D2F5 阳=D1F4 师=CAA6 妄=CDFD 想=CFEB → 内层 %D2%F5… → 外层 % 再转 %25
    expect(
      decodeLoginUsername('%25D2%25F5%25D1%25F4%25CA%25A6%25CD%25FD%25CF%25EB'),
    ).toBe('阴阳师妄想')
  })

  it('与本仓库 GBK 编码器对拍:中文/混排/带数字', () => {
    for (const name of ['阴阳师妄想', 'chasel43', '猫猫头MK2', '天使动漫']) {
      expect(decodeLoginUsername(doubleEncode(name))).toBe(name)
    }
  })

  it('兼容 Java URLEncoder 的习惯:小写十六进制与 + 号空格', () => {
    expect(decodeLoginUsername('%25d2%25f5')).toBe('阴')
    // 名字带空格:内层空格→+,外层 + 是安全字符原样保留
    expect(decodeLoginUsername('a+b')).toBe('a b')
  })

  it('畸形输入返回 null,调用方回落 UID 展示', () => {
    expect(decodeLoginUsername('%2')).toBeNull() // 孤立的 %
    expect(decodeLoginUsername('%25ZZ')).toBeNull() // 第二层孤立的 %
    expect(decodeLoginUsername('')).toBeNull() // 空串
    expect(decodeLoginUsername('%2581')).toBeNull() // 孤立的 GBK 前导字节 → 替换字符
    expect(decodeLoginUsername('名字')).toBeNull() // 根本不是 URL 编码产物
  })
})
