/**
 * 从 WebView 的 cookie 串里认出登录凭证（API 文档 §0.2）。
 *
 * 登录成功的标志是同时出现 `ngaPassportUid` 与 `ngaPassportCid` 两个 Cookie；
 * 登录**前**页面就会挂着占位值（uid 可能是 `guest`、cid 可能是短垃圾串），
 * 所以光看键存在不够，还得按取值形状过滤，否则轮询会在登录前误报。
 */

/** 解析 `k=v; k2=v2` 形式的 cookie 串。值里允许再出现 `=`（base64 之类）。 */
export function parseCookieString(cookie: string): ReadonlyMap<string, string> {
  const jar = new Map<string, string>()
  for (const part of cookie.split(';')) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    const key = part.slice(0, eq).trim()
    const value = part.slice(eq + 1).trim()
    if (key !== '') jar.set(key, value)
  }
  return jar
}

export interface LoginCookies {
  readonly uid: string
  /** 会话凭证 ngaPassportCid */
  readonly cid: string
  /** 原始 ngaPassportUrlencodedUname（GBK 双重 URLEncode），可能缺失 */
  readonly urlencodedUname: string | null
}

/** uid 必须是纯数字——排掉 `guest` 这类未登录占位。 */
const UID_PATTERN = /^\d+$/
/** cid 是长随机串（实测 40 位字母数字）；短值/空值视为还没登录完。 */
const CID_PATTERN = /^[0-9A-Za-z_-]{16,}$/

/** cookie 串里有合法凭证就取出来，否则 null（表示登录还没完成）。 */
export function extractLoginCookies(cookie: string): LoginCookies | null {
  const jar = parseCookieString(cookie)
  const uid = jar.get('ngaPassportUid') ?? ''
  const cid = jar.get('ngaPassportCid') ?? ''
  if (!UID_PATTERN.test(uid) || !CID_PATTERN.test(cid)) return null
  return { uid, cid, urlencodedUname: jar.get('ngaPassportUrlencodedUname') ?? null }
}
