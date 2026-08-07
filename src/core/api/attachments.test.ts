import { describe, expect, it } from 'vitest'

import {
  ATTACH_BASE_FALLBACK,
  attachmentUrl,
  normalizeAttachBase,
  stripThumbnailSuffix,
} from './attachments'

/** 2025-05-26 17:33 (UTC+8)，取自 read-comment-noimg fixture 的第 3 楼。 */
const POSTED_AT = 1748252025

describe('normalizeAttachBase', () => {
  it('把 _ATTACH_BASE_VIEW 的裸域名补成 https 基址', () => {
    expect(normalizeAttachBase('img.nga.cn/attachments')).toBe('https://img.nga.cn/attachments')
  })

  it('已带协议的原样用，末尾斜杠去掉', () => {
    expect(normalizeAttachBase('https://img.nga.cn/attachments/')).toBe(
      'https://img.nga.cn/attachments',
    )
    // 服务端给 http 时升到 https：RN 默认禁明文流量
    expect(normalizeAttachBase('http://img.nga.cn/attachments')).toBe(
      'https://img.nga.cn/attachments',
    )
  })

  it('缺字段时退到兜底基址', () => {
    expect(normalizeAttachBase(undefined)).toBe(`https://${ATTACH_BASE_FALLBACK}`)
    expect(normalizeAttachBase('')).toBe(`https://${ATTACH_BASE_FALLBACK}`)
    expect(normalizeAttachBase(42)).toBe(`https://${ATTACH_BASE_FALLBACK}`)
  })
})

describe('stripThumbnailSuffix', () => {
  it('剥掉四种缩略图后缀，还原原图地址', () => {
    for (const suffix of ['.thumb.jpg', '.thumb_s.jpg', '.thumb_ss.jpg', '.medium.jpg']) {
      expect(stripThumbnailSuffix(`mon_202607/21/a.jpg${suffix}`)).toBe('mon_202607/21/a.jpg')
    }
  })

  it('只剥结尾那一层，不动正常文件名', () => {
    expect(stripThumbnailSuffix('mon_202607/21/a.jpg')).toBe('mon_202607/21/a.jpg')
    expect(stripThumbnailSuffix('mon_202607/21/thumb.jpg')).toBe('mon_202607/21/thumb.jpg')
  })
})

describe('attachmentUrl', () => {
  const base = 'https://img.nga.cn/attachments'

  it('绝对地址原样返回', () => {
    const src = 'https://img.nga.178.com/attachments/mon_201903/26/x.jpg'
    expect(attachmentUrl({ src, needsAttachBase: false }, { base })).toBe(src)
  })

  it('相对路径拼上响应给的基址，不硬编码域名', () => {
    expect(
      attachmentUrl({ src: 'mon_202607/21/a.jpg', needsAttachBase: true }, { base }),
    ).toBe('https://img.nga.cn/attachments/mon_202607/21/a.jpg')
    // 换一个基址就该换域名——这条是「附件域名动态获取」的回归锁
    expect(
      attachmentUrl(
        { src: 'mon_202607/21/a.jpg', needsAttachBase: true },
        { base: 'https://img.example.test/att' },
      ),
    ).toBe('https://img.example.test/att/mon_202607/21/a.jpg')
  })

  it('相对路径里的缩略图后缀要剥掉', () => {
    expect(
      attachmentUrl({ src: 'mon_202607/21/a.jpg.medium.jpg', needsAttachBase: true }, { base }),
    ).toBe('https://img.nga.cn/attachments/mon_202607/21/a.jpg')
  })

  it('[noimg] 那种没有 mon_ 路径的，按发帖时间补 mon_YYYYMM/DD/ 前缀', () => {
    expect(
      attachmentUrl(
        { src: '-7Qd36d-8aydZbT1kShs-13i.jpg', needsAttachBase: true },
        { base, postedAt: POSTED_AT },
      ),
    ).toBe('https://img.nga.cn/attachments/mon_202505/26/-7Qd36d-8aydZbT1kShs-13i.jpg')
  })

  it('日期前缀按 UTC+8 算，不跟运行环境的时区走', () => {
    // 1748252025 = 2025-05-26 17:33 (UTC+8) = 2025-05-26 09:33 UTC；
    // 再取一个 UTC 已经跨天、北京时间还没跨天的点：2025-05-26 23:30 (UTC+8)
    const lateNight = Date.UTC(2025, 4, 26, 15, 30) / 1000
    expect(attachmentUrl({ src: 'a.jpg', needsAttachBase: true }, { base, postedAt: lateNight })).toBe(
      'https://img.nga.cn/attachments/mon_202505/26/a.jpg',
    )
  })

  it('已经带 mon_ 路径的不再补前缀', () => {
    expect(
      attachmentUrl(
        { src: 'mon_202607/21/a.jpg', needsAttachBase: true },
        { base, postedAt: POSTED_AT },
      ),
    ).toBe('https://img.nga.cn/attachments/mon_202607/21/a.jpg')
  })

  it('没有发帖时间就不猜日期，原样拼', () => {
    expect(attachmentUrl({ src: 'a.jpg', needsAttachBase: true }, { base })).toBe(
      'https://img.nga.cn/attachments/a.jpg',
    )
  })
})
