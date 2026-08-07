import { describe, expect, it } from 'vitest'

import { unescapeNgaText } from './entities'

describe('unescapeNgaText', () => {
  it('解码常见命名实体', () => {
    expect(unescapeNgaText('a &lt;b&gt; &quot;c&quot;')).toBe('a <b> "c"')
  })

  it('解码十进制与十六进制数字实体', () => {
    expect(unescapeNgaText('&#65;&#x42;&#20320;')).toBe('AB你')
  })

  it('把成对的 UTF-16 代理对实体还原为星平面字符', () => {
    expect(unescapeNgaText('&#55357;&#56836;')).toBe('😄')
  })

  it('对双重转义的 emoji 跑两轮解码', () => {
    expect(unescapeNgaText('&amp;#55357;&amp;#56836;')).toBe('😄')
  })

  it('还原 ZWJ 家庭 emoji 与变体选择符', () => {
    const escaped =
      'A&#55357;&#56834;B&#10084;&#65039;C' +
      '&#55357;&#56424;&#8205;&#55357;&#56425;&#8205;&#55357;&#56423;&#8205;&#55357;&#56422;'
    expect(unescapeNgaText(escaped)).toBe('A😂B❤️C👨‍👩‍👧‍👦')
  })

  it('&nbsp; 解成不间断空格,保住 NGA 的排版空白', () => {
    expect(unescapeNgaText('a&nbsp;&nbsp;b')).toBe('a\u00a0\u00a0b')
  })

  it('原样保留无法识别的实体', () => {
    expect(unescapeNgaText('&zzz; &#; 100&50')).toBe('&zzz; &#; 100&50')
  })

  it('把落单的代理码元换成替换字符,避免下游拿到非法字符串', () => {
    const decoded = unescapeNgaText('a&#55357;b')
    expect(decoded).toBe('a�b')
  })
})
