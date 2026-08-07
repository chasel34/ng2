import { describe, expect, it } from 'vitest'
import { buildFormBody, buildQueryString, gbk } from './query'

describe('buildQueryString', () => {
  it('拼常规参数', () => {
    expect(buildQueryString({ fid: 650, page: 1 })).toBe('fid=650&page=1')
  })

  it('剔除空值参数：null / undefined / 空串 / false', () => {
    // MNGA 全局行为，大量逻辑依赖它：bool false 编码成空串即「不传」、fid/stid 二选一
    expect(
      buildQueryString({ fid: 650, stid: null, key: '', content: false, page: undefined }),
    ).toBe('fid=650')
  })

  it('true 编码成 1', () => {
    expect(buildQueryString({ searchpost: true })).toBe('searchpost=1')
  })

  it('数字 0 保留（不是空值）', () => {
    expect(buildQueryString({ page: 0 })).toBe('page=0')
  })

  it('默认按 UTF-8 percent-encode', () => {
    // thread.php 的 key 是 UTF-8
    expect(buildQueryString({ key: '原神' })).toBe('key=%E5%8E%9F%E7%A5%9E')
  })

  it('gbk() 标记的值按 GBK 编码', () => {
    // 同一个 thread.php，author 却是 GBK（API 文档 §0.5）
    expect(buildQueryString({ author: gbk('原神') })).toBe('author=%D4%AD%C9%F1')
  })

  it('空的 gbk() 值同样被剔除', () => {
    expect(buildQueryString({ author: gbk(''), fid: 1 })).toBe('fid=1')
  })

  it('无参数时返回空串', () => {
    expect(buildQueryString({})).toBe('')
  })
})

describe('buildFormBody', () => {
  it('与 query 同规则', () => {
    expect(buildFormBody({ access_uid: '123', access_token: 'abc', extra: '' })).toBe(
      'access_uid=123&access_token=abc',
    )
  })
})
