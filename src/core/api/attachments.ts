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
 * 3. **老帖正文里写死的绝对地址要重挂**，见 `rehostLegacyAttachment`。
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

/**
 * 认「这是不是 NGA 自己的附件域名」。域名换过好几次，老域名的地址还留在老帖正文里。
 * 这张表只用来**认**，不用来拼——真正的目标基址仍然只从 `_ATTACH_BASE_VIEW` 来（ADR-0002）。
 */
const NGA_ATTACH_HOST = /(?:^|\.)(?:nga\.cn|ngacn\.cc|nga\.178\.com)$/i

/** 绝对地址里的 `<host>/attachments/<路径>`；协议相对（`//`）的写法也收。 */
const ABSOLUTE_ATTACHMENT = /^(?:https?:)?\/\/([^/]+)\/attachments\/(.+)$/i

/**
 * 把老帖正文里写死的附件地址重挂到当前附件域名。
 *
 * 版头这类多年不动的帖子里，图片是绝对地址而不是 `./` 相对路径，例如
 * `[img]https://img.nga.178.com/attachments/mon_202006/03/-914q0Q5-….png[/img]`。
 * `img.nga.178.com` 已经停了（TLS 握手直接失败，2026-08-08 实测），而同一条
 * `mon_202006/03/…` 路径挂在响应给的 `img.nga.cn/attachments` 下仍然是 200——
 * 所以只要地址落在 NGA 的 `/attachments/` 目录里，就换成响应给的基址再拼。
 *
 * 站外图片（图床、外链）原样返回：那些地址跟附件域名没关系。
 */
export function rehostLegacyAttachment(src: string, base: string): string {
  const match = ABSOLUTE_ATTACHMENT.exec(src)
  if (match === null) return src
  // 端口不影响判定，取主机名部分即可
  const host = match[1]!.split(':')[0]!
  if (!NGA_ATTACH_HOST.test(host)) return src
  return `${base}/${match[2]!}`
}

/**
 * 换成缩略图地址（22 号票的「图片加载策略」省流量那两档）。
 *
 * 只对挂在当前附件基址下的图动手：站外图床没有这套后缀约定，加上去就是 404。
 * 先剥再加，所以对已经是缩略图的地址是幂等的。
 */
export function thumbnailUrl(url: string, base: string): string {
  if (!url.startsWith(`${base}/`)) return url
  return `${stripThumbnailSuffix(url)}${THUMBNAIL_SUFFIX}`
}

/** 落盘文件名里不敢要的字符（Android 文件系统 + MediaStore 的交集）。 */
const UNSAFE_FILENAME_CHARS = /[\\/:*?"<>|\s%#]+/g

/** 常见图片扩展名 → MIME。落不进表的按 jpeg 兜底——NGA 附件绝大多数是 jpg。 */
const IMAGE_MIME_TYPES: Readonly<Record<string, string>> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  avif: 'image/avif',
}

/**
 * 从图片地址取一个能直接落盘的文件名（保存到相册 / 分享的下载中转都用它）。
 *
 * 取路径最后一段、去掉查询串、剥掉缩略图后缀（存的是原图，名字不该带 `.thumb`）、
 * 替换文件系统不认的字符；没有认得出的图片扩展名时补 `.jpg`——
 * `MediaStore` 靠扩展名认类型，裸哈希名会存成「未知文件」进不了相册。
 */
export function imageFileName(url: string): string {
  const path = url.split(/[?#]/)[0] ?? url
  const lastSegment = path.slice(path.lastIndexOf('/') + 1)
  const base = stripThumbnailSuffix(decodeURIComponentSafe(lastSegment)).replace(
    UNSAFE_FILENAME_CHARS,
    '_',
  )
  const named = base === '' || base === '_' ? `image-${hashOf(url)}` : base
  return extensionOf(named) === undefined ? `${named}.jpg` : named
}

/** 按文件名猜 MIME（系统分享面板要它来挑目标应用）。 */
export function imageMimeType(fileName: string): string {
  const extension = extensionOf(fileName)
  return (extension === undefined ? undefined : IMAGE_MIME_TYPES[extension]) ?? 'image/jpeg'
}

/** 认得出的图片扩展名（小写），认不出返回 undefined。 */
function extensionOf(fileName: string): string | undefined {
  const dot = fileName.lastIndexOf('.')
  if (dot <= 0) return undefined
  const extension = fileName.slice(dot + 1).toLowerCase()
  return extension in IMAGE_MIME_TYPES ? extension : undefined
}

/** 站外图床会出现 `%20` 这类转义；转不动的（裸 `%`）原样保留，不能抛。 */
function decodeURIComponentSafe(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

/** 文件名兜底用的短哈希（djb2）。只求稳定可辨，不求防碰撞。 */
function hashOf(value: string): string {
  let hash = 5381
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) + hash + value.charCodeAt(index)) >>> 0
  }
  return hash.toString(36)
}

export interface AttachmentUrlOptions {
  /** `normalizeAttachBase` 的产物 */
  readonly base: string
  /** 所在楼层的发帖时间（秒级 unix），补日期目录用；没有就不猜 */
  readonly postedAt?: number
}

/** 把 AST 里的资源引用拼成能直接喂给 `<Image>` 的地址。 */
export function attachmentUrl(ref: AttachmentRef, options: AttachmentUrlOptions): string {
  if (!ref.needsAttachBase) return rehostLegacyAttachment(ref.src, options.base)

  let path = stripThumbnailSuffix(ref.src).replace(/^\/+/, '')
  if (options.postedAt !== undefined && !DATED_PATH_PATTERN.test(path)) {
    path = `${datedDirectory(options.postedAt)}/${path}`
  }
  return `${options.base}/${path}`
}
