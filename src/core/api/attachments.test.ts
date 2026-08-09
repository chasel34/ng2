import { describe, expect, it } from 'vitest'

import { childNodeLists, parseBBCode, type BBCodeNode, type ImageNode } from '../bbcode'
import { decodeResponseBody, parseNgaJson } from '../net'
import {
  ATTACH_BASE_FALLBACK,
  attachmentUrl,
  imageFileName,
  imageMimeType,
  normalizeAttachBase,
  stripThumbnailSuffix,
  thumbnailUrl,
} from './attachments'
import { fixtureContentType, readFixtureBytes } from './__fixtures__'
import { parseTopicDetail } from './topic-detail'

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

  it('站外的绝对地址原样返回', () => {
    const src = 'https://i.imgur.example/attachments/x.jpg'
    expect(attachmentUrl({ src, needsAttachBase: false }, { base })).toBe(src)
  })

  it('老域名写死的附件地址重挂到响应给的基址', () => {
    // 2026-08-08 抓的版头帖（fid=-7）正文里的原话：178 那个域名已经连不上了
    expect(
      attachmentUrl(
        {
          src: 'https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png',
          needsAttachBase: false,
        },
        { base },
      ),
    ).toBe('https://img.nga.cn/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png')

    // 目标域名仍然只从响应来：换个基址就跟着换
    expect(
      attachmentUrl(
        { src: 'http://imgs.ngacn.cc/attachments/mon_201903/26/x.jpg', needsAttachBase: false },
        { base: 'https://img.example.test/att' },
      ),
    ).toBe('https://img.example.test/att/mon_201903/26/x.jpg')
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

/** AST 里所有图片节点，按出现顺序。 */
function imageNodes(nodes: readonly BBCodeNode[]): ImageNode[] {
  const found: ImageNode[] = []
  for (const node of nodes) {
    if (node.type === 'image') found.push(node)
    for (const children of childNodeLists(node)) found.push(...imageNodes(children))
  }
  return found
}

describe('thumbnailUrl（22 票的图片加载策略）', () => {
  const base = 'https://img.nga.cn/attachments'

  it('挂在附件基址下的图换成 .thumb.jpg', () => {
    expect(thumbnailUrl(`${base}/mon_202608/07/x.png`, base)).toBe(
      `${base}/mon_202608/07/x.png.thumb.jpg`,
    )
  })

  it('已经是缩略图时不重复加后缀', () => {
    const thumb = `${base}/mon_202608/07/x.png.thumb.jpg`
    expect(thumbnailUrl(thumb, base)).toBe(thumb)
    expect(thumbnailUrl(`${base}/mon_202608/07/x.png.medium.jpg`, base)).toBe(thumb)
  })

  it('站外图床原样返回——那些地址没有这套后缀约定', () => {
    const outside = 'https://i.imgur.com/abc.png'
    expect(thumbnailUrl(outside, base)).toBe(outside)
  })
})

describe('版头 0 楼那张图（真实样本，M2 遗留缺陷 2）', () => {
  const envelope = parseNgaJson(
    decodeResponseBody(readFixtureBytes('readBoardHead'), fixtureContentType('readBoardHead')),
  )
  const detail = parseTopicDetail(envelope.data, { context: 'ctx' })

  it('从响应到最终地址走一遍：老域名换成 _ATTACH_BASE_VIEW 给的那个', () => {
    expect(detail.attachBase).toBe('https://img.nga.cn/attachments')

    const main = detail.floors.find((floor) => floor.lou === 0)
    expect(main).toBeDefined()
    const images = imageNodes(parseBBCode(main!.content))
    // 正文里唯一一张图，写的是绝对地址而不是 ./ 相对路径
    expect(images).toHaveLength(1)
    expect(images[0]?.needsAttachBase).toBe(false)
    expect(images[0]?.src).toBe(
      'https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png',
    )

    expect(
      attachmentUrl(images[0]!, { base: detail.attachBase, postedAt: main!.postedAt }),
    ).toBe('https://img.nga.cn/attachments/mon_202006/03/-914q0Q5-7r39K17T1kSdr-4w.png')
  })
})

describe('imageFileName', () => {
  it('取路径最后一段并去掉查询串', () => {
    expect(
      imageFileName('https://img.nga.cn/attachments/mon_202608/07/-7Qd36d-abcK2fT3cSu0-qo.jpg?x=1#f'),
    ).toBe('-7Qd36d-abcK2fT3cSu0-qo.jpg')
  })

  it('剥掉缩略图后缀——存的是原图，名字不该带 .thumb', () => {
    expect(imageFileName('https://img.nga.cn/attachments/mon_202608/07/a.jpg.thumb.jpg')).toBe('a.jpg')
    expect(imageFileName('https://img.nga.cn/attachments/mon_202608/07/a.jpg.medium.jpg')).toBe('a.jpg')
  })

  it('没有认得出的图片扩展名时补 .jpg', () => {
    expect(imageFileName('https://example.com/image/12345')).toBe('12345.jpg')
    expect(imageFileName('https://example.com/a.php')).toBe('a.php.jpg')
  })

  it('替换文件系统不认的字符', () => {
    expect(imageFileName('https://example.com/a%20b.png')).toBe('a_b.png')
    expect(imageFileName('https://example.com/a"b|c.png')).toBe('a_b_c.png')
  })

  it('整段路径都没有名字时用短哈希兜底', () => {
    const name = imageFileName('https://example.com/')
    expect(name).toMatch(/^image-[0-9a-z]+\.jpg$/)
    // 同一地址两次要得到同一个名字（缓存中转靠它幂等）
    expect(imageFileName('https://example.com/')).toBe(name)
  })
})

describe('imageMimeType', () => {
  it('按扩展名给 MIME，大小写不敏感', () => {
    expect(imageMimeType('a.PNG')).toBe('image/png')
    expect(imageMimeType('a.webp')).toBe('image/webp')
    expect(imageMimeType('a.gif')).toBe('image/gif')
  })

  it('认不出扩展名时按 jpeg 兜底', () => {
    expect(imageMimeType('a.bin')).toBe('image/jpeg')
    expect(imageMimeType('noext')).toBe('image/jpeg')
  })
})
