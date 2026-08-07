import { describe, expect, it } from 'vitest'
import { buildAuthAttachment } from './auth'

const credentials = { uid: '10000001', token: 'fake-cid-token' }

describe('buildAuthAttachment · 两种等价认证方式（API 文档 §0.2）', () => {
  it('form 方式把凭证放 POST body（MNGA 的做法）', () => {
    expect(buildAuthAttachment('form', credentials)).toEqual({
      headers: {},
      form: { access_uid: '10000001', access_token: 'fake-cid-token' },
    })
  })

  it('cookie 方式把凭证放 Cookie 头（Android 的做法）', () => {
    expect(buildAuthAttachment('cookie', credentials)).toEqual({
      headers: { Cookie: 'ngaPassportUid=10000001; ngaPassportCid=fake-cid-token' },
      form: {},
    })
  })

  it('none 或无凭证时什么都不加（游客访问）', () => {
    expect(buildAuthAttachment('none', credentials)).toEqual({ headers: {}, form: {} })
    expect(buildAuthAttachment('cookie', null)).toEqual({ headers: {}, form: {} })
    expect(buildAuthAttachment('form', undefined)).toEqual({ headers: {}, form: {} })
  })

  it('凭证残缺时按游客处理', () => {
    expect(buildAuthAttachment('cookie', { uid: '1', token: '' })).toEqual({ headers: {}, form: {} })
    expect(buildAuthAttachment('form', { uid: '', token: 'x' })).toEqual({ headers: {}, form: {} })
  })
})
