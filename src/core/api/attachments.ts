/**
 * 附件图片地址拼装（API 文档 §0.1、§3）。
 *
 * 两件事在这里合流：
 *
 * 1. **域名不写死**。`read.php` 每次都在 `__GLOBAL._ATTACH_BASE_VIEW` 里带当前的附件基址，
 *    NGA 换过好几次域名（`img.nga.178.com` / `ngacn.cc` / `img.nga.cn`），写死就等着失效。
 * 2. **相对路径的两种形态**。`[img]./mon_202608/07/x.jpg[/img]` 自带日期目录，
 *    而 `[noimg]./-7Qd36d-….jpg[/noimg]` 没有——后者要按发帖时间补 `mon_YYYYMM/DD/`
 *    才能取到图（实测缺前缀的地址是 404）。
 */

import type { AttachmentRef } from '../bbcode'

/**
 * 响应里没有 `_ATTACH_BASE_VIEW` 时的兜底基址。
 *
 * 只在字段缺失（被封、Web 反解、旧缓存）时用得上：宁可拿一个可能过期的域名去试，
 * 也好过整楼图片全渲染不出来。正常路径一律用响应给的值。
 */
export const ATTACH_BASE_FALLBACK = 'img.nga.cn/attachments'

/** 缩略图后缀（旧 Android 客户端 `ForumImageDecoder` 的同一张表）。 */
const THUMBNAIL_SUFFIXES = ['.thumb_ss.jpg', '.thumb_s.jpg', '.thumb.jpg', '.medium.jpg'] as const

/** 服务端给的缩略图后缀，展示时要剥掉换回原图。 */
export const THUMBNAIL_SUFFIX = '.thumb.jpg'

/** NGA 的日期目录按论坛所在时区（UTC+8）分，不能跟着设备时区走。 */
const NGA_UTC_OFFSET_MS = 8 * 60 * 60 * 1000

/** 已经带日期目录的相对路径，例如 `mon_202608/07/x.jpg`。 */
const DATED_PATH_PATTERN = /^mon_\d{6}\//

/**
 * 把 `__GLOBAL._ATTACH_BASE_VIEW` 归一成能直接往后拼路径的基址。
 *
 * 服务端给的是不带协议的 `img.nga.cn/attachments`。这里**保留整段路径**，
 * 而不是像旧 Android 客户端那样只取 `split("/")[0]` 再拼死的 `/attachments`——
 * 那等于把路径换个地方硬编码。
 */
export function normalizeAttachBase(raw: unknown): string {
  const value = typeof raw === 'string' ? raw.trim() : ''
  const base = value === '' ? ATTACH_BASE_FALLBACK : value
  // RN 默认禁明文流量，服务端给 http 也要升到 https
  const withoutScheme = base.replace(/^https?:\/\//, '')
  return `https://${withoutScheme.replace(/\/+$/, '')}`
}

/** 剥掉结尾的缩略图后缀，还原原图路径；没有后缀时原样返回。 */
export function stripThumbnailSuffix(src: string): string {
  for (const suffix of THUMBNAIL_SUFFIXES) {
    if (src.endsWith(suffix)) return src.slice(0, -suffix.length)
  }
  return src
}

/** `mon_YYYYMM/DD`（UTC+8），`[noimg]` 的相对路径缺的就是这一段。 */
function datedDirectory(postedAt: number): string {
  const shifted = new Date(postedAt * 1000 + NGA_UTC_OFFSET_MS)
  const year = shifted.getUTCFullYear()
  const month = String(shifted.getUTCMonth() + 1).padStart(2, '0')
  const day = String(shifted.getUTCDate()).padStart(2, '0')
  return `mon_${year}${month}/${day}`
}

export interface AttachmentUrlOptions {
  /** `normalizeAttachBase` 的产物 */
  readonly base: string
  /** 所在楼层的发帖时间（秒级 unix），补日期目录用；没有就不猜 */
  readonly postedAt?: number
}

/** 把 AST 里的资源引用拼成能直接喂给 `<Image>` 的地址。 */
export function attachmentUrl(ref: AttachmentRef, options: AttachmentUrlOptions): string {
  if (!ref.needsAttachBase) return ref.src

  let path = stripThumbnailSuffix(ref.src).replace(/^\/+/, '')
  if (options.postedAt !== undefined && !DATED_PATH_PATTERN.test(path)) {
    path = `${datedDirectory(options.postedAt)}/${path}`
  }
  return `${options.base}/${path}`
}
