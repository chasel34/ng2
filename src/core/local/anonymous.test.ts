import { describe, expect, it } from 'vitest'

import { decodeAnonymousName, resolveAuthorName } from './anonymous'

/**
 * 期望值全部来自 NGA 官方前端 `commonui.anonyName`（js_commonui.js）——
 * 把线上那段函数原样跑一遍取到的输出，不是照着本实现算出来的。
 */
describe('decodeAnonymousName', () => {
  it('把 #anony_<hex> 还原成六字假名与两个色值', () => {
    expect(decodeAnonymousName('#anony_0123456789abcdef0123456789abcdef')).toEqual({
      name: '甲高傅庚聂涂',
      colors: ['bcdef0', '123456'],
    })
  })

  it('全 0 的 hex 落在两张表的第一个字上', () => {
    expect(decodeAnonymousName('#anony_00000000000000000000000000000000')).toEqual({
      name: '甲王王甲王王',
      colors: ['000000', '000000'],
    })
  })

  it('另一组随机 hex 与官方实现逐字一致', () => {
    expect(decodeAnonymousName('#anony_a3f01c7b92d4e6580a1b2c3d4e5f6071')).toEqual({
      name: '子戴李辛时程',
      colors: ['4e6580', 'a1b2c3'],
    })
    expect(decodeAnonymousName('#anony_1e2d3c4b5a69788796a5b4c3d2e1f0aa')).toEqual({
      name: '乙卞席戊牟辛',
      colors: ['978879', '6a5b4c'],
    })
  })

  it('百家姓表只有 255 字，下标 0xff 官方就是取空——照抄这个短名', () => {
    expect(decodeAnonymousName('#anony_ffffffffffffffffffffffffffffffff')?.name).toBe('巳巳')
  })

  /**
   * 另一路对拍:这串 hex 是 `thread.php?fid=-7` 真实响应里的匿名作者
   * (见 core/api/__fixtures__/thread-list-fid-7.gbk.bin,主题「技师请我吃饭(续)」)。
   *
   * 设计稿 design/project/NGA客户端.dc.html:1124 的主题列表 mock 里,恰好有个作者叫「卯邱潘」——
   * 那是设计师照着真机上同一个版块转录的,而且它在**本实现存在之前**就随初始提交进了仓库。
   * 换句话说:一边是官方 JS 的算法,一边是官方 App 渲染出来、由第三方转录的结果,两边对上了。
   */
  it('真实 hex 的解码结果与设计稿从真机转录的假名对得上', () => {
    const decoded = decodeAnonymousName('#anony_d43225f5a338ca2efea68a14773537e6')
    expect(decoded?.name).toBe('卯邱潘巳邵卢')
    expect(decoded?.name.slice(0, 3)).toBe('卯邱潘')
  })

  it('不是匿名串就还不出来', () => {
    expect(decodeAnonymousName('春曰影')).toBeUndefined()
    expect(decodeAnonymousName('#anony_0123')).toBeUndefined()
    // 官方判定用的正则只认 32 位小写 hex
    expect(decodeAnonymousName('#anony_0123456789ABCDEF0123456789ABCDEF')).toBeUndefined()
    expect(decodeAnonymousName('#ANONYMOUS#')).toBeUndefined()
  })
})

describe('resolveAuthorName', () => {
  it('匿名作者显示还原后的假名', () => {
    expect(resolveAuthorName('#anony_00000000000000000000000000000000')).toBe('甲王王甲王王')
  })

  it('普通作者原样显示', () => {
    expect(resolveAuthorName('春曰影')).toBe('春曰影')
  })
})
